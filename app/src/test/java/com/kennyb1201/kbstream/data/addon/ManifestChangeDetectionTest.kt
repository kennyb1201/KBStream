package com.kennyb1201.kbstream.data.addon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the background add-on refresh is allowed to treat as "no change".
 *
 * This is the difference between an add-on updating by itself and a user having
 * to press "Refresh addons" to see it. Both paths fetch the same manifest and
 * apply it through the same merge; the ONLY thing the background path does that
 * the manual one does not is skip the apply when this says nothing changed. So
 * every field the apply writes must be covered here, or a real manifest update
 * is dropped silently by the automatic path while the manual path — which
 * applies unconditionally — picks it up.
 *
 * The regression these pin: the check used to cover name, version, resources and
 * each catalog's (type, id, name) only, so a manifest that changed just its
 * description, logo, types, idPrefixes, or a catalog's showInHome/isSearch hint
 * was reported "unchanged" and never applied.
 */
class ManifestChangeDetectionTest {

    @Test
    fun `an identical manifest is not a change`() {
        assertFalse(manifestChangesInstalledAddon(installed(), manifest()))
    }

    @Test
    fun `a new description is a change`() {
        assertTrue(
            manifestChangesInstalledAddon(installed(), manifest(description = "Rewritten"))
        )
    }

    @Test
    fun `a new logo is a change`() {
        assertTrue(manifestChangesInstalledAddon(installed(), manifest(logo = "https://l/new.png")))
    }

    @Test
    fun `an icon fills in for an absent logo and is a change`() {
        assertTrue(
            manifestChangesInstalledAddon(
                installed(logo = null),
                manifest(logo = null, icon = "https://l/icon.png")
            )
        )
    }

    @Test
    fun `new id prefixes are a change`() {
        assertTrue(
            manifestChangesInstalledAddon(installed(), manifest(idPrefixes = listOf("tt", "tvdb")))
        )
    }

    @Test
    fun `a new media type is a change`() {
        assertTrue(
            manifestChangesInstalledAddon(installed(), manifest(types = listOf("movie", "series")))
        )
    }

    @Test
    fun `a new resource is a change`() {
        assertTrue(
            manifestChangesInstalledAddon(
                installed(),
                manifest(resources = listOf("catalog", "stream"))
            )
        )
    }

    @Test
    fun `a catalog's new showInHome hint is a change`() {
        // The BingeCat case: its manifests declare showInHome per catalog, and
        // the apply stores that hint — but the old check never compared it, so
        // a hint-only update never landed automatically.
        assertTrue(
            manifestChangesInstalledAddon(
                installed(),
                manifest(catalogs = listOf(catalog(showInHomeHint = false)))
            )
        )
    }

    @Test
    fun `a catalog's new isSearch marker is a change`() {
        assertTrue(
            manifestChangesInstalledAddon(
                installed(),
                manifest(catalogs = listOf(catalog(isSearchCatalog = true)))
            )
        )
    }

    @Test
    fun `a renamed catalog is a change`() {
        assertTrue(
            manifestChangesInstalledAddon(
                installed(),
                manifest(catalogs = listOf(catalog(name = "Top Today")))
            )
        )
    }

    @Test
    fun `a new catalog is a change`() {
        assertTrue(
            manifestChangesInstalledAddon(
                installed(),
                manifest(
                    catalogs = listOf(catalog(), catalog(id = "new", name = "New Rail"))
                )
            )
        )
    }

    @Test
    fun `a removed catalog is a change`() {
        assertTrue(
            manifestChangesInstalledAddon(
                installed(catalogs = listOf(catalog(), catalog(id = "gone"))),
                manifest(catalogs = listOf(catalog()))
            )
        )
    }

    // ── what must NOT count as a change ─────────────────────────────

    @Test
    fun `the user's rename and hidden rail are not changes`() {
        // These are KBStream-local: the merge carries them across, so a
        // manifest can never "change" them and they must not force an apply on
        // every launch for every renamed or hidden rail.
        assertFalse(
            manifestChangesInstalledAddon(
                installed(
                    catalogs = listOf(catalog(customName = "My Rail", showOnHome = false))
                ),
                manifest()
            )
        )
    }

    @Test
    fun `the user's addon rename is not a change`() {
        // customName is the user's override of the tile label; the manifest
        // cannot change it, so it must not make one look changed either.
        assertFalse(
            manifestChangesInstalledAddon(
                installed(customName = "My BingeCat"),
                manifest()
            )
        )
    }

    @Test
    fun `reordered id prefixes are not a change`() {
        // Order is not meaningful within one addon (idPrefixes orders probes
        // BETWEEN addons), so a reshuffled list must not apply on every launch.
        assertFalse(
            manifestChangesInstalledAddon(
                installed(idPrefixes = listOf("tt", "tvdb")),
                manifest(idPrefixes = listOf("tvdb", "tt"))
            )
        )
    }

    @Test
    fun `an empty list and an absent one are not a change`() {
        // A manifest that drops "idPrefixes" entirely and one that declares an
        // empty list normalize to the same stored value.
        assertFalse(
            manifestChangesInstalledAddon(
                installed(idPrefixes = emptyList()),
                manifest(idPrefixes = null)
            )
        )
    }

    // ── builders ────────────────────────────────────────────────────

    private fun catalog(
        type: String = "movie",
        id: String = "top",
        name: String = "Top",
        showInHomeHint: Boolean? = null,
        isSearchCatalog: Boolean? = null,
        customName: String? = null,
        showOnHome: Boolean = true
    ): ManifestCatalog = ManifestCatalog(
        type = type,
        id = id,
        name = name,
        showOnHome = showOnHome,
        customName = customName,
        showInHomeHint = showInHomeHint,
        isSearchCatalog = isSearchCatalog
    )

    private fun manifest(
        catalogs: List<ManifestCatalog> = listOf(catalog()),
        description: String? = "A catalogue",
        logo: String? = "https://l/logo.png",
        icon: String? = null,
        types: List<String> = listOf("movie"),
        idPrefixes: List<String>? = listOf("tt"),
        resources: List<String> = listOf("catalog")
    ): AddonManifest = AddonManifest(
        id = "com.binge.cat",
        name = "BingeCat",
        version = "1.0.0",
        description = description,
        resources = resources,
        types = types,
        catalogs = catalogs,
        logo = logo,
        icon = icon,
        idPrefixes = idPrefixes
    )

    private fun installed(
        catalogs: List<ManifestCatalog> = listOf(catalog()),
        name: String = "BingeCat",
        customName: String? = null,
        description: String? = "A catalogue",
        logo: String? = "https://l/logo.png",
        types: List<String> = listOf("movie"),
        idPrefixes: List<String>? = listOf("tt"),
        resources: List<String> = listOf("catalog")
    ): InstalledAddon = InstalledAddon(
        manifestUrl = "https://bingecat.example/manifest.json",
        id = "com.binge.cat",
        name = name,
        customName = customName,
        resources = resources,
        catalogs = catalogs,
        version = "1.0.0",
        description = description,
        types = types,
        logo = logo,
        idPrefixes = idPrefixes
    )
}
