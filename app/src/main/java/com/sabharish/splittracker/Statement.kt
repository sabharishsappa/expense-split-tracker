package com.sabharish.splittracker

import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

/** One line of a bank or card statement. */
data class StmtLine(val date: Long, val desc: String, val amount: Double, val credit: Boolean)

data class Recon(
    val matched: Int,
    val missing: List<StmtLine>,      // on the statement, not in the app
    val extra: List<Txn>,             // in the app within the statement dates, not on the statement
    val lines: Int
)

/** A card or account you can upload a statement for. */
data class StmtSource(val key: String, val label: String, val bank: String, val last: String, val isCard: Boolean)

object Statement {
    fun sources(txns: List<Txn>): List<StmtSource> {
        val cards = txns.filter { it.kind == "SPEND" && !Filters.isDebitCard(it) }.map { it.bank to it.card }.distinct()
            .sortedBy { it.first + it.second }
            .map { (b, c) -> StmtSource("card|$b|$c", "${Accounts.label(b, c).replace("••", "card ••")}", b, c, true) }
        val det = Accounts.detected(txns)
        val accts = det.map { (b, c) -> StmtSource("acct|$b|$c", "${Accounts.label(b, c)} (account)", b, c, false) }
        return cards + accts
    }

    fun txnsFor(s: StmtSource, txns: List<Txn>): List<Txn> {
        val det = Accounts.detected(txns)
        return if (s.isCard) txns.filter { it.bank == s.bank && it.card == s.last && (it.kind == "SPEND" || it.kind == "REFUND" || it.kind == "PAYMENT") && it.status != "REVERSED" }
        else txns.filter { Accounts.accountOf(it, det) == Accounts.keyOf(s.bank, s.last) }
    }

    private const val NUM = """\d[\d,]*(?:\.\d{1,2})?"""
    private val dateRx = Regex("""^\s*(?:\d{1,4}\s+)?(\d{1,2}[/\-. ](?:\d{1,2}|[A-Za-z]{3})[/\-. ]\d{2,4})(?!\d)""")
    private val openingRx = Regex("""(?:Opening Balance|Balance on \d{2}-\d{2}-\d{2}:?)\D*?(-?[\d,]+\.\d{2})\s*(?:null.*)?$""")
    private val cardRx = Regex("""(\+)?\s*(?:(?<![A-Za-z0-9])C|₹|Rs\.?|`)?\s*($NUM)\s*(Cr|CR|Dr|DR|C|D)?\s*$""")
    private val fedRx = Regex("""(\s+TFR\s+\S+)?\s+($NUM)\s+($NUM)\s+($NUM)\s+(?:CR|DR)\s*$""")
    private val serialRx = Regex("""^\d{10,}\s+""")
    private val plainRx = Regex("""(?<![\w.])-?($NUM)\s*$""")
    private val decimalTok = Regex("""-?\d[\d,]*\.\d{1,2}""")
    private val skipRx = Regex("""\b(total|statement|due|opening|closing|balance|previous|summary|limit|rewards?|points)\b""", RegexOption.IGNORE_CASE)
    private val formats = listOf("dd/MM/yyyy", "dd-MM-yyyy", "dd.MM.yyyy", "dd/MM/yy", "dd-MM-yy", "dd MMM yyyy", "dd-MMM-yyyy", "dd/MMM/yyyy", "dd-MMM-yy", "dd MMM yy")

    private fun parseDate(s: String): Long? {
        val t = s.trim().replace(Regex("\\s+"), " ")
        for (f in formats) try {
            val df = SimpleDateFormat(f, Locale.ENGLISH); df.isLenient = false
            val d = df.parse(t)
            if (d != null) {
                val c = java.util.Calendar.getInstance(); c.time = d
                if (c.get(java.util.Calendar.YEAR) >= 1990) return d.time
            }
        } catch (_: Exception) {}
        return null
    }

    private fun num(s: String) = s.replace(",", "").toDouble()

    /**
     * Reads statement text (from a CSV or pulled out of a PDF): every line that starts with a date and ends with an amount.
     * Handles card statements (amount followed by C/D or Cr/Dr, or "+ C 68.90" for money in) and bank statements
     * (amount + balance, or credit/debit/balance columns; the direction comes from the column order or from how the
     * balance moved). Wrapped description lines and totals are ignored.
     */
    fun parse(text: String): List<StmtLine> {
        val out = mutableListOf<StmtLine>()
        var prev: Double? = null
        val creditFirst = Regex("""Credit\s+Debit""").containsMatchIn(text)
        val all = text.lines()
        for ((idx, raw) in all.withIndex()) {
            val line = raw.trim().trim('"')
            val d = dateRx.find(line)
            if (d == null) {
                openingRx.find(line)?.let { prev = num(it.groupValues[1]) }
                continue
            }
            val date = parseDate(d.groupValues[1]) ?: continue
            var rest = line.substring(d.range.last + 1).trim()
            rest = rest.replace(Regex("""^[|\s]*(\d{1,2}:\d{2}(:\d{2})?)?[|\s]*"""), "")
            // a second (value) date right after the first
            dateRx.find(rest)?.let { m2 ->
                if (m2.range.first == 0 && parseDate(m2.groupValues[1]) != null) rest = rest.substring(m2.range.last + 1).trim()
            }
            rest = rest.replace(Regex("""\s+[lI|]$"""), "")

            // ICICI card: "date  serial-no  merchant  points" with the amount on the same or a following line
            if (serialRx.containsMatchIn(rest)) {
                val block = mutableListOf(rest)
                for (j in idx + 1..minOf(idx + 3, all.size - 1)) {
                    if (dateRx.containsMatchIn(all[j])) break
                    block.add(all[j].trim())
                }
                val amount = block.firstNotNullOfOrNull { b -> Regex("""(?<![\w.])(\d[\d,]*\.\d{2})(?![\w.])""").find(b)?.groupValues?.get(1) }
                if (amount != null) {
                    val points = Regex("""\s{2,}(-?\d+)(?=\s|$)""").find(rest)
                    val credit = block.any { Regex("""\bCR\b""").containsMatchIn(it) } || points?.groupValues?.get(1)?.startsWith("-") == true
                    val desc = rest.replace(serialRx, "").let { d -> points?.let { d.substring(0, d.indexOf(it.value).coerceAtLeast(0)) } ?: d }
                    out.add(StmtLine(date, clean(desc), num(amount), credit))
                }
                continue
            }

            // brought-forward balance line (ICICI): not a transaction, just the starting balance
            if (Regex("""^B/F\b""", RegexOption.IGNORE_CASE).containsMatchIn(rest)) {
                Regex("""-?[\d,]+\.\d{2}""").findAll(rest).lastOrNull()?.let { prev = num(it.value) }
                continue
            }

            // Federal account: ... withdrawal deposit balance CR  (whole rupees are printed without decimals)
            val fed = fedRx.find(rest)
            if (fed != null) {
                val w = num(fed.groupValues[2]); val dep = num(fed.groupValues[3])
                if (w != 0.0 || dep != 0.0) {
                    out.add(StmtLine(date, clean(rest.substring(0, fed.range.first)), if (w != 0.0) w else dep, w == 0.0))
                }
                prev = num(fed.groupValues[4])
                continue
            }

            // plain CSV cells
            val cells = if (rest.count { it == ',' } >= 2 && !rest.contains(Regex("""\d,\d{3}"""))) rest.split(',').map { it.trim().trim('"') } else listOf(rest)
            val nums = cells.mapNotNull { c -> if (c.matches(Regex("""-?\d+(\.\d+)?"""))) c.toDouble() else null }
            if (cells.size > 1 && nums.isNotEmpty()) {
                val desc = cells.filter { !it.matches(Regex("""-?\d+(\.\d+)?""")) && it.isNotBlank() }.joinToString(" ")
                val amount = nums.first { it != 0.0 }
                out.add(StmtLine(date, desc.ifBlank { "Statement entry" }, abs(amount), amount < 0))
                continue
            }

            // card style: marker after or before the amount
            val a = cardRx.find(rest)
            if (a != null && a.groupValues[2].contains('.')) {
                val suffix = a.groupValues[3]; val plus = a.groupValues[1].isNotEmpty()
                val pre = rest.substring(0, a.groups[2]!!.range.first)
                val prefixC = Regex("""(?<![A-Za-z0-9])C\s*$""").containsMatchIn(pre)
                if (suffix.isNotEmpty() || plus || prefixC) {
                    var desc = pre.replace(Regex("""\s*\+?\s*(C|₹|Rs\.?|`)?\s*$"""), "")
                    if (Regex("""\s{2,}\d{1,3}\s*$""").containsMatchIn(desc)) desc = desc.replace(Regex("""\s+\d+\s*$"""), "")
                    out.add(StmtLine(date, clean(desc), num(a.groupValues[2]), plus || suffix.uppercase() in listOf("C", "CR")))
                    continue
                }
            }

            // bank style: amount(s) then balance
            val tail = mutableListOf<String>()
            for (t in rest.split(Regex("\\s+")).reversed()) { if (decimalTok.matches(t) || t == "0") tail.add(t) else break }
            if (tail.size >= 2) {
                val vals = tail.reversed().takeLast(3).map { num(it) }
                val bal = vals.last()
                val tailRx = Regex("(?:\\s+(?:-?\\d[\\d,]*\\.\\d{1,2}|0)){${vals.size}}$")
                val desc = tailRx.find(rest)?.let { rest.substring(0, it.range.first) } ?: rest
                val amount: Double; val credit: Boolean
                if (vals.size == 3) {
                    val x = vals[0]; val y = vals[1]
                    if (creditFirst) { credit = x != 0.0; amount = if (x != 0.0) x else y }
                    else { credit = y != 0.0; amount = if (y != 0.0) y else x }
                } else {
                    amount = vals[0]
                    val p = prev
                    credit = if (p != null) bal > p else Regex("""/CR/|\bCR\b""").containsMatchIn(rest)
                }
                prev = bal
                out.add(StmtLine(date, clean(desc.trim().trimEnd('-').trim()), amount, credit))
                continue
            }

            // simple: description then amount
            val p = plainRx.find(rest)
            if (p != null && p.groupValues[1].contains('.')) {
                out.add(StmtLine(date, clean(rest.substring(0, p.range.first)), num(p.groupValues[1]), rest[p.range.first] == '-'))
            }
        }
        return out.filter { !skipRx.containsMatchIn(it.desc) }
    }

    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").trim().ifBlank { "Statement entry" }

    /** One-to-one matching on amount and date (within [days] days). */
    fun reconcile(lines: List<StmtLine>, txns: List<Txn>, days: Int = 3): Recon {
        if (lines.isEmpty()) return Recon(0, emptyList(), emptyList(), 0)
        val window = days * 86_400_000L
        val free = txns.toMutableList()
        var matched = 0
        val missing = mutableListOf<StmtLine>()
        for (l in lines.sortedBy { it.date }) {
            val hit = free.filter { abs(it.amount - l.amount) < 0.011 && abs(it.txnDate - l.date) <= window && (isIn(it) == l.credit) }
                .minByOrNull { abs(it.txnDate - l.date) }
            if (hit != null) { free.remove(hit); matched++ } else missing.add(l)
        }
        val from = lines.minOf { it.date } - window; val to = lines.maxOf { it.date } + window
        val extra = free.filter { it.txnDate in from..to }
        return Recon(matched, missing, extra, lines.size)
    }

    private fun isIn(t: Txn) = t.kind == "UPI_IN" || t.kind == "REFUND" || t.kind == "PAYMENT"
}
