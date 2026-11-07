package com.sabharish.splittracker

import org.junit.Assert.*
import org.junit.Test

class StrictMatchTest {
    @Test fun sharedSurnameIsNotEnough() = assertNull(Logic.strictMatch("Suresh Kumar", listOf("Ravi Kumar")))
    @Test fun tieGivesNoAnswer() = assertNull(Logic.strictMatch("Kumar Ravi", listOf("Ravi Kumar", "Ravi Kumari")))
    @Test fun fullNameMatches() = assertEquals("Ravi Kumar", Logic.strictMatch("RAVI KUMAR K", listOf("Ravi Kumar", "Anita Rao")))
    @Test fun firstNameOnlyMatches() = assertEquals("Ravi", Logic.strictMatch("Ravi Shankar", listOf("Ravi", "Anita")))
    @Test fun nothingInCommon() = assertNull(Logic.strictMatch("Zed", listOf("Ravi")))
}

class DiagnosticsTest {
    @Test fun bankDebitLooksFinancial() = assertTrue(Diagnostics.looksFinancial("Rs. 500 debited from A/c XX1234 on 01-Nov"))
    @Test fun otpIsIgnored() = assertFalse(Diagnostics.looksFinancial("OTP 123456 for payment of Rs. 500"))
    @Test fun promoIsIgnored() = assertFalse(Diagnostics.looksFinancial("Win cashback Rs. 500 credited, apply now"))
    @Test fun blankIsIgnored() { assertFalse(Diagnostics.looksFinancial("")); assertFalse(Diagnostics.looksFinancial(null)) }
}
