/*
 * dovi_bridge.c -- the JNI bridge between KBStream's player and libdovi.
 *
 * Why this exists
 * ---------------
 * The ExoPlayer engine's Dolby Vision handling rewrites a Player 7 RPU into a
 * Profile 8.1 one by hand (see DolbyVisionCompat.rewriteRpuTo81 and the VPS/SPS
 * rewriters beside it). On the boxes this app is field-tested on, the MediaTek
 * decoder stalls on every variant of that rewrite -- relabel, no-relabel, VPS
 * rewrite -- and presents as audio over a black screen. libdovi parses the RPU
 * properly and can produce the 8.1 version of it itself, which is what the
 * reference player does and what renders on the same box, so the bytes of that
 * rewrite should come from libdovi rather than from a hand-rolled parser.
 *
 * This bridge is deliberately narrow: it hands libdovi the RPU NALs and writes
 * back what libdovi produced, and it strips a whole sample on request
 * (strip_dovi_rpu / strip_hdr10_plus in process_sample): that is the "Strip All"
 * path, the variant that stalls the MediaTek decoder. The codec-string rewrite
 * and the surgical, message-level HDR10+ removal stay on the Kotlin side, where
 * they are already tuned and pinned by tests (see DolbyVisionCompat), which also
 * keeps its own NAL scan as the fallback for a build without these libraries.
 *
 * Requirements
 * ------------
 * Built by scripts/build_dovi.sh, which produces, per ABI:
 *
 *   libdovi.so         -- quietvoid/dovi_tool's `dolby_vision` crate built with
 *                         its C API (`--features capi`). The C API's generated
 *                         header is <libdovi/rpu_parser.h>.
 *   libdovi_bridge.so  -- this file, linked against libdovi.so and liblog.
 *
 * Both land in app/src/main/jniLibs/<abi>/, the default Gradle source set, so
 * the APK packages them with no build-file wiring. Nothing in the app requires
 * them to run: DoviBridge.available is false when they are absent and the Kotlin
 * RPU rewrite stays in charge (that is the fallback the DV settings already have
 * for local builds).
 *
 * Notes on the C API, pinned to dolby_vision 3.4.0
 * ------------------------------------------------
 *  - dovi_parse_unspec62_nalu() takes the NAL's bytes INCLUDING its two-byte
 *    NAL header (0x7C 0x01 for an RPU), with no start code and no length field,
 *    and returns an opaque object that must be freed with dovi_rpu_free(). A
 *    parse failure is reported through dovi_rpu_get_error()/a null header, not
 *    through the return value, and the object still has to be freed.
 *  - dovi_rpu_get_header() allocates: free it with dovi_rpu_free_header(). Its
 *    `guessed_profile` is the profile number (4, 5, 7 or 8; 0 when the header
 *    does not identify one).
 *  - dovi_convert_rpu_with_mode() returns 0 on success. The mode numbers are
 *    the crate's ConversionMode values; see DoviBridge.kt for what this pinned
 *    version does with them (its C API comments disagree with its own From<u8>).
 *  - dovi_write_unspec62_nalu() returns a heap buffer (free it with
 *    dovi_data_free()) whose bytes INCLUDE the two-byte 0x7C 0x01 header.
 *
 * Every path that fails leaves the caller on its own Kotlin rewrite: this file
 * never invents RPU bytes, and a conversion that does not succeed forwards the
 * original NAL untouched rather than replacing it with something unparsed.
 */

#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <android/log.h>

#if defined(__has_include)
#if __has_include(<libdovi/rpu_parser.h>)
#include <libdovi/rpu_parser.h>
#else
#include <rpu_parser.h>
#endif
#else
#include <libdovi/rpu_parser.h>
#endif

#define LOG_TAG "PLAYER_DV"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

/* NAL_FORMAT_* / return codes: mirrored in DoviBridge.kt, which documents them. */
#define NAL_FORMAT_ANNEX_B 0
#define NAL_FORMAT_LENGTH_DELIMITED 1
#define NAL_FORMAT_SINGLE_NAL 2

#define DV_NOT_DOLBY_VISION (-1)
#define DV_PARSE_ERROR (-2)
#define DV_BUFFER_TOO_SMALL (-3)

/* HEVC NAL types this bridge cares about. */
#define NAL_TYPE_PREFIX_SEI 39
#define NAL_TYPE_SUFFIX_SEI 40
#define NAL_TYPE_DV_RPU 62
#define NAL_TYPE_DV_EL 63

/* An RPU/EL NAL's own header, which libdovi also reads and writes. */
#define DV_NAL_HEADER_BYTES 2

static int hevc_nal_type(const uint8_t *p, size_t len) {
    if (len < 2) return -1;
    return (p[0] >> 1) & 0x3F;
}

static int hevc_layer_id(const uint8_t *p, size_t len) {
    if (len < 2) return 0;
    return ((p[0] & 0x01) << 5) | ((p[1] & 0xF8) >> 3);
}

/*
 * Whether this SEI NAL carries an HDR10+ (ST 2094-40) message: payload type 4
 * (user_data_registered_itu_t_t35) whose T.35 payload names country code 0xB5
 * with terminal provider 0x003C (Samsung).
 *
 * The walk is over the NAL as it sits in the stream, so it does NOT remove
 * emulation-prevention bytes first: a message split by one is not recognized.
 * That is deliberate rather than overlooked -- this flag is the COARSE strip
 * (the whole SEI NAL goes, as the reference transformer does), which is what the
 * "Strip All" path and the plain-HDR10+ sniff path ask for. Kotlin keeps a
 * surgical, emulation-prevention-aware pass that removes only the HDR10+ message
 * and leaves the rest of a shared SEI NAL alone; that one is the fallback for a
 * build without these libraries.
 */
static bool sei_carries_hdr10_plus(const uint8_t *p, size_t len) {
    size_t i = DV_NAL_HEADER_BYTES;
    while (i < len) {
        size_t type = 0;
        while (i < len && p[i] == 0xFF) {
            type += 255;
            i++;
        }
        if (i >= len) return false;
        type += p[i++];
        /* rbsp_trailing_bits (0x80) or a zero type: nothing after this. */
        if (type == 0x80 || type == 0) return false;

        size_t size = 0;
        while (i < len && p[i] == 0xFF) {
            size += 255;
            i++;
        }
        if (i >= len) return false;
        size += p[i++];

        if (type == 4 && size >= 5 && i + 5 <= len) {
            if (p[i] == 0xB5 && p[i + 1] == 0x00 && p[i + 2] == 0x3C &&
                p[i + 3] == 0x00 && p[i + 4] == 0x01) {
                return true;
            }
        }
        if (i + size > len) return false;
        i += size;
    }
    return false;
}

/*
 * The Dolby Vision profile of the RPU NAL at [nal, nal+len), or
 * DV_NOT_DOLBY_VISION when it parses but identifies no profile, or
 * DV_PARSE_ERROR when it does not parse as an RPU at all.
 */
static int detect_profile(const uint8_t *nal, size_t len) {
    DoviRpuOpaque *rpu = dovi_parse_unspec62_nalu(nal, len);
    if (rpu == NULL) return DV_PARSE_ERROR;

    const DoviRpuDataHeader *header = dovi_rpu_get_header(rpu);
    int result = DV_PARSE_ERROR;
    if (header != NULL) {
        int guessed = (int)header->guessed_profile;
        result = (guessed == 4 || guessed == 5 || guessed == 7 || guessed == 8)
                     ? guessed
                     : DV_NOT_DOLBY_VISION;
        dovi_rpu_free_header(header);
    }
    dovi_rpu_free(rpu);
    return result;
}

/*
 * Converts the RPU NAL at [nal, nal+len) with [mode] and writes the new NAL --
 * header included, as libdovi produces it -- into dst (capacity dst_cap).
 *
 * Returns the bytes written, 0 when there was nothing to convert (not an RPU,
 * or a profile the caller did not ask for), or a negative error code. The
 * caller keeps the original NAL on anything but a positive return.
 */
static int convert_rpu(
    const uint8_t *nal,
    size_t len,
    uint8_t mode,
    int profile,
    uint8_t *dst,
    size_t dst_cap
) {
    DoviRpuOpaque *rpu = dovi_parse_unspec62_nalu(nal, len);
    if (rpu == NULL) return DV_PARSE_ERROR;

    const DoviRpuDataHeader *header = dovi_rpu_get_header(rpu);
    int result = 0;
    if (header != NULL) {
        int guessed = (int)header->guessed_profile;
        bool wanted = (profile == 0) || (guessed == profile);
        bool convertible = (guessed == 5 || guessed == 7 || guessed == 8);
        if (wanted && convertible && dovi_convert_rpu_with_mode(rpu, mode) == 0) {
            const DoviData *written = dovi_write_unspec62_nalu(rpu);
            if (written != NULL && written->len > 0) {
                if ((size_t)written->len > dst_cap) {
                    result = DV_BUFFER_TOO_SMALL;
                } else {
                    memcpy(dst, written->data, (size_t)written->len);
                    result = (int)written->len;
                }
            }
            if (written != NULL) dovi_data_free(written);
        }
        dovi_rpu_free_header(header);
    }
    dovi_rpu_free(rpu);
    return result;
}

/* The index of the next 00 00 01 start code at or after [from), or -1. */
static int find_start_code(const uint8_t *buf, int from, int len) {
    for (int i = from; i + 2 < len; i++) {
        if (buf[i] == 0x00 && buf[i + 1] == 0x00 && buf[i + 2] == 0x01) return i;
    }
    return -1;
}

static void write_length(uint8_t *dst, int value, int length_bytes) {
    for (int i = 0; i < length_bytes; i++) {
        dst[length_bytes - 1 - i] = (uint8_t)((value >> (8 * i)) & 0xFF);
    }
}

/*
 * One pass over a sample (or a single NAL, for NAL_FORMAT_SINGLE_NAL).
 *
 * Returns for the framed formats the number of bytes written to out, which is
 * the input length when nothing changed; for NAL_FORMAT_SINGLE_NAL it returns 0
 * when the one NAL was not converted (that caller is asking "did you convert
 * it?") and the new NAL's size when it was. Negative on error.
 */
static int process_sample(
    const uint8_t *in,
    int len,
    int format,
    int length_field_bytes,
    uint8_t *out,
    int out_cap,
    bool convert_dovi,
    uint8_t dovi_mode,
    int dovi_profile,
    bool strip_dovi_rpu,
    bool strip_hdr10_plus
) {
    if (format == NAL_FORMAT_SINGLE_NAL) {
        if (!convert_dovi) return 0;
        if (hevc_nal_type(in, (size_t)len) != NAL_TYPE_DV_RPU) return 0;
        int n = convert_rpu(in, (size_t)len, dovi_mode, dovi_profile, out, (size_t)out_cap);
        return n > 0 ? n : 0;
    }

    bool length_delimited = (format == NAL_FORMAT_LENGTH_DELIMITED);
    if (length_delimited && (length_field_bytes < 1 || length_field_bytes > 4)) {
        return DV_PARSE_ERROR;
    }

    int read = 0;
    int write = 0;
    while (read < len) {
        int code;
        int header;
        int nal_end;
        if (length_delimited) {
            if (read + length_field_bytes > len) return DV_PARSE_ERROR;
            int nal_size = 0;
            for (int i = 0; i < length_field_bytes; i++) {
                nal_size = (nal_size << 8) | in[read + i];
            }
            if (nal_size <= 0) return DV_PARSE_ERROR;
            code = read;
            header = read + length_field_bytes;
            nal_end = header + nal_size;
            if (nal_end > len) return DV_PARSE_ERROR;
        } else {
            code = find_start_code(in, read, len);
            if (code < 0) {
                /* Trailing bytes with no start code: keep them verbatim. */
                if (out_cap - write < len - read) return DV_BUFFER_TOO_SMALL;
                memcpy(out + write, in + read, (size_t)(len - read));
                write += len - read;
                break;
            }
            int code_len =
                (code + 3 < len && in[code + 2] == 0x00 && in[code + 3] == 0x01) ? 4 : 3;
            header = code + code_len;
            nal_end = find_start_code(in, header, len);
            if (nal_end < 0) nal_end = len;
        }

        int framing = header - code;
        int nal_len = nal_end - header;
        int type = hevc_nal_type(in + header, (size_t)nal_len);
        int layer_id = hevc_layer_id(in + header, (size_t)nal_len);

        /* A single-layer output cannot carry the enhancement layer, and an RPU
         * next to a rewritten codec string is what the decoder stalls on. */
        if (strip_dovi_rpu &&
            (type == NAL_TYPE_DV_RPU || type == NAL_TYPE_DV_EL || layer_id > 0)) {
            read = nal_end;
            continue;
        }

        if (convert_dovi && type == NAL_TYPE_DV_RPU) {
            int dst = write + framing;
            int n = convert_rpu(
                in + header,
                (size_t)nal_len,
                dovi_mode,
                dovi_profile,
                out + dst,
                (size_t)(out_cap - dst)
            );
            if (n > 0) {
                memcpy(out + write, in + code, (size_t)framing);
                if (length_delimited) write_length(out + write, n, length_field_bytes);
                write += framing + n;
                read = nal_end;
                continue;
            }
            if (n == DV_BUFFER_TOO_SMALL) return DV_BUFFER_TOO_SMALL;
            /* 0 or a parse error: forward the original NAL below. */
        }

        if (strip_hdr10_plus && (type == NAL_TYPE_PREFIX_SEI || type == NAL_TYPE_SUFFIX_SEI) &&
            sei_carries_hdr10_plus(in + header, (size_t)nal_len)) {
            read = nal_end;
            continue;
        }

        if (out_cap - write < nal_end - code) return DV_BUFFER_TOO_SMALL;
        memcpy(out + write, in + code, (size_t)(nal_end - code));
        write += nal_end - code;
        read = nal_end;
    }
    return write;
}

JNIEXPORT jint JNICALL
Java_com_kennyb1201_kbstream_ui_player_DoviBridge_nativeDetectProfile(
    JNIEnv *env,
    jobject thiz,
    jbyteArray payload,
    jint offset,
    jint length
) {
    (void)thiz;
    if (payload == NULL || offset < 0 || length < 2) return DV_PARSE_ERROR;

    jsize array_len = (*env)->GetArrayLength(env, payload);
    if (offset > array_len || length > array_len - offset) return DV_PARSE_ERROR;

    uint8_t *in = (uint8_t *)malloc((size_t)length);
    if (in == NULL) return DV_PARSE_ERROR;
    (*env)->GetByteArrayRegion(env, payload, offset, length, (jbyte *)in);

    int result = detect_profile(in, (size_t)length);
    free(in);
    return (jint)result;
}

JNIEXPORT jint JNICALL
Java_com_kennyb1201_kbstream_ui_player_DoviBridge_nativeProcessVideoSample(
    JNIEnv *env,
    jobject thiz,
    jbyteArray sample,
    jint sample_len,
    jint nal_format,
    jint nal_length_field_length,
    jbyteArray out_buffer,
    jboolean convert_dovi,
    jint dovi_mode,
    jint dovi_profile,
    jboolean strip_dovi_rpu,
    jboolean strip_hdr10_plus
) {
    (void)thiz;
    if (sample == NULL || out_buffer == NULL) return DV_PARSE_ERROR;

    jsize in_cap = (*env)->GetArrayLength(env, sample);
    jsize out_cap = (*env)->GetArrayLength(env, out_buffer);
    if (sample_len < 0) return DV_PARSE_ERROR;
    if (sample_len > in_cap) sample_len = in_cap;
    if (sample_len == 0) return 0;
    if (nal_format == NAL_FORMAT_SINGLE_NAL && (jsize)sample_len > out_cap) {
        return DV_BUFFER_TOO_SMALL;
    }

    uint8_t *in = (uint8_t *)malloc((size_t)sample_len);
    uint8_t *out = (uint8_t *)malloc(out_cap > 0 ? (size_t)out_cap : 1);
    if (in == NULL || out == NULL) {
        free(in);
        free(out);
        return DV_PARSE_ERROR;
    }
    (*env)->GetByteArrayRegion(env, sample, 0, sample_len, (jbyte *)in);

    int written = process_sample(
        in,
        (int)sample_len,
        (int)nal_format,
        (int)nal_length_field_length,
        out,
        (int)out_cap,
        convert_dovi == JNI_TRUE,
        (uint8_t)dovi_mode,
        (int)dovi_profile,
        strip_dovi_rpu == JNI_TRUE,
        strip_hdr10_plus == JNI_TRUE
    );

    if (written > 0 && written <= (int)out_cap) {
        (*env)->SetByteArrayRegion(env, out_buffer, 0, (jsize)written, (jbyte *)out);
    }
    free(in);
    free(out);
    return (jint)written;
}
