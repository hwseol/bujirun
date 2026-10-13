package com.mhmh2.bujirun.data

import com.mhmh2.bujirun.tr

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Writes the GPX to the app's cache and opens the system share sheet (Drive, Files, mail, ...). */
object GpxShare {
    fun share(context: Context, title: String, workout: WorkoutEntity, points: List<TrackPointEntity>) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        // Old exports have served their purpose once shared; don't let the cache grow forever.
        dir.listFiles()?.forEach { it.delete() }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
            .format(Instant.ofEpochMilli(workout.startedAt).atZone(ZoneId.systemDefault()))
        val file = File(dir, "bujirun-$stamp.gpx")
        file.writeText(Gpx.write(title, workout, points))

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/gpx+xml")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, tr("GPX 내보내기", "Export GPX")))
    }
}
