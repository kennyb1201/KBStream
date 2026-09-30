package com.kennyb1201.kbstream.data.tmdb

/**
 * Which of TMDB's two entity id spaces a brand's mark is read from.
 *
 * A NETWORK id is never a company id: the two spaces are numbered
 * independently, so the same number is two different things - 41 is TNT as a
 * network and Orion Pictures as a company, 76 is E! and Zentropa
 * Entertainments, 74 is Bravo and Moovie. Anything that pairs them has to
 * prove the pair is the same brand first; see [entityNamesMatch].
 */
internal enum class BrandIdSpace { COMPANY, NETWORK }

internal val BrandIdSpace.isCompany: Boolean
    get() = this == BrandIdSpace.COMPANY

/**
 * The id spaces a brand's mark may be drawn from, its OWN space first.
 *
 * Both spaces are offered, in this order, for a network and for a company
 * alike - a brand TMDB filed only under the other kind (a network page whose
 * artwork sits on the company entry for the same brand) still gets its mark.
 * The order is what keeps the entity's own artwork ahead of any twin's, and
 * the second space's marks are only ever used once the twin's name has been
 * checked (see [entityNamesMatch]), so a numeric coincidence cannot put an
 * unrelated logo on the page.
 */
internal fun brandIdSpaces(isNetwork: Boolean): List<BrandIdSpace> =
    if (isNetwork) {
        listOf(BrandIdSpace.NETWORK, BrandIdSpace.COMPANY)
    } else {
        listOf(BrandIdSpace.COMPANY, BrandIdSpace.NETWORK)
    }

/**
 * How many characters a brand name needs before a CONTAINMENT match is
 * trusted - "Angel" inside "Angel Studios" is the same brand, a one-letter
 * name matching anything is not. An exact match needs no such floor.
 */
internal const val BRAND_NAME_TWIN_MIN_CHARS = 4

/** A brand name reduced to what two TMDB entries for one brand agree on. */
internal fun normalizeBrandName(name: String?): String? =
    name
        ?.lowercase()
        ?.filter { it.isLetterOrDigit() }
        ?.takeIf { it.isNotBlank() }

/**
 * Whether two TMDB entity names are the same brand, which is what licenses
 * reading one's artwork for the other.
 *
 * Equality after normalization is always enough. Containment needs a floor on
 * the shorter name, because TMDB spells one brand several ways ("Angel" for
 * its network, "Angel Studios" for the company) and a two-letter stub would
 * otherwise match half the catalogue. Measured against the app's own id list:
 * TNT/Orion Pictures, E!/Zentropa Entertainments, Bravo/Moovie, CMT/Konrad
 * Pictures and BET/Mikona Productions all answer false, which is the point -
 * before this check their logos were offered as fallbacks for those networks.
 */
internal fun entityNamesMatch(a: String?, b: String?): Boolean {
    val x = normalizeBrandName(a) ?: return false
    val y = normalizeBrandName(b) ?: return false
    if (x == y) return true
    val shorter = minOf(x.length, y.length)
    return shorter >= BRAND_NAME_TWIN_MIN_CHARS &&
        (x.contains(y) || y.contains(x))
}
