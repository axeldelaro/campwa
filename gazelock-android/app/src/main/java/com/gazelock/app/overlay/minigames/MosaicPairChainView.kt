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
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.random.Random

/**
 * Brand-New Mini-Game #2: Mahjong / Onet Image Link Chain (4x4 = 8 face-up pairs):
 * - All 16 folder image tiles are visible face-up!
 * - Eliminate any matching pair if they are adjacent, on the same unobstructed row/column,
 *   OR both on the same outer border!
 * - Automatically reshuffles remaining tiles if no legal link exists -> zero soft-lock!
 * - Wrong link = +4s penalty + triggers onMistake().
 */
class MosaicPairChainView(
    context: Context,
    sourcePool: List<Bitmap>,
    private val onPenaltySec: (Int) -> Unit,
    private val onMistake: () -> Unit,
    private val onSolved: () -> Unit
) : LinearLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val pairBitmaps = ArrayList<Bitmap>(8)
    // board[0..15] = pairId (0..7) or -1 if cleared
    private val board = IntArray(16) { -1 }
    private var selected = -1
    private var pairsLeft = 8
    private var solved = false

    private val statusTv: TextView
    private val boardCanvas: ChainCanvas

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(14), dp(13), dp(14), dp(13))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Color.argb(238, 14, 12, 30))
            setStroke(dp(2), Color.rgb(56, 189, 248))
        }

        val valid = sourcePool.filter { !it.isRecycled }.shuffled(Random)
        for (i in 0 until 8) {
            val src = if (i < valid.size) valid[i] else null
            pairBitmaps.add(makeDistinctCard(src, i))
        }

        val deck = mutableListOf(0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7).shuffled(Random)
        for (i in 0 until 16) board[i] = deck[i]
        ensureAtLeastOneLegalMove()

        addView(TextView(context).apply {
            text = "🔗 CHAÎNE MAHJONG D'IMAGES (4×4)"
            setTextColor(Color.rgb(125, 211, 252)); textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            text = "Relie 2 photos identiques voisines, alignées sans obstacle ou sur le même bord extérieur ! Erreur = +4 s."
            setTextColor(Color.rgb(212, 204, 232)); textSize = 10.8f; gravity = Gravity.CENTER; setPadding(0, dp(3), 0, dp(6))
        })

        statusTv = TextView(context).apply {
            setTextColor(Color.rgb(253, 224, 71)); textSize = 12f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        addView(statusTv)

        val sizePx = minOf((context.resources.displayMetrics.widthPixels * 0.80f).toInt(), dp(312))
        boardCanvas = ChainCanvas(context)
        addView(boardCanvas, LayoutParams(sizePx, sizePx))

        updateStatus("Paires restantes : $pairsLeft / 8")
    }

    private fun makeDistinctCard(src: Bitmap?, idx: Int): Bitmap {
        val sz = 180
        if (src != null) return PuzzleBitmapHelper.prepareSquareBoardBitmap(src, sz)
        val out = Bitmap.createBitmap(sz, sz, Bitmap.Config.RGB_565)
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
        c.drawRect(0f, 0f, sz.toFloat(), sz.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, sz.toFloat(), sz.toFloat(), p[0], p[1], Shader.TileMode.CLAMP)
        })
        val sym = arrayOf("★", "◆", "●", "▲", "✦", "❖", "⬢", "☀")[idx % 8]
        c.drawText(sym, sz * 0.5f, sz * 0.65f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 74f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        })
        return out
    }

    private fun canConnect(a: Int, b: Int): Boolean {
        if (a == b || board[a] < 0 || board[a] != board[b]) return false
        val r1 = a / 4; val c1 = a % 4
        val r2 = b / 4; val c2 = b % 4
        // 1) Adjacent directly
        if (abs(r1 - r2) + abs(c1 - c2) == 1) return true
        // 2) Same outer border (top, bottom, left, or right edge)
        if ((r1 == 0 && r2 == 0) || (r1 == 3 && r2 == 3) || (c1 == 0 && c2 == 0) || (c1 == 3 && c2 == 3)) return true
        // 3) Clear straight row
        if (r1 == r2) {
            val lo = minOf(c1, c2) + 1; val hi = maxOf(c1, c2)
            if ((lo until hi).all { board[r1 * 4 + it] < 0 }) return true
        }
        // 4) Clear straight column
        if (c1 == c2) {
            val lo = minOf(r1, r2) + 1; val hi = maxOf(r1, r2)
            if ((lo until hi).all { board[it * 4 + c1] < 0 }) return true
        }
        // 5) One-corner L-link through an empty corner!
        val corner1 = r1 * 4 + c2
        if (board[corner1] < 0 && clearSegment(r1, c1, r1, c2) && clearSegment(r1, c2, r2, c2)) return true
        val corner2 = r2 * 4 + c1
        if (board[corner2] < 0 && clearSegment(r1, c1, r2, c1) && clearSegment(r2, c1, r2, c2)) return true
        return false
    }

    private fun clearSegment(r1: Int, c1: Int, r2: Int, c2: Int): Boolean {
        return if (r1 == r2) {
            val lo = minOf(c1, c2) + 1; val hi = maxOf(c1, c2)
            (lo until hi).all { board[r1 * 4 + it] < 0 }
        } else {
            val lo = minOf(r1, r2) + 1; val hi = maxOf(r1, r2)
            (lo until hi).all { board[it * 4 + c1] < 0 }
        }
    }

    private fun hasAnyLegalMove(): Boolean {
        for (i in 0 until 16) {
            if (board[i] < 0) continue
            for (j in i + 1 until 16) {
                if (canConnect(i, j)) return true
            }
        }
        return false
    }

    private fun ensureAtLeastOneLegalMove() {
        var guard = 0
        while (pairsLeft > 0 && !hasAnyLegalMove() && guard < 12) {
            val aliveIdx = (0 until 16).filter { board[it] >= 0 }
            val vals = aliveIdx.map { board[it] }.shuffled(Random)
            for (k in aliveIdx.indices) board[aliveIdx[k]] = vals[k]
            guard++
        }
    }

    private fun updateStatus(msg: String) { statusTv.text = msg }

    private fun handleTap(cell: Int) {
        if (solved || cell !in 0..15 || board[cell] < 0) return
        if (selected == -1) {
            selected = cell; boardCanvas.invalidate()
        } else if (selected == cell) {
            selected = -1; boardCanvas.invalidate()
        } else if (canConnect(selected, cell)) {
            board[selected] = -1; board[cell] = -1
            selected = -1; pairsLeft--
            if (pairsLeft <= 0) {
                solved = true
                statusTv.setTextColor(Color.rgb(134, 239, 172))
                updateStatus("✅ GRILLE NETTOYÉE !")
                boardCanvas.invalidate()
                mainHandler.postDelayed({ onSolved() }, 650L)
            } else {
                val hadMove = hasAnyLegalMove()
                if (!hadMove) ensureAtLeastOneLegalMove()
                updateStatus(if (hadMove) "Paires restantes : $pairsLeft / 8" else "🔀 Mélange auto ! Reste : $pairsLeft / 8")
                boardCanvas.invalidate()
            }
        } else {
            selected = -1
            onPenaltySec(4); onMistake()
            updateStatus("❌ Lien bloqué ou différent (+4 s) • Reste : $pairsLeft/8")
            boardCanvas.invalidate()
        }
    }

    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        for (b in pairBitmaps) { try { if (!b.isRecycled) b.recycle() } catch (_: Throwable) {} }
        pairBitmaps.clear()
    }

    private inner class ChainCanvas(ctx: Context) : View(ctx) {
        private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val selStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(4).toFloat(); color = Color.rgb(250, 204, 21) }
        private val normStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat(); color = Color.argb(160, 255, 255, 255) }
        private val emptyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(55, 255, 255, 255) }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return
            val cw = width / 4f; val ch = height / 4f; val gap = dp(3).toFloat()
            for (i in 0 until 16) {
                val r = i / 4; val c = i % 4
                val rect = RectF(c * cw + gap, r * ch + gap, (c + 1) * cw - gap, (r + 1) * ch - gap)
                val pid = board[i]
                if (pid < 0) {
                    canvas.drawRoundRect(rect, dp(6).toFloat(), dp(6).toFloat(), emptyFill)
                } else {
                    val bmp = pairBitmaps[pid]
                    if (!bmp.isRecycled) canvas.drawBitmap(bmp, null, rect, bmpPaint)
                    canvas.drawRect(rect, if (i == selected) selStroke else normStroke)
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP && width > 0 && height > 0) {
                val c = (event.x / (width / 4f)).toInt().coerceIn(0, 3)
                val r = (event.y / (height / 4f)).toInt().coerceIn(0, 3)
                handleTap(r * 4 + c)
            }
            return true
        }
    }
}
