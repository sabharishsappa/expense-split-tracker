package com.sabharish.splittracker

import org.junit.Assert.*
import org.junit.Test

/** Messages are made up in the common shapes banks use; no real person's message is in here. */
class GeneralSmsTest {
    private fun p(body: String, sender: String? = null): Parsed {
        val r = SmsParser.parse(body, sender)
        assertNotNull("should parse: $body", r)
        return r!!
    }

    @Test fun axisUpiDebit() {
        val r = p("INR 500.00 debited A/c no. XX1234 12-11-25, 10:15:30 UPI/P2M/312345678901/SWIGGY Not you? SMS BLOCKUPI Cust ID to 18002026 - Axis Bank", "AX-AXISBK-S")
        assertEquals("AXIS", r.bank); assertEquals("UPI_OUT", r.kind); assertEquals(500.0, r.amount, 0.001)
        assertEquals("1234", r.card); assertEquals("SWIGGY", r.merchant); assertNotNull(r.dateMillis)
    }
    @Test fun axisUpiCredit() {
        val r = p("INR 1,000.00 credited to A/c no. XX1234 on 12-11-25. Info- UPI/P2A/312345678902/RAVI KUMAR. Axis Bank", "AX-AXISBK-S")
        assertEquals("UPI_IN", r.kind); assertEquals(1000.0, r.amount, 0.001); assertEquals("Ravi Kumar", r.merchant); assertEquals("1234", r.card)
    }
    @Test fun hdfcUpiSent() {
        val r = p("Sent Rs.250.00 From HDFC Bank A/C *1234 To SWIGGY On 12/11/25 Ref 312345678903 Not You? Call 18002586161 To Block UPI", "VM-HDFCBK-S")
        assertEquals("HDFC", r.bank); assertEquals("UPI_OUT", r.kind); assertEquals(250.0, r.amount, 0.001); assertEquals("SWIGGY", r.merchant); assertEquals("1234", r.card)
    }
    @Test fun hdfcUpiReceived() {
        val r = p("Credit Alert! Rs.500.00 credited to HDFC Bank A/c XX1234 on 12-11-25 from VPA ravi@okhdfcbank (UPI 312345678904)", "VM-HDFCBK-S")
        assertEquals("UPI_IN", r.kind); assertEquals(500.0, r.amount, 0.001); assertEquals("ravi", r.merchant)
    }
    @Test fun bankOfBarodaDrCr() {
        val r = p("Rs.300.00 Dr. from A/C XXXXXX1234 and Cr. to x1234@ybl. Ref:312345678905. AvlBal Rs.2000(2025:11:12 10:00:00)- Bank of Baroda", "VK-BOBTXN-S")
        assertEquals("BOB", r.bank); assertEquals("UPI_OUT", r.kind); assertEquals(300.0, r.amount, 0.001); assertEquals("1234", r.card); assertEquals("x1234", r.merchant)
    }
    @Test fun canaraDebitHasNoPayeeAndIgnoresBalance() {
        val r = p("An amount of INR 300.00 has been DEBITED to your account XXX1234 on 12/11/2025. Total Avail.bal INR 1,000.00 - Canara Bank", "AD-CANBNK-S")
        assertEquals("CANARA", r.bank); assertEquals("UPI_OUT", r.kind); assertEquals(300.0, r.amount, 0.001); assertEquals("Unknown", r.merchant)
    }
    @Test fun unionAlertWithShareWarningIsNotAnOtp() {
        val r = p("A/c *1234 Debited for Rs:300.00 on 12-11-2025 20:10:12 by Mob Bk ref no 123456. Avl Bal Rs:1000.00.Never Share OTP/PIN/CVV-Union Bank of India", "VM-UNIONB-S")
        assertEquals("UNION", r.bank); assertEquals("UPI_OUT", r.kind); assertEquals(300.0, r.amount, 0.001)
    }
    @Test fun axisCardSpendTakesMerchantAfterTime() {
        val r = p("Spent INR 450.00 Axis Bank Card no. XX1234 12-11-25 10:15:30 IST SWIGGY Avl Limit: INR 50000.00 If not you? SMS BLOCKCARD to 5676782", "AX-AXISBK-S")
        assertEquals("AXIS", r.bank); assertEquals("SPEND", r.kind); assertEquals(450.0, r.amount, 0.001); assertEquals("1234", r.card); assertEquals("SWIGGY", r.merchant)
    }
    @Test fun debitCardSpendUsesBankNameFromText() {
        val r = p("Rs.450.00 spent on Federal Bank Debit Card ending 1234 at AMAZON on 12-11-25")
        assertEquals("FEDERAL", r.bank); assertEquals("SPEND", r.kind); assertEquals("AMAZON", r.merchant); assertEquals("1234", r.card)
    }
    @Test fun creditCardPayment() {
        val r = p("Payment of Rs 5,000.00 has been received towards your Federal Bank Credit Card ending 1234")
        assertEquals("PAYMENT", r.kind); assertEquals(5000.0, r.amount, 0.001); assertEquals("1234", r.card)
    }
    @Test fun impsCreditFromUnknownBankPrefersTheSender() {
        val r = p("Rs 2,500.00 credited to your A/c XX9876 on 12-11-25 by IMPS from RAVI KUMAR. Ref 312345678906. Bal Rs 10,000")
        assertEquals("BANK", r.bank); assertEquals("UPI_IN", r.kind); assertEquals(2500.0, r.amount, 0.001); assertEquals("Ravi Kumar", r.merchant); assertEquals("9876", r.card)
    }

    // ---------- not transactions ----------
    @Test fun remindersRequestsFailuresAndOffersAreIgnored() {
        listOf(
            "Rs 500.00 will be debited from your A/c XX1234 on 15-11-25 towards EMI",
            "Dear customer, Rs 200 has been requested via UPI collect request by ravi@ybl. Approve on your UPI app",
            "Your txn of Rs 100 on A/c XX1234 has failed. Amount will be reversed",
            "Get Rs 500 cashback credited to your wallet. Click link",
            "Your OTP for payment of Rs 500 at AMAZON is 123456",
            "123456 is your OTP to pay Rs 500 from A/c XX1234",
            "Avl Bal Rs 1000 in A/c XX1234"
        ).forEach { assertNull(it, SmsParser.parse(it)) }
    }
}

class BanksTest {
    @Test fun senderIdsGiveTheBank() {
        assertEquals("HDFC", Banks.fromSender("VM-HDFCBK-S"))
        assertEquals("SBI", Banks.fromSender("JD-SBIUPI-S"))
        assertEquals("AXIS", Banks.fromSender("AX-AXISBK"))
        assertEquals("KOTAK", Banks.fromSender("BP-KOTAKB-T"))
        assertEquals("HDFC", Banks.fromSender("HDFCBK"))
        assertNull(Banks.fromSender("VM-ZOMATO-S"))
        assertNull(Banks.fromSender(null))
    }
    @Test fun bankNamesInTextAreFoundLongestFirst() {
        assertEquals("FEDERAL", Banks.fromText("towards your Federal Bank Credit Card"))
        assertEquals("HDFC", Banks.fromText("Spent on HDFC Bank Card"))
        assertNull(Banks.fromText("Hello there"))
    }
}

class ReadCheckTest {
    private fun r(sms: Boolean = true, inbox: Int = 50, scanned: Int = 20, good: Int = 0, bad: Int = 0, sdk: Int = 34, notif: Boolean = true, recv: Boolean = true) =
        Diagnostics.ReadCheck(sms, recv, notif, inbox, scanned, good, bad, listOf("AX-AXISBK").take(if (bad > 0) 1 else 0), sdk)

    @Test fun deniedPermissionOnNewAndroidExplainsRestrictedSettings() {
        val v = Diagnostics.verdict(r(sms = false)).joinToString(" ")
        assertTrue(v.contains("not allowed to read messages")); assertTrue(v.contains("Allow restricted settings"))
    }
    @Test fun deniedPermissionOnOlderAndroidJustSaysAllow() {
        val v = Diagnostics.verdict(r(sms = false, sdk = 30)).joinToString(" ")
        assertFalse(v.contains("restricted")); assertTrue(v.contains("allow SMS"))
    }
    @Test fun workingReadingReportsTheCount() = assertTrue(Diagnostics.verdict(r(good = 7)).first().contains("7 bank messages"))
    @Test fun notUnderstoodMessagesAreReported() {
        val v = Diagnostics.verdict(r(good = 0, bad = 4)).joinToString(" ")
        assertTrue(v.contains("4 look like bank transactions")); assertTrue(v.contains("Copy without numbers"))
    }
    @Test fun emptyInboxIsExplained() = assertTrue(Diagnostics.verdict(r(inbox = 0, scanned = 0)).first().contains("no messages"))
    @Test fun missingReceiveAndNotificationsAreMentioned() {
        val v = Diagnostics.verdict(r(good = 1, recv = false, notif = false)).joinToString(" ")
        assertTrue(v.contains("Receive SMS")); assertTrue(v.contains("Notifications are off"))
    }
    @Test fun anonymiseKeepsShapeButHidesNumbersAndHandles() =
        assertEquals("Rs.999.99 debited from A/c XX9999 to name@bank", Diagnostics.anonymise("Rs.500.00 debited from A/c XX1234 to ravi@okhdfc"))
}
