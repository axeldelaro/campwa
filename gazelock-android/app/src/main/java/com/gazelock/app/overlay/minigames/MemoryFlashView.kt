package com.gazelock.app.overlay.minigames

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

/**
 * #16 Memory Flash (4x3 = 6 pairs) Mini-Game using REAL images from the user's video folder!
 * - Shows all 12 cards face-up for 2.3s at start ("MÉMORISE !"), then flips face-down.
 * - Match locks cards emerald; mismatch flashes crimson and adds +4s penalty (+ triggers onMistake!).
 * - Includes a 1.3s Peek Helper (+5s) unlocked after 16s so zero soft-lock!
 */
class MemoryFlashView(
    context: Context,
    sourcePool: List<Bitmap>,
    private val onPenaltySec: (Int) -> Unit,
    private val onMistake: () -> Unit,
    private val onSolved: () -> Unit
) : LinearLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val pairBitmaps: List<Bitmap>
    private val pairIds = IntArray(12)
    private val matched = BooleanArray(12)
    private var revealAll = true
    private var firstPick = -1
    private var secondPick = -1
    private var mismatchHold = false
    private var pairsFound = 0
    private var mistakes = 0
    private var solved = false
    private var peekUnlocked = false

    private lateinit var statusTv: TextView
    private lateinit var boardCanvas: MemoryCanvas
    private lateinit var peekBtn: Button

    private val endInitialRevealRunnable = Runnable {
        revealAll = false
        updateStatus()
        boardCanvas.invalidate()
    }

    private val clearMismatchRunnable = Runnable {
        firstPick = -1
        secondPick = -1
        mismatchHold = false
        boardCanvas.invalidate()
    }

    private val unlockPeekRunnable = Runnable {
        if (!solved) {
            peekUnlocked = true
            peekBtn.isEnabled = true
            peekBtn.alpha = 1.0f
            peekBtn.text = "👁️ Flash Mémoire (+5 s)"
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Color.argb(236, 16, 12, 30))
            setStroke(dp(2), Color.rgb(236, 72, 153))
        }

        val valid = sourcePool.filter { !it.isRecycled }.shuffled(Random)
        val built = ArrayList<Bitmap>(6)
        for (i in 0 until 6) {
            val src = if (i < valid.size) valid[i] else null
            built.add(makeDistinctCardBitmap(src, i))
        }
        pairBitmaps = built

        val deck = mutableListOf(0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5).shuffled(Random)
        for (i in 0 until 12) pairIds[i] = deck[i]

        addView(TextView(context).apply {
            text = "🧠 MEMORY FLASH (6 PAIRES DU DOSSIER)"
            setTextColor(Color.WHITE); textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            text = "Retrouve les 6 paires d'images du dossier. Chaque erreur rajoute +4 s au chrono !"
            setTextColor(Color.rgb(212, 204, 232)); textSize = 11.2f; gravity = Gravity.CENTER; setPadding(0, dp(3), 0, dp(6))
        })

        statusTv = TextView(context).apply {
            text = "👀 MÉMORISE LES 6 PAIRES (2,3 s)…"
            setTextColor(Color.rgb(253, 224, 71)); textSize = 12f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        addView(statusTv)

        val boardW = minOf((context.resources.displayMetrics.widthPixels * 0.80f).toInt(), dp(312))
        val boardH = (boardW * 0.78f).toInt()
        boardCanvas = MemoryCanvas(context)
        addView(boardCanvas, LayoutParams(boardW, boardH))

        peekBtn = Button(context).apply {
            text = "👁️ Flash Mémoire (dans 16 s)"
            isAllCaps = false; textSize = 11.6f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(190, 24, 93)) }
            minHeight = 0; minimumHeight = 0; setPadding(dp(14), dp(8), dp(14), dp(8))
            isEnabled = false; alpha = 0.45f
            layoutParams = LayoutParams(boardW, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            setOnClickListener { triggerPaidPeek() }
        }
        addView(peekBtn)

        mainHandler.postDelayed(endInitialRevealRunnable, 2_300L)
        mainHandler.postDelayed(unlockPeekRunnable, 16_000L)
    }

    private fun makeDistinctCardBitmap(src: Bitmap?, pairIndex: Int): Bitmap {
        val size = 200
        return if (src != null) {
            PuzzleBitmapHelper.prepareSquareBoardBitmap(src, size)
        } else {
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            val c = Canvas(out)
            val palettes = arrayOf(
                intArrayOf(Color.rgb(225, 29, 72), Color.rgb(131, 24, 67)),
                intArrayOf(Color.rgb(147, 51, 234), Color.rgb(59, 7, 100)),
                intArrayOf(Color.rgb(14, 165, 233), Color.rgb(12, 74, 110)),
                intArrayOf(Color.rgb(16, 185, 129), Color.rgb(6, 78, 59)),
                intArrayOf(Color.rgb(245, 158, 11), Color.rgb(120, 53, 15)),
                intArrayOf(Color.rgb(236, 72, 153), Color.rgb(79, 70, 229))
            )
            val p = palettes[pairIndex % palettes.size]
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), p[0], p[1], Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bg)
            val sym = arrayOf("★", "◆", "●", "▲", "✦", "❖")[pairIndex % 6]
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 78f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
            }
            c.drawText(sym, size * 0.5f, size * 0.64f, tp)
            out
        }
    }

    private fun triggerPaidPeek() {
        if (!peekUnlocked || solved || revealAll || mismatchHold) return
        onPenaltySec(5)
        revealAll = true
        boardCanvas.invalidate()
        mainHandler.postDelayed({
            revealAll = false
            boardCanvas.invalidate()
        }, 1_300L)
    }

    private fun updateStatus() {
        statusTv.text = if (solved) {
            "✅ 6/6 PAIRES TROUVÉES !"
        } else {
            "Paires : $pairsFound/6  •  Erreurs : $mistakes (+4s/err)"
        }
    }

    private fun handleCardTap(idx: Int) {
        if (solved || revealAll || mismatchHold || idx !in 0..11) return
        if (matched[idx] || idx == firstPick) return

        if (firstPick == -1) {
            firstPick = idx
            boardCanvas.invalidate()
        } else {
            secondPick = idx
            if (pairIds[firstPick] == pairIds[secondPick]) {
                matched[firstPick] = true
                matched[secondPick] = true
                firstPick = -1
                secondPick = -1
                pairsFound++
                if (pairsFound >= 6) {
                    solved = true
                    mainHandler.removeCallbacks(unlockPeekRunnable)
                    peekBtn.isEnabled = false
                    statusTv.setTextColor(Color.rgb(134, 239, 172))
                    updateStatus()
                    boardCanvas.invalidate()
                    mainHandler.postDelayed({ onSolved() }, 750L)
                } else {
                    updateStatus()
                    boardCanvas.invalidate()
                }
            } else {
                mistakes++
                mismatchHold = true
                onPenaltySec(4)
                onMistake()
                updateStatus()
                boardCanvas.invalidate()
                mainHandler.postDelayed(clearMismatchRunnable, 580L)
            }
        }
    }

    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        for (b in pairBitmaps) { try { if (!b.isRecycled) b.recycle() } catch (_: Throwable) {} }
    }

    private inner class MemoryCanvas(ctx: Context) : View(ctx) {
        private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val backFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(34, 24, 56) }
        private val backStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat(); color = Color.argb(190, 217, 70, 239)
        }
        private val backMark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 244, 114, 182); textSize = dp(22).toFloat(); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        private val okStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat(); color = Color.rgb(16, 185, 129)
        }
        private val pickStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat(); color = Color.rgb(250, 204, 21)
        }
        private val errStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(4).toFloat(); color = Color.rgb(244, 63, 94)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return
            val cols = 4; val rows = 3
            val cw = width.toFloat() / cols
            val ch = height.toFloat() / rows
            val gap = dp(4).toFloat()
            val rad = dp(8).toFloat()

            for (i in 0 until 12) {
                val r = i / cols; val c = i % cols
                val rect = RectF(c * cw + gap, r * ch + gap, (c + 1) * cw - gap, (r + 1) * ch - gap)
                val faceUp = revealAll || matched[i] || i == firstPick || i == secondPick
                if (faceUp) {
                    val bmp = pairBitmaps[pairIds[i]]
                    if (!bmp.isRecycled) canvas.drawBitmap(bmp, null, rect, bmpPaint)
                    val st = when {
                        matched[i] -> okStroke
                        mismatchHold && (i == firstPick || i == secondPick) -> errStroke
                        else -> pickStroke
                    }
                    canvas.drawRect(rect, st)
                } else {
                    canvas.drawRoundRect(rect, rad, rad, backFill)
                    canvas.drawRoundRect(rect, rad, rad, backStroke)
                    canvas.drawText("?", rect.centerX(), rect.centerY() + dp(8), backMark)
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP && width > 0 && height > 0) {
                val c = (event.x / (width / 4f)).toInt().coerceIn(0, 3)
                val r = (event.y / (height / 3f)).toInt().coerceIn(0, 2)
                handleCardTap(r * 4 + c)
            }
            return true
        }
    }
}
