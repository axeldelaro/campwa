package com.gazelock.app.overlay.minigames

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

/**
 * #54 Flash Target Hunt at 9 images/second (111 ms per image!) — 3 Intense Rounds:
 * - Phase 1 (1.9s): Memorize the golden-framed target photo from the user's video folder.
 * - Phase 2: Rapid stream flashes folder photos at EXACTLY 9 img/s (111 ms interval)!
 * - Target appears randomly every 5..11 frames; tap the image or "⚡ STOP — C'EST ELLE !"
 *   within the target frame (+ generous 220ms human reaction window) to catch it!
 * - Wrong tap = +5s penalty + triggers onMistake()!
 * - Includes a "🐢 Ralenti 5 img/s (+5 s)" helper unlocked after 18s -> zero soft-lock!
 */
class FlashTargetHuntView(
    context: Context,
    sourcePool: List<Bitmap>,
    private val onPenaltySec: (Int) -> Unit,
    private val onMistake: () -> Unit,
    private val onSolved: () -> Unit
) : LinearLayout(context) {

    private enum class Phase { SHOW_TARGET, STREAMING, FEEDBACK }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val cardPool = ArrayList<Bitmap>()
    private var round = 1
    private val totalRounds = 3
    private var phase = Phase.SHOW_TARGET
    private var targetIdx = 0
    private var currentShownIdx = -1
    private var framesSinceLastTarget = 0
    private var nextTargetCountdown = 6
    private var lastTargetShownElapsedMs = 0L
    private var frameDelayMs = 111L // 9 images/sec = 1000 / 9 ≈ 111 ms!
    private var solved = false
    private var released = false
    private var slowUnlocked = false

    private lateinit var statusTv: TextView
    private lateinit var photoFrame: FrameLayout
    private lateinit var photoIv: ImageView
    private lateinit var catchBtn: Button
    private lateinit var slowBtn: Button

    private val streamTick = object : Runnable {
        override fun run() {
            if (released || solved || phase != Phase.STREAMING || cardPool.size < 2) return

            framesSinceLastTarget++
            val showTargetNow = framesSinceLastTarget >= nextTargetCountdown
            if (showTargetNow) {
                currentShownIdx = targetIdx
                framesSinceLastTarget = 0
                nextTargetCountdown = Random.nextInt(5, 11)
                lastTargetShownElapsedMs = SystemClock.elapsedRealtime()
            } else {
                var pick = Random.nextInt(cardPool.size)
                var guard = 0
                while ((pick == targetIdx || pick == currentShownIdx) && guard < 12) {
                    pick = (pick + 1) % cardPool.size
                    guard++
                }
                currentShownIdx = pick
            }

            val bmp = cardPool[currentShownIdx]
            if (!bmp.isRecycled) photoIv.setImageBitmap(bmp)

            mainHandler.postDelayed(this, frameDelayMs)
        }
    }

    private val unlockSlowRunnable = Runnable {
        if (!solved && !released) {
            slowUnlocked = true
            slowBtn.isEnabled = true
            slowBtn.alpha = 1.0f
            slowBtn.text = "🐢 Ralenti 5 img/s (+5 s)"
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Color.argb(238, 16, 10, 30))
            setStroke(dp(2), Color.rgb(250, 204, 21))
        }

        // Build at least 8 visually distinct square cards from folder pool (with vibrant fallbacks if needed)
        val valid = sourcePool.filter { !it.isRecycled }.shuffled(Random).take(14)
        for (src in valid) cardPool.add(PuzzleBitmapHelper.prepareSquareBoardBitmap(src, 380))
        while (cardPool.size < 8) {
            cardPool.add(makeFallbackCard(cardPool.size))
        }

        addView(TextView(context).apply {
            text = "⚡ CHASSE AU MOTIF ÉCLAIR — 9 IMG/S"
            setTextColor(Color.rgb(253, 224, 71)); textSize = 14.4f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            text = "Mémorise la cible dorée puis frappe STOP dès qu'elle réapparaît dans le flux à 9 images/seconde ! Erreur = +5 s !"
            setTextColor(Color.rgb(214, 206, 234)); textSize = 11f; gravity = Gravity.CENTER; setPadding(0, dp(3), 0, dp(6))
        })

        statusTv = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 12f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        addView(statusTv)

        val sizePx = minOf((context.resources.displayMetrics.widthPixels * 0.76f).toInt(), dp(282))
        photoFrame = FrameLayout(context).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = makeFrameBorder(Color.rgb(250, 204, 21))
            setOnClickListener { handleCatchAttempt() }
        }
        photoIv = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        photoFrame.addView(photoIv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(photoFrame, LayoutParams(sizePx, sizePx))

        catchBtn = Button(context).apply {
            text = "👀 MÉMORISE LA CIBLE…"
            isAllCaps = false; textSize = 13.6f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.rgb(225, 29, 72)) }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isEnabled = false; alpha = 0.55f
            layoutParams = LayoutParams(sizePx, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            setOnClickListener { handleCatchAttempt() }
        }
        addView(catchBtn)

        slowBtn = Button(context).apply {
            text = "🐢 Ralenti 5 img/s (dans 18 s)"
            isAllCaps = false; textSize = 11.2f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(79, 70, 229)) }
            minHeight = 0; minimumHeight = 0; setPadding(dp(12), dp(7), dp(12), dp(7))
            isEnabled = false; alpha = 0.45f
            layoutParams = LayoutParams(sizePx, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) }
            setOnClickListener {
                if (!slowUnlocked || solved) return@setOnClickListener
                onPenaltySec(5)
                frameDelayMs = 200L // Slowed to 5 img/s
                slowBtn.isEnabled = false; slowBtn.alpha = 0.45f; slowBtn.text = "🐢 Ralenti à 5 img/s !"
            }
        }
        addView(slowBtn)

        startRound()
        mainHandler.postDelayed(unlockSlowRunnable, 18_000L)
    }

    private fun makeFrameBorder(strokeColor: Int) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(Color.BLACK)
        setStroke(dp(4), strokeColor)
    }

    private fun makeFallbackCard(idx: Int): Bitmap {
        val size = 380
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        val c = Canvas(out)
        val cols = arrayOf(
            intArrayOf(Color.rgb(225, 29, 72), Color.rgb(127, 29, 29)),
            intArrayOf(Color.rgb(147, 51, 234), Color.rgb(59, 7, 100)),
            intArrayOf(Color.rgb(14, 165, 233), Color.rgb(12, 74, 110)),
            intArrayOf(Color.rgb(16, 185, 129), Color.rgb(6, 78, 59)),
            intArrayOf(Color.rgb(245, 158, 11), Color.rgb(120, 53, 15)),
            intArrayOf(Color.rgb(236, 72, 153), Color.rgb(88, 28, 135)),
            intArrayOf(Color.rgb(99, 102, 241), Color.rgb(30, 27, 75)),
            intArrayOf(Color.rgb(20, 184, 166), Color.rgb(19, 78, 74))
        )
        val p = cols[idx % cols.size]
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), p[0], p[1], Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bg)
        val sym = arrayOf("★", "◆", "●", "▲", "✦", "❖", "⬢", "☀")[idx % 8]
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 150f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        c.drawText(sym, size * 0.5f, size * 0.64f, tp)
        return out
    }

    private fun startRound() {
        if (released || solved) return
        mainHandler.removeCallbacks(streamTick)
        phase = Phase.SHOW_TARGET
        targetIdx = Random.nextInt(cardPool.size)
        currentShownIdx = targetIdx
        lastTargetShownElapsedMs = 0L
        framesSinceLastTarget = 0
        nextTargetCountdown = Random.nextInt(5, 10)

        photoIv.setImageBitmap(cardPool[targetIdx])
        photoFrame.background = makeFrameBorder(Color.rgb(250, 204, 21))
        statusTv.text = "🎯 MANCHE $round/$totalRounds — MÉMORISE CETTE CIBLE !"
        statusTv.setTextColor(Color.rgb(253, 224, 71))
        catchBtn.text = "👀 MÉMORISE LA CIBLE…"
        catchBtn.isEnabled = false; catchBtn.alpha = 0.55f

        mainHandler.postDelayed({
            if (released || solved) return@postDelayed
            phase = Phase.STREAMING
            val fpsLbl = if (frameDelayMs <= 120L) "9 IMG/S" else "5 IMG/S"
            photoFrame.background = makeFrameBorder(Color.rgb(217, 70, 239))
            statusTv.text = "⚡ MANCHE $round/$totalRounds — FLUX $fpsLbl ACTIF !"
            statusTv.setTextColor(Color.WHITE)
            catchBtn.text = "⚡ STOP — C'EST ELLE !"
            catchBtn.isEnabled = true; catchBtn.alpha = 1.0f
            mainHandler.post(streamTick)
        }, 1_900L)
    }

    private fun handleCatchAttempt() {
        if (released || solved || phase != Phase.STREAMING) return
        val now = SystemClock.elapsedRealtime()
        // Hit if currently displaying target OR within crisp 320ms human reaction window after target flashed!
        val isHit = (currentShownIdx == targetIdx) ||
            (lastTargetShownElapsedMs > 0L && (now - lastTargetShownElapsedMs) <= 320L)

        mainHandler.removeCallbacks(streamTick)
        phase = Phase.FEEDBACK
        catchBtn.isEnabled = false

        if (isHit) {
            photoIv.setImageBitmap(cardPool[targetIdx])
            photoFrame.background = makeFrameBorder(Color.rgb(16, 185, 129))
            statusTv.text = "✅ ATTRAPÉE EN PLEIN VOL ! ($round/$totalRounds)"
            statusTv.setTextColor(Color.rgb(134, 239, 172))
            mainHandler.postDelayed({
                if (released) return@postDelayed
                round++
                if (round > totalRounds) {
                    solved = true
                    mainHandler.removeCallbacks(unlockSlowRunnable)
                    slowBtn.isEnabled = false
                    onSolved()
                } else {
                    startRound()
                }
            }, 850L)
        } else {
            photoFrame.background = makeFrameBorder(Color.rgb(244, 63, 94))
            statusTv.text = "❌ RATÉ ! Ce n'était pas la cible (+5 s) !"
            statusTv.setTextColor(Color.rgb(252, 165, 165))
            onPenaltySec(5)
            onMistake()
            mainHandler.postDelayed({
                if (!released && !solved) {
                    // Resume stream immediately after brief error flash!
                    phase = Phase.STREAMING
                    photoFrame.background = makeFrameBorder(Color.rgb(217, 70, 239))
                    val fpsLbl = if (frameDelayMs <= 120L) "9 IMG/S" else "5 IMG/S"
                    statusTv.text = "⚡ MANCHE $round/$totalRounds — FLUX $fpsLbl ACTIF !"
                    statusTv.setTextColor(Color.WHITE)
                    catchBtn.isEnabled = true
                    mainHandler.post(streamTick)
                }
            }, 650L)
        }
    }

    fun release() {
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        photoIv.setImageDrawable(null)
        for (b in cardPool) { try { if (!b.isRecycled) b.recycle() } catch (_: Throwable) {} }
        cardPool.clear()
    }
}
