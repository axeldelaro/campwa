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
 * Media & Hidden Folder Engine:
 * - Reads images from ANY folder including hidden `.dotfolders`, `.nomedia` folders, and hidden files
 *   via All Files Access (`MANAGE_EXTERNAL_STORAGE`), with Android SAF DocumentTree fallback.
 * - Safe downsampled decoding (never OOMs on 24MP photos).
 * - Backs up & restores original wallpaper cleanly before applying #14 wallpaper pranks!
 */
object MediaFolderHelper {

    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")
    private const val BACKUP_WALLPAPER_NAME = "original_wallpaper_backup.jpg"

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun isImageFileName(name: String): Boolean {
        val clean = name.lowercase()
        val dot = clean.lastIndexOf('.')
        if (dot <= 0 || dot == clean.lastIndex) return false
        return IMAGE_EXTS.contains(clean.substring(dot + 1))
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

    fun countConfiguredImages(context: Context, cfg: DemonConfigSnapshot): Int {
        if (cfg.mediaFolderPath.isNotBlank()) {
            val dir = File(cfg.mediaFolderPath)
            if (dir.exists() && dir.isDirectory) {
                return listFilesInFolder(dir, cfg.includeHiddenFiles).size
            }
        }
        if (cfg.mediaTreeUri.isNotBlank()) {
            return try {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(cfg.mediaTreeUri)) ?: return 0
                tree.listFiles().count { it.isFile && isImageFileName(it.name.orEmpty()) }
            } catch (_: Throwable) {
                0
            }
        }
        return 0
    }

    private fun listFilesInFolder(dir: File, includeHidden: Boolean): List<File> {
        val out = ArrayList<File>()
        fun scan(cur: File, depth: Int) {
            if (depth > 2) return
            val list = cur.listFiles() ?: return
            for (f in list) {
                if (!includeHidden && f.name.startsWith(".")) continue
                if (f.isFile && isImageFileName(f.name) && f.length() > 1024L) {
                    out.add(f)
                } else if (f.isDirectory && depth < 1) {
                    scan(f, depth + 1)
                }
            }
        }
        scan(dir, 0)
        return out
    }

    fun loadRandomMediaBitmap(context: Context, cfg: DemonConfigSnapshot, maxDimPx: Int = 1080): Bitmap? {
        // 1) Direct filesystem folder (supports hidden `.dotfolders` & `.nomedia`!)
        if (cfg.mediaFolderPath.isNotBlank()) {
            val dir = File(cfg.mediaFolderPath)
            if (dir.exists() && dir.isDirectory) {
                val files = listFilesInFolder(dir, cfg.includeHiddenFiles)
                if (files.isNotEmpty()) {
                    val chosen = files[Random.nextInt(files.size)]
                    return decodeSampledFile(chosen, maxDimPx)
                }
            }
        }

        // 2) Android SAF DocumentTree fallback
        if (cfg.mediaTreeUri.isNotBlank()) {
            try {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(cfg.mediaTreeUri))
                val imgs = tree?.listFiles()?.filter { it.isFile && isImageFileName(it.name.orEmpty()) }.orEmpty()
                if (imgs.isNotEmpty()) {
                    val chosen = imgs[Random.nextInt(imgs.size)]
                    return decodeSampledUri(context, chosen.uri, maxDimPx)
                }
            } catch (_: Throwable) {
            }
        }

        // 3) Fallback to avatar image itself if user hasn't picked a media folder yet
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
