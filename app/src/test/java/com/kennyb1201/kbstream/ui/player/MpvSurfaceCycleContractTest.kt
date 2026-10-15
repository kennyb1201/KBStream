package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MPV engine's surface cycle, and the `fast` profile added beside it.
 *
 * Field history, because this file has had to change sides twice:
 *
 *  - an early build attached from `initialize()` on `isValid`, and the engine
 *    rendered on NO video output (audio over black). Pulling that attach out
 *    left the callback as the one attach point, and that stands — but it also
 *    removed the only path that could attach a surface which is ALREADY up when
 *    `initialize()` runs, and a holder does not replay a create that already
 *    happened. mpv was then handed nothing at all (`wid` stays 0) while the
 *    file still opened, which is the field log's `Both surface and
 *    native_window are NULL` on the `vo=mediacodec_embed` path — frames
 *    decoding (`Got First Frame Ready`) over a black screen. The attach is the
 *    callback's plus that one case, and a load now waits for it
 *    (`surfaceAttached`). The line that removal rested on — "Could not find
 *    corresponding native window for surface" — turned out not to be mpv's at
 *    all: it is absent from the linked AAR's libmpv and its FFmpeg, and it
 *    shows up in the logcat of Fire OS apps that use neither, so it is the
 *    platform's and says nothing about this attach. When mpv itself cannot
 *    resolve a window it says "Failed to create ANativeWindow";
 *  - the next run also stopped touching `vo` on the cycle, on the theory that
 *    the runtime re-initialization was the black screen. That theory was wrong:
 *    with the output never nulled and never re-opened, mpv never re-initializes
 *    its video output against the newly attached surface. The output still
 *    points at the destroyed one (or at nothing), the decoder renders
 *    ("Got First Frame Render") and every frame goes nowhere — the same black
 *    screen with audio, on every output, which is what the field test reported;
 *  - so the cycle is the reference player's (`BaseMPVView` in mpv-android, the
 *    view Nuvio's working engine is built on), which is four calls in a fixed
 *    order.
 *
 * What has to hold together, and is pinned here:
 *
 *  1. `initialize()` sets `force-window=no` and `idle=once` AFTER `init()` and
 *     registers the holder callback LAST — the reference player's
 *     `initialize()` order: a surface can arrive the moment that callback goes
 *     on, and the pair has to be in force (and out of reach of any option set
 *     above it) before one does;
 *  2. `applyOptions()` sets `vo` as an OPTION before `init()`, from the
 *     device-resolved output — the session's one configuration of the output;
 *  3. `surfaceCreated()` attaches the surface and then takes ONE of the
 *     reference player's two branches, decided by whether this is the FIRST
 *     create (`surfaceCreatedOnce`): a first creation starts any waiting load
 *     and leaves `vo` alone (the OPTION already configured it), and a
 *     recreation — which always follows `surfaceDestroyed()` — re-opens `vo` as
 *     a PROPERTY from that same resolution, which is what re-initializes the
 *     output against the surface that was just attached;
 *  4. `surfaceDestroyed()` nulls `vo`, drops forced rendering and detaches, in
 *     that order: the output is torn down while its surface still exists;
 *  5. `release()`'s final `vo=null` before `destroy()` stays, so a dead instance
 *     cannot keep an output alive, and a session that starts again starts with
 *     `applyOptions()` on a fresh instance — with the holder callback removed
 *     first, the reference player's `destroy()` order;
 *  6. the SurfaceView's z-order is raised at construction — in `init`, before
 *     this view can have a Surface, because the flag is only read when one is
 *     created — and with the MEDIA_OVERLAY flag rather than on-top: the picture
 *     belongs behind the app's own splash and chrome, not above them.
 *
 * Only the OPTION is "set once per instance", and the property is what the
 * cycle owns on the way back in. Setting that property on EVERY create was the
 * third wrong answer: on a first creation the output is re-initialized against
 * a surface mpv has only just been handed, and the black screen comes back —
 * which is why the callback branches, as the reference does on its deferred
 * file path. The branch is decided by the explicit first-creation flag, NOT by
 * the pending load: this view's SurfaceView is in the layout, so its surface is
 * created during activity startup and `load()` runs after it, leaving
 * `pendingLoad` null on a first creation — a pending-load test took the
 * recreation branch there and re-set the output, the output re-set before
 * `start-file` the 0.6.71 field log shows.
 *
 * `profile=fast` is pinned here too: it was added for the same boxes (a 4K
 * release on this class of hardware is GPU-bound), and it is only compatible
 * with points 1-3 because it names no `vo`, no GPU context and no decoder. Its
 * contents come from the bundled libmpv's own compiled-in profile list —
 * verified by reading it out of the AAR, because a profile that mpv does not
 * define would be an option-set error and a silent no-op, not a fix:
 *
 *     unzip -p app/libs/mpv-android-lib-libdovi.aar jni/arm64-v8a/libmpv.so > libmpv.so
 *     strings -a libmpv.so | grep -A8 '^\[fast\]$'
 *     -> scale=bilinear, dscale=bilinear, dither=no, correct-downscaling=no,
 *        linear-downscaling=no, sigmoid-upscaling=no, hdr-compute-peak=no,
 *        allow-delayed-peak-detect=yes
 *
 * There is no device in CI and a libmpv Activity cannot be driven from a JVM
 * test, so the sequence is read out of the source the way this tree's other
 * player contracts are.
 */
class MpvSurfaceCycleContractTest {

    private val view: String by lazy { read(MPV_VIEW) }

    /**
     * The same source with every comment line dropped, so a rule can be stated
     * about the CODE. It matters here on both sides of the ledger: the class
     * comment names the calls the cycle makes (`vo=null`, `force-window=no`,
     * detachSurface), and the attach helper's explains which one it does NOT
     * make — asserting against prose would grade the documentation instead of
     * the code, in whichever direction the prose happened to point.
     */
    private val code: String by lazy {
        view.lines()
            .filterNot { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
            }
            .joinToString("\n")
    }

    @Test
    fun `the video output is an option set once, before init`() {
        val applications = Regex("MpvBackend\\.setOptionString\\(\"vo\"[^)]*\\)").findAll(code).toList()
        assertEquals(
            "the OPTION is the session's single configuration of the output: a second " +
                "setOptionString would be a second mode decision, and it is the property that " +
                "the surface cycle owns",
            1,
            applications.size
        )
        assertTrue(
            "and it must take the device-resolved output, not a pinned one",
            applications.single().value.contains("vo.vo")
        )
        // Options only take as options before init(): after that the same call is
        // a runtime change, which is the whole distinction this fix rests on. The
        // body of initialize() is where that order is visible - the two functions
        // are declared far apart, so their positions in the file say nothing.
        val initialize = code.substringAfter("fun initialize()")
            .substringBefore("private fun ")
        val options = initialize.indexOf("applyOptions()")
        val init = initialize.indexOf("MpvBackend.init()")
        assertTrue("initialize() has to apply the options", options >= 0)
        assertTrue("and initialize the engine", init >= 0)
        assertTrue(
            "applyOptions() must run before MpvBackend.init()",
            options < init
        )
    }

    @Test
    fun `the surface is attached from the callback, and at init only for a surface already up`() {
        // The create the holder will not replay. On a box whose surface is up
        // before initialize() runs, SurfaceHolder delivers no surfaceCreated for
        // it, so the callback alone leaves mpv with no `wid` at all while the
        // file still opens - the black screen with a running decoder this tree's
        // field log shows. NuvioTV's engine calls the same helper in the same
        // place.
        val initialize = initializeBody()
        assertTrue(
            "initialize() has to cover the surface the holder already has: $initialize",
            initialize.contains("ensureSurfaceAttachedIfAlreadyAvailable()")
        )
        assertFalse(
            "and only through that helper: a bare attach or isValid test here would be a " +
                "second copy of the check the helper owns: $initialize",
            initialize.contains("attachSurface()") || initialize.contains("isValid")
        )
        // The helper is the callback's own path, behind the live-surface check
        // that makes an attach from here safe - it attaches nothing itself.
        val ensure = ensureHelper()
        assertTrue(
            "the helper has to establish that the surface is really there first: $ensure",
            ensure.contains("surface?.isValid != true") || ensure.contains("surface?.isValid == true")
        )
        assertTrue(
            "and it must re-use the callback's attach path rather than attach on its own",
            ensure.contains("surfaceCreated(holder") || ensure.contains("surfaceCreated(liveHolder")
        )
        assertFalse(
            "with no attach of its own to drift from the callback's: $ensure",
            ensure.contains("MpvBackend.attachSurface")
        )
        assertTrue(
            "surfaceCreated is still where the attach happens",
            surfaceCreated().contains("attachSurface()")
        )
        assertTrue(
            "and a load that was waiting for the surface starts from there too",
            surfaceCreated().contains("startPendingLoad()")
        )
    }

    @Test
    fun `a load waits for the attach, never for the holder's own isValid`() {
        // `isValid` describes the Surface; it says nothing about mpv having been
        // handed it. Gating the load on it is what let the file open against an
        // mpv whose `wid` was still 0 - the black screen with a running decoder
        // (and `Both surface and native_window are NULL` on the mediacodec_embed
        // path) this rule exists to stop coming back.
        val load = code.substringAfter("fun load(request: LoadRequest) {")
            .substringBefore("fun release()")
        assertTrue(
            "the load has to start once mpv holds the surface: $load",
            load.contains("if (surfaceAttached) startPendingLoad()")
        )
        assertFalse(
            "and never on the holder's Surface, which can report valid while mpv has none: $load",
            load.contains("isValid")
        )
        // ...and the flag has to be moved by the two calls that really move it.
        val attach = code.substringAfter("private fun attachSurface()")
            .substringBefore("private fun startPendingLoad()")
        assertTrue(
            "the attach is what records mpv as holding the surface: $attach",
            attach.contains("MpvBackend.attachSurface(surface)") &&
                attach.contains("surfaceAttached = true")
        )
        assertTrue(
            "inside the same guarded block as the call it describes, so a throwing attach " +
                "cannot claim it",
            attach.indexOf("MpvBackend.attachSurface(surface)") <
                attach.indexOf("surfaceAttached = true")
        )
        val destroyed = surfaceDestroyed()
        assertTrue(
            "and the teardown takes it back, or a load arriving after it would run against " +
                "an mpv with no surface: $destroyed",
            destroyed.contains("surfaceAttached = false")
        )
        assertTrue(
            "after the detach it describes",
            destroyed.indexOf("MpvBackend.detachSurface()") <
                destroyed.indexOf("surfaceAttached = false")
        )
    }

    @Test
    fun `attaching the surface says nothing about the video output`() {
        val attach = code.substringAfter("private fun attachSurface()")
            .substringBefore("private fun startPendingLoad()")
        assertTrue(
            "the surface still has to be attached",
            attach.contains("MpvBackend.attachSurface(surface)")
        )
        assertTrue(
            "and forced rendering with it, or mpv decides it has no window to draw into",
            attach.contains("MpvBackend.setOptionString(\"force-window\", \"yes\")")
        )
        assertTrue(
            "in the reference player's order - attach first, then force-window=yes, with the " +
                "output re-opened back in surfaceCreated (recreation branch)",
            attach.indexOf("MpvBackend.attachSurface(surface)") <
                attach.indexOf("MpvBackend.setOptionString(\"force-window\", \"yes\")")
        )
        // The output property belongs to surfaceCreated, one line after this
        // helper returns - on the attach helper itself it would re-initialize
        // the output in the same breath as the attach, out of the reference
        // player's order (and, before the surface-timing fix, on a surface mpv
        // had no window for).
        assertFalse(
            "the attach helper attaches and nothing else - the `vo` re-open is the callback's",
            attach.contains("videoOutput()") || attach.contains("\"vo\"")
        )
    }

    @Test
    fun `the surface cycle tears the video output down and re-opens it only on recreation`() {
        // The reference player's cycle, both halves. This file has pinned each of
        // them wrong in turn: without the tear-down the output keeps a surface
        // that is gone, and without the re-open it never picks up the new one -
        // either way mpv decodes and presents nothing, which is the black screen
        // with audio the field test reports.
        val destroyed = surfaceDestroyed()
        val nullVo = destroyed.indexOf("setPropertyString(\"vo\", \"null\")")
        val forceOff = destroyed.indexOf("setPropertyString(\"force-window\", \"no\")")
        val detach = destroyed.indexOf("MpvBackend.detachSurface()")
        assertTrue(
            "teardown must tear the video output down, not just detach the surface",
            nullVo >= 0
        )
        assertTrue("and drop forced rendering", forceOff >= 0)
        assertTrue("and detach the surface", detach >= 0)
        assertTrue(
            "in the reference player's order: the output goes first, while its surface still " +
                "exists (vo=$nullVo forceWindow=$forceOff detach=$detach)",
            nullVo < forceOff && forceOff < detach
        )

        val created = surfaceCreated()
        val attach = created.indexOf("attachSurface()")
        val branch = created.indexOf("if (!surfaceCreatedOnce)")
        val vo = created.indexOf("setPropertyString(\"vo\", videoOutput().vo)")
        assertTrue("the surface is attached first", attach >= 0)
        assertTrue(
            "and the branch that decides the rest of the callback comes after it - touching " +
                "the output on EVERY create, first one included, is the black screen this " +
                "branch removes (attach=$attach branch=$branch)",
            branch >= 0 && attach < branch
        )

        // First creation: any file that was waiting goes out, and the output is
        // left exactly as applyOptions() set it. The re-set the callback used to
        // make here is not a no-op - it tears the video output down and
        // re-initializes it, and on the first attach that re-initialization
        // races the surface mpv was handed moments earlier.
        val firstCreation = created.substring(branch).substringBefore("} else {")
        assertTrue(
            "the load that was waiting for the surface starts from this branch",
            firstCreation.contains("startPendingLoad()")
        )
        assertFalse(
            "and this branch must NOT set the video output: it was already configured as the " +
                "OPTION before init(), so the only thing a set here can do is re-initialize " +
                "the output against the surface that was just attached: $firstCreation",
            firstCreation.contains("\"vo\"")
        )

        // Recreation: it always follows surfaceDestroyed(), which nulled the
        // output on the way out, so this set is what re-initializes it against
        // the surface that was just attached.
        val recreation = created.substringAfter("} else {")
        assertTrue(
            "the output is re-opened in the recreation branch, or mpv never re-initializes " +
                "against the new surface and every frame goes to the dead one - the call " +
                "whose absence is the black screen (attach=$attach vo=$vo)",
            recreation.contains("setPropertyString(\"vo\", videoOutput().vo)")
        )
        assertTrue(
            "as a property set after the attach, never before the branch",
            vo >= 0 && vo > branch
        )
        assertTrue(
            "re-opened from the resolution the session configured, not a pinned string",
            recreation.contains("videoOutput().vo") && !recreation.contains("\"gpu\"")
        )
        assertEquals(
            "and there is exactly one such set in the callback: a second would be a second " +
                "mode decision",
            1,
            Regex("setPropertyString\\(\"vo\", videoOutput\\(\\)\\.vo\\)").findAll(created).count()
        )
    }

    @Test
    fun `the first creation is the creation flag's call, never the pending load's`() {
        // The exact bug this flag replaces, from the field log of build 0.6.71
        // (sha c350dced): the output is re-set BEFORE `start-file` on a first
        // launch, i.e. the recreation branch taken on the first create. The
        // SurfaceView is in the layout, so `surfaceCreated` runs during activity
        // startup and `load()` — the only writer of `pendingLoad` — runs after
        // it. `pendingLoad` is therefore null on that first create, and
        // deciding on it reads first creation as recreation.
        val created = surfaceCreated()
        val branch = created.indexOf("if (!surfaceCreatedOnce)")
        assertTrue(
            "the callback has to branch on the explicit first-creation flag: $created",
            branch >= 0
        )
        assertTrue(
            "and nothing above the branch may have touched the flag, or the first creation " +
                "would take the recreation path on something other than a create having " +
                "happened",
            !created.substring(0, branch).contains("surfaceCreatedOnce")
        )
        val pendingTest = created.indexOf("if (pendingLoad != null)")
        assertTrue(
            "a pending load may still decide whether to START one inside a branch, but it " +
                "may not be what PICKS the branch: on a first creation there is no pending " +
                "load to read (pending=$pendingTest branch=$branch)",
            pendingTest > branch
        )
        assertTrue(
            "the first branch is the flag's own half, and it is what claims the first " +
                "creation",
            created.substring(branch).substringBefore("} else")
                .contains("surfaceCreatedOnce = true")
        )
        assertTrue(
            "the flag is a plain field that starts false, so a fresh view has never created " +
                "a surface",
            code.contains("private var surfaceCreatedOnce = false")
        )
        // Release is one-way: the Activity never re-initializes a released view
        // (see its onStop), so the flag needs no reset — and resetting it there
        // would be worse than useless, since a re-create of a released view
        // would then take the recreation path with no output left to re-open.
        val release = code.substringAfter("fun release()").substringBefore("fun togglePause()")
        assertFalse(
            "release() does not reset the flag: a new MpvPlayerView starts with a fresh one",
            release.contains("surfaceCreatedOnce")
        )
    }

    @Test
    fun `initialize sets the post-init pair and registers the holder callback last`() {
        // The reference player's initialize(): create, options, init(),
        // postInitOptions(), `force-window=no`, `idle=once`, and only then the
        // holder callback. When the pair lands is the point — mpv must already
        // have been told it has no window (and must stay up with nothing to
        // play) before the callback that can hand it one is registered — and it
        // is set after init() so no option above it can put force-window back
        // on. `idle=once` is what keeps mpv alive between the create and the
        // first loadfile.
        val initialize = initializeBody()
        val init = initialize.indexOf("MpvBackend.init()")
        val forceOff = initialize.indexOf("MpvBackend.setOptionString(\"force-window\", \"no\")")
        val idleOnce = initialize.indexOf("MpvBackend.setOptionString(\"idle\", \"once\")")
        val callback = initialize.indexOf("holder.addCallback(this)")
        assertTrue("initialize() has to init the engine", init >= 0)
        assertTrue("then drop forced rendering", forceOff >= 0)
        assertTrue("and tell mpv to idle at least once", idleOnce >= 0)
        assertTrue(
            "in the reference player's order: init, force-window=no, idle=once " +
                "(init=$init forceWindow=$forceOff idle=$idleOnce)",
            init < forceOff && forceOff < idleOnce
        )
        assertTrue("the holder callback is registered", callback >= 0)
        assertTrue(
            "LAST - a surface can arrive the moment it goes on, and the pair has to be in " +
                "force by then (idle=$idleOnce callback=$callback)",
            idleOnce < callback
        )
        // `initialized` goes up BEFORE the registration, not after it: the
        // holder can deliver a create from that very call, and surfaceCreated()
        // returns early while the flag is down. A dropped create is a surface
        // mpv never gets.
        val armed = initialize.indexOf("initialized = true")
        assertTrue("initialize() has to mark itself up", armed >= 0)
        assertTrue(
            "before the holder callback goes on, or a create delivered by the " +
                "registration itself is thrown away (armed=$armed callback=$callback)",
            armed < callback
        )
        assertTrue(
            "and the holder's already-live surface is covered right after it - the create " +
                "no callback will deliver",
            callback < initialize.indexOf("ensureSurfaceAttachedIfAlreadyAvailable()")
        )
        assertEquals(
            "and the pair lives in initialize() alone: an option set before init() is not " +
                "the reference player's order",
            1,
            Regex("setOptionString\\(\"force-window\", \"no\"\\)").findAll(code).count()
        )
        assertEquals(
            "the same for the idle value",
            1,
            Regex("setOptionString\\(\"idle\", \"once\"\\)").findAll(code).count()
        )
        assertFalse(
            "and the pre-init idle=yes it replaced is gone with it",
            code.contains("setOptionString(\"idle\", \"yes\")")
        )
    }

    @Test
    fun `release still nulls the output before destroying the instance`() {
        // Not part of the cycle: a dead instance cannot re-initialize against a
        // null output, and a session that starts again re-applies applyOptions()
        // on a fresh instance.
        val release = code.substringAfter("fun release()")
            .substringBefore("fun togglePause()")
        assertTrue(
            "the final `vo=null` stays",
            release.contains("setPropertyString(\"vo\", \"null\")")
        )
        assertTrue(
            "and has to be paired with destroy(), which is what makes it final",
            release.contains("MpvBackend.destroy()")
        )
        assertTrue(
            "in the reference player's teardown order - output, then surface",
            release.indexOf("setPropertyString(\"vo\", \"null\")") <
                release.indexOf("MpvBackend.detachSurface()")
        )
        // BaseMPVView.destroy() removes the callback before MpvBackend.destroy(),
        // and that half is load-bearing: a surface arriving mid-teardown would
        // otherwise attach to an instance that is being freed.
        val removeCallback = release.indexOf("holder.removeCallback(this)")
        assertTrue("the holder callback has to be removed", removeCallback >= 0)
        assertTrue(
            "before the instance is destroyed (removeCallback=$removeCallback)",
            removeCallback < release.indexOf("MpvBackend.destroy()")
        )
    }

    @Test
    fun `the fast profile is set with the performance options, before the output`() {
        val applyOptions = code.substringAfter("private fun applyOptions()")
            .substringBefore("private fun ")
        assertTrue(
            "the profile must be mpv's own built-in `fast`",
            applyOptions.contains("MpvBackend.setOptionString(\"profile\", \"fast\")")
        )
        assertTrue(
            "set before the video output: a profile applies the options it names the moment " +
                "it is set, and this one has to stay underneath the device-resolved vo",
            applyOptions.indexOf("MpvBackend.setOptionString(\"profile\", \"fast\")") <
                applyOptions.indexOf("MpvBackend.setOptionString(\"vo\"")
        )
    }

    @Test
    fun `the surface size is published at the attach, not only from surfaceChanged`() {
        // mpv's Android GPU contexts (`gpu-context=android`, in both the OpenGL
        // and the Vulkan implementation the bundled libmpv carries) size their
        // EGL / Vulkan window surface from `android-surface-size`, and they have
        // no other way to learn it: `ANativeWindow_getWidth/Height` is not
        // answered for a SurfaceView's window until a buffer has been queued.
        // The reference player publishes it from `surfaceChanged` alone
        // (`BaseMPVView.surfaceChanged`), which is enough there because
        // `surfaceChanged` follows every `surfaceCreated` — but NOT for a
        // surface the holder already had: a SurfaceHolder replays NO callback
        // for a surface that came up before the callback was registered, so the
        // helper attached to in initialize() hands the GPU output a window that
        // no size update will ever describe. That is a `vo=gpu` failure on a box
        // whose MediaCodec-direct output (`mediacodec_embed`) works, because
        // that one renders no window of its own and never asks for a size — the
        // box-specific, output-specific black screen this pins.
        //
        // The `updateAcquireFence: Did not find frame` line the earlier field
        // reports quoted for that failure is worth stating plainly, because it
        // sent three rounds of this cycle in the wrong direction: it is the
        // platform's `libgui` (`ProducerFrameEventHistory::updateAcquireFence`,
        // frameworks/native/libs/gui/FrameTimestamps.cpp), which runs in this
        // process because the app is the producer of this view's buffer queue.
        // It fires when the producer queues a frame its own frame-time history
        // has no record for, it is absent from every native library in the
        // linked MPV AAR (exactly as "Could not find corresponding native
        // window for surface" is), and it reports frames being QUEUED — not
        // lost, and not SurfaceFlinger refusing to acquire one. The window size,
        // not that line, is what the GPU path needs and the attach now
        // publishes.
        val attach = code.substringAfter("private fun attachSurface()")
            .substringBefore("private fun startPendingLoad()")
        assertTrue(
            "attaching the surface has to publish the window's size: $attach",
            attach.contains("publishAttachedSurfaceSize()")
        )
        assertTrue(
            "after the attach it describes, since it reads the surface the holder has",
            attach.indexOf("MpvBackend.attachSurface(surface)") <
                attach.indexOf("publishAttachedSurfaceSize()")
        )
        assertTrue(
            "and BEFORE force-window=yes, which is what can create the video output - the " +
                "output has to read the size at its own init, not after a first frame",
            attach.indexOf("publishAttachedSurfaceSize()") <
                attach.indexOf("MpvBackend.setOptionString(\"force-window\", \"yes\")")
        )
        // One writer for the property, so the attach and the holder's own size
        // change cannot drift apart.
        assertEquals(
            "there is exactly one place that writes android-surface-size, and every caller " +
                "goes through it",
            1,
            Regex("setPropertyString\\(\"android-surface-size\"").findAll(code).count()
        )
        assertTrue(
            "which is what surfaceChanged, the reference player's publisher, calls: " +
                surfaceChangedBody(),
            surfaceChangedBody().contains("publishAndroidSurfaceSize(width, height")
        )
        assertTrue(
            "and the surface the holder already had is covered by the attach it re-uses - " +
                "the path with no surfaceChanged to come: ${ensureHelper()}",
            ensureHelper().contains("surfaceCreated(liveHolder")
        )
        // The attach publishes only a size it KNOWS, and the already-live path
        // is the one where it cannot: initialize() registers with the holder
        // before the Activity has laid the view out, so the frame is empty and
        // the measured size is zero. That surface gets no `surfaceChanged`
        // either, so the layout is the last event that can carry the size and
        // the view's own size change is where it has to be published from.
        assertTrue(
            "the view's own size change has to publish it too, or that attach keeps a window " +
                "whose size was never published: ${onSizeChangedBody()}",
            onSizeChangedBody().contains("publishAndroidSurfaceSize(w, h")
        )
        assertTrue(
            "and only while mpv holds the surface: before the attach there is no window for a " +
                "size to describe",
            onSizeChangedBody().contains("!surfaceAttached")
        )
    }

    @Test
    fun `the surface is raised in z-order at construction, never after one exists`() {
        // The last field theory for a black screen with a fully-up engine: mpv
        // holds the window, the output is open, frames are decoded and reported
        // "shown", and nothing reaches the panel - and software decoding is
        // black as well, which rules out the decoder and hwdec. What is left is
        // where the SurfaceView's surface is composited. The flag is read when
        // the Surface is CREATED, so where the call sits decides whether it does
        // anything: this view is in the layout, so its surface can be up before
        // initialize() runs (the case ensureSurfaceAttachedIfAlreadyAvailable
        // covers) - an initialize()-time call would be a no-op on exactly the
        // boxes it is for.
        val raised = Regex("setZOrderMediaOverlay\\(true\\)").findAll(code).toList()
        assertEquals(
            "the surface's z-order is decided once, in one place: a second call would be a " +
                "second opinion about where the picture sits",
            1,
            raised.size
        )
        assertTrue(
            "and it has to be the constructor's own init block - the only code that runs " +
                "before this view can have a Surface at all, and therefore the only place the " +
                "flag is still read",
            code.contains("init {\n        setZOrderMediaOverlay(true)\n    }")
        )
        assertFalse(
            "setZOrderOnTop is not this lever: it would place the video above this app's own " +
                "splash and chrome instead of behind them",
            code.contains("setZOrderOnTop")
        )
    }

    /** The whole callback, braces included: the cycle's own body. */
    private fun surfaceCreated(): String =
        code.substringAfter("override fun surfaceCreated(holder: SurfaceHolder) {")
            .substringBefore("\n    }")

    /** initialize()'s body only: the order of its calls is what is pinned. */
    private fun initializeBody(): String =
        code.substringAfter("fun initialize(): Boolean {")
            .substringBefore("\n    }")

    /** surfaceChanged()'s body only: the holder's own size update. */
    private fun surfaceChangedBody(): String =
        code.substringAfter("override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {")
            .substringBefore("\n    }")

    /** As above; it runs to the attach helper, which is the next declaration. */
    private fun surfaceDestroyed(): String =
        code.substringAfter("override fun surfaceDestroyed(holder: SurfaceHolder)")
            .substringBefore("private fun attachSurface()")

    /** The already-live-surface helper's body only. */
    private fun ensureHelper(): String =
        code.substringAfter("private fun ensureSurfaceAttachedIfAlreadyAvailable()")
            .substringBefore("\n    }")

    /** The view's own size change: the publisher a held surface still gets. */
    private fun onSizeChangedBody(): String =
        code.substringAfter("override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {")
            .substringBefore("\n    }")

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val MPV_VIEW = "com/kennyb1201/kbstream/ui/player/MpvPlayerView.kt"
    }
}
