package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Catalog Builder's turn at Phase 2's rule: one `FormFactor` branch at the
 * TV entry, the phone layout in a tv-free file under `ui/mobile/`, and the same
 * activity-scoped ViewModel behind both.
 *
 * A JVM test cannot compose either layout, so what is pinned here is the
 * contract: the dispatch and what it hands over, the touch page's shape (list,
 * editor, pinned preview, confirm-before-delete), and - the part that matters
 * most for this screen - that the phone editor writes EVERY rule the TV editor
 * writes, through the same setters on the same view model.
 */
class CatalogBuilderScreenMobileContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText().replace(Regex("\\s+"), " ")
    }

    private fun tvScreen(): String =
        source("com/kennyb1201/kbstream/ui/addons/CatalogBuilderScreen.kt")

    private fun mobileScreen(): String =
        source("com/kennyb1201/kbstream/ui/mobile/CatalogBuilderScreenMobile.kt")

    @Test
    fun `the TV entry dispatches on the form factor and hands the view model over`() {
        val tv = tvScreen()
        val dispatch = tv.indexOf("if (!com.kennyb1201.kbstream.FormFactor.isTv)")
        val call = tv.indexOf("CatalogBuilderScreenMobile(")
        assertTrue("CatalogBuilderScreen must branch on FormFactor.isTv", dispatch >= 0)
        assertTrue("the mobile branch must call CatalogBuilderScreenMobile", call > dispatch)
        val branch = tv.substring(call).substringBefore("return")
        assertTrue(
            "the branch must hand the same ViewModel over",
            branch.contains("viewModel = viewModel")
        )
        assertTrue(
            "the branch must hand the same back callback over",
            branch.contains("onBack = onBack")
        )
        assertTrue(
            "the branch must return, so the TV page never composes on a phone",
            tv.substring(call).substringBefore("val ").contains("return")
        )
        assertTrue(
            "the TV page must still be below the branch",
            tv.substringAfter("if (!com.kennyb1201.kbstream.FormFactor.isTv)")
                .substringAfter("return")
                .contains("Column(")
        )
    }

    @Test
    fun `the phone page is built on the touch twins and the mobile theme`() {
        val mobile = mobileScreen()
        assertTrue("the phone page must apply MobileTheme", mobile.contains("MobileTheme {"))
        assertTrue(
            "the phone page must not branch on the form factor itself",
            !mobile.contains("FormFactor")
        )
        listOf(
            "ui.components.touch.KBTextField",
            "ui.components.touch.KBButton",
            "ui.components.touch.KBCard",
            "ui.components.touch.KBDialogPanel"
        ).forEach { component ->
            assertTrue("the phone page must use $component", mobile.contains(component))
        }
        assertTrue(
            "the phone page must drive the shared CatalogBuilderViewModel",
            mobile.contains("viewModel: CatalogBuilderViewModel")
        )
        assertFalse(
            "the phone page must not own a rule vocabulary of its own",
            mobile.contains("data class CatalogFilterOption")
        )
    }

    @Test
    fun `every rule the TV editor writes is reachable on a phone`() {
        val mobile = mobileScreen()
        // One entry per setter the TV editor wires (see CatalogBuilderScreen's
        // editor argument list). A phone catalog and a TV catalog therefore
        // write the same `KBFilters` fields.
        val setters = listOf(
            "setName",
            "setMediaType",
            "setSort",
            "setRuntimeMin",
            "setRuntimeMax",
            "toggleGenre",
            "toggleService",
            "setRegion",
            "toggleStudio",
            "toggleNetwork",
            "toggleExcludedNetwork",
            "setTvStatus",
            "setTvType",
            "setKeywordsInclude",
            "searchKeywords",
            "pickKeyword",
            "removeKeyword",
            "searchPeople",
            "pickPerson",
            "removePerson",
            "setDecade",
            "setReleasedAfter",
            "setReleasedBefore",
            "setReleaseType",
            "toggleLanguage",
            "toggleCountry",
            "setCertificationCountry",
            "setCertification",
            "setCertificationCeiling",
            "setMinRating",
            "setMaxRating",
            "setMinVotes",
            "addCustomIds",
            "saveDraft",
            "cancelEdit",
            "startNew",
            "edit",
            "delete",
            "move",
            "toggleHidden",
            "runPreview"
        )
        setters.forEach { setter ->
            assertTrue(
                "the phone editor must reach $setter",
                mobile.contains("viewModel::$setter") || mobile.contains(".$setter(")
            )
        }
    }

    @Test
    fun `the two TV-only rule families stay hidden on a phone, as on TV`() {
        val mobile = mobileScreen()
        val tv = tvScreen()
        // TMDB's /discover/tv documents neither `with_cast` nor
        // `with_release_type`, so neither editor may offer them on a series
        // catalog - a chip that writes a rule the endpoint ignores is worse
        // than no chip.
        assertTrue(
            "the cast section must be gated on the media type",
            mobile.contains("if (!isTv) { BuilderSectionMobile(\"Cast\")")
        )
        assertTrue(
            "the release-type section must be gated on the media type",
            mobile.contains("if (!isTv) { BuilderSectionMobile(\"Release type\")")
        )
        assertTrue("the TV editor keeps the same gate", tv.contains("if (!isTv)"))
    }

    @Test
    fun `the escape hatches, the confirmation and the pinned preview survive the port`() {
        val mobile = mobileScreen()
        assertTrue(
            "hand-typed ids must be parsed by the TV helper",
            mobile.contains("parseCustomIds(")
        )
        assertTrue(
            "hand-typed vote counts must be parsed by the TV helper",
            mobile.contains("parseVoteCountInput(")
        )
        assertTrue(
            "a typed id must be added through the view model's own field path",
            mobile.contains("viewModel.addCustomIds(field, ids)")
        )
        assertTrue(
            "the delete action must raise a confirmation, not delete on tap",
            mobile.contains("var pendingDelete by remember") &&
                mobile.contains("Delete catalog") &&
                mobile.contains("viewModel.delete(target.id)")
        )
        val editorBranch = mobile.substringAfter("if (current == null) {")
        val preview = editorBranch.indexOf("PreviewPanelMobile(preview = preview)")
        val editor = editorBranch.indexOf("CatalogEditorMobile(")
        assertTrue("the editor branch must draw the preview", preview >= 0)
        assertTrue(
            "the preview must be pinned above the form, so PREVIEW changes something visible",
            editor > preview
        )
        assertTrue(
            "saving must stay behind the view model's own validation message",
            mobile.contains("message?.let")
        )
    }
}
