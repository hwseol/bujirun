package com.mhmh2.bujirun.data

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * GPX 1.1 - the format Strava, Garmin Connect, Komoot and most others import. One <trkseg> per
 * pause segment, so importers don't draw (or count) a straight line across a pause.
 */
object Gpx {
    fun write(title: String, workout: WorkoutEntity, points: List<TrackPointEntity>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="Bujirun" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        append("  <metadata><name>").append(escape(title)).append("</name><time>")
            .append(time(workout.startedAt)).append("</time></metadata>\n")
        append("  <trk>\n")
        append("    <name>").append(escape(title)).append("</name>\n")
        // Strava reads this to set the activity type on import.
        append("    <type>").append(if (workout.isRun) "running" else "walking").append("</type>\n")
        points.groupBy { it.segment }.toSortedMap().values.forEach { segment ->
            append("    <trkseg>\n")
            segment.forEach { p ->
                append("      <trkpt lat=\"").append(coord(p.lat)).append("\" lon=\"").append(coord(p.lon)).append("\">")
                p.altitudeM?.let { append("<ele>").append(String.format(Locale.US, "%.1f", it)).append("</ele>") }
                if (p.epochMs > 0) append("<time>").append(time(p.epochMs)).append("</time>")
                append("</trkpt>\n")
            }
            append("    </trkseg>\n")
        }
        append("  </trk>\n")
        append("</gpx>\n")
    }

    // Whole seconds: some importers choke on fractional seconds.
    private fun time(epochMs: Long) = Instant.ofEpochMilli(epochMs).truncatedTo(ChronoUnit.SECONDS).toString()

    private fun coord(v: Double) = String.format(Locale.US, "%.7f", v)

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
