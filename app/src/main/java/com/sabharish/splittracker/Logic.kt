package com.sabharish.splittracker

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure logic with no Android dependencies, so it can be unit tested on a normal JVM.
 * Repo and the UI call into these functions.
 */
object Logic {
    const val ME = "Me"

    fun norm(s: String): String = s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")

    // ---------- money ----------

    /** Indian grouping: 1,00,000. Whole rupees drop the decimals. */
    fun money(v: Double): String {
        val neg = v < 0
        val cents = Math.round(Math.abs(v) * 100)
        val whole = (cents / 100).toString()
        val frac = (cents % 100).toInt()
        val grouped = if (whole.length <= 3) whole
        else whole.dropLast(3).reversed().chunked(2).joinToString(",").reversed() + "," + whole.takeLast(3)
        val body = if (frac == 0) grouped else grouped + "." + frac.toString().padStart(2, '0')
        return (if (neg) "-" else "") + "₹" + body
    }

    fun shortDate(ms: Long): String = SimpleDateFormat("d MMM", Locale.ENGLISH).format(Date(ms))

    // ---------- splitting ----------

    /** Equal split. "Me" is listed first. Rounding leftovers go to the last person. */
    fun equalShares(total: Double, names: List<String>, paidOthers: Boolean): List<NewShare> {
        val ordered = names.filter { it == ME } + names.filter { it != ME }
        val n = ordered.size
        if (n == 0) return emptyList()
        val per = Math.floor(total / n * 100) / 100
        var left = total
        return ordered.mapIndexed { i, p ->
            val a = if (i == n - 1) Math.round(left * 100) / 100.0 else per
            left -= a
            NewShare(p, a, p == ME || paidOthers)
        }
    }

    /** Shares proportional to weights (percentages or parts). Returns null for invalid input. */
    fun weightedShares(total: Double, names: List<String>, weights: List<Double>, paidOthers: Boolean): List<NewShare>? {
        if (names.isEmpty() || names.size != weights.size || weights.any { it < 0 }) return null
        val sum = weights.sum()
        if (sum <= 0) return null
        var left = total
        return names.mapIndexed { i, p ->
            val a = if (i == names.size - 1) Math.round(left * 100) / 100.0 else Math.floor(total * weights[i] / sum * 100) / 100
            left -= a
            NewShare(p, a, p == ME || paidOthers)
        }
    }

    /** One-line summary shown next to the Save button. */
    fun selectionSummary(total: Double, selected: List<String>): String {
        if (selected.isEmpty()) return "Pick who this was for"
        val others = selected.filter { it != ME }
        if (others.isEmpty()) return "Mine"
        if (!selected.contains(ME) && others.size == 1) return "${others[0]} owes ${money(total)}"
        val per = equalShares(total, selected, false).first().amount
        return "Split ${selected.size} ways · ${money(per)} each"
    }

    // ---------- names ----------

    /** Best matching known person for a UPI sender name (e.g. "KURUVA KOUSHIK" ~ "Koushik Kuruva"). */
    fun bestMatch(name: String, people: List<String>): String? {
        fun toks(s: String) = s.lowercase(Locale.ROOT).split(Regex("[^a-z]+")).filter { it.length >= 3 }
        val nt = toks(name)
        if (nt.isEmpty()) return null
        var best: String? = null
        var bestScore = 0
        for (p in people) {
            val pt = toks(p)
            val score = nt.count { a ->
                pt.any { b -> a == b || (a.length >= 4 && b.length >= 4 && (a.startsWith(b) || b.startsWith(a))) }
            }
            if (score > bestScore) { bestScore = score; best = p }
        }
        return best
    }

    /** Friendly name with the bank's original name beside it, so the merchant stays recognisable. */
    fun title(raw: String, aliases: Map<String, String>): String {
        val a = aliases[norm(raw)] ?: Categories.builtinAlias(raw)
        return if (a == null || a.equals(raw, true)) raw else "$a · $raw"
    }

    /** Short name for messages to other people. */
    fun nameOnly(raw: String, aliases: Map<String, String>): String =
        aliases[norm(raw)] ?: Categories.builtinAlias(raw) ?: raw

    // ---------- tags ----------

    /** Adds a custom tag. Blank, duplicate (any case) or built-in names are ignored. */
    fun addTag(existing: List<String>, name: String): List<String> {
        val n = name.replace(",", " ").trim().take(24)
        if (n.isEmpty()) return existing
        val taken = (Categories.all + existing).map { it.lowercase(Locale.ROOT) }
        return if (n.lowercase(Locale.ROOT) in taken) existing else existing + n
    }

    /** What is still due on the ticked bills. Nothing ticked means all of them. */
    fun dueOf(rows: List<PendingRow>, ticked: Set<Long>): Double =
        rows.filter { ticked.isEmpty() || it.shareId in ticked }.sumOf { it.amount - it.settled }

    /** Other transactions from the same merchant (spelling, case and spaces ignored). */
    fun similar(t: Txn, others: List<Txn>): List<Txn> {
        val n = norm(t.merchant)
        return if (n.isEmpty()) emptyList() else others.filter { it.id != t.id && norm(it.merchant) == n }
    }

    fun parseTags(s: String): List<String> = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    fun joinTags(tags: List<String>): String = tags.joinToString(",")

    /** Built-in tags with your own tags placed before "Other". */
    fun allTags(custom: List<String>, hidden: List<String> = emptyList()): List<String> =
        Categories.all.filter { it != "Other" && it !in hidden } + custom + "Other"

    /** New list with [old] renamed, or null when the new name is empty or already used. */
    fun renameTag(custom: List<String>, old: String, new: String): List<String>? {
        val n = new.replace(",", " ").trim().take(24)
        if (n.isEmpty() || custom.none { it.equals(old, true) }) return null
        val taken = (Categories.all + custom.filter { !it.equals(old, true) }).map { it.lowercase(Locale.ROOT) }
        if (n.lowercase(Locale.ROOT) in taken) return null
        return custom.map { if (it.equals(old, true)) n else it }
    }

    /** WhatsApp wants country code + number, digits only. Indian 10-digit numbers get 91. */
    fun waNumber(raw: String): String? {
        var d = raw.filter { it.isDigit() }.trimStart('0')
        if (d.length == 10) d = "91$d"
        return if (d.length in 11..15) d else null
    }
}

/** Decides which purchase a refund, reversal or failed-payment refund belongs to. */
object RefundMatcher {
    private fun alnum4(s: String) = Logic.norm(s).take(4)

    fun isUpiReversal(refund: Txn) = refund.merchant == "UPI reversal"

    /** Best guess first: failed payments, then same merchant, then most recent. */
    fun rank(refund: Txn, candidates: List<Txn>): List<Txn> {
        val reversal = isUpiReversal(refund)
        return candidates.sortedWith(
            compareByDescending<Txn> { it.status == "FAILED" }
                .thenByDescending { reversal || alnum4(it.merchant) == alnum4(refund.merchant) }
                .thenByDescending { it.smsTime }
        )
    }

    /** Returns the best match and whether it is certain enough to close the books without asking. */
    fun find(refund: Txn, candidates: List<Txn>): Pair<Txn, Boolean>? {
        val c = rank(refund, candidates)
        if (c.isEmpty()) return null
        val failed = c.filter { it.status == "FAILED" }
        if (failed.size == 1) return failed[0] to true
        val matches = if (isUpiReversal(refund)) c else c.filter { alnum4(it.merchant) == alnum4(refund.merchant) }
        if (failed.isEmpty() && matches.size == 1) return matches[0] to true
        return c[0] to false
    }
}

/** The message sent to someone who owes you money. */
object Reminder {
    fun build(person: String, rows: List<PendingRow>, aliases: Map<String, String>, upi: String, nowMs: Long): String {
        if (rows.isEmpty()) return ""
        val sorted = rows.sortedBy { it.txnDate }
        val total = sorted.sumOf { it.amount - it.settled }
        val days = ((nowMs - sorted.first().txnDate) / 86_400_000L).toInt()
        val first = person.trim().substringBefore(" ")
        val intro = when {
            days <= 14 -> "Hi $first 👋\n\nA quick reminder about the spends I covered for you:"
            days <= 30 -> "Hi $first,\n\nA gentle reminder about the spends I covered for you:"
            else -> "Hi $first,\n\nFollowing up on the spends I covered for you. This has been pending for over a month:"
        }
        val shown = sorted.take(8)
        val lines = shown.joinToString("\n") { r ->
            val name = Logic.nameOnly(r.merchant, aliases)
            val date = Logic.shortDate(r.txnDate)
            if (r.parts > 1) {
                val paid = if (r.settled > 0.004) " (paid ${Logic.money(r.settled)}, due ${Logic.money(r.amount - r.settled)})" else ""
                "• $date · $name\n   Bill ${Logic.money(r.txnTotal)} · split between ${r.parts} · your share ${Logic.money(r.amount)}$paid"
            } else {
                "• $date · $name · ${Logic.money(r.amount - r.settled)}"
            }
        }
        val more = if (sorted.size > shown.size) "\n…and ${sorted.size - shown.size} more" else ""
        val pay = if (upi.isNotBlank()) "\nPay to: ${upi.trim()}" else ""
        val close = if (days > 30) "Could you please clear it this week? Thank you!" else "Thank you!"
        return "$intro\n\n$lines$more\n\nTotal pending: ${Logic.money(total)}$pay\n\n$close"
    }
}
