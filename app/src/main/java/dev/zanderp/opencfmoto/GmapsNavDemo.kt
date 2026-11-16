// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only fake Google Maps navigation: plays a scripted route through the same parser
 * ([GmapsNav.parse]) the real notification listener uses, so the dash turn card can be checked
 * without riding or starting Google Maps.
 */
object GmapsNavDemo {
    private data class Step(
        /** Notification title / text as Google Maps might post them; "{d}" = live distance. */
        val title: String,
        val text: String?,
        val startM: Int,
    )

    private val script = listOf(
        Step("{d}", "Turn right onto Jalan Ampang", 350),
        Step("{d}", "Keep left at the fork", 220),
        Step("{d}", "At the roundabout, take the 2nd exit onto Jalan Tun Razak", 1400),
        // Distance inside the sentence instead of a separate title (tests the other parse path).
        Step("Turn slight right", "In {d}", 300),
        Step("{d}", "Turn left onto Persiaran KLCC", 600),
        Step("{d}", "Make a U-turn", 120),
        Step("{d}", "Your destination is on the right", 80),
    )

    private const val TICK_MS = 700L
    private const val TICKS_PER_STEP = 8

    private val main = Handler(Looper.getMainLooper())
    @Volatile var running = false
        private set

    fun start(delayMs: Long = 2_500L) {
        stop()
        running = true
        LogBus.log("[GMAPS] demo starting — fake Google Maps route, ${script.size} turns")
        main.postDelayed({ tick(0, 0) }, delayMs)
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        if (running) {
            running = false
            GmapsNav.publish(null)
            LogBus.log("[GMAPS] demo stopped")
        }
    }

    private fun tick(stepIdx: Int, t: Int) {
        if (!running) return
        if (stepIdx >= script.size) {
            LogBus.log("[GMAPS] demo finished — navigation ended")
            running = false
            GmapsNav.publish(null)
            return
        }
        val step = script[stepIdx]
        val remainFrac = 1f - t.toFloat() / TICKS_PER_STEP
        val distM = (step.startM * remainFrac).toInt().coerceAtLeast(10)
        val d = formatDist(distM)
        val title = step.title.replace("{d}", d)
        val text = step.text?.replace("{d}", d)
        // Remaining route ≈ this step + all later steps, at ~40 km/h.
        val remainM = distM + script.drop(stepIdx + 1).sumOf { it.startM }
        val etaMs = System.currentTimeMillis() + (remainM / 11.1 * 1000).toLong()
        val sub = "${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(etaMs))} ETA"

        val parsed = GmapsNav.parse(title, text, null, sub)
        if (parsed != null) {
            val (dist, instruction, eta) = parsed
            GmapsNav.publish(
                GmapsNav.Turn(
                    icon = null,
                    iconRes = GmapsNav.iconForText(instruction),
                    distance = dist,
                    instruction = instruction,
                    eta = eta,
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
        }
        if (t == 0) LogBus.log("[GMAPS] demo turn ${stepIdx + 1}: title='$title' text='${text.orEmpty()}'")
        val next = if (t + 1 > TICKS_PER_STEP) (stepIdx + 1) to 0 else stepIdx to (t + 1)
        main.postDelayed({ tick(next.first, next.second) }, TICK_MS)
    }

    private fun formatDist(m: Int): String = when {
        m >= 1000 -> String.format(Locale.US, "%.1f km", m / 1000f)
        m >= 100 -> "${(m / 10) * 10} m"
        else -> "$m m"
    }
}
