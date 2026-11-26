package com.gazelock.app.overlay.minigames

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

/**
 * Brand-New Mini-Game #1: Scratch & Identify (3 Rounds):
 * - A real folder photo is hidden under an interactive metallic neon scratch foil.
 * - Rub your finger on the foil to uncover clues of the hidden image, then tap the matching
 *   thumbnail among 4 folder photos below! Wrong guess = +5s penalty + triggers onMistake().
 */
class ScratchRevealMatchView(
    context: Context,
    sourcePool: List<Bitmap>,
    private val onPenaltySec: (Int) -> Unit,
    private val onMistake: () -> Unit,
    private val onSolved: () -> Unit
) : LinearLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val cardPool = ArrayList<Bitmap>()
    private var round = 1
    private val totalRounds = 3
    private var targetCardIdx = 0
    private var correctChoiceSlot = 0
    private var choiceIndices = IntArray(4)
    private var inputLocked = false
    private var released = false

    private val statusTv: TextView
    private val scratchView: ScratchCanvasView
    private val choiceViews = ArrayList<ImageView>(4)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(14), dp(13), dp(14), dp(13))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Color.argb(238, 16, 10, 30))
            setStroke(dp(2), Color.rgb(250, 204, 21))
        }

        val valid = sourcePool.filter { !it.isRecycled }.shuffled(Random).take(12)
        for (b in valid) cardPool.add(PuzzleBitmapHelper.prepareSquareBoardBitmap(b, 320))
        while (cardPool.size < 6) cardPool.add(makeFallbackCard(cardPool.size))

        addView(TextView(context).apply {
            text = "🪙 GRATTE & IDENTIFIE (3 MANCHES)"
            setTextColor(Color.rgb(253, 224, 71)); textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            text = "Gratte le vernis au doigt pour révéler la photo cachée du dossier, puis touche la bonne miniature dessous (+5 s si erreur) !"
            setTextColor(Color.rgb(212, 204, 232)); textSize = 10.8f; gravity = Gravity.CENTER; setPadding(0, dp(3), 0, dp(6))
        })

        statusTv = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 11.8f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }
        addView(statusTv)

        val scratchSize = minOf((context.resources.displayMetrics.widthPixels * 0.62f).toInt(), dp(215))
        scratchView = ScratchCanvasView(context)
        addView(scratchView, LayoutParams(scratchSize, scratchSize))

        val choicesRow = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        val thumbSize = minOf((context.resources.displayMetrics.widthPixels * 0.18f).toInt(), dp(68))
        for (slot in 0 until 4) {
            val iv = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(dp(2), dp(2), dp(2), dp(2))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat(); setColor(Color.BLACK); setStroke(dp(2), Color.argb(180, 255, 255, 255))
                }
                setOnClickListener { handleChoiceTap(slot) }
            }
            choiceViews.add(iv)
            choicesRow.addView(iv, LayoutParams(thumbSize, thumbSize).apply {
                marginStart = dp(4); marginEnd = dp(4)
            })
        }
        addView(choicesRow)

        startRound()
    }

    private fun makeFallbackCard(i: Int): Bitmap {
        val sz = 320
        val out = Bitmap.createBitmap(sz, sz, Bitmap.Config.RGB_565)
        val c = Canvas(out)
        val cols = arrayOf(
            intArrayOf(Color.rgb(225, 29, 72), Color.rgb(88, 28, 135)),
            intArrayOf(Color.rgb(14, 165, 233), Color.rgb(30, 58, 138)),
            intArrayOf(Color.rgb(16, 185, 129), Color.rgb(6, 78, 59)),
            intArrayOf(Color.rgb(245, 158, 11), Color.rgb(154, 52, 18)),
            intArrayOf(Color.rgb(236, 72, 153), Color.rgb(157, 23, 77)),
            intArrayOf(Color.rgb(139, 92, 246), Color.rgb(49, 46, 129))
        )
        val p = cols[i % cols.size]
        c.drawRect(0f, 0f, sz.toFloat(), sz.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, sz.toFloat(), sz.toFloat(), p[0], p[1], Shader.TileMode.CLAMP)
        })
        val sym = arrayOf("★", "◆", "●", "▲", "✦", "❖")[i % 6]
        c.drawText(sym, sz * 0.5f, sz * 0.65f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 130f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        })
        return out
    }

    private fun startRound() {
        if (released) return
        inputLocked = false
        val picked = cardPool.indices.shuffled(Random).take(4)
        correctChoiceSlot = Random.nextInt(4)
        targetCardIdx = picked[correctChoiceSlot]
        for (i in 0 until 4) {
            choiceIndices[i] = picked[i]
            choiceViews[i].setImageBitmap(cardPool[picked[i]])
            choiceViews[i].background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat(); setColor(Color.BLACK); setStroke(dp(2), Color.argb(180, 255, 255, 255))
            }
        }
        scratchView.resetForImage(cardPool[targetCardIdx])
        statusTv.text = "🪙 MANCHE $round/$totalRounds — GRATTE & CHOISIS !"
        statusTv.setTextColor(Color.rgb(253, 224, 71))
    }

    private fun handleChoiceTap(slot: Int) {
        if (inputLocked || released) return
        if (slot == correctChoiceSlot) {
            inputLocked = true
            scratchView.revealCompletely()
            choiceViews[slot].background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat(); setColor(Color.BLACK); setStroke(dp(3), Color.rgb(16, 185, 129))
            }
            statusTv.text = "✅ TROUVÉE ! ($round/$totalRounds)"
            statusTv.setTextColor(Color.rgb(134, 239, 172))
            mainHandler.postDelayed({
                if (released) return@postDelayed
                round++
                if (round > totalRounds) onSolved() else startRound()
            }, 720L)
        } else {
            choiceViews[slot].background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat(); setColor(Color.BLACK); setStroke(dp(3), Color.rgb(244, 63, 94))
            }
            statusTv.text = "❌ MAUVAISE PHOTO (+5 s) — Gratte encore !"
            statusTv.setTextColor(Color.rgb(252, 165, 165))
            onPenaltySec(5)
            onMistake()
        }
    }

    fun release() {
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        scratchView.releaseFoil()
        for (iv in choiceViews) iv.setImageDrawable(null)
        for (b in cardPool) { try { if (!b.isRecycled) b.recycle() } catch (_: Throwable) {} }
        cardPool.clear()
    }

    private inner class ScratchCanvasView(ctx: Context) : View(ctx) {
        private var targetBmp: Bitmap? = null
        private var foilBmp: Bitmap? = null
        private var foilCanvas: Canvas? = null
        private var revealed = false
        private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = dp(28).toFloat()
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat(); color = Color.rgb(250, 204, 21)
        }
        private var lastX = 0f; private var lastY = 0f

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        fun resetForImage(bmp: Bitmap) {
            targetBmp = bmp
            revealed = false
            buildFreshFoil(width.coerceAtLeast(240), height.coerceAtLeast(240))
            invalidate()
        }

        fun revealCompletely() {
            revealed = true
            invalidate()
        }

        private fun buildFreshFoil(w: Int, h: Int) {
            releaseFoil()
            val f = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(f)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
                shader = LinearGradient(
                    0f, 0f, w.toFloat(), h.toFloat(),
                    intArrayOf(Color.rgb(76, 29, 149), Color.rgb(190, 24, 93), Color.rgb(88, 28, 135)),
                    null, Shader.TileMode.CLAMP
                )
            })
            c.drawText("GRATTE ICI", w * 0.5f, h * 0.54f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(185, 255, 255, 255); textSize = dp(18).toFloat(); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
            })
            foilBmp = f
            foilCanvas = c
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w > 0 && h > 0 && !revealed) buildFreshFoil(w, h)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val tb = targetBmp
            if (tb != null && !tb.isRecycled) canvas.drawBitmap(tb, null, RectF(0f, 0f, w, h), bmpPaint)
            if (!revealed) {
                val fb = foilBmp
                if (fb != null && !fb.isRecycled) canvas.drawBitmap(fb, null, RectF(0f, 0f, w, h), bmpPaint)
            }
            canvas.drawRect(0f, 0f, w, h, borderPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (revealed || foilCanvas == null) return true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x; lastY = event.y
                    foilCanvas?.drawCircle(event.x, event.y, dp(16).toFloat(), Paint(erasePaint).apply { style = Paint.Style.FILL })
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    foilCanvas?.drawLine(lastX, lastY, event.x, event.y, erasePaint)
                    lastX = event.x; lastY = event.y
                    invalidate()
                }
            }
            return true
        }

        fun releaseFoil() {
            foilCanvas = null
            try { if (foilBmp != null && !foilBmp!!.isRecycled) foilBmp!!.recycle() } catch (_: Throwable) {}
            foilBmp = null
        }
    }
}
