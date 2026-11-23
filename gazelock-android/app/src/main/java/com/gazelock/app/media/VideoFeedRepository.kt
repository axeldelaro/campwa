package com.gazelock.app.media

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import com.gazelock.app.config.GazeConfigSnapshot
import java.io.File

/**
 * Recursive Video Scanner supporting hidden folders (`.*` & `.nomedia`) via All Files Access
 * AND standard Android SAF DocumentTree fallback!
 */
object VideoFeedRepository {

    data class VideoItem(
        val filePath: String? = null,
        val contentUri: Uri? = null,
        val title: String = "Vidéo"
    )

    private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "mov", "m4v", "3gp", "avi", "ts")

    fun isVideoName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTS
    }

    fun hasAllFilesAccess(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }

    fun loadVideoPlaylist(context: Context, cfg: GazeConfigSnapshot): List<VideoItem> {
        val out = ArrayList<VideoItem>()
        if (cfg.folderPath.isNotBlank()) {
            val dir = File(cfg.folderPath)
            if (dir.exists() && dir.isDirectory) {
                collectRecursive(dir, out, depth = 0)
            }
        }
        if (out.isEmpty() && cfg.treeUri.isNotBlank()) {
            try {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(cfg.treeUri))
                if (tree != null && tree.exists()) {
                    collectSafRecursive(tree, out, depth = 0)
                }
            } catch (_: Throwable) {}
        }
        return if (cfg.shuffleVideos) out.shuffled() else out.sortedBy { it.title.lowercase() }
    }

    fun countVideosInDir(dir: File): Int {
        val tmp = ArrayList<VideoItem>()
        collectRecursive(dir, tmp, depth = 0, limit = 500)
        return tmp.size
    }

    private fun collectRecursive(dir: File, out: MutableList<VideoItem>, depth: Int, limit: Int = 800) {
        if (depth > 4 || out.size >= limit) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (out.size >= limit) break
            if (f.isDirectory) {
                collectRecursive(f, out, depth + 1, limit)
            } else if (f.isFile && f.length() > 8_192L && isVideoName(f.name)) {
                out.add(VideoItem(filePath = f.absolutePath, title = f.nameWithoutExtension))
            }
        }
    }

    private fun collectSafRecursive(dir: DocumentFile, out: MutableList<VideoItem>, depth: Int, limit: Int = 500) {
        if (depth > 3 || out.size >= limit) return
        val children = try { dir.listFiles() } catch (_: Throwable) { emptyArray() }
        for (doc in children) {
            if (out.size >= limit) break
            if (doc.isDirectory) {
                collectSafRecursive(doc, out, depth + 1, limit)
            } else if (doc.isFile && isVideoName(doc.name.orEmpty())) {
                out.add(VideoItem(contentUri = doc.uri, title = doc.name.orEmpty().substringBeforeLast('.')))
            }
        }
    }
}
