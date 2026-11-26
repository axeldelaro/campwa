package com.aigallery.pro.data

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/**
 * Ultra-fast Simple Gallery Pro Media Scanner & File Manager:
 * - Queries MediaStore Images + Videos cleanly AND (when All Files Access + Show Hidden enabled)
 *   recursively scans hidden .* and .nomedia folders just like Simple Gallery Pro!
 * - Handles Rename, Copy, Move, Create Folder, Recycle Bin (move/restore/empty), Permanent Delete, and Share!
 */
object MediaRepository {

    private val PHOTO_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif", "avif")
    private val VIDEO_EXTS = setOf("mp4", "mkv", "mov", "webm", "3gp", "avi", "m4v", "ts")
    private val GIF_EXTS = setOf("gif")

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun detectMediaType(name: String): MediaType? {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return when {
            ext in GIF_EXTS -> MediaType.GIF
            ext in PHOTO_EXTS -> MediaType.PHOTO
            ext in VIDEO_EXTS -> MediaType.VIDEO
            else -> null
        }
    }

    fun scanAllMedia(context: Context, db: GalleryDatabase, showHidden: Boolean): List<MediaItem> {
        val metaMap = db.loadAllMediaMeta()
        val mediaPersonsMap = db.loadMediaToPersonsMap()
        val byPath = LinkedHashMap<String, MediaItem>(1024)

        // 1) Fast MediaStore Images query
        queryMediaStore(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false, byPath, metaMap, mediaPersonsMap, showHidden)
        // 2) Fast MediaStore Videos query
        queryMediaStore(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true, byPath, metaMap, mediaPersonsMap, showHidden)

        // 3) If "Afficher fichiers cachés .* / .nomedia" is ON and All Files Access is granted, scan filesystem directly!
        if (showHidden && hasAllFilesAccess()) {
            val root = Environment.getExternalStorageDirectory()
            if (root != null && root.exists()) {
                scanDirectoryForHidden(root, depth = 0, byPath, metaMap, mediaPersonsMap)
            }
        }

        return byPath.values.toList()
    }

    private fun queryMediaStore(
        context: Context,
        uri: android.net.Uri,
        isVideo: Boolean,
        out: MutableMap<String, MediaItem>,
        metaMap: Map<String, MediaMetaRow>,
        personsMap: Map<String, List<Long>>,
        showHidden: Boolean
    ) {
        val proj = if (isVideo) arrayOf(
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT
        ) else arrayOf(
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )

        try {
            context.contentResolver.query(uri, proj, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    if (path.contains("/.SimpleGalleryAITrash/")) continue
                    val file = File(path)
                    if (!file.exists() || file.length() <= 0L) continue
                    val parent = file.parentFile ?: continue
                    if (!showHidden && (file.name.startsWith(".") || parent.name.startsWith("."))) continue

                    val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: file.name
                    val type = if (isVideo) MediaType.VIDEO else (detectMediaType(name) ?: MediaType.PHOTO)
                    val size = c.getLong(2).takeIf { it > 0L } ?: file.length()
                    val mtime = c.getLong(3).takeIf { it > 0L } ?: (file.lastModified() / 1000L)
                    val dur = if (isVideo) c.getLong(4).coerceAtLeast(0L) else 0L
                    val w = if (isVideo) c.getInt(5) else c.getInt(4)
                    val h = if (isVideo) c.getInt(6) else c.getInt(5)

                    val meta = metaMap[path]
                    out[path] = MediaItem(
                        path = path,
                        name = name,
                        folderPath = parent.absolutePath,
                        folderName = parent.name.ifBlank { "Stockage" },
                        type = type,
                        sizeBytes = size,
                        dateModifiedSec = mtime,
                        durationMs = dur,
                        width = w,
                        height = h,
                        isFavorite = meta?.isFavorite ?: false,
                        swipeRating = meta?.swipeRating ?: 0,
                        aiScorePct = meta?.aiScorePct ?: -1,
                        aiReason = meta?.aiReason.orEmpty(),
                        personIds = personsMap[path].orEmpty(),
                        faceCount = meta?.faceCount ?: 0
                    )
                }
            }
        } catch (_: Throwable) {}
    }

    private fun scanDirectoryForHidden(
        dir: File,
        depth: Int,
        out: MutableMap<String, MediaItem>,
        metaMap: Map<String, MediaMetaRow>,
        personsMap: Map<String, List<Long>>
    ) {
        if (depth > 5) return
        val dirName = dir.name
        if (dirName == "Android" && depth == 1) return
        if (dirName == ".SimpleGalleryAITrash" || dirName == ".thumbnails") return
        val children = try { dir.listFiles() } catch (_: Throwable) { null } ?: return
        for (f in children) {
            if (f.isDirectory) {
                scanDirectoryForHidden(f, depth + 1, out, metaMap, personsMap)
            } else if (f.isFile && f.length() > 1024L) {
                val p = f.absolutePath
                if (out.containsKey(p)) continue
                val type = detectMediaType(f.name) ?: continue
                val meta = metaMap[p]
                out[p] = MediaItem(
                    path = p,
                    name = f.name,
                    folderPath = dir.absolutePath,
                    folderName = dir.name.ifBlank { "Stockage" },
                    type = type,
                    sizeBytes = f.length(),
                    dateModifiedSec = f.lastModified() / 1000L,
                    durationMs = 0L,
                    isFavorite = meta?.isFavorite ?: false,
                    swipeRating = meta?.swipeRating ?: 0,
                    aiScorePct = meta?.aiScorePct ?: -1,
                    aiReason = meta?.aiReason.orEmpty(),
                    personIds = personsMap[p].orEmpty(),
                    faceCount = meta?.faceCount ?: 0
                )
            }
        }
    }

    fun buildAlbums(
        allMedia: List<MediaItem>,
        folderPrefs: Map<String, FolderPrefRow>,
        sortOrder: SortOrder,
        includeExcluded: Boolean = false
    ): List<AlbumFolder> {
        val grouped = allMedia.groupBy { it.folderPath }
        val albums = ArrayList<AlbumFolder>(grouped.size)
        for ((fp, list) in grouped) {
            val pref = folderPrefs[fp]
            if (!includeExcluded && pref?.isExcluded == true) continue
            val sorted = list.sortedByDescending { it.dateModifiedSec }
            val cover = sorted.first()
            val scored = list.map { it.aiScorePct }.filter { it >= 0 }
            val avgAi = if (scored.isNotEmpty()) scored.average().toInt() else -1
            val isHidden = File(fp).name.startsWith(".") || File(fp, ".nomedia").exists()
            albums.add(
                AlbumFolder(
                    path = fp,
                    name = cover.folderName,
                    coverPath = cover.path,
                    coverType = cover.type,
                    mediaCount = list.size,
                    latestDateSec = cover.dateModifiedSec,
                    totalSizeBytes = list.sumOf { it.sizeBytes },
                    isPinned = pref?.isPinned == true,
                    isHiddenFolder = isHidden,
                    avgAiScore = avgAi
                )
            )
        }
        return albums.sortedWith(
            compareByDescending<AlbumFolder> { it.isPinned }.then(
                when (sortOrder) {
                    SortOrder.DATE_DESC -> compareByDescending { it.latestDateSec }
                    SortOrder.DATE_ASC -> compareBy { it.latestDateSec }
                    SortOrder.NAME_ASC -> compareBy { it.name.lowercase(Locale.US) }
                    SortOrder.NAME_DESC -> compareByDescending { it.name.lowercase(Locale.US) }
                    SortOrder.SIZE_DESC -> compareByDescending { it.totalSizeBytes }
                    SortOrder.AI_SCORE_DESC -> compareByDescending { it.avgAiScore }
                }
            )
        )
    }

    fun sortMedia(list: List<MediaItem>, order: SortOrder): List<MediaItem> = when (order) {
        SortOrder.DATE_DESC -> list.sortedByDescending { it.dateModifiedSec }
        SortOrder.DATE_ASC -> list.sortedBy { it.dateModifiedSec }
        SortOrder.NAME_ASC -> list.sortedBy { it.name.lowercase(Locale.US) }
        SortOrder.NAME_DESC -> list.sortedByDescending { it.name.lowercase(Locale.US) }
        SortOrder.SIZE_DESC -> list.sortedByDescending { it.sizeBytes }
        SortOrder.AI_SCORE_DESC -> list.sortedWith(compareByDescending<MediaItem> { it.aiScorePct }.thenByDescending { it.isFavorite }.thenByDescending { it.dateModifiedSec })
    }

    // ==================== FILE OPERATIONS (SIMPLE GALLERY PRO STYLE) ====================

    private fun getTrashDir(context: Context): File {
        val ext = Environment.getExternalStorageDirectory()
        val d = if (hasAllFilesAccess() && ext != null && ext.canWrite()) {
            File(ext, ".SimpleGalleryAITrash")
        } else {
            File(context.filesDir, "recycle_bin")
        }
        if (!d.exists()) d.mkdirs()
        try { File(d, ".nomedia").createNewFile() } catch (_: Throwable) {}
        return d
    }

    fun moveToRecycleBin(context: Context, db: GalleryDatabase, item: MediaItem): Boolean {
        val src = File(item.path)
        if (!src.exists()) return false
        val dst = File(getTrashDir(context), "${System.currentTimeMillis()}_${src.name}")
        val moved = try {
            src.renameTo(dst) || run { src.copyTo(dst, overwrite = true); src.delete() }
        } catch (_: Throwable) { false }
        if (moved) {
            db.insertTrashedItem(item.path, dst.absolutePath, item.name, item.type)
            notifyMediaScanner(context, arrayOf(item.path))
        }
        return moved
    }

    fun restoreFromRecycleBin(context: Context, db: GalleryDatabase, trashed: TrashedMedia): Boolean {
        val src = File(trashed.trashFilePath)
        if (!src.exists()) {
            db.removeRecycleBinRow(trashed.trashId)
            return false
        }
        var dst = File(trashed.originalPath)
        dst.parentFile?.mkdirs()
        if (dst.exists()) {
            val base = dst.nameWithoutExtension
            val ext = dst.extension
            dst = File(dst.parentFile, "${base}_restored_${System.currentTimeMillis() % 10000}.$ext")
        }
        val restored = try {
            src.renameTo(dst) || run { src.copyTo(dst, overwrite = true); src.delete() }
        } catch (_: Throwable) { false }
        if (restored) {
            db.removeRecycleBinRow(trashed.trashId)
            notifyMediaScanner(context, arrayOf(dst.absolutePath))
        }
        return restored
    }

    fun deletePermanently(context: Context, db: GalleryDatabase, path: String): Boolean {
        val ok = try { File(path).delete() } catch (_: Throwable) { false }
        if (ok) {
            db.deleteMediaRecords(path)
            notifyMediaScanner(context, arrayOf(path))
        }
        return ok
    }

    fun deleteTrashedPermanently(db: GalleryDatabase, trashed: TrashedMedia): Boolean {
        try { File(trashed.trashFilePath).delete() } catch (_: Throwable) {}
        db.deleteMediaRecords(trashed.originalPath)
        db.removeRecycleBinRow(trashed.trashId)
        return true
    }

    fun renameFile(context: Context, db: GalleryDatabase, oldPath: String, newNameNoExt: String): File? {
        val src = File(oldPath)
        if (!src.exists()) return null
        val clean = newNameNoExt.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_")
        if (clean.isEmpty()) return null
        val ext = src.extension
        val dst = File(src.parentFile, if (ext.isNotEmpty()) "$clean.$ext" else clean)
        if (dst.exists()) return null
        return if (try { src.renameTo(dst) } catch (_: Throwable) { false }) {
            db.renameMediaPath(oldPath, dst.absolutePath)
            notifyMediaScanner(context, arrayOf(oldPath, dst.absolutePath))
            dst
        } else null
    }

    fun copyOrMoveFile(context: Context, db: GalleryDatabase, item: MediaItem, targetFolderPath: String, move: Boolean): File? {
        val src = File(item.path)
        if (!src.exists()) return null
        val targetDir = File(targetFolderPath).apply { mkdirs() }
        var dst = File(targetDir, src.name)
        if (dst.exists()) {
            dst = File(targetDir, "${src.nameWithoutExtension}_${System.currentTimeMillis() % 10000}.${src.extension}")
        }
        val ok = try {
            if (move && src.renameTo(dst)) true
            else {
                src.copyTo(dst, overwrite = false)
                if (move) src.delete() else true
            }
        } catch (_: Throwable) { false }
        if (!ok) return null
        if (move) db.renameMediaPath(item.path, dst.absolutePath)
        notifyMediaScanner(context, arrayOf(item.path, dst.absolutePath))
        return dst
    }

    fun buildShareIntent(context: Context, items: List<MediaItem>): Intent? {
        if (items.isEmpty()) return null
        val authority = "${context.packageName}.fileprovider"
        val uris = ArrayList<android.net.Uri>()
        for (it in items) {
            val f = File(it.path)
            if (f.exists()) {
                try { uris.add(FileProvider.getUriForFile(context, authority, f)) } catch (_: Throwable) {}
            }
        }
        if (uris.isEmpty()) return null
        return if (uris.size == 1) {
            val mime = when (items[0].type) {
                MediaType.VIDEO -> "video/*"
                MediaType.GIF -> "image/gif"
                MediaType.PHOTO -> "image/*"
            }
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    fun notifyMediaScanner(context: Context, paths: Array<String>) {
        try { MediaScannerConnection.scanFile(context.applicationContext, paths, null, null) } catch (_: Throwable) {}
    }

    fun formatFileSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> String.format(Locale.FRANCE, "%.2f Go", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format(Locale.FRANCE, "%.1f Mo", bytes / 1_048_576.0)
        bytes >= 1024L -> "${bytes / 1024L} Ko"
        else -> "$bytes o"
    }

    fun formatDuration(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        val m = s / 60L
        val r = s % 60L
        return if (m >= 60L) String.format("%d:%02d:%02d", m / 60L, m % 60L, r) else String.format("%d:%02d", m, r)
    }
}
