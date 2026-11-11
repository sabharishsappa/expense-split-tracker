package com.sabharish.splittracker

import java.util.Calendar

/** Everything the All tab can filter by. Defaults mean "no filter". */
data class TxnFilter(
    val type: String = "Any",        // Any | Credit card | Debit card | UPI
    val tagState: String = "Any",    // Any | Tagged | Untagged
    val tag: String = "",            // a specific tag, or ""
    val range: String = "Any time",  // see Filters.ranges
    val status: String = "Any",      // Any | Needs you | Pending | Refunds | Failed
    val query: String = "",
    val minAmt: Double? = null,
    val maxAmt: Double? = null
) {
    val active get() = this != TxnFilter()
}

object Filters {
    val types = listOf("Any", "Credit card", "Debit card", "UPI")
    val ranges = listOf("Any time", "Last 7 days", "This month", "Last month", "Last 30 days", "Last 90 days")
    val statuses = listOf("Any", "Needs you", "Pending", "Refunds", "Failed")

    fun isDebitCard(t: Txn) = t.kind == "SPEND" && t.bank == "KOTAK"

    /** Start (inclusive) and end (exclusive) in millis; null = open ended. */
    fun rangeFor(name: String, now: Long): Pair<Long?, Long?> {
        val day = 86_400_000L
        fun monthStart(offset: Int): Long {
            val c = Calendar.getInstance(); c.timeInMillis = now
            c.set(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0); c.add(Calendar.MONTH, offset)
            return c.timeInMillis
        }
        return when (name) {
            "Last 7 days" -> (now - 7 * day) to null
            "Last 30 days" -> (now - 30 * day) to null
            "Last 90 days" -> (now - 90 * day) to null
            "This month" -> monthStart(0) to null
            "Last month" -> monthStart(-1) to monthStart(0)
            else -> null to null
        }
    }

    fun matches(t: Txn, shares: List<Share>, aliases: Map<String, String>, f: TxnFilter, now: Long): Boolean {
        val typeOk = when (f.type) {
            "Credit card" -> t.kind == "SPEND" && !isDebitCard(t)
            "Debit card" -> isDebitCard(t)
            "UPI" -> t.kind.startsWith("UPI")
            else -> true
        }
        if (!typeOk) return false
        val untagged = t.category.isBlank()
        if (f.tagState == "Tagged" && untagged) return false
        if (f.tagState == "Untagged" && !untagged) return false
        if (f.tag.isNotBlank() && !t.category.equals(f.tag, true)) return false
        if (f.minAmt != null && t.amount < f.minAmt - 0.005) return false
        if (f.maxAmt != null && t.amount > f.maxAmt + 0.005) return false
        val (from, to) = rangeFor(f.range, now)
        if (from != null && t.txnDate < from) return false
        if (to != null && t.txnDate >= to) return false
        val statusOk = when (f.status) {
            "Needs you" -> t.status == "INBOX"
            "Pending" -> shares.any { it.person != Logic.ME && it.settled < it.amount - 0.005 }
            "Refunds" -> t.kind == "REFUND" || t.status == "REVERSED"
            "Failed" -> t.status == "FAILED"
            else -> true
        }
        if (!statusOk) return false
        return textMatches(t, shares, aliases, f.query)
    }

    /** Every word must appear somewhere: merchant, friendly name, tag, person, account, note, SMS text or amount. */
    fun textMatches(t: Txn, shares: List<Share>, aliases: Map<String, String>, query: String): Boolean {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val hay = listOf(t.merchant, Logic.title(t.merchant, aliases), t.category, t.bank, t.card, t.note, t.body)
            .plus(shares.map { it.person }).joinToString(" \u0001 ")
        return words.all { w ->
            hay.contains(w, true) || amountMatches(t.amount, w)
        }
    }

    private fun amountMatches(amount: Double, word: String): Boolean {
        val n = word.replace(",", "").removePrefix("₹").toDoubleOrNull() ?: return false
        return Math.abs(n - amount) < 0.011
    }
}
