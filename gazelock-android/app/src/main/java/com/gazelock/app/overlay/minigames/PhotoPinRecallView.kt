package com.gazelock.app.overlay.minigames

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

/**
 * Mini-Game #16 (v7.0 TITAN): Visual Photo-PIN Recall ("Code Visuel de Photos")
 * - 3 rounds: displays a 4-photo secret sequence for 2.8 seconds, then hides it!
 * - Tap the 4 photos in the exact memorized order from the 6-photo folder grid below.
 * - Mistake flashes red, calls onMistake(), and briefly re-flashes the sequence!
 */
class PhotoPinRecallView(
    context: Context,
    poolBitmaps: List<Bitmap>,
    private val onMistake: () -> Unit,
    private val onSolved: () -> Unit
) : LinearLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val safePool = ArrayList<Bitmap>()
    private val statusTv: TextView
    private val seqStrip: LinearLayout
    private val gridHost: LinearLayout

    private val totalRounds = 3
    private val seqLen = 4
    private var currentRound = 0
    private var roundPhotos = emptyList<Bitmap>()
    private var secretOrder = emptyList<Int>()
    private var inputCursor = 0
    private var acceptingInput = false
    private var finished = false
    private var released = false

    init {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.argb(242, 14, 10, 26))
            setStroke(dp(2), Color.rgb(168, 85, 247))
        }
        setPadding(dp(12), dp(12), dp(12), dp(12))

        val valid = poolBitmaps.filter { !it.isRecycled }.take(10)
        for (b in valid) safePool.add(PuzzleBitmapHelper.prepareSquareBoardBitmap(b, 200))
        while (safePool.size < 6) safePool.add(PuzzleBitmapHelper.prepareSquareBoardBitmap(null, 200))

        addView(TextView(context).apply {
            text = "🔐 CODE VISUEL DE PHOTOS"
            setTextColor(Color.rgb(216, 180, 254)); textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        statusTv = TextView(context).apply {
            text = "👀 Mémorise la séquence de 4 photos…"
            setTextColor(Color.WHITE); textSize = 11.6f; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(8))
        }
        addView(statusTv)

        seqStrip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(Color.rgb(26, 18, 44))
                setStroke(dp(1), Color.argb(120, 168, 85, 247))
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        addView(seqStrip, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })

        gridHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        addView(gridHost, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private val hideSeqRunnable: Runnable = Runnable {
        if (finished || released) return@Runnable
        acceptingInput = true
        renderSequenceStrip(revealed = false)
        statusTv.text = "🔐 Manche ${currentRound + 1}/$totalRounds • Reproduis l'ordre (0/$seqLen)"
        statusTv.setTextColor(Color.rgb(253, 224, 71))
    }

    init { startRound(0) }

    private fun startRound(roundIdx: Int) {
        currentRound = roundIdx
        inputCursor = 0
        acceptingInput = false
        roundPhotos = safePool.shuffled(Random).take(6)
        secretOrder = (0 until 6).shuffled(Random).take(seqLen)

        renderGrid()
        renderSequenceStrip(revealed = true)
        statusTv.text = "👀 Manche ${roundIdx + 1}/$totalRounds • MÉMORISE CET ORDRE (2,8 s) !"
        statusTv.setTextColor(Color.rgb(125, 211, 252))

        mainHandler.removeCallbacks(hideSeqRunnable)
        mainHandler.postDelayed(hideSeqRunnable, 2_800L)
    }

    private fun renderSequenceStrip(revealed: Boolean) {
        seqStrip.removeAllViews()
        val thumbPx = dp(52)
        for (slot in 0 until seqLen) {
            val box = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(if (slot < inputCursor) Color.rgb(16, 128, 96) else Color.rgb(36, 26, 58))
                    setStroke(dp(2), if (slot < inputCursor) Color.rgb(134, 239, 172) else Color.argb(120, 255, 255, 255))
                }
            }
            val showImg = revealed || slot < inputCursor
            if (showImg) {
                val bmp = roundPhotos[secretOrder[slot]]
                box.addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    if (!bmp.isRecycled) setImageBitmap(bmp)
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            } else {
                box.addView(TextView(context).apply {
                    text = "${slot + 1}"
                    setTextColor(Color.rgb(253, 224, 71)); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            seqStrip.addView(box, LayoutParams(thumbPx, thumbPx).apply {
                marginStart = dp(4); marginEnd = dp(4)
            })
        }
    }

    private fun renderGrid() {
        gridHost.removeAllViews()
        val tileSz = dp(82)
        for (row in 0 until 2) {
            val rowBox = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            for (col in 0 until 3) {
                val idx = row * 3 + col
                val bmp = roundPhotos[idx]
                val card = FrameLayout(context).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(2), Color.argb(150, 216, 180, 254))
                    }
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    setOnClickListener { onPhotoTapped(idx) }
                }
                card.addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    if (!bmp.isRecycled) setImageBitmap(bmp)
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                rowBox.addView(card, LayoutParams(tileSz, tileSz).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) })
            }
            gridHost.addView(rowBox)
        }
    }

    private fun onPhotoTapped(idx: Int) {
        if (!acceptingInput || finished || released) return
        if (idx == secretOrder[inputCursor]) {
            inputCursor++
            renderSequenceStrip(revealed = false)
            if (inputCursor >= seqLen) {
                acceptingInput = false
                if (currentRound + 1 >= totalRounds) {
                    finished = true
                    statusTv.text = "✅ CODE VISUEL VALIDÉ !"
                    statusTv.setTextColor(Color.rgb(134, 239, 172))
                    mainHandler.postDelayed({ if (!released) onSolved() }, 360L)
                } else {
                    statusTv.text = "✅ Manche réussie !"
                    statusTv.setTextColor(Color.rgb(134, 239, 172))
                    mainHandler.postDelayed({ if (!released) startRound(currentRound + 1) }, 500L)
                }
            } else {
                statusTv.text = "🔐 Manche ${currentRound + 1}/$totalRounds • Bien ! ($inputCursor/$seqLen)"
                statusTv.setTextColor(Color.rgb(134, 239, 172))
            }
        } else {
            onMistake()
            acceptingInput = false
            inputCursor = 0
            statusTv.text = "❌ Mauvaise photo ! +5 s · Regarde encore !"
            statusTv.setTextColor(Color.rgb(252, 165, 165))
            renderSequenceStrip(revealed = true)
            mainHandler.removeCallbacks(hideSeqRunnable)
            mainHandler.postDelayed(hideSeqRunnable, 2_000L)
        }
    }

    fun release() {
        released = true
        mainHandler.removeCallbacks(hideSeqRunnable)
        for (b in safePool) if (!b.isRecycled) b.recycle()
        safePool.clear()
    }
}
