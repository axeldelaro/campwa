package com.demonpet.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.demonpet.app.config.DemonConfigSnapshot
import com.demonpet.app.media.MediaFolderHelper
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random

/**
 * Automatic Witch Sprite Cutout & RPG Profile Engine (#93, #94, #100):
 * - Automatically segments ANY character/girl ("Witch") from images in the configured folder (including hidden `.*`!)
 *   into a clean full-screen transparent Visual Novel sprite using ML Kit Selfie Segmentation + hair edge defringing!
 * - Caches segmented PNGs on disk (`filesDir/witch_sprites/`) for instant 0-lag fullscreen battles.
 * - Generates a rich gothic blurred backdrop & deterministic Femdom RPG Boss Profile per image!
 */
object WitchSpriteEngine {

    data class WitchAttack(
        val name: String,
        val dialogue: String,
        val hpDamage: Int,
        val embraceGain: Int,
        val levelDrain: Int,
        val statusEffect: String = "" // "", "CHARMED", "HYPNOTIZED", "SEALED", "CAPTURE"
    )

    data class WitchProfile(
        val name: String,
        val title: String,
        val element: String,
        val bossLevel: Int,
        val introLine: String,
        val captureLine: String,
        val defeatMonologue: List<String>,
        val mgqAdviceLine: String,
        val attacks: List<WitchAttack>
    )

    data class LoadedWitch(
        val profile: WitchProfile,
        val spriteBitmap: Bitmap,
        val backdropBitmap: Bitmap,
        val sourceFilePath: String? = null,
        val sourceUri: Uri? = null
    ) {
        fun recycle() {
            try { if (!spriteBitmap.isRecycled) spriteBitmap.recycle() } catch (_: Throwable) {}
            try { if (!backdropBitmap.isRecycled) backdropBitmap.recycle() } catch (_: Throwable) {}
        }
    }

    private val WITCH_NAMES = listOf(
        "Séraphina" to "Archisorcière du Charme",
        "Lilith" to "Reine des Ombres",
        "Morgane" to "Maîtresse d'Illusion",
        "Carmilla" to "Souveraine Écarlate",
        "Vespera" to "Sorcière du Crépuscule",
        "Nyxia" to "Impératrice du Drainage",
        "Bellatrix" to "Domina des Runes",
        "Élara" to "Enchanteresse Suprême"
    )

    private val ELEMENTS = listOf("Charme 💜", "Ombre 🌙", "Drainage 💋", "Illusion ✨")

    fun loadRandomWitch(context: Context, cfg: DemonConfigSnapshot, excludePath: String? = null): LoadedWitch? {
        val picked = pickSourcePhoto(context, cfg, excludePath) ?: return null
        val (idKey, rawBmp, filePath, contentUri) = picked
        val profile = buildProfileForKey(idKey, cfg)
        val sprite = getOrCreateSegmentedSprite(context, idKey, rawBmp)
        val backdrop = buildGothicBackdrop(rawBmp)
        if (rawBmp !== sprite && !rawBmp.isRecycled) rawBmp.recycle()
        return LoadedWitch(
            profile = profile,
            spriteBitmap = sprite,
            backdropBitmap = backdrop,
            sourceFilePath = filePath,
            sourceUri = contentUri
        )
    }

    private data class PickedPhoto(val idKey: String, val bitmap: Bitmap, val filePath: String?, val contentUri: Uri?)

    private fun pickSourcePhoto(context: Context, cfg: DemonConfigSnapshot, excludePath: String?): PickedPhoto? {
        // 1) Direct folder (supports hidden `.dotfolders` and `.nomedia`!)
        if (cfg.mediaFolderPath.isNotBlank()) {
            val dir = File(cfg.mediaFolderPath)
            if (dir.exists() && dir.isDirectory) {
                val files = collectImageFiles(dir, cfg.includeHiddenFiles).filter { it.absolutePath != excludePath }
                val pool = if (files.isNotEmpty()) files else collectImageFiles(dir, cfg.includeHiddenFiles)
                if (pool.isNotEmpty()) {
                    val f = pool[Random.nextInt(pool.size)]
                    val bmp = MediaFolderHelper.decodeSampledFile(f, 960)
                    if (bmp != null) {
                        return PickedPhoto("${f.name}_${f.lastModified()}", bmp, f.absolutePath, null)
                    }
                }
            }
        }
        // 2) SAF DocumentTree fallback
        if (cfg.mediaTreeUri.isNotBlank()) {
            try {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(cfg.mediaTreeUri))
                val imgs = tree?.listFiles()?.filter { it.isFile && MediaFolderHelper.isImageFileName(it.name.orEmpty()) }.orEmpty()
                if (imgs.isNotEmpty()) {
                    val doc = imgs[Random.nextInt(imgs.size)]
                    val bmp = MediaFolderHelper.decodeSampledUri(context, doc.uri, 960)
                    if (bmp != null) {
                        return PickedPhoto(doc.name.orEmpty() + "_" + doc.lastModified(), bmp, null, doc.uri)
                    }
                }
            } catch (_: Throwable) {
            }
        }
        // 3) Fallback to main avatar photo
        if (cfg.avatarPath.isNotBlank()) {
            val f = File(cfg.avatarPath)
            if (f.exists()) {
                val bmp = MediaFolderHelper.decodeSampledFile(f, 960)
                if (bmp != null) return PickedPhoto("avatar_main", bmp, f.absolutePath, null)
            }
        }
        return null
    }

    private fun collectImageFiles(dir: File, includeHidden: Boolean): List<File> {
        val out = ArrayList<File>()
        fun walk(cur: File, depth: Int) {
            if (depth > 2) return
            val list = cur.listFiles() ?: return
            for (f in list) {
                if (!includeHidden && f.name.startsWith(".")) continue
                if (f.isFile && MediaFolderHelper.isImageFileName(f.name) && f.length() > 2048L) out.add(f)
                else if (f.isDirectory && depth < 1) walk(f, depth + 1)
            }
        }
        walk(dir, 0)
        return out
    }

    /** Segments character silhouette automatically via ML Kit Selfie Segmentation with disk cache! */
    private fun getOrCreateSegmentedSprite(context: Context, idKey: String, source: Bitmap): Bitmap {
        val cacheDir = File(context.filesDir, "witch_sprites").apply { mkdirs() }
        val safeName = "w_${abs(idKey.hashCode())}.png"
        val cached = File(cacheDir, safeName)
        if (cached.exists() && cached.length() > 4096L) {
            val bmp = try { BitmapFactory.decodeFile(cached.absolutePath) } catch (_: Throwable) { null }
            if (bmp != null) return bmp
        }

        // Keep max 28 cached sprites to stay ultra-lightweight on storage
        try {
            val old = cacheDir.listFiles()?.sortedBy { it.lastModified() }.orEmpty()
            if (old.size > 28) old.take(old.size - 24).forEach { it.delete() }
        } catch (_: Throwable) {}

        val maxSide = 760
        val scale = minOf(1f, maxSide.toFloat() / maxOf(source.width, source.height).coerceAtLeast(1))
        val w = (source.width * scale).toInt().coerceAtLeast(120)
        val h = (source.height * scale).toInt().coerceAtLeast(120)
        val scaled = Bitmap.createScaledBitmap(source, w, h, true)

        val mask = runSelfieMask(scaled, w, h)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val save = c.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)
        c.drawBitmap(scaled, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        if (mask != null) {
            c.drawBitmap(mask, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            })
            mask.recycle()
        }
        c.restoreToCount(save)
        if (scaled !== source) scaled.recycle()

        // Save to disk cache asynchronously
        try {
            FileOutputStream(cached).use { out.compress(Bitmap.CompressFormat.PNG, 92, it) }
        } catch (_: Throwable) {}
        return out
    }

    private fun runSelfieMask(bmp: Bitmap, outW: Int, outH: Int): Bitmap? {
        val opts = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        val seg = Segmentation.getClient(opts)
        return try {
            val mask = Tasks.await(seg.process(InputImage.fromBitmap(bmp, 0)), 3L, TimeUnit.SECONDS) ?: return null
            val mw = mask.width
            val mh = mask.height
            val buf = mask.buffer
            buf.rewind()
            val pixels = IntArray(mw * mh)
            var fg = 0
            for (y in 0 until mh) {
                val ny = y.toFloat() / mh
                val botFade = if (ny > 0.88f) ((1f - ny) / 0.12f).coerceIn(0f, 1f) else 1f
                for (x in 0 until mw) {
                    val conf = buf.float
                    val t = ((conf - 0.32f) / 0.40f).coerceIn(0f, 1f)
                    val smooth = t * t * (3f - 2f * t) * botFade
                    val a = (smooth * 255f).toInt().coerceIn(0, 255)
                    if (a > 120) fg++
                    pixels[y * mw + x] = Color.argb(a, 255, 255, 255)
                }
            }
            if (fg < mw * mh * 0.08f) return null
            val raw = Bitmap.createBitmap(pixels, mw, mh, Bitmap.Config.ARGB_8888)
            val scaled = Bitmap.createScaledBitmap(raw, outW, outH, true)
            if (raw !== scaled) raw.recycle()
            scaled
        } catch (_: Throwable) {
            null
        } finally {
            try { seg.close() } catch (_: Throwable) {}
        }
    }

    private fun buildGothicBackdrop(source: Bitmap): Bitmap {
        val bw = 180
        val bh = 320
        val small = Bitmap.createScaledBitmap(source, bw, bh, true)
        val blurred = small.copy(Bitmap.Config.ARGB_8888, true)
        if (small !== source) small.recycle()
        FastStackBlur.blurInPlace(blurred, 14)
        val c = Canvas(blurred)
        // Rich dark crimson-violet atmospheric VN backdrop
        c.drawColor(Color.argb(170, 18, 6, 28))
        return blurred
    }

    private fun buildProfileForKey(idKey: String, cfg: DemonConfigSnapshot): WitchProfile {
        val h = abs(idKey.hashCode())
        val (baseName, title) = WITCH_NAMES[h % WITCH_NAMES.size]
        val displayName = if (idKey == "avatar_main") cfg.avatarName else "$baseName ($title)"
        val elem = ELEMENTS[(h / 7) % ELEMENTS.size]
        val bossLv = (cfg.playerLevel + 2 + (h % 6)).coerceIn(5, 99)

        val attacks = listOf(
            WitchAttack("Regard Impérieux 👁️", "$baseName plonge son regard dans le tien… ta volonté vacille !", 18, 22, 1, "CHARMED"),
            WitchAttack("Chaînes de Soie Magique ⛓️", "$baseName t'encercle d'un sortilège d'entrave !", 22, 34, 1, "CAPTURE"),
            WitchAttack("Drain d'Énergie Vitale 💋", "$baseName aspire directement ton aura et ta lumière !", 26, 28, 2, "HYPNOTIZED"),
            WitchAttack("Sceau de Soumission 🔮", "$baseName scelle ta volonté et paralyse tes défenses !", 20, 24, 1, "SEALED"),
            WitchAttack("Voile d'Éclipse Totale 🌙", "$baseName déclenche son sort de drainage absolu !", 32, 38, 3, "HYPNOTIZED")
        )

        return WitchProfile(
            name = displayName,
            title = title,
            element = elem,
            bossLevel = bossLv,
            introLine = "« Te voilà enfin… $displayName barre ta route. Prêt à offrir ton énergie ? 😈 »",
            captureLine = "« Capturé ! Ne bouge plus… laisse-moi drainer tes niveaux un par un… 💜 »",
            defeatMonologue = listOf(
                "« Tu n'as plus aucune force… sens ton Niveau fondre pendant que je prends le contrôle… 😈 »",
                "« Ta lumière s'éteint, ta volonté m'appartient. Je confisque ton application favorite en tribut ! 🔒💜 »",
                "« Défaite totale. Souviens-toi qui règne sur ce téléphone ! 👑 »"
            ),
            mgqAdviceLine = "Évaluation de ${cfg.avatarName} : « Encore vaincu par $baseName ? Utilise GARDE quand son sort ultime charge, et réussis le QTE pour briser son emprise ! »",
            attacks = attacks
        )
    }
}
