package com.mhmh2.bujirun.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A photo for a workout: found on the phone by when it was taken, or added from the picker. */
data class WorkoutPhoto(val uri: Uri, val takenAt: Long?, val addedId: Long? = null)

/**
 * The photos taken during a workout. Those on the phone (camera, downloads, and what Google
 * Photos / Samsung Cloud keep on it) are found by the time they were taken - nothing to do;
 * others (in the cloud only) are added with the system photo picker, which shows the cloud
 * library too, and remembered with the workout.
 */
object WorkoutPhotos {
    /** What to ask for to read the phone's photos: Android 14+ also offers "selected photos only". */
    val permissions: Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun canRead(context: Context) = permissions.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Photos on the phone taken between [from] and [to] (wall clock), oldest first. */
    suspend fun onPhone(context: Context, from: Long, to: Long): List<WorkoutPhoto> = withContext(Dispatchers.IO) {
        if (!canRead(context)) return@withContext emptyList()
        val out = mutableListOf<WorkoutPhoto>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN),
                // Screenshots (of the course, a message) aren't photos of the run.
                "${MediaStore.Images.Media.DATE_TAKEN} BETWEEN ? AND ? AND " +
                    "(${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} IS NULL OR " +
                    "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} NOT IN ('Screenshots', 'Screenshot', '스크린샷'))",
                arrayOf(from.toString(), to.toString()),
                "${MediaStore.Images.Media.DATE_TAKEN} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    out += WorkoutPhoto(uri, c.getLong(1))
                }
            }
        }
        out
    }

    /** Remembers photos picked for [workoutId] (kept readable after the app restarts). */
    suspend fun add(context: Context, workoutId: Long, uris: List<Uri>) = withContext(Dispatchers.IO) {
        val dao = WorkoutStore.database.workouts()
        for (uri in uris) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            dao.insertPhoto(WorkoutPhotoEntity(workoutId = workoutId, uri = uri.toString(), takenAt = takenAt(context, uri)))
        }
    }

    suspend fun remove(id: Long) = withContext(Dispatchers.IO) { WorkoutStore.database.workouts().deletePhoto(id) }

    fun observeAdded(workoutId: Long) = WorkoutStore.database.workouts().observePhotos(workoutId)

    /** When a picked photo was taken, if its provider says (the picker's does). */
    private fun takenAt(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it > 0 } else null
        }
    }.getOrNull()

    /** The photo at about [px] on its longer side (a thumbnail, or a full-screen view). */
    suspend fun load(context: Context, uri: Uri, px: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29 && px <= 512) {
                context.contentResolver.loadThumbnail(uri, Size(px, px), null)
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }
        }.getOrNull()
    }
}
