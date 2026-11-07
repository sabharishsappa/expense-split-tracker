package com.sabharish.splittracker

import kotlin.math.abs

/**
 * Payments seen in notifications: UPI apps, bank apps, the messages app (when SMS itself cannot be read)
 * and mail apps (the preview of a bank email). Only these apps are looked at; everything else is ignored.
 */
object NotificationReader {
    const val PREFIX = "[Notification"

    enum class Type { UPI, MESSAGES, MAIL, BANK }
    class Source(val type: Type, val label: String, val bank: String? = null)
    class Read(val parsed: Parsed?, val bill: ParsedBill?, val balance: BalanceHit?, val body: String)

    private val sources: Map<String, Source> = buildMap {
        put("com.google.android.apps.nbu.paisa.user", Source(Type.UPI, "Google Pay"))
        put("com.phonepe.app", Source(Type.UPI, "PhonePe"))
        put("net.one97.paytm", Source(Type.UPI, "Paytm"))
        put("in.org.npci.upiapp", Source(Type.UPI, "BHIM"))
        put("com.dreamplug.androidapp", Source(Type.UPI, "CRED"))
        put("com.mobikwik_new", Source(Type.UPI, "MobiKwik"))
        put("com.freecharge.android", Source(Type.UPI, "Freecharge"))
        listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms", "com.oneplus.mms", "com.miui.mms", "com.coloros.mms")
            .forEach { put(it, Source(Type.MESSAGES, "Messages")) }
        put("com.google.android.gm", Source(Type.MAIL, "Gmail"))
        put("com.microsoft.office.outlook", Source(Type.MAIL, "Outlook"))
        put("com.yahoo.mobile.client.android.mail", Source(Type.MAIL, "Yahoo Mail"))
        put("ch.protonmail.android", Source(Type.MAIL, "Proton Mail"))
        put("com.snapwork.hdfc", Source(Type.BANK, "HDFC app", "HDFC"))
        put("com.csam.icici.bank.imobile", Source(Type.BANK, "iMobile", "ICICI"))
        put("com.sbi.lotusintouch", Source(Type.BANK, "YONO", "SBI"))
        put("com.sbi.SBIFreedomPlus", Source(Type.BANK, "YONO", "SBI"))
        put("com.msf.kbank.mobile", Source(Type.BANK, "Kotak app", "KOTAK"))
        put("com.axis.mobile", Source(Type.BANK, "Axis app", "AXIS"))
    }

    fun isWatched(pkg: String) = pkg in sources

    private const val AMT = """(?:₹|rs\.?|inr)\s?(?<amt>[\d,]+(?:\.\d{1,2})?)"""
    private const val END = """(?=\s+(?:on|via|using|from|ref|upi|successful|successfully|at|for)\b|[.,!(]|$)"""
    private val I = RegexOption.IGNORE_CASE
    private val noise = Regex("""\b(request(?:ed|s)?|reminder|pending|failed|declined|unsuccessful|cashback|reward|scratch|offer|expires?|due|will be|collect|autopay)\b""", I)
    private val outRules = listOf(
        Regex("""\b(?:paid|sent|transferred|payment of)\s+$AMT\s+to\s+(?<name>.{2,40}?)$END""", I),
        Regex("""$AMT\s+(?:paid|sent|debited)\s+to\s+(?<name>.{2,40}?)$END""", I)
    )
    private val inRules = listOf(
        Regex("""\b(?:received|credited)\s+$AMT\s+from\s+(?<name>.{2,40}?)$END""", I),
        Regex("""$AMT\s+(?:received|credited)\s+from\s+(?<name>.{2,40}?)$END""", I),
        Regex("""(?<name>[A-Za-z][A-Za-z '&-]{1,38}?)\s+(?:has\s+)?(?:paid|sent)\s+you\s+$AMT""", I)
    )

    /** "Paid ₹500 to Swiggy" / "Ravi paid you ₹300" in a UPI app's notification. */
    fun parseUpiApp(text: String, label: String): Parsed? {
        val t = text.replace(Regex("\\s+"), " ").trim()
        if (noise.containsMatchIn(t)) return null
        for ((rules, kind) in listOf(outRules to "UPI_OUT", inRules to "UPI_IN")) {
            for (r in rules) {
                val m = r.find(t) ?: continue
                val amt = m.groups["amt"]?.value?.replace(",", "")?.toDoubleOrNull() ?: continue
                val name = m.groups["name"]?.value?.trim()?.trim('"', '\'') ?: continue
                if (amt <= 0 || name.isBlank()) continue
                return Parsed("UPI", kind, amt, label, SmsParser.cleanName(name), null)
            }
        }
        return null
    }

    fun read(pkg: String, title: String, text: String): Read? {
        val src = sources[pkg] ?: return null
        val combined = listOf(title, text).filter { it.isNotBlank() }.joinToString(". ")
        if (combined.isBlank()) return null
        val body = "$PREFIX · ${src.label}] $combined"
        return when (src.type) {
            Type.UPI -> parseUpiApp(combined, src.label)?.let { Read(it, null, null, body) }
            Type.MESSAGES -> {
                val bill = BillParser.parse(text)
                val p = if (bill == null) SmsParser.parse(text, title) else null
                val bal = BalanceSms.parse(text, title)
                if (bill == null && p == null && bal == null) null else Read(p, bill, bal, body)
            }
            Type.MAIL -> if (Banks.fromText(combined) == null) null else SmsParser.parse(combined, null)?.let { Read(it, null, null, body) }
            Type.BANK -> SmsParser.parse(combined, null)?.let { p -> Read(if (p.bank == "BANK") p.copy(bank = src.bank ?: p.bank) else p, null, null, body) }
        }
    }
}

/**
 * One payment often arrives several ways: the bank's SMS, a UPI app's notification, the bank app, an email.
 * This keeps a single entry. An SMS is the most complete (it carries the account), so it takes over an entry
 * that began as a notification; the person's tags stay.
 */
object CrossSource {
    const val WINDOW_MS = 10 * 60_000L
    const val REPEAT_MS = 2 * 60_000L

    sealed class Decision {
        object Insert : Decision()
        object Drop : Decision()
        class Upgrade(val twin: Txn, val merchant: String) : Decision()
    }

    private fun isNotif(t: Txn) = t.body.startsWith(NotificationReader.PREFIX)

    /** [near]: entries of the same kind and amount within [WINDOW_MS] of [at]. */
    fun decide(near: List<Txn>, p: Parsed, body: String, at: Long): Decision {
        if (!body.startsWith(NotificationReader.PREFIX)) {
            val twin = near.firstOrNull { isNotif(it) } ?: return Decision.Insert
            return Decision.Upgrade(twin, if (twin.merchant.isBlank() || twin.merchant == "Unknown") p.merchant else twin.merchant)
        }
        if (near.any { !isNotif(it) }) return Decision.Drop
        val twin = near.firstOrNull { abs(it.smsTime - at) <= REPEAT_MS } ?: return Decision.Insert
        return if (twin.bank == "UPI" && p.bank != "UPI") Decision.Upgrade(twin, twin.merchant) else Decision.Drop
    }
}
