package com.sabharish.splittracker

import com.sabharish.splittracker.TestUtil.tx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RefundMatcherTest {
    private val refundAmazon = tx(100, "AMAZON", 500.0, 1_000_000, status = "INBOX", kind = "REFUND")
    private val upiReversal = tx(101, "UPI reversal", 500.0, 1_000_000, status = "INBOX", kind = "REFUND")

    @Test fun noCandidatesMeansNoMatch() {
        assertNull(RefundMatcher.find(refundAmazon, emptyList()))
    }

    @Test fun onePurchaseFromTheSameMerchantClosesAutomatically() {
        val r = RefundMatcher.find(refundAmazon, listOf(tx(1, "Amazon Pay", 500.0, 10), tx(2, "Swiggy", 500.0, 20)))!!
        assertEquals(1L, r.first.id); assertTrue(r.second)
    }

    @Test fun twoPurchasesFromTheSameMerchantAsksTheUser() {
        val r = RefundMatcher.find(refundAmazon, listOf(tx(1, "Amazon", 500.0, 10), tx(2, "Amazon", 500.0, 20)))!!
        assertEquals(2L, r.first.id)     // most recent is the suggestion
        assertFalse(r.second)
    }

    @Test fun noMerchantMatchAsksTheUserWithTheMostRecentGuess() {
        val r = RefundMatcher.find(refundAmazon, listOf(tx(1, "Swiggy", 500.0, 10), tx(2, "Uber", 500.0, 20)))!!
        assertEquals(2L, r.first.id); assertFalse(r.second)
    }

    @Test fun singleFailedPaymentClosesEvenIfNamesDiffer() {
        val r = RefundMatcher.find(refundAmazon, listOf(tx(1, "Some Gateway", 500.0, 10, status = "FAILED"), tx(2, "Amazon", 500.0, 20)))!!
        assertEquals(1L, r.first.id); assertTrue(r.second)
    }

    @Test fun twoFailedPaymentsAsksTheUser() {
        val r = RefundMatcher.find(refundAmazon, listOf(tx(1, "X", 500.0, 10, status = "FAILED"), tx(2, "Y", 500.0, 20, status = "FAILED")))!!
        assertEquals(2L, r.first.id); assertFalse(r.second)
    }

    @Test fun upiReversalWithOneCandidateCloses() {
        val r = RefundMatcher.find(upiReversal, listOf(tx(1, "Kota Sai", 500.0, 10, kind = "UPI_OUT")))!!
        assertEquals(1L, r.first.id); assertTrue(r.second)
    }

    @Test fun upiReversalWithTwoCandidatesAsks() {
        val r = RefundMatcher.find(upiReversal, listOf(tx(1, "A", 500.0, 10, kind = "UPI_OUT"), tx(2, "B", 500.0, 20, kind = "UPI_OUT")))!!
        assertEquals(2L, r.first.id); assertFalse(r.second)
    }

    @Test fun failedBeatsNormalForUpiReversal() {
        val r = RefundMatcher.find(upiReversal, listOf(tx(1, "A", 500.0, 30, kind = "UPI_OUT"), tx(2, "B", 500.0, 10, kind = "UPI_OUT", status = "FAILED")))!!
        assertEquals(2L, r.first.id); assertTrue(r.second)
    }

    @Test fun rankingOrder() {
        val ranked = RefundMatcher.rank(refundAmazon, listOf(tx(1, "Swiggy", 500.0, 30), tx(2, "Amazon", 500.0, 10), tx(3, "X", 500.0, 5, status = "FAILED")))
        assertEquals(listOf(3L, 2L, 1L), ranked.map { it.id })
    }
}
