package com.sabharish.splittracker

import com.sabharish.splittracker.TestUtil.ymd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ParserTest {
    private fun p(body: String): Parsed {
        val r = SmsParser.parse(body)
        assertNotNull("should parse: $body", r)
        return r!!
    }

    // ---------- card spends ----------
    @Test fun sbiCardSpend() {
        val r = p("Rs.1,234.50 spent on your SBI Credit Card ending 6674 at SWIGGY on 12/10/25. Trxn. not done by you? Report at https://sbicard.com/Dispute")
        assertEquals("SBI", r.bank); assertEquals("SPEND", r.kind)
        assertEquals(1234.5, r.amount, 0.001); assertEquals("6674", r.card); assertEquals("SWIGGY", r.merchant)
        assertEquals("2025-10-12", ymd(r.dateMillis))
    }

    @Test fun sbiCardSpendViaUpi() {
        val r = p("Rs.8.00 spent on your SBI Credit Card ending with 6836 at HOTANDCOOLBAKERY on 23-07-26 via UPI (Ref No. 605353937494). Trxn. not done by you? Report at https://sbicard.com/Dispute")
        assertEquals("SPEND", r.kind); assertEquals("6836", r.card); assertEquals(8.0, r.amount, 0.001)
        assertEquals("2026-07-23", ymd(r.dateMillis))
    }

    @Test fun hdfcSpend() {
        val r = p("Spent Rs.1411 On HDFC Bank Card 5033 At RSP*INSTAMART On 2026-02-11:19:32:28.Not You? To Block+Reissue Call 18002586161/SMS BLOCK CC 5033 to 7308080808")
        assertEquals("HDFC", r.bank); assertEquals(1411.0, r.amount, 0.001); assertEquals("RSP*INSTAMART", r.merchant)
        assertEquals("2026-02-11", ymd(r.dateMillis))
    }

    @Test fun iciciSpend() {
        val r = p("INR 320.00 spent using ICICI Bank Card XX6007 on 01-Jun-25 on J K ENTERPRISES. Avl Limit: INR 26,068.00. If not you, call 1800 2662/SMS BLOCK 6007 to 9215676766.")
        assertEquals("ICICI", r.bank); assertEquals("6007", r.card); assertEquals("J K ENTERPRISES", r.merchant)
        assertEquals("2025-06-01", ymd(r.dateMillis))
    }

    @Test fun indusindSpend() {
        val r = p("INR 1,232.00 spent on IndusInd Card XX4122 on 13-09-2025 07:17:05 pm at AVENUE SUPERMARTS LTD. Avl Lmt: INR 98,768.00. To dispute, call 18602677777/SMS BLOCK 4122 to 5676757")
        assertEquals("INDUSIND", r.bank); assertEquals(1232.0, r.amount, 0.001); assertEquals("AVENUE SUPERMARTS LTD", r.merchant)
        assertEquals("2025-09-13", ymd(r.dateMillis))
    }

    @Test fun yesSpend() {
        val r = p("INR 239.00 spent on YES BANK Card X3121 @UPI_SWIGGY 03-04-2026 08:54:16 am. Avl Lmt INR 299,761.00. SMS BLKCC 3121 to 9840909000 if not you")
        assertEquals("YES", r.bank); assertEquals("3121", r.card); assertEquals("UPI_SWIGGY", r.merchant)
    }

    @Test fun kotakDebitCardSpendCollapsesSpaces() {
        val r = p("Rs.711.00 spent via Kotak Debit Card XX2159 at FLAVOURS CAFE   BENGALURU on 28/09/2025. Avl bal Rs.96.72 Not you?Tap https://kotak.com/KBANKT/Fraud")
        assertEquals("KOTAK", r.bank); assertEquals("FLAVOURS CAFE BENGALURU", r.merchant); assertEquals("2159", r.card)
    }

    // ---------- refunds ----------
    @Test fun iciciRefund() {
        val r = p("AMAZON refund of Rs 367.09 credited to ICICI Bank Credit Card XX6007 on 24-NOV-25. Revised total due Rs 10,519.41, minimum due Rs 182.91")
        assertEquals("REFUND", r.kind); assertEquals("AMAZON", r.merchant); assertEquals(367.09, r.amount, 0.001)
        assertEquals("2025-11-24", ymd(r.dateMillis))
    }

    @Test fun hdfcRefund() {
        val r = p("Alert! Rs. 495.94 refunded by MYNTRA DESIGNS PRIVATE Bangalore IND on 01/OCT/2026 & adjusted against HDFC Bank Credit Card 5033 View updated balance here: https://1.hdfc.bank.in/HDFCBK/s/8dKXamPN")
        assertEquals("REFUND", r.kind); assertEquals("5033", r.card); assertEquals("2026-10-01", ymd(r.dateMillis))
    }

    @Test fun sbiUpiReversalIsARefund() {
        val r = p("Dear SBI UPI User, ur A/cX3157 credited with Rs1869 on 01Sep24 against reversal of txn (Ref no 424587112788)")
        assertEquals("REFUND", r.kind); assertEquals(1869.0, r.amount, 0.001)
        assertEquals("UPI reversal", r.merchant); assertEquals("2024-09-01", ymd(r.dateMillis))
    }

    // ---------- UPI ----------
    @Test fun sbiUpiSentCleansUppercaseName() {
        val r = p("Dear UPI user A/C X3157 debited by 564.0 on date 01Feb24 trf to MUNGI SRIKANTH R Refno 403296759330. If not u? call 1800111109. -SBI")
        assertEquals("UPI_OUT", r.kind); assertEquals(564.0, r.amount, 0.001); assertEquals("3157", r.card)
        assertEquals("Mungi Srikanth R", r.merchant); assertEquals("2024-02-01", ymd(r.dateMillis))
    }

    @Test fun sbiUpiReceived() {
        val r = p("Dear SBI User, your A/c X3157-credited by Rs.41990 on 01Oct24 transfer from KURUVA KOUSHIK Ref No 427569585775 -SBI")
        assertEquals("UPI_IN", r.kind); assertEquals(41990.0, r.amount, 0.001); assertEquals("Kuruva Koushik", r.merchant)
    }

    @Test fun iciciUpiSentAndReceived() {
        val out = p("ICICI Bank Acct XX395 debited for Rs 542.00 on 06-Nov-23; Kota Sai Nithin credited. UPI:331010331279. Call 18002662 for dispute. SMS BLOCK 395 to 9215676766.")
        assertEquals("UPI_OUT", out.kind); assertEquals("395", out.card); assertEquals("Kota Sai Nithin", out.merchant)
        val inn = p("Dear Customer, Acct XX395 is credited with Rs 1773.95 on 07-Nov-23 from IRCTC Web UPI. UPI:367749736606-ICICI Bank.")
        assertEquals("UPI_IN", inn.kind); assertEquals("IRCTC Web UPI", inn.merchant)
    }

    @Test fun kotakUpiHandlesBecomeReadable() {
        val out = p("Sent Rs.1950.00 from Kotak Bank AC X2670 to paytmqr2810050501011wpgfnjpibtz@paytm on 10-08-25.UPI Ref 522218744103. Not you, https://kotak.com/KBANKT/Fraud")
        assertEquals("UPI_OUT", out.kind); assertEquals("Paytm merchant", out.merchant)
        val inn = p("Received Rs.3000.00 in your Kotak Bank AC 2670 from SAPPA SABHARISH on 08-09-26.UPI Ref:129274350575")
        assertEquals("UPI_IN", inn.kind); assertEquals("Sappa Sabharish", inn.merchant); assertEquals("2670", inn.card)
    }

    @Test fun federalUpiThreeFormats() {
        val a = p("Rs 216.00 sent via UPI on 20-08-2025 at 18:42:22 to THE TOOTH CORNE.Ref:281082224714.Not you? Call 18004251199/SMS BLOCKUPI to 98950 88888 -Federal Bank")
        assertEquals("FEDERAL", a.bank); assertEquals("The Tooth Corne", a.merchant); assertEquals(216.0, a.amount, 0.001)
        val b = p("Debited Rs 35.00 from a/c X3079 on 01Oct26 22:12 via UPI to BMTC. Ref 963156225872.Bal Rs 14445.09. Not you?Call 18004251199 -Federal Bank")
        assertEquals("3079", b.card); assertEquals("BMTC", b.merchant); assertEquals("2026-10-01", ymd(b.dateMillis))
        val c = p("Rs 1800.00 debited via UPI on 23-02-2025 13:35:27 to VPA sabharish.sappa@ybl.Ref No 704114286528.Small txns?Use UPI Lite!-Federal Bank")
        assertEquals("sabharish.sappa", c.merchant)
    }

    // ---------- card bill payments ----------
    @Test fun billPayments() {
        val a = p("Payment of Rs 320.76 has been received on your ICICI Bank Credit Card XX6007 through Bharat Bill Payment System on 18-JUL-25.")
        assertEquals("PAYMENT", a.kind); assertEquals("6007", a.card)
        val b = p("We have received payment of Rs.23,351.00 via BBPS & the same has been credited to your SBI Credit Card. Your available limit is Rs.98,777.00.")
        assertEquals("PAYMENT", b.kind); assertEquals(23351.0, b.amount, 0.001)
    }

    // ---------- things that must NOT become transactions ----------
    @Test fun otpMessagesAreIgnored() {
        assertNull(SmsParser.parse("313501 is the OTP for Trxn. of INR 3994.00 at Amazon De with your credit card ending 6674. OTP is valid for 10 mins. Do not share it with anyone - SBI Card"))
    }

    @Test fun promosAndEmptyAreIgnored() {
        assertNull(SmsParser.parse("Get 5% cashback on all your spends with the new card. Apply now at example.com"))
        assertNull(SmsParser.parse(""))
        assertNull(SmsParser.parse(null))
    }

    @Test fun cleanNameRules() {
        assertEquals("Paytm merchant", SmsParser.cleanName("paytmqr123@paytm"))
        assertEquals("Ramesh Kumar", SmsParser.cleanName("RAMESH KUMAR"))
        assertEquals("KA01AR1140", SmsParser.cleanName("KA01AR1140"))
        assertEquals("BMTC", SmsParser.cleanName("BMTC"))   // single-word acronyms stay as-is
        assertEquals("rahul.k", SmsParser.cleanName("rahul.k@okaxis"))
    }
}
