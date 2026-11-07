package com.sabharish.splittracker

import java.util.Locale

/**
 * Reads bank messages that the exact-wording rules in [SmsParser] do not know, so any Indian bank works:
 * the bank comes from the sender id, then it finds the amount (skipping balance and limit figures), whether
 * money went out or came in, the account or card digits, and who it was paid to or received from.
 * Anything that looks like an OTP, reminder, request, failure or offer is left alone.
 */
object GeneralSms {
    private val skip = Regex(
        """\b(will be (?:auto[- ]?)?debited|to be debited|is scheduled|due|statement|offers?|pre-?approved|apply|click|win|reward|voucher|""" +
            """requested|requests?|collect|declined|failed|failure|unsuccessful|not (?:been )?(?:debited|processed|successful)|could not|usd|eur|gbp|aed|sgd)\b|https?:|www\.|bit\.ly""",
        RegexOption.IGNORE_CASE
    )
    private val shareWarning = Regex("""(?:never|not|don'?t|do not)\s+(?:to\s+)?share[^.]*?\b(?:otp|pin|cvv)\b[^.]*""", RegexOption.IGNORE_CASE)
    private val otpText = Regex("""\b\d{4,8}\b[^.]{0,40}\botp\b|\botp\s*(?:is|for|:|-|to)\b|\buse\s+otp\b|\botp\s+\d{4,8}|\b(?:your|the)\s+(?:otp|one[- ]time password)\b|\bone[- ]time password\b""", RegexOption.IGNORE_CASE)

    /** A real one-time-password message. The warning "never share OTP/PIN" at the end of genuine alerts does not count. */
    fun isOtp(text: String): Boolean = otpText.containsMatchIn(shareWarning.replace(text, " "))

    private val currency = Regex("""(?:rs\.?|inr|₹)\s*:?\s*(\d[\d,]*(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
    private val balanceBefore = Regex("""(?:bal(?:ance)?|avl|avail(?:able)?|limit|outstanding|total|min(?:imum)?)\W*(?:\w+\W+){0,2}$""", RegexOption.IGNORE_CASE)
    private val bareAmount = Regex("""\b(?:debited|credited|dr|cr)\.?\s*(?:by|for|with|of)?\s*(?:an amount of)?\s*(\d[\d,]*\.\d{1,2}|\d[\d,]{2,})""", RegexOption.IGNORE_CASE)

    private val debitWord = Regex("""\b(debited|debit|spent|withdrawn|withdrawal|purchase|paid|sent|deducted|dr)\b""", RegexOption.IGNORE_CASE)
    private val creditWord = Regex("""\b(credited|credit|received|deposited|refunded|refund|reversal|reversed|cr)\b""", RegexOption.IGNORE_CASE)
    private val cardKind = Regex("""\b(?:credit|debit) cards?\b""", RegexOption.IGNORE_CASE)

    private val cardNo = Regex("""\bcards?\s*(?:no\.?|number|ending(?: with| in)?)?\s*[:\-]?\s*[x*•]*\s*(\d{4})\b""", RegexOption.IGNORE_CASE)
    private val acctNo = Regex("""\b(?:a/?c|acct|account|ac)\s*(?:no\.?|number)?\s*[:\-]?\s*(?:ending(?: with| in)?)?\s*[:\-]?\s*[x*•.#]*\s*(\d{3,})\b""", RegexOption.IGNORE_CASE)
    private val spentWord = Regex("""\b(spent|purchase|used|swiped|pos|txn|transaction)\b""", RegexOption.IGNORE_CASE)
    private val upiHint = Regex("""\b(upi|vpa|imps|neft|rtgs|ref(?:erence)?\.? ?no|utr)\b""", RegexOption.IGNORE_CASE)

    private val stop = Regex("""\s+(?:on|ref|refno|upi|txn|avl|bal|dt|date|via|using|from|if|not|call|sms|never|for|credited|debited)\b|[.;,(]|\s-\s|\s\d{5,}""", RegexOption.IGNORE_CASE)
    private val dateLike = Regex("""\d{1,2}[-/]\d{1,2}[-/]\d{2,4}|\d{4}-\d{2}-\d{2}|\d{1,2}[- ]?[A-Za-z]{3}[- ]?\d{2,4}""")

    private fun after(text: String, marker: Regex): String? {
        for (m in marker.findAll(text)) {
            val rest = text.substring(m.range.last + 1)
            val end = stop.find(rest)?.range?.first ?: rest.length
            val v = rest.substring(0, end).trim().trim('"', '\'', ':', '-')
            if (v.length in 2..40 && !Regex("""^(?:your|a/?c|acct|account|beneficiary|the|you|bank|x{2,}\d|rs\b|inr\b|₹|\d)""", RegexOption.IGNORE_CASE).containsMatchIn(v)) return v
        }
        return null
    }

    private val upiInfo = Regex("""upi[/-](?:p2[am][/-])?(?:dr[/-]|cr[/-])?\d{6,}[/-]([A-Za-z0-9 &.'_@-]+?)(?=/|\.(?:\s|$)|\s+(?:not|sms|if|call|avl|bal|never)\b|$)""", RegexOption.IGNORE_CASE)
    private val semiCredited = Regex(""";\s*([^;.]{2,40}?)\s+credited""", RegexOption.IGNORE_CASE)
    private val afterTime = Regex("""(?:\bIST\b|\d{2}:\d{2}(?::\d{2})?(?:\s*[ap]m)?(?!\s*IST))\s+([A-Za-z][A-Za-z0-9 &.'*\-]{2,30}?)\s+(?:avl|available|limit|bal)\b""", RegexOption.IGNORE_CASE)

    private val toMarker = Regex("""\b(?:trf to|transfer to|paid to|sent to|to vpa|to)\b""", RegexOption.IGNORE_CASE)
    private val crToMarker = Regex("""\bcr\.?\s*to\b""", RegexOption.IGNORE_CASE)
    private val fromMarker = Regex("""\b(?:from vpa|transfer from|from)\b""", RegexOption.IGNORE_CASE)
    private val byMarker = Regex("""\bby\b""", RegexOption.IGNORE_CASE)
    private val atMarker = Regex("""\b(?:at|towards|info:)\b""", RegexOption.IGNORE_CASE)

    private fun amountOf(b: String): Double? {
        for (m in currency.findAll(b)) {
            val before = b.substring(maxOf(0, m.range.first - 22), m.range.first)
            if (balanceBefore.containsMatchIn(before)) continue
            return m.groupValues[1].replace(",", "").toDoubleOrNull() ?: continue
        }
        val m = bareAmount.find(b) ?: return null
        return m.groupValues[1].replace(",", "").toDoubleOrNull()
    }

    fun parse(body: String?, sender: String?): Parsed? {
        if (body.isNullOrBlank()) return null
        val b = body.replace(Regex("\\s+"), " ").trim()
        if (isOtp(b) || skip.containsMatchIn(b)) return null

        val amount = amountOf(b)?.takeIf { it > 0 } ?: return null
        val dirText = cardKind.replace(b, "ccard")
        val d = debitWord.find(dirText); val c = creditWord.find(dirText)
        if (d == null && c == null) return null
        val debit = d != null && (c == null || d.range.first < c.range.first)
        val refund = Regex("""\b(refund(?:ed)?|reversal|reversed)\b""", RegexOption.IGNORE_CASE).containsMatchIn(b)

        val card = cardNo.find(b)?.groupValues?.get(1)
        val acctDigits = acctNo.find(b)?.groupValues?.get(1)?.takeLast(4)
        val creditCardMention = Regex("""\bcredit cards?\b""", RegexOption.IGNORE_CASE).containsMatchIn(b)
        val isCard = card != null && (acctDigits == null || spentWord.containsMatchIn(b) || creditCardMention)
        val hasUpi = upiHint.containsMatchIn(b)
        if (!isCard && acctDigits == null && !hasUpi) return null   // nothing says this is about money in a bank account or card

        val bank = Banks.detect(b, sender) ?: "BANK"
        val last = if (isCard) card!! else acctDigits ?: bank
        val date = dateLike.findAll(b).firstNotNullOfOrNull { SmsParser.parseDate(it.value) }

        fun merchant(vararg finders: () -> String?): String? = finders.firstNotNullOfOrNull { it() }
        return when {
            isCard && debit -> {
                val m = merchant({ after(b, atMarker) }, { afterTime.find(b)?.groupValues?.get(1)?.trim() }, { after(b, toMarker) })
                Parsed(bank, "SPEND", amount, last, m ?: "Unknown", date)
            }
            isCard && refund -> Parsed(bank, "REFUND", amount, last, merchant({ after(b, fromMarker) }, { after(b, atMarker) }) ?: "Refund", date)
            isCard -> Parsed(bank, "PAYMENT", amount, last, "Card bill payment", date)
            debit -> {
                val atm = Regex("""\b(atm|cash withdrawal|cash wdl)\b""", RegexOption.IGNORE_CASE).containsMatchIn(b)
                val m = if (atm) "ATM withdrawal" else merchant(
                    { upiInfo.find(b)?.groupValues?.get(1)?.trim() }, { after(b, crToMarker) }, { semiCredited.find(b)?.groupValues?.get(1)?.trim() },
                    { after(b, toMarker) }, { after(b, atMarker) }
                ) ?: "Unknown"
                Parsed(bank, "UPI_OUT", amount, last, SmsParser.cleanName(m), date)
            }
            refund -> Parsed(bank, "REFUND", amount, last, "UPI reversal", date)
            else -> {
                val m = merchant({ upiInfo.find(b)?.groupValues?.get(1)?.trim() }, { after(b, fromMarker) }, { after(b, byMarker) }) ?: "Unknown sender"
                Parsed(bank, "UPI_IN", amount, last, SmsParser.cleanName(m), date)
            }
        }
    }
}
