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

    /**
     * A received UPI payment that is clearly a repayment: the sender's name matches one person who owes you,
     * and the amount is exactly one of their bills or exactly everything they owe. Returns those share ids, else null.
     */
    fun autoSettle(rows: List<PendingRow>, sender: String, amount: Double): List<Long>? {
        val owing = rows.filter { it.person != ME && it.amount - it.settled > 0.004 }
        val person = strictMatch(sender, owing.map { it.person }.distinct()) ?: return null
        val mine = owing.filter { it.person == person }
        val one = mine.filter { Math.abs((it.amount - it.settled) - amount) < 0.011 }
        if (one.size == 1) return listOf(one[0].shareId)
        if (one.size > 1) return null                       // two equal bills: let the user choose
        return if (Math.abs(mine.sumOf { it.amount - it.settled } - amount) < 0.011) mine.map { it.shareId } else null
    }

    /** New paid total after paying [add] towards a debt: never below what was paid, never above the debt. */
    fun paidAfter(total: Double, paid: Double, add: Double): Double =
        Math.round(Math.min(total, Math.max(paid, paid + add)) * 100) / 100.0

    /** What you still owe, per person, biggest first. */
    fun owedByYou(list: List<Payable>): List<Pair<String, Double>> =
        list.groupBy { it.person }.map { (p, v) -> p to v.sumOf { it.amount - it.paid } }.filter { it.second > 0.004 }.sortedByDescending { it.second }

    /** Cash lent to people (tag "Lent"): what is still to come back. */
    fun lentTotal(rows: List<PendingRow>): Double =
        rows.filter { it.person != ME && it.category.equals("Lent", true) }.sumOf { it.amount - it.settled }

    /** One line describing what the single ✓ will do to every ticked bill. */
    fun bulkSummary(count: Int, total: Double, tag: String, people: List<String>): String {
        if (count == 0) return ""
        if (tag.isBlank() && people.isEmpty()) return "Pick a tag and/or who these were for"
        val parts = ArrayList<String>()
        if (tag.isNotBlank()) parts.add("tag $tag")
        if (people.isNotEmpty()) parts.add(
            if (people.size == 1) (if (people[0] == ME) "all mine" else "all ${people[0]}'s")
            else "split ${people.joinToString(" + ") { if (it == ME) "Me" else it }}")
        return "$count ${if (count == 1) "bill" else "bills"} · ${money(total)} → ${parts.joinToString(", ")}"
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

    /**
     * Like [bestMatch] but for actions done without asking: one clear winner only, and the person's first name
     * must appear in the sender's name (a shared surname like "Kumar" is not enough).
     */
    fun strictMatch(name: String, people: List<String>): String? {
        fun toks(s: String) = s.lowercase(Locale.ROOT).split(Regex("[^a-z]+")).filter { it.length >= 3 }
        fun hit(a: String, b: String) = a == b || (a.length >= 4 && b.length >= 4 && (a.startsWith(b) || b.startsWith(a)))
        val nt = toks(name)
        if (nt.isEmpty()) return null
        val scored = people.map { p -> val pt = toks(p); p to nt.count { a -> pt.any { b -> hit(a, b) } } }.filter { it.second > 0 }
        if (scored.isEmpty()) return null
        val top = scored.maxOf { it.second }
        val best = scored.filter { it.second == top }
        if (best.size != 1) return null
        val first = toks(best[0].first).firstOrNull() ?: return null
        return if (nt.any { hit(it, first) }) best[0].first else null
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

    /** Chosen items first (in the order chosen), then everything else in its usual order. */
    fun selectedFirst(all: List<String>, selected: List<String>): List<String> =
        selected.distinct() + all.filter { it !in selected }

    data class ExistingSplit(val people: List<String>, val mode: String, val amounts: Map<String, Double>, val allPaid: Boolean)

    /** Turns saved shares back into what the split screen shows, so a saved split can be edited. */
    fun splitFromExisting(total: Double, shares: List<Share>): ExistingSplit? {
        val live = shares.filter { it.amount > 0.004 }
        if (live.isEmpty()) return null
        val people = (live.filter { it.person == ME } + live.filter { it.person != ME }).map { it.person }
        val amounts = live.associate { it.person to it.amount }
        val eq = equalShares(total, people, false)
        val isEqual = eq.all { Math.abs(it.amount - (amounts[it.person] ?: -1.0)) <= 0.011 }
        val others = live.filter { it.person != ME }
        val allPaid = others.isNotEmpty() && others.all { it.settled >= it.amount - 0.005 }
        return ExistingSplit(people, if (isEqual) "EQUAL" else "AMOUNT", amounts, allPaid)
    }

    /** When a split is edited, money already received from someone is kept (up to their new share). */
    fun carrySettled(old: List<Share>, new: List<NewShare>): List<Double> =
        new.map { s -> if (s.paid) s.amount else minOf(old.firstOrNull { it.person == s.person }?.settled ?: 0.0, s.amount) }

    fun parseTags(s: String): List<String> = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    fun joinTags(tags: List<String>): String = tags.joinToString(",")

    /** Built-in tags with your own tags placed before "Other". */
    fun allTags(custom: List<String>, hidden: List<String> = emptyList()): List<String> =
        Categories.all.filter { it != "Other" && it !in hidden } + custom + "Other"

    /** Most-used tags first (ties keep their original order); "Other" always last. */
    fun orderByUse(tags: List<String>, counts: Map<String, Int>): List<String> {
        val lower = counts.mapKeys { it.key.lowercase(Locale.ROOT) }
        val body = tags.filter { it != "Other" }
        val sorted = body.withIndex().sortedWith(compareByDescending<IndexedValue<String>> { lower[it.value.lowercase(Locale.ROOT)] ?: 0 }.thenBy { it.index }).map { it.value }
        return sorted + tags.filter { it == "Other" }
    }

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
/** Spots bank-style messages the parser did not understand, so they can be reviewed on the phone. */
object Diagnostics {
    private val money = Regex("""(?:rs\.?|inr|₹)\s?[\d,]+""", RegexOption.IGNORE_CASE)
    private val verb = Regex("""\b(debited|credited|spent|sent|received|withdrawn|paid|payment of|refund)\b""", RegexOption.IGNORE_CASE)
    private val noise = Regex("""\b(otp|offer|win|cashback|loan|kyc|apply now|click|voucher|reward)\b""", RegexOption.IGNORE_CASE)
    fun looksFinancial(body: String?): Boolean = !body.isNullOrBlank() && money.containsMatchIn(body) && verb.containsMatchIn(body) && !noise.containsMatchIn(body)

    /** Hides digits and UPI handles so a message's shape can be shared without the numbers in it. */
    fun anonymise(body: String): String =
        body.replace(Regex("""[\w.\-]+@[A-Za-z]+"""), "name@bank").replace(Regex("""\d"""), "9")

    data class ReadCheck(
        val smsAllowed: Boolean, val receiveAllowed: Boolean, val notificationsAllowed: Boolean,
        val inboxTotal: Int, val scanned: Int, val recognised: Int, val unrecognised: Int,
        val unrecognisedSenders: List<String>, val sdk: Int,
        val notificationReadingOn: Boolean = false
    )

    /** Plain-language reasons why messages might not be showing up, most important first. */
    fun verdict(r: ReadCheck): List<String> = buildList {
        if (!r.smsAllowed) {
            add("The app is not allowed to read messages, so nothing can appear.")
            if (r.sdk >= 33) add("Android blocks this for apps installed from a file. Open Settings > Apps > Split Tracker, tap the ⋮ menu, choose \"Allow restricted settings\", then allow SMS under Permissions.")
            else add("Open Settings > Apps > Split Tracker > Permissions and allow SMS.")
            return@buildList
        }
        if (r.inboxTotal == 0) add("Reading is allowed but this phone shows no messages to the app. Check that the bank texts arrive in the normal Messages app, not a separate Business or Promotions inbox.")
        else if (r.scanned == 0) add("Reading works, but there are no messages from the last 90 days.")
        else if (r.recognised > 0) add("Reading works: ${r.recognised} bank messages from the last 90 days were understood.")
        else if (r.unrecognised == 0) add("Reading works, but none of the ${r.scanned} recent messages looked like a bank transaction. Banks may be sending only emails, or very small payments may not send a text.")
        if (r.unrecognised > 0) add("${r.unrecognised} look like bank transactions but were not understood" +
            (if (r.unrecognisedSenders.isNotEmpty()) " (from ${r.unrecognisedSenders.joinToString(", ")})" else "") +
            ". Use \"Copy without numbers\" below and send them to the developer.")
        if (!r.notificationReadingOn && (r.recognised == 0 || r.unrecognised > 0))
            add("Tip: turn on \"Read notifications too\" in Settings. It catches payments from Google Pay, PhonePe, your bank app and mail previews, even when texts can't be read.")
        if (!r.receiveAllowed) add("New messages will not be caught as they arrive: allow \"Receive SMS\" for the app.")
        if (!r.notificationsAllowed) add("Notifications are off, so you will not get a ping for new payments.")
    }
}

/** Groups you split with often ("Badminton" → Ravi, Kiran, Sai), remembered on this phone only. */
object Groups {
    data class Group(val name: String, val people: List<String>)
    private const val MAX = 8

    fun decode(raw: String): List<Group> = raw.lines().mapNotNull { l ->
        val i = l.indexOf('|'); if (i <= 0) return@mapNotNull null
        val people = l.substring(i + 1).split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (people.isEmpty()) null else Group(l.substring(0, i).trim(), people)
    }
    fun encode(gs: List<Group>) = gs.joinToString("\n") { it.name.replace("|", " ").replace("\n", " ") + "|" + it.people.joinToString(",") { p -> p.replace(",", " ") } }

    /** Newest first; the same name replaces the older entry; people never include "Me". */
    fun upsert(gs: List<Group>, name: String, people: List<String>): List<Group> {
        val n = name.trim().take(24)
        val others = people.filter { it != Logic.ME }.distinct()
        if (n.isEmpty() || others.isEmpty()) return gs
        return (listOf(Group(n, others)) + gs.filter { !it.name.equals(n, true) }).take(MAX)
    }
}

object Reminder {
    private fun payBlock(upi: String): String =
        if (upi.isBlank()) "" else "\n\nPay by clicking the UPI ID below:\n${upi.trim()}"

    /** The message for one person right after a split is created. */
    fun splitRequest(person: String, title: String, total: Double, share: Double, upi: String): String {
        val first = person.trim().substringBefore(" ")
        val what = title.trim().ifEmpty { "our split" }
        val pay = payBlock(upi)
        return "Hi $first 👋\n\n$what · total ${Logic.money(total)}\nYour share: ${Logic.money(share)}$pay\n\nThank you!"
    }

    fun build(person: String, rows: List<PendingRow>, aliases: Map<String, String>, upi: String, nowMs: Long): String {
        if (rows.isEmpty()) return ""
        val sorted = rows.sortedBy { it.txnDate }
        val total = sorted.sumOf { it.amount - it.settled }
        val days = ((nowMs - sorted.first().txnDate) / 86_400_000L).toInt()
        val first = person.trim().substringBefore(" ")
        val intro = when {
            days <= 14 -> "Hi $first 👋\n\nA quick reminder about the spends I covered for you:"
            days <= 30 -> "Hi $first 👋\n\nA gentle reminder about the spends I covered for you:"
            else -> "Hi $first 👋\n\nFollowing up on the spends I covered for you. This has been pending for over a month:"
        }
        val shown = sorted.take(8)
        val lines = shown.joinToString("\n") { r ->
            val shop = Logic.nameOnly(r.merchant, aliases)
            val name = if (r.category.isNotBlank() && !r.category.equals("Other", true)) "${r.category} · $shop" else shop
            val date = Logic.shortDate(r.txnDate)
            if (r.parts > 1) {
                val paid = if (r.settled > 0.004) " (paid ${Logic.money(r.settled)}, due ${Logic.money(r.amount - r.settled)})" else ""
                "• $date · $name\n   Bill ${Logic.money(r.txnTotal)} · split between ${r.parts} · your share ${Logic.money(r.amount)}$paid"
            } else {
                "• $date · $name · ${Logic.money(r.amount - r.settled)}"
            }
        }
        val more = if (sorted.size > shown.size) "\n…and ${sorted.size - shown.size} more" else ""
        val pay = payBlock(upi)
        val close = if (days > 30) "Could you please clear it this week? Thank you!" else "Thank you!"
        return "$intro\n\n$lines$more\n\nTotal pending: ${Logic.money(total)}$pay\n\n$close"
    }
}
