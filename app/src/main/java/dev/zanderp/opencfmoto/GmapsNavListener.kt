// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Live turn-by-turn from the real Google Maps app, read from its ongoing navigation
 * notification. Lets the OpenCfMoto Map (own projection, no Android Auto) show Google's
 * next maneuver on the dash while Google Maps does the routing on the phone.
 *
 * Prototype: Maps' notification layout is not a public API. Every new layout is logged once
 * under `[GMAPS]` so the parser can be tuned from a shared log.
 */
object GmapsNav {
    data class Turn(
        /** Google's maneuver arrow, pre-tinted white when it is a transparent glyph. */
        val icon: Bitmap?,
        /** Fallback arrow inferred from the instruction text. */
        val iconRes: Int,
        val distance: String?,
        val instruction: String,
        /** "14:32" style arrival, or the raw ETA line when no clock time was found. */
        val eta: String?,
        val updatedAtMs: Long,
    )

    @Volatile var current: Turn? = null
        private set

    private val listeners = CopyOnWriteArrayList<(Turn?) -> Unit>()

    fun addListener(l: (Turn?) -> Unit) {
        listeners.add(l)
        l(current)
    }

    fun removeListener(l: (Turn?) -> Unit) {
        listeners.remove(l)
    }

    internal fun publish(turn: Turn?) {
        current = turn
        for (l in listeners) {
            try { l(turn) } catch (e: Exception) { LogBus.log("[GMAPS] listener failed: $e") }
        }
    }

    fun isAccessGranted(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners",
        ) ?: return false
        val me = ComponentName(context, GmapsNavListener::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun openAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(intent) } catch (e: Exception) {
            LogBus.log("[GMAPS] cannot open notification access settings: $e")
        }
    }

    // ---- parsing (pure; kept here so it can be unit-tested) ----

    private val DIST_RE = Regex(
        """(\d+(?:[.,]\d+)?)\s?(km|m|mi|ft|yd)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val CLOCK_RE = Regex("""\b\d{1,2}[:.]\d{2}(\s?[AaPp]\.?[Mm]\.?)?""")

    /** Pull distance / instruction / ETA out of the notification's text fields. */
    fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
    ): Triple<String?, String, String?>? {
        val fields = listOfNotNull(title, text, bigText).map { it.trim() }.filter { it.isNotEmpty() }
        if (fields.isEmpty()) return null
        // A field that is only a distance ("250 m") is the distance; otherwise grab the first
        // distance mentioned anywhere ("In 250 m, turn left").
        val pureDist = fields.firstOrNull { f -> DIST_RE.matchEntire(f) != null }
        val distance = pureDist ?: fields.firstNotNullOfOrNull { DIST_RE.find(it)?.value }
        val instruction = fields
            .filter { it != pureDist }
            .distinct()
            .joinToString(" · ")
            .ifBlank { pureDist ?: return null }
        val etaSource = listOfNotNull(subText, text, bigText).joinToString(" ")
        val eta = CLOCK_RE.find(etaSource)?.value ?: subText?.takeIf { it.isNotBlank() }
        return Triple(distance, instruction, eta)
    }

    fun iconForText(s: String): Int {
        val t = s.lowercase()
        return when {
            "u-turn" in t || "make a u" in t -> R.drawable.ic_man_uturn
            "roundabout" in t || "rotary" in t -> R.drawable.ic_man_roundabout
            "arriv" in t || "destination" in t -> R.drawable.ic_man_arrive
            "merge" in t -> R.drawable.ic_man_merge
            "fork" in t -> R.drawable.ic_man_fork
            "sharp left" in t -> R.drawable.ic_man_sharp_left
            "sharp right" in t -> R.drawable.ic_man_sharp_right
            "slight left" in t || "keep left" in t || "bear left" in t -> R.drawable.ic_man_slight_left
            "slight right" in t || "keep right" in t || "bear right" in t -> R.drawable.ic_man_slight_right
            "left" in t -> R.drawable.ic_man_left
            "right" in t -> R.drawable.ic_man_right
            else -> R.drawable.ic_man_straight
        }
    }
}

class GmapsNavListener : NotificationListenerService() {

    private var navKey: String? = null
    private var lastLayoutSig: String? = null

    override fun onListenerConnected() {
        LogBus.log("[GMAPS] notification listener connected")
        try {
            activeNotifications?.forEach { onNotificationPosted(it) }
        } catch (e: Exception) {
            LogBus.log("[GMAPS] scan active failed: $e")
        }
    }

    override fun onListenerDisconnected() {
        LogBus.log("[GMAPS] notification listener disconnected")
        navKey = null
        GmapsNav.publish(null)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != MAPS_PKG) return
        val n = sbn.notification ?: return
        if (!isNavNotification(sbn, n)) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        val sig = "${n.channelId}|${title != null}|${text != null}|${big != null}|${sub != null}|" +
            "${n.getLargeIcon() != null}"
        if (sig != lastLayoutSig) {
            lastLayoutSig = sig
            LogBus.log(
                "[GMAPS] nav layout ch=${n.channelId} cat=${n.category} " +
                    "title='${title.orEmpty()}' text='${text.orEmpty()}' " +
                    "big='${big.orEmpty()}' sub='${sub.orEmpty()}' largeIcon=${n.getLargeIcon() != null}",
            )
        }

        val parsed = GmapsNav.parse(title, text, big, sub)
        if (parsed == null) {
            LogBus.log("[GMAPS] nav notification has no readable text (custom view?)")
            return
        }
        val (dist, instruction, eta) = parsed
        navKey = sbn.key
        GmapsNav.publish(
            GmapsNav.Turn(
                icon = loadIcon(n),
                iconRes = GmapsNav.iconForText(instruction),
                distance = dist,
                instruction = instruction,
                eta = eta,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == navKey) {
            navKey = null
            LogBus.log("[GMAPS] navigation ended")
            GmapsNav.publish(null)
        }
    }

    private fun isNavNotification(sbn: StatusBarNotification, n: Notification): Boolean {
        if (n.category == Notification.CATEGORY_NAVIGATION) return true
        val ch = n.channelId?.lowercase().orEmpty()
        if ("nav" in ch) return true
        // Older builds: ongoing Maps notification with a distance in the title.
        return sbn.isOngoing && n.extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.let { Regex("""\d\s?(m|km|ft|mi)\b""").containsMatchIn(it) } == true
    }

    private fun loadIcon(n: Notification): Bitmap? {
        val d: Drawable = try {
            n.getLargeIcon()?.loadDrawable(this)
        } catch (_: Exception) {
            null
        } ?: return null
        val src = if (d is BitmapDrawable && d.bitmap != null) {
            d.bitmap
        } else {
            val w = d.intrinsicWidth.takeIf { it > 0 } ?: 128
            val h = d.intrinsicHeight.takeIf { it > 0 } ?: 128
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                val c = Canvas(b)
                d.setBounds(0, 0, w, h)
                d.draw(c)
            }
        }
        // Maps' arrow is a glyph on transparency → force white so it reads on the dark dash card.
        // If the corners are opaque it's a full picture; leave it alone.
        val transparentCorners = runCatching {
            Color.alpha(src.getPixel(0, 0)) < 32 &&
                Color.alpha(src.getPixel(src.width - 1, src.height - 1)) < 32
        }.getOrDefault(false)
        if (!transparentCorners) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            src, 0f, 0f,
            Paint().apply { colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN) },
        )
        return out
    }

    companion object {
        private const val MAPS_PKG = "com.google.android.apps.maps"
    }
}
