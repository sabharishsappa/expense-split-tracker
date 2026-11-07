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
