package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide's deep dive, pinned where a device would find it.
 *
 * Five confirmed findings and one suspected one, all in composition/wiring
 * rather than in the tested rules: a program-search press that promised one
 * program and delivered another (C1), a NOW/NEXT boundary shared by two
 * predicates (C2), a header count that meant two things (C3), an empty-state
 * button that was a no-op exactly where it was offered (C4/S1), a failed
 * catch-up read dressed as an empty one (C5), and RELOAD that was LOAD (I2).
 *
 * The clock-dependent parts have pure seams and are tested as behaviour in
 * [GuideRulesTest] (`programHitPress`, the boundary tick). What is left is
 * Compose/ViewModel wiring over an Android context, read out of the source like
 * this tree's other UI contracts.
 */
class GuideDeepDiveContractTest {

    private fun source(path: String): String {
        val file = File(findMainSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun findMainSourceRoot(): File {
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

    // ── C1: a program hit delivers the program it names ──────────────────

    @Test
    fun `the tv program hit's press is decided by the clock`() {
        val guide = source(GUIDE_TV)

        assertTrue(
            "the row asks the pure rule, with the hit's own start and end",
            guide.contains("fun pressFor(hit: GuideProgramHit): ProgramHitPress = programHitPress(") &&
                guide.contains("startUtcMillis = hit.program.startUtcMillis,") &&
                guide.contains("endUtcMillis = hit.program.endUtcMillis,")
        )
        assertTrue(
            "and whether a reminder is already armed",
            guide.contains("reminderSet = ReminderRules.reminderKey(") &&
                guide.contains(") in reminderKeys")
        )
        assertTrue(
            "a future hit arms a reminder through the shared toggle, not a tune",
            guide.contains("ProgramHitPress.SET_REMINDER,") &&
                guide.contains("ProgramHitPress.CANCEL_REMINDER ->") &&
                guide.contains("onToggleProgramReminder(hit)")
        )
        assertTrue(
            "only a hit that is airing (or over) tunes the channel",
            guide.contains("ProgramHitPress.WATCH_LIVE,") &&
                guide.contains("ProgramHitPress.WATCH_LIVE_ENDED ->") &&
                guide.contains("onPlay(hit.item)")
        )
        assertTrue(
            "and the row says what the press will do",
            guide.contains("text = programHitActionLabel(press),")
        )
    }

    @Test
    fun `the search dialog keeps its own clock while it is open`() {
        val guide = source(GUIDE_TV)
        assertTrue(
            "a dialog left open across a start time must not keep offering a " +
                "reminder for a program already on air",
            guide.contains("var nowUtcMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }") &&
                guide.contains("delay(PROGRAM_HIT_CLOCK_MS)") &&
                guide.contains("private const val PROGRAM_HIT_CLOCK_MS = 30_000L")
        )
    }

    @Test
    fun `the phone's program hit behaves the same way`() {
        val mobile = source(GUIDE_MOBILE)

        assertTrue(
            "the same rule, fed by the screen's own 30s clock",
            mobile.contains("val press = programHitPress(") &&
                mobile.contains("nowUtcMillis = nowMs,")
        )
        assertTrue(
            "a future hit toggles a reminder; a live one tunes",
            mobile.contains("ProgramHitPress.CANCEL_REMINDER ->") &&
                mobile.contains("toggleProgramReminder(") &&
                mobile.contains("onPlayChannel?.invoke(hit.item)")
        )
        assertTrue(
            "and the row carries the label, not just the title and time",
            mobile.contains("actionLabel = programHitActionLabel(press),") &&
                mobile.contains("text = actionLabel,")
        )
        assertTrue(
            "armed reminders are read from the one store, by the worker's key",
            mobile.contains("val reminderKeys = remember(reminders) {") &&
                mobile.contains("ReminderRules.reminderKey(it.channelId, it.startUtcMillis)")
        )
    }

    @Test
    fun `both screens arm reminders through one function`() {
        // The menu and a search hit are two doors onto one record and one
        // worker alert; a second copy is how one of them arms something the
        // other cannot remove.
        val guide = source(GUIDE_TV)
        assertTrue(
            "the TV guide has one toggle and both call it",
            guide.contains("fun toggleProgramReminder(") &&
                guide.split("toggleProgramReminder(").size - 1 >= 3
        )
        val mobile = source(GUIDE_MOBILE)
        assertTrue(
            "and so does the phone: the sheet's row is a thin wrapper over it",
            mobile.contains("fun toggleProgramReminder(") &&
                mobile.contains("fun toggleReminder(item: IptvChannelWithEpg)") &&
                mobile.contains("val next = item.next ?: return") &&
                mobile.split("toggleProgramReminder(").size - 1 >= 3
        )
    }

    // ── C2: a program that starts exactly now ────────────────────────────

    @Test
    fun `next is strictly after the clock, in the query and in the tick`() {
        val repository = source(REPOSITORY)
        val nextWindow = repository.substringAfter("val now = matchedPrograms.firstOrNull")
            .substringBefore("val upcoming")
        assertTrue(
            "the database mapping asks for a program that starts AFTER now, so " +
                "one starting exactly now is not on both cards",
            nextWindow.contains("program.startUtcMillis > nowUtcMillis")
        )
        assertFalse(
            "the `>=` is what put the now program on the next card as well",
            nextWindow.contains("program.startUtcMillis >= nowUtcMillis")
        )

        val rules = source(RULES)
        assertTrue(
            "and the in-memory tick holds the same rule, or the two drift",
            rules.contains("val next = programs.firstOrNull { it.startUtcMillis > nowUtcMillis }")
        )
    }

    // ── C3: the header count says what it counts ─────────────────────────

    @Test
    fun `the header count is labelled by what it counts`() {
        val guide = source(GUIDE_TV)
        assertTrue(
            "a group states its own count, so \"412 channels • Sports\" cannot " +
                "read as a playlist total",
            guide.contains("selectedGroup == \"All\" -> \"\$channelCount channels • \$selectedGroup\"") &&
                guide.contains("else -> \"\$channelCount in \$selectedGroup\"")
        )
    }

    // ── C4 + S1: the empty state names its own case ──────────────────────

    @Test
    fun `the empty view offers the action that can fix it`() {
        val guide = source(GUIDE_TV)

        assertTrue(
            "an empty playlist is not a hidden-channels problem",
            guide.contains("emptyPlaylist -> showSetup = true") &&
                guide.contains("\"This playlist has no channels. Open \"")
        )
        assertTrue(
            "and hide-everything opens the manager that can undo it, instead of " +
                "re-selecting All (already selected)",
            guide.contains("allHidden -> showHiddenManager = true") &&
                guide.contains("\"MANAGE HIDDEN CHANNELS\"") &&
                guide.contains("\"Every channel in this view is hidden. \"")
        )
        assertTrue(
            "a group with nothing in it still offers All",
            guide.contains("else -> selectedGroup = \"All\"") &&
                guide.contains("\"SHOW ALL CHANNELS\"")
        )
    }

    // ── C5: a failed catch-up read is not an empty one ───────────────────

    @Test
    fun `a catch-up failure is carried to the dialog, not blanked`() {
        val viewModel = source(VIEW_MODEL)
        assertTrue(
            "the VM reports the failure as its own state",
            viewModel.contains("val catchupError: StateFlow<String?> = _catchupError.asStateFlow()") &&
                viewModel.contains("_catchupError.value = CATCHUP_FAILED_LABEL")
        )
        assertTrue(
            "generation-guarded, like the list itself",
            viewModel.substringAfter("} catch (t: Throwable) {")
                .substringBefore("} finally {")
                .contains("if (generation == catchupGeneration)")
        )

        listOf(GUIDE_TV, GUIDE_MOBILE).forEach { path ->
            val screen = source(path)
            assertTrue(
                "$path must read the error state",
                screen.contains("val catchupError by viewModel.catchupError.collectAsStateWithLifecycle()")
            )
            assertTrue(
                "$path must hand it to its dialog",
                screen.contains("error = catchupError,")
            )
            assertTrue(
                "$path's dialog prints it instead of the no-history advice",
                screen.contains("text = error ?: (")
            )
        }
    }

    // ── I2: RELOAD is not LOAD under a second name ───────────────────────

    @Test
    fun `reload forces the guide refresh`() {
        val viewModel = source(VIEW_MODEL)
        val reload = viewModel.substringAfter("fun reload() {")
            .substringBefore("}")
        assertTrue(
            "it goes through the force-refresh path, which is the only thing that " +
                "helps a stale guide",
            reload.contains("loadWithGuideRefresh()")
        )
        assertTrue(
            "and the panel's RELOAD button calls it",
            source(GUIDE_TV).contains("onReload = { viewModel.reload() },")
        )
        assertFalse(
            "the old identical-to-LOAD wiring is gone",
            source(GUIDE_TV).contains("onReload = { viewModel.load() },")
        )
    }

    private companion object {
        const val GUIDE_TV = "com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt"
        const val GUIDE_MOBILE = "com/kennyb1201/kbstream/ui/mobile/GuideScreenMobile.kt"
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/iptv/IptvViewModel.kt"
        const val RULES = "com/kennyb1201/kbstream/ui/iptv/GuideRules.kt"
        const val REPOSITORY = "com/kennyb1201/kbstream/data/iptv/IptvRepository.kt"
    }
}
