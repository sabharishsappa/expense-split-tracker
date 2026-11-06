package com.sabharish.splittracker

import com.sabharish.splittracker.Habit.Streak
import com.sabharish.splittracker.TestUtil.ms
import com.sabharish.splittracker.TestUtil.tx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HabitTest {
    private val d5 = ms(2026, 11, 5); private val d6 = ms(2026, 11, 6); private val d9 = ms(2026, 11, 9)

    @Test fun firstClearDayStartsAtOne() { assertEquals(Streak(1, "2026-11-06"), Habit.update(Streak(0, ""), d6, true)) }
    @Test fun consecutiveDaysAdd() { assertEquals(Streak(4, "2026-11-06"), Habit.update(Streak(3, "2026-11-05"), d6, true)) }
    @Test fun sameDayIsCountedOnce() { assertEquals(Streak(3, "2026-11-06"), Habit.update(Streak(3, "2026-11-06"), d6, true)) }
    @Test fun missedDayRestarts() { assertEquals(Streak(1, "2026-11-09"), Habit.update(Streak(7, "2026-11-06"), d9, true)) }
    @Test fun notEmptyInboxChangesNothing() { assertEquals(Streak(3, "2026-11-05"), Habit.update(Streak(3, "2026-11-05"), d6, false)) }

    @Test fun shownStreakExpires() {
        assertEquals(3, Habit.shown(Streak(3, "2026-11-06"), d6))
        assertEquals(3, Habit.shown(Streak(3, "2026-11-05"), d6))
        assertEquals(0, Habit.shown(Streak(3, "2026-11-05"), d9))
    }

    @Test fun encodeDecode() {
        assertEquals(Streak(5, "2026-11-06"), Habit.decode(Habit.encode(Streak(5, "2026-11-06"))))
        assertEquals(Streak(0, ""), Habit.decode("garbage"))
    }

    @Test fun clarityIsShareOfSortedSpendsThisMonth() {
        val start = ms(2026, 11, 1)
        val list = listOf(tx(1, "A", 1.0, d6), tx(2, "B", 1.0, d6, status = "INBOX"), tx(3, "C", 1.0, d5), tx(4, "D", 1.0, d5, status = "INBOX"),
            tx(5, "Old", 1.0, ms(2026, 9, 1), status = "INBOX"), tx(6, "Own", 1.0, d6, status = "SELF"))
        assertEquals(0.5f, Habit.clarity(list, start)!!, 0.001f)
        assertNull(Habit.clarity(emptyList(), start))
    }

    @Test fun lines() {
        assertEquals("Every spend this month is sorted", Habit.clarityLine(100, 0))
        assertEquals("1 spend left to sort", Habit.clarityLine(90, 1))
        assertEquals("4 spends left to sort", Habit.clarityLine(50, 4))
    }
}
