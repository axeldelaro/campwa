package com.sentinelcam.app.control

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import com.sentinelcam.app.storage.PortalPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.abs

/**
 * Per-App Visual Activity Timeline Capture Engine (v24):
 * - Captures lightweight screenshots every X seconds (5..120s) ONLY when:
 *   1) Timeline capture is enabled by the Parent
 *   2) Screen is ON
 *   3) Current foreground app is in the Parent's selected monitored apps list
 *   4) Screen visual content actually changed (deduplicates static screens!)
 * - Can safely borrow an active MediaProjection from AiScreenShieldEngine (zero Android 14 conflict!)
 *   OR manage its own MediaProjection when AI Shield is off.
 * - Stores up to 120 compressed JPEGs inside an app-private locked vault (`filesDir/timeline_vault`)
 *   and wipes everything cleanly on unpair.
 */
class VisualTimelineManager(
    context: Context,
    private val prefs: PortalPreferences,
    private val onStateChanged: () -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var mediaProjection: MediaProjection? = null
    private var ownsProjection: Boolean = false
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private var captureW = 540
    private var captureH = 960
    private var lastFrameHash = IntArray(HASH_GRID * HASH_GRID)
    private var hasLastHash = false
    private var lastCapturedPkg = ""

    @Volatile
    var isActive: Boolean = false
        private set

    @Volatile
    private var screenInteractive: Boolean = true
    private var receiverRegistered: Boolean = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenInteractive = false
                    workerHandler?.removeCallbacks(captureTick)
                }

                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    screenInteractive = true
                    if (isActive && prefs.timelineCaptureEnabled) {
                        workerHandler?.removeCallbacks(captureTick)
                        workerHandler?.postDelayed(captureTick, 1_500L)
                    }
                }
            }
        }
    }

    private val captureTick = object : Runnable {
        override fun run() {
            if (!isActive || !screenInteractive) return
            if (!prefs.timelineCaptureEnabled) {
                stop()
                return
            }
            val intervalMs = prefs.timelineIntervalSec.coerceIn(5, 120) * 1000L
            try {
                tryCaptureTick()
            } catch (_: Throwable) {
            }
            if (isActive && screenInteractive) {
                workerHandler?.postDelayed(this, intervalMs)
            }
        }
    }

    fun hasActiveProjection(): Boolean = mediaProjection != null

    fun startOrRefresh(
        sharedProjection: MediaProjection?,
        projectionIntent: Intent?
    ): Boolean {
        if (!prefs.timelineCaptureEnabled || prefs.getTimelineMonitoredPackages().isEmpty()) {
            stop()
            return false
        }

        if (mediaProjection == null) {
            if (sharedProjection != null) {
                mediaProjection = sharedProjection
                ownsProjection = false
            } else {
                val intent = projectionIntent ?: return false
                val mpm = appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val proj = try {
                    mpm.getMediaProjection(Activity.RESULT_OK, intent)
                } catch (_: Throwable) {
                    null
                } ?: return false
                proj.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        mainHandler.post { stop() }
                    }
                }, mainHandler)
                mediaProjection = proj
                ownsProjection = true
            }
        }

        startPipelineIfNeeded()
        screenInteractive = try { powerManager.isInteractive } catch (_: Throwable) { true }
        if (!receiverRegistered) {
            try {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                appContext.registerReceiver(screenReceiver, filter)
                receiverRegistered = true
            } catch (_: Throwable) {
            }
        }
        isActive = true
        workerHandler?.removeCallbacks(captureTick)
        if (screenInteractive) {
            workerHandler?.postDelayed(captureTick, 1_500L)
        }
        onStateChanged()
        return true
    }

    fun stop() {
        isActive = false
        workerHandler?.removeCallbacks(captureTick)
        if (receiverRegistered) {
            try { appContext.unregisterReceiver(screenReceiver) } catch (_: Throwable) {}
            receiverRegistered = false
        }
        teardownPipeline()
        if (ownsProjection) {
            try { mediaProjection?.stop() } catch (_: Throwable) {}
        }
        mediaProjection = null
        ownsProjection = false
        onStateChanged()
    }

    private fun startPipelineIfNeeded() {
        if (virtualDisplay != null && imageReader != null) return
        val proj = mediaProjection ?: return

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay?.getRealMetrics(metrics)
        val screenW = metrics.widthPixels.coerceAtLeast(360)
        val screenH = metrics.heightPixels.coerceAtLeast(640)
        val densityDpi = metrics.densityDpi.coerceAtLeast(160)

        val ratio = 540f / minOf(screenW, screenH).toFloat()
        captureW = (((screenW * ratio).toInt() / 2) * 2).coerceIn(320, 720)
        captureH = (((screenH * ratio).toInt() / 2) * 2).coerceIn(480, 1280)

        val thread = HandlerThread("SentinelVisualTimeline").apply { start() }
        workerThread = thread
        workerHandler = Handler(thread.looper)

        val reader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = proj.createVirtualDisplay(
            "SentinelTimelineDisplay",
            captureW,
            captureH,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            workerHandler
        )
    }

    private fun teardownPipeline() {
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Throwable) {}
        imageReader = null
        try { workerThread?.quitSafely() } catch (_: Throwable) {}
        workerThread = null
        workerHandler = null
    }

    private fun tryCaptureTick() {
        val screenOn = try { powerManager.isInteractive } catch (_: Throwable) { true }
        if (!screenOn) return

        val monitored = prefs.getTimelineMonitoredPackages()
        if (monitored.isEmpty()) return

        val fgPkg = ScreenTimeHelper.detectCurrentForegroundPackage(appContext) ?: return
        if (fgPkg == appContext.packageName || !monitored.contains(fgPkg)) return

        val reader = imageReader ?: return
        val img = reader.acquireLatestImage() ?: return
        val bmp: Bitmap? = try {
            val plane = img.planes[0]
            val buf = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * captureW
            val strideW = captureW + rowPadding / pixelStride
            val raw = Bitmap.createBitmap(strideW, captureH, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(buf)
            if (strideW == captureW) raw else {
                val cropped = Bitmap.createBitmap(raw, 0, 0, captureW, captureH)
                raw.recycle()
                cropped
            }
        } catch (_: Throwable) {
            null
        } finally {
            img.close()
        }

        if (bmp == null) return
        try {
            // Skip duplicate static frames unless the foreground app changed
            val diff = computeHashDiff(bmp)
            if (fgPkg == lastCapturedPkg && hasLastHash && diff < 5.0f) {
                return
            }
            lastCapturedPkg = fgPkg
            val appName = ScreenTimeHelper.resolveAppName(appContext.packageManager, fgPkg)
            saveCapture(appContext, bmp, fgPkg, appName)
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    private fun computeHashDiff(bmp: Bitmap): Float {
        val w = bmp.width
        val h = bmp.height
        val next = IntArray(HASH_GRID * HASH_GRID)
        var sum = 0
        for (gy in 0 until HASH_GRID) {
            val py = ((gy + 0.5f) / HASH_GRID * h).toInt().coerceIn(0, h - 1)
            for (gx in 0 until HASH_GRID) {
                val px = ((gx + 0.5f) / HASH_GRID * w).toInt().coerceIn(0, w - 1)
                val c = bmp.getPixel(px, py)
                val lum = (((c ushr 16) and 0xFF) * 3 + ((c ushr 8) and 0xFF) * 6 + (c and 0xFF)) / 10
                val idx = gy * HASH_GRID + gx
                next[idx] = lum
                if (hasLastHash) sum += abs(lum - lastFrameHash[idx])
            }
        }
        val avg = if (hasLastHash) sum.toFloat() / (HASH_GRID * HASH_GRID) else 100f
        lastFrameHash = next
        hasLastHash = true
        return avg
    }

    companion object {
        private const val HASH_GRID = 10
        private val vaultLock = Any()

        private fun getVaultDir(context: Context): File {
            val dir = File(context.applicationContext.filesDir, "timeline_vault")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        private fun getIndexFile(context: Context): File = File(getVaultDir(context), "index.json")

        private fun readIndexLocked(context: Context): JSONArray {
            return try {
                val f = getIndexFile(context)
                if (!f.exists()) JSONArray() else JSONArray(f.readText())
            } catch (_: Throwable) {
                JSONArray()
            }
        }

        private fun writeIndexLocked(context: Context, arr: JSONArray) {
            try { getIndexFile(context).writeText(arr.toString()) } catch (_: Throwable) {}
        }

        private fun saveCapture(context: Context, bmp: Bitmap, pkg: String, appName: String) {
            synchronized(vaultLock) {
                val id = "tl_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(5)}"
                val file = File(getVaultDir(context), "$id.jpg")
                try {
                    FileOutputStream(file).use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 64, out)
                    }
                } catch (_: Throwable) {
                    try { file.delete() } catch (_: Throwable) {}
                    return
                }

                val idx = readIndexLocked(context)
                val entry = JSONObject().apply {
                    put("id", id)
                    put("packageName", pkg)
                    put("appName", appName)
                    put("file", file.name)
                    put("timestamp", System.currentTimeMillis())
                }
                val next = JSONArray()
                next.put(entry)
                val maxCap = 120
                var kept = 1
                for (i in 0 until idx.length()) {
                    val o = idx.optJSONObject(i) ?: continue
                    if (kept < maxCap) {
                        next.put(o)
                        kept++
                    } else {
                        try { File(getVaultDir(context), o.optString("file", "")).delete() } catch (_: Throwable) {}
                    }
                }
                writeIndexLocked(context, next)
            }
        }

        fun listTimelinePage(
            context: Context,
            prefs: PortalPreferences,
            running: Boolean,
            page: Int = 0,
            pageSize: Int = 18
        ): JSONObject {
            val safePage = page.coerceAtLeast(0)
            val safeSize = pageSize.coerceIn(6, 24)

            val entries = ArrayList<JSONObject>()
            synchronized(vaultLock) {
                val dir = getVaultDir(context)
                val idx = readIndexLocked(context)
                val kept = JSONArray()
                for (i in 0 until idx.length()) {
                    val o = idx.optJSONObject(i) ?: continue
                    val f = File(dir, o.optString("file", ""))
                    if (!f.exists()) continue
                    kept.put(o)
                    entries.add(o)
                }
                if (kept.length() != idx.length()) writeIndexLocked(context, kept)
            }

            val start = safePage * safeSize
            val slice = if (start < entries.size) {
                entries.subList(start, minOf(entries.size, start + safeSize))
            } else {
                emptyList()
            }

            val dir = getVaultDir(context)
            val itemsArr = JSONArray()
            for (o in slice) {
                val f = File(dir, o.optString("file", ""))
                val thumb = loadThumbBase64(f) ?: continue
                itemsArr.put(
                    JSONObject().apply {
                        put("id", o.optString("id"))
                        put("packageName", o.optString("packageName"))
                        put("appName", o.optString("appName"))
                        put("timestamp", o.optLong("timestamp"))
                        put("thumbBase64", thumb)
                    }
                )
            }

            val monArr = JSONArray()
            for (p in prefs.getTimelineMonitoredPackages()) monArr.put(p)

            return JSONObject().apply {
                put("items", itemsArr)
                put("page", safePage)
                put("totalCount", entries.size)
                put("hasMore", start + slice.size < entries.size)
                put("enabled", prefs.timelineCaptureEnabled)
                put("intervalSec", prefs.timelineIntervalSec)
                put("monitoredPackages", monArr)
                put("running", running)
                put("needsConsent", prefs.timelineCaptureEnabled && !running)
            }
        }

        fun loadFullImageBase64(context: Context, captureId: String): Pair<String?, String?> {
            synchronized(vaultLock) {
                val dir = getVaultDir(context)
                val idx = readIndexLocked(context)
                for (i in 0 until idx.length()) {
                    val o = idx.optJSONObject(i) ?: continue
                    if (o.optString("id") == captureId) {
                        val f = File(dir, o.optString("file", ""))
                        if (!f.exists()) return Pair(null, "Capture introuvable.")
                        return try {
                            Pair(Base64.encodeToString(f.readBytes(), Base64.NO_WRAP), null)
                        } catch (t: Throwable) {
                            Pair(null, "Lecture impossible : ${t.message}")
                        }
                    }
                }
                return Pair(null, "Capture introuvable.")
            }
        }

        fun deleteCapture(context: Context, captureId: String): Boolean {
            synchronized(vaultLock) {
                val dir = getVaultDir(context)
                val idx = readIndexLocked(context)
                val remaining = JSONArray()
                var removed = false
                for (i in 0 until idx.length()) {
                    val o = idx.optJSONObject(i) ?: continue
                    if (o.optString("id") == captureId) {
                        try { File(dir, o.optString("file", "")).delete() } catch (_: Throwable) {}
                        removed = true
                    } else {
                        remaining.put(o)
                    }
                }
                writeIndexLocked(context, remaining)
                return removed
            }
        }

        fun clearAllCaptures(context: Context) {
            synchronized(vaultLock) {
                try {
                    getVaultDir(context).listFiles()?.forEach { f ->
                        try { f.delete() } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {
                }
            }
        }

        private fun loadThumbBase64(file: File): String? {
            return try {
                val opts = BitmapFactory.Options().apply { inSampleSize = 3 }
                val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 52, out)
                bmp.recycle()
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            } catch (_: Throwable) {
                null
            }
        }
    }
}
