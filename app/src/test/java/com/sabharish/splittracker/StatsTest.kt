package com.sabharish.splittracker

import java.util.Calendar
import org.junit.Assert.*
import org.junit.Test

class StatsTest {
    private fun day(y: Int, m0: Int, d: Int) = Calendar.getInstance().apply { clear(); set(y, m0, d, 12, 0) }.timeInMillis
    private fun t(id: Long, at: Long, amt: Double, status: String = "TAGGED", kind: String = "UPI_OUT", cat: String = "Food", note: String = "") =
        Txn(id = id, bank = "HDFC", card = "1", kind = kind, amount = amt, merchant = "x", txnDate = at, smsTime = at, status = status, category = cat, note = note)
    private fun me(id: Long, amt: Double) = Share(txnId = id, person = "Me", amount = amt, settled = amt)
    private fun other(id: Long, amt: Double) = Share(txnId = id, person = "Ravi", amount = amt, settled = 0.0)

    @Test fun expenseIsYourShareOfATaggedSpend() =
        assertEquals(50.0, Stats.expenseOf(t(1, 0, 150.0), listOf(me(1, 50.0), other(1, 100.0))), 0.001)

    @Test fun draftsAreNotCountedUntilConfirmed() {
        assertEquals(0.0, Stats.expenseOf(t(1, 0, 80.0, "INBOX"), emptyList()), 0.001)
        assertEquals(0.0, Stats.expenseOf(t(2, 0, 80.0).copy(auto = true), listOf(me(2, 80.0))), 0.001)
        assertEquals(80.0, Stats.expenseOf(t(3, 0, 80.0), listOf(me(3, 80.0))), 0.001)
        assertTrue(Drafts.isSpendDraft(t(4, 0, 10.0, "INBOX"))); assertFalse(Drafts.isDraft(t(5, 0, 10.0)))
    }

    @Test fun ownTransfersFailedAndReversedAreNotExpenses() {
        for (s in listOf("SELF", "FAILED", "REVERSED", "IGNORED", "BILLPAY")) assertEquals(0.0, Stats.expenseOf(t(1, 0, 80.0, s), emptyList()), 0.001)
    }

    @Test fun onlyNamedIncomeIsIncome() {
        assertEquals(50000.0, Stats.incomeOf(t(1, 0, 50000.0, "RECEIVED", "UPI_IN", note = "Received: Salary")), 0.001)
        assertEquals(0.0, Stats.incomeOf(t(2, 0, 500.0, "RECEIVED", "UPI_IN", note = "Borrowed from Ravi · you owe this back")), 0.001)
        assertEquals(0.0, Stats.incomeOf(t(3, 0, 500.0, "REPAID", "UPI_IN")), 0.001)
    }

    // "COUNTED" stands for any confirmed status without a split: these tests are about adding rows up
    @Test fun dayTotalsGroupByDay() {
        val rows = listOf(t(1, day(2026, 10, 3), 100.0, "COUNTED"), t(2, day(2026, 10, 3), 40.0, "COUNTED"),
            t(3, day(2026, 10, 9), 5000.0, "RECEIVED", "UPI_IN", note = "Received: Salary"), t(4, day(2026, 9, 3), 999.0, "COUNTED"))
        val d = Stats.dayTotals(rows, emptyMap(), 2026, 10)
        assertEquals(140.0, d[3]!!.spent, 0.001); assertEquals(5000.0, d[9]!!.income, 0.001); assertNull(d[4])
        assertEquals(2, d.size)
    }

    @Test fun categoriesBiggestFirstUntaggedNamed() {
        val rows = listOf(t(1, day(2026, 10, 1), 100.0, "COUNTED", cat = "Food"), t(2, day(2026, 10, 2), 300.0, "COUNTED", cat = ""), t(3, day(2026, 10, 2), 50.0, "COUNTED", cat = "Food"))
        assertEquals(listOf("Untagged" to 300.0, "Food" to 150.0), Stats.byCategory(rows, emptyMap(), 2026, 10))
    }

    @Test fun trendCoversLastMonthsAcrossYearEnd() {
        val rows = listOf(t(1, day(2026, 0, 5), 10.0, "COUNTED"), t(2, day(2025, 11, 5), 20.0, "COUNTED"))
        val tr = Stats.trend(rows, emptyMap(), 2026, 0, 3)
        assertEquals(listOf("Nov", "Dec", "Jan"), tr.map { it.first }); assertEquals(listOf(0.0, 20.0, 10.0), tr.map { it.second })
    }

    @Test fun shiftHandlesYearBoundaries() {
        assertEquals(2025 to 11, Stats.shift(2026, 0, -1)); assertEquals(2027 to 0, Stats.shift(2026, 11, 1))
    }

    @Test fun pieMergesTheTailIntoOthers() {
        val s = Stats.slices((1..8).map { "T$it" to (100.0 - it) }, 4)
        assertEquals(4, s.size); assertEquals("Others", s.last().label); assertEquals(1f, s.sumOf { it.fraction.toDouble() }.toFloat(), 0.001f)
        assertTrue(Stats.slices(emptyList()).isEmpty())
    }

    @Test fun calendarWeeksStartOnMonday() {
        val w = Stats.weeks(2026, 10)       // 1 Nov 2026 is a Sunday
        assertEquals(7, w[0].size); assertNull(w[0][5]); assertEquals(1, w[0][6]); assertEquals(30, w.last().filterNotNull().last())
        assertTrue(w.all { it.size == 7 })
    }
}

class BudgetsTest {
    @Test fun roundTrip() {
        val m = mapOf("Total" to 20000.0, "Food" to 4500.5)
        assertEquals(m, Budgets.decode(Budgets.encode(m)))
        assertTrue(Budgets.decode("garbage|=5|x=abc|y=-3").isEmpty())
    }
    @Test fun setAndClear() {
        val m = Budgets.set(emptyMap(), "Food", 100.0)
        assertEquals(100.0, m["Food"]!!, 0.001); assertTrue(Budgets.set(m, "Food", null).isEmpty()); assertTrue(Budgets.set(m, "Food", 0.0).isEmpty())
    }
    @Test fun stateThresholds() {
        assertEquals(Budgets.State.OK, Budgets.state(50.0, 100.0)); assertEquals(Budgets.State.CLOSE, Budgets.state(80.0, 100.0))
        assertEquals(Budgets.State.OVER, Budgets.state(101.0, 100.0)); assertEquals(Budgets.State.OK, Budgets.state(10.0, 0.0))
    }
    @Test fun fractionIsClamped() { assertEquals(1f, Budgets.fraction(500.0, 100.0), 0f); assertEquals(0.25f, Budgets.fraction(25.0, 100.0), 0.0001f) }
    @Test fun safeToSpendPerDay() {
        assertEquals(100.0, Budgets.perDay(3000.0, 1000.0, 20), 0.001); assertEquals(0.0, Budgets.perDay(1000.0, 1500.0, 10), 0.001)
        assertEquals(0.0, Budgets.perDay(1000.0, 0.0, 0), 0.001)
    }
}
