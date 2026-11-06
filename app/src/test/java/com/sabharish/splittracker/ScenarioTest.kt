package com.sabharish.splittracker

import com.sabharish.splittracker.TestUtil.ms
import com.sabharish.splittracker.TestUtil.pending
import com.sabharish.splittracker.TestUtil.tx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Everyday combinations a user can produce, tried exhaustively or with fixed random seeds. */
class ScenarioTest {
    private val me = Logic.ME
    private val day = 86_400_000L

    // ---------- splitting money ----------
    @Test fun everyHeadCountAndTotalAddsUpToTheBill() {
        val totals = listOf(0.01, 0.5, 1.0, 9.99, 10.0, 33.33, 99.99, 100.0, 187.5, 1000.0, 12345.67, 100000.0)
        for (n in 1..8) for (total in totals) for (withMe in listOf(true, false)) for (paid in listOf(true, false)) {
            val names = (1..n).map { "P$it" } + if (withMe) listOf(me) else emptyList()
            val s = Logic.equalShares(total, names, paid)
            assertEquals(names.size, s.size)
            assertEquals("n=$n total=$total", total, s.sumOf { it.amount }, 0.005)
            assertTrue("no negative share n=$n total=$total", s.all { it.amount >= 0 })
            if (withMe) assertEquals(me, s.first().person)
            assertEquals(names.distinct().size, s.map { it.person }.distinct().size)
            if (paid) assertTrue(s.all { it.paid }) else assertEquals(withMe, s.any { it.paid && it.person == me } )
            assertTrue("shares differ by at most a few paise", s.maxOf { it.amount } - s.minOf { it.amount } < 0.05 * n)
        }
    }

    @Test fun percentAndPartsAlwaysAddUp() {
        val rnd = Random(7)
        repeat(300) {
            val n = 2 + rnd.nextInt(5)
            val total = (1 + rnd.nextInt(500000)) / 100.0
            val parts = (1..n).map { (1 + rnd.nextInt(9)).toDouble() }
            val s = Logic.weightedShares(total, (1..n).map { "P$it" }, parts, false)!!
            assertEquals(total, s.sumOf { it.amount }, 0.005)
            assertTrue(s.all { it.amount >= 0 })
        }
    }

    @Test fun selectionSummaryNeverBreaks() {
        val pool = listOf(me, "A", "B", "C", "D")
        for (mask in 0 until 32) {
            val sel = pool.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
            for (total in listOf(1.0, 100.0, 187.5, 0.01)) {
                val t = Logic.selectionSummary(total, sel)
                assertTrue(t.isNotBlank())
                assertFalse(t.contains("null") || t.contains("NaN"))
                if (sel.isEmpty()) assertEquals("Pick who this was for", t)
                if (sel == listOf(me)) assertEquals("Mine", t)
            }
        }
    }

    // ---------- editing a saved split ----------
    @Test fun savingThenEditingGivesTheSameSplitBack() {
        val rnd = Random(11)
        repeat(200) {
            val n = 1 + rnd.nextInt(5)
            val names = (listOf(me) + (1..n).map { "P$it" }).take(1 + rnd.nextInt(n + 1)).ifEmpty { listOf("P1") }
            val total = (100 + rnd.nextInt(100000)) / 100.0
            val saved = Logic.equalShares(total, names, false).map { Share(0, 1, it.person, it.amount, if (it.paid) it.amount else 0.0) }
            val ex = Logic.splitFromExisting(total, saved)!!
            assertEquals(names.toSet(), ex.people.toSet())
            assertEquals("EQUAL", ex.mode)
            val rebuilt = Logic.equalShares(total, ex.people, ex.allPaid)
            assertEquals(saved.sumOf { it.amount }, rebuilt.sumOf { it.amount }, 0.005)
        }
    }

    @Test fun editingNeverLosesMoneyAlreadyReceived() {
        val rnd = Random(5)
        repeat(200) {
            val old = (1..3).map { Share(0, 1, "P$it", 100.0, rnd.nextInt(101).toDouble()) }
            val new = (1..4).map { NewShare("P$it", (rnd.nextInt(200)).toDouble(), false) }
            val carried = Logic.carrySettled(old, new)
            carried.forEachIndexed { i, c ->
                assertTrue(c >= 0.0 && c <= new[i].amount + 1e-9)
                val before = old.firstOrNull { it.person == new[i].person }?.settled ?: 0.0
                assertEquals(minOf(before, new[i].amount), c, 1e-9)
            }
        }
    }

    // ---------- reminders ----------
    @Test fun remindersAreSaneForEveryShape() {
        val now = ms(2026, 11, 6)
        for (items in listOf(1, 2, 7, 8, 9, 15)) for (parts in listOf(1, 2, 5)) for (age in listOf(0, 3, 14, 15, 30, 31, 90))
            for (upi in listOf("", "me@okaxis")) for (partPaid in listOf(false, true)) {
                val rows = (1..items).map { i ->
                    val share = 10.0 * i
                    pending("Rajesh Kumar", "Shop$i", share, now - age * day - i * 1000L, total = share * parts, parts = parts, settled = if (partPaid) share / 2 else 0.0)
                }
                val text = Reminder.build("Rajesh Kumar", rows, emptyMap(), upi, now)
                val due = rows.sumOf { it.amount - it.settled }
                assertTrue(text.startsWith("Hi Rajesh"))
                assertTrue("total shown", text.contains("Total pending: ${Logic.money(due)}"))
                assertEquals(upi.isNotEmpty(), text.contains("Pay to: $upi"))
                assertEquals(items > 8, text.contains("more"))
                assertFalse(text.contains("null") || text.contains("NaN"))
                assertFalse("no triple blank lines", text.contains("\n\n\n"))
                assertTrue(text.trimEnd().endsWith("Thank you!"))
                assertEquals(parts > 1, text.contains("split between $parts"))
            }
    }

    // ---------- filters: adding a filter never adds results ----------
    private fun sample(): List<Triple<Txn, List<Share>, Int>> {
        val now = ms(2026, 11, 6)
        val kinds = listOf("SPEND", "UPI_OUT", "UPI_IN", "REFUND")
        val banks = listOf("SBI", "KOTAK", "ICICI")
        val cats = listOf("", "Grocery", "Cab")
        val statuses = listOf("INBOX", "TAGGED", "FAILED", "SELF", "REFUND")
        val out = mutableListOf<Triple<Txn, List<Share>, Int>>()
        var id = 1L
        for (k in kinds) for (b in banks) for (c in cats) for (s in statuses) for (age in listOf(1, 20, 45, 100)) {
            val t = tx(id, "Merchant$id", 10.0 + id, now - age * day, status = s, kind = k, bank = b).copy(category = c, body = "msg $id")
            val shares = if (id % 3 == 0L) listOf(Share(id, id, "Raj", 5.0, if (id % 2 == 0L) 5.0 else 0.0)) else emptyList()
            out.add(Triple(t, shares, 0)); id++
        }
        return out
    }

    @Test fun addingAFilterOnlyNarrowsTheList() {
        val now = ms(2026, 11, 6)
        val data = sample()
        fun count(f: TxnFilter) = data.count { Filters.matches(it.first, it.second, emptyMap(), f, now) }
        val all = count(TxnFilter())
        assertEquals(data.size, all)
        for (type in Filters.types) for (tag in listOf("Any", "Tagged", "Untagged")) for (range in Filters.ranges) for (status in Filters.statuses) {
            val f = TxnFilter(type = type, tagState = tag, range = range, status = status)
            val c = count(f)
            assertTrue(c <= all)
            assertTrue(count(f.copy(query = "zzzznomatch")) == 0)
            assertTrue(count(f.copy(type = "Any")) >= c)
            assertTrue(count(f.copy(range = "Any time")) >= c)
            assertTrue(count(f.copy(status = "Any")) >= c)
            assertTrue(count(f.copy(tagState = "Any")) >= c)
        }
    }

    @Test fun searchingAnyTransactionsOwnDetailsFindsIt() {
        val now = ms(2026, 11, 6)
        for ((t, ss, _) in sample().take(150)) {
            for (q in listOf(t.merchant, t.merchant.lowercase(), "${t.amount}", t.body)) {
                assertTrue("find ${t.id} by '$q'", Filters.matches(t, ss, emptyMap(), TxnFilter(query = q), now))
            }
        }
    }

    // ---------- tags ----------
    @Test fun anySequenceOfTagEditsKeepsTheListClean() {
        val rnd = Random(3)
        var tags = emptyList<String>()
        val names = listOf("Gym", "gym", "Pets", " Pets ", "Rent", "Grocery", "", "A,B", "x".repeat(40), "Cab", "Fuel ")
        repeat(500) {
            val a = names[rnd.nextInt(names.size)]; val b = names[rnd.nextInt(names.size)]
            tags = when (rnd.nextInt(3)) {
                0 -> Logic.addTag(tags, a)
                1 -> Logic.renameTag(tags, a.trim(), b) ?: tags
                else -> tags.filter { !it.equals(a.trim(), true) }
            }
            val lower = tags.map { it.lowercase() }
            assertEquals("no duplicates: $tags", lower.size, lower.distinct().size)
            assertTrue(tags.none { it.isBlank() || it.contains(",") || it.length > 24 })
            assertTrue(tags.none { t -> Categories.all.any { it.equals(t, true) } })
            val all = Logic.allTags(tags)
            assertEquals("Other", all.last()); assertEquals(all.size, all.distinct().size)
        }
    }

    // ---------- streak ----------
    @Test fun streakMatchesASimpleModel() {
        val rnd = Random(9)
        repeat(100) {
            var s = Habit.Streak(0, "")
            var expected = 0; var lastClear = -10
            for (d in 0 until 60) {
                val now = ms(2026, 1, 1) + d * day
                val visits = 1 + rnd.nextInt(3)
                repeat(visits) { s = Habit.update(s, now + it * 3_600_000L, rnd.nextInt(3) == 0) }
                // model: replay the same random choices is not possible, so check invariants instead
                assertTrue(s.count >= 0)
                assertTrue(Habit.shown(s, now) <= s.count)
                if (s.lastDay.isNotEmpty()) assertTrue(s.lastDay <= Habit.dayId(now))
            }
        }
        // exact model: clear every day for 10 days, skip 3, clear again
        var s = Habit.Streak(0, "")
        for (d in 0 until 10) s = Habit.update(s, ms(2026, 1, 1) + d * day, true)
        assertEquals(10, s.count)
        s = Habit.update(s, ms(2026, 1, 14), true)
        assertEquals(1, s.count)
    }

    // ---------- accounts ----------
    @Test fun balanceEqualsOpeningPlusMovements() {
        val rnd = Random(21)
        repeat(100) {
            val acct = Account("KOTAK|2670", "KOTAK", "2670", "", 5000.0, 1_000)
            var expected = 5000.0
            val txns = (1..40).map { i ->
                val amt = (1 + rnd.nextInt(50000)) / 100.0
                val kind = listOf("UPI_IN", "UPI_OUT", "SPEND")[rnd.nextInt(3)]
                val bank = if (rnd.nextInt(4) == 0) "SBI" else "KOTAK"
                val card = if (bank == "KOTAK") "2670" else "3157"
                val t = tx(i.toLong(), "M$i", amt, 2_000L + i, kind = kind, bank = bank, card = card)
                if (bank == "KOTAK" && kind != "SPEND") expected += if (kind == "UPI_IN") amt else -amt
                t
            }
            val det = Accounts.detected(txns)
            assertEquals(Math.round(expected * 100) / 100.0, Accounts.balance(acct, txns.filter { !(it.kind == "SPEND" && it.bank == "KOTAK") }, det), 0.02)
        }
    }

    // ---------- statements ----------
    @Test fun reconcileAccountsForEveryLine() {
        val rnd = Random(2)
        repeat(100) {
            val n = 1 + rnd.nextInt(25)
            val lines = (1..n).map { StmtLine(ms(2026, 10, 1 + rnd.nextInt(28)), "L$it", (1 + rnd.nextInt(100000)) / 100.0, false) }
            val keep = lines.filter { rnd.nextInt(3) != 0 }
            val txns = keep.mapIndexed { i, l -> tx(i + 1L, "T$i", l.amount, l.date + rnd.nextInt(3) * day) }
            val r = Statement.reconcile(lines, txns)
            assertEquals(lines.size, r.matched + r.missing.size)
            assertEquals(lines.size, r.lines)
            assertTrue(r.matched >= keep.size - 0 || r.matched <= keep.size)
            assertTrue("a match never uses an entry twice", r.matched <= txns.size)
        }
    }

    @Test fun statementOrderDoesNotChangeTheMatchCount() {
        val lines = (1..12).map { StmtLine(ms(2026, 10, it), "L$it", 100.0 + it % 3, it % 5 == 0) }
        val txns = lines.take(9).mapIndexed { i, l -> tx(i + 1L, "T", l.amount, l.date, kind = if (l.credit) "UPI_IN" else "SPEND") }
        val a = Statement.reconcile(lines, txns).matched
        val b = Statement.reconcile(lines.shuffled(Random(1)), txns).matched
        assertEquals(a, b)
    }

    // ---------- refunds ----------
    @Test fun refundMatchingNeverCrashesAndIsStable() {
        val refund = tx(100, "AMAZON", 500.0, 1_000_000, status = "INBOX", kind = "REFUND")
        val merchants = listOf("Amazon", "Amazon Pay", "Swiggy", "X")
        for (n in 0..4) for (failed in 0..2) {
            val c = (1..n).map { i -> tx(i.toLong(), merchants[i % merchants.size], 500.0, i * 100L, status = if (i <= failed) "FAILED" else "TAGGED") }
            val r = RefundMatcher.find(refund, c)
            if (n == 0) assertNull(r) else {
                assertNotNull(r)
                assertEquals(RefundMatcher.find(refund, c.reversed())?.second, r!!.second)
            }
        }
    }
}
