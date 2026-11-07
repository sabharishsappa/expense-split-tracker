package com.sabharish.splittracker

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern

data class Parsed(
    val bank: String,
    val kind: String,        // SPEND | REFUND | PAYMENT
    val amount: Double,
    val card: String,        // last 4 digits only
    val merchant: String,
    val dateMillis: Long?
)

/**
 * Bank SMS parser. Each rule: bank, kind, regex with named groups
 * amt (required), card, merchant, date (optional). %AMT% is replaced by the amount pattern.
 * Rules are verified against a real SMS backup (see data_model_and_parser_spec.md).
 */
object SmsParser {
    private const val AMT = """(?<amt>[\d,]+(?:\.\d+)?)"""

    private class Rule(val bank: String, val kind: String, regex: String) {
        val pattern: Pattern = Pattern.compile(regex.replace("%AMT%", AMT), Pattern.CASE_INSENSITIVE)
    }

    private fun rule(bank: String, kind: String, regex: String) = Rule(bank, kind, regex)

    private val rules: List<Rule> = listOf(
        // ---- spends ----
        rule("SBI","SPEND","""Rs\.?%AMT% spent on your SBI Credit Card ending (?:with )?(?<card>\d{4}) at (?<merchant>.+?) on (?<date>[\d/\-]+)(?:\.| via UPI)"""),
        rule("KOTAK","SPEND","""Rs\.?%AMT% spent via Kotak Debit Card XX(?<card>\d{4}) at (?<merchant>.+?) on (?<date>[\d/]+)\."""),
        rule("HDFC","SPEND","""Spent Rs\.?%AMT% On HDFC Bank Card (?<card>\d{4}) At (?<merchant>.+?) On (?<date>\d{4}-\d{2}-\d{2})"""),
        rule("ICICI","SPEND","""INR %AMT% spent using ICICI Bank Card XX(?<card>\d{4}) on (?<date>[\d\w\-]+) on (?<merchant>.+?)\. Avl"""),
        rule("INDUSIND","SPEND","""INR %AMT% spent on IndusInd Card XX(?<card>\d{4}) on (?<date>[\d\-]+) [\d:]+ [ap]m at (?<merchant>.+?)\. Avl"""),
        rule("YES","SPEND","""INR %AMT% spent on YES BANK Card X(?<card>\d{4}) @(?<merchant>\S+(?: \S+)*?) (?<date>\d{2}-\d{2}-\d{4}) """),
        // ---- refunds ----
        rule("ICICI","REFUND","""(?<merchant>.+?) refund of Rs %AMT% credited to ICICI Bank Credit Card XX(?<card>\d{4}) on (?<date>[\w\-]+)"""),
        rule("INDUSIND","REFUND","""refund of INR %AMT% from (?<merchant>.+?) has been credited to your IndusInd Bank Credit Card XX(?<card>\d{4}) on (?<date>[\w\-]+)"""),
        rule("HDFC","REFUND","""Rs\.? ?%AMT% refunded by (?<merchant>.+?) on (?<date>[\w/]+) & adjusted against HDFC Bank Credit Card (?<card>\d{4})"""),
        // ---- bill payments made by you ----
        rule("SBI","PAYMENT","""received payment of Rs\.?%AMT% via (?:BBPS|UPI)[^.]*\.? ?(?:& )?the same has been credited to your SBI Credit Card"""),
        rule("HDFC","PAYMENT","""PAYMENT OF Rs\. %AMT% RECEIVED TOWARDS YOUR CREDIT CARD ENDING WITH (?<card>\d{4}) ON (?<date>[\d\-]+)\."""),
        rule("ICICI","PAYMENT","""Payment of Rs %AMT% has been received on your ICICI Bank Credit Card XX(?<card>\d{4}).*?on (?<date>[\w\-]+)"""),
        rule("ICICI","PAYMENT","""Payment of INR %AMT% has been received on your ICICI Bank Credit Card Account \w*?(?<card>\d{4}) on (?<date>[\w\-]+)"""),
        rule("INDUSIND","PAYMENT","""thank you for your Payment of INR %AMT% towards your IndusInd Bank Credit Card on (?<date>[\d/]+)"""),
        rule("YES","PAYMENT","""payment of Rs\.?%AMT% is received towards your YES BANK Credit Card ending (?<card>\d{4})"""),
        rule("HDFC","PAYMENT","""Online Payment of Rs\.?%AMT% vide Ref# \w+ was credited to your card ending (?<card>\d{4}) On (?<date>[\w/]+)_"""),
        // ---- UPI / bank account: money sent ----
        rule("SBI","UPI_OUT","""Dear UPI user A/C X(?<card>\d{4}) debited by %AMT% on date (?<date>\d{2}[A-Za-z]{3}\d{2}) trf to (?<merchant>.+?) Refno"""),
        rule("ICICI","UPI_OUT","""ICICI Bank Acct X+(?<card>\d{3,4}) debited for Rs %AMT% on (?<date>[\w\-]+); (?<merchant>.+?) credited\. ?UPI"""),
        rule("ICICI","UPI_OUT","""ICICI Bank Acct X+(?<card>\d{3,4}) debited with INR %AMT% on (?<date>[\w\-]+)\. Acct (?<merchant>X+\d+) credited"""),
        rule("KOTAK","UPI_OUT","""Sent Rs\.?%AMT% from Kotak Bank A(?:C|/c) X(?<card>\d{4}) to (?<merchant>\S+) on (?<date>[\d\-]+)\."""),
        rule("FEDERAL","UPI_OUT","""Rs\.? ?%AMT% sent via UPI on (?<date>[\d\-]+) at [\d:]+ to (?<merchant>.+?)\.Ref"""),
        rule("FEDERAL","UPI_OUT","""Debited Rs\.? ?%AMT% from a/c X(?<card>\d{4}) on (?<date>\d{2}[A-Za-z]{3}\d{2}) [\d:]+ via UPI to (?<merchant>.+?)\. Ref"""),
        rule("FEDERAL","UPI_OUT","""Rs\.? ?%AMT% debited via UPI on (?<date>[\d\-]+) [\d:]+ to VPA (?<merchant>\S+?)\.Ref"""),
        // ---- UPI / bank account: money received ----
        rule("SBI","REFUND","""ur A/c ?X(?<card>\d{4}) credited with Rs\.?%AMT% on (?<date>\d{2}[A-Za-z]{3}\d{2}) against reversal of txn"""),
        rule("SBI","UPI_IN","""A/c ?X(?<card>\d{4})-? ?credited by Rs\.?%AMT% on (?<date>\d{2}[A-Za-z]{3}\d{2}) transfer from (?<merchant>.+?) Ref"""),
        rule("SBI","UPI_IN","""ur A/c ?X(?<card>\d{4}) credited by Rs\.?%AMT% on (?<date>\d{2}[A-Za-z]{3}\d{2}) by \(Ref"""),
        rule("ICICI","UPI_IN","""Acct X+(?<card>\d{3,4}) is credited with Rs %AMT% on (?<date>[\w\-]+) from (?<merchant>.+?)\. UPI"""),
        rule("KOTAK","UPI_IN","""Received Rs\.?%AMT% in your Kotak Bank AC X?(?<card>\d{4}) from (?<merchant>\S+(?: \S+)*?) on (?<date>[\d\-]+)\.\s?UPI""")
    )

    private val dateFormats = listOf(
        "dd/MM/yy", "dd-MM-yy", "dd-MM-yyyy", "dd/MM/yyyy",
        "dd-MMM-yy", "dd-MMM-yyyy", "dd/MMM/yyyy", "yyyy-MM-dd", "ddMMMyy", "ddMMMyyyy", "dd MMM yy", "dd MMM yyyy"
    )

    fun parseDate(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        for (f in dateFormats) {
            try {
                val df = SimpleDateFormat(f, Locale.ENGLISH)
                df.isLenient = false
                val d = df.parse(s.trim())
                if (d != null) return d.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun group(m: java.util.regex.Matcher, name: String): String? =
        try { m.group(name) } catch (_: Exception) { null }

    /** UPI handles (name@bank) become readable names; ALL CAPS names become Title Case. */
    fun cleanName(raw: String): String {
        var n = raw.trim().trimEnd('.', ',')
        if (n.contains("@")) {
            val local = n.substringBefore("@")
            n = when {
                local.startsWith("paytmqr", true) || local.startsWith("paytm-", true) -> "Paytm merchant"
                local.startsWith("q", true) && local.length > 8 && local.drop(1).all { it.isDigit() } -> "UPI merchant"
                Regex("^\\d{10}$").matches(local) -> "UPI $local"
                else -> local
            }
        }
        if (n.contains(" ") && n.none { it.isDigit() } && n == n.uppercase() && n.any { it.isLetter() }) {
            n = n.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
        }
        return n
    }

    fun parse(body: String?, sender: String? = null): Parsed? {
        if (body.isNullOrBlank()) return null
        val b = body.replace(Regex("\\s+"), " ").trim()
        if (GeneralSms.isOtp(b)) return null   // OTP texts repeat amount+merchant
        for (r in rules) {
            val m = r.pattern.matcher(b)
            if (m.find()) {
                val amt = (group(m, "amt") ?: continue).replace(",", "").toDoubleOrNull() ?: continue
                val card = group(m, "card") ?: r.bank
                val rawName = group(m, "merchant")
                val merchant = when {
                    rawName != null -> if (r.kind.startsWith("UPI")) cleanName(rawName) else rawName.trim()
                    r.kind == "PAYMENT" -> "Card bill payment"
                    r.kind == "REFUND" -> "UPI reversal"
                    r.kind == "UPI_IN" -> "Unknown sender"
                    else -> "Unknown"
                }
                return Parsed(r.bank, r.kind, amt, card, merchant, parseDate(group(m, "date")))
            }
        }
        // none of the exact wordings matched: read it the general way, so any bank works
        return GeneralSms.parse(b, sender)
    }
}
