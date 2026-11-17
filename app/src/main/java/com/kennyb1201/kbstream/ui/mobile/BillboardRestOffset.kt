package com.kennyb1201.kbstream.ui.mobile

/**
 * How far off a page can sit and still count as "at rest" (spec 2026-11-17, F1).
 *
 * A pager that has settled is at offset exactly 0. A thousandth of a page is a
 * third of a pixel on a 358dp phone frame - below anything a viewer could see -
 * so it is the line between "floating point" and "a strip is showing". The
 * reported strip measured about 3% of the frame's width, thirty times this.
 */
internal const val BILLBOARD_REST_EPSILON = 0.001f

/**
 * Whether a page at rest is off-centre enough to show its neighbour (F1).
 *
 * The rule is deliberately a pure function of one float: it is the whole
 * invariant ("at rest, offset is 0") in the one place a test can state it, and
 * the composable only decides what to do about the answer.
 *
 * Symmetric on purpose. The field photo showed the NEXT page's art at the RIGHT
 * edge - which is a positive offset, the viewport shifted left - but a negative
 * one shows the PREVIOUS page at the left edge, and S1 asks for neither edge,
 * not just the one that was photographed.
 */
internal fun billboardRestOffCenter(offsetFraction: Float): Boolean =
    kotlin.math.abs(offsetFraction) > BILLBOARD_REST_EPSILON
