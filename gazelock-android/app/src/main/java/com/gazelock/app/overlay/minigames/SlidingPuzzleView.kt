package com.gazelock.app.overlay.minigames

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.random.Random

/**
 * 3x3 Sliding Puzzle (Taquin) Mini-Game using a real image from the user's video folder:
 * - Mathematically 100% solvable shuffle (generated via valid blank-slot moves from solved state).
 * - Supports both tap-to-slide (including multi-tile row/column sliding!) and swipe gestures.
 * - Live emerald borders on correctly placed tiles + subtle tile numbers (1..8) + 1.5s Peek button.
 * - Built-in fast A* Manhattan-distance optimal next-move hint (+5s) unlocked after 18s -> zero soft-lock!
 */
class SlidingPuzzleView(
    context: Context,
    sourceBitmap: Bitmap?,
    private val onPenaltySec: (Int) -> Unit,
    private val onSolved: () -> Unit
) : LinearLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val fullBitmap: Bitmap = PuzzleBitmapHelper.prepareSquareBoardBitmap(sourceBitmap, 540)
    private val tiles: List<Bitmap> = PuzzleBitmapHelper.sliceIntoGrid(fullBitmap, 3)

    // board[cellIndex] = tileId (0..7 = image tile, 8 = empty slot)
    private val board = IntArray(9) { it }
    private var movesCount = 0
    private var solved = false
    private var showingPreview = false
    private var hintAvailable = false

    private val statusTv: TextView
    private val boardView: SlidingBoardCanvas
    private lateinit var hintBtn: Button

    private val unlockHintRunnable = Runnable {
        if (!solved) {
            hintAvailable = true
            hintBtn.isEnabled = true
            hintBtn.alpha = 1.0f
            hintBtn.text = "💡 Coup optimal (+5 s)"
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Color.argb(232, 16, 12, 28))
            setStroke(dp(2), Color.rgb(192, 38, 211))
        }

        addView(TextView(context).apply {
            text = "🧩 PUZZLE À GLISSEMENT (3×3)"
            setTextColor(Color.WHITE); textSize = 15.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            text = "Reconstitue l'image du dossier en faisant glisser les tuiles. Le chrono de 3 min est en pause jusqu'à la réussite !"
            setTextColor(Color.rgb(212, 204, 232)); textSize = 11.2f; gravity = Gravity.CENTER; setPadding(0, dp(3), 0, dp(6))
        })

        statusTv = TextView(context).apply {
            setTextColor(Color.rgb(253, 224, 71)); textSize = 12f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        addView(statusTv)

        val boardPx = minOf((context.resources.displayMetrics.widthPixels * 0.78f).toInt(), dp(305))
        boardView = SlidingBoardCanvas(context)
        addView(boardView, LayoutParams(boardPx, boardPx))

        val btnRow = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        val peekBtn = Button(context).apply {
            text = "👁️ Modèle (1,5 s)"
            isAllCaps = false; textSize = 11.6f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(79, 70, 229)) }
            minHeight = 0; minimumHeight = 0; setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { triggerPreview() }
        }
        hintBtn = Button(context).apply {
            text = "💡 Aide (dans 18 s)"
            isAllCaps = false; textSize = 11.6f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(190, 24, 93)) }
            minHeight = 0; minimumHeight = 0; setPadding(dp(10), dp(8), dp(10), dp(8))
            isEnabled = false; alpha = 0.45f
            setOnClickListener { applyOptimalHintMove() }
        }
        btnRow.addView(peekBtn, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) })
        btnRow.addView(hintBtn, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(5) })
        addView(btnRow, LayoutParams(boardPx, ViewGroup.LayoutParams.WRAP_CONTENT))

        shuffleSolvable()
        updateStatusLabel()
        mainHandler.postDelayed(unlockHintRunnable, 18_000L)
    }

    private fun shuffleSolvable() {
        for (i in 0 until 9) board[i] = i
        var blankIdx = 8
        var prevBlank = -1
        // 24 valid non-backtracking moves -> guaranteed 100% solvable in ~12..24 steps!
        repeat(24) {
            val br = blankIdx / 3; val bc = blankIdx % 3
            val neighbors = ArrayList<Int>(4)
            if (br > 0) neighbors.add((br - 1) * 3 + bc)
            if (br < 2) neighbors.add((br + 1) * 3 + bc)
            if (bc > 0) neighbors.add(br * 3 + (bc - 1))
            if (bc < 2) neighbors.add(br * 3 + (bc + 1))
            val filtered = neighbors.filter { it != prevBlank }.ifEmpty { neighbors }
            val pick = filtered[Random.nextInt(filtered.size)]
            board[blankIdx] = board[pick]
            board[pick] = 8
            prevBlank = blankIdx
            blankIdx = pick
        }
        // Guarantee not already solved
        if (countCorrectTiles() >= 7) {
            val br = blankIdx / 3; val bc = blankIdx % 3
            val nb = if (br > 0) (br - 1) * 3 + bc else (br + 1) * 3 + bc
            board[blankIdx] = board[nb]; board[nb] = 8
        }
    }

    private fun countCorrectTiles(): Int {
        var ok = 0
        for (i in 0 until 8) if (board[i] == i) ok++
        return ok
    }

    private fun isBoardSolved(): Boolean {
        for (i in 0 until 9) if (board[i] != i) return false
        return true
    }

    private fun updateStatusLabel() {
        statusTv.text = if (solved) {
            "✅ PUZZLE RÉSOLU EN $movesCount COUPS !"
        } else {
            "Tuiles bien placées : ${countCorrectTiles()}/8  •  Coups : $movesCount"
        }
    }

    private fun triggerPreview() {
        if (solved || showingPreview) return
        showingPreview = true
        boardView.invalidate()
        mainHandler.postDelayed({
            showingPreview = false
            boardView.invalidate()
        }, 1_500L)
    }

    private fun trySlideFromCell(cellIdx: Int): Boolean {
        if (solved || showingPreview || cellIdx !in 0..8) return false
        val blankIdx = board.indexOf(8)
        if (blankIdx < 0 || cellIdx == blankIdx) return false

        val tr = cellIdx / 3; val tc = cellIdx % 3
        val br = blankIdx / 3; val bc = blankIdx % 3

        if (tr == br) {
            // Same row: shift 1 or 2 tiles horizontally toward blank!
            val step = if (tc < bc) -1 else 1
            var cur = bc
            while (cur != tc) {
                val next = cur + step
                board[br * 3 + cur] = board[br * 3 + next]
                cur = next
            }
            board[cellIdx] = 8
        } else if (tc == bc) {
            // Same column: shift 1 or 2 tiles vertically toward blank!
            val step = if (tr < br) -1 else 1
            var cur = br
            while (cur != tr) {
                val next = cur + step
                board[cur * 3 + bc] = board[next * 3 + bc]
                cur = next
            }
            board[cellIdx] = 8
        } else {
            return false
        }

        movesCount++
        checkSolvedAndRefresh()
        return true
    }

    private fun checkSolvedAndRefresh() {
        if (isBoardSolved()) {
            solved = true
            mainHandler.removeCallbacks(unlockHintRunnable)
            hintBtn.isEnabled = false
            statusTv.setTextColor(Color.rgb(134, 239, 172))
            updateStatusLabel()
            boardView.invalidate()
            mainHandler.postDelayed({ onSolved() }, 850L)
        } else {
            updateStatusLabel()
            boardView.invalidate()
        }
    }

    /** Runs a fast A* solver with Manhattan heuristic (<3 ms) to perform the exact next optimal move! */
    private fun applyOptimalHintMove() {
        if (!hintAvailable || solved || showingPreview) return
        val nextBlankCell = findOptimalNextBlankCell(board) ?: return
        onPenaltySec(5)
        val blankIdx = board.indexOf(8)
        if (blankIdx >= 0 && nextBlankCell in 0..8) {
            board[blankIdx] = board[nextBlankCell]
            board[nextBlankCell] = 8
            movesCount++
            checkSolvedAndRefresh()
        }
    }

    private fun findOptimalNextBlankCell(start: IntArray): Int? {
        data class Node(val state: IntArray, val g: Int, val f: Int, val firstMove: Int) : Comparable<Node> {
            override fun compareTo(other: Node) = f.compareTo(other.f)
        }
        fun encode(a: IntArray): Long {
            var k = 0L; for (v in a) k = (k shl 4) or v.toLong(); return k
        }
        fun manhattan(a: IntArray): Int {
            var d = 0
            for (i in 0 until 9) {
                val v = a[i]
                if (v != 8) d += abs(i / 3 - v / 3) + abs(i % 3 - v % 3)
            }
            return d
        }
        val goalKey = encode(intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8))
        val pq = PriorityQueue<Node>()
        val bestG = HashMap<Long, Int>(512)
        val sCopy = start.copyOf()
        val sKey = encode(sCopy)
        if (sKey == goalKey) return null
        pq.add(Node(sCopy, 0, manhattan(sCopy), -1))
        bestG[sKey] = 0

        var expanded = 0
        while (pq.isNotEmpty() && expanded < 14_000) {
            val cur = pq.poll() ?: break
            expanded++
            val blank = cur.state.indexOf(8)
            val br = blank / 3; val bc = blank % 3
            val dirs = intArrayOf(-3, 3, -1, 1)
            for (d in dirs) {
                val nb = blank + d
                if (nb !in 0..8) continue
                if (d == -1 && bc == 0) continue
                if (d == 1 && bc == 2) continue
                if (d == -3 && br == 0) continue
                if (d == 3 && br == 2) continue
                val next = cur.state.copyOf()
                next[blank] = next[nb]; next[nb] = 8
                val fm = if (cur.firstMove == -1) nb else cur.firstMove
                val nKey = encode(next)
                if (nKey == goalKey) return fm
                val ng = cur.g + 1
                val prev = bestG[nKey]
                if (prev == null || ng < prev) {
                    bestG[nKey] = ng
                    pq.add(Node(next, ng, ng + manhattan(next), fm))
                }
            }
        }
        return null
    }

    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        for (t in tiles) { try { if (!t.isRecycled) t.recycle() } catch (_: Throwable) {} }
        try { if (!fullBitmap.isRecycled) fullBitmap.recycle() } catch (_: Throwable) {}
    }

    private inner class SlidingBoardCanvas(ctx: Context) : View(ctx) {
        private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 18, 40) }
        private val emptyStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat(); color = Color.argb(180, 217, 70, 239)
        }
        private val okStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat(); color = Color.rgb(16, 185, 129)
        }
        private val normalStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat(); color = Color.argb(130, 255, 255, 255)
        }
        private val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(165, 12, 10, 22) }
        private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = dp(11).toFloat(); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        private var downX = 0f; private var downY = 0f; private var downCell = -1

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            if (showingPreview || solved) {
                if (!fullBitmap.isRecycled) {
                    canvas.drawBitmap(fullBitmap, null, RectF(0f, 0f, w, h), bmpPaint)
                }
                if (solved) {
                    val winStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE; strokeWidth = dp(5).toFloat(); color = Color.rgb(16, 185, 129)
                    }
                    canvas.drawRect(0f, 0f, w, h, winStroke)
                }
                return
            }

            val cellW = w / 3f; val cellH = h / 3f
            val gap = dp(2).toFloat()
            for (idx in 0 until 9) {
                val r = idx / 3; val c = idx % 3
                val rect = RectF(c * cellW + gap, r * cellH + gap, (c + 1) * cellW - gap, (r + 1) * cellH - gap)
                val tileId = board[idx]
                if (tileId == 8) {
                    canvas.drawRoundRect(rect, dp(6).toFloat(), dp(6).toFloat(), emptyPaint)
                    canvas.drawRoundRect(rect, dp(6).toFloat(), dp(6).toFloat(), emptyStroke)
                } else {
                    val bmp = tiles[tileId]
                    if (!bmp.isRecycled) canvas.drawBitmap(bmp, null, rect, bmpPaint)
                    canvas.drawRect(rect, if (tileId == idx) okStroke else normalStroke)
                    // Subtle corner number badge (1..8)
                    val br = dp(10).toFloat()
                    canvas.drawCircle(rect.left + br + dp(3), rect.top + br + dp(3), br, badgeBg)
                    canvas.drawText(
                        "${tileId + 1}",
                        rect.left + br + dp(3),
                        rect.top + br + dp(7),
                        badgeText
                    )
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (solved || showingPreview || width <= 0 || height <= 0) return true
            val cellW = width / 3f; val cellH = height / 3f
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y
                    val c = (event.x / cellW).toInt().coerceIn(0, 2)
                    val r = (event.y / cellH).toInt().coerceIn(0, 2)
                    downCell = r * 3 + c
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX; val dy = event.y - downY
                    if (downCell in 0..8) {
                        if (abs(dx) < dp(18) && abs(dy) < dp(18)) {
                            // Direct tap-to-slide!
                            trySlideFromCell(downCell)
                        } else {
                            // Swipe direction check toward blank!
                            val blank = board.indexOf(8)
                            val br = blank / 3; val bc = blank % 3
                            val dr = downCell / 3; val dc = downCell % 3
                            val matchesSwipe = if (abs(dx) > abs(dy)) {
                                (dx > 0 && br == dr && bc > dc) || (dx < 0 && br == dr && bc < dc)
                            } else {
                                (dy > 0 && bc == dc && br > dr) || (dy < 0 && bc == dc && br < dr)
                            }
                            if (matchesSwipe) trySlideFromCell(downCell)
                        }
                    }
                    downCell = -1
                    return true
                }
            }
            return true
        }
    }
}
