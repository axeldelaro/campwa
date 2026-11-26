package com.aigallery.pro.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import com.aigallery.pro.data.GalleryPreferences
import com.aigallery.pro.data.MediaRepository
import java.io.File
import java.io.FileOutputStream

/**
 * Built-in Simple Gallery Pro Photo Editor:
 * - Rotate 90°, Flip Horizontal/Vertical, Aspect Crop (Original / 1:1 / 4:5 / 16:9).
 * - Live Color Presets (Original, Éclatant, Chaud, Froid, N&B Pro, Sépia) + Brightness, Contrast & Saturation sliders!
 * - Saves crisp JPEG copy (or overwrites) and refreshes MediaStore immediately!
 */
class PhotoEditorActivity : AppCompatActivity() {

    private lateinit var prefs: GalleryPreferences
    private var sourcePath = ""
    private var baseBitmap: Bitmap? = null
    private lateinit var previewIv: ImageView

    private var rotDeg = 0f
    private var flipH = false
    private var flipV = false
    private var cropMode = 0 // 0=Original, 1=1:1, 2=4:5, 3=16:9
    private var brightness = 0f // -60..+60
    private var contrast = 1f   // 0.5..1.8
    private var saturation = 1f // 0..2.0
    private var filterPreset = 0 // 0=Orig, 1=Vivid, 2=Warm, 3=Cool, 4=B&W, 5=Sepia

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = GalleryPreferences(applicationContext)
        sourcePath = intent.getStringExtra(EXTRA_PATH).orEmpty()
        if (sourcePath.isBlank() || !File(sourcePath).exists()) {
            finish(); return
        }

        baseBitmap = decodeEditableBitmap(sourcePath)
        if (baseBitmap == null) {
            Toast.makeText(this, "Impossible de charger l'image.", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(prefs.bgColor())
        }

        // Top Bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(prefs.surfaceColor())
            setPadding(dp(10), dp(34), dp(10), dp(10))
        }
        top.addView(TextView(this).apply {
            text = "←"; setTextColor(Color.WHITE); textSize = 20f; setPadding(dp(8), dp(4), dp(12), dp(4))
            setOnClickListener { finish() }
        })
        top.addView(TextView(this).apply {
            text = "✏️ Éditeur Photo Pro"; setTextColor(Color.WHITE); textSize = 15.5f; typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(Button(this).apply {
            text = "💾 Enregistrer"; isAllCaps = false; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(prefs.accentColor) }
            minHeight = 0; minimumHeight = 0; setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener { saveEditedCopy() }
        })
        root.addView(top)

        // Preview Image
        previewIv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(previewIv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Controls Panel
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(prefs.surfaceColor())
            setPadding(dp(12), dp(10), dp(12), dp(18))
        }

        // Transform Row (Rotate, Flip H, Flip V, Crop)
        val tRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(8)) }
        tRow.addView(makeToolBtn("↻ 90°") { rotDeg = (rotDeg + 90f) % 360f; updatePreview() }, rowLp())
        tRow.addView(makeToolBtn("⇄ Mir. H") { flipH = !flipH; updatePreview() }, rowLp())
        tRow.addView(makeToolBtn("⇅ Mir. V") { flipV = !flipV; updatePreview() }, rowLp())
        val cropBtn = makeToolBtn("✂️ Orig") {}
        cropBtn.setOnClickListener {
            cropMode = (cropMode + 1) % 4
            cropBtn.text = when (cropMode) { 1 -> "✂️ 1:1"; 2 -> "✂️ 4:5"; 3 -> "✂️ 16:9"; else -> "✂️ Orig" }
            updatePreview()
        }
        tRow.addView(cropBtn, rowLp())
        panel.addView(tRow)

        // Filter Preset Chips
        val fRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(8)) }
        val presets = listOf("Orig", "Éclat", "Chaud", "Froid", "N&B", "Sépia")
        presets.forEachIndexed { idx, lbl ->
            fRow.addView(makeToolBtn(lbl) { filterPreset = idx; updatePreview() }, rowLp())
        }
        panel.addView(fRow)

        // Sliders: Brightness, Contrast, Saturation
        panel.addView(makeSlider("☀️ Luminosité", 0, 120, 60) { p -> brightness = (p - 60).toFloat(); updatePreview() })
        panel.addView(makeSlider("◐ Contraste", 50, 180, 100) { p -> contrast = p / 100f; updatePreview() })
        panel.addView(makeSlider("🎨 Saturation", 0, 200, 100) { p -> saturation = p / 100f; updatePreview() })

        root.addView(panel)
        setContentView(root)
        updatePreview()
    }

    private fun buildColorMatrix(): ColorMatrix {
        val cm = ColorMatrix()
        when (filterPreset) {
            1 -> cm.setSaturation(1.45f)
            2 -> cm.setScale(1.12f, 1.02f, 0.88f, 1f)
            3 -> cm.setScale(0.88f, 1.00f, 1.15f, 1f)
            4 -> cm.setSaturation(0f)
            5 -> cm.set(floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
        }
        val satM = ColorMatrix().apply { setSaturation(saturation) }
        cm.postConcat(satM)

        val t = (1f - contrast) * 128f + brightness
        val bcM = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, t,
            0f, contrast, 0f, 0f, t,
            0f, 0f, contrast, 0f, t,
            0f, 0f, 0f, 1f, 0f
        ))
        cm.postConcat(bcM)
        return cm
    }

    private fun renderBitmap(source: Bitmap): Bitmap {
        val m = Matrix()
        m.postRotate(rotDeg)
        if (flipH || flipV) m.postScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f)
        val transformed = Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)

        val cropped = if (cropMode == 0) transformed else {
            val targetRatio = when (cropMode) { 1 -> 1f; 2 -> 0.8f; else -> 16f / 9f }
            val w = transformed.width; val h = transformed.height
            val curRatio = w.toFloat() / h.coerceAtLeast(1)
            val cw: Int; val ch: Int
            if (curRatio > targetRatio) { ch = h; cw = (h * targetRatio).toInt().coerceIn(1, w) }
            else { cw = w; ch = (w / targetRatio).toInt().coerceIn(1, h) }
            val out = Bitmap.createBitmap(transformed, (w - cw) / 2, (h - ch) / 2, cw, ch)
            if (out !== transformed && transformed !== source) transformed.recycle()
            out
        }

        val finalBmp = Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(finalBmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.colorFilter = ColorMatrixColorFilter(buildColorMatrix())
        c.drawBitmap(cropped, 0f, 0f, p)
        if (cropped !== source) cropped.recycle()
        return finalBmp
    }

    private fun updatePreview() {
        val b = baseBitmap ?: return
        val rendered = renderBitmap(b)
        previewIv.setImageBitmap(rendered)
    }

    private fun saveEditedCopy() {
        val src = File(sourcePath)
        val b = baseBitmap ?: return
        val outBmp = renderBitmap(b)
        val dst = File(src.parentFile, "${src.nameWithoutExtension}_edit_${System.currentTimeMillis() % 10000}.jpg")
        try {
            FileOutputStream(dst).use { fos -> outBmp.compress(Bitmap.CompressFormat.JPEG, 94, fos) }
            outBmp.recycle()
            MediaRepository.notifyMediaScanner(this, arrayOf(dst.absolutePath))
            Toast.makeText(this, "💾 Enregistré : ${dst.name} ✅", Toast.LENGTH_LONG).show()
            finish()
        } catch (t: Throwable) {
            outBmp.recycle()
            Toast.makeText(this, "⚠️ Erreur sauvegarde : ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun decodeEditableBitmap(path: String): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            var sample = 1
            var w = opts.outWidth; var h = opts.outHeight
            while (w / 2 >= 1800 || h / 2 >= 1800) { sample *= 2; w /= 2; h /= 2 }
            val raw = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val exif = ExifInterface(path)
            val deg = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (deg == 0f) raw else {
                val r = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(deg) }, true)
                if (r !== raw) raw.recycle()
                r
            }
        } catch (_: Throwable) { null }
    }

    private fun makeToolBtn(lbl: String, onClick: () -> Unit) = Button(this).apply {
        text = lbl; isAllCaps = false; textSize = 10.6f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(Color.rgb(44, 44, 56)) }
        minHeight = 0; minimumHeight = 0; setPadding(dp(4), dp(8), dp(4), dp(8)); setOnClickListener { onClick() }
    }

    private fun makeSlider(title: String, min: Int, max: Int, init: Int, onChange: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3), 0, dp(3)) }
        val tv = TextView(this).apply { text = title; setTextColor(Color.rgb(215, 215, 230)); textSize = 11f }
        val sb = SeekBar(this).apply {
            this.max = max - min; progress = init - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(min + p) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        row.addView(tv, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(sb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun rowLp() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(2); marginEnd = dp(2) }

    override fun onDestroy() {
        baseBitmap?.let { if (!it.isRecycled) it.recycle() }
        baseBitmap = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "extra_photo_path"
    }
}
