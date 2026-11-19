// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

/**
 * "Keep my music volume": the bike's TFT sets the phone's music volume over Bluetooth (AVRCP
 * absolute volume) when it connects — often quite low. Apps can't switch that off (only the
 * developer option "Disable absolute volume" can), so we undo it: remember the rider's level and
 * put it back whenever a Bluetooth audio device connects, reverting any change for
 * [GUARD_MS] afterwards. Outside that window a volume change is the rider's own and is remembered.
 *
 * Stands aside while handlebar capture is on — [MediaButtonBridge] then owns the music volume.
 */
object VolumeKeeper {
    private const val PREFS = "opencfmoto_volume_keeper"
    private const val KEY_ON = "keep_volume"
    private const val KEY_LEVEL = "level"
    /** How long after a Bluetooth audio connect we treat volume changes as the bike's, not the rider's. */
    private const val GUARD_MS = 10_000L

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var audio: AudioManager? = null
    private var observer: ContentObserver? = null
    private var deviceCallback: AudioDeviceCallback? = null
    @Volatile private var guardUntil = 0L
    @Volatile private var ignoreUntil = 0L
    private var lastSeen = -1

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ON, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ON, on).apply()
        if (on) audio?.let { a -> remember(ctx, a.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    }

    /** The rider chose [level] (home/Controls slider, or phone keys outside the guard window). */
    fun remember(ctx: Context, level: Int) {
        if (level < 0) return
        prefs(ctx).edit().putInt(KEY_LEVEL, level).apply()
    }

    private fun saved(ctx: Context): Int = prefs(ctx).getInt(KEY_LEVEL, -1)

    private fun active(ctx: Context) = enabled(ctx) && !ButtonMode.isControlAa(ctx)

    fun start(context: Context) {
        if (observer != null) return
        val ctx = context.applicationContext
        appContext = ctx
        val a = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio = a
        lastSeen = a.getStreamVolume(AudioManager.STREAM_MUSIC)
        // First run with nothing saved: take the current level unless the bike already set it.
        if (saved(ctx) < 0 && !bluetoothAudioConnected(a)) remember(ctx, lastSeen)

        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = onVolumeChanged()
        }
        try {
            ctx.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, obs)
            observer = obs
        } catch (e: Exception) {
            LogBus.log("[VOL] observer failed: $e")
        }
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                if (added.any { it.isSink && isBluetoothAudio(it) }) onBikeAudioConnected("Bluetooth audio connected")
            }
        }
        // Fires once for devices already present — covers a bike that connected before the app.
        a.registerAudioDeviceCallback(cb, handler)
        deviceCallback = cb
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        observer?.let { o -> try { appContext?.contentResolver?.unregisterContentObserver(o) } catch (_: Exception) {} }
        observer = null
        deviceCallback?.let { cb -> try { audio?.unregisterAudioDeviceCallback(cb) } catch (_: Exception) {} }
        deviceCallback = null
    }

    private fun onBikeAudioConnected(reason: String) {
        val ctx = appContext ?: return
        if (!active(ctx) || saved(ctx) < 0) return
        guardUntil = SystemClock.elapsedRealtime() + GUARD_MS
        LogBus.log("[VOL] $reason — keeping music at ${saved(ctx)} for ${GUARD_MS / 1000}s (bike may try to set it)")
        // The dash usually pushes its level a moment after the link comes up; restore a few times.
        for (delay in longArrayOf(0L, 1_500L, 4_000L)) handler.postDelayed({ restore("connect") }, delay)
    }

    private fun onVolumeChanged() {
        val ctx = appContext ?: return
        val a = audio ?: return
        val now = try { a.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { return }
        if (now == lastSeen) return
        lastSeen = now
        if (!active(ctx) || SystemClock.elapsedRealtime() < ignoreUntil) return
        if (SystemClock.elapsedRealtime() < guardUntil) {
            if (now != saved(ctx)) {
                LogBus.log("[VOL] bike set music volume to $now — putting it back to ${saved(ctx)}")
                restore("bike-set")
            }
        } else {
            remember(ctx, now)   // the rider's own change (phone keys / notification shade)
        }
    }

    private fun restore(reason: String) {
        val ctx = appContext ?: return
        val a = audio ?: return
        if (!active(ctx)) return
        val want = saved(ctx)
        if (want < 0) return
        val now = try { a.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { return }
        if (now == want) return
        try {
            ignoreUntil = SystemClock.elapsedRealtime() + 300
            a.setStreamVolume(AudioManager.STREAM_MUSIC, want.coerceIn(0, a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0)
            lastSeen = want
            if (reason == "connect") LogBus.log("[VOL] music volume restored $now → $want")
        } catch (e: Exception) {
            LogBus.log("[VOL] restore failed: $e")
        }
    }

    private fun isBluetoothAudio(d: AudioDeviceInfo): Boolean = when (d.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> true
        else -> false
    }

    private fun bluetoothAudioConnected(a: AudioManager): Boolean =
        try { a.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any(::isBluetoothAudio) } catch (_: Exception) { false }
}
