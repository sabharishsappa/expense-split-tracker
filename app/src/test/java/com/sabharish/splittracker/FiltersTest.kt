package com.sabharish.splittracker

import com.sabharish.splittracker.TestUtil.ms
import com.sabharish.splittracker.TestUtil.tx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FiltersTest {
    private val now = ms(2026, 11, 6)
    private fun t(id: Long, m: String, amt: Double, d: Long, kind: String = "SPEND", bank: String = "SBI", cat: String = "", body: String = "", status: String = "TAGGED") =
        tx(id, m, amt, d, status = status, kind = kind, bank = bank).copy(category = cat, body = body)
    private fun ok(t: Txn, f: TxnFilter, ss: List<Share> = emptyList()) = Filters.matches(t, ss, emptyMap(), f, now)

    @Test fun defaultFilterShowsEverything() {
        assertTrue(ok(t(1, "X", 1.0, now), TxnFilter()))
        assertFalse(TxnFilter().active)
    }

    @Test fun creditDebitUpiSplit() {
        val credit = t(1, "A", 1.0, now); val debit = t(2, "B", 1.0, now, bank = "KOTAK"); val upi = t(3, "C", 1.0, now, kind = "UPI_OUT")
        val c = TxnFilter(type = "Credit card"); val d = TxnFilter(type = "Debit card"); val u = TxnFilter(type = "UPI")
        assertEquals(listOf(true, false, false), listOf(credit, debit, upi).map { ok(it, c) })
        assertEquals(listOf(false, true, false), listOf(credit, debit, upi).map { ok(it, d) })
        assertEquals(listOf(false, false, true), listOf(credit, debit, upi).map { ok(it, u) })
    }

    @Test fun knownAndUnknownTags() {
        val tagged = t(1, "A", 1.0, now, cat = "Grocery"); val bare = t(2, "B", 1.0, now)
        assertTrue(ok(tagged, TxnFilter(tagState = "Tagged"))); assertFalse(ok(bare, TxnFilter(tagState = "Tagged")))
        assertTrue(ok(bare, TxnFilter(tagState = "Untagged"))); assertFalse(ok(tagged, TxnFilter(tagState = "Untagged")))
        assertTrue(ok(tagged, TxnFilter(tag = "grocery"))); assertFalse(ok(bare, TxnFilter(tag = "Grocery")))
    }

    @Test fun dateRanges() {
        val today = t(1, "A", 1.0, now); val lastMonth = t(2, "B", 1.0, ms(2026, 10, 15)); val old = t(3, "C", 1.0, ms(2026, 6, 1))
        assertEquals(listOf(true, false, false), listOf(today, lastMonth, old).map { ok(it, TxnFilter(range = "This month")) })
        assertEquals(listOf(false, true, false), listOf(today, lastMonth, old).map { ok(it, TxnFilter(range = "Last month")) })
        assertEquals(listOf(true, true, false), listOf(today, lastMonth, old).map { ok(it, TxnFilter(range = "Last 90 days")) })
        assertEquals(listOf(true, false, false), listOf(today, lastMonth, old).map { ok(it, TxnFilter(range = "Last 7 days")) })
    }

    @Test fun searchByAmountNameMessageAndPerson() {
        val x = t(1, "Kings and Queens", 1234.5, now, body = "Rs.1,234.50 spent on your SBI Credit Card ending 6674")
        val ss = listOf(Share(1, 1, "Rajesh", 100.0, 0.0))
        assertTrue(Filters.textMatches(x, ss, emptyMap(), "kings"))
        assertTrue(Filters.textMatches(x, ss, emptyMap(), "1234.50"))
        assertTrue(Filters.textMatches(x, ss, emptyMap(), "₹1,234.5"))
        assertTrue(Filters.textMatches(x, ss, emptyMap(), "ending 6674"))
        assertTrue(Filters.textMatches(x, ss, emptyMap(), "rajesh queens"))
        assertFalse(Filters.textMatches(x, ss, emptyMap(), "swiggy"))
        assertFalse(Filters.textMatches(x, ss, emptyMap(), "999"))
    }

    @Test fun searchUsesFriendlyNameToo() {
        val x = t(1, "RAZDREAMPLUGPAYTECHSOL", 10.0, now)
        assertTrue(Filters.textMatches(x, emptyList(), emptyMap(), "cred"))
    }

    @Test fun statusFilters() {
        val inbox = t(1, "A", 1.0, now, status = "INBOX"); val failed = t(2, "B", 1.0, now, status = "FAILED")
        val refund = t(3, "C", 1.0, now, kind = "REFUND", status = "REFUND")
        assertTrue(ok(inbox, TxnFilter(status = "Needs you"))); assertFalse(ok(failed, TxnFilter(status = "Needs you")))
        assertTrue(ok(failed, TxnFilter(status = "Failed"))); assertTrue(ok(refund, TxnFilter(status = "Refunds")))
        val owing = listOf(Share(1, 1, "Raj", 50.0, 0.0)); val paid = listOf(Share(2, 1, "Raj", 50.0, 50.0))
        assertTrue(ok(inbox, TxnFilter(status = "Pending"), owing)); assertFalse(ok(inbox, TxnFilter(status = "Pending"), paid))
    }

    @Test fun filtersCombine() {
        val x = t(1, "Swiggy", 300.0, now, cat = "Food", kind = "UPI_OUT")
        assertTrue(ok(x, TxnFilter(type = "UPI", tag = "Food", range = "This month", query = "swiggy 300")))
        assertFalse(ok(x, TxnFilter(type = "UPI", tag = "Food", range = "Last month")))
    }
}
