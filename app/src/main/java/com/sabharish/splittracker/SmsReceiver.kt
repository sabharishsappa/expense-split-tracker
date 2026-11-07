package com.sabharish.splittracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    private fun quietOld(at: Long) = System.currentTimeMillis() - at > 2 * 86_400_000L   // old messages delivered late never ping

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (msgs.isEmpty()) return
        // Long SMS arrive in parts: join the parts of each sender
        val bodies = msgs.groupBy { it.originatingAddress ?: "" }
            .map { (from, parts) -> Triple(parts.joinToString("") { it.messageBody ?: "" }, parts.first().timestampMillis, from) }
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                for ((body, time, from) in bodies) {
                    val at = if (time > 0) time else System.currentTimeMillis()
                    val bill = BillParser.parse(body)
                    if (bill != null) { Repo.ingestBill(app, bill, at); continue }
                    val p = SmsParser.parse(body, from)
                    if (p != null) { val t = Repo.ingest(app, p, at, body); if (t != null && !quietOld(at)) Repo.notifyNew(app, t) }
                    val bal = BalanceSms.parse(body, from)
                    if (bal != null) Repo.ingestBalance(app, bal, at)
                    else if (p == null && Diagnostics.looksFinancial(body)) Repo.noteUnparsed(app, body)
                }
            } catch (e: Throwable) {
                CrashLog.record(app, e)
            } finally {
                pending.finish()
            }
        }
    }
}
