package com.demonpet.app.media

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import com.demonpet.app.config.DemonConfigSnapshot
import com.demonpet.app.config.WallpaperTarget
import java.io.File
import java.io.FileOutputStream
import kotlin.random.Random

/**
 * Media & Hidden Folder Engine (Photos + Videos):
 * - Reads images AND videos from ANY folder including hidden `.dotfolders`, `.nomedia` folders, and hidden files
 *   via All Files Access (`MANAGE_EXTERNAL_STORAGE`), with Android SAF DocumentTree fallback.
 * - Supports #14 wallpaper backup/restore, #15 popups, #16 flash, #38 Timeout Corner Video, #61 Slideshow.
 */
object MediaFolderHelper {

    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")
    private val VIDEO_EXTS = setOf("mp4", "webm", "mkv", "mov", "3gp", "m4v", "avi")
    private const val BACKUP_WALLPAPER_NAME = "original_wallpaper_backup.jpg"

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun isImageFileName(name: String): Boolean {
        val ext = extOf(name) ?: return false
        return IMAGE_EXTS.contains(ext)
    }

    fun isVideoFileName(name: String): Boolean {
        val ext = extOf(name) ?: return false
        return VIDEO_EXTS.contains(ext)
    }

    private fun extOf(name: String): String? {
        val clean = name.lowercase()
        val dot = clean.lastIndexOf('.')
        if (dot <= 0 || dot == clean.lastIndex) return null
        return clean.substring(dot + 1)
    }

    fun countImagesInFolder(dir: File, includeHidden: Boolean = true): Int {
        if (!dir.exists() || !dir.isDirectory) return 0
        val children = dir.listFiles() ?: return 0
        var count = 0
        for (f in children) {
            if (!includeHidden && f.name.startsWith(".")) continue
            if (f.isFile && isImageFileName(f.name)) count++
        }
        return count
    }

    fun countVideosInFolder(dir: File, includeHidden: Boolean = true): Int {
        if (!dir.exists() || !dir.isDirectory) return 0
        val children = dir.listFiles() ?: return 0
        var count = 0
        for (f in children) {
            if (!includeHidden && f.name.startsWith(".")) continue
            if (f.isFile && isVideoFileName(f.name)) count++
        }
        return count
    }

    /** Returns Pair(imageCount, videoCount) */
    fun countConfiguredMedia(context: Context, cfg: DemonConfigSnapshot): Pair<Int, Int> {
        if (cfg.mediaFolderPath.isNotBlank()) {
            val dir = File(cfg.mediaFolderPath)
            if (dir.exists() && dir.isDirectory) {
                val imgs = listMediaInFolder(dir, cfg.includeHiddenFiles, videos = false).size
                val vids = listMediaInFolder(dir, cfg.includeHiddenFiles, videos = true).size
                return Pair(imgs, vids)
            }
        }
        if (cfg.mediaTreeUri.isNotBlank()) {
            return try {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(cfg.mediaTreeUri)) ?: return Pair(0, 0)
                val files = tree.listFiles().filter { it.isFile }
                val imgs = files.count { isImageFileName(it.name.orEmpty()) }
                val vids = files.count { isVideoFileName(it.name.orEmpty()) }
                Pair(imgs, vids)
            } catch (_: Throwable) {
                Pair(0, 0)
            }
        }
        return Pair(0, 0)
    }

    fun countConfiguredImages(context: Context, cfg: DemonConfigSnapshot): Int =
        countConfiguredMedia(context, cfg).first

    private fun listMediaInFolder(dir: File, includeHidden: Boolean, videos: Boolean): List<File> {
        val out = ArrayList<File>()
        fun scan(cur: File, depth: Int) {
            if (depth > 2) return
            val list = cur.listFiles() ?: return
            for (f in list) {
                if (!includeHidden && f.name.startsWith(".")) continue
                if (f.isFile && f.length() > 1024L) {
                    val match = if (videos) isVideoFileName(f.name) else isImageFileName(f.name)
                    if (match) out.add(f)
                } else if (f.isDirectory && depth < 1) {
                    scan(f, depth + 1)
                }
            }
        }
        scan(dir, 0)
        return out
    }

    /** Picks a random video Uri from the configured folder (including hidden `.dotfolders`!) for #38 Timeout Corner */
    fun pickRandomVideoUri(context: Context, cfg: DemonConfigSnapshot): Uri? {
        if (cfg.mediaFolderPath.isNotBlank()) {
            val dir = File(cfg.mediaFolderPath)
            if (dir.exists() && dir.isDirectory) {
                val vids = listMediaInFolder(dir, cfg.includeHiddenFiles, videos = true)
                if (vids.isNotEmpty()) {
                    return Uri.fromFile(vids[Random.nextInt(vids.size)])
                }
            }
        }
        if (cfg.mediaTreeUri.isNotBlank()) {
            try {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(cfg.mediaTreeUri))
                val vids = tree?.listFiles()?.filter { it.isFile && isVideoFileName(it.name.orEmpty()) }.orEmpty()
                if (vids.isNotEmpty()) {
                    return vids[Random.nextInt(vids.size)].uri
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /** Loads up to [count] distinct random photos for #61 Forced Slideshow */
    fun loadMultipleRandomBitmaps(context: Context, cfg: DemonConfigSnapshot, count: Int = 4, maxDimPx: Int = 1080): List<Bitmap> {
        val out = ArrayList<Bitmap>()
        if (cfg.mediaFolderPath.isNotBlank()) {
            val dir = File(cfg.mediaFolderPath)
            if (dir.exists() && dir.isDirectory) {
                val files = listMediaInFolder(dir, cfg.includeHiddenFiles, videos = false).shuffled()
                for (f in files.take(count)) {
                    decodeSampledFile(f, maxDimPx)?.let { out.add(it) }
                }
                if (out.isNotEmpty()) return out
            }
        }
        if (cfg.mediaTreeUri.isNotBlank()) {
            try {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(cfg.mediaTreeUri))
                val imgs = tree?.listFiles()?.filter { it.isFile && isImageFileName(it.name.orEmpty()) }.orEmpty().shuffled()
                for (doc in imgs.take(count)) {
                    decodeSampledUri(context, doc.uri, maxDimPx)?.let { out.add(it) }
                }
                if (out.isNotEmpty()) return out
            } catch (_: Throwable) {
            }
        }
        loadRandomMediaBitmap(context, cfg, maxDimPx)?.let { out.add(it) }
        return out
    }

    fun loadRandomMediaBitmap(context: Context, cfg: DemonConfigSnapshot, maxDimPx: Int = 1080): Bitmap? {
        if (cfg.mediaFolderPath.isNotBlank()) {
            val dir = File(cfg.mediaFolderPath)
            if (dir.exists() && dir.isDirectory) {
                val files = listMediaInFolder(dir, cfg.includeHiddenFiles, videos = false)
                if (files.isNotEmpty()) {
                    return decodeSampledFile(files[Random.nextInt(files.size)], maxDimPx)
                }
            }
        }
        if (cfg.mediaTreeUri.isNotBlank()) {
            try {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(cfg.mediaTreeUri))
                val imgs = tree?.listFiles()?.filter { it.isFile && isImageFileName(it.name.orEmpty()) }.orEmpty()
                if (imgs.isNotEmpty()) {
                    return decodeSampledUri(context, imgs[Random.nextInt(imgs.size)].uri, maxDimPx)
                }
            } catch (_: Throwable) {
            }
        }
        if (cfg.avatarPath.isNotBlank()) {
            val f = File(cfg.avatarPath)
            if (f.exists()) return decodeSampledFile(f, maxDimPx)
        }
        return null
    }

    fun decodeSampledFile(file: File, maxDimPx: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            var sample = 1
            while (opts.outWidth / sample > maxDimPx || opts.outHeight / sample > maxDimPx) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
        } catch (_: Throwable) {
            null
        }
    }

    fun decodeSampledUri(context: Context, uri: Uri, maxDimPx: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (opts.outWidth / sample > maxDimPx || opts.outHeight / sample > maxDimPx) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
        } catch (_: Throwable) {
            null
        }
    }

    fun backupOriginalWallpaperIfNeeded(context: Context) {
        val backupFile = File(context.filesDir, BACKUP_WALLPAPER_NAME)
        if (backupFile.exists() && backupFile.length() > 4096L) return
        try {
            val wm = WallpaperManager.getInstance(context)
            val drawable = wm.drawable ?: return
            val bmp = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                drawable.bitmap
            } else {
                val w = drawable.intrinsicWidth.coerceIn(360, 1440)
                val h = drawable.intrinsicHeight.coerceIn(640, 2560)
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                drawable.setBounds(0, 0, w, h)
                drawable.draw(c)
                out
            }
            FileOutputStream(backupFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, fos)
            }
        } catch (_: Throwable) {
        }
    }

    fun applyRandomWallpaperPrank(context: Context, cfg: DemonConfigSnapshot): Boolean {
        val bmp = loadRandomMediaBitmap(context, cfg, maxDimPx = 1440) ?: return false
        return try {
            backupOriginalWallpaperIfNeeded(context)
            val wm = WallpaperManager.getInstance(context)
            val flag = when (cfg.wallpaperTarget) {
                WallpaperTarget.HOME -> WallpaperManager.FLAG_SYSTEM
                WallpaperTarget.LOCK -> WallpaperManager.FLAG_LOCK
                WallpaperTarget.BOTH -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            }
            wm.setBitmap(bmp, null, true, flag)
            true
        } catch (_: Throwable) {
            false
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    fun restoreOriginalWallpaper(context: Context): Boolean {
        val backupFile = File(context.filesDir, BACKUP_WALLPAPER_NAME)
        if (!backupFile.exists()) return false
        val bmp = decodeSampledFile(backupFile, 1600) ?: return false
        return try {
            val wm = WallpaperManager.getInstance(context)
            wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
            true
        } catch (_: Throwable) {
            false
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    fun hasWallpaperBackup(context: Context): Boolean {
        val f = File(context.filesDir, BACKUP_WALLPAPER_NAME)
        return f.exists() && f.length() > 4096L
    }
}
