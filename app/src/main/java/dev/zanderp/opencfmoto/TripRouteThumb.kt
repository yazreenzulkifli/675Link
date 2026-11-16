// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** Lightweight GPS-track thumbnail for trip list cards (no map tiles). */
object TripRouteThumb {
    fun render(
        trip: Trip,
        widthPx: Int,
        heightPx: Int,
        strokeColor: Int,
        bgColor: Int,
        startColor: Int,
        endColor: Int,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(bgColor)

        val pts = subsample(trip.points, 180)
        if (pts.size < 2) return bmp

        var minLat = pts[0].lat
        var maxLat = pts[0].lat
        var minLon = pts[0].lon
        var maxLon = pts[0].lon
        for (p in pts) {
            minLat = min(minLat, p.lat)
            maxLat = max(maxLat, p.lat)
            minLon = min(minLon, p.lon)
            maxLon = max(maxLon, p.lon)
        }
        // Equirectangular with cos(lat) so E–W isn’t stretched at mid latitudes.
        val midLat = (minLat + maxLat) * 0.5
        val cosLat = cos(Math.toRadians(midLat)).coerceAtLeast(0.2)
        var spanX = (maxLon - minLon) * cosLat
        var spanY = maxLat - minLat
        if (spanX < 1e-7) spanX = 1e-7
        if (spanY < 1e-7) spanY = 1e-7

        val pad = min(widthPx, heightPx) * 0.14f
        val drawW = widthPx - pad * 2
        val drawH = heightPx - pad * 2
        val scale = min(drawW / spanX, drawH / spanY)
        val usedW = spanX * scale
        val usedH = spanY * scale
        val ox = pad + (drawW - usedW) / 2f
        val oy = pad + (drawH - usedH) / 2f

        fun xOf(lon: Double) = (ox + ((lon - minLon) * cosLat) * scale).toFloat()
        fun yOf(lat: Double) = (oy + (maxLat - lat) * scale).toFloat()

        val path = Path()
        path.moveTo(xOf(pts[0].lon), yOf(pts[0].lat))
        for (i in 1 until pts.size) {
            path.lineTo(xOf(pts[i].lon), yOf(pts[i].lat))
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = strokeColor
            strokeWidth = max(4f, min(widthPx, heightPx) * 0.035f)
        }
        canvas.drawPath(path, paint)

        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val r = max(5f, min(widthPx, heightPx) * 0.045f)
        dot.color = startColor
        canvas.drawCircle(xOf(pts.first().lon), yOf(pts.first().lat), r, dot)
        dot.color = endColor
        canvas.drawCircle(xOf(pts.last().lon), yOf(pts.last().lat), r, dot)

        return bmp
    }

    /** Dark GPS-grid preview: glowing cyan route, start ring, and an emerald top-speed marker. */
    fun renderDark(trip: Trip, widthPx: Int, heightPx: Int, density: Float): Bitmap {
        val cyan = 0xFF00B4D8.toInt()
        val emerald = 0xFF10B981.toInt()
        val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF111827.toInt())

        val grid = Paint().apply { color = 0x14FFFFFF; strokeWidth = density }
        val gs = 28f * density
        var gx = 0f
        while (gx < widthPx) { canvas.drawLine(gx, 0f, gx, heightPx.toFloat(), grid); gx += gs }
        var gy = 0f
        while (gy < heightPx) { canvas.drawLine(0f, gy, widthPx.toFloat(), gy, grid); gy += gs }

        val pts = subsample(trip.points, 240)
        if (pts.size < 2) return bmp

        var minLat = pts[0].lat; var maxLat = pts[0].lat
        var minLon = pts[0].lon; var maxLon = pts[0].lon
        for (p in pts) {
            minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat)
            minLon = min(minLon, p.lon); maxLon = max(maxLon, p.lon)
        }
        val cosLat = cos(Math.toRadians((minLat + maxLat) * 0.5)).coerceAtLeast(0.2)
        val spanX = ((maxLon - minLon) * cosLat).coerceAtLeast(1e-7)
        val spanY = (maxLat - minLat).coerceAtLeast(1e-7)
        val padX = 28f * density
        val padY = 30f * density
        val drawW = widthPx - padX * 2
        val drawH = heightPx - padY * 2
        val scale = min(drawW / spanX, drawH / spanY)
        val ox = padX + (drawW - spanX * scale) / 2f
        val oy = padY + (drawH - spanY * scale) / 2f
        fun xOf(lon: Double) = (ox + ((lon - minLon) * cosLat) * scale).toFloat()
        fun yOf(lat: Double) = (oy + (maxLat - lat) * scale).toFloat()

        val path = Path()
        path.moveTo(xOf(pts[0].lon), yOf(pts[0].lat))
        for (i in 1 until pts.size) path.lineTo(xOf(pts[i].lon), yOf(pts[i].lat))

        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            color = 0x6600B4D8; strokeWidth = 9f * density
            maskFilter = android.graphics.BlurMaskFilter(4f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawPath(path, glow)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            color = cyan; strokeWidth = 3f * density
        }
        canvas.drawPath(path, line)

        // Apex (top speed) marker with label.
        val apex = pts.maxByOrNull { it.speedMs }
        if (apex != null && apex.speedMs > 0f) {
            val ax = xOf(apex.lon); val ay = yOf(apex.lat)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG)
            fill.color = 0x4410B981; canvas.drawCircle(ax, ay, 9f * density, fill)
            fill.color = emerald; canvas.drawCircle(ax, ay, 5f * density, fill)
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = cyan; textSize = 11f * density; typeface = android.graphics.Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("${(apex.speedMs * 3.6f).toInt()} km/h", ax, ay - 14f * density, tp)
        }

        // Start / finish ping: translucent ring + white core.
        val s = pts.first()
        val sx = xOf(s.lon); val sy = yOf(s.lat)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG)
        ring.color = 0x5500B4D8; canvas.drawCircle(sx, sy, 11f * density, ring)
        ring.color = cyan; canvas.drawCircle(sx, sy, 7f * density, ring)
        ring.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(sx, sy, 4f * density, ring)
        return bmp
    }

    private fun subsample(points: List<TrackPoint>, maxPts: Int): List<TrackPoint> {
        if (points.size <= maxPts) return points
        val out = ArrayList<TrackPoint>(maxPts)
        val step = (points.size - 1).toDouble() / (maxPts - 1)
        var i = 0
        while (i < maxPts) {
            out.add(points[(i * step).toInt().coerceIn(0, points.lastIndex)])
            i++
        }
        return out
    }
}
