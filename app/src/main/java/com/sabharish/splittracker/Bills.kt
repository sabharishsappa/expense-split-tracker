package com.sabharish.splittracker

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern

data class ParsedBill(val bank: String, val card: String, val total: Double, val minDue: Double, val due: Long)

/** Credit-card statement SMS ("Total due … pay by …") → a bill you need to pay. Pure, unit-tested. */
object BillParser {
    private const val A = """(?<amt>[\d,]+(?:\.\d+)?)"""
    private class R(val bank: String, regex: String) {
        val p: Pattern = Pattern.compile(regex.replace("%A%", A).replace("%M%", A.replace("amt", "min")), Pattern.CASE_INSENSITIVE)
    }
    private val rules = listOf(
        R("SBI", """E-statement of SBI Credit Card ending X*(?<card>\d{2,4}) .*?Total Amt Due Rs\.? ?%A%; Min Amt Due Rs\.? ?%M%; Payable by (?<due>[\d/]+)"""),
        R("ICICI", """Credit Card X+(?<card>\d{3,4}) Statement is sent .*?Total of Rs\.? ?%A% or minimum of Rs\.? ?%M% is due by (?<due>[\w\-]+)"""),
        R("ICICI", """Pay Total (?:Amount )?Due of Rs\.? ?%A% or Minimum (?:Amount )?Due (?:of )?Rs\.? ?%M% by (?<due>[\w\-]+) (?:for|towards) ICICI Bank Credit Card X+(?<card>\d{3,4})"""),
        R("INDUSIND", """Total Amount Due on your IndusInd Bank Credit Card X+(?<card>\d{4}) is INR %A% and Minimum Amount Due is INR %M%, payable by (?<due>[\d/]+)"""),
        R("HDFC", """HDFC Bank Credit Card X+(?<card>\d{4}) Statement: Total due: Rs\.? ?%A% Min\.? ?due: Rs\.? ?%M% Pay by (?<due>\d{2}-\d{2}-\d{4})"""),
        R("YES", """YES BANK Credit Card X+(?<card>\d{4}) \w{3}-\d{2} statement: Total due INR %A% Min due INR %M% Due by (?<due>[\w\-]+)"""),
        R("CRED", """credit card bill for (?<bankname>[A-Za-z ]+?) X+-?(?<card>\d{4}) has been generated\. Total amount: INR %A% Due date: (?<due>[A-Za-z]+ \d{1,2}, \d{4})""")
    )
    private val formats = listOf("dd/MM/yyyy", "dd-MM-yyyy", "dd-MMM-yy", "dd-MMM-yyyy", "dd/MM/yy", "dd-MM-yy", "MMMM dd, yyyy")

    fun parseDue(s: String): Long? {
        for (f in formats) try {
            val df = SimpleDateFormat(f, Locale.ENGLISH); df.isLenient = false
            val t = s.trim(); val pos = java.text.ParsePosition(0)
            val d = df.parse(t, pos)
            if (d != null && pos.index == t.length && java.util.Calendar.getInstance().also { it.time = d }.get(java.util.Calendar.YEAR) >= 1990) return d.time
        } catch (_: Exception) {}
        return null
    }

    private fun g(m: java.util.regex.Matcher, n: String): String? = try { m.group(n) } catch (_: Exception) { null }

    fun parse(body: String?): ParsedBill? {
        if (body.isNullOrBlank()) return null
        val b = body.replace(Regex("\\s+"), " ").trim()
        for (r in rules) {
            val m = r.p.matcher(b)
            if (!m.find()) continue
            val total = g(m, "amt")?.replace(",", "")?.toDoubleOrNull() ?: continue
            val min = g(m, "min")?.replace(",", "")?.toDoubleOrNull() ?: 0.0
            val due = parseDue(g(m, "due") ?: continue) ?: continue
            if (total <= 0.0) continue
            val bank = if (r.bank == "CRED") bankOf(g(m, "bankname") ?: "") else r.bank
            return ParsedBill(bank, g(m, "card") ?: continue, total, min, due)
        }
        return null
    }

    fun bankOf(name: String): String {
        val n = name.uppercase()
        return listOf("ICICI", "SBI", "HDFC", "INDUSIND", "YES", "KOTAK", "AXIS").firstOrNull { n.contains(it) } ?: n.trim()
    }
}

@androidx.room.Entity(tableName = "bill")
data class Bill(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bank: String, val card: String, val total: Double, val minDue: Double,
    val dueDate: Long, val smsTime: Long, val paid: Boolean = false
)

object Bills {
    const val DAY = 86_400_000L

    /** SBI statements show 2 digits, others 4 — two cards match when one's digits end the other's. */
    fun sameCard(a: String, b: String): Boolean {
        val x = a.filter { it.isDigit() }; val y = b.filter { it.isDigit() }
        if (x.length < 2 || y.length < 2) return true       // unknown card: match on bank alone
        return x.endsWith(y) || y.endsWith(x)
    }

    /** Whole days from today to the due date (negative once overdue). */
    fun daysLeft(due: Long, now: Long): Int {
        val cal = java.util.Calendar.getInstance()
        fun day(t: Long): Long { cal.timeInMillis = t; cal.set(java.util.Calendar.HOUR_OF_DAY, 12); cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0); return cal.timeInMillis }
        return Math.round((day(due) - day(now)).toDouble() / DAY).toInt()
    }

    /** OVERDUE | SOON (≤3 days) | LATER */
    fun urgency(days: Int) = when { days < 0 -> "OVERDUE"; days <= 3 -> "SOON"; else -> "LATER" }

    fun dueLine(days: Int) = when {
        days < -1 -> "${-days} days overdue"
        days == -1 -> "1 day overdue"
        days == 0 -> "due today"
        days == 1 -> "due tomorrow"
        else -> "$days days left"
    }

    /** Is this a statement we already hold (same card, same due date)? */
    fun isSame(a: Bill, p: ParsedBill) = a.bank == p.bank && sameCard(a.card, p.card) && daysLeft(a.dueDate, p.due) == 0

    /** The unpaid bill a card-payment SMS settles: same bank and card, after the statement, covering at least the minimum. */
    fun billPaidBy(bills: List<Bill>, bank: String, card: String, amount: Double, payTime: Long): Bill? =
        bills.filter { !it.paid && it.bank == bank && sameCard(it.card, card) && payTime >= it.smsTime - DAY && amount + 0.5 >= it.minDue }
            .minByOrNull { it.dueDate }

    /** A newer statement for the same card already includes any unpaid older one, so the old ones are done. */
    fun superseded(bills: List<Bill>, p: ParsedBill): List<Bill> =
        bills.filter { !it.paid && it.bank == p.bank && sameCard(it.card, p.card) && it.dueDate < p.due - DAY / 2 }

    /** Start-date choices offered when someone first opens the app. */
    fun trackFromOptions(now: Long): List<Pair<String, Long>> {
        val c = java.util.Calendar.getInstance(); c.timeInMillis = now
        c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0); c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        val today = c.timeInMillis
        c.set(java.util.Calendar.DAY_OF_MONTH, 1)
        val monthStart = c.timeInMillis
        return listOf("Last 7 days" to today - 7 * DAY, "This month" to monthStart, "Last 30 days" to today - 30 * DAY, "Last 90 days" to today - 90 * DAY)
            .distinctBy { it.second }
    }
}
