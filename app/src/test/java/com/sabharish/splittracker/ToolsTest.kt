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

class UpiLinkTest {
    @Test fun buildsLink() = assertEquals("upi://pay?pa=me@okaxis&am=250.00&cu=INR&tn=Dinner%20split", UpiLink.build(" me@okaxis ", 250.0, "Dinner split"))
    @Test fun blankNoteGetsDefault() = assertTrue(UpiLink.build("a@b", 1.0, " ").endsWith("tn=Payment"))
    @Test fun reminderCarriesLinkOnlyWithUpi() {
        assertTrue(Reminder.splitRequest("Ravi", "Badminton", 1200.0, 300.0, "me@okaxis").contains("upi://pay?pa=me@okaxis&am=300.00"))
        assertFalse(Reminder.splitRequest("Ravi", "Badminton", 1200.0, 300.0, "").contains("upi://"))
    }
}

class HealthTest {
    private val day = 86_400_000L
    private fun t(smsTime: Long, body: String) = Txn(bank = "HDFC", card = "1", kind = "SPEND", amount = 1.0, merchant = "x", txnDate = smsTime, smsTime = smsTime, status = "INBOX", body = body)

    @Test fun captureIgnoresHandMadeEntries() {
        val now = 10 * day
        val line = Health.capture(listOf(t(now - 2 * 3_600_000L, "Rs 5 spent"), t(now - 60_000L, "")), now)!!
        assertEquals("Last bank message read 2 h ago", line.text)
        assertFalse(line.warn)
    }
    @Test fun captureWarnsAfterThreeDays() = assertTrue(Health.capture(listOf(t(0, "msg")), 5 * day)!!.warn)
    @Test fun noMessagesNoLine() = assertNull(Health.capture(listOf(t(5, "")), 100))
    @Test fun backupNudgeOnlyForRealData() = assertNull(Health.backup(0, false, 5, 100 * day))
    @Test fun noBackupYetWarns() = assertTrue(Health.backup(0, false, 50, 100 * day)!!.warn)
    @Test fun recentBackupIsQuiet() = assertNull(Health.backup(99 * day, false, 50, 100 * day))
    @Test fun staleAutoBackupWarnsSooner() = assertTrue(Health.backup(95 * day, true, 50, 100 * day) != null)
}

class AutoBackupDueTest {
    @Test fun dueAfterAboutADay() { assertTrue(AutoBackup.due(0, 86_400_000L)); assertFalse(AutoBackup.due(1_000, 3_600_000L)) }
}

class JumpTest {
    @Test fun untaggedFilterShape() = assertEquals("Untagged", TxnFilter(tagState = "Untagged").tagState)
}

class InsightsTest {
    private fun spend(id: Long, cat: String, amt: Double, y: Int, m0: Int) = Txn(
        id = id, bank = "HDFC", card = "1", kind = "SPEND", amount = amt, merchant = "x", category = cat, status = "INBOX",
        txnDate = Calendar.getInstance().apply { clear(); set(y, m0, 10, 12, 0, 0) }.timeInMillis, smsTime = 0
    )

    @Test fun changesRankByBiggestMove() {
        val c = Stats.changes(listOf("Food" to 3000.0, "Cab" to 500.0), listOf("Food" to 1800.0, "Cab" to 480.0, "Fuel" to 900.0))
        assertEquals(listOf("Food", "Fuel", "Cab"), c.map { it.tag })
        assertEquals(1200.0, c[0].delta, 0.001)
        assertEquals(-900.0, c[1].delta, 0.001)
    }
    @Test fun tinyMovesIgnored() = assertTrue(Stats.changes(listOf("A" to 10.2), listOf("A" to 10.0)).isEmpty())
    @Test fun yearRowsSumMonths() {
        val t = listOf(spend(1, "Food", 100.0, 2026, 0), spend(2, "Food", 50.0, 2026, 0), spend(3, "Cab", 70.0, 2026, 5), spend(4, "Cab", 999.0, 2025, 5))
        val rows = Stats.yearMonths(t, emptyMap(), 2026)
        assertEquals(12, rows.size)
        assertEquals(150.0, rows[0].spent, 0.001); assertEquals(70.0, rows[5].spent, 0.001)
        assertEquals(listOf("Food" to 150.0, "Cab" to 70.0), Stats.byCategoryYear(t, emptyMap(), 2026))
    }
    @Test fun paceIsShareOfMonth() { assertEquals(0.5f, Budgets.pace(15, 30), 0.001f); assertEquals(0f, Budgets.pace(1, 0), 0.001f) }
    @Test fun suggestRoundsUp() {
        assertEquals(1300.0, Budgets.suggest(listOf(1200.0, 1250.0, 1210.0))!!, 0.001)       // avg 1220 → step 100
        assertEquals(15500.0, Budgets.suggest(listOf(15000.0, 15200.0, 15100.0))!!, 0.001)   // avg 15100 → step 500
        assertNull(Budgets.suggest(listOf(0.0, 0.0)))
    }
    @Test fun amountFilterBounds() {
        val t = spend(1, "Food", 250.0, 2026, 0)
        val now = System.currentTimeMillis()
        assertTrue(Filters.matches(t, emptyList(), emptyMap(), TxnFilter(minAmt = 200.0, maxAmt = 300.0), now))
        assertFalse(Filters.matches(t, emptyList(), emptyMap(), TxnFilter(minAmt = 300.0), now))
        assertFalse(Filters.matches(t, emptyList(), emptyMap(), TxnFilter(maxAmt = 100.0), now))
    }
}
