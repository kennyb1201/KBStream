package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.settings.AppPreferences

/**
 * Which video output the MPV engine opens with, by device.
 *
 * The 6.51 mpvlibAndroid swap moved the black screen rather than removing it:
 * the Onn 4K Plus started displaying frames, and the Fire TV Stick 4K Plus
 * (MediaTek, `karat`) went the other way — audio plays, frames decode, and no
 * frame ever reaches the Surface (`updateAcquireFence: Did not find frame`).
 * That is a property of the video output, not of the file, so the settings
 * around `vo` have to be chosen per device. This is that choice, as one pure
 * function of the mode and the three `Build` fields it is made from, so it can
 * be pinned by a unit test rather than by two TVs.
 *
 * A resolved [Settings] is applied as mpv OPTIONS before `init()` (see
 * [MpvPlayerView.applyOptions]) and read back as a PROPERTY when a surface
 * re-attach has to put the video output back (see [MpvPlayerView.attachSurface])
 * — the same resolution both times, which is why it lives here and not inline in
 * either.
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
     * The GPU path this app has configured since before the library swap:
     * `vo=gpu` on the Android context, with the API pinned to OpenGL ES because
     * `gpu-api` defaults to `auto` and a Vulkan-capable box answered with the
     * Vulkan path while `gpu-context=android` asked for GL — a mismatched pair
     * that opens without error, plays audio, and never shows a frame.
     */
    private val OPENGL = Settings(
        vo = AppPreferences.MPV_VO_GPU,
        gpuContext = "android",
        gpuApi = "opengl",
        openglEs = true
    )

    /**
     * Bypass the GPU entirely: MediaCodec decodes straight into the Surface.
     * No `gpu-context`, no `gpu-api` and no `opengl-es` — there is no GPU
     * context to configure on this path, and naming one is what the stalled
     * boxes choke on. The cost is the GPU's own work: no shader-based
     * subtitle blending or scaling, which is why it is the fallback the field
     * test cycles away from once a GPU configuration works.
     */
    private val MEDIACODEC_EMBED = Settings(
        vo = AppPreferences.MPV_VO_MEDIACODEC_EMBED,
        gpuContext = null,
        gpuApi = null,
        openglEs = false
    )

    /** The same `vo=gpu` output, on the Vulkan API instead of OpenGL ES. */
    private val VULKAN = Settings(
        vo = AppPreferences.MPV_VO_GPU,
        gpuContext = "android",
        gpuApi = "vulkan",
        // Not `opengl-es=yes`: asking for the GL context on the Vulkan API is
        // the mismatched pair above.
        openglEs = false
    )

    /**
     * The Fire TV Stick 4K Plus (2nd gen), the box the 6.51 swap black-screened.
     *
     * Matched on manufacturer AND (model or device): "Amazon" alone is every
     * Fire TV ever made, and the workaround is only known-correct for this one.
     */
    fun isFireTvStick4kPlus(manufacturer: String, model: String, device: String): Boolean =
        manufacturer.equals("Amazon", ignoreCase = true) &&
            (model == "AFTKRT" || device == "karat")

    /**
     * The Onn 4K Plus — the box the library swap fixed, and the one the OpenGL
     * path was validated on.
     */
    fun isOnn4kPlus(manufacturer: String, model: String): Boolean =
        manufacturer.contains("onn", ignoreCase = true) ||
            model.contains("onn", ignoreCase = true)

    /**
     * The video output for [mode] on the device named by the three `Build`
     * fields.
     *
     * An explicit mode is obeyed as-is, so the settings row can put any of them
     * on any box while the field test looks for a working configuration. The
     * `auto` mode is the device table below, which is what a default install
     * gets:
     *
     *  - the Fire TV Stick 4K Plus, whose GPU output never delivers a frame
     *    since 6.51, goes to MediaCodec;
     *  - the Onn 4K Plus keeps the OpenGL GPU path it is known to display with;
     *  - every other box keeps that same OpenGL path, which is what this app
     *    configured before the library swap, so nothing else changes behavior
     *    on an upgrade.
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
        else -> if (isFireTvStick4kPlus(manufacturer, model, device)) {
            MEDIACODEC_EMBED
        } else if (isOnn4kPlus(manufacturer, model)) {
            // Broken out rather than folded into the branch below: this is the
            // box the OpenGL path is known to work on, so if the default for
            // unknown hardware ever has to change, this one keeps it.
            OPENGL
        } else {
            OPENGL
        }
    }
}
