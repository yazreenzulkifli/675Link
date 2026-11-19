// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Saved rides grouped by calendar day. Prev/Next flips the day like a day calendar;
 * each trip card can open the map, export GPX, or long-press delete.
 */
class TripsListActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var empty: TextView
    private lateinit var dayLabel: TextView
    private lateinit var daySummary: TextView
    private lateinit var todayBtn: MaterialButton

    /** Local midnight of the day currently shown. */
    private var dayStartMs: Long = 0L
    private var allTrips: List<Trip> = emptyList()
    private var didInitialDayPick = false

    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val trip = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()?.let { TripGpxImport.parse(it) }
        if (trip == null) {
            Toast.makeText(this, R.string.trips_import_fail, Toast.LENGTH_LONG).show()
        } else {
            TripStore.save(this, trip)
            dayStartMs = startOfDay(trip.start)
            allTrips = TripStore.list(this)
            renderDay()
            Toast.makeText(this, getString(R.string.trips_import_ok, trip.dateText(), trip.distanceKm), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_trips)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.trips_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        container = findViewById(R.id.trips_container)
        empty = findViewById(R.id.trips_empty)
        dayLabel = findViewById(R.id.trips_day_label)
        daySummary = findViewById(R.id.trips_day_summary)
        todayBtn = findViewById(R.id.trips_today)

        findViewById<View>(R.id.trips_day_prev).setOnClickListener {
            shiftDay(-1)
        }
        findViewById<View>(R.id.trips_day_next).setOnClickListener {
            shiftDay(1)
        }
        todayBtn.setOnClickListener {
            dayStartMs = startOfDay(System.currentTimeMillis())
            renderDay()
        }

        findViewById<View>(R.id.trips_back).setOnClickListener { finish() }
        findViewById<View>(R.id.trips_import).setOnClickListener {
            importPicker.launch(arrayOf("*/*"))
        }

        dayStartMs = startOfDay(System.currentTimeMillis())
    }

    override fun onResume() {
        super.onResume()
        allTrips = TripStore.list(this)
        // First open: if today has no rides, land on the most recent ride day.
        if (!didInitialDayPick) {
            didInitialDayPick = true
            val today = startOfDay(System.currentTimeMillis())
            if (allTrips.none { startOfDay(it.start) == today }) {
                allTrips.firstOrNull()?.let { dayStartMs = startOfDay(it.start) }
            }
        }
        renderDay()
    }

    /** Days (local midnights) that actually have rides, ascending. */
    private fun rideDays(): List<Long> = allTrips.map { startOfDay(it.start) }.distinct().sorted()

    /** Jump to the nearest day with rides in that direction; days without rides are skipped. */
    private fun shiftDay(delta: Int) {
        val days = rideDays()
        val target = if (delta < 0) days.lastOrNull { it < dayStartMs } else days.firstOrNull { it > dayStartMs }
        if (target != null) {
            dayStartMs = target
            renderDay()
        }
    }

    private fun renderDay() {
        container.removeAllViews()
        val dayEnd = dayStartMs + 24L * 60L * 60L * 1000L
        val dayTrips = allTrips.filter { it.start in dayStartMs until dayEnd }
            .sortedByDescending { it.start }

        dayLabel.text = DAY_FMT.format(Date(dayStartMs))
        val today = startOfDay(System.currentTimeMillis())
        val days = rideDays()
        todayBtn.visibility = if (dayStartMs != today && today in days) View.VISIBLE else View.GONE
        findViewById<View>(R.id.trips_day_prev).apply { val on = days.any { it < dayStartMs }; isEnabled = on; alpha = if (on) 1f else 0.35f }
        findViewById<View>(R.id.trips_day_next).apply { val on = days.any { it > dayStartMs }; isEnabled = on; alpha = if (on) 1f else 0.35f }

        if (dayTrips.isEmpty()) {
            empty.visibility = View.VISIBLE
            empty.text = if (allTrips.isEmpty()) {
                getString(R.string.trips_no_trips_yet)
            } else {
                getString(R.string.trips_no_rides_browse_days)
            }
            daySummary.text = getString(R.string.trips_no_rides_summary)
        } else {
            empty.visibility = View.GONE
            val km = dayTrips.sumOf { it.distanceKm }
            val n = dayTrips.size
            daySummary.text = resources.getQuantityString(
                R.plurals.trips_day_summary,
                n,
                n,
                km,
            )
            for (trip in dayTrips) container.addView(buildCard(trip))
        }
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun rideTitle(trip: Trip): String {
        val h = Calendar.getInstance().apply { timeInMillis = trip.start }.get(Calendar.HOUR_OF_DAY)
        val res = when (h) {
            in 5..11 -> R.string.trips_title_morning
            in 12..16 -> R.string.trips_title_afternoon
            in 17..20 -> R.string.trips_title_evening
            else -> R.string.trips_title_night
        }
        return getString(res)
    }

    private fun lp(w: Int, h: Int, top: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = top }

    private fun buildCard(trip: Trip): View {
        val cyan = color(R.color.home_cyan)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, dp(12))
            background = ContextCompat.getDrawable(this@TripsListActivity, R.drawable.trip_card_bg)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            isClickable = true
            isFocusable = true
            setOnClickListener { TripMapActivity.start(this@TripsListActivity, trip.id) }
            setOnLongClickListener { confirmDelete(trip); true }
        }

        // Header: bolt badge + time range + title.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            background = ContextCompat.getDrawable(this@TripsListActivity, R.drawable.home_icon_circle)
            backgroundTintList = android.content.res.ColorStateList.valueOf(color(R.color.home_cyan_soft))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setImageResource(R.drawable.ic_trip_bolt)
            imageTintList = android.content.res.ColorStateList.valueOf(cyan)
            contentDescription = null
        })
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12) }
        }
        titles.addView(TextView(this).apply {
            text = trip.timeRangeText()
            setTextColor(color(R.color.home_text))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        titles.addView(TextView(this).apply {
            text = rideTitle(trip)
            setTextColor(0xFF94A3B8.toInt())
            textSize = 12f
        })
        header.addView(titles)
        card.addView(header)

        // Dark GPS map preview with overlay pills.
        if (trip.points.size >= 2) {
            val thumbH = dp(170)
            val frame = FrameLayout(this).apply {
                layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, thumbH, dp(14))
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
                    }
                }
                setBackgroundColor(0xFF111827.toInt())
            }
            val preview = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
                scaleType = ImageView.ScaleType.FIT_XY
                contentDescription = getString(R.string.trips_route_preview)
            }
            preview.post {
                val w = preview.width.coerceAtLeast(dp(240))
                preview.setImageBitmap(
                    TripRouteThumb.renderDark(trip, w, thumbH, resources.displayMetrics.density),
                )
            }
            frame.addView(preview)
            frame.addView(overlayPill(getString(R.string.trips_gps_accurate), true).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.START,
                ).apply { setMargins(dp(10), 0, 0, dp(10)) }
            })
            card.addView(frame)
        }

        // Metrics row.
        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, dp(16))
        }
        stats.addView(stat(getString(R.string.trip_distance), String.format(Locale.US, "%.2f", trip.distanceKm), "km", false))
        stats.addView(stat(getString(R.string.trips_stat_time), trip.durationText(), null, false))
        stats.addView(stat(getString(R.string.trips_stat_avg), "${trip.avgKmh}", "km/h", false))
        stats.addView(stat(getString(R.string.trips_stat_max), "${trip.maxKmh}", "km/h", true))
        card.addView(stats)

        // Actions.
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, dp(16))
        }
        actions.addView(actionButton(
            getString(R.string.trips_share), R.drawable.ic_trip_share, R.drawable.trip_share_btn,
            color(R.color.home_text), color(R.color.home_text),
        ) { shareSummary(trip) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) }
        })
        actions.addView(actionButton(
            getString(R.string.trips_export_gpx), R.drawable.ic_trip_download, R.drawable.trip_export_btn,
            0xFFFFFFFF.toInt(), cyan,
        ) { exportGpx(trip) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) }
        })
        card.addView(actions)
        return card
    }

    private fun overlayPill(text: String, dot: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@TripsListActivity, R.drawable.trip_map_overlay_pill)
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        if (dot) {
            row.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(6) }
                background = ContextCompat.getDrawable(this@TripsListActivity, R.drawable.home_dot)
                backgroundTintList = android.content.res.ColorStateList.valueOf(color(R.color.home_mint))
            })
        }
        row.addView(TextView(this).apply {
            this.text = text
            setTextColor(0xFFE2E8F0.toInt())
            textSize = 11f
        })
        return row
    }

    private fun actionButton(
        label: String, icon: Int, bg: Int, textColor: Int, iconColor: Int, onClick: () -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = ContextCompat.getDrawable(this@TripsListActivity, bg)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        addView(ImageView(this@TripsListActivity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) }
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(iconColor)
            contentDescription = null
        })
        addView(TextView(this@TripsListActivity).apply {
            text = label
            setTextColor(textColor)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
        })
    }

    private fun shareSummary(trip: Trip) {
        val body = "${trip.dateText()} · ${trip.timeRangeText()}\n" +
            "${trip.distanceText()} · ${trip.durationText()} · avg ${trip.avgKmh} km/h · max ${trip.maxKmh} km/h"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, body)
        }
        startActivity(Intent.createChooser(send, getString(R.string.trips_share)))
    }

    private fun exportGpx(trip: Trip) {
        try {
            if (trip.points.isEmpty()) {
                Toast.makeText(this, getString(R.string.trips_no_gps_points), Toast.LENGTH_SHORT).show()
                return
            }
            val dir = File(cacheDir, "trip-export").apply { mkdirs() }
            val file = File(dir, TripGpx.suggestedFileName(trip))
            file.writeText(TripGpx.toXml(trip), Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(contentResolver, file.name, uri)
            }
            startActivity(Intent.createChooser(send, getString(R.string.trips_export_gpx)))
            LogBus.log("→ Trip GPX exported: ${file.name}")
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.trips_export_failed, e.toString()), Toast.LENGTH_LONG).show()
        }
    }

    private fun stat(label: String, value: String, unit: String?, highlight: Boolean): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        box.addView(TextView(this).apply {
            text = label.uppercase()
            setTextColor(0xFF94A3B8.toInt())
            textSize = 10f
            letterSpacing = 0.06f
        })
        box.addView(TextView(this).apply {
            val vColor = if (highlight) color(R.color.home_cyan) else color(R.color.home_text)
            val sb = android.text.SpannableStringBuilder(value)
            sb.setSpan(android.text.style.ForegroundColorSpan(vColor), 0, sb.length, 0)
            sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length, 0)
            if (unit != null) {
                val s = sb.length
                sb.append(" ").append(unit)
                sb.setSpan(android.text.style.ForegroundColorSpan(0xFF64748B.toInt()), s, sb.length, 0)
                sb.setSpan(android.text.style.RelativeSizeSpan(0.7f), s, sb.length, 0)
            }
            text = sb
            textSize = 16f
            fontFeatureSettings = "tnum"
            maxLines = 1
        })
        return box
    }

    private fun confirmDelete(trip: Trip) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.trips_delete_trip_title)
            .setMessage("${trip.dateText()} · ${trip.distanceText()}")
            .setPositiveButton(R.string.trips_delete) { _, _ ->
                TripStore.delete(this, trip.id)
                allTrips = TripStore.list(this)
                renderDay()
            }
            .setNegativeButton(R.string.dash_cancel, null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val DAY_FMT = SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault())

        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, TripsListActivity::class.java))
        }

        fun startOfDay(epochMs: Long): Long {
            val cal = Calendar.getInstance().apply {
                timeInMillis = epochMs
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return cal.timeInMillis
        }
    }
}
