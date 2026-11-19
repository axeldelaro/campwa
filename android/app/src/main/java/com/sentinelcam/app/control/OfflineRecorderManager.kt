package com.sentinelcam.app.control

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Base64
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Offline Deferred Ambient Audio & Camera Video Recorder (v23):
 * - Runs completely independently of the Parent Website (continues recording even offline or screen OFF!).
 * - Stores recordings inside an app-private locked vault (`filesDir/recordings_vault`) invisible from
 *   the child's Gallery and File Manager.
 * - Hard auto-stop fail-safe timer (audio: 1..30 min, video: 1..10 min) + storage guard (>= 200 MB free).
 * - Automatic wipe of all recordings when the Parent unpairs the device.
 */
object OfflineRecorderManager {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaRecorder: MediaRecorder? = null
    private var stopTimerRunnable: Runnable? = null

    @Volatile
    var isRecording: Boolean = false
        private set

    @Volatile
    var activeMode: String = "audio" // "audio" | "video"
        private set

    @Volatile
    var activeId: String? = null
        private set

    @Volatile
    var startedAtMs: Long = 0L
        private set

    @Volatile
    var targetEndAtMs: Long = 0L
        private set

    private var activeFile: File? = null
    private var activeFacing: String = "back"
    private var onStateChangedCb: (() -> Unit)? = null

    private fun getVaultDir(context: Context): File {
        val dir = File(context.applicationContext.filesDir, "recordings_vault")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getIndexFile(context: Context): File = File(getVaultDir(context), "index.json")

    private fun readIndex(context: Context): JSONArray {
        return try {
            val f = getIndexFile(context)
            if (!f.exists()) JSONArray() else JSONArray(f.readText())
        } catch (_: Throwable) {
            JSONArray()
        }
    }

    private fun writeIndex(context: Context, arr: JSONArray) {
        try {
            getIndexFile(context).writeText(arr.toString())
        } catch (_: Throwable) {
        }
    }

    private fun freeStorageMb(): Long {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            (stat.availableBlocksLong * stat.blockSizeLong) / (1024L * 1024L)
        } catch (_: Throwable) {
            1024L
        }
    }

    @Synchronized
    fun startRecording(
        context: Context,
        mode: String = "audio",
        durationMinutes: Int = 5,
        facing: String = "back",
        onStateChanged: () -> Unit = {}
    ): Pair<Boolean, String?> {
        val appCtx = context.applicationContext
        val cleanMode = if (mode == "video") "video" else "audio"

        val hasMic = ContextCompat.checkSelfPermission(
            appCtx,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            return Pair(false, "Autorisation Micro manquante sur le téléphone.")
        }
        if (cleanMode == "video") {
            val hasCam = ContextCompat.checkSelfPermission(
                appCtx,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasCam) {
                return Pair(false, "Autorisation Caméra manquante sur le téléphone.")
            }
        }

        if (freeStorageMb() < 200L) {
            return Pair(false, "Espace de stockage insuffisant sur le téléphone (< 200 Mo libres).")
        }

        // Stop any existing recording cleanly first
        if (isRecording) {
            stopRecording(appCtx)
        }

        val maxMin = if (cleanMode == "video") 10 else 30
        val safeMin = durationMinutes.coerceIn(1, maxMin)
        val recId = "rec_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val ext = if (cleanMode == "video") "mp4" else "m4a"
        val outFile = File(getVaultDir(appCtx), "$recId.$ext")

        return try {
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(appCtx)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            if (cleanMode == "video") {
                @Suppress("DEPRECATION")
                mr.setAudioSource(MediaRecorder.AudioSource.MIC)
                @Suppress("DEPRECATION")
                mr.setVideoSource(MediaRecorder.VideoSource.CAMERA)
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioEncodingBitRate(32_000)
                mr.setAudioSamplingRate(22_050)
                mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                mr.setVideoSize(640, 480)
                mr.setVideoFrameRate(15)
                mr.setVideoEncodingBitRate(380_000)
                mr.setOrientationHint(if (facing == "front") 270 else 90)
            } else {
                mr.setAudioSource(MediaRecorder.AudioSource.MIC)
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioChannels(1)
                mr.setAudioSamplingRate(22_050)
                mr.setAudioEncodingBitRate(32_000)
            }

            mr.setOutputFile(outFile.absolutePath)
            mr.setMaxDuration(safeMin * 60 * 1000)
            // Strict max file size guard (18 MB audio / 22 MB video) so transfer stays fast & safe
            mr.setMaxFileSize(if (cleanMode == "video") 22L * 1024L * 1024L else 18L * 1024L * 1024L)
            mr.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                ) {
                    mainHandler.post { stopRecording(appCtx) }
                }
            }
            mr.prepare()
            mr.start()

            val now = System.currentTimeMillis()
            mediaRecorder = mr
            activeFile = outFile
            activeId = recId
            activeMode = cleanMode
            activeFacing = facing
            startedAtMs = now
            targetEndAtMs = now + safeMin * 60_000L
            isRecording = true
            onStateChangedCb = onStateChanged

            val stopTask = Runnable { stopRecording(appCtx) }
            stopTimerRunnable = stopTask
            mainHandler.postDelayed(stopTask, safeMin * 60_000L + 1_500L)

            onStateChanged()
            Pair(true, null)
        } catch (t: Throwable) {
            try { outFile.delete() } catch (_: Throwable) {}
            cleanupRecorderObjects()
            Pair(false, "Impossible de démarrer l’enregistrement : ${t.message ?: "capteur occupé"}")
        }
    }

    @Synchronized
    fun stopRecording(context: Context): Pair<Boolean, String?> {
        stopTimerRunnable?.let { mainHandler.removeCallbacks(it) }
        stopTimerRunnable = null

        val wasRecording = isRecording
        val rec = mediaRecorder
        val file = activeFile
        val recId = activeId
        val mode = activeMode
        val started = startedAtMs
        val cb = onStateChangedCb

        isRecording = false
        mediaRecorder = null
        activeFile = null
        activeId = null
        startedAtMs = 0L
        targetEndAtMs = 0L
        onStateChangedCb = null

        if (rec != null) {
            try { rec.stop() } catch (_: Throwable) {}
            try { rec.reset() } catch (_: Throwable) {}
            try { rec.release() } catch (_: Throwable) {}
        }

        if (wasRecording && file != null && recId != null && file.exists() && file.length() > 1024L) {
            val durSec = ((System.currentTimeMillis() - started) / 1000L).coerceAtLeast(1L)
            val idx = readIndex(context)
            val entry = JSONObject().apply {
                put("id", recId)
                put("mode", mode)
                put("file", file.name)
                put("mimeType", if (mode == "video") "video/mp4" else "audio/mp4")
                put("startedAt", started)
                put("durationSec", durSec)
                put("sizeBytes", file.length())
            }
            val next = JSONArray().apply {
                put(entry)
                // Keep at most 20 recordings in vault, pruning oldest automatically
                var kept = 1
                for (i in 0 until idx.length()) {
                    val obj = idx.optJSONObject(i) ?: continue
                    if (kept < 20) {
                        put(obj)
                        kept++
                    } else {
                        try {
                            File(getVaultDir(context), obj.optString("file", "")).delete()
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
            writeIndex(context, next)
        } else if (file != null && file.exists() && file.length() <= 1024L) {
            try { file.delete() } catch (_: Throwable) {}
        }

        try { cb?.invoke() } catch (_: Throwable) {}
        return Pair(true, null)
    }

    private fun cleanupRecorderObjects() {
        try { mediaRecorder?.reset() } catch (_: Throwable) {}
        try { mediaRecorder?.release() } catch (_: Throwable) {}
        mediaRecorder = null
        activeFile = null
        activeId = null
        isRecording = false
        startedAtMs = 0L
        targetEndAtMs = 0L
    }

    fun listRecordings(context: Context): JSONObject {
        val dir = getVaultDir(context)
        val idx = readIndex(context)
        val items = JSONArray()
        val kept = JSONArray()

        for (i in 0 until idx.length()) {
            val obj = idx.optJSONObject(i) ?: continue
            val f = File(dir, obj.optString("file", ""))
            if (!f.exists() || f.length() <= 0L) continue
            kept.put(obj)
            items.put(
                JSONObject().apply {
                    put("id", obj.optString("id"))
                    put("mode", obj.optString("mode", "audio"))
                    put("mimeType", obj.optString("mimeType", "audio/mp4"))
                    put("startedAt", obj.optLong("startedAt", f.lastModified()))
                    put("durationSec", obj.optLong("durationSec", 0L))
                    put("sizeBytes", f.length())
                }
            )
        }
        if (kept.length() != idx.length()) {
            writeIndex(context, kept)
        }

        return JSONObject().apply {
            put("items", items)
            put("isRecording", isRecording)
            put("activeMode", activeMode)
            put("activeStartedAt", startedAtMs)
            put("activeTargetEndAt", targetEndAtMs)
        }
    }

    fun loadRecordingBase64(context: Context, recId: String): Triple<String?, String, String?> {
        val dir = getVaultDir(context)
        val idx = readIndex(context)
        var found: JSONObject? = null
        for (i in 0 until idx.length()) {
            val obj = idx.optJSONObject(i) ?: continue
            if (obj.optString("id") == recId) {
                found = obj
                break
            }
        }
        val entry = found ?: return Triple(null, "audio/mp4", "Enregistrement introuvable.")
        val f = File(dir, entry.optString("file", ""))
        val mime = entry.optString("mimeType", "audio/mp4")
        if (!f.exists()) return Triple(null, mime, "Fichier introuvable.")
        if (f.length() > 14L * 1024L * 1024L) {
            return Triple(null, mime, "Fichier trop volumineux (> 14 Mo) pour le transfert rapide.")
        }
        return try {
            val bytes = f.readBytes()
            Triple(Base64.encodeToString(bytes, Base64.NO_WRAP), mime, null)
        } catch (t: Throwable) {
            Triple(null, mime, "Lecture impossible : ${t.message}")
        }
    }

    fun deleteRecording(context: Context, recId: String): Pair<Boolean, String?> {
        val dir = getVaultDir(context)
        val idx = readIndex(context)
        val remaining = JSONArray()
        var removed = false
        for (i in 0 until idx.length()) {
            val obj = idx.optJSONObject(i) ?: continue
            if (obj.optString("id") == recId) {
                try { File(dir, obj.optString("file", "")).delete() } catch (_: Throwable) {}
                removed = true
            } else {
                remaining.put(obj)
            }
        }
        writeIndex(context, remaining)
        return if (removed) Pair(true, null) else Pair(false, "Enregistrement introuvable.")
    }

    @Synchronized
    fun wipeAllOnUnpair(context: Context) {
        try { stopRecording(context) } catch (_: Throwable) {}
        try {
            val dir = getVaultDir(context)
            dir.listFiles()?.forEach { f ->
                try { f.delete() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
        }
    }
}
