package com.sabharish.splittracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (msgs.isEmpty()) return
        // Long SMS arrive in parts: join the parts of each sender
        val bodies = msgs.groupBy { it.originatingAddress ?: "" }
            .map { (_, parts) -> parts.joinToString("") { it.messageBody ?: "" } to parts.first().timestampMillis }
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                for ((body, time) in bodies) {
                    val at = if (time > 0) time else System.currentTimeMillis()
                    val bill = BillParser.parse(body)
                    if (bill != null) { Repo.ingestBill(app, bill, at); continue }
                    val p = SmsParser.parse(body) ?: continue
                    val t = Repo.ingest(app, p, at, body)
                    if (t != null) Repo.notifyNew(app, t)
                }
            } catch (e: Throwable) {
                CrashLog.record(app, e)
            } finally {
                pending.finish()
            }
        }
    }
}
