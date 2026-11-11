package com.sabharish.splittracker

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A daily backup file written into a folder you choose on this phone (or a cloud folder your phone already syncs, if you pick one).
 * The app itself never connects anywhere. The new file is written completely before the old one is replaced.
 */
object AutoBackup {
    const val DIR = "backupDir"
    private const val LAST = "lastAuto"
    private const val NAME = "PaisaLog-auto-backup.json"
    private const val DAY = 86_400_000L

    fun folder(ctx: Context): String? = Repo.getPref(ctx, DIR, "").ifEmpty { null }
    fun due(last: Long, now: Long) = now - last > DAY - 4 * 3_600_000L

    /** Returns true when a new file was written. */
    suspend fun run(ctx: Context, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val dir = folder(ctx) ?: return@withContext false
        val now = System.currentTimeMillis()
        if (!force && !due(Repo.getPref(ctx, LAST, "0").toLongOrNull() ?: 0L, now)) return@withContext false
        try {
            val tree = Uri.parse(dir)
            val resolver = ctx.contentResolver
            val docId = DocumentsContract.getTreeDocumentId(tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            val text = Repo.sealed(ctx, Repo.exportBackup(ctx))
            val temp = DocumentsContract.createDocument(resolver, parent, "application/json", "PaisaLog-auto-backup-new.json") ?: return@withContext false
            resolver.openOutputStream(temp, "wt")?.use { it.write(text.toByteArray()) } ?: return@withContext false
            // now that the new copy is safely written, replace the old one
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) if (c.getString(1) == NAME) DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)))
            }
            DocumentsContract.renameDocument(resolver, temp, NAME)
            Repo.putPref(ctx, LAST, now.toString())
            true
        } catch (e: Exception) { CrashLog.record(ctx, e); false }
    }
}
