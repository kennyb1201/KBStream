package com.kennyb1201.kbstream.ui.detail

/**
 * Where one chain ask actually put the focus.
 *
 * The field failure this exists for (2026-11-21, build 443): `GENRE DOWN ->
 * TABS landed=true verified=true` on a press whose focus ended on HEADER. The
 * old verifier only asked whether focus was on SOME other row, so a landed
 * press could certify a row the press never aimed at.
 */
internal enum class DetailFocusVia {
    CHAIN,
    GEOMETRY
}

/**
 * The honest outcome of one chain ask (spec 2026-11-21 follow-up).
 *
 * [landed] means the focus really moved; [verified] means it was observed on
 * the row the press aimed at - or on a row the walk DECLARED it stepped to.
 * They are separate on purpose: a geometric fallback can move the focus
 * ([landed] true) onto a row the press did not aim at ([verified] false), and
 * a wrong-row chain landing is a miss, not a success. [via] is never rewritten
 * from GEOMETRY to CHAIN: a paste has to be able to tell the pill's landing
 * from the platform's search (spec 2026-11-21, defect 3).
 */
internal data class DetailFocusLanding(
    val landed: Boolean,
    val verified: Boolean,
    val via: DetailFocusVia,
    val target: DetailRow,
    val observed: DetailRow?,
    val attempted: Set<DetailRow>
)

/**
 * The rows one [DetailFocusScreen.focusRow] walk asked for, and how it placed
 * the focus when the chain itself could not.
 *
 * The recursion adds every [DetailRow] it attempts, so the target plus the
 * rows the walk stepped over are the only rows a landing may be verified on.
 */
internal class DetailFocusAskPath {
    val attempted: LinkedHashSet<DetailRow> = LinkedHashSet()
    var via: DetailFocusVia = DetailFocusVia.CHAIN
}

/** The pure rules a landing is judged by, so the contract can test them. */
internal object DetailFocusLandingRules {

    /**
     * Whether [observed] is a row this press DECLARED it was going to.
     *
     * The target always counts. A row the walk stepped over to because the
     * target would not take the focus also counts - the chain declared that
     * move in [DetailFocusAskPath.attempted]. Anything else is a miss.
     */
    fun isDeclared(
        target: DetailRow,
        attempted: Set<DetailRow>,
        observed: DetailRow?
    ): Boolean = observed != null && (observed == target || observed in attempted)

    fun outcome(
        target: DetailRow,
        before: DetailRow?,
        via: DetailFocusVia,
        observed: DetailRow?,
        attempted: Set<DetailRow>
    ): DetailFocusLanding {
        val declared = isDeclared(target, attempted, observed)
        val moved = observed != null && observed != before
        return DetailFocusLanding(
            // A geometric fallback that moved focus is a real move, but the
            // trace must say GEOMETRY so a paste can tell "pill landed" from
            // "geometry landed somewhere" (spec 2026-11-21, defect 3).
            landed = declared || (via == DetailFocusVia.GEOMETRY && moved),
            verified = declared,
            // Never rewrite a geometry landing into `chain`: the pill ask
            // FAILED in that path, and `via=chain` was exactly how the wrong
            // landing used to certify itself.
            via = via,
            target = target,
            observed = observed,
            attempted = attempted
        )
    }
}
