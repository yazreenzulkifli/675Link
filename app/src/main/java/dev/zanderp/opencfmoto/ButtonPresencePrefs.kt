// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context

/**
 * Which physical handlebar sources this bike actually delivers over AVRCP. Some dashes (e.g. 800NK
 * Adventure) never emit volume ▲/▼ while still sending track keys — pinning phone volume waiting for
 * rockers that never arrive is a "volume hostage". Teach-my-handlebar / auto-probe write here;
 * [MediaButtonBridge] reads it before pinning.
 *
 * Per-bike via [BikeScope]. Unknown = not taught yet (pin as today); Present / Absent are sticky
 * until the rider re-teaches or we see an event that flips Present.
 */
enum class ButtonPresence {
    UNKNOWN,
    PRESENT,
    ABSENT,
}

object ButtonPresencePrefs {
    private const val PREF = "button_presence"
    private const val KEY_VOLUME = "volume_rocker"
    private const val KEY_TRACK = "track_keys"
    private const val KEY_FREE_VOLUME = "free_volume"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun volumeRocker(ctx: Context): ButtonPresence =
        read(ctx, KEY_VOLUME)

    fun trackKeys(ctx: Context): ButtonPresence =
        read(ctx, KEY_TRACK)

    fun setVolumeRocker(ctx: Context, presence: ButtonPresence) {
        write(ctx, KEY_VOLUME, presence)
    }

    fun setTrackKeys(ctx: Context, presence: ButtonPresence) {
        write(ctx, KEY_TRACK, presence)
    }

    /** Call when a volume ▲/▼ event is observed — proves the rocker exists. */
    fun markVolumeSeen(ctx: Context) {
        if (volumeRocker(ctx) != ButtonPresence.PRESENT) {
            setVolumeRocker(ctx, ButtonPresence.PRESENT)
            LogBus.log("[BTN] volume rocker marked PRESENT (event seen)")
        }
    }

    /** Call when ◀/▶ / Select media keys are observed. */
    fun markTrackSeen(ctx: Context) {
        if (trackKeys(ctx) != ButtonPresence.PRESENT) {
            setTrackKeys(ctx, ButtonPresence.PRESENT)
            LogBus.log("[BTN] track keys marked PRESENT (event seen)")
        }
    }

    /** True when we should pin STREAM_MUSIC to hijack ▲/▼ as nav. */
    fun shouldPinVolume(ctx: Context): Boolean =
        !freeVolume(ctx) && volumeRocker(ctx) != ButtonPresence.ABSENT

    /**
     * Rider override: never pin the phone's music volume, so it can go to max and stays where it's
     * set. Handlebar ▲/▼ can no longer be used for Android Auto navigation while this is on.
     * Global (not per-bike).
     */
    fun freeVolume(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_FREE_VOLUME, true)

    fun setFreeVolume(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_FREE_VOLUME, on).apply()
    }

    /**
     * Rider override: hold the button audio focus for the whole ride (old behaviour). Handlebar
     * keys stick to Android Auto more reliably, but other apps' music stays ducked (~20%).
     * Off (default) = grab focus briefly, then release so music plays at full volume. Global.
     */
    fun holdFocus(ctx: Context): Boolean =
        prefs(ctx).getBoolean("hold_audio_focus", false)

    fun setHoldFocus(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("hold_audio_focus", on).apply()
    }

    fun summarize(ctx: Context): String {
        fun label(p: ButtonPresence) = when (p) {
            ButtonPresence.UNKNOWN -> "untested"
            ButtonPresence.PRESENT -> "works"
            ButtonPresence.ABSENT -> "absent"
        }
        return "▲/▼ ${label(volumeRocker(ctx))} · ◀/▶ ${label(trackKeys(ctx))}"
    }

    private fun read(ctx: Context, key: String): ButtonPresence {
        val raw = BikeScope.getString(prefs(ctx), ctx, key, null) ?: return ButtonPresence.UNKNOWN
        return try {
            ButtonPresence.valueOf(raw)
        } catch (_: Exception) {
            ButtonPresence.UNKNOWN
        }
    }

    private fun write(ctx: Context, key: String, presence: ButtonPresence) {
        BikeScope.putString(prefs(ctx), ctx, key, presence.name)
    }
}
