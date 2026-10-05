package com.mhmh2.bujirun.course

import com.mhmh2.bujirun.tracking.distanceMeters
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory
import kotlin.math.cos

data class GeoPoint(val lat: Double, val lon: Double, val altitudeM: Double? = null)

/** Where a course came from - shown on the list, and decides the default name. */
enum class CourseSource(val label: String) {
    GPX("파일"), WORKOUT("내 기록"), DRAWN("직접 만듦"), RECOMMENDED("추천"),
}

object CourseGeometry {
    fun distanceM(points: List<GeoPoint>): Double =
        points.zipWithNext().sumOf { (a, b) -> distanceMeters(a.lat, a.lon, b.lat, b.lon) }

    /** Ends within 100 m of where it starts. */
    fun isLoop(points: List<GeoPoint>): Boolean {
        if (points.size < 3) return false
        val a = points.first(); val b = points.last()
        return distanceMeters(a.lat, a.lon, b.lat, b.lon) < 100
    }

    /** Total climb, ignoring altitude wobbles under [hysteresisM] (as for recorded workouts). */
    fun ascentM(points: List<GeoPoint>, hysteresisM: Double = 3.0): Double {
        var ref: Double? = null
        var ascent = 0.0
        for (alt in points.mapNotNull { it.altitudeM }) {
            val r = ref
            when {
                r == null -> ref = alt
                alt > r + hysteresisM -> { ascent += alt - r; ref = alt }
                alt < r - hysteresisM -> ref = alt
            }
        }
        return ascent
    }

    /**
     * Douglas-Peucker: drops points that lie within [toleranceM] of the line through their
     * neighbours. A Strava export with a point every second becomes a few hundred points that
     * draw and follow the same, without storing thousands of rows per course.
     */
    fun simplify(points: List<GeoPoint>, toleranceM: Double = 2.0): List<GeoPoint> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true; keep[points.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to points.lastIndex) }
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            var maxD = 0.0; var idx = -1
            for (i in s + 1 until e) {
                val d = offsetM(points[i], points[s], points[e])
                if (d > maxD) { maxD = d; idx = i }
            }
            if (idx >= 0 && maxD > toleranceM) {
                keep[idx] = true
                stack.add(s to idx); stack.add(idx to e)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** Distance (m) from [p] to the segment [a]-[b], on a local flat projection. */
    private fun offsetM(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val kx = 111_195.0 * cos(Math.toRadians(a.lat)); val ky = 111_195.0
        val px = (p.lon - a.lon) * kx; val py = (p.lat - a.lat) * ky
        val bx = (b.lon - a.lon) * kx; val by = (b.lat - a.lat) * ky
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - t * bx; val dy = py - t * by
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}

/** A GPX file read as a course: its name (if any) and its line. */
data class ParsedGpx(val name: String?, val points: List<GeoPoint>)

object CourseGpx {
    /**
     * Reads the course line from a GPX file: track points if there are any (what Strava, Garmin
     * and most apps export), else route points (planned routes), else waypoints in order.
     * Multiple track segments are joined. Throws [IllegalArgumentException] if there's no line.
     */
    fun parse(input: InputStream): ParsedGpx {
        val trk = mutableListOf<GeoPoint>(); val rte = mutableListOf<GeoPoint>(); val wpt = mutableListOf<GeoPoint>()
        var name: String? = null
        val handler = object : DefaultHandler() {
            private var current: MutableList<GeoPoint>? = null
            private var lat = 0.0; private var lon = 0.0; private var ele: Double? = null
            private val text = StringBuilder()
            private var depthInPoint = false

            override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
                text.setLength(0)
                when (local(qName)) {
                    "trkpt", "rtept", "wpt" -> {
                        val la = attrs.getValue("lat")?.toDoubleOrNull()
                        val lo = attrs.getValue("lon")?.toDoubleOrNull()
                        if (la != null && lo != null) {
                            lat = la; lon = lo; ele = null; depthInPoint = true
                            current = when (local(qName)) { "trkpt" -> trk; "rtept" -> rte; else -> wpt }
                        }
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) { text.append(ch, start, length) }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (local(qName)) {
                    "ele" -> if (depthInPoint) ele = text.toString().trim().toDoubleOrNull()
                    // The first non-empty name outside a point (metadata, trk or rte) names the course.
                    "name" -> if (!depthInPoint && name == null) name = text.toString().trim().ifEmpty { null }
                    "trkpt", "rtept", "wpt" -> if (depthInPoint) {
                        current?.add(GeoPoint(lat, lon, ele)); depthInPoint = false
                    }
                }
            }

            private fun local(q: String) = q.substringAfter(':')
        }
        SAXParserFactory.newInstance().newSAXParser().parse(input, handler)
        val points = when {
            trk.size >= 2 -> trk
            rte.size >= 2 -> rte
            wpt.size >= 2 -> wpt
            else -> throw IllegalArgumentException("no track")
        }
        return ParsedGpx(name, points)
    }

    /** GPX 1.1 for a course (a route line, no times). */
    fun write(name: String, points: List<GeoPoint>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="Bujirun" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        append("  <trk><name>").append(escape(name)).append("</name><trkseg>\n")
        points.forEach { p ->
            append("    <trkpt lat=\"").append("%.7f".format(java.util.Locale.US, p.lat))
                .append("\" lon=\"").append("%.7f".format(java.util.Locale.US, p.lon)).append("\">")
            p.altitudeM?.let { append("<ele>").append("%.1f".format(java.util.Locale.US, it)).append("</ele>") }
            append("</trkpt>\n")
        }
        append("  </trkseg></trk>\n</gpx>\n")
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
