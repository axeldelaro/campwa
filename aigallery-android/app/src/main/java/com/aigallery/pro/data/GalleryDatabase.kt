package com.aigallery.pro.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * 100% Local On-Device SQLite Database for Simple Gallery AI Pro:
 * - Persists favorites, Tinder swipe ratings, 96-D visual feature vectors, predicted AI favorite scores,
 *   256-D aligned face embeddings, clustered Person profiles (with VIP/Ignored/Affinity stats),
 *   online logistic regression weights, folder pin/exclude/affinity metadata, and Recycle Bin items!
 */
class GalleryDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE media_meta (
                path TEXT PRIMARY KEY,
                is_fav INTEGER DEFAULT 0,
                swipe_rating INTEGER DEFAULT 0,
                swiped_at INTEGER DEFAULT 0,
                ai_score INTEGER DEFAULT -1,
                ai_reason TEXT DEFAULT '',
                feature_vec BLOB,
                indexed_mtime INTEGER DEFAULT 0,
                face_count INTEGER DEFAULT 0,
                smile_avg REAL DEFAULT 0.0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE persons (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT DEFAULT '',
                avatar_path TEXT DEFAULT '',
                proto_vec BLOB,
                sample_count INTEGER DEFAULT 1,
                likes INTEGER DEFAULT 0,
                dislikes INTEGER DEFAULT 0,
                super_likes INTEGER DEFAULT 0,
                is_vip INTEGER DEFAULT 0,
                is_ignored INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE faces (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                media_path TEXT NOT NULL,
                person_id INTEGER NOT NULL,
                face_vec BLOB,
                avatar_path TEXT DEFAULT '',
                confidence REAL DEFAULT 1.0,
                smile_prob REAL DEFAULT 0.0,
                eye_prob REAL DEFAULT 0.8,
                face_ratio REAL DEFAULT 0.1
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_faces_media ON faces(media_path)")
        db.execSQL("CREATE INDEX idx_faces_person ON faces(person_id)")

        db.execSQL(
            """
            CREATE TABLE ai_model (
                id INTEGER PRIMARY KEY,
                weights BLOB,
                bias REAL DEFAULT 0.0,
                train_steps INTEGER DEFAULT 0,
                updated_at INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE folder_prefs (
                folder_path TEXT PRIMARY KEY,
                is_pinned INTEGER DEFAULT 0,
                is_excluded INTEGER DEFAULT 0,
                likes INTEGER DEFAULT 0,
                dislikes INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE recycle_bin (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                original_path TEXT NOT NULL,
                trash_path TEXT NOT NULL,
                display_name TEXT NOT NULL,
                media_type TEXT NOT NULL,
                trashed_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Initial v1 schema
    }

    // ==================== MEDIA META ====================

    @Synchronized
    fun loadAllMediaMeta(): Map<String, MediaMetaRow> {
        val map = HashMap<String, MediaMetaRow>(512)
        readableDatabase.rawQuery(
            "SELECT path, is_fav, swipe_rating, swiped_at, ai_score, ai_reason, feature_vec, indexed_mtime, face_count, smile_avg FROM media_meta",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val p = c.getString(0)
                map[p] = MediaMetaRow(
                    path = p,
                    isFavorite = c.getInt(1) == 1,
                    swipeRating = c.getInt(2),
                    swipedAtMs = c.getLong(3),
                    aiScorePct = c.getInt(4),
                    aiReason = c.getString(5).orEmpty(),
                    featureVec = bytesToFloatArray(c.getBlob(6)),
                    indexedMtimeSec = c.getLong(7),
                    faceCount = c.getInt(8),
                    smileAvg = c.getFloat(9)
                )
            }
        }
        return map
    }

    @Synchronized
    fun getMediaMeta(path: String): MediaMetaRow? {
        readableDatabase.rawQuery(
            "SELECT path, is_fav, swipe_rating, swiped_at, ai_score, ai_reason, feature_vec, indexed_mtime, face_count, smile_avg FROM media_meta WHERE path=?",
            arrayOf(path)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return MediaMetaRow(
                path = c.getString(0),
                isFavorite = c.getInt(1) == 1,
                swipeRating = c.getInt(2),
                swipedAtMs = c.getLong(3),
                aiScorePct = c.getInt(4),
                aiReason = c.getString(5).orEmpty(),
                featureVec = bytesToFloatArray(c.getBlob(6)),
                indexedMtimeSec = c.getLong(7),
                faceCount = c.getInt(8),
                smileAvg = c.getFloat(9)
            )
        }
    }

    @Synchronized
    fun setFavorite(path: String, fav: Boolean) {
        val cur = getMediaMeta(path)
        val cv = ContentValues().apply {
            put("path", path)
            put("is_fav", if (fav) 1 else 0)
            if (cur == null) {
                put("swipe_rating", if (fav) 1 else 0)
                put("swiped_at", if (fav) System.currentTimeMillis() else 0L)
                put("ai_score", -1)
                put("ai_reason", "")
                put("indexed_mtime", 0L)
                put("face_count", 0)
                put("smile_avg", 0f)
            }
        }
        if (cur == null) writableDatabase.insertWithOnConflict("media_meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        else writableDatabase.update("media_meta", cv, "path=?", arrayOf(path))
    }

    @Synchronized
    fun recordSwipeRating(path: String, rating: Int, markFav: Boolean) {
        val cur = getMediaMeta(path)
        val cv = ContentValues().apply {
            put("path", path)
            put("swipe_rating", rating)
            put("swiped_at", System.currentTimeMillis())
            put("is_fav", if (markFav) 1 else 0)
            if (cur == null) {
                put("ai_score", -1); put("ai_reason", "")
                put("indexed_mtime", 0L); put("face_count", 0); put("smile_avg", 0f)
            }
        }
        if (cur == null) writableDatabase.insertWithOnConflict("media_meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        else writableDatabase.update("media_meta", cv, "path=?", arrayOf(path))
    }

    @Synchronized
    fun saveIndexedFeatures(path: String, mtimeSec: Long, featureVec: FloatArray, faceCount: Int, smileAvg: Float) {
        val cur = getMediaMeta(path)
        val cv = ContentValues().apply {
            put("path", path)
            put("feature_vec", floatArrayToBytes(featureVec))
            put("indexed_mtime", mtimeSec)
            put("face_count", faceCount)
            put("smile_avg", smileAvg)
            if (cur == null) {
                put("is_fav", 0); put("swipe_rating", 0); put("swiped_at", 0L)
                put("ai_score", -1); put("ai_reason", "")
            }
        }
        if (cur == null) writableDatabase.insertWithOnConflict("media_meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        else writableDatabase.update("media_meta", cv, "path=?", arrayOf(path))
    }

    @Synchronized
    fun updateAiScoresBatch(scores: Map<String, Pair<Int, String>>) {
        if (scores.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stmt = db.compileStatement("UPDATE media_meta SET ai_score=?, ai_reason=? WHERE path=?")
            for ((path, pair) in scores) {
                stmt.clearBindings()
                stmt.bindLong(1, pair.first.toLong())
                stmt.bindString(2, pair.second)
                stmt.bindString(3, path)
                stmt.executeUpdateDelete()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun renameMediaPath(oldPath: String, newPath: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv1 = ContentValues().apply { put("path", newPath) }
            db.update("media_meta", cv1, "path=?", arrayOf(oldPath))
            val cv2 = ContentValues().apply { put("media_path", newPath) }
            db.update("faces", cv2, "media_path=?", arrayOf(oldPath))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun deleteMediaRecords(path: String) {
        writableDatabase.delete("media_meta", "path=?", arrayOf(path))
        writableDatabase.delete("faces", "media_path=?", arrayOf(path))
    }

    // ==================== FACES & PERSON CLUSTERS ====================

    @Synchronized
    fun loadMediaToPersonsMap(): Map<String, List<Long>> {
        val map = HashMap<String, MutableList<Long>>()
        readableDatabase.rawQuery("SELECT media_path, person_id FROM faces", null).use { c ->
            while (c.moveToNext()) {
                val p = c.getString(0)
                val pid = c.getLong(1)
                val list = map.getOrPut(p) { ArrayList(2) }
                if (pid !in list) list.add(pid)
            }
        }
        return map
    }

    @Synchronized
    fun loadAllPersons(): List<PersonProfile> {
        val countMap = HashMap<Long, Int>()
        readableDatabase.rawQuery("SELECT person_id, COUNT(DISTINCT media_path) FROM faces GROUP BY person_id", null).use { c ->
            while (c.moveToNext()) countMap[c.getLong(0)] = c.getInt(1)
        }
        val list = ArrayList<PersonProfile>()
        readableDatabase.rawQuery(
            "SELECT id, name, avatar_path, proto_vec, likes, dislikes, super_likes, is_vip, is_ignored FROM persons ORDER BY is_vip DESC, likes DESC, id ASC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val cnt = countMap[id] ?: 0
                if (cnt <= 0) continue
                list.add(
                    PersonProfile(
                        id = id,
                        name = c.getString(1).orEmpty(),
                        avatarPath = c.getString(2).orEmpty(),
                        faceCount = cnt,
                        likesCount = c.getInt(4),
                        dislikesCount = c.getInt(5),
                        superLikesCount = c.getInt(6),
                        isVip = c.getInt(7) == 1,
                        isIgnored = c.getInt(8) == 1,
                        prototypeEmbedding = bytesToFloatArray(c.getBlob(3)) ?: FloatArray(0)
                    )
                )
            }
        }
        return list.sortedWith(compareByDescending<PersonProfile> { it.isVip }.thenByDescending { it.faceCount })
    }

    @Synchronized
    fun loadFacesForPerson(personId: Long): List<DetectedFaceRecord> {
        val list = ArrayList<DetectedFaceRecord>()
        readableDatabase.rawQuery(
            "SELECT id, media_path, person_id, face_vec, avatar_path, confidence, smile_prob, eye_prob, face_ratio FROM faces WHERE person_id=?",
            arrayOf(personId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    DetectedFaceRecord(
                        faceId = c.getLong(0),
                        mediaPath = c.getString(1),
                        personId = c.getLong(2),
                        embedding = bytesToFloatArray(c.getBlob(3)) ?: FloatArray(0),
                        avatarPath = c.getString(4).orEmpty(),
                        confidence = c.getFloat(5),
                        smileProb = c.getFloat(6),
                        eyeOpenProb = c.getFloat(7),
                        faceRatio = c.getFloat(8)
                    )
                )
            }
        }
        return list
    }

    @Synchronized
    fun loadAllFaceSamplesByPerson(maxPerPerson: Int = 8): Map<Long, List<FloatArray>> {
        val map = HashMap<Long, MutableList<FloatArray>>()
        readableDatabase.rawQuery("SELECT person_id, face_vec FROM faces ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) {
                val pid = c.getLong(0)
                val list = map.getOrPut(pid) { ArrayList() }
                if (list.size >= maxPerPerson) continue
                val vec = bytesToFloatArray(c.getBlob(1))
                if (vec != null && vec.isNotEmpty()) list.add(vec)
            }
        }
        return map
    }

    @Synchronized
    fun clearFacesForMedia(mediaPath: String) {
        writableDatabase.delete("faces", "media_path=?", arrayOf(mediaPath))
    }

    @Synchronized
    fun createPerson(avatarPath: String, initialVec: FloatArray): Long {
        val cv = ContentValues().apply {
            put("name", "")
            put("avatar_path", avatarPath)
            put("proto_vec", floatArrayToBytes(initialVec))
            put("sample_count", 1)
            put("likes", 0); put("dislikes", 0); put("super_likes", 0)
            put("is_vip", 0); put("is_ignored", 0)
        }
        return writableDatabase.insert("persons", null, cv)
    }

    @Synchronized
    fun insertFaceAndUpdatePrototype(
        mediaPath: String,
        personId: Long,
        faceVec: FloatArray,
        avatarPath: String,
        confidence: Float,
        smileProb: Float,
        eyeProb: Float,
        faceRatio: Float
    ) {
        val cv = ContentValues().apply {
            put("media_path", mediaPath)
            put("person_id", personId)
            put("face_vec", floatArrayToBytes(faceVec))
            put("avatar_path", avatarPath)
            put("confidence", confidence)
            put("smile_prob", smileProb)
            put("eye_prob", eyeProb)
            put("face_ratio", faceRatio)
        }
        writableDatabase.insert("faces", null, cv)

        // Update running normalized prototype centroid for this person!
        readableDatabase.rawQuery("SELECT proto_vec, sample_count, avatar_path FROM persons WHERE id=?", arrayOf(personId.toString())).use { c ->
            if (c.moveToFirst()) {
                val oldVec = bytesToFloatArray(c.getBlob(0))
                val cnt = c.getInt(1).coerceAtLeast(1)
                val oldAvatar = c.getString(2).orEmpty()
                val nextVec = if (oldVec != null && oldVec.size == faceVec.size) {
                    val merged = FloatArray(faceVec.size)
                    val wOld = cnt.toFloat().coerceAtMost(24f)
                    for (i in merged.indices) merged[i] = oldVec[i] * wOld + faceVec[i]
                    l2Normalize(merged)
                } else l2Normalize(faceVec.copyOf())
                val ucv = ContentValues().apply {
                    put("proto_vec", floatArrayToBytes(nextVec))
                    put("sample_count", cnt + 1)
                    if (oldAvatar.isBlank() && avatarPath.isNotBlank()) put("avatar_path", avatarPath)
                }
                writableDatabase.update("persons", ucv, "id=?", arrayOf(personId.toString()))
            }
        }
    }

    @Synchronized
    fun renamePerson(personId: Long, newName: String) {
        val cv = ContentValues().apply { put("name", newName.trim()) }
        writableDatabase.update("persons", cv, "id=?", arrayOf(personId.toString()))
    }

    @Synchronized
    fun setPersonFlags(personId: Long, isVip: Boolean, isIgnored: Boolean) {
        val cv = ContentValues().apply {
            put("is_vip", if (isVip) 1 else 0)
            put("is_ignored", if (isIgnored) 1 else 0)
        }
        writableDatabase.update("persons", cv, "id=?", arrayOf(personId.toString()))
    }

    @Synchronized
    fun adjustPersonSwipeStats(personId: Long, dLikes: Int, dDislikes: Int, dSuper: Int) {
        writableDatabase.execSQL(
            "UPDATE persons SET likes=MAX(0, likes+?), dislikes=MAX(0, dislikes+?), super_likes=MAX(0, super_likes+?) WHERE id=?",
            arrayOf(dLikes, dDislikes, dSuper, personId)
        )
    }

    /** Merges sourcePersonId INTO targetPersonId, recomputing exact normalized prototype & combined stats! */
    @Synchronized
    fun mergePersons(sourcePersonId: Long, targetPersonId: Long) {
        if (sourcePersonId == targetPersonId) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            var srcLikes = 0; var srcDis = 0; var srcSuper = 0; var srcVip = 0
            db.rawQuery("SELECT likes, dislikes, super_likes, is_vip FROM persons WHERE id=?", arrayOf(sourcePersonId.toString())).use { c ->
                if (c.moveToFirst()) {
                    srcLikes = c.getInt(0); srcDis = c.getInt(1); srcSuper = c.getInt(2); srcVip = c.getInt(3)
                }
            }
            val fCv = ContentValues().apply { put("person_id", targetPersonId) }
            db.update("faces", fCv, "person_id=?", arrayOf(sourcePersonId.toString()))

            // Recompute exact mean prototype over all faces of targetPersonId
            val allVecs = ArrayList<FloatArray>()
            db.rawQuery("SELECT face_vec FROM faces WHERE person_id=?", arrayOf(targetPersonId.toString())).use { c ->
                while (c.moveToNext()) {
                    bytesToFloatArray(c.getBlob(0))?.let { if (it.isNotEmpty()) allVecs.add(it) }
                }
            }
            val meanVec = if (allVecs.isNotEmpty()) {
                val dim = allVecs[0].size
                val acc = FloatArray(dim)
                for (v in allVecs) {
                    if (v.size == dim) for (i in 0 until dim) acc[i] += v[i]
                }
                l2Normalize(acc)
            } else FloatArray(0)

            db.execSQL(
                "UPDATE persons SET likes=likes+?, dislikes=dislikes+?, super_likes=super_likes+?, is_vip=MAX(is_vip, ?), sample_count=?, proto_vec=? WHERE id=?",
                arrayOf(srcLikes, srcDis, srcSuper, srcVip, allVecs.size.coerceAtLeast(1), floatArrayToBytes(meanVec), targetPersonId)
            )
            db.delete("persons", "id=?", arrayOf(sourcePersonId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun removeFaceFromPerson(faceId: Long, personId: Long) {
        writableDatabase.delete("faces", "id=?", arrayOf(faceId.toString()))
        // Recompute remaining count; delete person if 0 faces left
        readableDatabase.rawQuery("SELECT COUNT(*) FROM faces WHERE person_id=?", arrayOf(personId.toString())).use { c ->
            if (c.moveToFirst() && c.getInt(0) == 0) {
                writableDatabase.delete("persons", "id=?", arrayOf(personId.toString()))
            }
        }
    }

    // ==================== AI MODEL WEIGHTS ====================

    @Synchronized
    fun loadAiModel(dim: Int): AiModelState {
        readableDatabase.rawQuery("SELECT weights, bias, train_steps FROM ai_model WHERE id=1", null).use { c ->
            if (c.moveToFirst()) {
                val w = bytesToFloatArray(c.getBlob(0))
                if (w != null && w.size == dim) {
                    return AiModelState(w, c.getFloat(1), c.getInt(2))
                }
            }
        }
        return AiModelState(FloatArray(dim), 0f, 0)
    }

    @Synchronized
    fun saveAiModel(state: AiModelState) {
        val cv = ContentValues().apply {
            put("id", 1)
            put("weights", floatArrayToBytes(state.weights))
            put("bias", state.bias)
            put("train_steps", state.trainSteps)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("ai_model", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun resetAiLearningOnly() {
        writableDatabase.delete("ai_model", null, null)
        writableDatabase.execSQL("UPDATE media_meta SET swipe_rating=0, swiped_at=0, ai_score=-1, ai_reason=''")
        writableDatabase.execSQL("UPDATE persons SET likes=0, dislikes=0, super_likes=0")
        writableDatabase.execSQL("UPDATE folder_prefs SET likes=0, dislikes=0")
    }

    // ==================== FOLDER PREFS ====================

    @Synchronized
    fun loadAllFolderPrefs(): Map<String, FolderPrefRow> {
        val map = HashMap<String, FolderPrefRow>()
        readableDatabase.rawQuery("SELECT folder_path, is_pinned, is_excluded, likes, dislikes FROM folder_prefs", null).use { c ->
            while (c.moveToNext()) {
                val fp = c.getString(0)
                map[fp] = FolderPrefRow(fp, c.getInt(1) == 1, c.getInt(2) == 1, c.getInt(3), c.getInt(4))
            }
        }
        return map
    }

    @Synchronized
    fun setFolderPinned(folderPath: String, pinned: Boolean) {
        val cur = loadAllFolderPrefs()[folderPath]
        val cv = ContentValues().apply {
            put("folder_path", folderPath)
            put("is_pinned", if (pinned) 1 else 0)
            put("is_excluded", if (cur?.isExcluded == true) 1 else 0)
            put("likes", cur?.likes ?: 0)
            put("dislikes", cur?.dislikes ?: 0)
        }
        writableDatabase.insertWithOnConflict("folder_prefs", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun setFolderExcluded(folderPath: String, excluded: Boolean) {
        val cur = loadAllFolderPrefs()[folderPath]
        val cv = ContentValues().apply {
            put("folder_path", folderPath)
            put("is_pinned", if (cur?.isPinned == true) 1 else 0)
            put("is_excluded", if (excluded) 1 else 0)
            put("likes", cur?.likes ?: 0)
            put("dislikes", cur?.dislikes ?: 0)
        }
        writableDatabase.insertWithOnConflict("folder_prefs", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun adjustFolderSwipeStats(folderPath: String, dLikes: Int, dDislikes: Int) {
        val cur = loadAllFolderPrefs()[folderPath]
        val cv = ContentValues().apply {
            put("folder_path", folderPath)
            put("is_pinned", if (cur?.isPinned == true) 1 else 0)
            put("is_excluded", if (cur?.isExcluded == true) 1 else 0)
            put("likes", ((cur?.likes ?: 0) + dLikes).coerceAtLeast(0))
            put("dislikes", ((cur?.dislikes ?: 0) + dDislikes).coerceAtLeast(0))
        }
        writableDatabase.insertWithOnConflict("folder_prefs", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ==================== RECYCLE BIN ====================

    @Synchronized
    fun insertTrashedItem(originalPath: String, trashPath: String, name: String, type: MediaType): Long {
        val cv = ContentValues().apply {
            put("original_path", originalPath)
            put("trash_path", trashPath)
            put("display_name", name)
            put("media_type", type.id)
            put("trashed_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("recycle_bin", null, cv)
    }

    @Synchronized
    fun loadRecycleBin(): List<TrashedMedia> {
        val list = ArrayList<TrashedMedia>()
        readableDatabase.rawQuery(
            "SELECT id, original_path, trash_path, display_name, media_type, trashed_at FROM recycle_bin ORDER BY trashed_at DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    TrashedMedia(
                        trashId = c.getLong(0),
                        originalPath = c.getString(1),
                        trashFilePath = c.getString(2),
                        name = c.getString(3),
                        type = MediaType.fromId(c.getString(4)),
                        trashedAtMs = c.getLong(5)
                    )
                )
            }
        }
        return list
    }

    @Synchronized
    fun removeRecycleBinRow(trashId: Long) {
        writableDatabase.delete("recycle_bin", "id=?", arrayOf(trashId.toString()))
    }

    companion object {
        private const val DB_NAME = "simple_gallery_ai_pro.db"
        private const val DB_VERSION = 1

        fun floatArrayToBytes(arr: FloatArray): ByteArray {
            val bb = ByteBuffer.allocate(arr.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in arr) bb.putFloat(v)
            return bb.array()
        }

        fun bytesToFloatArray(bytes: ByteArray?): FloatArray? {
            if (bytes == null || bytes.size < 4 || bytes.size % 4 != 0) return null
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val arr = FloatArray(bytes.size / 4)
            for (i in arr.indices) arr[i] = bb.float
            return arr
        }

        fun l2Normalize(vec: FloatArray): FloatArray {
            var sumSq = 0.0
            for (v in vec) sumSq += v * v
            val norm = sqrt(sumSq).toFloat()
            if (norm > 1e-6f) {
                for (i in vec.indices) vec[i] /= norm
            }
            return vec
        }

        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.isEmpty() || a.size != b.size) return 0f
            var dot = 0f
            for (i in a.indices) dot += a[i] * b[i]
            return dot.coerceIn(-1f, 1f)
        }
    }
}
