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
