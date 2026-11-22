package com.demonpet.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.demonpet.app.config.DemonConfigSnapshot
import com.demonpet.app.media.MediaFolderHelper
import com.demonpet.app.rpg.WitchCatalog
import com.demonpet.app.rpg.WitchRpgState
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
 * Automatic Witch Sprite Cutout & 50-Witch RPG Profile Engine (v5.0):
 * - Automatically segments ANY photo from the user's chosen folder (including hidden `.*` & `.nomedia`!)
 *   into a clean fullscreen transparent Visual Novel sprite using ML Kit Selfie Segmentation!
 * - Binds any of the 50 unique Witches from [WitchCatalog] to a randomly picked HD sprite + gothic backdrop.
 * - Caches segmented PNGs on disk (`filesDir/witch_sprites/`) for instant 0-lag fullscreen battles.
 */
object WitchSpriteEngine {

    enum class AttackKind {
        STRIKE,
        ENCHANT_CHARM,
        ENCHANT_HYPNO,
        BIND_CAPTURE,
        SEAL_MAGIC,
        SIPHON_DRAIN,
        BARRIER_SHIELD,
        MIRAGE_CLONE,
        TIME_DOUBLE,
        ULTIMATE
    }

    data class WitchAttack(
        val name: String,
        val hpDamage: Int,
        val embraceGain: Int,
        val levelDrain: Int,
        val statusEffect: String = "", // "", "CHARMED", "HYPNOTIZED", "SEALED", "CAPTURE", "SIPHON"
        val kind: AttackKind = AttackKind.STRIKE,
        val telegraphHint: String = "⚔️ Prépare une attaque de ${""}"
    )

    data class WitchProfile(
        val def: WitchCatalog.WitchDef,
        val name: String,
        val title: String,
        val element: String,
        val bossLevel: Int,
        val maxResolve: Int,
        val mgqAdviceLine: String,
        val attacks: List<WitchAttack>,
        val ultimateAttack: WitchAttack
    )

    data class LoadedWitch(
        val def: WitchCatalog.WitchDef,
        val profile: WitchProfile,
        val spriteBitmap: Bitmap,
        val backdropBitmap: Bitmap,
        val sourceFilePath: String? = null,
        val sourceUri: Uri? = null,
        val isPlaceholderSprite: Boolean = false
    ) {
        fun recycle() {
            // Intentionally no manual Bitmap.recycle() here: Android 14-16 HWUI RenderThread may still
            // hold a DisplayList reference for 1-2 frames; letting NativeAllocationRegistry GC collect
            // prevents all "Canvas: trying to use a recycled bitmap" / SIGABRT crashes during end scenes!
        }
    }

    fun loadRandomWitch(context: Context, cfg: DemonConfigSnapshot, excludePath: String? = null): LoadedWitch {
        val rpg = WitchRpgState(context)
        val def = WitchCatalog.randomWitchForChapter(rpg.selectedChapter, includeBoss = false)
        return loadWitchForDef(context, cfg, def, excludePath)
    }

    fun loadWitchForDef(
        context: Context,
        cfg: DemonConfigSnapshot,
        def: WitchCatalog.WitchDef,
        excludePath: String? = null
    ): LoadedWitch {
        val picked = pickSourcePhoto(context, cfg, excludePath)
        val profile = buildProfileForDef(def, cfg)

        if (picked == null) {
            // Graceful dark-fantasy procedural Witch silhouette if the user hasn't picked a folder yet
            val (sprite, backdrop) = buildFallbackWitchArtwork(def)
            return LoadedWitch(
                def = def,
                profile = profile,
                spriteBitmap = sprite,
                backdropBitmap = backdrop,
                sourceFilePath = null,
                sourceUri = null,
                isPlaceholderSprite = true
            )
        }

        val (idKey, rawBmp, filePath, contentUri) = picked
        val sprite = getOrCreateSegmentedSprite(context, idKey, rawBmp)
        val backdrop = buildGothicBackdrop(rawBmp, def.archetype.accentHex)
        if (rawBmp !== sprite && !rawBmp.isRecycled) rawBmp.recycle()
        return LoadedWitch(
            def = def,
            profile = profile,
            spriteBitmap = sprite,
            backdropBitmap = backdrop,
            sourceFilePath = filePath,
            sourceUri = contentUri,
            isPlaceholderSprite = false
        )
    }

    private data class PickedPhoto(val idKey: String, val bitmap: Bitmap, val filePath: String?, val contentUri: Uri?)

    private fun pickSourcePhoto(context: Context, cfg: DemonConfigSnapshot, excludePath: String?): PickedPhoto? {
        // 1) Direct folder (supports hidden `.dotfolders` and `.nomedia`!)
        if (cfg.mediaFolderPath.isNotBlank()) {
            val dir = File(cfg.mediaFolderPath)
            if (dir.exists() && dir.isDirectory) {
                val all = collectImageFiles(dir, cfg.includeHiddenFiles)
                val filtered = all.filter { it.absolutePath != excludePath }
                val pool = if (filtered.isNotEmpty()) filtered else all
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

        // Keep max 40 cached sprites for instant reuse across the 50 Witches
        try {
            val old = cacheDir.listFiles()?.sortedBy { it.lastModified() }.orEmpty()
            if (old.size > 40) old.take(old.size - 34).forEach { it.delete() }
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

    private fun buildGothicBackdrop(source: Bitmap, accentColor: Int): Bitmap {
        val bw = 180
        val bh = 320
        val small = Bitmap.createScaledBitmap(source, bw, bh, true)
        val blurred = small.copy(Bitmap.Config.ARGB_8888, true)
        if (small !== source) small.recycle()
        FastStackBlur.blurInPlace(blurred, 14)
        val c = Canvas(blurred)
        val r = (Color.red(accentColor) * 0.25f).toInt()
        val g = (Color.green(accentColor) * 0.12f).toInt()
        val b = (Color.blue(accentColor) * 0.28f).toInt()
        c.drawColor(Color.argb(175, r.coerceAtLeast(14), g.coerceAtLeast(5), b.coerceAtLeast(22)))
        return blurred
    }

    /** Stylized dark-fantasy Witch silhouette fallback used ONLY when no photo folder / avatar is configured yet. */
    private fun buildFallbackWitchArtwork(def: WitchCatalog.WitchDef): Pair<Bitmap, Bitmap> {
        val accent = def.archetype.accentHex
        val bg = Bitmap.createBitmap(180, 320, Bitmap.Config.ARGB_8888)
        Canvas(bg).apply {
            drawRect(0f, 0f, 180f, 320f, Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, 320f,
                    Color.rgb(24, 10, 38), Color.rgb(10, 5, 18), Shader.TileMode.CLAMP
                )
            })
        }

        val sp = Bitmap.createBitmap(440, 620, Bitmap.Config.ARGB_8888)
        val c = Canvas(sp)
        val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(220f, 260f, 210f, Color.argb(120, Color.red(accent), Color.green(accent), Color.blue(accent)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        c.drawCircle(220f, 260f, 210f, auraPaint)

        val silPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(22, 14, 34) }
        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = accent
        }

        // Witch Hat
        val hat = Path().apply {
            moveTo(220f, 45f)
            lineTo(325f, 175f)
            lineTo(390f, 195f)
            lineTo(50f, 195f)
            lineTo(115f, 175f)
            close()
        }
        c.drawPath(hat, silPaint)
        c.drawPath(hat, rimPaint)

        // Head & Flowing Cloak
        c.drawCircle(220f, 240f, 56f, silPaint)
        val cloak = Path().apply {
            moveTo(164f, 290f)
            quadTo(220f, 275f, 276f, 290f)
            lineTo(350f, 590f)
            lineTo(90f, 590f)
            close()
        }
        c.drawPath(cloak, silPaint)
        c.drawPath(cloak, rimPaint)

        // Glowing Arcane Eyes
        val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        c.drawCircle(200f, 236f, 7f, eyePaint)
        c.drawCircle(240f, 236f, 7f, eyePaint)

        return sp to bg
    }

    private fun buildProfileForDef(def: WitchCatalog.WitchDef, cfg: DemonConfigSnapshot): WitchProfile {
        val chScale = 1.0f + (def.chapter - 1) * 0.14f + (if (def.isBoss) 0.22f else if (def.isMiniBoss) 0.11f else 0f)
        val s0 = def.skillNames.getOrElse(0) { "Sort d'Envoûtement" }
        val s1 = def.skillNames.getOrElse(1) { "Chaînes Magiques" }
        val s2 = def.skillNames.getOrElse(2) { "Drain d'Aura" }
        val drainBonus = if (def.archetype == WitchCatalog.Archetype.DRAINER || def.isBoss) 1 else 0

        // 6 Diverse Tactical Spells & Enchantments per Witch (no repetitive spam!)
        val attacks = ArrayList<WitchAttack>().apply {
            // 1. Signature Elemental Strike
            add(
                WitchAttack(
                    name = "$s0 ✨",
                    hpDamage = (15 * chScale).toInt().coerceIn(12, 34),
                    embraceGain = (14 * chScale).toInt().coerceIn(12, 28),
                    levelDrain = 0,
                    statusEffect = "",
                    kind = AttackKind.STRIKE,
                    telegraphHint = "⚔️ Prépare « $s0 » (Contre-Sceau ou Garde conseillé)"
                )
            )
            // 2. Enchantment (Charm OR Hypnosis)
            if (def.archetype == WitchCatalog.Archetype.HYPNOTIST || def.archetype == WitchCatalog.Archetype.CHRONO) {
                add(
                    WitchAttack(
                        name = "🌀 Spirale de $s2",
                        hpDamage = (13 * chScale).toInt().coerceIn(10, 28),
                        embraceGain = (20 * chScale).toInt().coerceIn(16, 36),
                        levelDrain = 1,
                        statusEffect = "HYPNOTIZED",
                        kind = AttackKind.ENCHANT_HYPNO,
                        telegraphHint = "🌀 Tisse une Hypnose ! (🧘 Méditation bloque l'envoûtement !)"
                    )
                )
            } else {
                add(
                    WitchAttack(
                        name = "💖 Envoûtement : $s0",
                        hpDamage = (12 * chScale).toInt().coerceIn(10, 26),
                        embraceGain = (21 * chScale).toInt().coerceIn(18, 38),
                        levelDrain = 0,
                        statusEffect = "CHARMED",
                        kind = AttackKind.ENCHANT_CHARM,
                        telegraphHint = "💖 Prépare un Envoûtement Charme ! (🧘 Méditation ou 🔮 Amulette !)"
                    )
                )
            }
            // 3. Binding / Capture Attempt
            add(
                WitchAttack(
                    name = "⛓️ Capture : $s1",
                    hpDamage = (17 * chScale).toInt().coerceIn(14, 34),
                    embraceGain = (24 * chScale).toInt().coerceIn(20, 40),
                    levelDrain = 1,
                    statusEffect = "CAPTURE",
                    kind = AttackKind.BIND_CAPTURE,
                    telegraphHint = "⛓️ Va tenter une CAPTURE ! (⚡ Contre-Sceau ou 🛡️ Voile d'Égide !)"
                )
            )
            // 4. Archetype Specialty #1 (Barrier Shield OR Mirage Clone OR Time Double)
            when (def.archetype) {
                WitchCatalog.Archetype.ILLUSIONIST, WitchCatalog.Archetype.CHARMER -> add(
                    WitchAttack(
                        name = "🪞 Mirage de ${def.name}",
                        hpDamage = (11 * chScale).toInt().coerceIn(8, 22),
                        embraceGain = (14 * chScale).toInt().coerceIn(12, 24),
                        levelDrain = 0,
                        statusEffect = "",
                        kind = AttackKind.MIRAGE_CLONE,
                        telegraphHint = "🪞 Invoque un Double Mirage ! (🌟 Exorcisme ou 🧘 Méditation perce l'illusion)"
                    )
                )
                WitchCatalog.Archetype.CHRONO, WitchCatalog.Archetype.SOVEREIGN -> add(
                    WitchAttack(
                        name = "⏳ Double Écho : $s2",
                        hpDamage = (21 * chScale).toInt().coerceIn(18, 42),
                        embraceGain = (20 * chScale).toInt().coerceIn(16, 36),
                        levelDrain = 1,
                        statusEffect = "",
                        kind = AttackKind.TIME_DOUBLE,
                        telegraphHint = "⏳ Distorsion Temporelle (Double Frappe) ! (🛡️ Garde fortement conseillée !)"
                    )
                )
                else -> add(
                    WitchAttack(
                        name = "🛡️ Barrière Runique de ${def.element}",
                        hpDamage = (10 * chScale).toInt().coerceIn(8, 20),
                        embraceGain = (12 * chScale).toInt().coerceIn(10, 22),
                        levelDrain = 0,
                        statusEffect = "",
                        kind = AttackKind.BARRIER_SHIELD,
                        telegraphHint = "🛡️ Érige une Barrière Runique ! (🔥 Lame Spirituelle la brise + ÉTOURDI !)"
                    )
                )
            }
            // 5. Siphon Mark (Drains HP & Heals Witch each turn!)
            add(
                WitchAttack(
                    name = "🩸 Siphon : $s2",
                    hpDamage = (16 * chScale).toInt().coerceIn(13, 32),
                    embraceGain = (18 * chScale).toInt().coerceIn(15, 32),
                    levelDrain = 1 + drainBonus,
                    statusEffect = "SIPHON",
                    kind = AttackKind.SIPHON_DRAIN,
                    telegraphHint = "🩸 Prépare une Marque de Siphon ! (Purifie avec 🔮 Amulette ou 🌟 Exorcisme)"
                )
            )
            // 6. Seal Magic (Inquisitor / Boss / High-chapter) OR Hypnosis
            if (def.archetype == WitchCatalog.Archetype.INQUISITOR || def.isBoss || def.isMiniBoss) {
                add(
                    WitchAttack(
                        name = "🔒 Sceau d'Interdiction",
                        hpDamage = (18 * chScale).toInt().coerceIn(14, 34),
                        embraceGain = (16 * chScale).toInt().coerceIn(14, 30),
                        levelDrain = 1,
                        statusEffect = "SEALED",
                        kind = AttackKind.SEAL_MAGIC,
                        telegraphHint = "🔒 Va SCELLER tes sorts PM ! (🧘 Méditation te protège 1 tour !)"
                    )
                )
            } else {
                add(
                    WitchAttack(
                        name = "🛡️ Barrière d'Éclipse",
                        hpDamage = (12 * chScale).toInt().coerceIn(10, 24),
                        embraceGain = (14 * chScale).toInt().coerceIn(12, 26),
                        levelDrain = 0,
                        statusEffect = "",
                        kind = AttackKind.BARRIER_SHIELD,
                        telegraphHint = "🛡️ Va activer une Barrière ! (🔥 Lame Spirituelle = Brise-Garde !)"
                    )
                )
            }
        }

        val ultimate = WitchAttack(
            name = "👑 ${def.ultimateName}",
            hpDamage = (32 * chScale).toInt().coerceIn(28, 62),
            embraceGain = (34 * chScale).toInt().coerceIn(28, 56),
            levelDrain = (2 + drainBonus).coerceAtMost(4),
            statusEffect = if (def.isBoss || def.archetype == WitchCatalog.Archetype.BINDER) "CAPTURE" else "HYPNOTIZED",
            kind = AttackKind.ULTIMATE,
            telegraphHint = "⚠️ SORT ULTIME « ${def.ultimateName} » ! Utilise 🛡️ GARDE pour une PARADE PARFAITE !"
        )

        val advice = "🔮 Évaluation de ${cfg.avatarName} (Grimoire #${def.id}/50) :\n" +
            "« Battu par ${def.name} (${def.archetype.label}) ? ${def.weaknessTip} »"

        return WitchProfile(
            def = def,
            name = def.displayTitle,
            title = def.title,
            element = def.element,
            bossLevel = def.level,
            maxResolve = def.maxResolve,
            mgqAdviceLine = advice,
            attacks = attacks,
            ultimateAttack = ultimate
        )
    }
}
