package com.sabharish.splittracker

import org.json.JSONArray
import org.json.JSONObject

/** Everything the app stores, in one object, so it can be saved to / restored from a single file. */
data class BackupData(
    val txns: List<Txn>,
    val shares: List<Share>,
    val settlements: List<Settlement>,
    val rules: List<Rule>,
    val prefs: Map<String, String>,
    val bills: List<Bill> = emptyList(),
    val payables: List<Payable> = emptyList(),
    val flags: Map<String, Boolean> = emptyMap()
)

/** Pure JSON (de)serialisation. No Android classes besides org.json. */
object Backup {
    const val VERSION = 1
    /** Settings worth keeping: UPI id, own-name words, custom tags, theme. */
    val PREF_KEYS = listOf("upi", "selfnames", "tags", "hiddenTags", "theme", "groups")

    /** Only these are left out: they describe this phone, not you. Everything else you set is saved. */
    private val SKIP = setOf("lastBackup", "unparsed", "lock", "backupDir", "lastAuto", "backupPass", "digest")
    fun keep(key: String) = key !in SKIP

    /** What a backup holds, in words, so it can be checked before restoring and after saving. */
    fun describe(text: String): String? {
        val d = fromJson(text) ?: return null
        val people = d.shares.map { it.person }.filter { it != Logic.ME }.distinct().size
        val tags = Logic.parseTags(d.prefs["tags"] ?: "").size
        val parts = mutableListOf(
            "${d.txns.size} transactions", "${d.shares.size} split shares", "$people people",
            "${d.rules.size} remembered choices", "$tags own tags"
        )
        if (d.bills.isNotEmpty()) parts += "${d.bills.size} bills"
        if (d.payables.isNotEmpty()) parts += "${d.payables.size} amounts you owe"
        parts += if ((d.prefs["upi"] ?: "").isNotBlank()) "UPI ID saved" else "no UPI ID"
        return parts.joinToString(" · ")
    }

    fun toJson(d: BackupData, now: Long): String {
        val o = JSONObject()
        o.put("app", "SplitTracker")
        o.put("version", VERSION)
        o.put("savedAt", now)
        o.put("txns", JSONArray().apply {
            d.txns.forEach { t ->
                put(JSONObject().put("id", t.id).put("bank", t.bank).put("card", t.card).put("kind", t.kind)
                    .put("amount", t.amount).put("merchant", t.merchant).put("txnDate", t.txnDate).put("smsTime", t.smsTime)
                    .put("status", t.status).put("splitAdded", t.splitAdded).put("category", t.category)
                    .put("refundOf", t.refundOf).put("note", t.note).put("auto", t.auto).put("body", t.body))
            }
        })
        o.put("shares", JSONArray().apply {
            d.shares.forEach { s ->
                put(JSONObject().put("id", s.id).put("txnId", s.txnId).put("person", s.person)
                    .put("amount", s.amount).put("settled", s.settled))
            }
        })
        o.put("settlements", JSONArray().apply {
            d.settlements.forEach { s ->
                put(JSONObject().put("id", s.id).put("person", s.person).put("amount", s.amount)
                    .put("date", s.date).put("mode", s.mode))
            }
        })
        o.put("rules", JSONArray().apply {
            d.rules.forEach { r -> put(JSONObject().put("key", r.ruleKey).put("action", r.action).put("people", r.people)) }
        })
        o.put("bills", JSONArray().apply {
            d.bills.forEach { b -> put(JSONObject().put("id", b.id).put("bank", b.bank).put("card", b.card).put("total", b.total)
                .put("minDue", b.minDue).put("dueDate", b.dueDate).put("smsTime", b.smsTime).put("paid", b.paid)) }
        })
        o.put("payables", JSONArray().apply {
            d.payables.forEach { p -> put(JSONObject().put("id", p.id).put("person", p.person).put("amount", p.amount)
                .put("paid", p.paid).put("date", p.date).put("note", p.note).put("txnId", p.txnId)) }
        })
        o.put("prefs", JSONObject().apply { d.prefs.forEach { (k, v) -> put(k, v) } })
        o.put("flags", JSONObject().apply { d.flags.forEach { (k, v) -> put(k, v) } })
        return o.toString()
    }

    /** Returns null when the text is not a Split Tracker backup. */
    fun fromJson(text: String): BackupData? = try {
        val o = JSONObject(text)
        if (o.optString("app") != "SplitTracker") null else {
            fun <T> arr(name: String, f: (JSONObject) -> T): List<T> {
                val a = o.optJSONArray(name) ?: JSONArray()
                return (0 until a.length()).map { f(a.getJSONObject(it)) }
            }
            val prefs = mutableMapOf<String, String>()
            o.optJSONObject("prefs")?.let { p -> p.keys().forEach { k -> prefs[k] = p.getString(k) } }
            val flags = mutableMapOf<String, Boolean>()
            o.optJSONObject("flags")?.let { p -> p.keys().forEach { k -> flags[k] = p.getBoolean(k) } }
            BackupData(
                flags = flags,
                txns = arr("txns") { j ->
                    Txn(id = j.getLong("id"), bank = j.getString("bank"), card = j.getString("card"), kind = j.getString("kind"),
                        amount = j.getDouble("amount"), merchant = j.getString("merchant"), txnDate = j.getLong("txnDate"),
                        smsTime = j.getLong("smsTime"), status = j.getString("status"), splitAdded = j.optBoolean("splitAdded"),
                        category = j.optString("category"), refundOf = j.optLong("refundOf"), note = j.optString("note"),
                        auto = j.optBoolean("auto"), body = j.optString("body"))
                },
                shares = arr("shares") { j ->
                    Share(id = j.getLong("id"), txnId = j.getLong("txnId"), person = j.getString("person"),
                        amount = j.getDouble("amount"), settled = j.getDouble("settled"))
                },
                settlements = arr("settlements") { j ->
                    Settlement(id = j.getLong("id"), person = j.getString("person"), amount = j.getDouble("amount"),
                        date = j.getLong("date"), mode = j.getString("mode"))
                },
                rules = arr("rules") { j -> Rule(j.getString("key"), j.getString("action"), j.getString("people")) },
                prefs = prefs,
                payables = arr("payables") { j -> Payable(j.getLong("id"), j.getString("person"), j.getDouble("amount"), j.getDouble("paid"),
                    j.getLong("date"), j.optString("note"), j.optLong("txnId")) },
                bills = arr("bills") { j -> Bill(j.getLong("id"), j.getString("bank"), j.getString("card"), j.getDouble("total"),
                    j.getDouble("minDue"), j.getLong("dueDate"), j.getLong("smsTime"), j.optBoolean("paid")) }
            )
        }
    } catch (e: Exception) { null }

    fun savedAt(text: String): Long = try { JSONObject(text).optLong("savedAt") } catch (e: Exception) { 0L }
}
