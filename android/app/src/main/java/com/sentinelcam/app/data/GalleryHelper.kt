package com.sentinelcam.app.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

/**
 * Public Photo Gallery Reader (MediaStore) strictly excluding hidden folders,
 * dot-prefixed directories, .nomedia directories, vault/private buckets, and trashed items.
 */
object GalleryHelper {

    private val HIDDEN_BUCKET_KEYWORDS = setOf(
        "hidden",
        "private",
        "locked",
        "secret",
        "vault",
        "coffre",
        "trash",
        "bin",
        "recycle",
        "corbeille",
        "thumbnails",
        "cache",
        "safefolder",
        "securefolder",
        "masqué",
        "masque"
    )

    fun hasGalleryPermission(context: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    fun hasStorageManagePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Environment.isExternalStorageManager()
            } catch (_: Throwable) {
                false
            }
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isHiddenPath(relativePath: String?, dataPath: String?, bucketName: String?): Boolean {
        val bucketLower = bucketName.orEmpty().lowercase(Locale.FRENCH)
        if (bucketLower.startsWith(".")) return true
        for (kw in HIDDEN_BUCKET_KEYWORDS) {
            if (bucketLower.contains(kw)) return true
        }

        val combinedPath = (relativePath.orEmpty() + "/" + dataPath.orEmpty())
            .replace('\\', '/')
        val segments = combinedPath.split('/')
        for (seg in segments) {
            val clean = seg.trim()
            if (clean.startsWith(".") && clean.length > 1) return true
            val low = clean.lowercase(Locale.FRENCH)
            if (low == ".nomedia" || low == ".thumbnails" || low == ".trash" || low == ".private") {
                return true
            }
        }

        // Verify .nomedia marker in parent folder when filesystem path is available
        if (!dataPath.isNullOrBlank()) {
            try {
                val parent = File(dataPath).parentFile
                if (parent != null) {
                    if (parent.name.startsWith(".")) return true
                    if (File(parent, ".nomedia").exists()) return true
                    val grandParent = parent.parentFile
                    if (grandParent != null && File(grandParent, ".nomedia").exists()) return true
                }
            } catch (_: Throwable) {
            }
        }
        return false
    }

    fun listGalleryPage(
        context: Context,
        page: Int = 0,
        pageSize: Int = 18,
        albumFilter: String = ""
    ): JSONObject {
        if (!hasGalleryPermission(context)) {
            return JSONObject().apply {
                put("error", "Autorisation Galerie (Photos) manquante sur le smartphone.")
                put("items", JSONArray())
                put("albums", JSONArray())
                put("totalCount", 0)
                put("hasMore", false)
            }
        }

        val safePage = page.coerceAtLeast(0)
        val safePageSize = pageSize.coerceIn(6, 24)
        val cleanAlbum = albumFilter.trim()

        val projection = ArrayList<String>().apply {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            add(MediaStore.Images.Media.DATE_TAKEN)
            add(MediaStore.Images.Media.DATE_ADDED)
            add(MediaStore.Images.Media.WIDTH)
            add(MediaStore.Images.Media.HEIGHT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Images.Media.RELATIVE_PATH)
                add(MediaStore.Images.Media.IS_PENDING)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.Images.Media.IS_TRASHED)
            }
            @Suppress("DEPRECATION")
            add(MediaStore.Images.Media.DATA)
        }.toTypedArray()

        data class RawPhoto(
            val id: Long,
            val name: String,
            val album: String,
            val dateMs: Long,
            val width: Int,
            val height: Int
        )

        val visiblePhotos = ArrayList<RawPhoto>()
        val albumCounts = LinkedHashMap<String, Int>()
        val resolver = context.contentResolver

        try {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idxId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val idxName = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val idxBucket = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val idxDateTaken = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val idxDateAdded = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val idxWidth = c.getColumnIndex(MediaStore.Images.Media.WIDTH)
                val idxHeight = c.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                val idxRel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                } else -1
                val idxPending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
                } else -1
                val idxTrashed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    c.getColumnIndex(MediaStore.Images.Media.IS_TRASHED)
                } else -1
                @Suppress("DEPRECATION")
                val idxData = c.getColumnIndex(MediaStore.Images.Media.DATA)

                while (c.moveToNext() && visiblePhotos.size < 1500) {
                    if (idxPending >= 0 && c.getInt(idxPending) != 0) continue
                    if (idxTrashed >= 0 && c.getInt(idxTrashed) != 0) continue

                    val relPath = if (idxRel >= 0) c.getString(idxRel) else null
                    val dataPath = if (idxData >= 0) c.getString(idxData) else null
                    val bucket = (if (idxBucket >= 0) c.getString(idxBucket) else null)
                        ?.trim()?.ifEmpty { "Photos" } ?: "Photos"

                    if (isHiddenPath(relPath, dataPath, bucket)) continue

                    albumCounts[bucket] = (albumCounts[bucket] ?: 0) + 1
                    if (cleanAlbum.isNotEmpty() && !bucket.equals(cleanAlbum, ignoreCase = true)) {
                        continue
                    }

                    val id = c.getLong(idxId)
                    val name = (if (idxName >= 0) c.getString(idxName) else null) ?: "Photo_$id.jpg"
                    val taken = if (idxDateTaken >= 0) c.getLong(idxDateTaken) else 0L
                    val added = if (idxDateAdded >= 0) c.getLong(idxDateAdded) * 1000L else 0L
                    val width = if (idxWidth >= 0) c.getInt(idxWidth) else 0
                    val height = if (idxHeight >= 0) c.getInt(idxHeight) else 0

                    visiblePhotos.add(
                        RawPhoto(
                            id = id,
                            name = name,
                            album = bucket,
                            dateMs = if (taken > 0L) taken else added,
                            width = width,
                            height = height
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            return JSONObject().apply {
                put("error", "Lecture galerie impossible : ${t.message}")
                put("items", JSONArray())
                put("albums", JSONArray())
                put("totalCount", 0)
                put("hasMore", false)
            }
        }

        val start = safePage * safePageSize
        val slice = if (start < visiblePhotos.size) {
            visiblePhotos.subList(start, minOf(visiblePhotos.size, start + safePageSize))
        } else {
            emptyList()
        }

        val itemsArr = JSONArray()
        for (p in slice) {
            val thumb = loadThumbnailBase64(context, p.id) ?: continue
            itemsArr.put(
                JSONObject().apply {
                    put("id", p.id.toString())
                    put("name", p.name)
                    put("album", p.album)
                    put("dateTaken", p.dateMs)
                    put("width", p.width)
                    put("height", p.height)
                    put("thumbBase64", thumb)
                }
            )
        }

        val albumsArr = JSONArray()
        for ((alb, cnt) in albumCounts.entries.sortedByDescending { it.value }.take(16)) {
            albumsArr.put(
                JSONObject().apply {
                    put("name", alb)
                    put("count", cnt)
                }
            )
        }

        return JSONObject().apply {
            put("items", itemsArr)
            put("albums", albumsArr)
            put("page", safePage)
            put("totalCount", visiblePhotos.size)
            put("hasMore", start + slice.size < visiblePhotos.size)
            put("quarantineCount", readQuarantineIndex(context).length())
            put("canManageFiles", hasStorageManagePermission(context))
        }
    }

    /* ==================== REMOTE DELETE & LOCKED QUARANTINE VAULT ==================== */

    private fun getQuarantineDir(context: Context): File {
        val dir = File(context.applicationContext.filesDir, "quarantine_vault")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getQuarantineIndexFile(context: Context): File {
        return File(getQuarantineDir(context), "index.json")
    }

    private fun readQuarantineIndex(context: Context): JSONArray {
        return try {
            val f = getQuarantineIndexFile(context)
            if (!f.exists()) JSONArray() else JSONArray(f.readText())
        } catch (_: Throwable) {
            JSONArray()
        }
    }

    private fun writeQuarantineIndex(context: Context, arr: JSONArray) {
        try {
            getQuarantineIndexFile(context).writeText(arr.toString())
        } catch (_: Throwable) {
        }
    }

    private fun lookupMediaFileInfo(context: Context, mediaId: Long): Triple<String?, String, String> {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
        val cols = ArrayList<String>().apply {
            add(MediaStore.Images.Media.DISPLAY_NAME)
            add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            @Suppress("DEPRECATION")
            add(MediaStore.Images.Media.DATA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Images.Media.RELATIVE_PATH)
            }
        }.toTypedArray()

        return try {
            context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return Triple(null, "Photo_$mediaId.jpg", "Photos")
                val name = c.getString(0)?.takeIf { it.isNotBlank() } ?: "Photo_$mediaId.jpg"
                val album = c.getString(1)?.takeIf { it.isNotBlank() } ?: "Photos"
                var path = c.getString(2)?.takeIf { it.isNotBlank() }
                if (path == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && c.columnCount > 3) {
                    val rel = c.getString(3)?.trim().orEmpty()
                    if (rel.isNotEmpty()) {
                        @Suppress("DEPRECATION")
                        val root = Environment.getExternalStorageDirectory()
                        val candidate = File(File(root, rel), name)
                        path = candidate.absolutePath
                    }
                }
                Triple(path, name, album)
            } ?: Triple(null, "Photo_$mediaId.jpg", "Photos")
        } catch (_: Throwable) {
            Triple(null, "Photo_$mediaId.jpg", "Photos")
        }
    }

    fun deletePublicPhoto(context: Context, mediaIdStr: String): Pair<Boolean, String?> {
        val mediaId = mediaIdStr.toLongOrNull() ?: return Pair(false, "Identifiant photo invalide.")
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
        val (dataPath, _, _) = lookupMediaFileInfo(context, mediaId)

        var fileDeleted = false
        var storeDeleted = false
        var fileExisted = false

        // 1. Direct filesystem removal when Manage Files permission is granted (works silently on Android 11-15!)
        if (!dataPath.isNullOrBlank()) {
            try {
                val f = File(dataPath)
                fileExisted = f.exists()
                if (fileExisted && f.delete()) {
                    fileDeleted = true
                    try {
                        val parentPath = f.parentFile?.absolutePath
                        val toScan = if (parentPath != null) arrayOf(dataPath, parentPath) else arrayOf(dataPath)
                        MediaScannerConnection.scanFile(context, toScan, null, null)
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }

        // 2. Also remove MediaStore row (works on own files & Android <=10 or with All Files Access)
        try {
            val rows = context.contentResolver.delete(uri, null, null)
            if (rows > 0) storeDeleted = true
        } catch (_: Throwable) {
        }

        // 3. Re-verify if file disappeared
        val goneFromDisk = if (!dataPath.isNullOrBlank()) {
            try {
                !File(dataPath).exists()
            } catch (_: Throwable) {
                false
            }
        } else {
            storeDeleted
        }

        val deleted = fileDeleted || (storeDeleted && (!fileExisted || goneFromDisk))

        return if (deleted) {
            Pair(true, null)
        } else if (!hasStorageManagePermission(context)) {
            Pair(
                false,
                "Activez « Gestion photos » une fois dans l’appli SentinelCam sur le téléphone pour autoriser la suppression et la quarantaine."
            )
        } else {
            Pair(false, "Suppression impossible pour cette image.")
        }
    }

    fun quarantinePhoto(context: Context, mediaIdStr: String): Pair<Boolean, String?> {
        if (!hasGalleryPermission(context)) {
            return Pair(false, "Autorisation Photos manquante.")
        }
        val mediaId = mediaIdStr.toLongOrNull() ?: return Pair(false, "Identifiant photo invalide.")
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
        val (_, name, album) = lookupMediaFileInfo(context, mediaId)

        val qId = "q_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val targetFile = File(getQuarantineDir(context), "$qId.jpg")

        // 1. Copy full original bytes into app-private locked quarantine directory
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return Pair(false, "Lecture de la photo impossible.")
            input.use { src ->
                FileOutputStream(targetFile).use { dst ->
                    src.copyTo(dst)
                }
            }
        } catch (t: Throwable) {
            targetFile.delete()
            return Pair(false, "Copie en quarantaine impossible : ${t.message}")
        }

        // 2. Remove public copy from Gallery so child can no longer see or open it anywhere
        val (removed, err) = deletePublicPhoto(context, mediaIdStr)
        if (!removed) {
            targetFile.delete()
            return Pair(false, err ?: "Impossible de retirer la photo de la galerie.")
        }

        // 3. Record metadata in locked vault index
        val idx = readQuarantineIndex(context)
        val entry = JSONObject().apply {
            put("id", qId)
            put("name", name)
            put("album", album)
            put("file", targetFile.name)
            put("quarantinedAt", System.currentTimeMillis())
        }
        val next = JSONArray().apply {
            put(entry)
            for (i in 0 until idx.length()) put(idx.getJSONObject(i))
        }
        writeQuarantineIndex(context, next)
        return Pair(true, null)
    }

    fun listQuarantineItems(context: Context): JSONObject {
        val dir = getQuarantineDir(context)
        val idx = readQuarantineIndex(context)
        val items = JSONArray()
        val kept = JSONArray()

        for (i in 0 until idx.length()) {
            val obj = idx.optJSONObject(i) ?: continue
            val fileName = obj.optString("file", "")
            val f = File(dir, fileName)
            if (!f.exists()) continue
            kept.put(obj)
            val thumb = loadFileThumbnailBase64(f) ?: continue
            items.put(
                JSONObject().apply {
                    put("id", obj.optString("id"))
                    put("name", obj.optString("name", "Photo.jpg"))
                    put("album", obj.optString("album", "Quarantaine"))
                    put("dateTaken", obj.optLong("quarantinedAt", f.lastModified()))
                    put("width", 0)
                    put("height", 0)
                    put("thumbBase64", thumb)
                    put("quarantined", true)
                }
            )
        }
        if (kept.length() != idx.length()) {
            writeQuarantineIndex(context, kept)
        }
        return JSONObject().apply {
            put("items", items)
            put("totalCount", items.length())
        }
    }

    fun loadQuarantinePhotoBase64(context: Context, qId: String): Pair<String?, String?> {
        val dir = getQuarantineDir(context)
        val idx = readQuarantineIndex(context)
        var target: File? = null
        for (i in 0 until idx.length()) {
            val obj = idx.optJSONObject(i) ?: continue
            if (obj.optString("id") == qId) {
                target = File(dir, obj.optString("file", ""))
                break
            }
        }
        val f = target ?: return Pair(null, "Média introuvable en quarantaine.")
        if (!f.exists()) return Pair(null, "Fichier introuvable.")
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            var sample = 1
            while (maxDim / sample > 1280) sample *= 2
            val raw = BitmapFactory.decodeFile(
                f.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return Pair(null, "Image illisible.")
            val out = ByteArrayOutputStream()
            raw.compress(Bitmap.CompressFormat.JPEG, 76, out)
            raw.recycle()
            Pair(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), null)
        } catch (t: Throwable) {
            Pair(null, "Erreur lecture quarantaine : ${t.message}")
        }
    }

    fun restoreQuarantinedPhoto(context: Context, qId: String): Pair<Boolean, String?> {
        val dir = getQuarantineDir(context)
        val idx = readQuarantineIndex(context)
        var found: JSONObject? = null
        val remaining = JSONArray()
        for (i in 0 until idx.length()) {
            val obj = idx.optJSONObject(i) ?: continue
            if (obj.optString("id") == qId && found == null) {
                found = obj
            } else {
                remaining.put(obj)
            }
        }
        val entry = found ?: return Pair(false, "Média introuvable.")
        val f = File(dir, entry.optString("file", ""))
        if (!f.exists()) {
            writeQuarantineIndex(context, remaining)
            return Pair(false, "Fichier introuvable.")
        }

        return try {
            val name = entry.optString("name", "Restored_${System.currentTimeMillis()}.jpg")
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/SentinelCam")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val inserted = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return Pair(false, "Restauration impossible.")
            resolver.openOutputStream(inserted)?.use { out ->
                f.inputStream().use { inp -> inp.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(inserted, done, null, null)
            }
            f.delete()
            writeQuarantineIndex(context, remaining)
            Pair(true, null)
        } catch (t: Throwable) {
            Pair(false, "Erreur restauration : ${t.message}")
        }
    }

    fun deleteQuarantinedPhoto(context: Context, qId: String): Pair<Boolean, String?> {
        val dir = getQuarantineDir(context)
        val idx = readQuarantineIndex(context)
        val remaining = JSONArray()
        var removed = false
        for (i in 0 until idx.length()) {
            val obj = idx.optJSONObject(i) ?: continue
            if (obj.optString("id") == qId) {
                File(dir, obj.optString("file", "")).delete()
                removed = true
            } else {
                remaining.put(obj)
            }
        }
        writeQuarantineIndex(context, remaining)
        return if (removed) Pair(true, null) else Pair(false, "Média introuvable.")
    }

    private fun loadFileThumbnailBase64(file: File): String? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            var sample = 1
            while (maxDim / sample > 240) sample *= 2
            val bmp = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null
            val scale = 150f / maxOf(bmp.width, bmp.height).coerceAtLeast(1)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bmp
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 52, out)
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Throwable) {
            null
        }
    }

    fun loadHighResPhotoBase64(context: Context, mediaIdStr: String): Pair<String?, String?> {
        if (!hasGalleryPermission(context)) {
            return Pair(null, "Autorisation Galerie manquante.")
        }
        val mediaId = mediaIdStr.toLongOrNull() ?: return Pair(null, "Identifiant photo invalide.")
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)

        // Re-verify it is not hidden / trashed
        try {
            val cols = ArrayList<String>().apply {
                add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Images.Media.RELATIVE_PATH)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.Images.Media.IS_TRASHED)
                @Suppress("DEPRECATION")
                add(MediaStore.Images.Media.DATA)
            }.toTypedArray()

            context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return Pair(null, "Photo introuvable.")
                val idxBucket = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val idxRel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                } else -1
                val idxTrash = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    c.getColumnIndex(MediaStore.Images.Media.IS_TRASHED)
                } else -1
                @Suppress("DEPRECATION")
                val idxData = c.getColumnIndex(MediaStore.Images.Media.DATA)

                if (idxTrash >= 0 && c.getInt(idxTrash) != 0) return Pair(null, "Photo masquée ou supprimée.")
                val bucket = if (idxBucket >= 0) c.getString(idxBucket) else null
                val rel = if (idxRel >= 0) c.getString(idxRel) else null
                val data = if (idxData >= 0) c.getString(idxData) else null
                if (isHiddenPath(rel, data, bucket)) {
                    return Pair(null, "Dossier masqué exclu.")
                }
            }
        } catch (_: Throwable) {
        }

        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            var sample = 1
            while (maxDim / sample > 1280) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return Pair(null, "Lecture de l’image impossible.")

            val targetMax = 1080
            val scale = targetMax.toFloat() / maxOf(raw.width, raw.height).coerceAtLeast(1)
            val bmp = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    raw,
                    (raw.width * scale).toInt().coerceAtLeast(1),
                    (raw.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                raw
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 74, out)
            if (bmp !== raw) bmp.recycle()
            raw.recycle()
            Pair(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), null)
        } catch (t: Throwable) {
            Pair(null, "Erreur chargement photo : ${t.message}")
        }
    }

    private fun loadThumbnailBase64(context: Context, mediaId: Long): String? {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
        val bmp: Bitmap? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(160, 160), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Thumbnails.getThumbnail(
                    context.contentResolver,
                    mediaId,
                    MediaStore.Images.Thumbnails.MINI_KIND,
                    null
                )
            }
        } catch (_: Throwable) {
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            } catch (_: Throwable) {
                null
            }
        }
        if (bmp == null) return null
        return try {
            val maxDim = 150
            val scale = maxDim.toFloat() / maxOf(bmp.width, bmp.height).coerceAtLeast(1)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bmp
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 52, out)
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Throwable) {
            bmp.recycle()
            null
        }
    }
}
