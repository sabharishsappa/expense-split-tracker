package com.sabharish.splittracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class CalcTest {
    @Test fun plainNumber() = assertEquals(450.0, Calc.eval("450")!!, 0.001)
    @Test fun addsAndMultipliesWithPrecedence() = assertEquals(210.0, Calc.eval("120+45×2")!!, 0.001)
    @Test fun divides() = assertEquals(25.0, Calc.eval("100÷4")!!, 0.001)
    @Test fun commasIgnored() = assertEquals(1500.0, Calc.eval("1,000+500")!!, 0.001)
    @Test fun danglingOperatorIgnored() = assertEquals(120.0, Calc.eval("120+")!!, 0.001)
    @Test fun divideByZeroIsNull() = assertNull(Calc.eval("5÷0"))
    @Test fun emptyOrJunkIsNull() { assertNull(Calc.eval("")); assertNull(Calc.eval("+5")); assertNull(Calc.eval("1.2.3")) }
    @Test fun detectsOperator() { assertTrue(Calc.hasOperator("1+2")); assertFalse(Calc.hasOperator("12")); assertFalse(Calc.hasOperator("12+")) }
}

class CsvTest {
    private fun t(id: Long, merchant: String, note: String = "", status: String = "TAGGED") =
        Txn(id = id, bank = "HDFC", card = "1234", kind = "SPEND", amount = 100.0, merchant = merchant, txnDate = 1_700_000_000_000L + id, smsTime = 0, status = status, note = note)

    @Test fun headerAndRow() {
        val out = Csv.build(listOf(t(1, "Zomato")), mapOf(1L to listOf(Share(txnId = 1, person = "Me", amount = 60.0, settled = 0.0))))
        val lines = out.trim().lines()
        assertEquals("Date,Type,Amount,Merchant,Category,Account,Status,My share,Note", lines[0])
        assertTrue(lines[1].contains(",Expense,100.00,Zomato,"))
        assertTrue(lines[1].contains(",60.00,"))
    }
    @Test fun quotesCommasAndBlocksFormulas() {
        val out = Csv.build(listOf(t(1, "A, B"), t(2, "=SUM(A1)")), emptyMap())
        assertTrue(out.contains("\"A, B\""))
        assertTrue(out.contains("'=SUM(A1)"))
    }
}

class RecurringTest {
    private fun at(y: Int, m0: Int, d: Int, h: Int = 12) = Calendar.getInstance().apply { clear(); set(y, m0, d, h, 0, 0) }.timeInMillis
    private fun r(day: Int, last: String) = Recurring("1", "Rent", 10000.0, "Expense", "Bills", "CASH|", day, last)

    @Test fun roundTrips() {
        val l = listOf(r(5, "2026-10"), r(31, ""))
        assertEquals(l, Recurrings.decode(Recurrings.encode(l)))
    }
    @Test fun dueOnceWhenDayPassed() {
        val due = Recurrings.due(listOf(r(5, "2026-10")), at(2026, 10, 11))
        assertEquals(1, due.size)
        assertEquals(at(2026, 10, 5, 9), due[0].second)
    }
    @Test fun notDueBeforeDay() = assertTrue(Recurrings.due(listOf(r(20, "2026-10")), at(2026, 10, 11)).isEmpty())
    @Test fun missedMonthsAreCaughtUp() = assertEquals(3, Recurrings.due(listOf(r(5, "2026-08")), at(2026, 10, 11)).size)
    @Test fun day31FallsOnLastDayOfShortMonth() {
        val due = Recurrings.due(listOf(r(28, "2027-01")), at(2027, 1, 28, 12))
        assertEquals(1, due.size)
    }
    @Test fun alreadyCreatedMonthIsSkipped() = assertTrue(Recurrings.due(listOf(r(5, "2026-11")), at(2026, 10, 11)).isEmpty())
    @Test fun newItemDoesNotBackfill() {
        val now = at(2026, 10, 11)
        assertEquals("2026-11", Recurrings.startLast(5, now))   // day already passed this month: counted as this month's, no entry made
        assertEquals("2026-10", Recurrings.startLast(20, now))  // day still ahead: this month's entry is still to come
    }
}

class LockRuleTest {
    @Test fun locksAfterGracePeriod() = assertTrue(LockRule.shouldLock(true, 1_000, 1_000 + LockRule.GRACE + 1))
    @Test fun staysOpenForQuickReturn() = assertFalse(LockRule.shouldLock(true, 1_000, 1_000 + 5_000))
    @Test fun neverLocksWhenOff() = assertFalse(LockRule.shouldLock(false, 1_000, 10_000_000))
}
