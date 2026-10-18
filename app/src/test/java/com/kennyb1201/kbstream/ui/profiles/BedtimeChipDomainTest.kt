package com.kennyb1201.kbstream.ui.profiles

import com.kennyb1201.kbstream.data.sync.KidsTimeGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bedtime row's chip domain, end to end.
 *
 * The bug this pins: the chips were PM hours 1..11 while the stored value is
 * minutes-after-midnight. A saved 9 PM (1260) loaded back as the chip value 21
 * - which matches no chip - and the next save of that untouched row wrote
 * `((21 + 12) % 24) * 60` = 540 = 9:00 AM. Since KidsTimeGuard locks from the
 * cutoff until 4:00 AM, that profile went from locking 9 PM-4 AM to locking
 * 9 AM-4 AM, i.e. most of the day.
 *
 * Everything therefore converts through [bedtimeChipFor] and
 * [bedtimeMinutesFor], and the case that matters is that a load followed by an
 * unmodified save is the identity.
 */
class BedtimeChipDomainTest {

    @Test
    fun `every PM hour round-trips through the chip unchanged`() {
        for (hour in 13..23) {
            val stored = hour * 60
            val chip = bedtimeChipFor(stored)
            assertEquals("$hour:00 should load as its PM chip", hour - 12, chip)
            assertEquals(
                "$hour:00 must survive a load-then-save",
                stored,
                bedtimeMinutesFor(chip, 0)
            )
        }
    }

    @Test
    fun `9 PM loads on the 9 PM chip and saves back as 9 PM`() {
        // The field case: 21:00 is 1260, and 1260 must not become 540.
        val chip = bedtimeChipFor(21 * 60)
        assertEquals(BEDTIME_CHIP_DEFAULT, chip)
        assertEquals("9 PM", bedtimeChipLabel(chip))
        assertEquals(21 * 60, bedtimeMinutesFor(chip, 0))
    }

    @Test
    fun `a corrupted 9 AM cutoff is repaired to 9 PM by the next save`() {
        // Values already written by the bug. 9 AM is not a bedtime, so the PM
        // twin is both the only representable answer and the one the parent
        // originally asked for.
        assertEquals(9, bedtimeChipFor(9 * 60))
        assertEquals(21 * 60, bedtimeMinutesFor(bedtimeChipFor(9 * 60), 0))
    }

    @Test
    fun `off is chip zero and saves as off`() {
        assertEquals(BEDTIME_CHIP_OFF, bedtimeChipFor(0))
        assertEquals("Off", bedtimeChipLabel(BEDTIME_CHIP_OFF))
        assertEquals(0, bedtimeMinutesFor(BEDTIME_CHIP_OFF, 0))
        // Off must never be the selected chip for a live bedtime: 0 is a chip
        // value, so the row can now show it selected without a -1 sentinel.
        assertTrue(bedtimeChipFor(21 * 60) != BEDTIME_CHIP_OFF)
    }

    @Test
    fun `midnight is representable and locks the 4 AM window`() {
        // 0 minutes is the Off value, so midnight is stored as 24:00.
        val midnight = bedtimeMinutesFor(BEDTIME_CHIP_MIDNIGHT, 0)
        assertEquals(24 * 60, midnight)
        assertEquals(BEDTIME_CHIP_MIDNIGHT, bedtimeChipFor(midnight))
        assertEquals("12 AM", bedtimeChipLabel(BEDTIME_CHIP_MIDNIGHT))

        // Locks from the cutoff until 4 AM, so 23:00 is still the evening.
        assertFalse(KidsTimeGuard.isBedtimeLocked(23 * 60, midnight))
        assertTrue(KidsTimeGuard.isBedtimeLocked(30, midnight))
        assertTrue(KidsTimeGuard.isBedtimeLocked(2 * 60, midnight))
        assertFalse(KidsTimeGuard.isBedtimeLocked(10 * 60, midnight))
    }

    @Test
    fun `the preserved minute survives a load and save`() {
        val stored = 21 * 60 + 30 // 9:30 PM
        val chip = bedtimeChipFor(stored)
        assertEquals(9, chip)
        assertEquals(30, stored % 60)
        assertEquals(stored, bedtimeMinutesFor(chip, stored % 60))
    }

    @Test
    fun `noon - the one unrepresentable PM value - resolves to a selectable chip`() {
        // A stored noon cutoff would lock noon-4 AM (most of the day) and the
        // row cannot express it. It must still land on a real chip, and the
        // one that shows the lock as off is the honest answer.
        assertEquals(BEDTIME_CHIP_OFF, bedtimeChipFor(12 * 60))
    }
}
