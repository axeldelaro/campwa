package com.aigallery.pro.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.aigallery.pro.data.GalleryDatabase
import com.aigallery.pro.data.GalleryPreferences
import com.aigallery.pro.data.MediaItem
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background Incremental On-Device AI & Face Recognition Indexer:
 * - Indexes only unindexed or modified media items (cached permanently in SQLite!).
 * - Extracts 224-D eye-aligned face embeddings -> clusters Persons -> extracts 72-D visual vector ->
 *   rescores all gallery predictions periodically and notifies UI live!
 */
class GalleryAiIndexer(
    private val context: Context,
    private val db: GalleryDatabase,
    private val prefs: GalleryPreferences
) {
    data class Progress(
        val running: Boolean,
        val done: Int,
        val total: Int,
        val personsCount: Int
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val runningFlag = AtomicBoolean(false)
    private val cancelFlag = AtomicBoolean(false)
    private var faceEngine: FaceRecognitionEngine? = null

    fun isIndexing(): Boolean = runningFlag.get()

    fun startIncrementalIndexing(
        allMedia: List<MediaItem>,
        forceReclusterAll: Boolean = false,
        onProgress: (Progress) -> Unit,
        onBatchUpdated: () -> Unit
    ) {
        if (!runningFlag.compareAndSet(false, true)) return
        cancelFlag.set(false)

        executor.execute {
            try {
                if (faceEngine == null) faceEngine = FaceRecognitionEngine(context.applicationContext)
                val engine = faceEngine!!
                val metaMap = db.loadAllMediaMeta()
                val threshold = prefs.faceMatchThresholdPct / 100f

                val toIndex = if (forceReclusterAll) {
                    allMedia
                } else {
                    allMedia.filter { item ->
                        val m = metaMap[item.path]
                        m == null || m.featureVec == null || m.indexedMtimeSec != item.dateModifiedSec
                    }
                }

                val total = toIndex.size
                if (total == 0) {
                    VisualPreferenceAiEngine.rescoreAllMedia(db, allMedia)
                    val pCnt = db.loadAllPersons().size
                    mainHandler.post {
                        onProgress(Progress(false, 0, 0, pCnt))
                        onBatchUpdated()
                    }
                    return@execute
                }

                var done = 0
                for (item in toIndex) {
                    if (cancelFlag.get()) break
                    val bmp = engine.decodeSampledBitmapForAi(item, maxDim = 560)
                    if (bmp != null) {
                        try {
                            val faceOut = engine.analyzeAndClusterFaces(item, bmp, db, matchThreshold = threshold)
                            val feat = VisualPreferenceAiEngine.extractMediaFeatureVector(item, bmp, faceOut)
                            db.saveIndexedFeatures(item.path, item.dateModifiedSec, feat, faceOut.faceCount, faceOut.maxSmileProb)
                        } catch (_: Throwable) {
                        } finally {
                            if (!bmp.isRecycled) bmp.recycle()
                        }
                    }
                    done++

                    if (done % 10 == 0 || done == total) {
                        val pCnt = db.loadAllPersons().size
                        if (done % 20 == 0 || done == total) {
                            VisualPreferenceAiEngine.rescoreAllMedia(db, allMedia)
                            mainHandler.post { onBatchUpdated() }
                        }
                        val snapDone = done
                        mainHandler.post { onProgress(Progress(true, snapDone, total, pCnt)) }
                    }
                }

                val finalPersons = db.loadAllPersons().size
                VisualPreferenceAiEngine.rescoreAllMedia(db, allMedia)
                mainHandler.post {
                    onProgress(Progress(false, done, total, finalPersons))
                    onBatchUpdated()
                }
            } catch (_: Throwable) {
                mainHandler.post { onProgress(Progress(false, 0, 0, db.loadAllPersons().size)) }
            } finally {
                runningFlag.set(false)
            }
        }
    }

    /** Synchronously ensures a single media item is indexed (used on-demand right before Tinder swipe!). */
    fun ensureIndexedAsync(item: MediaItem, onDone: () -> Unit) {
        executor.execute {
            try {
                val cur = db.getMediaMeta(item.path)
                if (cur?.featureVec == null) {
                    if (faceEngine == null) faceEngine = FaceRecognitionEngine(context.applicationContext)
                    val engine = faceEngine!!
                    val bmp = engine.decodeSampledBitmapForAi(item, maxDim = 560)
                    if (bmp != null) {
                        try {
                            val faceOut = engine.analyzeAndClusterFaces(item, bmp, db, prefs.faceMatchThresholdPct / 100f)
                            val feat = VisualPreferenceAiEngine.extractMediaFeatureVector(item, bmp, faceOut)
                            db.saveIndexedFeatures(item.path, item.dateModifiedSec, feat, faceOut.faceCount, faceOut.maxSmileProb)
                        } finally {
                            if (!bmp.isRecycled) bmp.recycle()
                        }
                    }
                }
            } catch (_: Throwable) {}
            mainHandler.post { onDone() }
        }
    }

    fun stopAndRelease() {
        cancelFlag.set(true)
        try { faceEngine?.close() } catch (_: Throwable) {}
        faceEngine = null
    }
}
