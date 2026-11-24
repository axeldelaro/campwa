package com.gazelock.app.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.gazelock.app.config.GazeConfig
import com.gazelock.app.config.GazeConfigSnapshot
import com.gazelock.app.media.VideoFeedRepository
import com.gazelock.app.overlay.minigames.ImageReconstructionView
import com.gazelock.app.overlay.minigames.SlidingPuzzleView
import kotlin.random.Random

/**
 * Fullscreen Interactive 3-Minute (180s) Imposed Overlay Unlock Portal (v3.0):
 * - Runs directly in a fullscreen TYPE_ACCESSIBILITY_OVERLAY window (or embedded in MainActivity).
 * - Continuous 0.1s (100ms, 10 FPS) folder image spam background (or auto-extracted video frames) that NEVER stops!
 * - Timed interactive Pop-Ups (Presence Check, Decoy Trap, 3-Tap Image Flash) — ignoring adds +8..10s penalty!
 * - 3 Scheduled Mandatory Visual Mini-Games using REAL images from the user's video folder:
 *   1) 3x3 Sliding Puzzle (Taquin) at ~02:25 remaining
 *   2) 3x3 Image Reconstruction (Swap Tiles) at ~01:20 remaining
 *   3) 3x3 Image Rotation Alignment at ~00:30 remaining
 *   (Timer pauses during each mini-game until solved!)
 * - Reaching 00:00 issues a fresh 6-digit access code & unlocks/removes the active video overlay!
 */
class AccessCodeWaitSpamView(
    context: Context,
    private val cfg: GazeConfigSnapshot,
    private val onIssueCode: () -> String,
    private val onCodeUnlockedAndExit: ((String) -> Unit)? = null,
    private val onCancelWait: () -> Unit
) : FrameLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private lateinit var imageView: ImageView
    private lateinit var countdownTv: TextView
    private lateinit var subTv: TextView
    private lateinit var flashAlertTv: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var popupLayer: FrameLayout
    private lateinit var minigameLayer: FrameLayout
    private lateinit var actionBtn: Button
    private lateinit var revealCard: LinearLayout
    private lateinit var revealedCodeTv: TextView

    private val bitmapPool = ArrayList<Bitmap>()
    private var running = false
    private var remainingWaitMs = GazeConfig.ACCESS_CODE_WAIT_SEC * 1000L
    private val totalWaitMs = GazeConfig.ACCESS_CODE_WAIT_SEC * 1000L
    private var flashCounter = 0
    private var lastBmpIdx = -1
    private var lastTickElapsedMs = 0L

    // Mini-games tracking
    private var activeSlidingView: SlidingPuzzleView? = null
    private var activeReconView: ImageReconstructionView? = null
    private var minigameActive = false
    private var triggeredStage1 = false // Sliding Puzzle at <= 145s
    private var triggeredStage2 = false // Swap Reconstruction at <= 80s
    private var triggeredStage3 = false // Rotate Reconstruction at <= 30s
    private var minigamesSolvedCount = 0
    private val totalMinigames = if (cfg.unlockMinigamesEnabled) 3 else 0

    // Interactive Pop-ups tracking
    private var popupActive = false
    private var popupExpireElapsedMs = 0L
    private var popupDurationMs = 8_500L
    private var popupProgressBar: ProgressBar? = null
    private var nextPopupAtRemainingMs = 164_000L
    private var popupsClearedCount = 0

    // Flash banner
    private var alertUntilElapsedMs = 0L

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (running && event != null &&
            (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_APP_SWITCH)
        ) {
            showTransientAlert("🔒 Annule en bas si tu veux quitter (remet les 3 min à zéro) !", isError = true)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private val spamTick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            val dt = (now - lastTickElapsedMs).coerceIn(50L, 350L)
            lastTickElapsedMs = now

            // 1) Advance 3-minute countdown ONLY when no mandatory mini-game is blocking!
            if (!minigameActive) {
                remainingWaitMs = (remainingWaitMs - dt).coerceAtLeast(0L)
            }

            // 2) Update progress bar & HUD timer
            val elapsedMs = (totalWaitMs - remainingWaitMs).coerceIn(0L, totalWaitMs)
            progressBar.progress = ((elapsedMs * 1000L) / totalWaitMs.coerceAtLeast(1L)).toInt()
            val remSec = ((remainingWaitMs + 999L) / 1000L).toInt()
            countdownTv.text = if (minigameActive) {
                String.format("⏸️ DÉFI REQUIS : %02d:%02d", remSec / 60, remSec % 60)
            } else {
                String.format("⏳ PORTAIL 3 MIN : %02d:%02d", remSec / 60, remSec % 60)
            }

            // 3) 0.1s (100ms) Continuous Rapid Folder Image-Spam! (Always active behind everything!)
            if (bitmapPool.isNotEmpty()) {
                var idx = Random.nextInt(bitmapPool.size)
                if (bitmapPool.size > 1 && idx == lastBmpIdx) idx = (idx + 1) % bitmapPool.size
                lastBmpIdx = idx
                val bmp = bitmapPool[idx]
                if (!bmp.isRecycled) {
                    imageView.setImageBitmap(bmp)
                    val sc = 1.0f + (flashCounter % 3) * 0.03f
                    imageView.scaleX = sc; imageView.scaleY = sc
                }
                flashCounter++
                val gameTag = if (totalMinigames > 0) " · 🧩 $minigamesSolvedCount/$totalMinigames" else ""
                val popTag = if (cfg.unlockPopupsEnabled) " · 🔔 $popupsClearedCount" else ""
                subTv.text = "🖼️ Spam 0,1 s (#$flashCounter)$gameTag$popTag"
            } else {
                setBackgroundColor(if (flashCounter++ % 2 == 0) Color.rgb(36, 8, 28) else Color.rgb(16, 8, 34))
                subTv.text = "⏳ Chargement des images du dossier… Attente obligatoire 3 min"
            }

            // 4) Alert banner auto-hide
            if (flashAlertTv.visibility == View.VISIBLE && now >= alertUntilElapsedMs) {
                flashAlertTv.visibility = View.GONE
            }

            // 5) Check Active Pop-Up Timeout
            if (popupActive) {
                val pRem = (popupExpireElapsedMs - now).coerceAtLeast(0L)
                popupProgressBar?.progress = ((pRem * 1000L) / popupDurationMs.coerceAtLeast(1L)).toInt()
                if (pRem <= 0L) {
                    dismissCurrentPopup()
                    addPenaltySeconds(10, "⏰ Pop-up ignoré ! +10 s ajoutées aux 3 min !")
                }
            }

            // 6) Trigger Mandatory Mini-Games at scheduled checkpoints!
            if (cfg.unlockMinigamesEnabled && !minigameActive) {
                when {
                    !triggeredStage1 && remainingWaitMs <= 145_000L -> {
                        triggeredStage1 = true
                        dismissCurrentPopup()
                        launchSlidingPuzzleChallenge()
                    }
                    !triggeredStage2 && remainingWaitMs <= 80_000L -> {
                        triggeredStage2 = true
                        dismissCurrentPopup()
                        launchImageReconstructionChallenge(ImageReconstructionView.Mode.SWAP_TILES)
                    }
                    !triggeredStage3 && remainingWaitMs <= 30_000L -> {
                        triggeredStage3 = true
                        dismissCurrentPopup()
                        launchImageReconstructionChallenge(ImageReconstructionView.Mode.ROTATE_TILES)
                    }
                }
            }

            // 7) Trigger Interactive Pop-Ups periodically between mini-games!
            if (cfg.unlockPopupsEnabled && !minigameActive && !popupActive &&
                remainingWaitMs in 9_000L..nextPopupAtRemainingMs
            ) {
                nextPopupAtRemainingMs = (remainingWaitMs - Random.nextLong(15_000L, 23_000L)).coerceAtLeast(0L)
                showInteractivePopup()
            }

            // 8) Completion check!
            if (remainingWaitMs <= 0L && !minigameActive) {
                finishWaitAndRevealCode()
            } else {
                mainHandler.postDelayed(this, GazeConfig.IMAGE_SPAM_DELAY_MS)
            }
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true

        // Layer 1: Fullscreen 0.1s Spam ImageView
        imageView = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        addView(imageView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Subtle dark veil so HUD, pop-ups & mini-games stay razor-sharp while 0.1s images visibly blast behind!
        addView(View(context).apply { setBackgroundColor(Color.argb(65, 10, 6, 20)) },
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Layer 2: Top Translucent Countdown Pill + Alert Banner
        val topBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(36), dp(14), dp(8))
        }
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(222, 22, 8, 30))
                setStroke(dp(2), Color.rgb(225, 29, 72))
            }
        }
        countdownTv = TextView(context).apply {
            text = "⏳ PORTAIL 3 MIN : 03:00"
            setTextColor(Color.WHITE); textSize = 18.5f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
        }
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = 0
            progressTintList = ColorStateList.valueOf(Color.rgb(225, 29, 72))
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(90, 255, 255, 255))
        }
        subTv = TextView(context).apply {
            text = "⏳ Préparation du spam d'images (0,1 s) & mini-jeux…"
            setTextColor(Color.rgb(253, 224, 71)); textSize = 11f; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0)
        }
        pill.addView(countdownTv)
        pill.addView(progressBar, LinearLayout.LayoutParams(dp(240), dp(6)).apply { topMargin = dp(5) })
        pill.addView(subTv)
        topBox.addView(pill)

        flashAlertTv = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            visibility = View.GONE
        }
        topBox.addView(flashAlertTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        addView(topBox, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Layer 3: Interactive Pop-Up Layer
        popupLayer = FrameLayout(context).apply { visibility = View.GONE }
        addView(popupLayer, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Layer 4: Mandatory Mini-Games Host Layer (Centered)
        minigameLayer = FrameLayout(context).apply {
            visibility = View.GONE
            setPadding(dp(16), dp(115), dp(16), dp(80))
        }
        addView(minigameLayer, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Layer 5: Bottom Cancel Button (resets the full 3-minute wait + mini-games!)
        actionBtn = Button(context).apply {
            text = "↩ Annuler le portail (remet les 3 min à zéro)"
            isAllCaps = false; textSize = 11.6f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(Color.argb(215, 42, 34, 62))
                setStroke(dp(1), Color.argb(120, 255, 255, 255))
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener {
                stopAndRelease()
                onCancelWait()
            }
        }
        addView(
            actionBtn,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(24)
            }
        )

        // Layer 6: Code Revealed & Direct Unlock Screen (ONLY once 00:00 AND all mini-games are done!)
        revealCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(245, 10, 8, 22))
            setPadding(dp(26), dp(26), dp(26), dp(26))
            visibility = View.GONE
        }
        revealCard.addView(TextView(context).apply {
            text = "🔓 CRÉNEAU DE 3 MIN ACCOMPLI !"
            setTextColor(Color.rgb(134, 239, 172)); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        revealCard.addView(TextView(context).apply {
            text = "Tu as complété les 3 minutes de spam 0,1 s, les pop-ups et les mini-jeux. Voici ton code d'accès unique (valable 2 min) :"
            setTextColor(Color.rgb(215, 215, 235)); textSize = 13f; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(14))
        })
        revealedCodeTv = TextView(context).apply {
            text = "------"
            setTextColor(Color.rgb(253, 224, 71)); textSize = 36f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat(); setColor(Color.rgb(28, 22, 48))
                setStroke(dp(2), Color.rgb(250, 204, 21))
            }
            setPadding(dp(24), dp(14), dp(24), dp(14))
        }
        revealCard.addView(revealedCodeTv)
        val confirmBtn = Button(context).apply {
            text = if (onCodeUnlockedAndExit != null) "🔓 RETIRER LES VIDÉOS MAINTENANT" else "✅ FERMER"
            isAllCaps = false; textSize = 14.2f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.rgb(16, 185, 129)) }
            setPadding(dp(20), dp(14), dp(20), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) }
            setOnClickListener {
                val c = revealedCodeTv.text?.toString()?.replace(" ", "").orEmpty()
                stopAndRelease()
                if (onCodeUnlockedAndExit != null) onCodeUnlockedAndExit.invoke(c) else onCancelWait()
            }
        }
        revealCard.addView(confirmBtn)
        addView(revealCard, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun startThreeMinuteSpamWait() {
        if (running) return
        running = true
        flashCounter = 0
        lastBmpIdx = -1
        remainingWaitMs = GazeConfig.ACCESS_CODE_WAIT_SEC * 1000L
        lastTickElapsedMs = SystemClock.elapsedRealtime()
        nextPopupAtRemainingMs = 165_000L
        triggeredStage1 = false; triggeredStage2 = false; triggeredStage3 = false
        minigamesSolvedCount = 0; popupsClearedCount = 0

        // Preload memory-safe folder image pool (or video-frame fallback) off UI thread!
        Thread {
            val loaded = VideoFeedRepository.loadSpamBitmapPool(context.applicationContext, cfg, maxPoolSize = 36)
            mainHandler.post {
                if (!running) {
                    for (b in loaded) if (!b.isRecycled) b.recycle()
                    return@post
                }
                bitmapPool.addAll(loaded)
            }
        }.start()

        mainHandler.post(spamTick)
    }

    private fun pickSafeSourceBitmap(): Bitmap? {
        val valid = bitmapPool.filter { !it.isRecycled }
        return if (valid.isNotEmpty()) valid[Random.nextInt(valid.size)] else null
    }

    // ==================== MANDATORY VISUAL MINI-GAMES ====================
    private fun launchSlidingPuzzleChallenge() {
        clearMinigameViews()
        minigameActive = true
        vibrateMs(90L)
        showTransientAlert("🧩 DÉFI #1 : Résous le Puzzle à glissement pour reprendre le chrono !", isError = false)
        val view = SlidingPuzzleView(
            context = context,
            sourceBitmap = pickSafeSourceBitmap(),
            onPenaltySec = { sec -> addPenaltySeconds(sec, "💡 Indice utilisé : +$sec s ajoutées !") },
            onSolved = {
                minigamesSolvedCount++
                vibrateMs(120L)
                clearMinigameViews()
                minigameActive = false
                showTransientAlert("✅ Puzzle résolu ! Décompte repris !", isError = false)
            }
        )
        activeSlidingView = view
        minigameLayer.addView(
            view,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        minigameLayer.visibility = View.VISIBLE
    }

    private fun launchImageReconstructionChallenge(mode: ImageReconstructionView.Mode) {
        clearMinigameViews()
        minigameActive = true
        vibrateMs(90L)
        val label = if (mode == ImageReconstructionView.Mode.SWAP_TILES) {
            "🖼️ DÉFI #2 : Reconstitue l'image par échange !"
        } else {
            "🔄 DÉFI FINAL #3 : Redresse chaque tuile de l'image !"
        }
        showTransientAlert(label, isError = false)
        val view = ImageReconstructionView(
            context = context,
            sourceBitmap = pickSafeSourceBitmap(),
            mode = mode,
            onPenaltySec = { sec -> addPenaltySeconds(sec, "💡 Indice utilisé : +$sec s ajoutées !") },
            onSolved = {
                minigamesSolvedCount++
                vibrateMs(120L)
                clearMinigameViews()
                minigameActive = false
                showTransientAlert("✅ Image reconstituée ! Décompte repris !", isError = false)
            }
        )
        activeReconView = view
        minigameLayer.addView(
            view,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        minigameLayer.visibility = View.VISIBLE
    }

    private fun clearMinigameViews() {
        activeSlidingView?.release(); activeSlidingView = null
        activeReconView?.release(); activeReconView = null
        minigameLayer.removeAllViews()
        minigameLayer.visibility = View.GONE
    }

    // ==================== TIMED INTERACTIVE POP-UPS ====================
    private fun showInteractivePopup() {
        dismissCurrentPopup()
        popupActive = true
        popupDurationMs = 8_500L
        popupExpireElapsedMs = SystemClock.elapsedRealtime() + popupDurationMs
        vibrateMs(55L)

        val kind = Random.nextInt(3)
        val gravityPick = listOf(
            Gravity.CENTER,
            Gravity.CENTER_HORIZONTAL or Gravity.TOP,
            Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        )[Random.nextInt(3)]

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(238, 22, 12, 36))
                setStroke(dp(2), when (kind) {
                    0 -> Color.rgb(236, 72, 153)
                    1 -> Color.rgb(245, 158, 11)
                    else -> Color.rgb(168, 85, 247)
                })
            }
        }

        // Countdown bar inside pop-up
        val pBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = 1000
            progressTintList = ColorStateList.valueOf(Color.rgb(250, 204, 21))
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(80, 255, 255, 255))
        }
        popupProgressBar = pBar
        card.addView(pBar, LinearLayout.LayoutParams(dp(220), dp(5)).apply { bottomMargin = dp(8) })

        val thumb = pickSafeSourceBitmap()
        if (thumb != null) {
            card.addView(ImageView(context).apply {
                setImageBitmap(thumb)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, LinearLayout.LayoutParams(dp(116), dp(82)).apply { bottomMargin = dp(8) })
        }

        when (kind) {
            0 -> {
                // Presence check: tap twice (button shifts after first tap!)
                card.addView(TextView(context).apply {
                    text = "⚡ POP-UP PRÉSENCE"
                    setTextColor(Color.rgb(249, 168, 212)); textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                })
                card.addView(TextView(context).apply {
                    text = "Tu veux déjà retirer les vidéos ? Confirme que tu regardes (sinon +10 s) !"
                    setTextColor(Color.WHITE); textSize = 11.6f; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(10))
                })
                var tapsLeft = 2
                val btn = Button(context).apply {
                    text = "👁️ JE REGARDE (2 clics)"
                    isAllCaps = false; textSize = 12.2f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.rgb(219, 39, 119)) }
                    setPadding(dp(14), dp(9), dp(14), dp(9))
                }
                btn.setOnClickListener {
                    tapsLeft--
                    if (tapsLeft > 0) {
                        btn.text = "⚡ ENCORE 1 CLIC !"
                        btn.translationX = (if (Random.nextBoolean()) 1 else -1) * dp(28).toFloat()
                    } else {
                        popupsClearedCount++
                        dismissCurrentPopup()
                        showTransientAlert("✅ Pop-up validé !", isError = false)
                    }
                }
                card.addView(btn)
            }
            1 -> {
                // Decoy Trap Pop-up!
                card.addView(TextView(context).apply {
                    text = "🎭 POP-UP PIÈGE"
                    setTextColor(Color.rgb(253, 224, 71)); textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                })
                card.addView(TextView(context).apply {
                    text = "Choisis le BON bouton pour poursuivre le décompte (piège = +8 s) :"
                    setTextColor(Color.WHITE); textSize = 11.6f; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(10))
                })
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                val goodBtn = Button(context).apply {
                    text = "✅ Poursuivre"
                    isAllCaps = false; textSize = 11.8f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(79, 70, 229)) }
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    setOnClickListener {
                        popupsClearedCount++
                        dismissCurrentPopup()
                        showTransientAlert("✅ Bien vu !", isError = false)
                    }
                }
                val trapBtn = Button(context).apply {
                    text = "⚡ Quitter (+8 s)"
                    isAllCaps = false; textSize = 11.8f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.rgb(16, 185, 129)) }
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    setOnClickListener {
                        dismissCurrentPopup()
                        addPenaltySeconds(8, "😈 Piège déclenché ! +8 s ajoutées !")
                    }
                }
                val lpA = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
                val lpB = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) }
                if (Random.nextBoolean()) { row.addView(goodBtn, lpA); row.addView(trapBtn, lpB) }
                else { row.addView(trapBtn, lpA); row.addView(goodBtn, lpB) }
                card.addView(row, LinearLayout.LayoutParams(dp(245), ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            else -> {
                // Mantra Image Flash Pop-up (3 taps to dismiss)
                val mantra = cfg.mantraList().random()
                card.addView(TextView(context).apply {
                    text = "✨ $mantra"
                    setTextColor(Color.rgb(216, 180, 254)); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                })
                card.addView(TextView(context).apply {
                    text = "Touche 3 fois rapidement pour dissiper ce pop-up :"
                    setTextColor(Color.WHITE); textSize = 11.4f; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(10))
                })
                var remTaps = 3
                val btn = Button(context).apply {
                    text = "🔓 DISSIPER (3)"
                    isAllCaps = false; textSize = 12.2f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.rgb(147, 51, 234)) }
                    setPadding(dp(16), dp(9), dp(16), dp(9))
                }
                btn.setOnClickListener {
                    remTaps--
                    if (remTaps > 0) {
                        btn.text = "🔓 DISSIPER ($remTaps)"
                    } else {
                        popupsClearedCount++
                        dismissCurrentPopup()
                    }
                }
                card.addView(btn)
            }
        }

        val lp = LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT, gravityPick).apply {
            topMargin = dp(148)
            bottomMargin = dp(95)
        }
        popupLayer.addView(card, lp)
        popupLayer.visibility = View.VISIBLE
    }

    private fun dismissCurrentPopup() {
        popupActive = false
        popupProgressBar = null
        popupLayer.removeAllViews()
        popupLayer.visibility = View.GONE
    }

    private fun addPenaltySeconds(sec: Int, msg: String) {
        remainingWaitMs = (remainingWaitMs + sec * 1000L).coerceAtMost(totalWaitMs + 120_000L)
        vibrateMs(140L)
        showTransientAlert(msg, isError = true)
    }

    private fun showTransientAlert(msg: String, isError: Boolean) {
        flashAlertTv.text = msg
        flashAlertTv.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(if (isError) Color.argb(235, 190, 18, 60) else Color.argb(230, 16, 128, 96))
        }
        flashAlertTv.visibility = View.VISIBLE
        alertUntilElapsedMs = SystemClock.elapsedRealtime() + 3_200L
    }

    private fun finishWaitAndRevealCode() {
        running = false
        mainHandler.removeCallbacks(spamTick)
        dismissCurrentPopup()
        clearMinigameViews()
        val freshCode = onIssueCode()
        val pretty = if (freshCode.length == 6) "${freshCode.substring(0, 3)} ${freshCode.substring(3)}" else freshCode
        revealedCodeTv.text = pretty
        actionBtn.visibility = View.GONE
        revealCard.visibility = View.VISIBLE
        vibrateMs(150L)
    }

    @Suppress("DEPRECATION")
    private fun vibrateMs(ms: Long) {
        try {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                v.vibrate(ms)
            }
        } catch (_: Throwable) {}
    }

    fun stopAndRelease() {
        running = false
        mainHandler.removeCallbacks(spamTick)
        dismissCurrentPopup()
        clearMinigameViews()
        imageView.setImageDrawable(null)
        for (b in bitmapPool) {
            try { if (!b.isRecycled) b.recycle() } catch (_: Throwable) {}
        }
        bitmapPool.clear()
    }
}
