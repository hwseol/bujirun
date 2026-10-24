package com.mhmh2.bujirun.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.mhmh2.bujirun.tr
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * All records in one file the user keeps: the database (workouts, routes, courses, auto records,
 * steps, photo links) and the profile photo, zipped. Moving between builds signed with different
 * keys (the sideloaded test APK and the Play build) is where Android's own backup can't help:
 * it won't restore one app's backup into an app signed differently.
 */
object RecordsFile {
    private const val DB = "bujirun.db"
    private const val PROFILE = "profile.jpg"

    class BadFile(message: String) : Exception(message)

    suspend fun export(context: Context, to: Uri) = withContext(Dispatchers.IO) {
        // Everything into the main file first, so the copy holds the latest writes.
        WorkoutStore.database.query("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        val out = context.contentResolver.openOutputStream(to) ?: throw BadFile(tr("파일을 만들지 못했어요.", "Couldn't create the file."))
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(DB))
            context.getDatabasePath(DB).inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            File(context.filesDir, PROFILE).takeIf { it.exists() }?.let { photo ->
                zip.putNextEntry(ZipEntry(PROFILE))
                photo.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Replaces this phone's records with the file's, then restarts the app on them. */
    suspend fun import(context: Context, from: Uri) = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "import").apply { deleteRecursively(); mkdirs() }
        val input = context.contentResolver.openInputStream(from) ?: throw BadFile(tr("파일을 열지 못했어요.", "Couldn't open the file."))
        ZipInputStream(input.buffered()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { e ->
                if (e.name == DB || e.name == PROFILE) File(staging, e.name).outputStream().use { zip.copyTo(it) }
            }
        }
        val db = File(staging, DB)
        if (!db.exists() || db.inputStream().use { i -> ByteArray(15).also { i.read(it) }.decodeToString() } != "SQLite format 3") {
            throw BadFile(tr("부지런 기록 파일이 아니에요.", "This isn't a Bujirun records file."))
        }
        WorkoutStore.database.close()
        val target = context.getDatabasePath(DB)
        listOf("", "-wal", "-shm", "-journal").forEach { File(target.path + it).delete() }
        db.copyTo(target, overwrite = true)
        File(staging, PROFILE).takeIf { it.exists() }?.copyTo(File(context.filesDir, PROFILE), overwrite = true)
        staging.deleteRecursively()
        // A fresh process opens the new file (and migrates it if it came from an older version).
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        Runtime.getRuntime().exit(0)
    }
}
