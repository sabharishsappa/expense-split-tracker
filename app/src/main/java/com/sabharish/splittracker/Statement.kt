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
        return if (s.isCard) txns.filter { it.bank == s.bank && it.card == s.last && (it.kind == "SPEND" || it.kind == "REFUND") && it.status != "REVERSED" }
        else txns.filter { Accounts.accountOf(it, det) == Accounts.keyOf(s.bank, s.last) }
    }

    private val dateRx = Regex("""^\s*(\d{1,2}[/\-. ](?:\d{1,2}|[A-Za-z]{3})[/\-. ]\d{2,4})\b""")
    private val amtRx = Regex("""(?<![\w.])-?(?:₹|Rs\.?|INR)?\s?(\d{1,3}(?:,\d{2,3})*(?:\.\d{1,2})|\d+\.\d{1,2})\s*(Cr|CR|cr|Dr|DR|dr)?\.?\s*$""")
    private val formats = listOf("dd/MM/yyyy", "dd-MM-yyyy", "dd.MM.yyyy", "dd/MM/yy", "dd-MM-yy", "dd MMM yyyy", "dd-MMM-yyyy", "dd/MMM/yyyy", "dd-MMM-yy", "dd MMM yy")

    private fun parseDate(s: String): Long? {
        for (f in formats) try {
            val df = SimpleDateFormat(f, Locale.ENGLISH); df.isLenient = false
            return df.parse(s.trim().replace(Regex("\\s+"), " "))?.time
        } catch (_: Exception) {}
        return null
    }

    /**
     * Reads plain statement text (from a CSV or from text pulled out of a PDF): any line that starts with a date and ends
     * with an amount. "Cr" or a minus sign marks money coming in; everything else is money going out.
     * Closing balance columns are skipped when a line has both an amount and a balance.
     */
    fun parse(text: String): List<StmtLine> {
        val out = mutableListOf<StmtLine>()
        for (raw in text.lines()) {
            val line = raw.replace(',', ',').trim().trim('"')
            val d = dateRx.find(line) ?: continue
            val date = parseDate(d.groupValues[1]) ?: continue
            var rest = line.substring(d.range.last + 1).trim()
            // csv: split on commas outside quotes into cells, keep numeric cells
            val cells = if (rest.count { it == ',' } >= 2 && !rest.contains(Regex("""\d,\d{3}"""))) rest.split(',').map { it.trim().trim('"') } else listOf(rest)
            val nums = cells.mapNotNull { c -> if (c.matches(Regex("""-?\d+(\.\d+)?"""))) c.toDouble() else null }
            if (cells.size > 1 && nums.isNotEmpty()) {
                val desc = cells.filter { !it.matches(Regex("""-?\d+(\.\d+)?""")) && it.isNotBlank() }.joinToString(" ")
                val amount = nums.first { it != 0.0 }
                out.add(StmtLine(date, desc.ifBlank { "Statement entry" }, abs(amount), amount < 0))
                continue
            }
            val m = amtRx.find(rest) ?: continue
            val amt = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: continue
            val credit = m.groupValues[2].equals("Cr", true) || m.value.trimStart().startsWith("-")
            rest = rest.substring(0, m.range.first).trim()
            out.add(StmtLine(date, rest.replace(Regex("\\s+"), " ").ifBlank { "Statement entry" }, amt, credit))
        }
        return out
    }

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

    private fun isIn(t: Txn) = t.kind == "UPI_IN" || t.kind == "REFUND"
}
