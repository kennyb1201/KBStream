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
 *  - the engine rendered on NO video output (audio over black) because the
 *    surface was attached before it had a native window — `initialize()`
 *    attached from `isValid`, which is true first. The fix for that is
 *    `surfaceCreated()` as the one attach point, and it stands;
 *  - the next run also stopped touching `vo` on the cycle, on the theory that
 *    the runtime re-initialization was the black screen. That theory was wrong:
 *    with the output never nulled and never re-opened, mpv never re-initializes
 *    its video output against the newly attached surface. The output still
 *    points at the destroyed one (or at nothing), the decoder renders
 *    ("Got First Frame Render") and every frame goes nowhere
 *    (`updateAcquireFence: Did not find frame`) — the same black screen with
 *    audio, on every output, which is what the field test reported;
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
 *     first, the reference player's `destroy()` order.
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
 * recreation branch there and re-set the output, the `vo="gpu"` before
 * `start-file` the 0.6.71 field log shows.
 *
 * `profile=fast` is pinned here too: it was added for the same boxes (a 4K
 * release on this class of hardware is GPU-bound), and it is only compatible
 * with points 1-3 because it names no `vo`, no GPU context and no decoder. Its
 * contents come from the bundled libmpv's own compiled-in profile list —
 * verified by reading it out of the AAR, because a profile that mpv does not
 * define would be an option-set error and a silent no-op, not a fix:
 *
 *     unzip -p app/libs/mpvlib-1.1.2.aar jni/arm64-v8a/libmpv.so > libmpv.so
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
    fun `the surface is attached only from the callback, never from initialize`() {
        // A Surface that reports isValid can still have NO native window behind
        // it, so attaching one from initialize() hands mpv a dead window: it
        // reconfigures to `video=audio-only`, the decoder renders anyway and mpv
        // cannot acquire the frames. initialize() runs from the Activity's
        // onCreate, before any surface exists, so there is nothing for it to
        // attach to in the first place.
        val initialize = initializeBody()
        assertFalse(
            "initialize() must not attach a surface - `isValid` is true before the " +
                "native window exists, which is the dead-window bug: $initialize",
            initialize.contains("attachSurface()") || initialize.contains("isValid")
        )
        assertTrue(
            "surfaceCreated is the one point where the surface is really backed, so it " +
                "is where the attach happens",
            surfaceCreated().contains("attachSurface()")
        )
        assertTrue(
            "and a load that was waiting for the surface starts from there too",
            surfaceCreated().contains("startPendingLoad()")
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

    /** The whole callback, braces included: the cycle's own body. */
    private fun surfaceCreated(): String =
        code.substringAfter("override fun surfaceCreated(holder: SurfaceHolder) {")
            .substringBefore("\n    }")

    /** initialize()'s body only: the order of its calls is what is pinned. */
    private fun initializeBody(): String =
        code.substringAfter("fun initialize(): Boolean {")
            .substringBefore("\n    }")

    /** As above; it runs to the attach helper, which is the next declaration. */
    private fun surfaceDestroyed(): String =
        code.substringAfter("override fun surfaceDestroyed(holder: SurfaceHolder)")
            .substringBefore("private fun attachSurface()")

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
