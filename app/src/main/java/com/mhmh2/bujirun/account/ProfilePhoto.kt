package com.mhmh2.bujirun.account

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The profile photo: picked by the user, kept only on this phone (a small square JPEG in the
 * app's files), never uploaded. [bitmap] is null when there is none.
 */
object ProfilePhoto {
    private const val SIZE = 384
    private val _bitmap = MutableStateFlow<Bitmap?>(null)
    val bitmap: StateFlow<Bitmap?> = _bitmap.asStateFlow()
    private var loaded = false

    private fun file(context: Context) = File(context.filesDir, "profile.jpg")

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        loaded = true
        val f = file(context)
        if (f.exists()) _bitmap.value = BitmapFactory.decodeFile(f.path)
    }

    /** Crops [uri] to a centred square, shrinks it and keeps it. False if it couldn't be read. */
    suspend fun set(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val r = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return@withContext false
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val raw = r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: return@withContext false
        val rotation = runCatching {
            r.openInputStream(uri)?.use { when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            } } ?: 0
        }.getOrDefault(0)
        val side = minOf(raw.width, raw.height)
        val m = Matrix().apply {
            val s = SIZE.toFloat() / side
            postScale(s, s)
            postRotate(rotation.toFloat())
        }
        val square = Bitmap.createBitmap(raw, (raw.width - side) / 2, (raw.height - side) / 2, side, side, m, true)
        file(context).outputStream().use { square.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        _bitmap.value = square
        true
    }

    fun remove(context: Context) {
        file(context).delete()
        _bitmap.value = null
    }
}
