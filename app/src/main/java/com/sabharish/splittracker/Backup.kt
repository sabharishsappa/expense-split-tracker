package com.sabharish.splittracker

import org.json.JSONArray
import org.json.JSONObject

/** Everything the app stores, in one object, so it can be saved to / restored from a single file. */
data class BackupData(
    val txns: List<Txn>,
    val shares: List<Share>,
    val settlements: List<Settlement>,
    val rules: List<Rule>,
    val prefs: Map<String, String>
)

/** Pure JSON (de)serialisation. No Android classes besides org.json. */
object Backup {
    const val VERSION = 1
    /** Settings worth keeping: UPI id, own-name words, custom tags, theme. */
    val PREF_KEYS = listOf("upi", "selfnames", "tags", "theme")

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
                    .put("refundOf", t.refundOf).put("note", t.note).put("auto", t.auto))
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
        o.put("prefs", JSONObject().apply { d.prefs.forEach { (k, v) -> put(k, v) } })
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
            BackupData(
                txns = arr("txns") { j ->
                    Txn(id = j.getLong("id"), bank = j.getString("bank"), card = j.getString("card"), kind = j.getString("kind"),
                        amount = j.getDouble("amount"), merchant = j.getString("merchant"), txnDate = j.getLong("txnDate"),
                        smsTime = j.getLong("smsTime"), status = j.getString("status"), splitAdded = j.optBoolean("splitAdded"),
                        category = j.optString("category"), refundOf = j.optLong("refundOf"), note = j.optString("note"),
                        auto = j.optBoolean("auto"))
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
                prefs = prefs
            )
        }
    } catch (e: Exception) { null }

    fun savedAt(text: String): Long = try { JSONObject(text).optLong("savedAt") } catch (e: Exception) { 0L }
}
