// JNI bridge that puts libass inside the ExoPlayer playback session.
//
// Why this exists
// ---------------
// media3 deliberately does not typeset ASS/SSA: google/ExoPlayer#8435
// ("Support SSA/ASS styling") has been open since 2021 with the styling
// removed from the original PR, and media3's SSA parser flattens the file
// into plain unstyled cues. KBStream's ExoPlayer engine therefore renders an
// .ass sidecar as a wall of unpositioned text, while the MPV engine (which
// statically links libass) typesets the same file correctly.
//
// This bridge gives the ExoPlayer session the same renderer: Kotlin feeds a
// track in, ticks the playback clock, and libass blends its ASS_Image list
// into an ARGB_8888 Bitmap that the player draws over the video surface.
//
// Ownership rule for libass symbols
// ---------------------------------
// The MPV engine's libmpv.so statically links its own libass/FreeType/
// HarfBuzz and exports ~850 ass_/FT_/hb_ symbols. That is not a conflict
// here, for the same reason libffmpegJNI.so can sit next to libmpv while
// shipping its own libavcodec: this .so is a separate, uniquely named
// library (libassjni.so) with its own static copies, so every reference
// resolves inside its own load scope and no soname is duplicated in the
// APK. Nothing here reaches into or replaces libmpv's copy.
//
// Threading: one handle is driven from the player's main thread only
// (viewport, load, render), so a single mutex is enough to keep a
// concurrent release() from freeing a handle mid-render.

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <cstdarg>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <new>
#include <string>
#include <vector>

extern "C" {
#include <ass/ass.h>
}

// libass 0.17 names the default font provider with an enum. The define keeps
// this compiling against the older int-valued spelling as well, so a future
// libass bump cannot turn into a build break over one constant.
#ifndef ASS_FONTPROVIDER_AUTODETECT
#define ASS_FONTPROVIDER_AUTODETECT 1
#endif

#define LOG_TAG "AssJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

/** One renderer instance: the whole per-track native state. */
struct AssHandle {
    ASS_Library* library = nullptr;
    ASS_Renderer* renderer = nullptr;
    ASS_Track* track = nullptr;
    int width = 0;
    int height = 0;
    /** Serialises release() against a render in flight. */
    std::mutex lock;
};

void logCallback(int level, const char* fmt, va_list args, void* /*data*/) {
    // libass is chatty at INFO for every font lookup; only warnings and
    // errors are worth a logcat line in a TV app.
    if (level > ASS_LOG_WARN) return;
    char buffer[512];
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    LOGW("libass: %s", buffer);
}

/**
 * Source-over blend of one libass coverage bitmap into a straight-alpha
 * (non-premultiplied) ARGB_8888 row.
 *
 * ASS_Image::color is AABBGGRR - alpha in the most significant byte, then
 * blue, green, red. A genuinely opaque ASS colour (the common case) has 0xFF
 * there, and the per-pixel coverage byte modulates it.
 */
inline uint32_t blendPixel(uint32_t dst, uint32_t r, uint32_t g, uint32_t b, uint32_t a) {
    if (a == 0) return dst;
    if (a >= 255) return 0xFF000000u | (r << 16) | (g << 8) | b;
    const uint32_t inv = 255u - a;
    const uint32_t dr = (dst >> 16) & 0xFFu;
    const uint32_t dg = (dst >> 8) & 0xFFu;
    const uint32_t db = dst & 0xFFu;
    const uint32_t nr = (r * a + dr * inv + 127u) / 255u;
    const uint32_t ng = (g * a + dg * inv + 127u) / 255u;
    const uint32_t nb = (b * a + db * inv + 127u) / 255u;
    return 0xFF000000u | (nr << 16) | (ng << 8) | nb;
}

AssHandle* asHandle(jlong handle) {
    return reinterpret_cast<AssHandle*>(static_cast<intptr_t>(handle));
}

/** Tears the instance down. Caller must hold the lock (or own the handle). */
void destroyLocked(AssHandle* h) {
    if (h == nullptr) return;
    if (h->track != nullptr && h->library != nullptr) {
        ass_free_track(h->track);
        h->track = nullptr;
    }
    if (h->renderer != nullptr) {
        ass_renderer_done(h->renderer);
        h->renderer = nullptr;
    }
    if (h->library != nullptr) {
        ass_library_done(h->library);
        h->library = nullptr;
    }
}

}  // namespace

/**
 * Creates a renderer. [configPath] and [cacheDir] are handed to fontconfig
 * (FONTCONFIG_FILE / FONTCONFIG_PATH / FONTCONFIG_CACHE) so libass can
 * resolve the system font directory: an Android build has no fontconfig
 * config and no writable /var/cache, and without one libass can only use the
 * fonts an .mkv embeds, which is exactly the case this renderer is here to
 * cover. Either may be null, in which case /system/fonts is still reachable
 * through the config the script ships next to it.
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_kennyb1201_kbstream_ui_player_AssNative_nativeCreate(
    JNIEnv* env, jclass /*clazz*/, jstring configPath, jstring cacheDir) {
    if (configPath != nullptr) {
        const char* path = env->GetStringUTFChars(configPath, nullptr);
        if (path != nullptr) {
            setenv("FONTCONFIG_FILE", path, 1);
            LOGI("fontconfig file: %s", path);
            env->ReleaseStringUTFChars(configPath, path);
        }
    }
    if (cacheDir != nullptr) {
        const char* cache = env->GetStringUTFChars(cacheDir, nullptr);
        if (cache != nullptr) {
            // XDG_CACHE_HOME is the only lever fontconfig has for where it
            // writes its cache; it is also what the default <cachedir> is
            // resolved against when HOME is unset (always, on Android).
            setenv("XDG_CACHE_HOME", cache, 1);
            env->ReleaseStringUTFChars(cacheDir, cache);
        }
    }

    auto* h = new (std::nothrow) AssHandle();
    if (h == nullptr) return 0;

    h->library = ass_library_init();
    if (h->library == nullptr) {
        LOGW("ass_library_init failed");
        delete h;
        return 0;
    }
    ass_set_message_cb(h->library, logCallback, nullptr);

    h->renderer = ass_renderer_init(h->library);
    if (h->renderer == nullptr) {
        LOGW("ass_renderer_init failed");
        ass_library_done(h->library);
        delete h;
        return 0;
    }

    // "sans-serif" is only a fallback name for a track with no styling; a
    // real fansub file names its own fonts, which fontconfig then resolves
    // out of /system/fonts (or the ones attached below).
    ass_set_fonts(h->renderer, nullptr, "sans-serif",
                  ASS_FONTPROVIDER_AUTODETECT, nullptr, 1);
    // No letterbox margins: KBStream draws the frame over the whole view, so
    // libass must lay out against the video's own rectangle.
    ass_set_use_margins(h->renderer, 0);
    ass_set_cache_limits(h->renderer, 0, 32);

    return static_cast<jlong>(reinterpret_cast<intptr_t>(h));
}

extern "C" JNIEXPORT void JNICALL
Java_com_kennyb1201_kbstream_ui_player_AssNative_nativeDestroy(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    AssHandle* h = asHandle(handle);
    if (h == nullptr) return;
    h->lock.lock();
    destroyLocked(h);
    h->lock.unlock();
    delete h;
}

/**
 * Attaches one font file (an .mkv's embedded attachment, or a system font
 * picked up by the Kotlin side). libass copies the bytes, so [data] is free
 * to be released the moment this returns.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_kennyb1201_kbstream_ui_player_AssNative_nativeAddFont(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jstring name, jbyteArray data) {
    AssHandle* h = asHandle(handle);
    if (h == nullptr || h->library == nullptr || data == nullptr) return;

    std::lock_guard<std::mutex> guard(h->lock);
    if (h->library == nullptr) return;

    const char* cname = env->GetStringUTFChars(name, nullptr);
    const jsize size = env->GetArrayLength(data);
    if (cname == nullptr || size <= 0) {
        if (cname != nullptr) env->ReleaseStringUTFChars(name, cname);
        return;
    }

    std::vector<char> bytes(static_cast<size_t>(size));
    env->GetByteArrayRegion(data, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
    ass_add_font(h->library, cname, bytes.data(), size);

    env->ReleaseStringUTFChars(name, cname);
}

/**
 * Sets the layout size. libass positions and scales everything against this
 * rectangle, so the caller passes the video's own pixel size (clamped) and
 * the view scales the result.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_kennyb1201_kbstream_ui_player_AssNative_nativeSetViewport(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint width, jint height) {
    AssHandle* h = asHandle(handle);
    if (h == nullptr || width <= 0 || height <= 0) return;
    std::lock_guard<std::mutex> guard(h->lock);
    if (h->renderer == nullptr) return;
    if (h->width == width && h->height == height) return;
    h->width = width;
    h->height = height;
    ass_set_frame_size(h->renderer, width, height);
    ass_set_storage_size(h->renderer, width, height);
}

/**
 * Loads a whole subtitle file. [content] is UTF-8 and must be the complete
 * script, header sections included; Kotlin normalises files that arrive
 * without one (see AssSubtitleSource.normalize). Replaces any previous
 * track.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_kennyb1201_kbstream_ui_player_AssNative_nativeLoadTrack(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jstring content) {
    AssHandle* h = asHandle(handle);
    if (h == nullptr || content == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> guard(h->lock);
    if (h->library == nullptr) return JNI_FALSE;

    const char* utf = env->GetStringUTFChars(content, nullptr);
    if (utf == nullptr) return JNI_FALSE;
    const jsize size = env->GetStringUTFLength(content);

    if (h->track != nullptr) {
        ass_free_track(h->track);
        h->track = nullptr;
    }
    // charset == nullptr: the script is UTF-8, which is what an .ass file is
    // authored in and what Kotlin decoded it as.
    h->track = ass_read_memory(h->library, const_cast<char*>(utf),
                               static_cast<size_t>(size), nullptr);
    env->ReleaseStringUTFChars(content, utf);

    if (h->track == nullptr) {
        LOGW("ass_read_memory produced no track");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

/**
 * Renders one frame into [bitmap], which the caller must have allocated as a
 * mutable ARGB_8888 bitmap matching the viewport.
 *
 * Returns whether anything was drawn. The bitmap is cleared first on every
 * call: libass only returns the images that exist at [timeMs], so a frame
 * with no subtitle must actively erase the previous one.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_kennyb1201_kbstream_ui_player_AssNative_nativeRenderFrame(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jlong timeMs, jobject bitmap) {
    AssHandle* h = asHandle(handle);
    if (h == nullptr || bitmap == nullptr) return JNI_FALSE;

    std::lock_guard<std::mutex> guard(h->lock);
    if (h->renderer == nullptr || h->track == nullptr) return JNI_FALSE;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGW("bitmap is not ARGB_8888");
        return JNI_FALSE;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }

    // AndroidBitmap_lockPixels does not guarantee zeroed memory, and libass
    // only paints the images present this frame.
    const size_t rowBytes = static_cast<size_t>(info.stride);
    std::memset(pixels, 0, rowBytes * static_cast<size_t>(info.height));

    int detectChange = 0;
    ASS_Image* images = ass_render_frame(
        h->renderer, h->track, static_cast<long long>(timeMs), &detectChange);

    auto* base = static_cast<uint8_t*>(pixels);
    const int stridePx = static_cast<int>(rowBytes / 4);
    const int clipW = static_cast<int>(info.width);
    const int clipH = static_cast<int>(info.height);

    int drawn = 0;
    for (ASS_Image* img = images; img != nullptr; img = img->next) {
        if (img->w <= 0 || img->h <= 0 || img->bitmap == nullptr) continue;
        const uint32_t color = img->color;
        const uint32_t a = (color >> 24) & 0xFFu;
        const uint32_t b = (color >> 16) & 0xFFu;
        const uint32_t g = (color >> 8) & 0xFFu;
        const uint32_t r = color & 0xFFu;
        if (a == 0) continue;

        for (int y = 0; y < img->h; y++) {
            const int dy = img->dst_y + y;
            if (dy < 0 || dy >= clipH) continue;
            auto* row = reinterpret_cast<uint32_t*>(base + static_cast<size_t>(dy) * rowBytes);
            const unsigned char* src = img->bitmap + static_cast<size_t>(y) * img->stride;
            for (int x = 0; x < img->w; x++) {
                const int dx = img->dst_x + x;
                if (dx < 0 || dx >= clipW) continue;
                const uint32_t coverage = src[x];
                if (coverage == 0) continue;
                row[dx] = blendPixel(row[dx], r, g, b, a * coverage / 255u);
                drawn++;
            }
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return drawn > 0 ? JNI_TRUE : JNI_FALSE;
}
