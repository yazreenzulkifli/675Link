// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import java.time.Instant

/**
 * Turns a GPX track (`trkpt` with optional `<time>` and `<speed>`) into a saved [Trip].
 * Missing timestamps are synthesised at a steady pace starting now so the ride still has a duration.
 */
object TripGpxImport {
    private val ptRe = Regex("""<trkpt\b([^>]*?)(?:/>|>([\s\S]*?)</trkpt>)""", RegexOption.IGNORE_CASE)
    private val latRe = Regex("""\blat\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val lonRe = Regex("""\blon\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val timeRe = Regex("""<time[^>]*>\s*([^<\s]+)\s*</time>""", RegexOption.IGNORE_CASE)
    private val speedRe = Regex("""<speed[^>]*>\s*([^<\s]+)\s*</speed>""", RegexOption.IGNORE_CASE)

    private const val FALLBACK_SPEED_MS = 12.0
    private const val MOVING_MIN_MS = 0.5

    /** Returns null when the GPX holds fewer than two usable track points. */
    fun parse(xml: String, nowMs: Long = System.currentTimeMillis()): Trip? {
        class Raw(val lat: Double, val lon: Double, val t: Long?, val v: Float?)
        val raw = ArrayList<Raw>()
        for (m in ptRe.findAll(xml)) {
            val lat = latRe.find(m.groupValues[1])?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            val lon = lonRe.find(m.groupValues[1])?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            val body = m.groupValues[2]
            val t = timeRe.find(body)?.groupValues?.get(1)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            val v = speedRe.find(body)?.groupValues?.get(1)?.toFloatOrNull()
            raw.add(Raw(lat, lon, t, v))
        }
        if (raw.size < 2) return null

        val hasTimes = raw.all { it.t != null }
        val points = ArrayList<TrackPoint>(raw.size)
        var t = nowMs
        var dist = 0.0
        var moving = 0L
        var maxSpeed = 0f
        for (i in raw.indices) {
            val r = raw[i]
            var segSpeed = r.v ?: 0f
            if (i > 0) {
                val p = raw[i - 1]
                val d = GpxNav.haversineM(p.lat, p.lon, r.lat, r.lon)
                val dt = if (hasTimes) (r.t!! - p.t!!).coerceAtLeast(0) else (d / FALLBACK_SPEED_MS * 1000).toLong()
                t = if (hasTimes) r.t!! else t + dt
                dist += d
                val computed = if (dt > 0) (d / (dt / 1000.0)).toFloat() else 0f
                if (r.v == null) segSpeed = computed
                if (segSpeed > MOVING_MIN_MS) moving += dt.coerceAtMost(30_000)
                if (segSpeed > maxSpeed) maxSpeed = segSpeed
            } else {
                t = if (hasTimes) r.t!! else nowMs
            }
            points.add(TrackPoint(r.lat, r.lon, t, segSpeed))
        }
        val start = points.first().time
        return Trip(
            id = start.toString(),
            start = start,
            end = points.last().time,
            distanceMeters = dist,
            movingTimeMs = moving,
            maxSpeedMs = maxSpeed,
            points = points,
        )
    }
}
