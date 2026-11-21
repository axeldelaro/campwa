package com.blurshield.app.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.blurshield.app.ui.MainActivity

/**
 * Optional 30 Hz Turbo Video Capture Service (MediaProjection):
 * - Runs ONLY when the user taps "Mode Turbo Vidéo (30 Hz)" for near-instant frame-by-frame
 *   video tracking (TikTok, YouTube, Reels).
 * - Feeds 288px GPU-downscaled frames straight into BlurAccessibilityService's TYPE_ACCESSIBILITY_OVERLAY.
 */
class TurboCaptureService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var captureW = 288
    private var captureH = 512
    private var reusableBitmap: Bitmap? = null

    private val frameTick = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val startNs = System.nanoTime()
            var frameStatic = false
            try {
                val reader = imageReader
                val image = reader?.acquireLatestImage()
                if (image != null) {
                    val bmp = try {
                        val plane = image.planes[0]
                        val buf = plane.buffer
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * captureW
                        val strideW = captureW + rowPadding / pixelStride
                        var b = reusableBitmap
                        if (b == null || b.width != strideW || b.height != captureH) {
                            b?.recycle()
                            b = Bitmap.createBitmap(strideW, captureH, Bitmap.Config.ARGB_8888)
                            reusableBitmap = b
                        }
                        b.copyPixelsFromBuffer(buf)
                        if (strideW == captureW) b else Bitmap.createBitmap(b, 0, 0, captureW, captureH)
                    } catch (_: Throwable) {
                        null
                    } finally {
                        image.close()
                    }
                    if (bmp != null) {
                        val shouldRecycle = bmp !== reusableBitmap
                        try {
                            frameStatic = BlurAccessibilityService.activeInstance?.processTurboFrame(bmp) ?: true
                        } finally {
                            if (shouldRecycle && !bmp.isRecycled) bmp.recycle()
                        }
                    }
                }
            } catch (_: Throwable) {
            }

            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            // ~28 Hz (36ms) on live video motion, relaxing to ~6 Hz (165ms) on static screens!
            val targetMs = if (frameStatic) 165L else 36L
            val delay = (targetMs - elapsedMs).coerceIn(14L, 180L)
            if (isRunning) {
                workerHandler?.postDelayed(this, delay)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val projData = pendingProjectionIntent
        pendingProjectionIntent = null
        if (projData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        val notif = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Throwable) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = try {
            mpm.getMediaProjection(Activity.RESULT_OK, projData)
        } catch (_: Throwable) {
            null
        }
        if (proj == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post { stopSelf() }
            }
        }, mainHandler)
        mediaProjection = proj

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay?.getRealMetrics(metrics)
        val screenW = metrics.widthPixels.coerceAtLeast(360)
        val screenH = metrics.heightPixels.coerceAtLeast(640)
        val dpi = metrics.densityDpi.coerceAtLeast(160)

        val ratio = 288f / minOf(screenW, screenH).toFloat()
        captureW = ((screenW * ratio).toInt() / 2) * 2
        captureH = ((screenH * ratio).toInt() / 2) * 2

        val ht = HandlerThread("BlurShieldTurbo").apply {
            start()
            priority = Thread.NORM_PRIORITY + 1
        }
        workerThread = ht
        workerHandler = Handler(ht.looper)

        val reader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = proj.createVirtualDisplay(
            "BlurShieldTurboDisplay",
            captureW,
            captureH,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            workerHandler
        )

        isRunning = true
        BlurAccessibilityService.activeInstance?.onTurboModeChanged(true)
        workerHandler?.post(frameTick)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        workerHandler?.removeCallbacks(frameTick)
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Throwable) {}
        imageReader = null
        try { reusableBitmap?.recycle() } catch (_: Throwable) {}
        reusableBitmap = null
        try { mediaProjection?.stop() } catch (_: Throwable) {}
        mediaProjection = null
        try { workerThread?.quitSafely() } catch (_: Throwable) {}
        workerThread = null
        workerHandler = null
        BlurAccessibilityService.activeInstance?.onTurboModeChanged(false)
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "BlurShield Mode Turbo 30Hz",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val openPi = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this,
            11,
            Intent(this, TurboCaptureService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("BlurShield AI • Mode Turbo 30 Hz actif")
            .setContentText("Fluidité vidéo maximale en cours")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_media_pause, "Revenir en mode Auto", stopPi)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "blurshield_turbo_v1"
        private const val NOTIF_ID = 7701
        private const val ACTION_STOP = "com.blurshield.app.ACTION_STOP_TURBO"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var pendingProjectionIntent: Intent? = null

        fun start(context: Context, projectionIntent: Intent) {
            pendingProjectionIntent = Intent(projectionIntent)
            val i = Intent(context, TurboCaptureService::class.java)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            if (!isRunning) return
            val i = Intent(context, TurboCaptureService::class.java).apply { action = ACTION_STOP }
            context.startService(i)
        }
    }
}
