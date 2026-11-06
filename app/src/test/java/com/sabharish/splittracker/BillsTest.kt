package com.sabharish.splittracker

import com.sabharish.splittracker.TestUtil.ms
import com.sabharish.splittracker.TestUtil.ymd
import org.junit.Assert.*
import org.junit.Test

class BillParserTest {
    private fun b(body: String): ParsedBill = BillParser.parse(body).also { assertNotNull("should parse: $body", it) }!!

    @Test fun sbi() {
        val x = b("E-statement of SBI Credit Card ending XX74 dated 03/06/2025 has been mailed. If not received, SMS ENRS to 5676791. Total Amt Due Rs 23351; Min Amt Due Rs 2287; Payable by 23/06/2025. Click https://sbicard.com/quickpaynet to pay your bill")
        assertEquals("SBI", x.bank); assertEquals("74", x.card); assertEquals(23351.0, x.total, 0.0); assertEquals(2287.0, x.minDue, 0.0)
        assertEquals("2025-06-23", ymd(x.due))
    }
    @Test fun iciciOld() {
        val x = b("ICICI Bank Credit Card XX6007 Statement is sent to sa**********sh@gmail.com. Total of Rs 13.96 or minimum of Rs 13.96 is due by 02-AUG-25.")
        assertEquals("6007", x.card); assertEquals(13.96, x.total, 0.0); assertEquals("2025-08-02", ymd(x.due))
    }
    @Test fun iciciNew() {
        val x = b("Pay Total Due of Rs 72,256.54 or Minimum Due Rs 3,620.00 by 02-Nov-25 for ICICI Bank Credit Card XX6007. Delayed/No payments are reported to Credit Bureaus.")
        assertEquals(72256.54, x.total, 0.0); assertEquals(3620.0, x.minDue, 0.0); assertEquals("2025-11-02", ymd(x.due))
        val y = b("Pay Total Amount Due of Rs 23,177.24 or Minimum Amount Due of Rs 1,160.00 by 05-Mar-26 towards ICICI Bank Credit Card XX6007. Delay/Non-payment is reported to Credit Bureaus. Ignore if paid.")
        assertEquals(23177.24, y.total, 0.0); assertEquals(1160.0, y.minDue, 0.0); assertEquals("ICICI", y.bank)
    }
    @Test fun indusind() {
        val x = b("15/10/25 Stmt Alert: Total Amount Due on your IndusInd Bank Credit Card XXXX4122 is INR 1,947.00 and Minimum Amount Due is INR 100.00, payable by 04/11/25. Payment to be made immediately if previous statement dues are unpaid. Click https://x to pay now - IndusInd Bank")
        assertEquals("INDUSIND", x.bank); assertEquals("4122", x.card); assertEquals("2025-11-04", ymd(x.due))
    }
    @Test fun hdfc() {
        val x = b("HDFC Bank Credit Card XX5033 Statement:\nTotal due: Rs.2,238.00\nMin.due: Rs.200.00\nPay by 05-03-2026\nView: https://1.hdfc.bank.in/HDFCBK/a/13wM7Zg")
        assertEquals("HDFC", x.bank); assertEquals(2238.0, x.total, 0.0); assertEquals(200.0, x.minDue, 0.0); assertEquals("2026-03-05", ymd(x.due))
    }
    @Test fun yes() {
        val x = b("YES BANK Credit Card XX3121 APR-26 statement: Total due INR 239.00  Min due INR 200.00 Due by 06-MAY-2026. Please pay full outstanding.")
        assertEquals("YES", x.bank); assertEquals("3121", x.card); assertEquals("2026-05-06", ymd(x.due))
    }
    @Test fun credUsesBankAndCard() {
        val x = b("Your credit card bill for ICICI Bank XXXX-2000 has been generated.\n\nTotal amount: INR 1,602.66\nDue date: February 02, 2026\n\nTap on the link to pay: https://link.cred.club/x and avoid late payment fees. - CRED")
        assertEquals("ICICI", x.bank); assertEquals("2000", x.card); assertEquals(0.0, x.minDue, 0.0); assertEquals("2026-02-02", ymd(x.due))
        assertEquals("INDUSIND", b("Your credit card bill for IndusInd Bank XXXX-2519 has been generated.\nTotal amount: INR 1,947.00\nDue date: February 04, 2026").bank)
    }
    @Test fun noiseIsIgnored() {
        listOf(
            "PayU*CRED refund of Rs 2.00 credited to ICICI Bank Credit Card XX2000 on 21-JUL-25. Revised total due Rs 0, minimum due Rs .00",
            "15/10/25 Stmt Alert: Total Amount Due on your IndusInd Bank Credit Card XXXX4122 is INR 0.00 and Minimum Amount Due is INR 0.00, payable by 04/11/25.",
            "HDFC Bank Credit Card XX5033 Statement:\nTotal due: Rs.-,744.90\nMin.due: Rs.0.00\nPay by Nil",
            "Card xx4122 bill due soon? Convert your outstanding to EMI before the due date https://x Ignore if paid",
            "Minimum Amount Due calculation for ICICI Bank Credit Card XX2000 is updated. Visit https://icici.co/x for details.",
            "Update from CRED:\n\nYour credit card bill has been generated.\n\nTotal amount: INR 45,949.32\nDue Date: January 23, 2026",
            "Dear Cardholder, Auto Debit facility has been activated. The amount deductible through Auto Debit will be the TAD.",
            "", "hello"
        ).forEach { assertNull("should ignore: $it", BillParser.parse(it)) }
    }
    @Test fun billSmsIsNotATransaction() {
        assertNull(SmsParser.parse("Pay Total Due of Rs 72,256.54 or Minimum Due Rs 3,620.00 by 02-Nov-25 for ICICI Bank Credit Card XX6007."))
    }
}

class BillLogicTest {
    private val t0 = ms(2026, 11, 6)
    private fun bill(id: Long, bank: String = "ICICI", card: String = "6007", total: Double = 1000.0, min: Double = 100.0, due: Long = ms(2026, 11, 20), sms: Long = ms(2026, 11, 1), paid: Boolean = false) =
        Bill(id, bank, card, total, min, due, sms, paid)

    @Test fun sameCardAllowsShortSbiDigits() {
        assertTrue(Bills.sameCard("74", "6674")); assertTrue(Bills.sameCard("6674", "74"))
        assertFalse(Bills.sameCard("1234", "6674")); assertTrue(Bills.sameCard("SBI", "6674"))
    }
    @Test fun daysAndUrgency() {
        assertEquals(0, Bills.daysLeft(ms(2026, 11, 6, 23), ms(2026, 11, 6, 1)))
        assertEquals(14, Bills.daysLeft(ms(2026, 11, 20), t0))
        assertEquals(-2, Bills.daysLeft(ms(2026, 11, 4), t0))
        assertEquals("OVERDUE", Bills.urgency(-1)); assertEquals("SOON", Bills.urgency(0)); assertEquals("SOON", Bills.urgency(3)); assertEquals("LATER", Bills.urgency(4))
        assertEquals("due today", Bills.dueLine(0)); assertEquals("due tomorrow", Bills.dueLine(1)); assertEquals("5 days left", Bills.dueLine(5))
        assertEquals("1 day overdue", Bills.dueLine(-1)); assertEquals("3 days overdue", Bills.dueLine(-3))
    }
    @Test fun paymentSettlesMatchingCardOnly() {
        val bills = listOf(bill(1), bill(2, card = "2000"), bill(3, bank = "HDFC", card = "5033"))
        assertEquals(1L, Bills.billPaidBy(bills, "ICICI", "6007", 1000.0, ms(2026, 11, 5))!!.id)
        assertEquals(2L, Bills.billPaidBy(bills, "ICICI", "2000", 100.0, ms(2026, 11, 5))!!.id)
        assertNull(Bills.billPaidBy(bills, "ICICI", "9999", 1000.0, ms(2026, 11, 5)))
    }
    @Test fun paymentBelowMinimumDoesNotSettle() {
        assertNull(Bills.billPaidBy(listOf(bill(1)), "ICICI", "6007", 50.0, ms(2026, 11, 5)))
    }
    @Test fun paymentBeforeStatementDoesNotSettle() {
        assertNull(Bills.billPaidBy(listOf(bill(1, sms = ms(2026, 11, 10))), "ICICI", "6007", 1000.0, ms(2026, 11, 2)))
    }
    @Test fun paymentWithoutCardMatchesBankAndOldestFirst() {
        val bills = listOf(bill(1, due = ms(2026, 12, 2)), bill(2, due = ms(2026, 11, 2)))
        assertEquals(2L, Bills.billPaidBy(bills, "ICICI", "ICICI", 1000.0, ms(2026, 11, 5))!!.id)
    }
    @Test fun paidBillsAreSkipped() {
        assertNull(Bills.billPaidBy(listOf(bill(1, paid = true)), "ICICI", "6007", 1000.0, ms(2026, 11, 5)))
    }
    @Test fun sameStatementNotAddedTwice() {
        val p = ParsedBill("ICICI", "6007", 1000.0, 100.0, ms(2026, 11, 20, 9))
        assertTrue(Bills.isSame(bill(1), p))
        assertFalse(Bills.isSame(bill(1, due = ms(2026, 12, 20)), p))
        assertFalse(Bills.isSame(bill(1, bank = "HDFC"), p))
    }
    @Test fun newStatementSupersedesOlderUnpaid() {
        val bills = listOf(bill(1, due = ms(2026, 10, 20)), bill(2, due = ms(2026, 10, 20), card = "2000"), bill(3, due = ms(2026, 12, 20)))
        val gone = Bills.superseded(bills, ParsedBill("ICICI", "6007", 5.0, 1.0, ms(2026, 11, 20))).map { it.id }
        assertEquals(listOf(1L), gone)
    }
    @Test fun trackFromOptionsStartAtMidnightAndAreDistinct() {
        val o = Bills.trackFromOptions(ms(2026, 11, 6))
        assertTrue(o.size >= 3); assertEquals(o.size, o.map { it.second }.distinct().size)
        assertTrue(o.any { ymd(it.second) == "2026-11-01" })
        assertTrue(o.all { it.second <= ms(2026, 11, 6) })
    }
}
