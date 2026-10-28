package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.settings.AppPreferences

/**
 * Which video output the MPV engine opens with.
 *
 * It was a per-device table while two boxes needed the MediaCodec workaround by
 * default; the 2026-10-28 field test confirmed the GPU path on both of them, so
 * `auto` is one output for every device and the workaround is a manual choice
 * only (see [resolve]). The history below is kept because it is why the table
 * existed at all, and because a regression on either box would re-open it.
 *
 * The 6.51 mpvlibAndroid swap moved the black screen rather than removing it:
 * the Fire TV Stick 4K Plus (MediaTek, `karat`) lost its picture — audio plays,
 * frames decode, and no frame ever reaches the Surface — and the Onn 4K Plus,
 * which the swap appeared to fix, was black-screening again by the 11 October
 * field test. The line those reports used to quote for it,
 * `updateAcquireFence: Did not find frame`, is the platform's own `libgui`
 * frame-timestamp bookkeeping (it is absent from every native library in
 * `mpvlib-1.1.2.aar`, exactly as "Could not find corresponding native window
 * for surface" is), and it reports frames being QUEUED, not lost — so no fix
 * was ever going to be read off it. See
 * [MpvPlayerView.publishAttachedSurfaceSize], which is where the window size
 * the GPU output was never told is now published, at the attach itself. That is a property of
 * the video output, not of the file, so the settings around `vo` have to be
 * chosen per device. This is that choice, as one pure function of the mode and
 * the three `Build` fields it is made from, so it can be pinned by a unit test
 * rather than by two TVs.
 *
 * The GPU output is `gpu-next` (see [OPENGL]): the AAR this build links is
 * built for it, and it is the output whose libplacebo performs the Dolby Vision
 * reshape. The stored mode that reaches it is still the one on disk —
 * [AppPreferences.MPV_VO_GPU] is the user-facing name of this output and is
 * deliberately not renamed, because it is a value written to disk (a rename
 * would reset every install's setting and orphan what was stored).
 *
 * A resolved [Settings] is applied as mpv OPTIONS before `init()` (see
 * [MpvPlayerView.applyOptions]) and is never re-applied afterwards. It used to
 * be read back as a PROPERTY on every surface re-attach, to restore a `vo` that
 * teardown had nulled - which re-initialized the video output after the surface
 * had been attached to the old one, and black-screened the engine on every mode
 * (see [MpvPlayerView.attachSurface]). The VO is now chosen once and the
 * surface cycle touches only the surface, so this resolution has exactly one
 * caller - which is why it lives here rather than inline in that one.
 */
internal object MpvVideoOutput {

    /** The mpv options one video output needs, and nothing more. */
    internal data class Settings(
        /** The `vo` implementation mpv opens with. */
        val vo: String,
        /** `gpu-context`, or null when this output has no GPU context to name. */
        val gpuContext: String?,
        /** `gpu-api`, or null when this output has no GPU API to name. */
        val gpuApi: String?,
        /** Whether `opengl-es=yes` has to be set for this output. */
        val openglEs: Boolean
    )

    /**
     * The GPU path: `vo=gpu-next` on the Android context, with `gpu-api` left on
     * mpv's default `auto` (instead of the `opengl` pin that had been set for
     * the old library). Unsetting it is done by the caller
     * ([MpvPlayerView.applyOptions]) doing nothing when [gpuApi] is null — mpv
     * resolves `auto` itself and picks Vulkan or OpenGL from the context.
     *
     * `gpu-next` and not `gpu`, since the 2026-10-15 library swap: the AAR this
     * build links is BUILT for it — its libplacebo carries `-Ddovi=enabled` and
     * its libdovi is statically linked, which is the whole point of the swap —
     * and `gpu-next` is libplacebo's own renderer, the one that performs the
     * Dolby Vision reshape and reads the HDR metadata off the RPU. `vo=gpu`'s
     * libplacebo path does not do the DV reshaping, so on the new library it
     * would decode DV titles as their HDR10 base layer only — the exact
     * limitation the swap was made to remove. `gpu-context=android` and
     * `android-surface-size` are the same for both outputs.
     *
     * This is what `auto` resolves to on EVERY box since the 2026-10-28 field
     * confirmation: the two boxes the 6.51/library swap had stalling on this
     * path (Fire TV Stick 4K Plus and Onn 4K Plus) now deliver frames on it, so
     * the MediaCodec workaround is no longer a default and is reached only when
     * the viewer picks it (see [resolve] and [MEDIACODEC_EMBED]).
     */
    private val OPENGL = Settings(
        vo = AppPreferences.MPV_VO_GPU_NEXT,
        gpuContext = "android",
        gpuApi = null, // was "opengl" — unset: the mpv default (`auto`) applies
        openglEs = true
    )

    /**
     * Bypass the GPU entirely: MediaCodec decodes straight into the Surface.
     * No `gpu-context`, no `gpu-api` and no `opengl-es` — there is no GPU
     * context to configure on this path, and naming one is what the stalled
     * boxes choke on.
     *
     * The cost is larger than "no GPU scaling", and mpv documents it plainly:
     * ``mediacodec_embed`` renders `IMGFMT_MEDIACODEC` frames straight to an
     * `android.view.Surface`, so "many of mpv's features (subtitle rendering,
     * OSD/OSC, video filters, etc) are not available with this driver"
     * (`DOCS/man/vo.rst`). This app delegates subtitles to mpv — the size /
     * background / position controls in [applySubtitleAppearance] are `sub-*`
     * options and an ASS track is typeset by mpv's libass — so on a box that
     * `auto` sends here (the two in [resolve]) the viewer gets a picture with
     * no subtitle rendering at all, and those settings silently do nothing.
     * It also only accepts frames from the zero-copy `--hwdec=mediacodec`, so
     * the `hwdec` chain in [MpvPlayerView.applyOptions] has to resolve to
     * `mediacodec`; a copy-back or software fallback leaves nothing to show.
     *
     * That is why this is a workaround and not a fix: it trades a black screen
     * for no subtitles. Since the 2026-10-28 field confirmation it is reached
     * only when the viewer selects it; `auto` no longer resolves to it anywhere
     * (see [resolve]), and the player says on screen what the choice costs
     * (MpvPlayerActivity.maybeWarnNoSubtitleRendering).
     */
    private val MEDIACODEC_EMBED = Settings(
        vo = AppPreferences.MPV_VO_MEDIACODEC_EMBED,
        gpuContext = null,
        gpuApi = null,
        openglEs = false
    )

    /** The same `gpu-next` output, on the Vulkan API instead of OpenGL ES. */
    private val VULKAN = Settings(
        vo = AppPreferences.MPV_VO_GPU_NEXT,
        gpuContext = "android",
        gpuApi = "vulkan",
        // Not `opengl-es=yes`: asking for the GL context on the Vulkan API is
        // the mismatched pair above.
        openglEs = false
    )

    /**
     * The video output for [mode].
     *
     * An explicit mode is obeyed as-is, so the settings row can put any of them
     * on any box. `auto` - what a default install gets - is the GPU path on
     * every device: the 2026-10-28 field test confirmed it delivers frames on
     * the two boxes the library swap had left stalling (Fire TV Stick 4K Plus,
     * `karat`, and the Onn 4K Plus), which is the condition the old per-device
     * table said it was waiting for. Those two entries and the workaround they
     * selected are gone from this decision; `mediacodec_embed` remains an
     * explicit choice for a box that ever needs it, and the player's own
     * settings panel still says what it costs (no subtitles, hardware decoding
     * only).
     *
     * [manufacturer], [model] and [device] are kept in the signature because
     * they are what the field report names, and because a future device-specific
     * entry would have to be read from these three again - a caller that already
     * has them should not have to change.
     *
     * A mode that is none of [AppPreferences.MPV_VO_MODES] resolves as `auto`,
     * so a corrupt or older stored value can never hand mpv a `vo` it has no
     * implementation for.
     */
    fun resolve(
        mode: String,
        manufacturer: String,
        model: String,
        device: String
    ): Settings = when (mode) {
        AppPreferences.MPV_VO_MEDIACODEC_EMBED -> MEDIACODEC_EMBED
        AppPreferences.MPV_VO_GPU_VULKAN -> VULKAN
        AppPreferences.MPV_VO_GPU -> OPENGL
        else -> OPENGL
    }
}
