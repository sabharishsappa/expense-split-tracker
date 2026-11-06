package com.sabharish.splittracker

import com.sabharish.splittracker.TestUtil.tx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountsTest {
    private val min = 60_000L
    private fun up(id: Long, kind: String, amt: Double, t: Long, bank: String, card: String, status: String = "INBOX", merchant: String = "X") =
        tx(id, merchant, amt, t, status = status, kind = kind, bank = bank, card = card)

    private val acct = Account("KOTAK|2670", "KOTAK", "2670", "", 10_000.0, 1_000)

    @Test fun balanceMovesWithUpiInAndOut() {
        val txns = listOf(
            up(1, "UPI_OUT", 500.0, 2_000, "KOTAK", "2670"),
            up(2, "UPI_IN", 200.0, 3_000, "KOTAK", "2670"),
            up(3, "UPI_OUT", 1_000.0, 4_000, "SBI", "3157")          // other account: ignored
        )
        assertEquals(9_700.0, Accounts.balance(acct, txns, Accounts.detected(txns)), 0.001)
    }

    @Test fun messagesBeforeTheBalanceWasEnteredAreIgnored() {
        val txns = listOf(up(1, "UPI_OUT", 500.0, 500, "KOTAK", "2670"), up(2, "UPI_OUT", 100.0, 1_500, "KOTAK", "2670"))
        assertEquals(9_900.0, Accounts.balance(acct, txns, Accounts.detected(txns)), 0.001)
    }

    @Test fun ownTransfersStillMoveBalances() {
        val txns = listOf(up(1, "UPI_OUT", 700.0, 2_000, "KOTAK", "2670", status = "SELF"))
        assertEquals(9_300.0, Accounts.balance(acct, txns, Accounts.detected(txns)), 0.001)
    }

    @Test fun upiReversalCreditsTheAccountBack() {
        val txns = listOf(
            up(1, "UPI_OUT", 1_869.0, 2_000, "SBI", "3157"),
            up(2, "REFUND", 1_869.0, 3_000, "SBI", "3157", merchant = "UPI reversal")
        )
        val sbi = Account("SBI|3157", "SBI", "3157", "", 5_000.0, 1_000)
        assertEquals(5_000.0, Accounts.balance(sbi, txns, Accounts.detected(txns)), 0.001)
    }

    @Test fun cardRefundsAndCreditCardSpendsDoNotTouchAccounts() {
        val txns = listOf(
            up(1, "UPI_OUT", 10.0, 2_000, "KOTAK", "2670"),
            up(2, "SPEND", 999.0, 2_100, "SBI", "6674"),
            up(3, "REFUND", 300.0, 2_200, "ICICI", "6007", merchant = "AMAZON")
        )
        assertEquals(9_990.0, Accounts.balance(acct, txns, Accounts.detected(txns)), 0.001)
        assertEquals(listOf("KOTAK" to "2670"), Accounts.detected(txns))
    }

    @Test fun kotakDebitCardSpendsComeOutOfTheKotakAccount() {
        val txns = listOf(up(1, "UPI_IN", 50.0, 2_000, "KOTAK", "2670"), up(2, "SPEND", 711.0, 3_000, "KOTAK", "2159"))
        assertEquals(10_000.0 + 50 - 711, Accounts.balance(acct, txns, Accounts.detected(txns)), 0.001)
    }

    @Test fun storageRoundTrip() {
        val a = Account("HDFC|123", "HDFC", "123", "Salary; main", 1234.5, 99L)
        val back = Accounts.decode(Accounts.encode(a))!!
        assertEquals(a.copy(name = "Salary  main"), back)
        assertNull(Accounts.decode(Rule("alias|x", "ALIAS", "CRED")))
    }

    @Test fun viewsShowDetectedAccountsEvenWithoutABalance() {
        val txns = listOf(up(1, "UPI_OUT", 10.0, 2_000, "KOTAK", "2670"), up(2, "UPI_IN", 5.0, 2_100, "SBI", "3157"))
        val v = Accounts.views(txns, listOf(Accounts.encode(acct)))
        assertEquals(2, v.size)
        assertTrue(v.first { it.key == "KOTAK|2670" }.hasBalance)
        assertFalse(v.first { it.key == "SBI|3157" }.hasBalance)
        assertEquals("Kotak ••2670", v.first { it.key == "KOTAK|2670" }.label)
    }

    // ---------- transfer detection ----------
    @Test fun matchingOutAndInFromTwoBanksIsATransfer() {
        val out = up(1, "UPI_OUT", 5_000.0, 10 * min, "SBI", "3157")
        val inn = up(2, "UPI_IN", 5_000.0, 11 * min, "KOTAK", "2670")
        assertEquals(2L, TransferMatcher.find(out, listOf(inn))?.id)
        assertEquals(1L, TransferMatcher.find(inn, listOf(out))?.id)
    }

    @Test fun differentAmountOrTooFarApartIsNotATransfer() {
        val out = up(1, "UPI_OUT", 5_000.0, 10 * min, "SBI", "3157")
        assertNull(TransferMatcher.find(out, listOf(up(2, "UPI_IN", 4_999.0, 11 * min, "KOTAK", "2670"))))
        assertNull(TransferMatcher.find(out, listOf(up(3, "UPI_IN", 5_000.0, 40 * min, "KOTAK", "2670"))))
    }

    @Test fun sameAccountOrSameDirectionIsNotATransfer() {
        val out = up(1, "UPI_OUT", 5_000.0, 10 * min, "SBI", "3157")
        assertNull(TransferMatcher.find(out, listOf(up(2, "UPI_IN", 5_000.0, 11 * min, "SBI", "3157"))))
        assertNull(TransferMatcher.find(out, listOf(up(3, "UPI_OUT", 5_000.0, 11 * min, "KOTAK", "2670"))))
    }

    @Test fun alreadyHandledTransactionsAreNotReused() {
        val out = up(1, "UPI_OUT", 5_000.0, 10 * min, "SBI", "3157")
        assertNull(TransferMatcher.find(out, listOf(up(2, "UPI_IN", 5_000.0, 11 * min, "KOTAK", "2670", status = "TAGGED"))))
    }

    @Test fun closestInTimeWins() {
        val out = up(1, "UPI_OUT", 100.0, 10 * min, "SBI", "3157")
        val far = up(2, "UPI_IN", 100.0, 18 * min, "KOTAK", "2670")
        val near = up(3, "UPI_IN", 100.0, 11 * min, "ICICI", "395")
        assertEquals(3L, TransferMatcher.find(out, listOf(far, near))?.id)
        assertNotNull(TransferMatcher.find(out, listOf(far)))
    }

    @Test fun cardKindsAreNeverTransfers() {
        val spend = up(1, "SPEND", 100.0, 10 * min, "SBI", "6674")
        assertNull(TransferMatcher.find(spend, listOf(up(2, "UPI_IN", 100.0, 11 * min, "KOTAK", "2670"))))
    }
}
