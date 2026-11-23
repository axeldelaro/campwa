package com.gazelock.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import com.gazelock.app.config.GazeConfigSnapshot
import java.io.File

/**
 * Recursive Video & Image Scanner supporting hidden folders (`.*` & `.nomedia`) via All Files Access
 * AND standard Android SAF DocumentTree fallback!
 * Also provides memory-safe preloaded bitmaps for the 3-minute 0.1s rapid image-spam access-code wall
 * (using images in the video folder, with automatic video-frame extraction fallback if folder has only videos!).
 */
object VideoFeedRepository {

    data class VideoItem(
        val filePath: String? = null,
        val contentUri: Uri? = null,
        val title: String = "Vidéo"
    )

    private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "mov", "m4v", "3gp", "avi", "ts")
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif")

    fun isVideoName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in VIDEO_EXTS
    fun isImageName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in IMAGE_EXTS

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun loadVideoPlaylist(context: Context, cfg: GazeConfigSnapshot): List<VideoItem> {
        val out = ArrayList<VideoItem>()
        if (cfg.folderPath.isNotBlank()) {
            val dir = File(cfg.folderPath)
            if (dir.exists() && dir.isDirectory) collectVideosRecursive(dir, out, depth = 0)
        }
        if (out.isEmpty() && cfg.treeUri.isNotBlank()) {
            try {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(cfg.treeUri))
                if (tree != null && tree.exists()) collectSafVideosRecursive(tree, out, depth = 0)
            } catch (_: Throwable) {}
        }
        return if (cfg.shuffleVideos) out.shuffled() else out.sortedBy { it.title.lowercase() }
    }

    /** Counts both videos and images in the configured folder for UI display. */
    fun countMediaInConfig(context: Context, cfg: GazeConfigSnapshot): Pair<Int, Int> {
        val vids = loadVideoPlaylist(context, cfg).size
        val imgs = collectImageRefs(context, cfg, limit = 400).size
        return vids to imgs
    }

    fun countVideosInDir(dir: File): Int {
        val tmp = ArrayList<VideoItem>()
        collectVideosRecursive(dir, tmp, depth = 0, limit = 500)
        return tmp.size
    }

    /**
     * Loads a fast memory-safe RGB_565 bitmap pool (max 32 images, max 680px dim) from images in the
     * video folder (`.hidden` included!) for silky-smooth 0.1s (10 FPS) zero-lag image spam!
     * If the folder has no standalone image files, extracts crisp frames from the videos themselves!
     */
    fun loadSpamBitmapPool(context: Context, cfg: GazeConfigSnapshot, maxPoolSize: Int = 32): List<Bitmap> {
        val out = ArrayList<Bitmap>(maxPoolSize)
        val refs = collectImageRefs(context, cfg, limit = 300).shuffled()

        for (ref in refs) {
            if (out.size >= maxPoolSize) break
            val bmp = decodeSampled(context, ref.first, ref.second, maxDim = 680)
            if (bmp != null) out.add(bmp)
        }

        // Smart fallback: if folder contains only videos (0 separate photos), extract frames from the videos!
        if (out.isEmpty()) {
            val vids = loadVideoPlaylist(context, cfg).shuffled().take(12)
            for (v in vids) {
                if (out.size >= maxPoolSize) break
                val mmr = MediaMetadataRetriever()
                try {
                    if (v.filePath != null) mmr.setDataSource(v.filePath)
                    else if (v.contentUri != null) mmr.setDataSource(context, v.contentUri)
                    val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 8_000L
                    val timesUs = listOf((durMs * 200L), (durMs * 500L), (durMs * 800L))
                    for (tUs in timesUs) {
                        if (out.size >= maxPoolSize) break
                        val frame = mmr.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        if (frame != null) {
                            val scaled = downscaleSafe(frame, 680)
                            if (scaled !== frame) frame.recycle()
                            out.add(scaled)
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    try { mmr.release() } catch (_: Throwable) {}
                }
            }
        }
        return out
    }

    private fun collectImageRefs(context: Context, cfg: GazeConfigSnapshot, limit: Int): List<Pair<File?, Uri?>> {
        val out = ArrayList<Pair<File?, Uri?>>()
        if (cfg.folderPath.isNotBlank()) {
            val dir = File(cfg.folderPath)
            if (dir.exists() && dir.isDirectory) collectImagesRecursive(dir, out, depth = 0, limit = limit)
        }
        if (out.isEmpty() && cfg.treeUri.isNotBlank()) {
            try {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(cfg.treeUri))
                if (tree != null && tree.exists()) collectSafImagesRecursive(tree, out, depth = 0, limit = limit)
            } catch (_: Throwable) {}
        }
        return out
    }

    private fun decodeSampled(context: Context, file: File?, uri: Uri?, maxDim: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (file != null) {
                BitmapFactory.decodeFile(file.absolutePath, bounds)
            } else if (uri != null) {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while ((bounds.outWidth / sample) > maxDim * 2 || (bounds.outHeight / sample) > maxDim * 2) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val raw = if (file != null) {
                BitmapFactory.decodeFile(file.absolutePath, opts)
            } else if (uri != null) {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            } else null
            if (raw == null) return null
            val scaled = downscaleSafe(raw, maxDim)
            if (scaled !== raw) raw.recycle()
            scaled
        } catch (_: Throwable) {
            null
        }
    }

    private fun downscaleSafe(src: Bitmap, maxDim: Int): Bitmap {
        val maxSide = maxOf(src.width, src.height)
        if (maxSide <= maxDim) return src
        val ratio = maxDim.toFloat() / maxSide.toFloat()
        return Bitmap.createScaledBitmap(src, (src.width * ratio).toInt().coerceAtLeast(64), (src.height * ratio).toInt().coerceAtLeast(64), true)
    }

    private fun collectVideosRecursive(dir: File, out: MutableList<VideoItem>, depth: Int, limit: Int = 800) {
        if (depth > 4 || out.size >= limit) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (out.size >= limit) break
            if (f.isDirectory) collectVideosRecursive(f, out, depth + 1, limit)
            else if (f.isFile && f.length() > 8_192L && isVideoName(f.name)) out.add(VideoItem(filePath = f.absolutePath, title = f.nameWithoutExtension))
        }
    }

    private fun collectSafVideosRecursive(dir: DocumentFile, out: MutableList<VideoItem>, depth: Int, limit: Int = 500) {
        if (depth > 3 || out.size >= limit) return
        val children = try { dir.listFiles() } catch (_: Throwable) { emptyArray() }
        for (doc in children) {
            if (out.size >= limit) break
            if (doc.isDirectory) collectSafVideosRecursive(doc, out, depth + 1, limit)
            else if (doc.isFile && isVideoName(doc.name.orEmpty())) out.add(VideoItem(contentUri = doc.uri, title = doc.name.orEmpty().substringBeforeLast('.')))
        }
    }

    private fun collectImagesRecursive(dir: File, out: MutableList<Pair<File?, Uri?>>, depth: Int, limit: Int) {
        if (depth > 4 || out.size >= limit) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (out.size >= limit) break
            if (f.isDirectory) collectImagesRecursive(f, out, depth + 1, limit)
            else if (f.isFile && f.length() > 4_096L && isImageName(f.name)) out.add(f to null)
        }
    }

    private fun collectSafImagesRecursive(dir: DocumentFile, out: MutableList<Pair<File?, Uri?>>, depth: Int, limit: Int) {
        if (depth > 3 || out.size >= limit) return
        val children = try { dir.listFiles() } catch (_: Throwable) { emptyArray() }
        for (doc in children) {
            if (out.size >= limit) break
            if (doc.isDirectory) collectSafImagesRecursive(doc, out, depth + 1, limit)
            else if (doc.isFile && isImageName(doc.name.orEmpty())) out.add(null to doc.uri)
        }
    }
}
