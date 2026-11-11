package com.sabharish.splittracker

import com.sabharish.splittracker.TestUtil.ms
import com.sabharish.splittracker.TestUtil.pending
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderTest {
    private val day = 86_400_000L
    private val nov6 = ms(2026, 11, 6)

    @Test fun sharedBillShowsTotalPeopleAndShare() {
        val rows = listOf(pending("Rajesh Kumar", "Namma Irani", 37.5, nov6, total = 187.5, parts = 5))
        val text = Reminder.build("Rajesh Kumar", rows, emptyMap(), "", nov6 + day)
        val expected = "Hi Rajesh 👋\n\n" +
            "A quick reminder about the spends I covered for you:\n\n" +
            "• 6 Nov · Namma Irani\n" +
            "   Bill ₹187.50 · split between 5 · your share ₹37.50\n\n" +
            "Total pending: ₹37.50\n\n" +
            "Thank you!"
        assertEquals(expected, text)
    }

    @Test fun upiIdIsAddedWhenPresent() {
        val rows = listOf(pending("Rajesh", "Namma Irani", 37.5, nov6, total = 187.5, parts = 5))
        val text = Reminder.build("Rajesh", rows, emptyMap(), "me@okaxis", nov6 + day)
        assertTrue(text.contains("Total pending: ₹37.50\n\nPay by clicking the UPI ID below:\nme@okaxis\n\n"))
        assertTrue(text.endsWith("Thank you!"))
    }

    @Test fun noUpiLineWhenBlank() {
        val rows = listOf(pending("Rajesh", "Swiggy", 640.0, nov6))
        assertFalse(Reminder.build("Rajesh", rows, emptyMap(), "  ", nov6 + day).contains("Pay by clicking"))
    }

    @Test fun fullySpentOnThemIsASingleLine() {
        val rows = listOf(pending("Rajesh", "Swiggy", 640.0, nov6))
        val text = Reminder.build("Rajesh", rows, emptyMap(), "", nov6 + day)
        assertTrue(text.contains("• 6 Nov · Swiggy · ₹640\n"))
        assertFalse(text.contains("Bill "))
    }

    @Test fun partlyPaidShareShowsWhatIsDue() {
        val rows = listOf(pending("Rajesh", "Namma Irani", 100.0, nov6, total = 300.0, parts = 3, settled = 40.0))
        val text = Reminder.build("Rajesh", rows, emptyMap(), "", nov6 + day)
        assertTrue(text.contains("your share ₹100 (paid ₹40, due ₹60)"))
        assertTrue(text.contains("Total pending: ₹60"))
    }

    @Test fun toneGetsFirmerWithAge() {
        val rows = listOf(pending("Rajesh", "Swiggy", 640.0, nov6))
        assertTrue(Reminder.build("Rajesh", rows, emptyMap(), "", nov6 + 5 * day).startsWith("Hi Rajesh 👋"))
        assertTrue(Reminder.build("Rajesh", rows, emptyMap(), "", nov6 + 20 * day).startsWith("Hi Rajesh 👋"))
        assertTrue(Reminder.build("Rajesh", rows, emptyMap(), "", nov6 + 20 * day).contains("A gentle reminder"))
        val old = Reminder.build("Rajesh", rows, emptyMap(), "", nov6 + 40 * day)
        assertTrue(old.contains("pending for over a month"))
        assertTrue(old.endsWith("Could you please clear it this week? Thank you!"))
    }

    @Test fun usesTheShortAliasNotTheBankName() {
        val rows = listOf(pending("Rajesh", "RAZDREAMPLUGPAYTECHSOL", 500.0, nov6))
        val text = Reminder.build("Rajesh", rows, emptyMap(), "", nov6 + day)
        assertTrue(text.contains("CRED"))
        assertFalse(text.contains("RAZDREAM"))
    }

    @Test fun longListsAreCappedAtEight() {
        val rows = (1..10).map { pending("Rajesh", "Item$it", 10.0, nov6 + it * day) }
        val text = Reminder.build("Rajesh", rows, emptyMap(), "", nov6 + 11 * day)
        assertTrue(text.contains("…and 2 more"))
        assertTrue(text.contains("Total pending: ₹100"))
        assertFalse(text.contains("Item9"))
    }

    @Test fun nothingPendingGivesEmptyText() {
        assertEquals("", Reminder.build("Rajesh", emptyList(), emptyMap(), "", nov6))
    }
}

class SplitRequestTest {
    @Test fun badmintonMessage() {
        val m = Reminder.splitRequest("Ravi Kumar", "Badminton", 1200.0, 300.0, "me@okaxis")
        assertTrue(m.startsWith("Hi Ravi 👋\n\nBadminton · total ₹1,200\nYour share: ₹300"))
        assertTrue(m, m.contains("Pay by clicking the UPI ID below:\nme@okaxis\n\n"))
        assertTrue(m, m.endsWith("Thank you!"))
        assertTrue(m.endsWith("Thank you!"))
    }
    @Test fun noUpiNoPayLine() = assertFalse(Reminder.splitRequest("Ravi", "Badminton", 1200.0, 300.0, " ").contains("Pay by"))
    @Test fun blankTitleHasFallback() = assertTrue(Reminder.splitRequest("Ravi", " ", 100.0, 50.0, "").contains("our split"))
}

class AutoSettleTest {
    private fun row(id: Long, person: String, amt: Double, settled: Double = 0.0) =
        TestUtil.pending(person, "x", amt, 0L, settled = settled).copy(shareId = id)
    @Test fun exactBillMatchesByName() =
        assertEquals(listOf(2L), Logic.autoSettle(listOf(row(1, "Ravi Kumar", 100.0), row(2, "Ravi Kumar", 250.0)), "RAVI KUMAR K", 250.0))
    @Test fun wholeBalanceMatches() =
        assertEquals(listOf(1L, 2L), Logic.autoSettle(listOf(row(1, "Ravi Kumar", 100.0), row(2, "Ravi Kumar", 250.0)), "Ravi Kumar", 350.0))
    @Test fun partlyPaidUsesRemainder() =
        assertEquals(listOf(1L), Logic.autoSettle(listOf(row(1, "Ravi", 100.0, settled = 40.0)), "Ravi", 60.0))
    @Test fun unknownNameNoMatch() = assertNull(Logic.autoSettle(listOf(row(1, "Ravi", 100.0)), "Someone Else", 100.0))
    @Test fun oddAmountNoMatch() = assertNull(Logic.autoSettle(listOf(row(1, "Ravi", 100.0)), "Ravi", 90.0))
    @Test fun twoEqualBillsAskTheUser() = assertNull(Logic.autoSettle(listOf(row(1, "Ravi", 100.0), row(2, "Ravi", 100.0)), "Ravi", 100.0))
    @Test fun myOwnShareIsNeverSettled() = assertNull(Logic.autoSettle(listOf(row(1, "Me", 100.0)), "Me Myself", 100.0))
}

class GroupsTest {
    @Test fun roundTrip() {
        val g = Groups.upsert(Groups.upsert(emptyList(), "Badminton", listOf("Me", "Ravi", "Kiran")), "Trip", listOf("Sai"))
        assertEquals(g, Groups.decode(Groups.encode(g)))
        assertEquals("Trip", g[0].name); assertEquals(listOf("Ravi", "Kiran"), g[1].people)
    }
    @Test fun sameNameReplacesAndMovesToFront() {
        val g = Groups.upsert(Groups.upsert(emptyList(), "Badminton", listOf("A")), "Trip", listOf("B"))
        val h = Groups.upsert(g, "badminton", listOf("C"))
        assertEquals(listOf("badminton", "Trip"), h.map { it.name }); assertEquals(listOf("C"), h[0].people)
    }
    @Test fun onlyMeOrNoNameIsIgnored() {
        assertTrue(Groups.upsert(emptyList(), "X", listOf("Me")).isEmpty())
        assertTrue(Groups.upsert(emptyList(), " ", listOf("A")).isEmpty())
    }
    @Test fun keepsAtMostEight() = assertEquals(8, (1..12).fold(emptyList<Groups.Group>()) { a, i -> Groups.upsert(a, "G$i", listOf("P$i")) }.size)
    @Test fun badTextIsSkipped() = assertTrue(Groups.decode("nonsense\n|x\nA|").isEmpty())
}
