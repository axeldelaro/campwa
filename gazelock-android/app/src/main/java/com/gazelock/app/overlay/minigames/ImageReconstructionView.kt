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
import kotlin.random.Random

/**
 * 3x3 Image Reconstruction Mini-Game using real photos/frames from the user's video folder!
 * Supports TWO distinct tactile challenges:
 * - [Mode.SWAP_TILES]: 9 scrambled image slices; tap Tile A then Tile B to swap them into place
 *   (correctly positioned tiles lock with an emerald border + checkmark!).
 * - [Mode.ROTATE_TILES]: 9 image slices randomly rotated 90/180/270 deg; tap any slice to rotate +90 deg
 *   until the full image is restored upright!
 */
class ImageReconstructionView(
    context: Context,
    sourceBitmap: Bitmap?,
    private val mode: Mode,
    private val onPenaltySec: (Int) -> Unit,
    private val onSolved: () -> Unit
) : LinearLayout(context) {

    enum class Mode { SWAP_TILES, ROTATE_TILES }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val fullBitmap: Bitmap = PuzzleBitmapHelper.prepareSquareBoardBitmap(sourceBitmap, 540)
    private val tiles: List<Bitmap> = PuzzleBitmapHelper.sliceIntoGrid(fullBitmap, 3)

    // For SWAP_TILES: order[cell] = tileId (goal: order[i] == i)
    private val order = IntArray(9) { it }
    private var selectedCell = -1

    // For ROTATE_TILES: rotQuarter[cell] in 0..3 representing 0/90/180/270 deg (goal: all 0)
    private val rotQuarter = IntArray(9) { 0 }

    private var actionsCount = 0
    private var solved = false
    private var showingPreview = false
    private var hintAvailable = false

    private val statusTv: TextView
    private val boardView: ReconstructionCanvas
    private lateinit var hintBtn: Button

    private val unlockHintRunnable = Runnable {
        if (!solved) {
            hintAvailable = true
            hintBtn.isEnabled = true
            hintBtn.alpha = 1.0f
            hintBtn.text = if (mode == Mode.SWAP_TILES) "💡 Placer 1 pièce (+5 s)" else "💡 Redresser 2 tuiles (+5 s)"
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(Color.argb(232, 14, 14, 30))
            setStroke(dp(2), if (mode == Mode.SWAP_TILES) Color.rgb(14, 165, 233) else Color.rgb(245, 158, 11))
        }

        addView(TextView(context).apply {
            text = if (mode == Mode.SWAP_TILES) "🖼️ RECONSTITUTION D'IMAGE (ÉCHANGE 3×3)" else "🔄 REDRESSEMENT D'IMAGE (ROTATION 3×3)"
            setTextColor(Color.WHITE); textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            text = if (mode == Mode.SWAP_TILES) {
                "Touche 2 pièces pour les échanger jusqu'à reconstituer l'image du dossier. Les pièces vertes sont verrouillées !"
            } else {
                "Touche chaque tuile pour la faire pivoter de 90° jusqu'à remettre toute l'image à l'endroit !"
            }
            setTextColor(Color.rgb(212, 204, 232)); textSize = 11.2f; gravity = Gravity.CENTER; setPadding(0, dp(3), 0, dp(6))
        })

        statusTv = TextView(context).apply {
            setTextColor(Color.rgb(253, 224, 71)); textSize = 12f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        addView(statusTv)

        val boardPx = minOf((context.resources.displayMetrics.widthPixels * 0.78f).toInt(), dp(305))
        boardView = ReconstructionCanvas(context)
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
            text = "💡 Aide (dans 16 s)"
            isAllCaps = false; textSize = 11.6f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(190, 24, 93)) }
            minHeight = 0; minimumHeight = 0; setPadding(dp(10), dp(8), dp(10), dp(8))
            isEnabled = false; alpha = 0.45f
            setOnClickListener { applyHint() }
        }
        btnRow.addView(peekBtn, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) })
        btnRow.addView(hintBtn, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(5) })
        addView(btnRow, LayoutParams(boardPx, ViewGroup.LayoutParams.WRAP_CONTENT))

        scrambleBoard()
        updateStatusLabel()
        mainHandler.postDelayed(unlockHintRunnable, 16_000L)
    }

    private fun scrambleBoard() {
        if (mode == Mode.SWAP_TILES) {
            // Generate a scramble with at most 1 tile already in place
            var attempts = 0
            do {
                val list = (0 until 9).shuffled(Random)
                for (i in 0 until 9) order[i] = list[i]
                attempts++
            } while (countCorrect() > 1 && attempts < 20)
        } else {
            // Rotate at least 7 out of 9 tiles randomly by 90/180/270 deg
            for (i in 0 until 9) rotQuarter[i] = Random.nextInt(1, 4)
            // Keep 1 random tile upright as visual anchor
            rotQuarter[Random.nextInt(9)] = 0
        }
    }

    private fun countCorrect(): Int {
        var ok = 0
        if (mode == Mode.SWAP_TILES) {
            for (i in 0 until 9) if (order[i] == i) ok++
        } else {
            for (i in 0 until 9) if (rotQuarter[i] % 4 == 0) ok++
        }
        return ok
    }

    private fun updateStatusLabel() {
        val c = countCorrect()
        statusTv.text = if (solved) {
            "✅ IMAGE RECONSTITUÉE EN $actionsCount ACTIONS !"
        } else if (mode == Mode.SWAP_TILES) {
            "Pièces placées : $c/9  •  Échanges : $actionsCount"
        } else {
            "Tuiles droites : $c/9  •  Rotations : $actionsCount"
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

    private fun handleCellTap(cell: Int) {
        if (solved || showingPreview || cell !in 0..8) return
        if (mode == Mode.SWAP_TILES) {
            if (order[cell] == cell) return // Already locked green!
            if (selectedCell == -1) {
                selectedCell = cell
                boardView.invalidate()
            } else if (selectedCell == cell) {
                selectedCell = -1
                boardView.invalidate()
            } else {
                val tmp = order[selectedCell]
                order[selectedCell] = order[cell]
                order[cell] = tmp
                selectedCell = -1
                actionsCount++
                checkSolvedAndRefresh()
            }
        } else {
            if (rotQuarter[cell] % 4 == 0) return // Already upright & locked!
            rotQuarter[cell] = (rotQuarter[cell] + 1) % 4
            actionsCount++
            checkSolvedAndRefresh()
        }
    }

    private fun applyHint() {
        if (!hintAvailable || solved || showingPreview) return
        onPenaltySec(5)
        if (mode == Mode.SWAP_TILES) {
            val wrong = (0 until 9).firstOrNull { order[it] != it } ?: return
            val whereCorrectTileIs = order.indexOf(wrong)
            if (whereCorrectTileIs >= 0) {
                val tmp = order[wrong]
                order[wrong] = order[whereCorrectTileIs]
                order[whereCorrectTileIs] = tmp
                selectedCell = -1
                actionsCount++
                checkSolvedAndRefresh()
            }
        } else {
            var fixed = 0
            for (i in 0 until 9) {
                if (rotQuarter[i] % 4 != 0) {
                    rotQuarter[i] = 0
                    fixed++
                    if (fixed >= 2) break
                }
            }
            actionsCount++
            checkSolvedAndRefresh()
        }
    }

    private fun checkSolvedAndRefresh() {
        if (countCorrect() == 9) {
            solved = true
            selectedCell = -1
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

    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        for (t in tiles) { try { if (!t.isRecycled) t.recycle() } catch (_: Throwable) {} }
        try { if (!fullBitmap.isRecycled) fullBitmap.recycle() } catch (_: Throwable) {}
    }

    private inner class ReconstructionCanvas(ctx: Context) : View(ctx) {
        private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val okStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat(); color = Color.rgb(16, 185, 129)
        }
        private val selStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(4).toFloat(); color = Color.rgb(250, 204, 21)
        }
        private val normalStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat(); color = Color.argb(145, 255, 255, 255)
        }
        private val checkBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(210, 16, 185, 129) }
        private val checkText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = dp(11).toFloat(); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }

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
                val tileId = if (mode == Mode.SWAP_TILES) order[idx] else idx
                val bmp = tiles[tileId]
                val isCorrect = if (mode == Mode.SWAP_TILES) (order[idx] == idx) else (rotQuarter[idx] % 4 == 0)

                if (!bmp.isRecycled) {
                    if (mode == Mode.ROTATE_TILES && rotQuarter[idx] != 0) {
                        canvas.save()
                        canvas.rotate(rotQuarter[idx] * 90f, rect.centerX(), rect.centerY())
                        canvas.drawBitmap(bmp, null, rect, bmpPaint)
                        canvas.restore()
                    } else {
                        canvas.drawBitmap(bmp, null, rect, bmpPaint)
                    }
                }

                when {
                    idx == selectedCell -> canvas.drawRect(rect, selStroke)
                    isCorrect -> {
                        canvas.drawRect(rect, okStroke)
                        val rad = dp(9).toFloat()
                        canvas.drawCircle(rect.right - rad - dp(3), rect.top + rad + dp(3), rad, checkBg)
                        canvas.drawText("✓", rect.right - rad - dp(3), rect.top + rad + dp(7), checkText)
                    }
                    else -> canvas.drawRect(rect, normalStroke)
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (solved || showingPreview || width <= 0 || height <= 0) return true
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val c = (event.x / (width / 3f)).toInt().coerceIn(0, 2)
                val r = (event.y / (height / 3f)).toInt().coerceIn(0, 2)
                handleCellTap(r * 3 + c)
            }
            return true
        }
    }
}
