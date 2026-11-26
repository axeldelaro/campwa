package com.aigallery.pro.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import com.aigallery.pro.ai.VisualPreferenceAiEngine
import com.aigallery.pro.data.MediaItem
import com.aigallery.pro.data.MediaRepository
import com.aigallery.pro.data.MediaType
import com.aigallery.pro.data.PersonProfile
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import java.io.File
import kotlin.math.abs

/**
 * 60 FPS Physics Tinder-Style AI Media Swipe Deck:
 * - Drag RIGHT = ❤️ Like (Favorite + AI learns +1)
 * - Drag LEFT  = ✕ Pass (AI learns -1)
 * - Drag UP    = ⭐ Super-Like (VIP Favorite + AI learns ×2.5!)
 * - Auto-plays videos right inside the top swipe card with mute toggle.
 * - Displays live AI favorite probability, recognized person chips, and plain-French explanation!
 * - Supports Undo, Fullscreen preview, and 1-tap Recycle Bin cleaning!
 */
class TinderSwipeView(
    context: Context,
    private val accentColor: Int,
    private var autoPlaySound: Boolean,
    private val onSwiped: (item: MediaItem, rating: Int) -> Unit,
    private val onUndoRequested: () -> Unit,
    private val onOpenFullscreen: (MediaItem) -> Unit,
    private val onTrashItem: (MediaItem) -> Unit,
    private val onModeChanged: (VisualPreferenceAiEngine.TinderDeckMode) -> Unit,
    private val onClearFilter: () -> Unit
) : LinearLayout(context) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val modeRow: LinearLayout
    private val filterBannerRow: LinearLayout
    private val filterBannerTv: TextView
    private val statsStripTv: TextView
    private val cardStage: FrameLayout
    private val emptyStateBox: LinearLayout
    private val emptyMsgTv: TextView
    private val undoBtn: TextView

    private val queue = ArrayList<MediaItem>()
    private var personsById: Map<Long, PersonProfile> = emptyMap()
    private var currentMode = VisualPreferenceAiEngine.TinderDeckMode.ACTIVE_DISCOVERY
    private var activeTopVideoView: VideoView? = null
    private var activeTopMp: MediaPlayer? = null
    private var topCardView: FrameLayout? = null
    private var backCardView: FrameLayout? = null
    private var animatingOut = false

    init {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(12), dp(6), dp(12), dp(8))

        // Mode switcher chips
        modeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(6)) }
        rebuildModeChips()
        addView(modeRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Active folder/person filter pill (when filtering!)
        filterBannerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(Color.argb(65, 168, 85, 247), 10, Color.rgb(168, 85, 247))
            setPadding(dp(10), dp(5), dp(10), dp(5))
            visibility = View.GONE
        }
        filterBannerTv = TextView(context).apply { setTextColor(Color.WHITE); textSize = 11.2f; typeface = Typeface.DEFAULT_BOLD }
        filterBannerRow.addView(filterBannerTv, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        filterBannerRow.addView(TextView(context).apply {
            text = "✕ Tous"; setTextColor(Color.rgb(253, 224, 71)); textSize = 11.2f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(2), dp(4), dp(2))
            setOnClickListener { onClearFilter() }
        })
        addView(filterBannerRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(5) })

        // Live AI training telemetry strip
        statsStripTv = TextView(context).apply {
            text = "🧠 Glisse à droite ❤️ · à gauche ✕ · en haut ⭐ Super-Like"
            setTextColor(Color.rgb(200, 198, 222)); textSize = 11f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }
        addView(statsStripTv)

        // Card Stack Stage
        cardStage = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
        emptyStateBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = roundedBg(Color.rgb(26, 26, 36), 18, Color.argb(70, 255, 255, 255))
            setPadding(dp(24), dp(24), dp(24), dp(24))
            visibility = View.GONE
        }
        emptyMsgTv = TextView(context).apply {
            text = "🎉 Tu as tout swipé !\nConsulte l'onglet ✨ Pour Toi ou réinitialise tes filtres."
            setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
        }
        emptyStateBox.addView(emptyMsgTv)
        cardStage.addView(emptyStateBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(cardStage, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Bottom Tinder Action Buttons (Undo, Pass, Trash, Super-Like, Like, Fullscreen)
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(4))
        }
        undoBtn = makeCircleBtn("↩", 44, Color.rgb(44, 44, 58), Color.rgb(253, 224, 71)) { onUndoRequested() }
        val passBtn = makeCircleBtn("✕", 58, Color.rgb(66, 20, 32), Color.rgb(244, 63, 94)) { triggerProgrammaticSwipe(-1) }
        val trashBtn = makeCircleBtn("🗑️", 44, Color.rgb(44, 34, 46), Color.rgb(220, 220, 232)) {
            queue.firstOrNull()?.let { onTrashItem(it) }
        }
        val superBtn = makeCircleBtn("⭐", 52, Color.rgb(64, 48, 16), Color.rgb(250, 204, 21)) { triggerProgrammaticSwipe(2) }
        val likeBtn = makeCircleBtn("❤️", 58, Color.rgb(16, 62, 46), Color.rgb(16, 185, 129)) { triggerProgrammaticSwipe(1) }
        val viewBtn = makeCircleBtn("🔍", 44, Color.rgb(36, 40, 64), Color.rgb(125, 211, 252)) {
            queue.firstOrNull()?.let { onOpenFullscreen(it) }
        }

        actionsRow.addView(undoBtn, circleLp(44))
        actionsRow.addView(passBtn, circleLp(58))
        actionsRow.addView(trashBtn, circleLp(44))
        actionsRow.addView(superBtn, circleLp(52))
        actionsRow.addView(likeBtn, circleLp(58))
        actionsRow.addView(viewBtn, circleLp(44))
        addView(actionsRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun rebuildModeChips() {
        modeRow.removeAllViews()
        val modes = VisualPreferenceAiEngine.TinderDeckMode.values()
        modes.forEachIndexed { idx, m ->
            val active = currentMode == m
            val b = Button(context).apply {
                text = when (m) {
                    VisualPreferenceAiEngine.TinderDeckMode.ACTIVE_DISCOVERY -> "🎲 Découverte IA"
                    VisualPreferenceAiEngine.TinderDeckMode.AI_TOP_PICKS -> "✨ Top IA"
                    VisualPreferenceAiEngine.TinderDeckMode.LATEST_FIRST -> "🕒 Récents"
                }
                isAllCaps = false; textSize = 10.8f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (active) Color.WHITE else Color.rgb(185, 185, 208))
                background = roundedBg(if (active) accentColor else Color.rgb(32, 32, 42), 11, if (active) Color.WHITE else Color.argb(40, 255, 255, 255))
                minHeight = 0; minimumHeight = 0; setPadding(dp(4), dp(7), dp(4), dp(7))
                setOnClickListener {
                    if (currentMode != m) {
                        currentMode = m
                        rebuildModeChips()
                        onModeChanged(m)
                    }
                }
            }
            modeRow.addView(b, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (idx > 0) marginStart = dp(4)
                if (idx < modes.lastIndex) marginEnd = dp(4)
            })
        }
    }

    fun updateDeck(
        newQueue: List<MediaItem>,
        persons: Map<Long, PersonProfile>,
        trainedSwipesCount: Int,
        filterLabel: String?,
        canUndo: Boolean,
        soundEnabled: Boolean
    ) {
        autoPlaySound = soundEnabled
        personsById = persons
        queue.clear()
        queue.addAll(newQueue)
        undoBtn.alpha = if (canUndo) 1f else 0.35f

        if (!filterLabel.isNullOrBlank()) {
            filterBannerTv.text = "🎯 Filtre actif : $filterLabel"
            filterBannerRow.visibility = View.VISIBLE
        } else {
            filterBannerRow.visibility = View.GONE
        }

        val brainLvl = when {
            trainedSwipesCount >= 100 -> "🧠 IA Expert ($trainedSwipesCount swipes)"
            trainedSwipesCount >= 30 -> "🧠 IA Précise ($trainedSwipesCount swipes)"
            trainedSwipesCount >= 10 -> "🧠 IA en Apprentissage ($trainedSwipesCount swipes)"
            else -> "🌱 Entraîne l'IA ($trainedSwipesCount/10 swipes initiaux)"
        }
        statsStripTv.text = "$brainLvl · ${queue.size} restants · ${persons.size} pers."
        renderCardStack()
    }

    fun pauseVideo() {
        try { activeTopVideoView?.pause() } catch (_: Throwable) {}
    }

    fun stopVideo() {
        activeTopMp = null
        try { activeTopVideoView?.stopPlayback() } catch (_: Throwable) {}
        activeTopVideoView = null
    }

    private fun renderCardStack() {
        stopVideo()
        cardStage.removeAllViews()
        cardStage.addView(emptyStateBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        topCardView = null
        backCardView = null
        animatingOut = false

        if (queue.isEmpty()) {
            emptyStateBox.visibility = View.VISIBLE
            return
        }
        emptyStateBox.visibility = View.GONE

        // Render next card underneath for depth!
        if (queue.size >= 2) {
            val back = buildCardView(queue[1], isTop = false)
            back.scaleX = 0.94f; back.scaleY = 0.94f; back.translationY = dp(10).toFloat(); back.alpha = 0.78f
            backCardView = back
            cardStage.addView(back, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        val top = buildCardView(queue[0], isTop = true)
        topCardView = top
        cardStage.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun buildCardView(item: MediaItem, isTop: Boolean): FrameLayout {
        val card = FrameLayout(context).apply {
            background = roundedBg(Color.rgb(20, 20, 28), 20, Color.argb(90, 255, 255, 255))
            clipToOutline = true
            elevation = if (isTop) dp(8).toFloat() else dp(2).toFloat()
        }

        if (item.type == MediaType.VIDEO && isTop) {
            val vv = VideoView(context)
            card.addView(vv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            vv.setVideoURI(Uri.fromFile(File(item.path)))
            var muted = !autoPlaySound
            vv.setOnPreparedListener { mp ->
                activeTopMp = mp
                mp.isLooping = true
                mp.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
                vv.start()
            }
            activeTopVideoView = vv

            // Mute toggle chip on top-right of video card
            val muteChip = TextView(context).apply {
                text = if (muted) "🔇 Son OFF" else "🔊 Son ON"
                setTextColor(Color.WHITE); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
                background = roundedBg(Color.argb(190, 14, 14, 22), 12)
                setPadding(dp(9), dp(5), dp(9), dp(5))
                setOnClickListener {
                    muted = !muted
                    text = if (muted) "🔇 Son OFF" else "🔊 Son ON"
                    try { activeTopMp?.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f) } catch (_: Throwable) {}
                }
            }
            card.addView(muteChip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(12); marginEnd = dp(12)
            })
        } else {
            val iv = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            Glide.with(iv).load(File(item.path)).centerCrop().diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).into(iv)
            card.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        // Top-Left AI Prediction Pill!
        val aiBadge = TextView(context).apply {
            val pctStr = if (item.aiScorePct >= 0) "✨ ${item.aiScorePct} % Match IA" else "🧠 Analyse IA…"
            text = pctStr
            setTextColor(Color.WHITE); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            val bg = when {
                item.aiScorePct >= 80 -> Color.argb(225, 16, 150, 95)
                item.aiScorePct >= 60 -> Color.argb(225, 147, 51, 234)
                item.aiScorePct >= 0 -> Color.argb(210, 32, 32, 46)
                else -> Color.argb(210, 245, 124, 0)
            }
            background = roundedBg(bg, 14, Color.argb(130, 255, 255, 255))
            setPadding(dp(11), dp(6), dp(11), dp(6))
        }
        card.addView(aiBadge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(12); marginStart = dp(12)
        })

        // Bottom info scrim (Recognized Persons + AI Explanation + Folder + Name)
        val infoBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.argb(225, 8, 8, 14), Color.argb(245, 8, 8, 14))
            )
            setPadding(dp(14), dp(36), dp(14), dp(14))
        }

        // Recognized person tags!
        val matchedPeople = item.personIds.mapNotNull { personsById[it] }
        if (matchedPeople.isNotEmpty()) {
            val peopleTxt = matchedPeople.take(3).joinToString("  ·  ") { p ->
                val star = if (p.isVip) "⭐ " else "👤 "
                "$star${p.displayName()} (${p.affinityPct()} %)"
            }
            infoBox.addView(TextView(context).apply {
                text = peopleTxt
                setTextColor(Color.rgb(253, 224, 71)); textSize = 12.2f; typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(3))
            })
        }

        val typeIcon = when (item.type) {
            MediaType.VIDEO -> "🎬 ${if (item.durationMs > 0L) MediaRepository.formatDuration(item.durationMs) else "Vidéo"} · "
            MediaType.GIF -> "🎞️ GIF · "
            MediaType.PHOTO -> ""
        }
        infoBox.addView(TextView(context).apply {
            text = "$typeIcon${item.folderName} — ${item.name}"
            setTextColor(Color.WHITE); textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1
        })
        if (item.aiReason.isNotBlank()) {
            infoBox.addView(TextView(context).apply {
                text = "💡 ${item.aiReason}"
                setTextColor(Color.rgb(210, 206, 232)); textSize = 11.4f; setPadding(0, dp(3), 0, 0)
            })
        }
        card.addView(infoBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // Tinder Swipe Stamps (LIKE / NOPE / SUPER-LIKE)
        val likeStamp = makeStamp("❤️ J'AIME", Color.rgb(16, 185, 129), -14f)
        val passStamp = makeStamp("✕ PASSER", Color.rgb(244, 63, 94), 14f)
        val superStamp = makeStamp("⭐ SUPER-LIKE", Color.rgb(250, 204, 21), 0f)
        card.addView(likeStamp, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(58); marginStart = dp(20)
        })
        card.addView(passStamp, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(58); marginEnd = dp(20)
        })
        card.addView(superStamp, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(100)
        })

        if (isTop) {
            attachSwipePhysics(card, item, likeStamp, passStamp, superStamp)
        }
        return card
    }

    private fun makeStamp(text: String, strokeCol: Int, rot: Float) = TextView(context).apply {
        this.text = text; setTextColor(strokeCol); textSize = 22f; typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.argb(175, 10, 10, 16))
            setStroke(dp(3), strokeCol)
        }
        setPadding(dp(12), dp(6), dp(12), dp(6))
        rotation = rot
        alpha = 0f
    }

    private fun attachSwipePhysics(card: FrameLayout, item: MediaItem, likeStamp: View, passStamp: View, superStamp: View) {
        var downX = 0f; var downY = 0f
        var moved = false
        card.setOnTouchListener { v, ev ->
            if (animatingOut) return@setOnTouchListener true
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    v.translationX = dx
                    v.translationY = dy
                    v.rotation = (dx / dp(22)).coerceIn(-22f, 22f)

                    val w = cardStage.width.coerceAtLeast(300).toFloat()
                    val h = cardStage.height.coerceAtLeast(300).toFloat()
                    likeStamp.alpha = (dx / (w * 0.25f)).coerceIn(0f, 1f)
                    passStamp.alpha = (-dx / (w * 0.25f)).coerceIn(0f, 1f)
                    superStamp.alpha = if (abs(dx) < w * 0.20f) (-dy / (h * 0.20f)).coerceIn(0f, 1f) else 0f

                    val progress = maxOf(abs(dx) / (w * 0.35f), (-dy) / (h * 0.30f)).coerceIn(0f, 1f)
                    backCardView?.let { bc ->
                        bc.scaleX = 0.94f + 0.06f * progress
                        bc.scaleY = 0.94f + 0.06f * progress
                        bc.translationY = dp(10) * (1f - progress)
                        bc.alpha = 0.78f + 0.22f * progress
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    val w = cardStage.width.coerceAtLeast(300).toFloat()
                    val h = cardStage.height.coerceAtLeast(300).toFloat()
                    when {
                        !moved -> onOpenFullscreen(item)
                        dx > w * 0.27f -> animateCardOut(v, item, rating = 1)
                        dx < -w * 0.27f -> animateCardOut(v, item, rating = -1)
                        dy < -h * 0.20f && abs(dx) < w * 0.28f -> animateCardOut(v, item, rating = 2)
                        else -> {
                            v.animate().translationX(0f).translationY(0f).rotation(0f).setDuration(200L).start()
                            likeStamp.animate().alpha(0f).setDuration(150L).start()
                            passStamp.animate().alpha(0f).setDuration(150L).start()
                            superStamp.animate().alpha(0f).setDuration(150L).start()
                            backCardView?.animate()?.scaleX(0.94f)?.scaleY(0.94f)?.translationY(dp(10).toFloat())?.alpha(0.78f)?.setDuration(200L)?.start()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    fun triggerProgrammaticSwipe(rating: Int) {
        if (animatingOut) return
        val top = topCardView ?: return
        val item = queue.firstOrNull() ?: return
        animateCardOut(top, item, rating)
    }

    private fun animateCardOut(card: View, item: MediaItem, rating: Int) {
        animatingOut = true
        stopVideo()
        vibrateQuick(if (rating == 2) 45L else 22L)
        val sw = resources.displayMetrics.widthPixels.toFloat()
        val sh = resources.displayMetrics.heightPixels.toFloat()
        val targetX = when (rating) {
            1 -> sw * 1.35f
            -1 -> -sw * 1.35f
            else -> 0f
        }
        val targetY = if (rating == 2) -sh * 1.15f else card.translationY + dp(40)
        val targetRot = when (rating) {
            1 -> 24f
            -1 -> -24f
            else -> 0f
        }
        backCardView?.animate()?.scaleX(1f)?.scaleY(1f)?.translationY(0f)?.alpha(1f)?.setDuration(220L)?.start()
        card.animate()
            .translationX(targetX)
            .translationY(targetY)
            .rotation(targetRot)
            .alpha(0f)
            .setDuration(230L)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animatingOut = false
                    onSwiped(item, rating)
                }
            })
            .start()
    }

    private fun makeCircleBtn(icon: String, sizeDp: Int, fill: Int, stroke: Int, onClick: () -> Unit) = TextView(context).apply {
        text = icon; textSize = if (sizeDp >= 54) 22f else 17f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill); setStroke(dp(2), stroke) }
        setOnClickListener { onClick() }
    }

    private fun circleLp(sizeDp: Int) = LayoutParams(dp(sizeDp), dp(sizeDp)).apply {
        marginStart = dp(5); marginEnd = dp(5)
    }

    private fun roundedBg(fill: Int, radiusDp: Int, strokeColor: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
        if (strokeColor != Color.TRANSPARENT) setStroke(dp(1), strokeColor)
    }

    @Suppress("DEPRECATION")
    private fun vibrateQuick(ms: Long) {
        try {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else v.vibrate(ms)
        } catch (_: Throwable) {}
    }
}
