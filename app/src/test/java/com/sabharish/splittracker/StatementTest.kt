package com.sabharish.splittracker

import com.sabharish.splittracker.TestUtil.ms
import com.sabharish.splittracker.TestUtil.tx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatementTest {
    @Test fun creditCardTextLines() {
        val text = """
            Statement for card ending 6674
            12/10/2025 SWIGGY BANGALORE 1,234.50
            14/10/2025 AMAZON REFUND 367.09 Cr
            15 Oct 2025 UBER INDIA 215.00
            Total due 1,082.41
        """.trimIndent()
        val l = Statement.parse(text)
        assertEquals(3, l.size)
        assertEquals(1234.5, l[0].amount, 0.001); assertFalse(l[0].credit); assertEquals("SWIGGY BANGALORE", l[0].desc)
        assertTrue(l[1].credit); assertEquals(367.09, l[1].amount, 0.001)
        assertEquals(215.0, l[2].amount, 0.001)
    }

    @Test fun csvWithDebitCreditColumns() {
        val csv = "Date,Description,Debit,Credit,Balance\n01-11-2026,UPI/Rajesh,500.00,,9500.00\n02-11-2026,Salary,,50000.00,59500.00"
        val l = Statement.parse(csv)
        assertEquals(2, l.size)
        assertEquals(500.0, l[0].amount, 0.001); assertFalse(l[0].credit)
    }

    @Test fun linesWithoutDateOrAmountAreSkipped() {
        assertTrue(Statement.parse("Opening balance\nNo transactions\n12/10/2025 header only").isEmpty())
    }

    @Test fun reconcileFindsMatchesMissingAndExtra() {
        val lines = listOf(
            StmtLine(ms(2026, 10, 12), "SWIGGY", 1234.5, false),
            StmtLine(ms(2026, 10, 15), "UBER", 215.0, false),
            StmtLine(ms(2026, 10, 20), "MYSTERY", 999.0, false)
        )
        val txns = listOf(
            tx(1, "Swiggy", 1234.5, ms(2026, 10, 12)),
            tx(2, "Uber", 215.0, ms(2026, 10, 14)),
            tx(3, "Forgotten at Statement", 80.0, ms(2026, 10, 18))
        )
        val r = Statement.reconcile(lines, txns)
        assertEquals(2, r.matched)
        assertEquals(listOf("MYSTERY"), r.missing.map { it.desc })
        assertEquals(listOf(3L), r.extra.map { it.id })
    }

    @Test fun eachAppEntryMatchesOnlyOnce() {
        val lines = listOf(StmtLine(ms(2026, 10, 12), "A", 100.0, false), StmtLine(ms(2026, 10, 12), "B", 100.0, false))
        val r = Statement.reconcile(lines, listOf(tx(1, "A", 100.0, ms(2026, 10, 12))))
        assertEquals(1, r.matched); assertEquals(1, r.missing.size)
    }

    @Test fun dateTooFarApartIsNotAMatch() {
        val r = Statement.reconcile(listOf(StmtLine(ms(2026, 10, 12), "A", 100.0, false)), listOf(tx(1, "A", 100.0, ms(2026, 10, 20))))
        assertEquals(0, r.matched)
    }

    @Test fun creditAndDebitAreNotMixedUp() {
        val r = Statement.reconcile(listOf(StmtLine(ms(2026, 10, 12), "REFUND", 100.0, true)), listOf(tx(1, "Shop", 100.0, ms(2026, 10, 12))))
        assertEquals(0, r.matched)
    }

    @Test fun emptyStatement() {
        val r = Statement.reconcile(emptyList(), listOf(tx(1, "A", 1.0, 1)))
        assertEquals(0, r.lines); assertTrue(r.extra.isEmpty())
    }
}

class StatementSourcesTest {
    @Test fun cardsAndAccountsAreListed() {
        val txns = listOf(
            tx(1, "A", 1.0, 1, bank = "SBI", card = "6674"),
            tx(2, "B", 1.0, 2, kind = "UPI_OUT", bank = "KOTAK", card = "2670"),
            tx(3, "C", 1.0, 3, bank = "KOTAK", card = "2159")           // debit card: belongs to the account, not a card source
        )
        val s = Statement.sources(txns)
        assertEquals(listOf("card|SBI|6674", "acct|KOTAK|2670"), s.map { it.key })
    }

    @Test fun cardSourceOnlyUsesThatCard() {
        val txns = listOf(tx(1, "A", 1.0, 1, bank = "SBI", card = "6674"), tx(2, "B", 1.0, 2, bank = "SBI", card = "6836"))
        val src = Statement.sources(txns).first { it.last == "6674" }
        assertEquals(listOf(1L), Statement.txnsFor(src, txns).map { it.id })
    }
}

/** Line layouts copied from real statements (amounts and names changed). */
class StatementLayoutsTest {
    private fun one(text: String) = Statement.parse(text).also { assertEquals(1, it.size) }[0]

    @Test fun sbiCardUsesTrailingCOrD() {
        val l = Statement.parse("""
              16 Sep 26        CARD CASHBACK CREDIT                                                         158.00        C
              16 Sep 26        TECHMASH SOLUTIONS PRI Bangalore IN                                          198.64         D
              02 Oct 26        PAYMENT RECEIVED 000BD016275BAMAAAOX936L                                   3,653.16        C
        """.trimIndent())
        assertEquals(listOf(true, false, true), l.map { it.credit })
        assertEquals(198.64, l[1].amount, 0.001)
        assertEquals("TECHMASH SOLUTIONS PRI Bangalore IN", l[1].desc)
    }

    @Test fun hdfcCardWithTimePipeAndPlusForMoneyIn() {
        val l = Statement.parse("""
              30/08/2026| 15:04                       Swiggy FoodBENGALURU                              C 689.00      l
              01/09/2026| 00:00                       10% Swiggy CashBack                              + C 68.90      l
              02/09/2026| 07:46                       BPPY CC PAYMENT BD016245 (Ref# ST262460083000010420126)  + C 32,691.00      l
        """.trimIndent())
        assertEquals(3, l.size)
        assertEquals(listOf(false, true, true), l.map { it.credit })
        assertEquals(689.0, l[0].amount, 0.001); assertEquals("Swiggy FoodBENGALURU", l[0].desc)
        assertEquals(32691.0, l[2].amount, 0.001)
        assertEquals("2026-08-30", TestUtil.ymd(l[0].date))
    }

    @Test fun indusindCardIgnoresRewardPointsColumn() {
        val l = Statement.parse("""
              02/10/2026      INDIE PAYMENT                                                          0                            481.20 CR
              24/09/2026      SWIGGY E COM BANGALORE IN                 DEPARTMENTAL                    10                            481.00 DR
        """.trimIndent())
        assertEquals(2, l.size)
        assertTrue(l[0].credit); assertFalse(l[1].credit)
        assertEquals(481.0, l[1].amount, 0.001)
        assertEquals("SWIGGY E COM BANGALORE IN DEPARTMENTAL", l[1].desc)
    }

    @Test fun sbiAccountCreditDebitBalanceColumns() {
        val l = Statement.parse("""
              Date                       Transaction Reference                       Ref.No./Chq.No.                    Credit               Debit              Balance
              Balance on 01-09-26:              441.32                                          null                    null               null                null
              02-09-26      IMPS/624507776782/ICI-XX395-SAPPA S/IMPS Tran                                      -            73383.00                      0          73824.32
              02-09-26      UPI/DR/624531846306/SAPPA SA/KKBK/sabharish./UPI                                   -                       0          15000.00           58824.32
        """.trimIndent())
        assertEquals(2, l.size)
        assertTrue(l[0].credit); assertEquals(73383.0, l[0].amount, 0.001)
        assertFalse(l[1].credit); assertEquals(15000.0, l[1].amount, 0.001)
    }

    @Test fun kotakAccountUsesBalanceMovementAndSerialNumbers() {
        val l = Statement.parse("""
              #       Date           Description                                   Chq/Ref. No.    Withdrawal (Dr.)     Deposit (Cr.)       Balance
              -       -                 Opening Balance                            -                                    -                   -             1,634.05
              1           01 Sep 2026   PCD/2159/Ratnadeep Super Market/P          624404379417                    87.05                                  1,547.00
              2           02 Sep 2026   UPI/Sappa Sabhari/SBIN/624531846306/UPI    UPI-624546912384                                15,000.00          16,547.00
              3           04 Sep 2026   UPI/Sappa Sabharis/SBIN/624796683751/UPI   UPI-624703461363             15,000.00                                 1,547.00
        """.trimIndent())
        assertEquals(3, l.size)
        assertEquals(listOf(false, true, false), l.map { it.credit })
        assertEquals(listOf(87.05, 15000.0, 15000.0), l.map { it.amount })
    }

    @Test fun totalsAndHeadersAreNotTransactions() {
        assertTrue(Statement.parse("Statement Date 15/10/2026 Total Outstanding 1,234.00\nTotal 10 481.00").isEmpty())
    }

    @Test fun twoDigitYearsAreRead() {
        assertEquals("2026-09-16", TestUtil.ymd(one("16 Sep 26   SHOP   10.00 D").date))
        assertEquals("2025-10-12", TestUtil.ymd(one("12/10/25 SHOP 10.00 D").date))
    }
}

class StatementLayoutsTest2 {
    @Test fun yesBankCardHasMerchantCategoryColumn() {
        val l = Statement.parse("""
            02/10/2026                                                                                            Retail Outlet Services                  40.00 Dr
            03/10/2026 UPI_GOVARDHAN CAFE IND - Ref No: RT262770381002310000917                                   Miscellaneous Stores                   770.00 Dr
        """.trimIndent())
        assertEquals(2, l.size)
        assertEquals(listOf(40.0, 770.0), l.map { it.amount })
        assertTrue(l.none { it.credit })
    }

    @Test fun iciciAccountStartsFromBroughtForwardBalance() {
        val l = Statement.parse("""
            DATE           MODE                      PARTICULARS                                   DEPOSITS               WITHDRAWALS                             BALANCE
            01-10-2026                                B/F                                                                                                                       43,507.02
            02-10-2026 MOBILE BANKING                                                                                                  43,507.00                           0.02
            17-10-2026                                NEFT-HDFCH01340164956-UPSTOX SECURITIES PVT LTD                  294.52                                                      294.54
            29-10-2026                                                                                                 7,000.00                                                     7,294.54
            29-10-2026                                CCW/62201HHR/15870263/9336/Cardless Cash WDL/IMB                                                   7,000.00                       294.54
            30-10-2026                                                                                                 1,30,141.00                                                 1,31,100.54
        """.trimIndent())
        assertEquals(5, l.size)
        assertEquals(listOf(false, true, true, false, true), l.map { it.credit })
        assertEquals(130141.0, l[4].amount, 0.001)
    }

    @Test fun federalAccountWithValueDateAndWholeRupees() {
        val l = Statement.parse("""
            Date                          Particulars                    Tran id                     Withdrawal         Deposits          Balance
                                         Opening Balance                 OPNBAL                                                              24         CR
            27/10/2026   27/10/2026    UPIOUT/666692342341/yo-      TFR    S40260308                         2                0                 22         CR
            27/10/2026   27/10/2026   UPI IN/666692398444/youtub-   TFR    S40295151                         0                2                 24         CR
        """.trimIndent())
        assertEquals(2, l.size)
        assertEquals(listOf(false, true), l.map { it.credit })
        assertEquals(2.0, l[0].amount, 0.001)
        assertEquals("UPIOUT/666692342341/yo-", l[0].desc)
    }
}

class StatementLayoutsDbs {
    @Test fun dbsAccountWithValueDateAndOpeningBalance() {
        val l = Statement.parse("""
            Transaction Date    Value Date            Details of transaction                 Debit         Credit        Balance
                                             Opening Balance                                                                   605.78
             08-Feb-2026       08-Feb-2026   UPI~118361944702~sabharish.sappa@okhdfc                          1,000.00        1,605.78
                                             bank~SAPPA
             08-Feb-2026       08-Feb-2026   Purchase-603916722984-080226                       1,538.32                           67.46
        """.trimIndent())
        assertEquals(2, l.size)
        assertEquals(listOf(true, false), l.map { it.credit })
        assertEquals(listOf(1000.0, 1538.32), l.map { it.amount })
        assertEquals("2026-02-08", TestUtil.ymd(l[0].date))
    }
}

class StatementLayoutsIcici {
    @Test fun iciciCardAmountsOnSameOrFollowingLineAndReversalIsCredit() {
        val l = Statement.parse("""
            3747XXXXXXXX2000
            25/09/2026              14235537572                PAYU*SWIGGY INSTAMART PRI Bangalore                               -19                                806.67
                                                                                                                                                                  FCE.GG CR
                                                         IN
            26/09/2026              14235537575                PAYU*SWIGGY INSTAMART PRI Bangalore                               19                                           806.67
                                                         IN
            26/09/2026              14235539390                PAYU*SWIGGY INSTAMART PRI Bangalore                               21                                      X,GDC.GG
                                                                                                                                                                     896.67
                                                         IN
            5241XXXXXXXX6007
            09/10/2026              14333041091                Flipkart Internet Priv Bangalore IN                              1539                                  DC,FBC.GG
                                                                                                                                                                    64130.00
        """.trimIndent())
        assertEquals(4, l.size)
        assertEquals(listOf(806.67, 806.67, 896.67, 64130.0), l.map { it.amount })
        assertEquals(listOf(true, false, false, false), l.map { it.credit })
        assertEquals("PAYU*SWIGGY INSTAMART PRI Bangalore", l[0].desc)
    }
}

class StatementSkipWordsTest {
    @Test fun merchantNamesContainingHeaderWordsAreKept() {
        val l = Statement.parse("""
            -       -                 Opening Balance                            -                                    -                   -             1,011.50
            70          15 Sep 2026   PCD/2159/RATNADEEP RETAIL LIMIT/ED           625805473342                      82.00                                  929.50
        """.trimIndent())
        assertEquals(1, l.size)
        assertEquals(82.0, l[0].amount, 0.001)
        assertFalse(l[0].credit)
    }
}
