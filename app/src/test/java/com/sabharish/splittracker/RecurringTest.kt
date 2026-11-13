package com.sabharish.splittracker

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurringTest {
    private fun at(y: Int, m0: Int, d: Int, h: Int = 12) = Calendar.getInstance().apply { clear(); set(y, m0, d, h, 0, 0) }.timeInMillis
    private fun r(day: Int, last: String) = Recurring("1", "Salary", 10000.0, "Income", "Salary", "CASH|", day, last)

    @Test fun roundTrips() {
        val l = listOf(r(5, "2026-10"), r(28, ""))
        assertEquals(l, Recurrings.decode(Recurrings.encode(l)))
    }
    @Test fun dueOnceWhenDayPassed() {
        val due = Recurrings.due(listOf(r(5, "2026-10")), at(2026, 10, 11))
        assertEquals(1, due.size)
        assertEquals(at(2026, 10, 5, 9), due[0].second)
    }
    @Test fun notDueBeforeDay() = assertTrue(Recurrings.due(listOf(r(20, "2026-10")), at(2026, 10, 11)).isEmpty())
    @Test fun missedMonthsAreCaughtUp() = assertEquals(3, Recurrings.due(listOf(r(5, "2026-08")), at(2026, 10, 11)).size)
    @Test fun alreadyCreatedMonthIsSkipped() = assertTrue(Recurrings.due(listOf(r(5, "2026-11")), at(2026, 10, 11)).isEmpty())
    @Test fun newItemDoesNotBackfill() {
        val now = at(2026, 10, 11)
        assertEquals("2026-11", Recurrings.startLast(5, now))
        assertEquals("2026-10", Recurrings.startLast(20, now))
    }
}
