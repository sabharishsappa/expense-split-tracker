package com.sabharish.splittracker

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Turns a picked file (CSV, text or PDF) into plain text. PDFs may need the password the bank gave you. */
object StatementReader {
    class NeedsPassword : Exception()

    suspend fun readText(ctx: Context, uri: Uri, password: String): String = withContext(Dispatchers.IO) {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext ""
        val isPdf = bytes.size > 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "%PDF"
        if (!isPdf) return@withContext String(bytes, Charsets.UTF_8)
        PDFBoxResourceLoader.init(ctx.applicationContext)
        val doc = try {
            PDDocument.load(bytes.inputStream(), password)
        } catch (e: Exception) {
            throw NeedsPassword()
        }
        doc.use { d ->
            val s = PDFTextStripper()
            s.sortByPosition = true
            s.getText(d)
        }
    }
}
