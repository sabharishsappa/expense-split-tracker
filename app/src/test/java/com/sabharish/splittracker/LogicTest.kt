package com.sabharish.splittracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    private val me = Logic.ME

    // ---------- money ----------
    @Test fun moneyFormatting() {
        assertEquals("₹640", Logic.money(640.0))
        assertEquals("₹37.50", Logic.money(37.5))
        assertEquals("₹1,234.50", Logic.money(1234.5))
        assertEquals("₹1,00,000", Logic.money(100000.0))
        assertEquals("₹12,34,567.89", Logic.money(1234567.89))
        assertEquals("₹0", Logic.money(0.0))
        assertEquals("-₹250", Logic.money(-250.0))
    }

    // ---------- equal split ----------
    @Test fun equalSplitPutsMeFirstAndFixesRounding() {
        val s = Logic.equalShares(100.0, listOf("A", me, "B"), false)
        assertEquals(listOf(me, "A", "B"), s.map { it.person })
        assertEquals(33.33, s[0].amount, 0.001)
        assertEquals(33.33, s[1].amount, 0.001)
        assertEquals(33.34, s[2].amount, 0.001)
        assertEquals(100.0, s.sumOf { it.amount }, 0.001)
    }

    @Test fun equalSplitPaidFlags() {
        val s = Logic.equalShares(90.0, listOf(me, "A", "B"), false)
        assertEquals(listOf(true, false, false), s.map { it.paid })
        val paid = Logic.equalShares(90.0, listOf(me, "A", "B"), true)
        assertTrue(paid.all { it.paid })
    }

    @Test fun fivePersonSplitIsExact() {
        val s = Logic.equalShares(187.5, listOf(me, "A", "B", "C", "D"), false)
        assertTrue(s.all { Math.abs(it.amount - 37.5) < 0.001 })
    }

    @Test fun equalSplitOfNobodyIsEmpty() {
        assertTrue(Logic.equalShares(50.0, emptyList(), false).isEmpty())
    }

    // ---------- percent / parts ----------
    @Test fun percentSplit() {
        val s = Logic.weightedShares(250.0, listOf(me, "A"), listOf(60.0, 40.0), false)!!
        assertEquals(150.0, s[0].amount, 0.001)
        assertEquals(100.0, s[1].amount, 0.001)
    }

    @Test fun partsSplitGivesRemainderToLast() {
        val s = Logic.weightedShares(100.0, listOf(me, "A"), listOf(2.0, 1.0), false)!!
        assertEquals(66.66, s[0].amount, 0.001)
        assertEquals(33.34, s[1].amount, 0.001)
        assertEquals(100.0, s.sumOf { it.amount }, 0.001)
    }

    @Test fun weightedSplitRejectsBadInput() {
        assertNull(Logic.weightedShares(100.0, listOf(me, "A"), listOf(1.0), false))
        assertNull(Logic.weightedShares(100.0, listOf(me, "A"), listOf(0.0, 0.0), false))
        assertNull(Logic.weightedShares(100.0, listOf(me, "A"), listOf(-1.0, 2.0), false))
        assertNull(Logic.weightedShares(100.0, emptyList(), emptyList(), false))
    }

    // ---------- selection summary (text beside the tick button) ----------
    @Test fun selectionSummaries() {
        assertEquals("Pick who this was for", Logic.selectionSummary(100.0, emptyList()))
        assertEquals("Mine", Logic.selectionSummary(100.0, listOf(me)))
        assertEquals("Rajesh owes ₹500", Logic.selectionSummary(500.0, listOf("Rajesh")))
        assertEquals("Split 3 ways · ₹33.33 each", Logic.selectionSummary(100.0, listOf(me, "A", "B")))
        assertEquals("Split 2 ways · ₹50 each", Logic.selectionSummary(100.0, listOf("A", "B")))
    }

    // ---------- UPI sender -> person ----------
    @Test fun bestMatchFindsReorderedNames() {
        val people = listOf("Rahul Sharma", "Koushik Kuruva")
        assertEquals("Koushik Kuruva", Logic.bestMatch("KURUVA KOUSHIK", people))
        assertEquals("Koushik Kuruva", Logic.bestMatch("KOUSHIK K", people))
        assertNull(Logic.bestMatch("Unknown Person", people))
        assertNull(Logic.bestMatch("", people))
    }

    // ---------- merchant names ----------
    @Test fun titleShowsBothNames() {
        assertEquals("CRED · RAZDREAMPLUGPAYTECHSOL", Logic.title("RAZDREAMPLUGPAYTECHSOL", emptyMap()))
        assertEquals("CRED", Logic.nameOnly("RAZDREAMPLUGPAYTECHSOL", emptyMap()))
        val aliases = mapOf(Logic.norm("Swiggy Instamart") to "Instamart")
        assertEquals("Instamart · Swiggy Instamart", Logic.title("Swiggy Instamart", aliases))
        assertEquals("Instamart", Logic.nameOnly("Swiggy Instamart", aliases))
        assertEquals("Namma Irani", Logic.title("Namma Irani", emptyMap()))
    }

    @Test fun userAliasBeatsBuiltinAlias() {
        val aliases = mapOf(Logic.norm("RAZDREAMPLUGPAYTECHSOL") to "Rent")
        assertEquals("Rent · RAZDREAMPLUGPAYTECHSOL", Logic.title("RAZDREAMPLUGPAYTECHSOL", aliases))
    }

    // ---------- custom tags ----------
    @Test fun addTagRules() {
        assertEquals(listOf("Gym"), Logic.addTag(emptyList(), "  Gym  "))
        assertEquals(listOf("Gym"), Logic.addTag(listOf("Gym"), "gym"))          // duplicate, any case
        assertEquals(emptyList<String>(), Logic.addTag(emptyList(), "grocery"))    // built-in
        assertEquals(emptyList<String>(), Logic.addTag(emptyList(), "   "))
        assertEquals(listOf("Pets Vet"), Logic.addTag(emptyList(), "Pets,Vet"))  // commas removed
        assertEquals(24, Logic.addTag(emptyList(), "x".repeat(40))[0].length)
    }

    @Test fun allTagsKeepsOtherLast() {
        val tags = Logic.allTags(listOf("Gym", "Pets"))
        assertEquals("Other", tags.last())
        assertTrue(tags.indexOf("Gym") < tags.indexOf("Other"))
        assertTrue(tags.contains("Grocery"))
        assertEquals(tags.size, tags.distinct().size)
    }
}

class TagStorageTest {
    @Test fun parseAndJoinRoundTrip() {
        val tags = listOf("Gym", "Pet Care")
        assertEquals(tags, Logic.parseTags(Logic.joinTags(tags)))
        assertEquals(emptyList<String>(), Logic.parseTags(""))
        assertEquals(listOf("A", "B"), Logic.parseTags(" A, ,B "))
    }

    @Test fun selectionWithOnePersonIsNotASplit() {
        assertEquals("Meera owes ₹120", Logic.selectionSummary(120.0, listOf("Meera")))
    }

    @Test fun fourWaySplitWithMe() {
        assertEquals("Split 4 ways · ₹250 each", Logic.selectionSummary(1000.0, listOf(Logic.ME, "A", "B", "C")))
    }
}

class SettleSelectionTest {
    private val day = 86_400_000L
    private val rows = listOf(
        TestUtil.pending("A", "X", 100.0, 1 * day).copy(shareId = 1),
        TestUtil.pending("A", "Y", 250.0, 2 * day, settled = 50.0).copy(shareId = 2),
        TestUtil.pending("A", "Z", 40.0, 3 * day).copy(shareId = 3)
    )

    @Test fun nothingTickedMeansAll() { assertEquals(340.0, Logic.dueOf(rows, emptySet()), 0.001) }
    @Test fun onlyTickedBillsCount() { assertEquals(300.0, Logic.dueOf(rows, setOf(1L, 2L)), 0.001) }
    @Test fun partlyPaidBillCountsWhatIsDue() { assertEquals(200.0, Logic.dueOf(rows, setOf(2L)), 0.001) }
}

class SimilarTest {
    @Test fun sameMerchantIgnoringCaseAndSpaces() {
        val a = TestUtil.tx(1, "Kings and Queens", 100.0, 1)
        val others = listOf(TestUtil.tx(2, "KINGS AND QUEENS", 50.0, 2), TestUtil.tx(3, "Kings And  Queens", 60.0, 3),
            TestUtil.tx(4, "Kings", 10.0, 4), a)
        assertEquals(listOf(2L, 3L), Logic.similar(a, others).map { it.id })
    }

    @Test fun blankMerchantMatchesNothing() {
        assertTrue(Logic.similar(TestUtil.tx(1, "", 1.0, 1), listOf(TestUtil.tx(2, "", 1.0, 2))).isEmpty())
    }
}

class TagEditTest {
    @Test fun renameKeepsOrderAndRejectsClashes() {
        assertEquals(listOf("Gym", "Pet Care"), Logic.renameTag(listOf("Gym", "Pets"), "Pets", "Pet Care"))
        assertEquals(listOf("Fitness"), Logic.renameTag(listOf("Gym"), "gym", " Fitness "))
        assertNull(Logic.renameTag(listOf("Gym", "Pets"), "Pets", "gym"))       // already used
        assertNull(Logic.renameTag(listOf("Gym"), "Gym", "Grocery"))            // built-in name
        assertNull(Logic.renameTag(listOf("Gym"), "Gym", "   "))
        assertNull(Logic.renameTag(listOf("Gym"), "Unknown", "X"))
        assertEquals(listOf("GYM"), Logic.renameTag(listOf("Gym"), "Gym", "GYM"))   // only the case changed
    }

    @Test fun hiddenBuiltInsLeaveTheListButOtherStays() {
        val t = Logic.allTags(listOf("Gym"), listOf("Fuel", "Health", "Other"))
        assertTrue("Fuel" !in t && "Health" !in t)
        assertEquals("Other", t.last()); assertTrue("Gym" in t); assertTrue("Grocery" in t)
    }
}

class WhatsAppNumberTest {
    @Test fun indianNumbersGetCountryCode() {
        assertEquals("919876543210", Logic.waNumber("98765 43210"))
        assertEquals("919876543210", Logic.waNumber("098765 43210"))
        assertEquals("919876543210", Logic.waNumber("+91 98765-43210"))
        assertEquals("447911123456", Logic.waNumber("+44 7911 123456"))
    }

    @Test fun badNumbersAreRejected() {
        assertNull(Logic.waNumber(""))
        assertNull(Logic.waNumber("12345"))
        assertNull(Logic.waNumber("abc"))
    }
}

class EditSplitTest {
    private fun sh(p: String, a: Double, s: Double = 0.0) = Share(0, 1, p, a, s)

    @Test fun equalSplitIsRecognised() {
        val ex = Logic.splitFromExisting(100.0, listOf(sh("Rajesh", 50.0), sh(Logic.ME, 50.0, 50.0)))!!
        assertEquals(listOf(Logic.ME, "Rajesh"), ex.people)
        assertEquals("EQUAL", ex.mode)
        assertFalse(ex.allPaid)
    }

    @Test fun unevenSplitComesBackAsAmounts() {
        val ex = Logic.splitFromExisting(100.0, listOf(sh(Logic.ME, 70.0, 70.0), sh("A", 30.0)))!!
        assertEquals("AMOUNT", ex.mode); assertEquals(30.0, ex.amounts["A"]!!, 0.001)
    }

    @Test fun threeWayRoundingStillCountsAsEqual() {
        val ex = Logic.splitFromExisting(100.0, listOf(sh(Logic.ME, 33.33, 33.33), sh("A", 33.33), sh("B", 33.34)))!!
        assertEquals("EQUAL", ex.mode)
    }

    @Test fun everyoneHavingPaidIsRemembered() {
        assertTrue(Logic.splitFromExisting(100.0, listOf(sh(Logic.ME, 50.0, 50.0), sh("A", 50.0, 50.0)))!!.allPaid)
        assertFalse(Logic.splitFromExisting(100.0, listOf(sh(Logic.ME, 100.0, 100.0)))!!.allPaid)   // only me: nobody to have paid
    }

    @Test fun sharesShrunkToZeroByRefundAreDropped() {
        val ex = Logic.splitFromExisting(100.0, listOf(sh(Logic.ME, 0.0), sh("A", 100.0)))!!
        assertEquals(listOf("A"), ex.people)
        assertNull(Logic.splitFromExisting(100.0, listOf(sh("A", 0.0))))
        assertNull(Logic.splitFromExisting(100.0, emptyList()))
    }

    @Test fun partPaymentsSurviveAnEdit() {
        val old = listOf(sh("A", 100.0, 40.0), sh("B", 100.0, 100.0))
        val new = listOf(NewShare("A", 80.0, false), NewShare("B", 50.0, false), NewShare("C", 70.0, false))
        assertEquals(listOf(40.0, 50.0, 0.0), Logic.carrySettled(old, new))      // B capped at the new, smaller share
    }

    @Test fun markedAlreadyPaidCountsAsFullyPaid() {
        assertEquals(listOf(80.0), Logic.carrySettled(emptyList(), listOf(NewShare("A", 80.0, true))))
    }

    @Test fun selectedItemsComeFirst() {
        assertEquals(listOf("C", "A", "B", "D"), Logic.selectedFirst(listOf("A", "B", "C", "D"), listOf("C")))
        assertEquals(listOf("B", "A", "C"), Logic.selectedFirst(listOf("A", "B", "C"), listOf("B", "A")))
        assertEquals(listOf("A", "B"), Logic.selectedFirst(listOf("A", "B"), emptyList()))
        assertEquals(listOf("X", "A"), Logic.selectedFirst(listOf("A"), listOf("X")))
    }
}

class BulkSummaryTest {
    @Test fun nothingTicked() = org.junit.Assert.assertEquals("Tick the bills to change", Logic.bulkSummary(0, 0.0, "", emptyList()))
    @Test fun nothingChosen() = org.junit.Assert.assertEquals("Pick a tag and/or who these were for", Logic.bulkSummary(3, 300.0, "", emptyList()))
    @Test fun tagOnly() = org.junit.Assert.assertEquals("2 bills · ₹300 → tag Food", Logic.bulkSummary(2, 300.0, "Food", emptyList()))
    @Test fun mineOnly() = org.junit.Assert.assertEquals("1 bill · ₹100 → all mine", Logic.bulkSummary(1, 100.0, "", listOf("Me")))
    @Test fun oneOther() = org.junit.Assert.assertEquals("1 bill · ₹100 → all Ravi's", Logic.bulkSummary(1, 100.0, "", listOf("Ravi")))
    @Test fun tagAndSplit() = org.junit.Assert.assertEquals("4 bills · ₹1,000 → tag Trip, split Me + Ravi", Logic.bulkSummary(4, 1000.0, "Trip", listOf("Me", "Ravi")))
}

class TagOrderTest {
    @Test fun mostUsedFirstOtherLast() {
        val r = Logic.orderByUse(listOf("Food", "Travel", "Gym", "Other"), mapOf("Gym" to 9, "food" to 3))
        org.junit.Assert.assertEquals(listOf("Gym", "Food", "Travel", "Other"), r)
    }
    @Test fun tiesKeepOriginalOrder() = org.junit.Assert.assertEquals(listOf("A", "B", "C"), Logic.orderByUse(listOf("A", "B", "C"), emptyMap()))
    @Test fun newCustomTagWithUseMovesUp() =
        org.junit.Assert.assertEquals("Pets", Logic.orderByUse(Logic.allTags(listOf("Pets")), mapOf("Pets" to 5)).first())
}

class ReminderTagTest {
    private val now = TestUtil.ms(2026, 11, 6)
    @Test fun reminderShowsTagAndMerchant() {
        val row = TestUtil.pending("Ravi", "SWIGGY", 250.0, TestUtil.ms(2026, 11, 3)).copy(category = "Food")
        val msg = Reminder.build("Ravi", listOf(row), emptyMap(), "", now)
        org.junit.Assert.assertTrue(msg, msg.contains("Food · "))
    }
    @Test fun noTagNoPrefix() {
        val row = TestUtil.pending("Ravi", "SWIGGY", 250.0, TestUtil.ms(2026, 11, 3))
        org.junit.Assert.assertFalse(Reminder.build("Ravi", listOf(row), emptyMap(), "", now).contains("Food"))
    }
}

class LentTest {
    private fun row(person: String, amt: Double, cat: String, settled: Double = 0.0) =
        TestUtil.pending(person, "x", amt, 0L, settled = settled).copy(category = cat)
    @Test fun threeFriendsLentSixThousand() {
        val rows = listOf(row("A", 1000.0, "Lent"), row("B", 2000.0, "Lent"), row("C", 3000.0, "Lent"), row("D", 500.0, "Food Order"))
        org.junit.Assert.assertEquals(6000.0, Logic.lentTotal(rows), 0.001)
    }
    @Test fun partlyRepaidLoanCountsRemainder() =
        org.junit.Assert.assertEquals(700.0, Logic.lentTotal(listOf(row("A", 1000.0, "Lent", settled = 300.0))), 0.001)
    @Test fun lentTagIsBuiltIn() = org.junit.Assert.assertTrue(Categories.all.contains("Lent"))
    @Test fun otherStaysLast() = org.junit.Assert.assertEquals("Other", Categories.all.last())
}
