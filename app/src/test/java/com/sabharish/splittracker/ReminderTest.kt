package com.sabharish.splittracker

import com.sabharish.splittracker.TestUtil.ms
import com.sabharish.splittracker.TestUtil.pending
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(text.contains("Total pending: ₹37.50\n\nPay via any UPI app (GPay, PhonePe, Paytm) to:\nme@okaxis\n\nThank you!"))
    }

    @Test fun noUpiLineWhenBlank() {
        val rows = listOf(pending("Rajesh", "Swiggy", 640.0, nov6))
        assertFalse(Reminder.build("Rajesh", rows, emptyMap(), "  ", nov6 + day).contains("Pay via"))
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
