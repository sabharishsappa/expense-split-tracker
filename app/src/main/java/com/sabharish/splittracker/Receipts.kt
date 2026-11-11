package com.sabharish.splittracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/** One receipt photo per transaction, kept inside the app's private storage. Not part of backups (photos are large). */
object Receipts {
    private fun dir(ctx: Context) = File(ctx.filesDir, "receipts").also { it.mkdirs() }
    fun file(ctx: Context, id: Long) = File(dir(ctx), "$id.jpg")
    fun has(ctx: Context, id: Long) = file(ctx, id).exists()
    fun delete(ctx: Context, id: Long) { file(ctx, id).delete() }

    /** Copies the picked image in, shrunk to at most 1600 px on its long side. */
    fun save(ctx: Context, id: Long, uri: Uri): Boolean = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3200) sample *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        if (bmp == null) false else {
            val scale = 1600f / maxOf(bmp.width, bmp.height)
            val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            file(ctx, id).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            true
        }
    } catch (e: Exception) { CrashLog.record(ctx, e); false }

    fun load(ctx: Context, id: Long): Bitmap? = file(ctx, id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
}
