package com.blurshield.app.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import com.blurshield.app.ai.BlurRegion
import com.blurshield.app.ai.BlurVisionEngine
import com.blurshield.app.config.BlurConfigSnapshot
import com.blurshield.app.config.BlurSettings
import com.blurshield.app.overlay.BlurOverlayView
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Primary Official Accessibility Blur Service (BlurShield AI):
 * - Renders 60–120 FPS hardware-accelerated blur masks directly via `TYPE_ACCESSIBILITY_OVERLAY`
 *   (covers every app without requiring SYSTEM_ALERT_WINDOW, survives reboots).
 * - Autonomous capture via Android 11+ `takeScreenshot()` (zero MediaProjection prompt!).
 * - Seamless handoff to optional 30 Hz `TurboCaptureService` for ultra-fluid video blurring.
 * - Instant 0ms scroll-delta tracking (`TYPE_VIEW_SCROLLED`) + 0ms app-switch mask clearing.
 */
class BlurAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var settings: BlurSettings
    private lateinit var visionEngine: BlurVisionEngine
    private lateinit var windowManager: WindowManager
    private lateinit var powerManager: PowerManager

    private var overlayView: BlurOverlayView? = null
    private var floatingPillView: TextView? = null
    private var floatingPillParams: WindowManager.LayoutParams? = null

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private val captureInFlight = AtomicBoolean(false)
    private val captureExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "BlurShieldShot").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 1
        }
    }

    @Volatile
    private var config: BlurConfigSnapshot? = null

    @Volatile
    private var screenInteractive: Boolean = true

    @Volatile
    private var currentForegroundPkg: String = ""

    @Volatile
    private var lastFrameStatic: Boolean = false

    @Volatile
    private var contentDirtyFlag: Boolean = true

    @Volatile
    private var lastScreenshotAtMs: Long = 0L

    private var screenWidthPx: Float = 1080f
    private var screenHeightPx: Float = 2400f
    private var lastBgHash = IntArray(BG_GRID_W * BG_GRID_H)
    private var hasPrevBgHash = false
    private var prevRowSig = FloatArray(ROW_SIG_BINS)
    private var hasPrevRowSig = false
    private var lastFullScanMs = 0L
    private var receiverRegistered = false

    private val settingsListener: (BlurConfigSnapshot) -> Unit = { snap ->
        config = snap
        mainHandler.post {
            overlayView?.updateConfig(snap, if (TurboCaptureService.isRunning) "Turbo 30Hz" else "Auto")
            syncFloatingPill()
            if (!snap.enabled || !snap.anyDetectionEnabled) {
                overlayView?.clearAllMasks()
            } else {
                contentDirtyFlag = true
                hasPrevBgHash = false
                scheduleAutonomousTick(40L)
            }
            notifyStateChanged()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenInteractive = false
                    overlayView?.clearImmediate()
                    workerHandler?.removeCallbacks(autonomousTick)
                }

                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    screenInteractive = true
                    hasPrevBgHash = false
                    hasPrevRowSig = false
                    contentDirtyFlag = true
                    scheduleAutonomousTick(100L)
                }
            }
        }
    }

    private val autonomousTick = object : Runnable {
        override fun run() {
            val snap = config ?: return
            if (!screenInteractive || !snap.enabled || !snap.anyDetectionEnabled) {
                overlayView?.clearAllMasks()
                return
            }
            // Yield autonomous loop cleanly whenever Turbo 30Hz MediaProjection service is feeding frames
            if (TurboCaptureService.isRunning) {
                return
            }
            if (!snap.isPackageAllowedForBlur(currentForegroundPkg, packageName)) {
                overlayView?.clearAllMasks()
                return
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return
            }

            val now = System.currentTimeMillis()
            val minGap = 1_015L
            val waitMore = minGap - (now - lastScreenshotAtMs)
            if (waitMore > 0L) {
                workerHandler?.postDelayed(this, waitMore + 15L)
                return
            }

            if (captureInFlight.compareAndSet(false, true)) {
                lastScreenshotAtMs = now
                contentDirtyFlag = false
                captureAutonomousFrame(snap)
            }

            val nextDelay = if (snap.ecoStaticSkip && lastFrameStatic && !contentDirtyFlag) {
                2_400L
            } else {
                1_030L
            }
            if (screenInteractive && snap.enabled && !TurboCaptureService.isRunning) {
                workerHandler?.postDelayed(this, nextDelay)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = BlurSettings(applicationContext)
        config = settings.snapshot()
        visionEngine = BlurVisionEngine(applicationContext)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        val dm = resources.displayMetrics
        screenWidthPx = dm.widthPixels.toFloat().coerceAtLeast(360f)
        screenHeightPx = dm.heightPixels.toFloat().coerceAtLeast(640f)
        screenInteractive = try { powerManager.isInteractive } catch (_: Throwable) { true }

        val ht = HandlerThread("BlurShieldWorker").apply {
            start()
            priority = Thread.NORM_PRIORITY + 1
        }
        workerThread = ht
        workerHandler = Handler(ht.looper)

        attachOverlay()
        syncFloatingPill()

        BlurSettings.addListener(settingsListener)
        registerScreenReceiver()

        activeInstance = this
        notifyStateChanged()
        scheduleAutonomousTick(120L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()?.trim().orEmpty()
                if (pkg.isEmpty()) return
                val lower = pkg.lowercase()
                val isTransient = lower == "com.android.systemui" ||
                    lower.contains("inputmethod") ||
                    lower.contains("gboard") ||
                    lower.contains("honeyboard") ||
                    lower.contains("swiftkey")
                if (!isTransient && pkg != currentForegroundPkg) {
                    currentForegroundPkg = pkg
                    hasPrevBgHash = false
                    hasPrevRowSig = false
                    lastFrameStatic = false
                    contentDirtyFlag = true
                    overlayView?.clearImmediate()
                    scheduleAutonomousTick(60L)
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                contentDirtyFlag = true
                lastFrameStatic = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val dy = event.scrollDeltaY
                    val dx = event.scrollDeltaX
                    if (dy != 0 || dx != 0) {
                        val dxNorm = (-dx.toFloat() / screenWidthPx).coerceIn(-0.45f, 0.45f)
                        val dyNorm = (-dy.toFloat() / screenHeightPx).coerceIn(-0.45f, 0.45f)
                        overlayView?.shiftMasks(dxNorm, dyNorm)
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (lastFrameStatic) {
                    contentDirtyFlag = true
                    lastFrameStatic = false
                    scheduleAutonomousTick(150L)
                }
            }
        }
    }

    override fun onInterrupt() {
        // Nothing to interrupt
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardownInternal()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardownInternal()
        super.onDestroy()
    }

    private fun teardownInternal() {
        if (activeInstance === this) {
            activeInstance = null
        }
        BlurSettings.removeListener(settingsListener)
        if (receiverRegistered) {
            try { unregisterReceiver(screenReceiver) } catch (_: Throwable) {}
            receiverRegistered = false
        }
        workerHandler?.removeCallbacks(autonomousTick)
        try { workerThread?.quitSafely() } catch (_: Throwable) {}
        workerThread = null
        workerHandler = null
        detachFloatingPill()
        detachOverlay()
        try { visionEngine.close() } catch (_: Throwable) {}
        notifyStateChanged()
    }

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        try {
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(screenReceiver, f)
            receiverRegistered = true
        } catch (_: Throwable) {
        }
    }

    private fun attachOverlay() {
        if (overlayView != null) return
        mainHandler.post {
            if (overlayView != null) return@post
            try {
                val view = BlurOverlayView(this)
                config?.let { view.updateConfig(it, if (TurboCaptureService.isRunning) "Turbo 30Hz" else "Auto") }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                }
                windowManager.addView(view, params)
                overlayView = view
            } catch (_: Throwable) {
            }
        }
    }

    private fun detachOverlay() {
        val v = overlayView ?: return
        overlayView = null
        mainHandler.post {
            try { windowManager.removeViewImmediate(v) } catch (_: Throwable) {}
        }
    }

    private fun syncFloatingPill() {
        val snap = config ?: return
        if (!snap.showFloatingPill) {
            detachFloatingPill()
            return
        }
        if (floatingPillView != null) {
            updateFloatingPillVisual(floatingPillView!!, snap.enabled)
            return
        }
        try {
            val density = resources.displayMetrics.density
            val pill = TextView(this).apply {
                textSize = 11f
                setTextColor(Color.WHITE)
                setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
                gravity = Gravity.CENTER
            }
            updateFloatingPillVisual(pill, snap.enabled)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = (12 * density).toInt()
                y = (160 * density).toInt()
            }

            var startY = 0
            var touchStartY = 0f
            var moved = false
            pill.setOnTouchListener { _, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = params.y
                        touchStartY = ev.rawY
                        moved = false
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dy = (ev.rawY - touchStartY).toInt()
                        if (abs(dy) > 8) moved = true
                        params.y = (startY + dy).coerceIn(60, (screenHeightPx - 180).toInt())
                        try { windowManager.updateViewLayout(pill, params) } catch (_: Throwable) {}
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            settings.enabled = !settings.enabled
                        }
                        true
                    }

                    else -> false
                }
            }

            windowManager.addView(pill, params)
            floatingPillView = pill
            floatingPillParams = params
        } catch (_: Throwable) {
        }
    }

    private fun updateFloatingPillVisual(pill: TextView, enabled: Boolean) {
        val density = resources.displayMetrics.density
        pill.text = if (enabled) "FLOU ON" else "PAUSE"
        pill.background = GradientDrawable().apply {
            cornerRadius = 18f * density
            setColor(if (enabled) Color.argb(215, 16, 185, 129) else Color.argb(200, 39, 39, 42))
            setStroke((1.2f * density).toInt(), if (enabled) Color.rgb(110, 231, 183) else Color.rgb(113, 113, 122))
        }
    }

    private fun detachFloatingPill() {
        val v = floatingPillView ?: return
        floatingPillView = null
        floatingPillParams = null
        mainHandler.post {
            try { windowManager.removeViewImmediate(v) } catch (_: Throwable) {}
        }
    }

    fun scheduleAutonomousTick(delayMs: Long = 80L) {
        if (TurboCaptureService.isRunning) return
        val h = workerHandler ?: return
        h.removeCallbacks(autonomousTick)
        if (screenInteractive && config?.enabled == true) {
            h.postDelayed(autonomousTick, delayMs)
        }
    }

    fun onTurboModeChanged(active: Boolean) {
        mainHandler.post {
            config?.let { overlayView?.updateConfig(it, if (active) "Turbo 30Hz" else "Auto") }
            if (active) {
                workerHandler?.removeCallbacks(autonomousTick)
            } else {
                hasPrevBgHash = false
                hasPrevRowSig = false
                scheduleAutonomousTick(100L)
            }
            notifyStateChanged()
        }
    }

    private fun captureAutonomousFrame(snap: BlurConfigSnapshot) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            captureInFlight.set(false)
            return
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                captureExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        var hwBmp: Bitmap? = null
                        var softBmp: Bitmap? = null
                        var scaledBmp: Bitmap? = null
                        try {
                            val buffer = screenshot.hardwareBuffer
                            val colorSpace = screenshot.colorSpace
                            try {
                                hwBmp = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                            } finally {
                                try { buffer.close() } catch (_: Throwable) {}
                            }
                            if (hwBmp != null) {
                                softBmp = hwBmp.copy(Bitmap.Config.ARGB_8888, false)
                                hwBmp.recycle()
                                hwBmp = null
                            }
                            if (softBmp != null) {
                                val targetShort = 320f
                                val ratio = targetShort / minOf(softBmp.width, softBmp.height).coerceAtLeast(1).toFloat()
                                if (ratio < 0.95f) {
                                    val tw = (softBmp.width * ratio).toInt().coerceAtLeast(160)
                                    val th = (softBmp.height * ratio).toInt().coerceAtLeast(240)
                                    scaledBmp = Bitmap.createScaledBitmap(softBmp, tw, th, true)
                                    softBmp.recycle()
                                    softBmp = null
                                } else {
                                    scaledBmp = softBmp
                                    softBmp = null
                                }
                            }
                        } catch (_: Throwable) {
                            try { hwBmp?.recycle() } catch (_: Throwable) {}
                            try { softBmp?.recycle() } catch (_: Throwable) {}
                            try { scaledBmp?.recycle() } catch (_: Throwable) {}
                            scaledBmp = null
                        } finally {
                            captureInFlight.set(false)
                        }
                        if (scaledBmp != null) {
                            val bmpRef = scaledBmp
                            workerHandler?.post {
                                processBitmapFrame(bmpRef, snap, isTurbo = false, recycleAfter = true)
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        captureInFlight.set(false)
                    }
                }
            )
        } catch (_: Throwable) {
            captureInFlight.set(false)
        }
    }

    /**
     * Entry point for 30 Hz TurboCaptureService (MediaProjection).
     * Returns true if frame was static (allows TurboCaptureService to adapt cadence smoothly).
     */
    fun processTurboFrame(frame: Bitmap): Boolean {
        val snap = config ?: return true
        if (!screenInteractive || !snap.enabled || !snap.anyDetectionEnabled) {
            overlayView?.clearAllMasks()
            return true
        }
        if (!snap.isPackageAllowedForBlur(currentForegroundPkg, packageName)) {
            overlayView?.clearAllMasks()
            return true
        }
        processBitmapFrame(frame, snap, isTurbo = true, recycleAfter = false)
        return lastFrameStatic
    }

    private fun processBitmapFrame(
        frame: Bitmap,
        snap: BlurConfigSnapshot,
        isTurbo: Boolean,
        recycleAfter: Boolean
    ) {
        val now = System.currentTimeMillis()
        try {
            val view = overlayView
            val existingBoxes = view?.getActiveBoxes().orEmpty()
            val bgDiff = computeOutsideMaskDiff(frame, existingBoxes)

            if (isTurbo && existingBoxes.isNotEmpty()) {
                val scrollDy = estimateVerticalScrollNorm(frame, existingBoxes)
                if (abs(scrollDy) >= 0.009f) {
                    view?.shiftMasks(0f, scrollDy)
                }
            }

            if (existingBoxes.isNotEmpty()) {
                if (bgDiff > 20f) {
                    view?.clearAllMasks()
                    hasPrevBgHash = false
                    hasPrevRowSig = false
                    lastFullScanMs = 0L
                } else if (snap.ecoStaticSkip && bgDiff < 4.0f && (now - lastFullScanMs) < (if (isTurbo) 900L else 4_000L)) {
                    lastFrameStatic = true
                    return
                }
            } else if (snap.ecoStaticSkip && bgDiff < 2.5f && (now - lastFullScanMs) < (if (isTurbo) 1_200L else 5_000L)) {
                lastFrameStatic = true
                return
            }

            lastFrameStatic = false
            lastFullScanMs = now

            val res = visionEngine.detectFrame(frame, snap)
            lastInferenceMs = res.inferenceMs
            val detections = res.regions

            val modeLabel = if (isTurbo) "Turbo 30Hz" else "Auto"
            if (detections.isNotEmpty()) {
                if (existingBoxes.isNotEmpty() && bgDiff < 15f) {
                    val merged = ArrayList<BlurRegion>(detections)
                    for ((i, oldBox) in existingBoxes.withIndex()) {
                        val covered = detections.any { d ->
                            val ix = (minOf(d.rect.right, oldBox.right) - maxOf(d.rect.left, oldBox.left)).coerceAtLeast(0f)
                            val iy = (minOf(d.rect.bottom, oldBox.bottom) - maxOf(d.rect.top, oldBox.top)).coerceAtLeast(0f)
                            ix * iy > 0.12f * oldBox.width() * oldBox.height()
                        }
                        if (!covered) {
                            merged.add(
                                BlurRegion(
                                    id = "hold-$i",
                                    kind = if (snap.anyNsfwEnabled && !snap.blurFaces) "nsfw" else "face",
                                    rect = oldBox,
                                    contour = null,
                                    confidence = 0.8f,
                                    patch = null
                                )
                            )
                        }
                    }
                    view?.updateDetections(merged, res.inferenceMs, snap, modeLabel, now)
                } else {
                    view?.updateDetections(detections, res.inferenceMs, snap, modeLabel, now)
                }
            } else if (existingBoxes.isEmpty() || bgDiff > 10f) {
                view?.updateDetections(emptyList(), res.inferenceMs, snap, modeLabel, now)
            }
            activeMasksCount = view?.getActiveCount() ?: 0
        } catch (_: Throwable) {
        } finally {
            if (recycleAfter && !frame.isRecycled) {
                frame.recycle()
            }
        }
    }

    private fun computeOutsideMaskDiff(bmp: Bitmap, masks: List<RectF>): Float {
        val w = bmp.width
        val h = bmp.height
        val nextHash = IntArray(BG_GRID_W * BG_GRID_H)
        var sumDiff = 0
        var count = 0
        for (gy in 0 until BG_GRID_H) {
            val ny = (gy + 0.5f) / BG_GRID_H
            val py = (ny * h).toInt().coerceIn(0, h - 1)
            for (gx in 0 until BG_GRID_W) {
                val nx = (gx + 0.5f) / BG_GRID_W
                val px = (nx * w).toInt().coerceIn(0, w - 1)
                val c = bmp.getPixel(px, py)
                val lum = (((c ushr 16) and 0xFF) * 3 + ((c ushr 8) and 0xFF) * 6 + (c and 0xFF)) / 10
                val idx = gy * BG_GRID_W + gx
                nextHash[idx] = lum
                val inside = masks.any { r ->
                    nx >= r.left - 0.04f && nx <= r.right + 0.04f && ny >= r.top - 0.04f && ny <= r.bottom + 0.04f
                }
                if (!inside && hasPrevBgHash) {
                    sumDiff += abs(lum - lastBgHash[idx])
                    count++
                }
            }
        }
        lastBgHash = nextHash
        hasPrevBgHash = true
        return if (count > 0) sumDiff.toFloat() / count else 0f
    }

    private fun estimateVerticalScrollNorm(bmp: Bitmap, masks: List<RectF>): Float {
        val w = bmp.width
        val h = bmp.height
        val sig = FloatArray(ROW_SIG_BINS)
        val xSamples = intArrayOf(w / 10, (w * 9) / 10)
        for (b in 0 until ROW_SIG_BINS) {
            val ny = (b + 0.5f) / ROW_SIG_BINS
            val py = (ny * h).toInt().coerceIn(0, h - 1)
            var sum = 0f
            var cnt = 0
            for (px in xSamples) {
                val nx = px.toFloat() / w
                if (masks.any { it.contains(nx, ny) }) continue
                val c = bmp.getPixel(px, py)
                sum += (((c ushr 16) and 0xFF) * 3 + ((c ushr 8) and 0xFF) * 6 + (c and 0xFF)) * 0.1f
                cnt++
            }
            sig[b] = if (cnt > 0) sum / cnt else 0f
        }
        var bestShift = 0
        if (hasPrevRowSig) {
            var bestErr = Float.MAX_VALUE
            var zeroErr = 0f
            for (shift in -12..12) {
                var err = 0f
                var n = 0
                for (i in 12 until ROW_SIG_BINS - 12) {
                    val j = i - shift
                    if (j in 0 until ROW_SIG_BINS) {
                        err += abs(sig[i] - prevRowSig[j])
                        n++
                    }
                }
                val avg = if (n > 0) err / n else Float.MAX_VALUE
                if (shift == 0) zeroErr = avg
                if (avg < bestErr) {
                    bestErr = avg
                    bestShift = shift
                }
            }
            if (zeroErr - bestErr < 3.0f) bestShift = 0
        }
        prevRowSig = sig
        hasPrevRowSig = true
        return bestShift.toFloat() / ROW_SIG_BINS
    }

    companion object {
        private const val BG_GRID_W = 12
        private const val BG_GRID_H = 20
        private const val ROW_SIG_BINS = 64

        @Volatile
        var activeInstance: BlurAccessibilityService? = null
            private set

        @Volatile
        var lastInferenceMs: Long = 0L
            private set

        @Volatile
        var activeMasksCount: Int = 0
            private set

        private val stateListeners = CopyOnWriteArraySet<() -> Unit>()

        fun isServiceActive(): Boolean = activeInstance != null

        fun isAccessibilityEnabledInSettings(context: Context): Boolean {
            if (activeInstance != null) return true
            return try {
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ).orEmpty()
                val full = ComponentName(context, BlurAccessibilityService::class.java).flattenToString()
                val short = ComponentName(context, BlurAccessibilityService::class.java).flattenToShortString()
                enabled.contains(full, ignoreCase = true) || enabled.contains(short, ignoreCase = true)
            } catch (_: Throwable) {
                false
            }
        }

        fun addStateListener(l: () -> Unit) {
            stateListeners.add(l)
        }

        fun removeStateListener(l: () -> Unit) {
            stateListeners.remove(l)
        }

        fun notifyStateChanged() {
            for (l in stateListeners) {
                try { l() } catch (_: Throwable) {}
            }
        }
    }
}
