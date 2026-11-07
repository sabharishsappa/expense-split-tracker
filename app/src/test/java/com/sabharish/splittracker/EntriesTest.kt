package com.sabharish.splittracker

import org.junit.Assert.*
import org.junit.Test

class EntriesTest {
    private val now = 1_000_000L
    private fun txn(kind: String, bank: String, card: String, amt: Double, t: Long, status: String = "INBOX") =
        Txn(id = t, bank = bank, card = card, kind = kind, amount = amt, merchant = "x", txnDate = t, smsTime = t, status = status)

    @Test fun cashSpendHasNoAccountDigits() {
        val t = Entries.spent(Accounts.CASH, " Tea ", 40.0, now)
        assertEquals("CASH", t.bank); assertEquals("", t.card); assertEquals("Tea", t.merchant); assertEquals("INBOX", t.status)
    }
    @Test fun spentFromBankAccountUsesItsKey() {
        val t = Entries.spent("HDFC|1234", "", 90.0, now)
        assertEquals("HDFC", t.bank); assertEquals("1234", t.card); assertEquals("Added by me", t.merchant)
    }
    @Test fun movedMakesAMatchedDebitAndCredit() {
        val (out, inn) = Entries.moved("HDFC|1234", Accounts.CASH, "Hdfc ••1234", "Cash", 500.0, now)
        assertEquals("UPI_OUT", out.kind); assertEquals("UPI_IN", inn.kind)
        assertEquals("SELF", out.status); assertEquals("SELF", inn.status)
        assertEquals("To Cash", out.merchant); assertEquals("From Hdfc ••1234", inn.merchant)
    }
    @Test fun movedChangesBothBalancesButNotSpends() {
        val (out, inn) = Entries.moved("HDFC|1234", Accounts.CASH, "A", "Cash", 500.0, now)
        val det = Accounts.detected(listOf(out, inn))
        val bank = Account("HDFC|1234", "HDFC", "1234", "", 1000.0, 0)
        val cash = Account(Accounts.CASH, "CASH", "", "", 100.0, 0)
        assertEquals(500.0, Accounts.balance(bank, listOf(out, inn), det), 0.001)
        assertEquals(600.0, Accounts.balance(cash, listOf(out, inn), det), 0.001)
    }
    @Test fun cashIsLabelledAndAlwaysOffered() {
        assertEquals("Cash", Accounts.label("CASH", ""))
        val o = Accounts.options(listOf(AccountView("HDFC|1234", "Hdfc", false, 0.0, 0, "")))
        assertEquals(listOf("HDFC|1234", Accounts.CASH), o.map { it.key })
        assertEquals(1, Accounts.options(o).count { it.key == Accounts.CASH })
    }
    @Test fun widerWindowPairsALateNeftCredit() {
        val out = txn("UPI_OUT", "HDFC", "1234", 5000.0, now)
        val late = txn("UPI_IN", "KOTAK", "9999", 5000.0, now + 3_600_000L)
        assertNull(TransferMatcher.find(out, listOf(late)))
        assertEquals(late, TransferMatcher.find(out, listOf(late), Entries.DAYS3))
    }
    @Test fun sameAccountNeverPairs() {
        val out = txn("UPI_OUT", "HDFC", "1234", 100.0, now)
        assertNull(TransferMatcher.find(out, listOf(txn("UPI_IN", "HDFC", "1234", 100.0, now + 1000)), Entries.DAYS3))
    }
}

class TransfersMergeTest {
    private fun row(id: Long, kind: String, bank: String, card: String, amt: Double, at: Long, status: String = "SELF") =
        Txn(id = id, bank = bank, card = card, kind = kind, amount = amt, merchant = "x", txnDate = at, smsTime = at, status = status)

    @Test fun debitAndCreditShowAsOneEntry() {
        val rows = listOf(row(1, "UPI_OUT", "HDFC", "1234", 500.0, 1000), row(2, "UPI_IN", "KOTAK", "9999", 500.0, 1500))
        val m = Transfers.merge(rows)
        assertEquals(listOf(1L), m.shown.map { it.id }); assertEquals(2L, m.partner[1L]!!.id)
    }
    @Test fun movedEntriesMergeToo() {
        val (o, i) = Entries.moved("HDFC|1234", Accounts.CASH, "A", "Cash", 100.0, 5000)
        val m = Transfers.merge(listOf(o.copy(id = 1), i.copy(id = 2)))
        assertEquals(1, m.shown.size)
    }
    @Test fun twoSameAmountTransfersPairByCloseness() {
        val rows = listOf(
            row(1, "UPI_OUT", "HDFC", "1234", 200.0, 0), row(2, "UPI_OUT", "HDFC", "1234", 200.0, 100_000_000),
            row(3, "UPI_IN", "KOTAK", "9999", 200.0, 100_000_500), row(4, "UPI_IN", "KOTAK", "9999", 200.0, 500)
        )
        val m = Transfers.merge(rows)
        assertEquals(4L, m.partner[1L]!!.id); assertEquals(3L, m.partner[2L]!!.id); assertEquals(listOf(1L, 2L), m.shown.map { it.id })
    }
    @Test fun sameAccountNeverPairsAndLoneEntriesStay() {
        val rows = listOf(row(1, "UPI_OUT", "HDFC", "1234", 50.0, 0), row(2, "UPI_IN", "HDFC", "1234", 50.0, 10), row(3, "UPI_OUT", "SBI", "1111", 70.0, 0))
        assertEquals(3, Transfers.merge(rows).shown.size)
    }
    @Test fun ordinaryEntriesAreUntouched() {
        val rows = listOf(row(1, "UPI_OUT", "HDFC", "1234", 50.0, 0, "TAGGED"), row(2, "UPI_IN", "KOTAK", "9999", 50.0, 10, "RECEIVED"))
        assertEquals(2, Transfers.merge(rows).shown.size)
    }
}
