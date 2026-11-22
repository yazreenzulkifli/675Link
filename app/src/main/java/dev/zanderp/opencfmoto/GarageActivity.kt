// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

/**
 * The garage: manage every remembered motorcycle. Pick the active bike (one-tap Connect uses it),
 * rename it, give it a photo, or remove it. Each bike carries its own projection + button settings
 * (see [BikeScope]); switching the active bike here switches those too.
 */
class GarageActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var empty: TextView

    /** The bike a pending photo pick is for (photo picker is async). */
    private var photoTargetRaw: String? = null

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val raw = result.data?.getStringExtra(QrScanActivity.RESULT_QR)
        if (result.resultCode != RESULT_OK || raw == null) return@registerForActivityResult
        val qr = QrData.parse(raw)
        if (qr == null) {
            Toast.makeText(this, "Invalid QR", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        BikeMemory.save(this, raw, qr)
        Toast.makeText(this, "Added ${BikeMemory.lastBikeName(this)}", Toast.LENGTH_SHORT).show()
        refresh()
    }

    private val photoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val raw = photoTargetRaw ?: return@registerForActivityResult
        photoTargetRaw = null
        if (uri == null) return@registerForActivityResult
        val path = importPhoto(uri, raw)
        if (path != null) {
            BikeMemory.setPhoto(this, raw, path)
            refresh()
        } else {
            Toast.makeText(this, "Couldn't read that image", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_garage)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.garage_root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        findViewById<View>(R.id.garage_back).setOnClickListener { finish() }
        container = findViewById(R.id.garage_container)
        empty = findViewById(R.id.garage_empty)
        findViewById<View>(R.id.garage_scan).setOnClickListener {
            scanLauncher.launch(Intent(this, QrScanActivity::class.java))
        }
        findViewById<View>(R.id.garage_manual).setOnClickListener {
            ManualWifiPairing.show(this) { raw, qr ->
                BikeMemory.save(this, raw, qr)
                Toast.makeText(this, "Added ${BikeMemory.lastBikeName(this)}", Toast.LENGTH_SHORT)
                    .show()
                refresh()
            }
        }
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.garage_show_dummy).apply {
            val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            visibility = if (debuggable) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, on ->
                if (on == DummyBike.inGarage(this@GarageActivity)) return@setOnCheckedChangeListener
                DummyBike.setInGarage(this@GarageActivity, on)
                refresh()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        container.removeAllViews()
        val bikes = BikeMemory.devices(this)
        val selected = BikeMemory.selected(this)
        empty.visibility = if (bikes.isEmpty()) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.garage_paired_count).text =
            getString(R.string.garage_paired_count, bikes.size)
        for (bike in bikes) container.addView(buildCard(bike, bike.raw == selected?.raw))
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.garage_show_dummy).isChecked =
            bikes.any { it.raw == DummyBike.QR_RAW }
    }

    private fun buildCard(bike: SavedBike, isActive: Boolean): View {
        val ctx = this
        val cyan = ContextCompat.getColor(ctx, R.color.home_cyan)
        val card = android.widget.FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
            setBackgroundResource(R.drawable.garage_card_bg)
            elevation = dp(2).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { showActions(bike, isActive) }
        }
        if (isActive) {
            card.addView(View(ctx).apply {
                setBackgroundResource(R.drawable.garage_accent_bar)
            }, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4)
            ).apply { marginStart = dp(24); marginEnd = dp(24) })
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(16))
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setBackgroundResource(R.drawable.garage_avatar_bg)
            clipToOutline = true
            val bmp = bike.photoPath?.let { loadPhoto(it) }
            if (bmp != null) {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(bmp)
            } else {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageResource(R.drawable.ic_ride)
                setColorFilter(cyan)
                setPadding(dp(11), dp(11), dp(11), dp(11))
            }
        })

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(14); marginEnd = dp(8) }
        }
        col.addView(TextView(ctx).apply {
            text = bike.name
            setTextColor(ContextCompat.getColor(ctx, R.color.home_text))
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        col.addView(TextView(ctx).apply {
            text = (bike.qr?.ssid ?: bike.raw).uppercase()
            setTextColor(0xFF94A3B8.toInt())
            textSize = 11f
            letterSpacing = 0.08f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(col)

        row.addView(TextView(ctx).apply {
            text = getString(if (isActive) R.string.garage_active else R.string.garage_paired)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            compoundDrawablePadding = dp(6)
            if (isActive) {
                setBackgroundResource(R.drawable.garage_pill_active)
                setTextColor(0xFF059669.toInt())
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.garage_dot_mint, 0, 0, 0)
            } else {
                setBackgroundResource(R.drawable.garage_pill_muted)
                setTextColor(0xFF64748B.toInt())
            }
        })
        content.addView(row)

        val edit = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }
        edit.addView(TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = getString(if (isActive) R.string.garage_tap_to_edit else R.string.garage_tap_to_select)
            setTextColor(0xFF94A3B8.toInt())
            textSize = 12f
        })
        edit.addView(ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            setBackgroundResource(R.drawable.garage_chevron_bg)
            setImageResource(R.drawable.ic_trip_chevron)
            setColorFilter(0xFF94A3B8.toInt())
            setPadding(dp(4), dp(4), dp(4), dp(4))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        content.addView(edit)

        card.addView(content)
        return card
    }

    private fun showActions(bike: SavedBike, isActive: Boolean) {
        val hasPhoto = bike.photoPath != null
        val items = buildList {
            if (!isActive) add("Use this bike")
            add("Rename")
            add(if (hasPhoto) "Change photo" else "Add photo")
            if (hasPhoto) add("Remove photo")
            add("Remove bike")
        }.toTypedArray()

        GlassAlertBuilder(this)
            .setTitle(bike.name)
            .setItems(items) { _, which ->
                when (items[which]) {
                    "Use this bike" -> {
                        BikeMemory.select(this, bike.raw)
                        Toast.makeText(this, "Selected ${bike.name}", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                    "Rename" -> promptRename(bike)
                    "Add photo", "Change photo" -> {
                        photoTargetRaw = bike.raw
                        photoLauncher.launch("image/*")
                    }
                    "Remove photo" -> {
                        deletePhotoFile(bike.photoPath)
                        BikeMemory.setPhoto(this, bike.raw, null)
                        refresh()
                    }
                    "Remove bike" -> confirmRemove(bike)
                }
            }
            .show()
    }

    private fun promptRename(bike: SavedBike) {
        val input = EditText(this).apply {
            setText(bike.name)
            setSelection(text.length)
            hint = "Bike name"
        }
        val pad = dp(20)
        val wrap = LinearLayout(this).apply {
            setPadding(pad, dp(8), pad, 0)
            addView(input)
        }
        GlassAlertBuilder(this)
            .setTitle("Rename bike")
            .setView(wrap)
            .setPositiveButton("Save") { _, _ ->
                BikeMemory.rename(this, bike.raw, input.text.toString())
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRemove(bike: SavedBike) {
        GlassAlertBuilder(this)
            .setTitle("Remove ${bike.name}?")
            .setMessage("This forgets its pairing, photo and settings. You can re-scan the dash QR or enter Wi‑Fi again later.")
            .setPositiveButton("Remove") { _, _ ->
                deletePhotoFile(bike.photoPath)
                BikeMemory.remove(this, bike.raw)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- photo storage: downscale into app-private files so we don't hold a content Uri permission ----

    private fun photosDir(): File = File(filesDir, "bike_photos").apply { mkdirs() }

    private fun importPhoto(uri: Uri, raw: String): String? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, 720)
        }
        val bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        val out = File(photosDir(), BikeScope.idFor(raw) + ".jpg")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        out.absolutePath
    } catch (_: Exception) {
        null
    }

    private fun loadPhoto(path: String): Bitmap? =
        runCatching { BitmapFactory.decodeFile(path) }.getOrNull()

    private fun deletePhotoFile(path: String?) {
        if (path != null) runCatching { File(path).delete() }
    }

    private fun sampleSizeFor(w: Int, h: Int, target: Int): Int {
        var sample = 1
        var mx = maxOf(w, h)
        while (mx > target * 2) { sample *= 2; mx /= 2 }
        return sample
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, GarageActivity::class.java))
        }
    }
}
