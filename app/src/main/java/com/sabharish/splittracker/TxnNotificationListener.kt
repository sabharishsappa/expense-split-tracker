package com.sabharish.splittracker

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Sees payment notifications from the few apps listed in [NotificationReader]. Everything stays on the phone. */
class TxnNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName || !NotificationReader.isWatched(sbn.packageName)) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val app = applicationContext
        val pkg = sbn.packageName
        val at = sbn.postTime
        CoroutineScope(Dispatchers.IO).launch {
            try { Repo.ingestNotification(app, pkg, title, text, at) } catch (e: Throwable) { CrashLog.record(app, e) }
        }
    }
}
