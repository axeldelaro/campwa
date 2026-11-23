package com.demonpet.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import com.demonpet.app.ai.WitchSpriteEngine
import com.demonpet.app.ai.WitchSpriteEngine.AttackKind
import com.demonpet.app.config.DemonConfigSnapshot
import com.demonpet.app.config.DemonSettings
import com.demonpet.app.mdm.DemonMdmManager
import com.demonpet.app.media.MediaFolderHelper
import com.demonpet.app.rpg.WitchAiDialogueEngine
import com.demonpet.app.rpg.WitchAiDialogueEngine.EventKind
import com.demonpet.app.rpg.WitchCatalog
import com.demonpet.app.rpg.WitchCombatAi
import com.demonpet.app.rpg.WitchCombatAi.InquisitionLaw
import com.demonpet.app.rpg.WitchCombatAi.SpecialPhoneSpell
import com.demonpet.app.rpg.WitchCombatSystem
import com.demonpet.app.rpg.WitchCombatSystem.BattleWeather
import com.demonpet.app.rpg.WitchCombatSystem.ElementalSpirit
import com.demonpet.app.rpg.WitchLoreAndCampaign
import com.demonpet.app.rpg.WitchRpgState
import com.demonpet.app.rpg.WitchStoryEngine
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * DemonMom RPG v8.0 — Deep Tactical 50-Witch Battle Engine, 8-Archetype AI Logic, Posture/Break,
 * Real Restrictions (Inquisition Laws), Concrete Phone Attacks & Unskippable Forced Folder-Video Trance!
 * - Posture/Stagger Gauge (Break = 2 turns stunned + x2.0 crit damage; basic spam barely dents armor & restores Posture!).
 * - Every Witch archetype runs its own stateful strategy ([WitchCombatAi]): Silk Threads, Chrono Hourglass,
 *   Inquisition Laws (real penalties if violated!), Siphon Self-Heal, Heal Inversion, SP Leech, Relic Confiscation.
 * - Unskippable Forced Video Trance (8–12s): plays a real video from the user's folder (or photo montage fallback)
 *   fullscreen while the Witch visibly acts on the phone in the background (brightness, audio, torch, Shizuku app hostage!).
 * - 100% Ejection-Proof & Crash-Free 5-Step End Scene!
 */
class WitchRpgBattleOverlay(
    private val context: Context,
    private val settings: DemonSettings,
    private val onMountFullscreen: (rootView: View) -> Unit,
    private val onDismissFullscreen: () -> Unit,
    private val onApplyVisualDrain: (drainPct: Int, cfg: DemonConfigSnapshot) -> Unit,
    private val onClearVisualDrain: () -> Unit,
    private val onSayPet: (msg: String) -> Unit,
    private val onSpawnTrappedAltar: (cfg: DemonConfigSnapshot) -> Unit,
    private val onVibrate: (ms: Long) -> Unit,
    private val onSecretEmergencyExit: () -> Unit,
    private val onBattleFinished: (() -> Unit)? = null
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val rpgState = WitchRpgState(context)
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var activeWitch: WitchSpriteEngine.LoadedWitch? = null
    private var secondWitch: WitchSpriteEngine.LoadedWitch? = null
    private var activeStageRef: WitchSpriteStageView? = null
    private var activeVideoView: VideoView? = null
    private var typewriterRunnable: Runnable? = null
    private var qteTickRunnable: Runnable? = null
    private var pendingTurnRunnable: Runnable? = null
    private var riftRemoveRunnable: Runnable? = null
    private var videoTickRunnable: Runnable? = null
    private var turnTimerRunnable: Runnable? = null

    @Volatile
    private var sessionActive = false

    fun isBattleActive(): Boolean = sessionActive

    fun launchBattle(cfg: DemonConfigSnapshot) {
        val def = WitchCatalog.randomWitchForChapter(rpgState.selectedChapter, includeBoss = false)
        launchBattleForWitch(cfg, def, towerFloor = 0)
    }

    fun launchTowerRun(cfg: DemonConfigSnapshot, floor: Int = 1, carryHp: Int = -1, carryMp: Int = -1, carrySp: Int = 25, carryEmbrace: Int = 0) {
        val targetChapter = (((floor - 1) / 2) + 1).coerceIn(1, 5)
        val isBossFloor = floor % 3 == 0
        val def = if (isBossFloor) WitchCatalog.bossOfChapter(targetChapter) else WitchCatalog.randomWitchForChapter(targetChapter, includeBoss = false)
        launchBattleForWitch(cfg, def, towerFloor = floor, carryHp = carryHp, carryMp = carryMp, carrySp = carrySp, carryEmbrace = carryEmbrace)
    }

    fun launchBattleForWitch(
        cfg: DemonConfigSnapshot,
        witchDef: WitchCatalog.WitchDef,
        towerFloor: Int = 0,
        carryHp: Int = -1,
        carryMp: Int = -1,
        carrySp: Int = 25,
        carryEmbrace: Int = 0,
        battleMods: WitchStoryEngine.BattleModifiers = WitchStoryEngine.BattleModifiers(),
        storyMilestoneIndex: Int = -1,
        onExpeditionVictory: ((hp: Int, mp: Int, sp: Int, embrace: Int) -> Unit)? = null
    ) {
        if (sessionActive) return
        sessionActive = true
        rpgState.markEncountered(witchDef.id)

        Thread {
            try {
                val witch1 = WitchSpriteEngine.loadWitchForDef(context, cfg, witchDef)
                val allowDuo = (witchDef.isBoss && witchDef.chapter >= 2) || cfg.chaosLevel >= 8 || towerFloor >= 4
                val witch2 = if (allowDuo) {
                    val allyDef = WitchCatalog.witchesInChapter(witchDef.chapter)
                        .filter { it.id != witchDef.id }
                        .randomOrNull() ?: WitchCatalog.ALL_WITCHES.random()
                    WitchSpriteEngine.loadWitchForDef(context, cfg, allyDef, excludePath = witch1.sourceFilePath)
                } else null
                val threatenedAppName = DemonMdmManager.getNextMostUsedAppLabel(context)

                mainHandler.post {
                    if (!sessionActive) return@post
                    activeWitch = witch1
                    secondWitch = witch2
                    mountBattleUi(witch1, witch2, cfg, towerFloor, carryHp, carryMp, carrySp, carryEmbrace, battleMods, storyMilestoneIndex, threatenedAppName, onExpeditionVictory)
                }
            } catch (_: Throwable) {
                sessionActive = false
            }
        }.start()
    }

    fun forceCleanup() {
        sessionActive = false
        cancelAllCallbacks()
        try { activeVideoView?.stopPlayback() } catch (_: Throwable) {}
        activeVideoView = null
        try { activeStageRef?.clearBitmaps() } catch (_: Throwable) {}
        activeStageRef = null
        activeWitch = null
        secondWitch = null
    }

    private fun cancelAllCallbacks() {
        typewriterRunnable?.let { mainHandler.removeCallbacks(it) }; typewriterRunnable = null
        qteTickRunnable?.let { mainHandler.removeCallbacks(it) }; qteTickRunnable = null
        pendingTurnRunnable?.let { mainHandler.removeCallbacks(it) }; pendingTurnRunnable = null
        riftRemoveRunnable?.let { mainHandler.removeCallbacks(it) }; riftRemoveRunnable = null
        videoTickRunnable?.let { mainHandler.removeCallbacks(it) }; videoTickRunnable = null
        turnTimerRunnable?.let { mainHandler.removeCallbacks(it) }; turnTimerRunnable = null
    }

    private fun obedienceRankFor(level: Int): String = when {
        level >= 25 -> "Rebelle"
        level >= 15 -> "Apprenti"
        level >= 8 -> "Captif"
        level >= 3 -> "Serviteur"
        else -> "Soumis Absolu"
    }

    private fun dominanceTierFor(level: Int): Int = when {
        level >= 20 -> 0
        level >= 12 -> 1
        level >= 6 -> 2
        else -> 3
    }

    private fun mountBattleUi(
        initialWitch: WitchSpriteEngine.LoadedWitch,
        reinforcement: WitchSpriteEngine.LoadedWitch?,
        cfg: DemonConfigSnapshot,
        towerFloor: Int,
        carryHp: Int,
        carryMp: Int,
        carrySp: Int,
        carryEmbrace: Int,
        battleMods: WitchStoryEngine.BattleModifiers,
        storyMilestoneIndex: Int,
        initialThreatApp: String?,
        onExpeditionVictory: ((hp: Int, mp: Int, sp: Int, embrace: Int) -> Unit)? = null
    ) {
        var curWitch = initialWitch
        var duoSpawned = false
        val snap0 = settings.snapshot()
        val rSnap0 = rpgState.snapshot()
        val apiKey = rSnap0.geminiApiKey
        val allowTouchSabotage = rSnap0.touchSabotageEnabled
        val allowMidAppPowers = rSnap0.midBattleAppPowers
        val sfxOn = rSnap0.combatSfxEnabled
        val titlePostureBonus = rSnap0.titlePostureBonus
        val spiritPowerMult = rSnap0.spiritPowerMult
        val spiritMpDiscount = rSnap0.spiritMpDiscount
        val demonAssistChance = rSnap0.demonAssistChance
        val demonFormBadge = rSnap0.demonForm.badge
        val unlockedSpirits = rSnap0.unlockedSpirits

        // Player Gauges scaled by Soul Forge + Titles
        val maxHp = rSnap0.maxHp
        val maxMp = rSnap0.maxMp
        val forgeAtkMul = rSnap0.attackMultiplier
        val forgeEmbraceResist = rSnap0.embraceResistMult
        val forgeSpBonus = rSnap0.spBonusPerAction
        val wardBonus = rSnap0.wardRank

        var playerLv = snap0.playerLevel
        var hp = if (carryHp > 0) carryHp.coerceIn(35, maxHp) else maxHp
        var mp = if (carryMp >= 0) carryMp.coerceIn(15, maxMp) else maxMp
        var sp = (if (battleMods.startSp != 25) battleMods.startSp else carrySp).coerceIn(0, 100)
        var embrace = (carryEmbrace + battleMods.startEmbrace).coerceIn(0, 82)

        // Witch Resolve + POSTURE / STAGGER BAR + MGQ v10.0 Mechanics!
        val towerScale = if (towerFloor > 1) 1f + (towerFloor - 1) * 0.14f else 1f
        var witchMaxResolve = (curWitch.profile.maxResolve * towerScale).toInt().coerceAtLeast(280)
        var witchResolve = (witchMaxResolve - battleMods.surpriseBossDmg).coerceAtLeast(100)
        var witchMaxPosture = when {
            curWitch.def.isSuperboss -> 220
            curWitch.def.isBoss -> 175
            curWitch.def.isMiniBoss -> 140
            else -> 110
        }
        var witchPosture = witchMaxPosture
        var bossPhase = 1
        var witchStunTurns = 0
        var witchShield = false
        var witchMirage = false
        var witchThornCounter = false
        var witchBurnTurns = 0
        var dominationAura = false

        // MGQ 4 Elemental Spirits, Weather, Adaptive Anti-Spam & 2-Stage Capture!
        var activeSpirit = ElementalSpirit.NONE
        var battleWeather = BattleWeather.CLEAR
        val adaptiveBrain = WitchCombatSystem.AdaptiveBrain()
        var focusCharged = false
        var playerRegenTurns = 0
        var captureStage = if (battleMods.startCaptured) 1 else 0

        // Live Bounty Trackers
        var relicsUsedThisFight = 0
        var parriesThisFight = 0
        var breaksThisFight = 0
        var qteWinsThisFight = 0
        var usedSpiritThisFight = false
        var refusedPactThisFight = false

        // 8-Archetype Stateful Mechanics & Real Restrictions!
        var silkThreads = 0                                   // Binder: 0..3 -> Auto-Capture!
        var chronoHourglass = 4                               // Chrono: 4..0 -> Time Stop Double Turn!
        var activeLaw = InquisitionLaw.NONE                   // Inquisitor/Boss: Real command restriction!
        var lawTurns = 0
        var healInvertTurns = 0                               // Heal Inversion curse (halves Elixirs!)
        var confiscatedRelic: Pair<String, String>? = null    // Stolen relic returned on Victory!
        var videoTranceCount = 0
        var inVideoTrance = false
        var pactOfferedThisBattle = false

        // Player Stances, Buffs & Statuses
        var captured = battleMods.startCaptured
        var guarding = false
        var counterStance = false
        var meditateImmunity = false
        var aegisActive = battleMods.startAegis
        var furyTurns = if (battleMods.startFury) 3 else 0
        var runicStormActive = false
        var adrenalineUsed = false

        var charmedTurns = 0
        var hypnoTurns = 0
        var sealedTurns = 0
        var siphonTurns = 0

        // Touch Sabotage & Live App Hostages
        var fogBlindTurns = 0
        var scrambleButtonsThisTurn = false
        var dodgeButtonThisTurn = false
        var frostButtonTaps = 0
        var mimicTrapThisTurn = false
        val hostageApps = ArrayList<Pair<String, String>>()
        var midBattleHostageCount = 0

        // Anti-Spam & Turn Pressure
        var lastActionKey = ""
        var repeatCount = 0
        var comboMult = 1.0f
        var turnNumber = 1
        var spamPenaltyCount = 0
        var missedUltimateGuards = 0
        var qteFails = 0
        var lawViolations = 0
        var drainPct = snap0.activeDrainPct
        var inEndScene = false
        var turnLocked = false
        var dialogueEpoch = 0
        var qteVarietyCounter = 0

        fun buildAiIntent(): WitchCombatAi.PlannedIntent = WitchCombatAi.chooseTacticalIntent(
            WitchCombatAi.CombatSnapshot(
                witchDef = curWitch.def,
                profile = curWitch.profile,
                turnNumber = turnNumber,
                bossPhase = bossPhase,
                witchShield = witchShield,
                witchMirage = witchMirage,
                silkThreads = silkThreads,
                chronoHourglass = chronoHourglass,
                playerHpRatio = hp.toFloat() / maxHp,
                playerMp = mp,
                playerSp = sp,
                playerLevel = playerLv,
                playerCaptured = captured,
                playerCharmed = charmedTurns > 0,
                playerHypno = hypnoTurns > 0,
                playerSiphon = siphonTurns > 0,
                activeLaw = activeLaw,
                videoTranceCount = videoTranceCount
            )
        )

        var plannedIntent: WitchCombatAi.PlannedIntent = buildAiIntent()

        val root = FrameLayout(context).apply { setBackgroundColor(Color.rgb(10, 6, 16)) }

        // Layer 1: Fullscreen 60 FPS Live2D-lite Stage
        val stage = WitchSpriteStageView(context).apply {
            setWitchBitmaps(curWitch.spriteBitmap, curWitch.backdropBitmap)
            hpRatio = hp.toFloat() / maxHp
            drainIntensity = drainPct / 100f
        }
        activeStageRef = stage
        root.addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Layer 1.5: Floating Golden Eclipse Rift Container
        val riftHost = FrameLayout(context)
        root.addView(riftHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Layer 4 (Topmost): Unskippable Forced Video Trance Container!
        val videoTranceHost = FrameLayout(context).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
        }

        fun closeBattleAndDismiss() {
            sessionActive = false
            cancelAllCallbacks()
            try { activeVideoView?.stopPlayback() } catch (_: Throwable) {}
            activeVideoView = null
            try { videoTranceHost.removeAllViews(); videoTranceHost.visibility = View.GONE } catch (_: Throwable) {}
            try { riftHost.removeAllViews() } catch (_: Throwable) {}
            try { stage.clearBitmaps() } catch (_: Throwable) {}
            Thread { try { DemonMdmManager.setTaskPinningViaShizuku(0, false) } catch (_: Throwable) {} }.start()
            try { onDismissFullscreen() } catch (_: Throwable) {}
            activeStageRef = null
            activeWitch = null
            secondWitch = null
        }

        fun closeBattleAndNotify() {
            closeBattleAndDismiss()
            try { onBattleFinished?.invoke() } catch (_: Throwable) {}
        }

        // Layer 2: Rich Tactical Top HUD (20-tap on NV bar = secret unlock 100205!)
        val topHud = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(32), dp(10), dp(7))
            background = GradientDrawable().apply {
                setColor(Color.argb(210, 14, 8, 26))
                setStroke(dp(1), Color.argb(145, 217, 70, 239))
            }
        }
        val bossTitleTv = TextView(context).apply {
            setTextColor(Color.rgb(245, 208, 254)); textSize = 12.6f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        var emergTaps = 0
        var emergTapMs = 0L
        val playerStatsTv = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 10.6f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))
            setOnClickListener {
                val now = System.currentTimeMillis()
                emergTaps = if (now - emergTapMs <= 1600L) emergTaps + 1 else 1
                emergTapMs = now
                if (emergTaps >= DemonSettings.REQUIRED_EXIT_TAPS) {
                    emergTaps = 0
                    closeBattleAndDismiss()
                    onSecretEmergencyExit()
                }
            }
        }
        val bossBarTv = TextView(context).apply {
            setTextColor(Color.rgb(253, 164, 175)); textSize = 10.5f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
        }
        val restrictionBannerTv = TextView(context).apply {
            setTextColor(Color.rgb(254, 215, 170)); textSize = 10.6f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.argb(190, 154, 52, 18)) }
            setPadding(dp(8), dp(3), dp(8), dp(3))
            visibility = View.GONE
        }
        val hostageBannerTv = TextView(context).apply {
            setTextColor(Color.rgb(254, 202, 202)); textSize = 10.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.argb(185, 159, 18, 57)) }
            setPadding(dp(8), dp(3), dp(8), dp(3))
            visibility = View.GONE
        }
        val intentBannerTv = TextView(context).apply {
            setTextColor(Color.rgb(253, 224, 71)); textSize = 10.9f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(dp(4), dp(3), dp(4), dp(1))
        }
        topHud.addView(bossTitleTv)
        topHud.addView(playerStatsTv)
        topHud.addView(bossBarTv)
        topHud.addView(restrictionBannerTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        topHud.addView(hostageBannerTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        topHud.addView(intentBannerTv)
        root.addView(topHud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Layer 3: Bottom Visual Novel AI Dialogue Box + Sabotaged Tactical Controls
        val bottomPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(9), dp(11), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.argb(238, 15, 9, 26))
                setStroke(dp(2), Color.argb(205, 217, 70, 239))
            }
        }
        val speakerTv = TextView(context).apply {
            setTextColor(Color.rgb(244, 114, 182)); textSize = 12.3f; typeface = Typeface.DEFAULT_BOLD
        }
        val dialogueTv = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 12.2f; minLines = 3; setPadding(0, dp(3), 0, dp(7))
        }
        val actionsBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        bottomPanel.addView(speakerTv)
        bottomPanel.addView(dialogueTv)
        bottomPanel.addView(actionsBox)

        root.addView(
            bottomPanel,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                marginStart = dp(9); marginEnd = dp(9); bottomMargin = dp(9)
            }
        )

        // Mount Video Trance Layer ON TOP of everything!
        root.addView(videoTranceHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        fun typeDialogue(speaker: String, fullText: String) {
            if (!sessionActive) return
            speakerTv.text = speaker
            typewriterRunnable?.let { mainHandler.removeCallbacks(it) }
            var idx = 0
            val run = object : Runnable {
                override fun run() {
                    if (!sessionActive) return
                    idx = (idx + 5).coerceAtMost(fullText.length)
                    dialogueTv.text = fullText.substring(0, idx)
                    if (idx < fullText.length) mainHandler.postDelayed(this, 16L)
                }
            }
            typewriterRunnable = run
            mainHandler.post(run)
        }

        fun speakAi(speaker: String, event: EventKind, skillName: String = "", extraFooter: String = "", affectedApp: String? = null) {
            dialogueEpoch++
            val myEpoch = dialogueEpoch
            val instant = WitchAiDialogueEngine.generateInstantLine(curWitch.def, event, skillName, playerLv, embrace, affectedApp)
            val fullLocal = if (extraFooter.isNotBlank()) "$instant\n$extraFooter" else instant
            typeDialogue(speaker, fullLocal)
            if (apiKey.isNotBlank()) {
                WitchAiDialogueEngine.generateLiveAiLineIfConfigured(apiKey, curWitch.def, event, skillName, playerLv, embrace) { aiLine ->
                    mainHandler.post {
                        if (sessionActive && dialogueEpoch == myEpoch) {
                            typewriterRunnable?.let { mainHandler.removeCallbacks(it) }
                            speakerTv.text = "$speaker • ✨ IA"
                            dialogueTv.text = if (extraFooter.isNotBlank()) "$aiLine\n$extraFooter" else aiLine
                        }
                    }
                }
            }
        }

        fun buildStatusSummary(): String {
            val parts = ArrayList<String>()
            if (activeSpirit != ElementalSpirit.NONE) parts.add(activeSpirit.shortBadge)
            if (focusCharged) parts.add("🎯Focus×2")
            if (playerRegenTurns > 0) parts.add("💚Régén($playerRegenTurns)")
            if (charmedTurns > 0) parts.add("💖Charmé($charmedTurns)")
            if (hypnoTurns > 0) parts.add("🌀Hypno($hypnoTurns)")
            if (sealedTurns > 0) parts.add("🔒Scellé($sealedTurns)")
            if (siphonTurns > 0) parts.add("🩸Siphon($siphonTurns)")
            if (healInvertTurns > 0) parts.add("🧪InvSoin($healInvertTurns)")
            if (furyTurns > 0) parts.add("🕯️Furie($furyTurns)")
            if (aegisActive) parts.add("🛡️Égide")
            if (runicStormActive) parts.add("🌩️Orage")
            return if (parts.isEmpty()) "" else " • " + parts.joinToString(" ")
        }

        fun refreshHud() {
            if (!sessionActive) return
            val domTier = dominanceTierFor(playerLv)
            val towerTag = if (towerFloor > 0) "🗼Étage $towerFloor • " else ""
            val privTag = when {
                DemonMdmManager.isShizukuActive() -> " • ⚡SHIZUKU"
                DemonMdmManager.isDeviceOwner(context) -> " • 👑MDM"
                else -> ""
            }
            val phaseStr = if (bossPhase > 1) " [P$bossPhase]" else ""
            val weatherTag = if (battleWeather != BattleWeather.CLEAR) " • ${battleWeather.shortBadge}" else ""
            bossTitleTv.text = "$towerTag#${curWitch.def.id} ${curWitch.profile.name}$phaseStr (${curWitch.def.archetype.label})$weatherTag$privTag"

            val comboTag = if (adaptiveBrain.chainMultiplier > 1.05f) String.format(" • 🔥×%.1f", adaptiveBrain.chainMultiplier) else ""
            val domTag = if (domTier > 0) " • 😈Dom.$domTier/3" else ""
            playerStatsTv.text = "NV $playerLv (${obedienceRankFor(playerLv)})$domTag | PV $hp/$maxHp | PM $mp/$maxMp | SP $sp$comboTag${buildStatusSummary()}"

            val bossPct = ((witchResolve * 100f) / witchMaxResolve.coerceAtLeast(1)).toInt().coerceIn(0, 100)
            val wTags = ArrayList<String>()
            if (witchStunTurns > 0) wTags.add("💥RUPTURE($witchStunTurns t)")
            else wTags.add("🛡️Posture $witchPosture/$witchMaxPosture")
            if (witchShield) wTags.add("🔷BARRIÈRE")
            if (witchMirage) wTags.add("🪞MIRAGE")
            if (witchThornCounter) wTags.add("🌹RIPOSTE")
            if (witchBurnTurns > 0) wTags.add("🔥Brûlé($witchBurnTurns)")
            if (curWitch.def.archetype == WitchCatalog.Archetype.BINDER) wTags.add("🕸️Fils $silkThreads/3")
            if (curWitch.def.archetype == WitchCatalog.Archetype.CHRONO) wTags.add("⏳Sablier $chronoHourglass")
            adaptiveBrain.highestAdaptationTag()?.let { wTags.add(it) }
            bossBarTv.text = "T$turnNumber | Boss $witchResolve/$witchMaxResolve ($bossPct%) | Emprise $embrace% | ${wTags.joinToString(" ")}"

            // Active Restriction, Weather & Confiscated Relic Banner
            val restList = ArrayList<String>()
            if (activeLaw != InquisitionLaw.NONE && lawTurns > 0) restList.add("${activeLaw.label} (${lawTurns}t)")
            if (battleWeather != BattleWeather.CLEAR) restList.add("${battleWeather.label}: ${battleWeather.effectSummary}")
            confiscatedRelic?.let { restList.add("🎒 Confisqué : ${it.second}") }
            if (restList.isNotEmpty()) {
                restrictionBannerTv.text = restList.joinToString(" • ")
                restrictionBannerTv.visibility = View.VISIBLE
            } else {
                restrictionBannerTv.visibility = View.GONE
            }

            // Live App Hostage Banner
            if (hostageApps.isNotEmpty()) {
                val names = hostageApps.joinToString(", ") { it.second }
                hostageBannerTv.text = "🔒 OTAGE(S) EN COURS : « $names » grisée(s) ! Gagne pour libérer !"
                hostageBannerTv.visibility = View.VISIBLE
            } else {
                hostageBannerTv.visibility = View.GONE
            }

            stage.hpRatio = (hp.toFloat() / maxHp).coerceIn(0f, 1f)
            stage.drainIntensity = (drainPct / 100f).coerceAtLeast(if (captured) 0.48f else 0f)
            stage.shieldActive = witchShield
            stage.mirrorCloneActive = witchMirage
            stage.enrageAuraActive = bossPhase >= 2
            stage.hypnoticSpiralActive = hypnoTurns > 0 || charmedTurns > 0 || fogBlindTurns > 0
            stage.captureZoomFactor = when {
                inEndScene -> 1.0f
                captured -> 0.68f
                embrace >= 65 -> 0.35f
                else -> 0f
            }

            val phoneTag = if (plannedIntent.phoneSpell != SpecialPhoneSpell.NONE) " + ${plannedIntent.phoneSpell.label}" else ""
            intentBannerTv.text = when {
                inEndScene -> "💋 SCÈNE FINALE — EMPRISE TOTALE & MALÉDICTION"
                captured -> "⛓️ CAPTURÉ (Phase 2) ! Réussis l'épreuve d'évasion ou utilise un 📜 Talisman !"
                witchStunTurns > 0 -> "💥 RUPTURE DE POSTURE ! ${curWitch.def.name} encaisse ×2.0 DÉGÂTS (${witchStunTurns}t) !"
                fogBlindTurns > 0 -> "🌫️ BROUILLARD D'INCERTITUDE : Intention cachée (???) — Utilise 🧘 Méditation !"
                else -> "🎯 ${plannedIntent.telegraphLine}$phoneTag"
            }
            intentBannerTv.setTextColor(
                when {
                    fogBlindTurns > 0 && !inEndScene -> Color.rgb(216, 180, 254)
                    plannedIntent.attack.kind == AttackKind.ULTIMATE && !inEndScene && witchStunTurns == 0 -> Color.rgb(252, 165, 165)
                    plannedIntent.phoneSpell == SpecialPhoneSpell.FORCED_VIDEO_TRANCE && !inEndScene -> Color.rgb(244, 114, 182)
                    witchStunTurns > 0 -> Color.rgb(134, 239, 172)
                    else -> Color.rgb(253, 224, 71)
                }
            )
        }

        // ==================== POSTURE DAMAGE & BREAK (STAGGER) SYSTEM ====================
        fun applyPostureDamage(rawAmount: Int): Boolean {
            if (witchStunTurns > 0 || rawAmount <= 0) return false
            val gaiaMul = if (activeSpirit == ElementalSpirit.GAIA) 1.40f else 1.0f
            val gravMul = if (battleWeather == BattleWeather.HEAVY_GRAVITY) 1.35f else 1.0f
            val focusMul = if (focusCharged) 1.55f else 1.0f
            val amount = ((rawAmount + titlePostureBonus) * gaiaMul * gravMul * focusMul).toInt().coerceAtLeast(1)
            witchPosture = (witchPosture - amount).coerceAtLeast(0)
            if (witchPosture <= 0) {
                breaksThisFight++
                witchStunTurns = 2
                witchShield = false
                witchMirage = false
                witchThornCounter = false
                activeLaw = InquisitionLaw.NONE
                lawTurns = 0
                if (curWitch.def.archetype == WitchCatalog.Archetype.BINDER) silkThreads = 0
                if (curWitch.def.archetype == WitchCatalog.Archetype.CHRONO) chronoHourglass = (chronoHourglass + 2).coerceAtMost(4)
                stage.triggerPerfectParryFlash()
                WitchCombatSystem.SoundFx.play(sfxOn, "break")
                onVibrate(80L)
                return true
            }
            return false
        }

        // ==================== LIVE MID-BATTLE SHIZUKU APP HOSTAGE SEIZURE ====================
        fun triggerMidBattleAppHostage(reasonPrefix: String, onSeizedLabel: ((String) -> Unit)? = null) {
            if (!allowMidAppPowers || midBattleHostageCount >= 2 || !DemonMdmManager.hasPrivilegedControl(context)) return
            midBattleHostageCount++
            Thread {
                try {
                    val seized = DemonMdmManager.seizeAppHostageMidBattle(context)
                    if (seized != null) {
                        mainHandler.post {
                            if (!sessionActive) return@post
                            hostageApps.add(seized)
                            onVibrate(120L)
                            stage.triggerWitchAttackLunge(intense = true)
                            refreshHud()
                            onSeizedLabel?.invoke(seized.second)
                            dialogueTv.text = "${dialogueTv.text}\n$reasonPrefix « ${seized.second} » grisée en OTAGE ! 🔒😈"
                        }
                    } else {
                        midBattleHostageCount--
                    }
                } catch (_: Throwable) {
                    midBattleHostageCount--
                }
            }.start()
        }

        // ==================== UNSKIPPABLE FORCED VIDEO TRANCE (WITCH ACTS IN BACKGROUND!) ====================
        fun launchForcedVideoTrance(durationSec: Int, onVideoDone: () -> Unit) {
            if (!sessionActive || inEndScene || inVideoTrance) {
                onVideoDone()
                return
            }
            inVideoTrance = true
            turnLocked = true
            videoTranceCount++
            turnTimerRunnable?.let { mainHandler.removeCallbacks(it) }

            val safeSec = durationSec.coerceIn(7, 14)
            val endElapsed = SystemClock.elapsedRealtime() + safeSec * 1000L
            videoTranceHost.removeAllViews()
            videoTranceHost.visibility = View.VISIBLE

            // Visual Layer: Real Folder Video OR Animated Hypnotic Photo Montage fallback!
            val vidSrc = MediaFolderHelper.pickRandomVideoSource(context, cfg)
            if (vidSrc != null) {
                val vv = VideoView(context).apply {
                    setOnPreparedListener { mpObj ->
                        try {
                            mpObj.isLooping = true
                            start()
                        } catch (_: Throwable) {}
                    }
                    setOnErrorListener { _, _, _ -> true }
                }
                activeVideoView = vv
                videoTranceHost.addView(vv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
                try {
                    if (vidSrc.filePath != null) vv.setVideoPath(vidSrc.filePath)
                    else if (vidSrc.contentUri != null) vv.setVideoURI(vidSrc.contentUri)
                } catch (_: Throwable) {}
            } else {
                // Graceful hypnotic photo montage fallback when folder has only photos
                val iv = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(curWitch.spriteBitmap)
                }
                videoTranceHost.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                Thread {
                    val pics = MediaFolderHelper.loadMultipleRandomBitmaps(context, cfg, count = 4, maxDimPx = 960)
                    if (pics.isNotEmpty()) {
                        var pIdx = 0
                        val swap = object : Runnable {
                            override fun run() {
                                if (!sessionActive || !inVideoTrance) return
                                iv.setImageBitmap(pics[pIdx % pics.size])
                                iv.scaleX = 1.12f; iv.scaleY = 1.12f
                                iv.animate().scaleX(1.0f).scaleY(1.0f).setDuration(1_800L).start()
                                pIdx++
                                mainHandler.postDelayed(this, 2_000L)
                            }
                        }
                        mainHandler.post(swap)
                    }
                }.start()
            }

            // Semi-transparent Top Unskippable Banner (20 taps emergency still works!)
            var vidEmerg = 0; var vidEmergMs = 0L
            val topBar = TextView(context).apply {
                text = "🎬 TRANSE VIDÉO FORCÉE — ${safeSec}s (Impossible de quitter !)"
                setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setPadding(dp(12), dp(36), dp(12), dp(12))
                setBackgroundColor(Color.argb(195, 134, 25, 143))
                setOnClickListener {
                    val now = System.currentTimeMillis()
                    vidEmerg = if (now - vidEmergMs <= 1600L) vidEmerg + 1 else 1
                    vidEmergMs = now
                    if (vidEmerg >= DemonSettings.REQUIRED_EXIT_TAPS) {
                        vidEmerg = 0
                        closeBattleAndDismiss()
                        onSecretEmergencyExit()
                    }
                }
            }
            videoTranceHost.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

            // Bottom Live Background Witch Actions Ticker!
            val bgLogTv = TextView(context).apply {
                text = "😈 ${curWitch.def.name} prend le contrôle pendant que tu regardes…"
                setTextColor(Color.rgb(254, 240, 138)); textSize = 12.2f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setPadding(dp(14), dp(12), dp(14), dp(24))
                setBackgroundColor(Color.argb(215, 18, 8, 30))
            }
            videoTranceHost.addView(bgLogTv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

            // Concrete Background Actions scheduled during the unskippable video!
            val bgNotes = ArrayList<String>()
            var stage1Done = false; var stage2Done = false; var stage3Done = false

            val tick = object : Runnable {
                override fun run() {
                    if (!sessionActive || !inVideoTrance) return
                    val remMs = endElapsed - SystemClock.elapsedRealtime()
                    val elapsedSec = safeSec - ((remMs + 999L) / 1000L).toInt()

                    if (!stage1Done && elapsedSec >= 2) {
                        stage1Done = true
                        onVibrate(80L)
                        DemonMdmManager.triggerWitchTorchPulse(context, pulses = 2)
                        Thread {
                            DemonMdmManager.applyWitchBrightnessCurse(context, 38)
                            DemonMdmManager.applyWitchAudioCurse(context, 0.35f)
                        }.start()
                        bgNotes.add("🌙 Luminosité abaissée + flash torche !")
                        bgLogTv.text = "😈 ARRIÈRE-PLAN : ${curWitch.def.name} baisse ta luminosité & active la torche !"
                    }
                    if (!stage2Done && elapsedSec >= 4) {
                        stage2Done = true
                        onVibrate(100L)
                        if (allowMidAppPowers && DemonMdmManager.hasPrivilegedControl(context)) {
                            bgLogTv.text = "🔒 ARRIÈRE-PLAN : ${curWitch.def.name} fouille tes applis via Shizuku…"
                            triggerMidBattleAppHostage("🎬 Pendant la vidéo :") { seizedName ->
                                bgNotes.add("🔒 « $seizedName » grisée en OTAGE !")
                                bgLogTv.text = "🔒 ARRIÈRE-PLAN : « $seizedName » vient d'être GRISÉE pendant la vidéo ! 😈"
                            }
                        } else {
                            bgNotes.add("🌀 Hypnose profonde posée !")
                            bgLogTv.text = "🌀 ARRIÈRE-PLAN : ${curWitch.def.name} brouille tes commandes tactiles !"
                        }
                    }
                    if (!stage3Done && elapsedSec >= 6) {
                        stage3Done = true
                        mp = (mp - 12).coerceAtLeast(0)
                        embrace = (embrace + 14).coerceAtMost(96)
                        witchPosture = (witchPosture + 30).coerceAtMost(witchMaxPosture)
                        scrambleButtonsThisTurn = true
                        fogBlindTurns = 1
                        bgNotes.add("💜 -12 PM • +14 % Emprise • Posture Boss +30 • Commandes mélangées !")
                        bgLogTv.text = "💜 ARRIÈRE-PLAN : -12 PM • +14 % Emprise • Sa Posture remonte & tes touches sont mélangées !"
                    }

                    if (remMs <= 0L) {
                        inVideoTrance = false
                        videoTickRunnable = null
                        try { activeVideoView?.stopPlayback() } catch (_: Throwable) {}
                        activeVideoView = null
                        videoTranceHost.removeAllViews()
                        videoTranceHost.visibility = View.GONE
                        typeDialogue(
                            "🎬 Fin de la Transe Vidéo !",
                            "Pendant que tu ne pouvais pas quitter la vidéo, ${curWitch.def.name} a agi en arrière-plan :\n${bgNotes.joinToString("\n")}"
                        )
                        refreshHud()
                        onVideoDone()
                    } else {
                        val remSec = ((remMs + 999L) / 1000L).toInt()
                        topBar.text = "🎬 TRANSE VIDÉO FORCÉE — ${remSec}s (Impossible de quitter !)"
                        mainHandler.postDelayed(this, 250L)
                    }
                }
            }
            videoTickRunnable = tick
            mainHandler.post(tick)
        }

        // ==================== FLOATING GOLDEN ECLIPSE RIFT ====================
        fun spawnFloatingGoldenRift() {
            if (!sessionActive || inEndScene || inVideoTrance) return
            riftRemoveRunnable?.let { mainHandler.removeCallbacks(it) }
            riftHost.removeAllViews()
            val orbBtn = Button(context).apply {
                text = "✨ FAILLE D'ÉCLIPSE !\n+35 SP / -25 Posture Boss"
                isAllCaps = false; textSize = 11.2f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dp(24).toFloat(); setColor(Color.argb(235, 217, 119, 6)); setStroke(dp(2), Color.rgb(254, 240, 138))
                }
                setPadding(dp(12), dp(9), dp(12), dp(9))
                setOnClickListener {
                    riftRemoveRunnable?.let { mainHandler.removeCallbacks(it) }
                    riftHost.removeAllViews()
                    sp = (sp + 35).coerceAtMost(100)
                    embrace = (embrace - 18).coerceAtLeast(0)
                    val broke = applyPostureDamage(25)
                    onVibrate(45L); stage.triggerPerfectParryFlash()
                    typeDialogue("✨ FAILLE SAISIE !", "+35 SP • -18 % Emprise • -25 Posture Boss !${if (broke) " 💥 RUPTURE !" else ""}")
                    refreshHud()
                }
            }
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = if (Random.nextBoolean()) (Gravity.CENTER_VERTICAL or Gravity.START) else (Gravity.CENTER_VERTICAL or Gravity.END)
                marginStart = dp(16); marginEnd = dp(16)
            }
            riftHost.addView(orbBtn, lp)
            val hide = Runnable { try { riftHost.removeAllViews() } catch (_: Throwable) {} }
            riftRemoveRunnable = hide
            mainHandler.postDelayed(hide, 2_800L)
        }

        fun rollTouchSabotageForNextTurn() {
            scrambleButtonsThisTurn = false; dodgeButtonThisTurn = false; frostButtonTaps = 0; mimicTrapThisTurn = false
            if (!allowTouchSabotage || inEndScene || captured) return
            val domTier = dominanceTierFor(playerLv)
            val archBoost = if (curWitch.def.archetype == WitchCatalog.Archetype.ILLUSIONIST || curWitch.def.archetype == WitchCatalog.Archetype.CHARMER) 0.18f else 0f
            val baseChance = 0.18f + domTier * 0.22f + (if (bossPhase >= 2) 0.16f else 0f) + archBoost
            if (Random.nextFloat() < baseChance) {
                when (Random.nextInt(5)) {
                    0 -> dodgeButtonThisTurn = true
                    1 -> scrambleButtonsThisTurn = true
                    2 -> frostButtonTaps = if (domTier >= 2 || curWitch.def.archetype == WitchCatalog.Archetype.CHRONO) 3 else 2
                    3 -> mimicTrapThisTurn = true
                    else -> if (fogBlindTurns == 0) fogBlindTurns = 1
                }
            }
            if (domTier >= 3 && Random.nextFloat() < 0.55f) scrambleButtonsThisTurn = true
        }

        lateinit var renderTurnControls: () -> Unit

        // True MGQ-Grade Adaptive Anti-Spam: repeating same category builds 35%->65%->80% Resistance + Counter!
        fun evaluateActionVariety(actionKey: String, isOffensive: Boolean = true): Pair<Float, Boolean> {
            val res = adaptiveBrain.evaluate(actionKey, isOffensive)
            comboMult = adaptiveBrain.chainMultiplier
            if (res.triggeredPunish) {
                spamPenaltyCount++
                witchPosture = (witchPosture + 24).coerceAtMost(witchMaxPosture)
            }
            return res.damageMult to res.triggeredPunish
        }

        // ==================== VICTORY (RESCUES HOSTAGES + CONFISCATED RELIC + BOUNTIES + EPILOGUE!) ====================
        fun finishBattleVictory() {
            if (inEndScene) return
            inEndScene = true; turnLocked = true
            cancelAllCallbacks()
            WitchCombatSystem.SoundFx.play(sfxOn, "victory")
            try { riftHost.removeAllViews() } catch (_: Throwable) {}
            try { videoTranceHost.removeAllViews(); videoTranceHost.visibility = View.GONE } catch (_: Throwable) {}

            // Evaluate Live Tactical Bounties!
            if (relicsUsedThisFight == 0) rpgState.markBountyCompleted("b_no_relic")
            if (parriesThisFight >= 2) rpgState.markBountyCompleted("b_parry_2")
            if (breaksThisFight >= 2) rpgState.markBountyCompleted("b_break_2")
            if (usedSpiritThisFight) rpgState.markBountyCompleted("b_spirit_win")
            if (lawViolations == 0 && (curWitch.def.archetype == WitchCatalog.Archetype.INQUISITOR || curWitch.def.isBoss)) {
                rpgState.markBountyCompleted("b_law_clean")
            }
            if (refusedPactThisFight) rpgState.markBountyCompleted("b_pact_defy")
            if (qteWinsThisFight >= 2) rpgState.markBountyCompleted("b_qte_master")

            // Return any relic confiscated mid-battle!
            confiscatedRelic?.let { (key, _) -> rpgState.grantReward(0, key) }
            val returnedRelicName = confiscatedRelic?.second
            confiscatedRelic = null

            if (storyMilestoneIndex >= 0) rpgState.advanceStoryIfAt(storyMilestoneIndex)
            val towerMult = if (towerFloor > 0) 1.0f + towerFloor * 0.25f else 1.0f
            if (towerFloor > 0) rpgState.recordTowerFloor(towerFloor)
            val (levelUps, unlockedNewCh, droppedRelic) = rpgState.recordVictory(curWitch.def, bonusMultiplier = towerMult)
            val newBondLv = rpgState.getBondLevel(curWitch.def.id)
            val bio = WitchLoreAndCampaign.getBiography(curWitch.def)
            val totalLvGain = (if (curWitch.def.isSuperboss) 4 else if (curWitch.def.isBoss) 2 else 1) + levelUps
            settings.adjustPlayerStats(levelDelta = totalLvGain, hpDelta = 100, drainDelta = -100)
            settings.adjustMood(-20)
            try { onClearVisualDrain() } catch (_: Throwable) {}

            val rewardParts = ArrayList<String>().apply {
                add("+$totalLvGain NV")
                add("+${(curWitch.def.rewardXp * towerMult).toInt()} XP")
                add("+${(curWitch.def.rewardGold * towerMult * rSnap0.goldBonusMult).toInt()} 💎")
                add("♥ Lien Nv.$newBondLv/5")
                if (droppedRelic != null) add("🎁 $droppedRelic")
                if (returnedRelicName != null) add("🎒 $returnedRelicName récupéré")
                if (unlockedNewCh) add("🗺️ Chapitre ${curWitch.def.chapter + 1} DÉBLOQUÉ !")
            }
            val rewardFooter = "🏆 ${rewardParts.joinToString(" • ")}\n📖 ${bio.victoryEpilogue}"

            if (curWitch.def.id == 50 || curWitch.def.id == 55) {
                val postSnap = rpgState.snapshot()
                val ending = WitchLoreAndCampaign.evaluateFiveEndings(
                    postSnap.corruption, playerLv, postSnap.defeatedSuperbossIds.size, postSnap.totalBondLevels
                )
                typeDialogue(ending.title, "${ending.body}\n\n$rewardFooter")
            } else {
                speakAi("✨ VICTOIRE — #${curWitch.def.id} VAINCUE !", EventKind.VICTORY, extraFooter = rewardFooter)
            }

            actionsBox.removeAllViews()
            if (onExpeditionVictory != null) {
                val carryH = (hp + 25).coerceAtMost(maxHp)
                val carryM = (mp + 20).coerceAtMost(maxMp)
                val carryS = sp.coerceAtLeast(25)
                val carryE = (embrace - 25).coerceAtLeast(0)
                actionsBox.addView(makeActionBtn("🗺️ Salle suivante de l'Expédition ▶", Color.rgb(16, 185, 129)) {
                    closeBattleAndDismiss()
                    onExpeditionVictory.invoke(carryH, carryM, carryS, carryE)
                })
            } else if (towerFloor > 0) {
                val nextFloor = towerFloor + 1
                val nextHp = (hp + 35).coerceAtMost(maxHp)
                val nextMp = (mp + 28).coerceAtMost(maxMp)
                val nextSp = sp.coerceAtLeast(25)
                val nextEmb = (embrace - 30).coerceAtLeast(0)
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(makeActionBtn("🗼 Étage $nextFloor (+${((1f + nextFloor * 0.25f) * 100).toInt()}% 💎)", Color.rgb(217, 119, 6)) {
                    closeBattleAndDismiss()
                    mainHandler.postDelayed({ launchTowerRun(settings.snapshot(), nextFloor, nextHp, nextMp, nextSp, nextEmb) }, 220L)
                }, LinearLayout.LayoutParams(0, dp(42), 1.3f).apply { marginEnd = dp(4) })
                row.addView(makeActionBtn("🏆 Quitter", Color.rgb(16, 128, 96)) { closeBattleAndNotify() },
                    LinearLayout.LayoutParams(0, dp(42), 0.7f).apply { marginStart = dp(4) })
                actionsBox.addView(row)
            } else {
                actionsBox.addView(makeActionBtn("🏆 Continuer l'aventure", Color.rgb(16, 185, 129)) {
                    closeBattleAndNotify()
                    onSayPet("Victoire face à ${curWitch.def.name} ! 🏆")
                })
            }

            val hostagesToFree = ArrayList(hostageApps)
            hostageApps.clear()
            Thread {
                try {
                    DemonMdmManager.restoreBattleTempCurses(context)
                    val freedNames = ArrayList<String>()
                    for ((pkg, lbl) in hostagesToFree) {
                        if (DemonMdmManager.releaseSpecificHostageApp(context, pkg)) freedNames.add(lbl)
                    }
                    val bonusHealed = DemonMdmManager.healOneSuspendedApp(context)
                    if (bonusHealed != null && !freedNames.contains(bonusHealed)) freedNames.add(bonusHealed)
                    if (freedNames.isNotEmpty()) {
                        mainHandler.post {
                            if (sessionActive) {
                                dialogueTv.text = "${dialogueTv.text}\n🕊️ Shizuku : « ${freedNames.joinToString(", ")} » dégrisée(s) & sauvée(s) !"
                                refreshHud()
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }.start()
        }

        // ==================== PHASE 3 : 100% EJECTION-PROOF 5-STEP CINEMATIC END SCENE ====================
        fun startCinematicDrainEndScene() {
            if (inEndScene) return
            inEndScene = true; turnLocked = true
            cancelAllCallbacks()
            try { riftHost.removeAllViews() } catch (_: Throwable) {}
            try { activeVideoView?.stopPlayback(); videoTranceHost.removeAllViews(); videoTranceHost.visibility = View.GONE } catch (_: Throwable) {}

            stage.captureZoomFactor = 1.0f; stage.drainIntensity = 1.0f; stage.hypnoticSpiralActive = true
            try { onApplyVisualDrain(85, cfg) } catch (_: Throwable) {}
            try { rpgState.recordDefeat(curWitch.def) } catch (_: Throwable) {}
            rpgState.adjustCorruption(+5)
            refreshHud()

            val severe = playerLv <= 6 || cfg.chaosLevel >= 8 || curWitch.def.isBoss
            val totalLevelsLost = when {
                playerLv <= 3 -> 1
                curWitch.def.isBoss -> Random.nextInt(3, 6)
                else -> Random.nextInt(2, 4)
            }
            val targetLv = (playerLv - totalLevelsLost).coerceAtLeast(1)

            val wPath = curWitch.sourceFilePath
            val wUri = curWitch.sourceUri
            val mdmSummaryRef = AtomicReference("⏳ Application du sceau système Shizuku…")
            val mdmAffectedAppRef = AtomicReference<String?>(hostageApps.firstOrNull()?.second)
            var currentStepIndex = 0

            Thread {
                try {
                    val rep = DemonMdmManager.applyDefeatMdmDamage(context, playerLv, severe)
                    val hostageNote = if (hostageApps.isNotEmpty()) {
                        "\n🔒 Otage(s) conservée(s) : ${hostageApps.joinToString(", ") { it.second }}"
                    } else ""
                    mdmSummaryRef.set(rep.summary + hostageNote)
                    if (rep.affectedAppLabel != null) mdmAffectedAppRef.set(rep.affectedAppLabel)
                    mainHandler.post {
                        if (sessionActive && currentStepIndex == 1) {
                            speakAi("🔒 Malédiction Système Active (2/5)", EventKind.DRAIN_STAGE_2, extraFooter = mdmSummaryRef.get(), affectedApp = mdmAffectedAppRef.get())
                        }
                    }
                } catch (t: Throwable) {
                    mdmSummaryRef.set("⚠️ Sceau système : ${t.message.orEmpty()}")
                }
            }.start()

            val drainStep = object : Runnable {
                override fun run() {
                    if (!sessionActive) return
                    try {
                        if (playerLv > targetLv) {
                            playerLv--
                            settings.adjustPlayerStats(levelDelta = -1, hpDelta = 0, drainDelta = +8)
                            onVibrate(65L); stage.triggerWitchAttackLunge(intense = true); refreshHud()
                            mainHandler.postDelayed(this, 440L)
                        }
                    } catch (_: Throwable) {}
                }
            }
            mainHandler.postDelayed(drainStep, 350L)

            val totalSteps = 5
            val dynamicAdvice = buildString {
                append(curWitch.profile.mgqAdviceLine)
                append("\n📌 Stratégie ${curWitch.def.archetype.label} : ${WitchCombatAi.archetypePassiveSummary(curWitch.def.archetype)}")
                when {
                    lawViolations > 0 -> append("\n💡 Analyse : tu as violé une Loi d'Inquisition ($lawViolations×) — lis la bannière orange avant de cliquer !")
                    spamPenaltyCount > 0 -> append("\n💡 Analyse : spam détecté ($spamPenaltyCount×) — varie tes attaques pour briser sa Posture (×2.0 dégâts) !")
                    missedUltimateGuards > 0 -> append("\n💡 Analyse : pare son Ultime avec 🛡️ Garde pour une Parade Parfaite (-55 Posture & +30 SP) !")
                    qteFails > 0 -> append("\n💡 Analyse : garde un 📜 Talisman ou utilise ⚡ Contre-Sceau quand elle prépare une Capture !")
                }
            }

            fun applyDeferredTrophyWallpaper() {
                if (cfg.wallpaperPrankEnabled && (wPath != null || wUri != null)) {
                    Thread {
                        try { MediaFolderHelper.applySpecificWallpaper(context, cfg, wPath, wUri) } catch (_: Throwable) {}
                    }.start()
                }
            }

            fun showSceneStep() {
                if (!sessionActive) return
                try {
                    stage.triggerWitchAttackLunge(intense = currentStepIndex < 3)
                    onVibrate(50L)
                    when (currentStepIndex) {
                        0 -> speakAi("💋 ${curWitch.profile.name} — Emprise Totale (1/5)", EventKind.DRAIN_STAGE_1, extraFooter = "📉 -$totalLevelsLost NV • L'emprise du Coven grandit sur ton téléphone !")
                        1 -> speakAi("🔒 Malédiction Système (2/5)", EventKind.DRAIN_STAGE_2, extraFooter = mdmSummaryRef.get(), affectedApp = mdmAffectedAppRef.get())
                        2 -> if (targetLv <= 1) {
                            typeDialogue("💀 BAD END ULTIME — NIVEAU 1 (3/5)", "Ton Niveau atteint 1 (Soumis Absolu) ! Les Sorcières ont désormais les pleins pouvoirs tactiles & système sur tes prochains combats ! 👑💜")
                        } else {
                            speakAi("🌙 Transe du Coven (3/5)", EventKind.DRAIN_STAGE_3)
                        }
                        3 -> speakAi("👑 Trophée de ${curWitch.def.name} (4/5)", EventKind.DRAIN_STAGE_4, extraFooter = "🎁 Consolation : +15 💎 (va à la ⚒️ Forge d'Âme !)")
                        else -> typeDialogue("🔮 Oracle Post-Défaite MGQ (5/5)", dynamicAdvice)
                    }

                    actionsBox.removeAllViews()
                    if (currentStepIndex < totalSteps - 1) {
                        actionsBox.addView(makeActionBtn("Suite ▶ (${currentStepIndex + 1}/$totalSteps)", Color.rgb(162, 28, 175)) {
                            currentStepIndex++; showSceneStep()
                        })
                    } else {
                        val rematchDef = curWitch.def
                        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                        row.addView(makeActionBtn("⚔️ Revanche !", Color.rgb(225, 29, 72)) {
                            settings.update { putInt("player_hp", 100) }
                            applyDeferredTrophyWallpaper(); closeBattleAndDismiss()
                            mainHandler.postDelayed({ launchBattleForWitch(settings.snapshot(), rematchDef, towerFloor = 0) }, 260L)
                        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
                        row.addView(makeActionBtn("😈 Accepter le Sort", Color.rgb(64, 24, 78)) {
                            settings.update { putInt("player_hp", 80) }; settings.adjustMood(+18)
                            applyDeferredTrophyWallpaper(); closeBattleAndNotify()
                            onSayPet("💋 Défaite face à ${curWitch.def.name} ! ${mdmAffectedAppRef.get()?.let { "$it grisée !" } ?: "Énergie drainée !"}")
                            if (cfg.unremovableAltarEnabled) { try { onSpawnTrappedAltar(cfg) } catch (_: Throwable) {} }
                        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(5) })
                        actionsBox.addView(row)
                    }
                } catch (_: Throwable) {
                    closeBattleAndNotify()
                }
            }
            showSceneStep()
        }

        // ==================== MID-BATTLE CURSED PACT (PHASE 2 DEVIL'S BARGAIN!) ====================
        fun showMidBattleCursedPact(onDone: () -> Unit) {
            if (!sessionActive || inEndScene) return
            turnLocked = false
            actionsBox.removeAllViews()
            val canHostagePact = allowMidAppPowers && DemonMdmManager.hasPrivilegedControl(context)
            typeDialogue(
                "😈 PACTE MAUDIT DE PHASE 2 — ${curWitch.def.name}",
                "« Avant ma vraie forme, un marché ! Que sacrifies-tu pour survivre ? »\nChoisis ton pacte ci-dessous :"
            )

            actionsBox.addView(makeActionBtn(
                if (canHostagePact) "🔒 Offrir 1 Appli en Otage → 💥 RUPTURE IMMÉDIATE (Posture 0 !)"
                else "🩸 Sacrifier -22 PV → 💥 RUPTURE IMMÉDIATE (Posture 0 !)",
                Color.rgb(190, 24, 93)
            ) {
                rpgState.adjustCorruption(+4)
                if (canHostagePact) {
                    triggerMidBattleAppHostage("😈 PACTE D'OTAGE ACCEPTÉ :")
                } else {
                    hp = (hp - 22).coerceAtLeast(1)
                }
                witchShield = false
                applyPostureDamage(witchMaxPosture + 50)
                stage.triggerWitchHitShake(critical = true); onVibrate(75L)
                typeDialogue("💥 PACTE D'OTAGE SCELLÉ !", "Sa Barrière & Posture explosent ! ${curWitch.def.name} est ÉTOURDIE 2 tours (×2.0 Dégâts !) 🔥")
                refreshHud(); onDone()
            })

            actionsBox.addView(makeActionBtn("💜 +25 % Emprise → 💚 100 % PV & PM + Purifie Statuts !", Color.rgb(126, 34, 206)) {
                rpgState.adjustCorruption(+5)
                embrace = (embrace + 25).coerceAtMost(96)
                hp = maxHp; mp = maxMp
                charmedTurns = 0; hypnoTurns = 0; sealedTurns = 0; siphonTurns = 0; healInvertTurns = 0
                onVibrate(55L)
                typeDialogue("💜 PACTE D'ABONDANCE !", "PV & PM restaurés à 100 % et statuts purifiés, mais son Emprise grimpe à $embrace % ! 😈")
                refreshHud(); onDone()
            }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) } })

            actionsBox.addView(makeActionBtn("🔥 REFUSER SON PACTE ! (+35 SP & -25 Posture Boss)", Color.rgb(217, 119, 6)) {
                refusedPactThisFight = true
                rpgState.adjustCorruption(-4)
                sp = (sp + 35).coerceAtMost(100)
                val broke = applyPostureDamage(25)
                stage.triggerPerfectParryFlash(); onVibrate(45L)
                typeDialogue("🔥 VOLONTÉ INDOMPTABLE !", "Tu rejettes le Pacte ! +35 SP & -25 Posture Boss${if (broke) " • 💥 RUPTURE !" else ""} !")
                refreshHud(); onDone()
            }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) } })
        }

        // ==================== CHECK BOSS PHASE SHIFTS ====================
        fun checkBossPhaseShift(): Boolean {
            val ratio = witchResolve.toFloat() / witchMaxResolve.coerceAtLeast(1)
            if (bossPhase == 1 && ratio <= 0.58f && witchResolve > 0) {
                bossPhase = 2
                witchShield = true
                witchPosture = witchMaxPosture
                if (curWitch.def.isBoss || curWitch.def.isMiniBoss) dominationAura = true
                stage.triggerWitchAttackLunge(intense = true)
                onVibrate(90L)
                DemonMdmManager.triggerWitchTorchPulse(context, pulses = 2)
                plannedIntent = buildAiIntent()
                if (curWitch.def.isBoss || battleMods.forcedAppRaid || dominanceTierFor(playerLv) >= 2) {
                    triggerMidBattleAppHostage("⚡ PHASE 2 — PLEINS POUVOIRS :")
                }
                refreshHud()
                if (!pactOfferedThisBattle) {
                    pactOfferedThisBattle = true
                    showMidBattleCursedPact {
                        rollTouchSabotageForNextTurn()
                        turnLocked = false
                        renderTurnControls()
                    }
                } else {
                    speakAi(
                        "🔥 PHASE 2 — ${curWitch.profile.name} !", EventKind.BOSS_PHASE_2,
                        extraFooter = "🔷 Barrière Runique + Posture restaurée ! Brise-la avec 🔥 Lame !"
                    )
                    rollTouchSabotageForNextTurn(); turnLocked = false; renderTurnControls()
                }
                return true
            }
            if (bossPhase == 2 && ratio <= 0.28f && witchResolve > 0 && (curWitch.def.isBoss || curWitch.def.isMiniBoss)) {
                bossPhase = 3
                witchPosture = witchMaxPosture
                DemonMdmManager.triggerWitchTorchPulse(context, pulses = 3)
                if (!duoSpawned && reinforcement != null) {
                    duoSpawned = true
                    curWitch = reinforcement
                    witchMaxResolve = (curWitch.profile.maxResolve * 0.52f).toInt().coerceAtLeast(140)
                    witchResolve = witchMaxResolve
                    rpgState.markEncountered(curWitch.def.id)
                    stage.setWitchBitmaps(curWitch.spriteBitmap, curWitch.backdropBitmap)
                    stage.triggerWitchAttackLunge(intense = true); onVibrate(105L)
                    plannedIntent = WitchCombatAi.PlannedIntent(curWitch.profile.ultimateAttack, SpecialPhoneSpell.FORCED_VIDEO_TRANCE)
                    speakAi("⚡ RENFORT DUO — ${curWitch.profile.name} !", EventKind.BOSS_PHASE_3, extraFooter = "2e Sorcière + Sort Ultime & Transe Vidéo imminents !")
                } else {
                    witchMirage = true; fogBlindTurns = 1
                    plannedIntent = WitchCombatAi.PlannedIntent(curWitch.profile.ultimateAttack, SpecialPhoneSpell.CATEGORY_HOSTAGE_SWEEP)
                    stage.triggerWitchAttackLunge(intense = true); onVibrate(95L)
                    speakAi("👑 PHASE 3 — FORME ULTIME !", EventKind.BOSS_PHASE_3, extraFooter = "🪞 Mirage + Brouillard + Ultime Souverain + Double Otage !")
                }
                refreshHud()
                rollTouchSabotageForNextTurn(); turnLocked = false; renderTurnControls()
                return true
            }
            return false
        }

        // ==================== WITCH TURN EXECUTION (STATEFUL 8-ARCHETYPE AI + CONCRETE PHONE SPELLS!) ====================
        fun executeWitchTurn(bonusMult: Float = 1f, forcedSpamCounter: Boolean = false, lawViolationNote: String? = null) {
            if (!sessionActive || inEndScene) return
            turnLocked = true
            turnTimerRunnable?.let { mainHandler.removeCallbacks(it) }
            turnNumber++

            val wasGuarding = guarding; val wasCountering = counterStance; val hadMeditateImm = meditateImmunity
            guarding = false; counterStance = false; meditateImmunity = false
            if (furyTurns > 0) furyTurns--
            if (fogBlindTurns > 0) fogBlindTurns--
            if (healInvertTurns > 0) healInvertTurns--
            if (lawTurns > 0) {
                lawTurns--
                if (lawTurns == 0) activeLaw = InquisitionLaw.NONE
            }

            val passiveNotes = ArrayList<String>()
            if (lawViolationNote != null) passiveNotes.add(lawViolationNote)
            if (witchThornCounter) witchThornCounter = false

            // Active Spirit & Weather Per-Turn Passives!
            if (activeSpirit == ElementalSpirit.ONDINE) {
                mp = (mp + 14).coerceAtMost(maxMp)
                charmedTurns = 0; hypnoTurns = 0
                passiveNotes.add("💧 Ondine +14 PM · Anti-Charme")
            }
            if (playerRegenTurns > 0) {
                playerRegenTurns--
                val reg = if (healInvertTurns > 0) 9 else 18
                hp = (hp + reg).coerceAtMost(maxHp)
                passiveNotes.add("💚 Régén +$reg PV")
            }
            if (witchBurnTurns > 0) {
                witchBurnTurns--
                val burnDmg = (24 * spiritPowerMult).toInt()
                witchResolve = (witchResolve - burnDmg).coerceAtLeast(0)
                passiveNotes.add("🔥 Brûlure Ignis -$burnDmg Boss")
                if (witchResolve <= 0) { finishBattleVictory(); return }
            }
            when (battleWeather) {
                BattleWeather.MIND_FOG -> {
                    embrace = (embrace + 7).coerceAtMost(98)
                    passiveNotes.add("🌫️ Brume Mentale +7 % Emprise")
                }
                BattleWeather.ETHER_STORM -> {
                    mp = (mp + 12).coerceAtMost(maxMp)
                    passiveNotes.add("⚡ Tempête d'Éther +12 PM")
                }
                else -> {}
            }

            // Archetype Passives ticking each turn!
            if (curWitch.def.archetype == WitchCatalog.Archetype.CHARMER && charmedTurns > 0) {
                embrace = (embrace + 10).coerceAtMost(100)
                passiveNotes.add("💖 Parfum Charmeuse : +10 % Emprise !")
            }
            if (curWitch.def.archetype == WitchCatalog.Archetype.BINDER && !captured && witchStunTurns == 0) {
                silkThreads++
                if (silkThreads >= 3) {
                    silkThreads = 0; captured = true
                    passiveNotes.add("🕸️ TOILE 3/3 : CAPTURE AUTOMATIQUE !")
                } else {
                    passiveNotes.add("🕸️ Toile tissée ($silkThreads/3)")
                }
            }
            if (curWitch.def.archetype == WitchCatalog.Archetype.CHRONO && witchStunTurns == 0) {
                chronoHourglass = (chronoHourglass - 1).coerceAtLeast(0)
                if (chronoHourglass == 0) {
                    chronoHourglass = 4
                    frostButtonTaps = 3
                    passiveNotes.add("⏳ SABLIER 0 : ARRÊT DU TEMPS (Double dégâts + Givre ×3) !")
                }
            }
            if (dominationAura && !wasGuarding) {
                embrace = (embrace + (6 * forgeEmbraceResist).toInt().coerceAtLeast(3)).coerceAtMost(100)
                passiveNotes.add("👑 Aura +Emprise")
            }
            if (siphonTurns > 0) {
                siphonTurns--
                hp = (hp - 9).coerceAtLeast(0)
                val healW = if (curWitch.def.archetype == WitchCatalog.Archetype.DRAINER) 34 else 22
                witchResolve = (witchResolve + healW).coerceAtMost(witchMaxResolve)
                passiveNotes.add("🩸 Siphon -9 PV / Boss +$healW")
            }
            if (hypnoTurns > 0) {
                mp = (mp - 8).coerceAtLeast(0)
                passiveNotes.add("🌀 Hypnose -8 PM")
            }
            if (charmedTurns > 0) charmedTurns--
            if (hypnoTurns > 0) hypnoTurns--
            if (sealedTurns > 0) sealedTurns--

            // Stunned Witch (Posture Break!) skips attack!
            if (witchStunTurns > 0) {
                witchStunTurns--
                if (witchStunTurns == 0) witchPosture = witchMaxPosture
                plannedIntent = buildAiIntent()
                typeDialogue("💥 ${curWitch.def.name} est en RUPTURE !", "Elle perd son tour ! Profite du bonus ×2.0 DÉGÂTS avant qu'elle récupère sa Posture ! 🔥")
                refreshHud(); turnLocked = false; renderTurnControls(); return
            }

            if (checkBossPhaseShift()) {
                return
            }

            val intent = plannedIntent
            val attack = intent.attack
            val extraFooterList = ArrayList<String>()
            if (passiveNotes.isNotEmpty()) extraFooterList.add(passiveNotes.joinToString(" • "))

            // 1) Aegis Veil absorbs everything!
            if (aegisActive) {
                aegisActive = false; stage.triggerPerfectParryFlash(); onVibrate(45L)
                plannedIntent = buildAiIntent()
                typeDialogue("🛡️ VOILE D'ÉGIDE !", "Absorbe 100 % de « ${attack.name} » et bloque son sort système !")
                rollTouchSabotageForNextTurn(); refreshHud(); turnLocked = false; renderTurnControls(); return
            }

            // 1.5) Zéphyr Wind Dodge (35% physical evasion) & Ondine Auto-Counter vs Capture!
            if (!wasGuarding && activeSpirit == ElementalSpirit.ZEPHYR && battleWeather != BattleWeather.HEAVY_GRAVITY &&
                (attack.kind == AttackKind.STRIKE || attack.kind == AttackKind.ARMOR_BREAK) && Random.nextFloat() < 0.35f
            ) {
                sp = (sp + 18 + forgeSpBonus).coerceAtMost(100)
                stage.triggerPerfectParryFlash(); WitchCombatSystem.SoundFx.play(sfxOn, "spirit"); onVibrate(35L)
                plannedIntent = buildAiIntent()
                typeDialogue("🌬️ ESQUIVE DE ZÉPHYR !", "Le vent te dérobe à « ${attack.name} » ! 0 dégât & +18 SP !")
                rollTouchSabotageForNextTurn(); refreshHud(); turnLocked = false; renderTurnControls(); return
            }
            if (activeSpirit == ElementalSpirit.ONDINE && (attack.kind == AttackKind.BIND_CAPTURE || attack.kind == AttackKind.BIND_ESCALATE)) {
                captured = false; captureStage = 0; silkThreads = 0
                val cDmg = ((38 + playerLv) * forgeAtkMul * spiritPowerMult).toInt()
                witchResolve = (witchResolve - cDmg).coerceAtLeast(0)
                val broke = applyPostureDamage(40)
                stage.triggerPerfectParryFlash(); WitchCombatSystem.SoundFx.play(sfxOn, "parry"); onVibrate(55L)
                if (witchResolve <= 0) { finishBattleVictory(); return }
                plannedIntent = buildAiIntent()
                speakAi("💧 CONTRE FLUIDE D'ONDINE !", EventKind.COUNTER_HIT, attack.name, "Capture annulée ! -$cDmg Boss • -40 Posture${if (broke) " • 💥 RUPTURE !" else ""}")
                refreshHud(); turnLocked = false; renderTurnControls(); return
            }

            // 2) Counter-Seal intercepts Capture / Strike / Double / ArmorBreak!
            if (wasCountering && (attack.kind == AttackKind.BIND_CAPTURE || attack.kind == AttackKind.BIND_ESCALATE || attack.kind == AttackKind.STRIKE || attack.kind == AttackKind.TIME_DOUBLE || attack.kind == AttackKind.ARMOR_BREAK)) {
                val cDmg = ((50 + playerLv) * forgeAtkMul * comboMult).toInt()
                witchResolve = (witchResolve - cDmg).coerceAtLeast(0)
                if (curWitch.def.archetype == WitchCatalog.Archetype.BINDER) silkThreads = (silkThreads - 2).coerceAtLeast(0)
                val broke = applyPostureDamage(54)
                sp = (sp + 26 + forgeSpBonus).coerceAtMost(100)
                stage.triggerPerfectParryFlash(); WitchCombatSystem.SoundFx.play(sfxOn, "parry"); onVibrate(60L)
                if (witchResolve <= 0) { finishBattleVictory(); return }
                plannedIntent = buildAiIntent()
                speakAi("⚡ CONTRE-SCEAU PARFAIT !", EventKind.COUNTER_HIT, attack.name, "💥 -$cDmg Boss • -54 Posture${if (broke) " • 💥 RUPTURE !" else ""}")
                refreshHud(); turnLocked = false; renderTurnControls(); return
            }

            // 3) Perfect Parry vs Telegraphed Ultimate, Time Double or Armor Break!
            if (wasGuarding && (attack.kind == AttackKind.ULTIMATE || attack.kind == AttackKind.TIME_DOUBLE || attack.kind == AttackKind.ARMOR_BREAK)) {
                rpgState.recordPerfectParry()
                parriesThisFight++
                val parryDmg = (attack.hpDamage * 0.14f).toInt().coerceAtLeast(4)
                hp = (hp - parryDmg).coerceAtLeast(1)
                val zephSp = if (activeSpirit == ElementalSpirit.ZEPHYR) 12 else 0
                sp = (sp + 34 + zephSp + forgeSpBonus).coerceAtMost(100)
                val riposte = ((36 + playerLv / 2) * forgeAtkMul).toInt()
                witchResolve = (witchResolve - riposte).coerceAtLeast(0)
                if (curWitch.def.archetype == WitchCatalog.Archetype.CHRONO) chronoHourglass = (chronoHourglass + 2).coerceAtMost(4)
                val broke = applyPostureDamage(60)
                stage.triggerPerfectParryFlash(); WitchCombatSystem.SoundFx.play(sfxOn, "parry"); onVibrate(55L)
                if (witchResolve <= 0) { finishBattleVictory(); return }
                plannedIntent = buildAiIntent()
                speakAi("⚡ PARADE PARFAITE !", EventKind.PERFECT_PARRY, attack.name, "🛡️ -$parryDmg PV • +${34 + zephSp} SP • -60 Posture${if (broke) " • 💥 RUPTURE !" else ""}")
                refreshHud(); turnLocked = false; renderTurnControls(); return
            }

            if (attack.kind == AttackKind.ULTIMATE && !wasGuarding) missedUltimateGuards++
            when (attack.kind) {
                AttackKind.BARRIER_SHIELD -> witchShield = true
                AttackKind.MIRAGE_CLONE -> witchMirage = true
                AttackKind.THORN_COUNTER -> { witchThornCounter = true; extraFooterList.add("🌹 Riposte Épineuse active ! Utilise ✨ Brise-Sort ou 🛡️ Garde !") }
                AttackKind.WEATHER_SHIFT -> {
                    battleWeather = listOf(BattleWeather.BLOOD_ECLIPSE, BattleWeather.MIND_FOG, BattleWeather.ETHER_STORM, BattleWeather.HEAVY_GRAVITY).random()
                    extraFooterList.add("🌑 Météo → ${battleWeather.label} (${battleWeather.effectSummary})")
                }
                else -> {}
            }

            // Enact Inquisition Law!
            if (intent.lawToEnact != InquisitionLaw.NONE && !hadMeditateImm) {
                activeLaw = intent.lawToEnact
                lawTurns = if (activeLaw == InquisitionLaw.NO_GUARD) 1 else 2
                extraFooterList.add("⚖️ NOUVELLE RESTRICTION : ${activeLaw.label}")
            }

            // Execute Special Character & Concrete Phone Spells!
            var triggerVideoAfterHit = false
            if (!wasGuarding) {
                when (intent.phoneSpell) {
                    SpecialPhoneSpell.FORCED_VIDEO_TRANCE -> {
                        triggerVideoAfterHit = true
                    }
                    SpecialPhoneSpell.SEIZE_APP_HOSTAGE -> {
                        triggerMidBattleAppHostage("🔒 SORT CONFISCATION :")
                    }
                    SpecialPhoneSpell.CATEGORY_HOSTAGE_SWEEP -> {
                        if (allowMidAppPowers && DemonMdmManager.hasPrivilegedControl(context)) {
                            Thread {
                                val seizedList = DemonMdmManager.seizeCategoryHostagesMidBattle(context, count = 2)
                                if (seizedList.isNotEmpty()) {
                                    mainHandler.post {
                                        if (!sessionActive) return@post
                                        hostageApps.addAll(seizedList)
                                        midBattleHostageCount += seizedList.size
                                        onVibrate(140L)
                                        refreshHud()
                                        val names = seizedList.joinToString(", ") { it.second }
                                        dialogueTv.text = "${dialogueTv.text}\n🔒🔒 DOUBLE OTAGE SHIZUKU : « $names » grisées !"
                                    }
                                }
                            }.start()
                        }
                    }
                    SpecialPhoneSpell.SYSTEM_SEAL_RAID -> {
                        if (allowMidAppPowers) {
                            Thread {
                                val sealMsg = DemonMdmManager.applyWitchSystemSealMidBattle(context)
                                if (sealMsg != null) mainHandler.post {
                                    if (sessionActive) dialogueTv.text = "${dialogueTv.text}\n$sealMsg"
                                }
                            }.start()
                        }
                    }
                    SpecialPhoneSpell.AUDIO_SILENCE_CURSE -> {
                        Thread { DemonMdmManager.applyWitchAudioCurse(context, 0.0f) }.start()
                        sealedTurns = sealedTurns.coerceAtLeast(1)
                        extraFooterList.add("🔇 Volume coupé + Sorts scellés 1t !")
                    }
                    SpecialPhoneSpell.CHRONO_SLOWMO -> {
                        frostButtonTaps = frostButtonTaps.coerceAtLeast(2)
                        if (allowMidAppPowers && DemonMdmManager.isShizukuActive()) {
                            Thread { DemonMdmManager.applyChronoSlowMoCurse(context, true) }.start()
                            extraFooterList.add("⏳ Shizuku : animations Android ralenties ×3,5 + Touches givrées !")
                        } else {
                            extraFooterList.add("⏳ Distorsion temporelle : Touches givrées ×2 !")
                        }
                    }
                    SpecialPhoneSpell.FAKE_LOW_BATTERY -> {
                        if (allowMidAppPowers && DemonMdmManager.isShizukuActive()) {
                            Thread { DemonMdmManager.applyFakeLowBatteryCurse(context, true, 3) }.start()
                            extraFooterList.add("🔋 Shizuku : Illusion Batterie Critique 3 % injectée en barre d'état !")
                        }
                    }
                    SpecialPhoneSpell.SCREEN_DENSITY_WARP -> {
                        scrambleButtonsThisTurn = true
                        if (allowMidAppPowers && DemonMdmManager.isShizukuActive()) {
                            Thread { DemonMdmManager.applyScreenDensityDistortion(context, true) }.start()
                            extraFooterList.add("🪞 Shizuku : Distorsion Zoom Écran activée jusqu'à ta victoire !")
                        } else {
                            extraFooterList.add("🪞 Illusion tactile : boutons mélangés !")
                        }
                    }
                    SpecialPhoneSpell.ISOLATION_NOTIF_SEAL -> {
                        if (allowMidAppPowers && DemonMdmManager.isShizukuActive()) {
                            Thread {
                                val isoMsg = DemonMdmManager.applyIsolationNotificationCurse(context)
                                if (isoMsg != null) mainHandler.post {
                                    if (sessionActive) dialogueTv.text = "${dialogueTv.text}\n$isoMsg"
                                }
                            }.start()
                        }
                    }
                    SpecialPhoneSpell.MIC_CLIPBOARD_SEAL -> {
                        if (allowMidAppPowers && DemonMdmManager.isShizukuActive()) {
                            Thread {
                                val sealMsg = DemonMdmManager.applyMicAndClipboardCurse(context)
                                if (sealMsg != null) mainHandler.post {
                                    if (sessionActive) dialogueTv.text = "${dialogueTv.text}\n$sealMsg"
                                }
                            }.start()
                        }
                    }
                    SpecialPhoneSpell.ECLIPSE_DARK_POWER -> {
                        if (allowMidAppPowers && DemonMdmManager.isShizukuActive()) {
                            Thread { DemonMdmManager.applyEclipsePowerAndDarkCurse(context, true) }.start()
                            extraFooterList.add("🌑 Shizuku : Mode Sombre + Économie d'Énergie forcés !")
                        }
                    }
                    SpecialPhoneSpell.SCREEN_SLEEP_SNAP -> {
                        if (allowMidAppPowers && DemonMdmManager.isShizukuActive()) {
                            extraFooterList.add("😴 Shizuku : Claquement d'Écran — extinction forcée !")
                            mainHandler.postDelayed({
                                if (sessionActive) Thread { DemonMdmManager.triggerScreenSleepSnap() }.start()
                            }, 850L)
                        }
                    }
                    SpecialPhoneSpell.CONFISCATE_RELIC -> {
                        if (confiscatedRelic == null) {
                            val inv = rpgState.snapshot()
                            val pick = when {
                                inv.elixirs > 0 -> "elixir" to "🧪 Élixir"
                                inv.talismans > 0 -> "talisman" to "📜 Talisman"
                                inv.crystals > 0 -> "crystal" to "💎 Cristal"
                                else -> null
                            }
                            if (pick != null && rpgState.consumeItem(pick.first)) {
                                confiscatedRelic = pick
                                extraFooterList.add("🎒 ${curWitch.def.name} confisque ton ${pick.second} jusqu'à ta victoire !")
                            }
                        }
                    }
                    SpecialPhoneSpell.SP_LEECH -> {
                        val stolen = sp.coerceAtMost(30)
                        sp = (sp - stolen).coerceAtLeast(0)
                        extraFooterList.add("⚡ Vol de Volonté : -$stolen SP siphonné !")
                    }
                    SpecialPhoneSpell.HEAL_INVERSION -> {
                        healInvertTurns = 2
                        extraFooterList.add("🧪 Inversion de Soin (2t) : tes Élixirs soignent moitié moins !")
                    }
                    SpecialPhoneSpell.NONE -> {}
                }
            }

            val guardMul = if (wasGuarding) 0.25f else 1.0f
            val gaiaDefMul = if (activeSpirit == ElementalSpirit.GAIA) 0.70f else 1.0f
            val eclipseMul = if (battleWeather == BattleWeather.BLOOD_ECLIPSE) 1.25f else 1.0f
            val captureMul = when { captureStage >= 2 -> 1.48f; captured -> 1.28f; else -> 1.0f }
            val doubleMul = if (attack.kind == AttackKind.TIME_DOUBLE) 1.35f else 1.0f
            val stormMul = if (runicStormActive) 1.45f else 1.0f
            runicStormActive = false

            val dmg = (attack.hpDamage * bonusMult * guardMul * gaiaDefMul * eclipseMul * captureMul * doubleMul * stormMul).toInt().coerceAtLeast(5)
            val embGain = (attack.embraceGain * bonusMult * guardMul * forgeEmbraceResist).toInt().coerceAtLeast(5)
            val lvLoss = if (wasGuarding || hadMeditateImm) 0 else attack.levelDrain

            hp = (hp - dmg).coerceAtLeast(0)
            embrace = (embrace + embGain).coerceAtMost(100)
            sp = (sp + (if (wasGuarding) 15 else 11) + forgeSpBonus).coerceAtMost(100)
            if (lvLoss > 0) playerLv = (playerLv - lvLoss).coerceAtLeast(1)
            drainPct = (drainPct + if (wasGuarding) 4 else 13).coerceAtMost(95)

            // Drainer heals on every real hit (Ignis Burn cuts vampiric healing by 65%!)
            if (curWitch.def.archetype == WitchCatalog.Archetype.DRAINER && !wasGuarding) {
                val burnAntiHeal = if (witchBurnTurns > 0) 0.35f else 1.0f
                val eclipseHeal = if (battleWeather == BattleWeather.BLOOD_ECLIPSE) 1.15f else 1.0f
                val vampHeal = (dmg * 0.65f * burnAntiHeal * eclipseHeal).toInt().coerceAtLeast(4)
                witchResolve = (witchResolve + vampHeal).coerceAtMost(witchMaxResolve)
                extraFooterList.add("🦇 Vol de Vie : +$vampHeal Boss${if (witchBurnTurns > 0) " (réduit par 🔥 Ignis !)" else ""}")
            }

            // Adrenaline Last Stand!
            if (hp <= 0 && embrace < 100 && !adrenalineUsed && Random.nextFloat() < 0.45f) {
                adrenalineUsed = true; hp = 1; sp = (sp + 40).coerceAtMost(100)
                extraFooterList.add("⚡ SURSAUT D'ADRÉNALINE ! Tu tiens à 1 PV (+40 SP) !")
            }

            var statusAppliedMsg = ""
            if (!hadMeditateImm && !wasGuarding) {
                val resistRoll = Random.nextFloat() < (wardBonus - 1) * 0.04f
                val ondineMentalImm = activeSpirit == ElementalSpirit.ONDINE && (attack.statusEffect == "CHARMED" || attack.statusEffect == "HYPNOTIZED")
                if (ondineMentalImm) {
                    statusAppliedMsg = " • 💧 Ondine immunise !"
                } else if (!resistRoll) {
                    when (attack.statusEffect) {
                        "CAPTURE" -> {
                            captureStage = if (captured) 2 else 1
                            captured = true
                            statusAppliedMsg = if (captureStage >= 2) " • ⛓️⛓️ ÉTREINTE Nv.2 !" else " • ⛓️ CAPTURÉ Nv.1 !"
                        }
                        "CHARMED" -> { charmedTurns = 2; statusAppliedMsg = " • 💖 CHARMÉ !" }
                        "HYPNOTIZED" -> { hypnoTurns = 2; statusAppliedMsg = " • 🌀 HYPNOTISÉ !" }
                        "SEALED" -> { sealedTurns = 2; statusAppliedMsg = " • 🔒 SCELLÉ !" }
                        "SIPHON" -> { siphonTurns = 3; statusAppliedMsg = " • 🩸 SIPHON (3t) !" }
                    }
                } else if (attack.statusEffect.isNotEmpty()) statusAppliedMsg = " • 🧿 Résisté !"
            } else if (hadMeditateImm && attack.statusEffect.isNotEmpty()) statusAppliedMsg = " • 🧘 Immunité !"
            if (!captured && embrace >= 64 && !wasGuarding) { captured = true; captureStage = 1; statusAppliedMsg = " • ⛓️ EMPRISE CAPTURE !" }

            // Random Golden Rift / Runic Storm twist
            if (turnNumber >= 2 && hp > 0 && embrace < 100 && !triggerVideoAfterHit) {
                val tw = Random.nextInt(100)
                if (tw < 14) spawnFloatingGoldenRift()
                else if (tw in 14..22) { runicStormActive = true; extraFooterList.add("🌩️ Orage Runique : prochain coup ×1.5 !") }
            }

            settings.adjustPlayerStats(levelDelta = -lvLoss, hpDelta = -dmg, drainDelta = +9)
            try { onApplyVisualDrain(drainPct, cfg) } catch (_: Throwable) {}
            stage.triggerWitchAttackLunge(intense = !wasGuarding)
            onVibrate(if (wasGuarding) 25L else 75L)

            val guardTxt = if (wasGuarding) " (🛡️ -75 %)" else ""
            val lvTxt = if (lvLoss > 0) " • -$lvLoss NV" else ""
            extraFooterList.add("💥 « ${attack.name} » : -$dmg PV$lvTxt • Emprise $embrace%$guardTxt$statusAppliedMsg")

            val ev = when {
                forcedSpamCounter -> EventKind.ADAPT_COUNTER
                wasGuarding -> EventKind.GUARDED
                attack.kind == AttackKind.THORN_COUNTER -> EventKind.THORN_COUNTER_UP
                attack.kind == AttackKind.WEATHER_SHIFT -> EventKind.WEATHER_CHANGE
                attack.kind == AttackKind.BIND_ESCALATE -> EventKind.BIND_STAGE_2
                attack.kind == AttackKind.BARRIER_SHIELD -> EventKind.SHIELD_UP
                attack.kind == AttackKind.MIRAGE_CLONE -> EventKind.MIRAGE_CAST
                attack.kind == AttackKind.ENCHANT_CHARM -> EventKind.ENCHANT_CHARM
                attack.kind == AttackKind.ENCHANT_HYPNO -> EventKind.ENCHANT_HYPNO
                attack.kind == AttackKind.SIPHON_DRAIN -> EventKind.SIPHON_MARK
                attack.kind == AttackKind.TIME_DOUBLE -> EventKind.TIME_DOUBLE
                captured && attack.statusEffect == "CAPTURE" -> EventKind.CAPTURE
                else -> EventKind.ATTACK
            }

            // Little Demon Battle Familiar Assist (scales with Demon Affection & Demon Heart Forge!)
            if (hp > 0 && embrace < 100 && !triggerVideoAfterHit && Random.nextFloat() < demonAssistChance * 0.65f) {
                when (Random.nextInt(3)) {
                    0 -> {
                        frostButtonTaps = 0; mimicTrapThisTurn = false
                        extraFooterList.add("$demonFormBadge ${cfg.avatarName} intervient : Givre & Pièges tactiles brisés !")
                    }
                    1 -> {
                        val spHelp = 15 + (rSnap0.demonForm.index * 4)
                        sp = (sp + spHelp).coerceAtMost(100)
                        extraFooterList.add("$demonFormBadge ${cfg.avatarName} te lance +$spHelp SP !")
                    }
                    else -> {
                        applyPostureDamage(22)
                        extraFooterList.add("$demonFormBadge ${cfg.avatarName} mord la Sorcière (-22 Posture Boss) !")
                    }
                }
            }

            plannedIntent = buildAiIntent()
            speakAi(curWitch.profile.name, ev, attack.name, extraFooterList.joinToString("\n"))
            rollTouchSabotageForNextTurn()
            refreshHud()

            if (hp <= 0 || embrace >= 100) {
                actionsBox.removeAllViews()
                val r = Runnable { startCinematicDrainEndScene() }
                pendingTurnRunnable = r; mainHandler.postDelayed(r, 950L)
            } else if (triggerVideoAfterHit) {
                actionsBox.removeAllViews()
                val vidSec = if (curWitch.def.isBoss || dominanceTierFor(playerLv) >= 2) 11 else 8
                val r = Runnable {
                    launchForcedVideoTrance(vidSec) {
                        if (sessionActive && !inEndScene) {
                            turnLocked = false
                            renderTurnControls()
                        }
                    }
                }
                pendingTurnRunnable = r; mainHandler.postDelayed(r, 900L)
            } else if (allowTouchSabotage && (dominanceTierFor(playerLv) >= 2 || bossPhase >= 2) && Random.nextFloat() < 0.20f) {
                actionsBox.removeAllViews()
                actionsBox.addView(makeActionBtn("✋ À GENOUX — ÉCOUTE ${curWitch.def.name.uppercase()} ! (Verrou tactile…)", Color.rgb(88, 28, 135)) { onVibrate(45L) })
                val unlock = Runnable { if (sessionActive && !inEndScene) { turnLocked = false; renderTurnControls() } }
                pendingTurnRunnable = unlock; mainHandler.postDelayed(unlock, 2_000L)
            } else {
                turnLocked = false
                renderTurnControls()
            }
        }

        // ==================== ENFORCE INQUISITION LAW VIOLATION PENALTY ====================
        fun checkLawViolation(violatedLaw: InquisitionLaw): Boolean {
            if (activeLaw != violatedLaw || lawTurns <= 0) return false
            lawViolations++
            hp = (hp - 26).coerceAtLeast(0)
            playerLv = (playerLv - 1).coerceAtLeast(1)
            embrace = (embrace + 18).coerceAtMost(100)
            onVibrate(110L)
            stage.triggerWitchAttackLunge(intense = true)
            triggerMidBattleAppHostage("⚖️ SENTENCE DE LOI VIOLÉE :")
            if (hp <= 0 || embrace >= 100) {
                startCinematicDrainEndScene()
            } else {
                executeWitchTurn(1.15f, lawViolationNote = "⚖️ LOI VIOLÉE (${violatedLaw.shortTag}) ! Sentence : -26 PV, -1 NV, +18 % Emprise !")
            }
            return true
        }

        // ==================== 5 VARIED PHASE-2 CAPTURE QTEs ====================
        fun qteSpiritBonusMs(): Long = if (activeSpirit == ElementalSpirit.ZEPHYR) 800L else 0L

        fun onQteSuccess(bonusDmg: Int = 34) {
            qteTickRunnable?.let { mainHandler.removeCallbacks(it) }; qteTickRunnable = null
            rpgState.recordQteBreak()
            qteWinsThisFight++
            captured = false; captureStage = 0; embrace = (embrace - 45).coerceAtLeast(0); silkThreads = 0
            sp = (sp + 24 + forgeSpBonus).coerceAtMost(100)
            witchResolve = (witchResolve - (bonusDmg * forgeAtkMul).toInt()).coerceAtLeast(0)
            val broke = applyPostureDamage(38)
            stage.triggerWitchHitShake(critical = true); WitchCombatSystem.SoundFx.play(sfxOn, "break"); onVibrate(45L)
            speakAi("💥 ÉVASION PARFAITE !", EventKind.QTE_BREAK, extraFooter = "Emprise -45 % • +24 SP • -38 Posture${if (broke) " • 💥 RUPTURE !" else ""}")
            refreshHud()
            if (witchResolve <= 0) finishBattleVictory() else { turnLocked = false; renderTurnControls() }
        }

        fun onQteFailure() {
            qteTickRunnable?.let { mainHandler.removeCallbacks(it) }; qteTickRunnable = null; qteFails++
            speakAi(curWitch.profile.name, EventKind.QTE_FAIL)
            val r = Runnable { executeWitchTurn(1.30f) }; pendingTurnRunnable = r; mainHandler.postDelayed(r, 520L)
        }

        fun startMashQte() {
            actionsBox.removeAllViews(); turnTimerRunnable?.let { mainHandler.removeCallbacks(it) }
            var taps = 0; val required = if (curWitch.def.isBoss) 12 else 10
            val endElapsed = SystemClock.elapsedRealtime() + 3_800L + (wardBonus - 1) * 140L
            val qteBtn = makeActionBtn("⚡ MARTÈLE VITE ! (0/$required)", Color.rgb(234, 88, 12)) {}
            val timerTv = TextView(context).apply {
                text = "⏳ Libère-toi !"; setTextColor(Color.rgb(253, 224, 71)); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0)
            }
            actionsBox.addView(qteBtn); actionsBox.addView(timerTv)
            qteBtn.setOnClickListener { taps++; onVibrate(14L); qteBtn.text = "⚡ MARTÈLE VITE ! ($taps/$required)"; if (taps >= required) onQteSuccess(30) }
            val tick = object : Runnable {
                override fun run() {
                    if (!sessionActive || inEndScene) return
                    val remMs = endElapsed - SystemClock.elapsedRealtime()
                    if (remMs <= 0L) onQteFailure() else { timerTv.text = String.format("⏳ %.1fs restantes !", remMs / 1000f); mainHandler.postDelayed(this, 90L) }
                }
            }
            qteTickRunnable = tick; mainHandler.postDelayed(tick, 90L)
        }

        fun startTimingQte() {
            actionsBox.removeAllViews(); turnTimerRunnable?.let { mainHandler.removeCallbacks(it) }
            var pos = 0; var dir = 1; val slots = 16; val endElapsed = SystemClock.elapsedRealtime() + 5_500L
            val barTv = TextView(context).apply {
                setTextColor(Color.rgb(253, 224, 71)); textSize = 15f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(6))
            }
            val hitBtn = makeActionBtn("🎯 BRISER DANS LA ZONE DORÉE [★] !", Color.rgb(217, 119, 6)) { if (pos in 6..10) onQteSuccess(44) else onQteFailure() }
            actionsBox.addView(barTv); actionsBox.addView(hitBtn)
            val tick = object : Runnable {
                override fun run() {
                    if (!sessionActive || inEndScene) return
                    if (endElapsed - SystemClock.elapsedRealtime() <= 0L) { onQteFailure(); return }
                    pos += dir; if (pos >= slots - 1) { pos = slots - 1; dir = -1 }; if (pos <= 0) { pos = 0; dir = 1 }
                    val sb = StringBuilder(); for (i in 0 until slots) sb.append(when { i == pos -> "💎"; i in 6..10 -> "★"; else -> "─" })
                    barTv.text = sb.toString(); mainHandler.postDelayed(this, 68L)
                }
            }
            qteTickRunnable = tick; mainHandler.post(tick)
        }

        fun startRuneSequenceQte() {
            actionsBox.removeAllViews(); turnTimerRunnable?.let { mainHandler.removeCallbacks(it) }
            val runes = listOf("🌙", "🔥", "⚡", "✨"); val seq = List(3) { runes.random() }; var idx = 0
            val endElapsed = SystemClock.elapsedRealtime() + 5_200L + (wardBonus - 1) * 180L
            val seqTv = TextView(context).apply {
                text = "🔮 Tape dans l'ordre : ${seq.joinToString("  →  ")}"; setTextColor(Color.rgb(240, 171, 252))
                textSize = 13.4f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(0, dp(2), 0, dp(6))
            }
            actionsBox.addView(seqTv)
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            runes.forEachIndexed { rIdx, rune ->
                row.addView(makeActionBtn(rune, Color.rgb(126, 34, 206)) {
                    onVibrate(18L)
                    if (rune == seq[idx]) { idx++; seqTv.text = "🔮 Validé $idx/3 : ${seq.joinToString("  →  ")}"; if (idx >= seq.size) onQteSuccess(38) } else onQteFailure()
                }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (rIdx > 0) marginStart = dp(4) })
            }
            actionsBox.addView(row)
            val tick = object : Runnable {
                override fun run() {
                    if (!sessionActive || inEndScene) return
                    if (endElapsed - SystemClock.elapsedRealtime() <= 0L) onQteFailure() else mainHandler.postDelayed(this, 120L)
                }
            }
            qteTickRunnable = tick; mainHandler.postDelayed(tick, 120L)
        }

        fun startHypnoOrbHuntQte() {
            actionsBox.removeAllViews(); turnTimerRunnable?.let { mainHandler.removeCallbacks(it) }
            var hits = 0; val targetHits = 3
            val endElapsed = SystemClock.elapsedRealtime() + 5_500L + (wardBonus - 1) * 180L
            val hdrTv = TextView(context).apply {
                text = "✨ CHASSE LE VRAI SCEAU DORÉ ! (0/$targetHits — évite 💜)"
                setTextColor(Color.rgb(254, 240, 138)); textSize = 12.6f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, dp(5))
            }
            val gridBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            actionsBox.addView(hdrTv); actionsBox.addView(gridBox)

            lateinit var drawOrbs: () -> Unit
            drawOrbs = {
                gridBox.removeAllViews()
                val trueIdx = Random.nextInt(4)
                for (rowIdx in 0 until 2) {
                    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    for (colIdx in 0 until 2) {
                        val idx = rowIdx * 2 + colIdx
                        val isTrue = idx == trueIdx
                        val btn = makeActionBtn(
                            if (isTrue) "✨ VRAI SCEAU" else "💜 ILLUSION",
                            if (isTrue) Color.rgb(217, 119, 6) else Color.rgb(107, 33, 168)
                        ) {
                            onVibrate(18L)
                            if (isTrue) {
                                hits++
                                hdrTv.text = "✨ CHASSE LE VRAI SCEAU DORÉ ! ($hits/$targetHits)"
                                if (hits >= targetHits) onQteSuccess(42) else drawOrbs()
                            } else {
                                onQteFailure()
                            }
                        }
                        row.addView(btn, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                            if (colIdx > 0) marginStart = dp(5)
                            if (rowIdx > 0) topMargin = dp(5)
                        })
                    }
                    gridBox.addView(row)
                }
            }
            drawOrbs()

            val tick = object : Runnable {
                override fun run() {
                    if (!sessionActive || inEndScene) return
                    val rem = endElapsed - SystemClock.elapsedRealtime()
                    if (rem <= 0L) onQteFailure() else mainHandler.postDelayed(this, 120L)
                }
            }
            qteTickRunnable = tick; mainHandler.postDelayed(tick, 120L)
        }

        /** 5th Escape QTE — "Résistance Mentale" : push back the rising purple wave by tapping 4 Willpower Seals! */
        fun startMentalPulseQte() {
            actionsBox.removeAllViews(); turnTimerRunnable?.let { mainHandler.removeCallbacks(it) }
            var wavePct = 20; var sealsCleared = 0; val goal = 4
            val endElapsed = SystemClock.elapsedRealtime() + 5_800L + (wardBonus - 1) * 160L + qteSpiritBonusMs()
            val gaugeTv = TextView(context).apply {
                text = "🧠 Vague d'Hypnose : $wavePct % • Sceaux brisés : 0/$goal"
                setTextColor(Color.rgb(240, 171, 252)); textSize = 12.6f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, dp(5))
            }
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            actionsBox.addView(gaugeTv); actionsBox.addView(row)
            val icons = listOf("🔥 VOLONTÉ", "🛡️ REMPART", "⚡ ÉCLAT", "🌟 ESPRIT")
            var activeIdx = Random.nextInt(4)
            lateinit var renderPulse: () -> Unit
            renderPulse = {
                row.removeAllViews()
                icons.forEachIndexed { idx, lbl ->
                    val lit = idx == activeIdx
                    row.addView(makeActionBtn(if (lit) "▶ $lbl" else "···", if (lit) Color.rgb(16, 185, 129) else Color.rgb(42, 34, 62)) {
                        onVibrate(18L)
                        if (lit) {
                            sealsCleared++; wavePct = (wavePct - 18).coerceAtLeast(0)
                            if (sealsCleared >= goal) onQteSuccess(46)
                            else { activeIdx = Random.nextInt(4); renderPulse() }
                        } else {
                            wavePct = (wavePct + 25).coerceAtMost(100)
                            if (wavePct >= 100) onQteFailure()
                        }
                    }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { if (idx > 0) marginStart = dp(4) })
                }
            }
            renderPulse()
            val tick = object : Runnable {
                override fun run() {
                    if (!sessionActive || inEndScene) return
                    wavePct += 3
                    gaugeTv.text = "🧠 Vague d'Hypnose : $wavePct % • Sceaux brisés : $sealsCleared/$goal"
                    if (wavePct >= 100 || endElapsed - SystemClock.elapsedRealtime() <= 0L) onQteFailure()
                    else mainHandler.postDelayed(this, 160L)
                }
            }
            qteTickRunnable = tick; mainHandler.postDelayed(tick, 160L)
        }

        fun startNextVariedQte() {
            when (qteVarietyCounter++ % 5) {
                0 -> startTimingQte()
                1 -> startRuneSequenceQte()
                2 -> startHypnoOrbHuntQte()
                3 -> startMentalPulseQte()
                else -> startMashQte()
            }
        }

        // ==================== HELPER: EXECUTE DIRECT PLAYER OFFENSIVE ART (SPIRITS + ADAPTATION + THORN REFLECT!) ====================
        fun executePlayerOffensiveArt(
            artKey: String,
            artTitle: String,
            baseMin: Int,
            baseMax: Int,
            postureDmg: Int,
            spGain: Int,
            breaksShield: Boolean = false,
            piercesMirage: Boolean = false,
            multiHits: Int = 1
        ) {
            if (witchThornCounter) {
                witchThornCounter = false
                val refDmg = (24 + curWitch.def.chapter * 5).coerceAtMost(hp - 1).coerceAtLeast(8)
                hp = (hp - refDmg).coerceAtLeast(1)
                stage.triggerWitchAttackLunge(intense = true); WitchCombatSystem.SoundFx.play(sfxOn, "curse"); onVibrate(75L)
                speakAi("🌹 RIPOSTE ÉPINEUSE !", EventKind.THORN_REFLECT, extraFooter = "-$refDmg PV renvoyés ! Utilise ✨ Brise-Sort ou 🛡️ Garde contre ses Épines !")
                refreshHud()
                val r = Runnable { executeWitchTurn(1.0f) }; pendingTurnRunnable = r; mainHandler.postDelayed(r, 700L)
                return
            }
            if (witchMirage && !piercesMirage && activeSpirit != ElementalSpirit.ZEPHYR && Random.nextBoolean()) {
                typeDialogue("🪞 MIRAGE !", "Ton coup traverse un clone ! Utilise ⚔️ Estocade, 🌬️ Zéphyr, 🧘 Méditation ou 🌟 Exorcisme !")
                val r = Runnable { executeWitchTurn(1.0f) }; pendingTurnRunnable = r; mainHandler.postDelayed(r, 650L)
                return
            }

            val (vMul, spammed) = evaluateActionVariety(artKey, isOffensive = true)
            val (elemMul, elemTag) = WitchCombatSystem.spiritElementMultiplier(activeSpirit, curWitch.def)
            val ignisBoost = if (activeSpirit == ElementalSpirit.IGNIS) 1.45f else 1.0f
            val eclipseBoost = if (battleWeather == BattleWeather.BLOOD_ECLIPSE) 1.25f else 1.0f
            val etherBoost = if (battleWeather == BattleWeather.ETHER_STORM && artKey != "STRIKE") 1.30f else 1.0f
            val focusMul = if (focusCharged) 1.85f else 1.0f
            val furyMul = if (furyTurns > 0) 1.60f else 1.0f
            val breakMul = if (witchStunTurns > 0) 2.0f else if (witchPosture > 0) 0.68f else 1.25f
            val wasShield = witchShield && breaksShield
            if (wasShield) witchShield = false
            val shieldMul = if (witchShield) 0.35f else 1.0f
            val stormMul = if (runicStormActive) 1.50f else 1.0f
            runicStormActive = false; focusCharged = false

            var totalDmg = 0
            for (h in 0 until multiHits) {
                val baseRoll = Random.nextInt(baseMin, baseMax + 1) + playerLv / 2
                totalDmg += (baseRoll * forgeAtkMul * vMul * elemMul * ignisBoost * eclipseBoost * etherBoost * focusMul * furyMul * breakMul * shieldMul * stormMul).toInt().coerceAtLeast(4)
            }
            if (activeSpirit == ElementalSpirit.IGNIS) witchBurnTurns = 3
            if (breaksShield && curWitch.def.archetype == WitchCatalog.Archetype.BINDER) silkThreads = (silkThreads - 2).coerceAtLeast(0)

            sp = (sp + spGain + forgeSpBonus).coerceAtMost(100)
            witchResolve = (witchResolve - totalDmg).coerceAtLeast(0)
            val broke = applyPostureDamage(if (wasShield) postureDmg + 28 else postureDmg)
            stage.triggerWitchHitShake(critical = wasShield || broke || witchStunTurns > 0 || elemMul >= 1.4f)
            onVibrate(if (broke || wasShield) 50L else 25L)

            val noteParts = ArrayList<String>().apply {
                add("-$totalDmg Boss")
                if (multiHits > 1) add("($multiHits coups)")
                if (elemTag != null) add(elemTag)
                if (activeSpirit == ElementalSpirit.IGNIS) add("🔥 Brûlure 3t")
                if (spammed) add("🧠 Adaptation ennemie !")
                if (broke) add("💥 RUPTURE 2 TOURS (×2.0 !)")
            }

            if (witchResolve <= 0) {
                finishBattleVictory()
            } else if (wasShield || broke) {
                speakAi(
                    if (broke) "💥 RUPTURE DE POSTURE — $artTitle !" else "💥 BOUCLIER BRISÉ !",
                    EventKind.SHIELD_BREAK,
                    extraFooter = noteParts.joinToString(" • ")
                )
                refreshHud(); renderTurnControls()
            } else {
                executeWitchTurn(if (spammed) 1.25f else 1.0f, forcedSpamCounter = spammed)
            }
        }

        // ==================== 16 PLAYER SKILLS: ⚔️ SWORD ARTS MENU ====================
        fun showSwordArtsMenu() {
            val isSealed = sealedTurns > 0
            val mpMul = if (hypnoTurns > 0) 1.4f else 1.0f
            val bladeCost = (12 * mpMul).toInt()
            val danceCost = (16 * mpMul).toInt()
            val counterCost = (12 * mpMul).toInt()
            actionsBox.removeAllViews()

            val r1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            r1.addView(makeActionBtn("⚔️ Estocade (+20 SP · Anti-Mirage)", Color.rgb(225, 29, 72)) {
                if (checkLawViolation(InquisitionLaw.NO_STRIKE)) return@makeActionBtn
                executePlayerOffensiveArt("STRIKE", "Estocade", 22, 32, postureDmg = 18, spGain = 20, piercesMirage = true)
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(4) })

            r1.addView(makeActionBtn(
                if (isSealed) "🔒 Brise-Garde" else "🔥 Fente Brise-Garde (-$bladeCost PM)",
                if (isSealed || mp < bladeCost) Color.rgb(58, 54, 72) else Color.rgb(234, 88, 12)
            ) {
                if (checkLawViolation(InquisitionLaw.NO_MAGIC)) return@makeActionBtn
                if (isSealed || mp < bladeCost) { typeDialogue("⚡ Indisponible", "Sorts scellés ou PM insuffisants !"); return@makeActionBtn }
                mp -= bladeCost
                executePlayerOffensiveArt("BLADE", "Fente Brise-Garde", 34, 48, postureDmg = 48, spGain = 14, breaksShield = true)
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(4) })
            actionsBox.addView(r1)

            val r2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            r2.addView(makeActionBtn(
                if (isSealed) "🔒 Danse Lames" else "🗡️ Danse des Lames ×3 (-$danceCost PM)",
                if (isSealed || mp < danceCost) Color.rgb(58, 54, 72) else Color.rgb(190, 24, 93)
            ) {
                if (checkLawViolation(InquisitionLaw.NO_STRIKE)) return@makeActionBtn
                if (isSealed || mp < danceCost) { typeDialogue("⚡ Indisponible", "Besoin de $danceCost PM !"); return@makeActionBtn }
                mp -= danceCost
                executePlayerOffensiveArt("DANCE", "Danse des Lames", 16, 23, postureDmg = 26, spGain = 16, multiHits = 3)
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(4); topMargin = dp(4) })

            r2.addView(makeActionBtn(
                if (isSealed) "🔒 Contre-Sceau" else "⚡ Contre-Sceau (-$counterCost PM)",
                if (isSealed || mp < counterCost) Color.rgb(58, 54, 72) else Color.rgb(14, 116, 144)
            ) {
                if (checkLawViolation(InquisitionLaw.NO_MAGIC)) return@makeActionBtn
                if (isSealed || mp < counterCost) { typeDialogue("⚡ Indisponible", "Besoin de $counterCost PM !"); return@makeActionBtn }
                mp -= counterCost; counterStance = true; evaluateActionVariety("COUNTER", isOffensive = false); executeWitchTurn(1.0f)
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(4); topMargin = dp(4) })
            actionsBox.addView(r2)

            val r3 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            r3.addView(makeActionBtn("🎯 Concentration (+20 SP · Prochain ×1.85 !)", Color.rgb(180, 83, 9)) {
                focusCharged = true; sp = (sp + 20 + forgeSpBonus).coerceAtMost(100)
                evaluateActionVariety("FOCUS", isOffensive = false)
                typeDialogue("🎯 CONCENTRATION !", "Ton prochain Art infligera ×1.85 dégâts et ×1.55 Brise-Posture !")
                refreshHud(); executeWitchTurn(0.92f)
            }, LinearLayout.LayoutParams(0, dp(36), 1.4f).apply { marginEnd = dp(4); topMargin = dp(4) })
            r3.addView(makeActionBtn("↩ Retour", Color.rgb(42, 36, 56)) { renderTurnControls() },
                LinearLayout.LayoutParams(0, dp(36), 0.6f).apply { marginStart = dp(4); topMargin = dp(4) })
            actionsBox.addView(r3)
        }

        // ==================== 16 PLAYER SKILLS: 🌀 4 ELEMENTAL SPIRITS, MAGIC & ULTIMATES MENU ====================
        fun showSpiritsAndMagicMenu() {
            val isSealed = sealedTurns > 0
            actionsBox.removeAllViews()

            // Row 1: The 4 MGQ Elemental Spirits!
            val spRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val spirits = listOf(ElementalSpirit.ZEPHYR, ElementalSpirit.GAIA, ElementalSpirit.ONDINE, ElementalSpirit.IGNIS)
            spirits.forEachIndexed { idx, spirit ->
                val isUnlocked = unlockedSpirits.contains(spirit.id)
                val cost = (spirit.mpCost - spiritMpDiscount).coerceAtLeast(6)
                val isActive = activeSpirit == spirit
                val label = when {
                    !isUnlocked -> "🔒 ${spirit.shortBadge.take(5)}"
                    isActive -> "✅ ${spirit.shortBadge}"
                    else -> "${spirit.shortBadge} (-$cost)"
                }
                val bg = when {
                    isActive -> spirit.colorHex
                    !isUnlocked || isSealed || mp < cost -> Color.rgb(48, 44, 62)
                    else -> Color.argb(235, Color.red(spirit.colorHex), Color.green(spirit.colorHex), Color.blue(spirit.colorHex))
                }
                spRow.addView(makeActionBtn(label, bg) {
                    if (!isUnlocked) { typeDialogue("🔒 ${spirit.displayName}", spirit.unlockHint); return@makeActionBtn }
                    if (isSealed) { typeDialogue("🔒 Sorts Scellés", "Purifie ton sceau d'abord !"); return@makeActionBtn }
                    if (mp < cost) { typeDialogue("⚡ PM insuffisants", "Besoin de $cost PM !"); return@makeActionBtn }
                    mp -= cost; activeSpirit = spirit; usedSpiritThisFight = true
                    if (spirit == ElementalSpirit.ONDINE) { charmedTurns = 0; hypnoTurns = 0 }
                    if (spirit == ElementalSpirit.GAIA) { frostButtonTaps = 0; dodgeButtonThisTurn = false }
                    WitchCombatSystem.SoundFx.play(sfxOn, "spirit")
                    speakAi("🌀 INVOCATION : ${spirit.displayName} !", EventKind.SPIRIT_SUMMON, spirit.displayName, spirit.passiveDesc)
                    refreshHud(); renderTurnControls()
                }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { if (idx > 0) marginStart = dp(3) })
            }
            actionsBox.addView(spRow)

            // Row 2: Tactical Magic (Méditation, Brise-Sort, Chant Résistance)
            val mRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            mRow.addView(makeActionBtn("🧘 Médit. (+32 PM)", Color.rgb(16, 128, 96)) {
                mp = (mp + 32).coerceAtMost(maxMp); sp = (sp + 15 + forgeSpBonus).coerceAtMost(100)
                charmedTurns = 0; hypnoTurns = 0; witchMirage = false; fogBlindTurns = 0; meditateImmunity = true
                if (battleWeather == BattleWeather.MIND_FOG) battleWeather = BattleWeather.CLEAR
                evaluateActionVariety("MEDITATE", isOffensive = false); executeWitchTurn(0.88f)
            }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(3); topMargin = dp(4) })

            mRow.addView(makeActionBtn("✨ Brise-Sort (-14)", if (isSealed || mp < 14) Color.rgb(54, 50, 68) else Color.rgb(126, 34, 206)) {
                if (isSealed || mp < 14) { typeDialogue("⚡ PM insuffisants", "Besoin de 14 PM !"); return@makeActionBtn }
                mp -= 14; witchShield = false; witchMirage = false; witchThornCounter = false
                activeLaw = InquisitionLaw.NONE; lawTurns = 0; battleWeather = BattleWeather.CLEAR
                val broke = applyPostureDamage(35)
                evaluateActionVariety("DISPEL", isOffensive = false); stage.triggerPerfectParryFlash()
                typeDialogue("✨ BRISE-SORT !", "Lois d'Inquisition, Riposte Épineuse, Barrière & Météo annulées ! -35 Posture${if (broke) " • 💥 RUPTURE !" else ""}")
                refreshHud(); if (broke) renderTurnControls() else executeWitchTurn(0.90f)
            }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(3); topMargin = dp(4) })

            mRow.addView(makeActionBtn("💚 Chant (-16)", if (isSealed || mp < 16) Color.rgb(54, 50, 68) else Color.rgb(5, 150, 105)) {
                if (isSealed || mp < 16) { typeDialogue("⚡ PM insuffisants", "Besoin de 16 PM !"); return@makeActionBtn }
                mp -= 16; embrace = (embrace - 35).coerceAtLeast(0); playerRegenTurns = 3
                evaluateActionVariety("CHANT", isOffensive = false)
                typeDialogue("💚 CHANT DE RÉSISTANCE !", "Emprise -35 % et Régénération +18 PV pendant 3 tours !")
                refreshHud(); executeWitchTurn(0.92f)
            }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { topMargin = dp(4) })
            actionsBox.addView(mRow)

            // Row 3: Ultimates (Exorcisme 50 SP, Jugement / Surcharge Spirituelle 100 SP) + Back
            val uRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val canOverdrive = sp >= 100 && activeSpirit != ElementalSpirit.NONE
            val canJudg = sp >= 100
            val canExor = sp >= 50
            uRow.addView(makeActionBtn(
                when {
                    canOverdrive -> "⚡ SURCHARGE ${activeSpirit.shortBadge} (100 SP)"
                    canJudg -> "💥 JUGEMENT ULTIME (100 SP)"
                    canExor -> "🌟 EXORCISME (50 SP)"
                    else -> "🌟 Ultimes ($sp/50 SP)"
                },
                when {
                    canOverdrive -> Color.rgb(192, 38, 211)
                    canJudg -> Color.rgb(220, 38, 38)
                    canExor -> Color.rgb(217, 119, 6)
                    else -> Color.rgb(54, 50, 68)
                }
            ) {
                when {
                    canOverdrive -> {
                        sp = 0; witchShield = false; witchMirage = false; witchThornCounter = false; fogBlindTurns = 0
                        activeLaw = InquisitionLaw.NONE; lawTurns = 0; witchBurnTurns = 3
                        charmedTurns = 0; hypnoTurns = 0; sealedTurns = 0; siphonTurns = 0; healInvertTurns = 0
                        embrace = (embrace - 70).coerceAtLeast(0); hp = (hp + 55).coerceAtMost(maxHp)
                        val (elemMul, _) = WitchCombatSystem.spiritElementMultiplier(activeSpirit, curWitch.def)
                        val broke = applyPostureDamage(120)
                        val dmgBoss = ((165 + playerLv * 3) * forgeAtkMul * spiritPowerMult * elemMul * (if (witchStunTurns > 0) 2.0f else 1.35f)).toInt()
                        witchResolve = (witchResolve - dmgBoss).coerceAtLeast(0)
                        stage.triggerWitchHitShake(critical = true); WitchCombatSystem.SoundFx.play(sfxOn, "break"); onVibrate(95L)
                        if (witchResolve <= 0) finishBattleVictory() else {
                            speakAi("⚡🌟 SURCHARGE SPIRITUELLE !", EventKind.OVERDRIVE_ULT, extraFooter = "-$dmgBoss Boss • +55 PV • -70 % Emprise • Brûlure 3t${if (broke) " • 💥 RUPTURE !" else ""}")
                            refreshHud(); renderTurnControls()
                        }
                    }
                    canJudg -> {
                        sp = 0; witchShield = false; witchMirage = false; witchThornCounter = false; fogBlindTurns = 0
                        charmedTurns = 0; hypnoTurns = 0; sealedTurns = 0; siphonTurns = 0; healInvertTurns = 0
                        embrace = (embrace - 60).coerceAtLeast(0); hp = (hp + 45).coerceAtMost(maxHp)
                        val broke = applyPostureDamage(95)
                        val dmgBoss = ((135 + playerLv * 2) * forgeAtkMul * comboMult * (if (witchStunTurns > 0) 2.0f else 1.25f)).toInt()
                        witchResolve = (witchResolve - dmgBoss).coerceAtLeast(0)
                        stage.triggerWitchHitShake(critical = true); WitchCombatSystem.SoundFx.play(sfxOn, "break"); onVibrate(80L)
                        if (witchResolve <= 0) finishBattleVictory() else {
                            speakAi("💥 JUGEMENT ULTIME (100 SP) !", EventKind.JUDGMENT_ULT, extraFooter = "-$dmgBoss Boss • +45 PV • Emprise -60 %${if (broke) " • 💥 RUPTURE !" else ""}")
                            refreshHud(); renderTurnControls()
                        }
                    }
                    canExor -> {
                        sp -= 50; witchShield = false; witchMirage = false; witchThornCounter = false; fogBlindTurns = 0
                        activeLaw = InquisitionLaw.NONE; lawTurns = 0
                        charmedTurns = 0; hypnoTurns = 0; sealedTurns = 0; siphonTurns = 0; healInvertTurns = 0
                        embrace = (embrace - 45).coerceAtLeast(0); evaluateActionVariety("EXORCISM", isOffensive = false)
                        val broke = applyPostureDamage(50)
                        val dmgBoss = ((74 + playerLv) * forgeAtkMul * comboMult * (if (witchStunTurns > 0) 2.0f else 1.0f)).toInt()
                        witchResolve = (witchResolve - dmgBoss).coerceAtLeast(0)
                        stage.triggerWitchHitShake(critical = true); onVibrate(55L)
                        if (witchResolve <= 0) finishBattleVictory() else {
                            speakAi("🌟 SCEAU D'EXORCISME !", EventKind.EXORCISM_HIT, extraFooter = "-$dmgBoss Boss • -50 Posture • Tout purifié !${if (broke) " • 💥 RUPTURE !" else ""}")
                            refreshHud()
                            if (broke) renderTurnControls() else {
                                val r = Runnable { executeWitchTurn(0.85f) }; pendingTurnRunnable = r; mainHandler.postDelayed(r, 700L)
                            }
                        }
                    }
                    else -> typeDialogue("✨ SP insuffisants", "Charge 50 SP (Exorcisme) ou 100 SP (Jugement / Surcharge Spirituelle) !")
                }
            }, LinearLayout.LayoutParams(0, dp(36), 1.45f).apply { marginEnd = dp(4); topMargin = dp(4) })

            uRow.addView(makeActionBtn("↩ Retour", Color.rgb(42, 36, 56)) { renderTurnControls() },
                LinearLayout.LayoutParams(0, dp(36), 0.55f).apply { topMargin = dp(4) })
            actionsBox.addView(uRow)
        }

        fun showItemsMenu(page: Int = 0) {
            if (activeLaw == InquisitionLaw.NO_ITEMS && lawTurns > 0) {
                typeDialogue("⚖️ SAC CONFISQUÉ !", "La Loi de ${curWitch.def.name} interdit les Reliques pendant encore $lawTurns tour(s) !")
                return
            }
            val inv = rpgState.snapshot()
            actionsBox.removeAllViews()
            if (page == 0) {
                val r1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                val healAmt = if (healInvertTurns > 0) 34 else 74
                r1.addView(makeActionBtn("🧪 Élixir +$healAmt (${inv.elixirs})", if (inv.elixirs > 0) Color.rgb(16, 128, 96) else Color.rgb(52, 52, 66)) {
                    if (rpgState.consumeItem("elixir")) {
                        relicsUsedThisFight++; hp = (hp + healAmt).coerceAtMost(maxHp)
                        typeDialogue("🧪 Élixir", "+$healAmt PV !"); refreshHud(); executeWitchTurn(0.88f)
                    }
                }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(4) })
                r1.addView(makeActionBtn("💎 Cristal +48 PM (${inv.crystals})", if (inv.crystals > 0) Color.rgb(37, 99, 235) else Color.rgb(52, 52, 66)) {
                    if (rpgState.consumeItem("crystal")) {
                        relicsUsedThisFight++; mp = (mp + 48).coerceAtMost(maxMp); sp = (sp + 25).coerceAtMost(100)
                        typeDialogue("💎 Cristal", "+48 PM & +25 SP !"); refreshHud(); executeWitchTurn(0.88f)
                    }
                }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(4) })
                actionsBox.addView(r1)

                val r2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                r2.addView(makeActionBtn("📜 Talisman (${inv.talismans})", if (inv.talismans > 0) Color.rgb(217, 119, 6) else Color.rgb(52, 52, 66)) {
                    if (rpgState.consumeItem("talisman")) {
                        relicsUsedThisFight++; captured = false; captureStage = 0; silkThreads = 0; embrace = (embrace - 50).coerceAtLeast(0)
                        stage.triggerWitchHitShake(); typeDialogue("📜 Talisman", "Liens pulvérisés ! Emprise -50 % !"); refreshHud(); renderTurnControls()
                    }
                }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(4); topMargin = dp(4) })
                r2.addView(makeActionBtn("🔮 Amulette (${inv.amulets})", if (inv.amulets > 0) Color.rgb(126, 34, 206) else Color.rgb(52, 52, 66)) {
                    if (rpgState.consumeItem("amulet")) {
                        relicsUsedThisFight++; charmedTurns = 0; hypnoTurns = 0; sealedTurns = 0; siphonTurns = 0; healInvertTurns = 0; fogBlindTurns = 0; drainPct = 0
                        try { onClearVisualDrain() } catch (_: Throwable) {}
                        typeDialogue("🔮 Amulette", "Statuts, Siphon & Drain purifiés ! ✨"); refreshHud(); renderTurnControls()
                    }
                }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(4); topMargin = dp(4) })
                actionsBox.addView(r2)

                val r3 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                r3.addView(makeActionBtn("🕯️ Furie ×1.6 (${inv.incenses})", if (inv.incenses > 0) Color.rgb(190, 24, 93) else Color.rgb(52, 52, 66)) {
                    if (rpgState.consumeItem("incense")) {
                        relicsUsedThisFight++; furyTurns = 3; typeDialogue("🕯️ Encens de Furie", "Attaques ×1.6 pendant 3 tours !"); refreshHud(); renderTurnControls()
                    }
                }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(4); topMargin = dp(4) })
                r3.addView(makeActionBtn("🛡️ Égide (${inv.aegisVeils})", if (inv.aegisVeils > 0) Color.rgb(14, 116, 144) else Color.rgb(52, 52, 66)) {
                    if (rpgState.consumeItem("aegis")) {
                        relicsUsedThisFight++; aegisActive = true; typeDialogue("🛡️ Voile d'Égide", "Absorbe 100 % du prochain assaut !"); refreshHud(); renderTurnControls()
                    }
                }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(4); topMargin = dp(4) })
                actionsBox.addView(r3)
            } else {
                val r1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                r1.addView(makeActionBtn("💣 Bombe Posture (${inv.postureBombs})", if (inv.postureBombs > 0) Color.rgb(234, 88, 12) else Color.rgb(52, 52, 66)) {
                    if (rpgState.consumeItem("posture_bomb")) {
                        relicsUsedThisFight++; witchShield = false; witchThornCounter = false
                        val broke = applyPostureDamage(75)
                        stage.triggerWitchHitShake(critical = true)
                        typeDialogue("💣 BOMBE BRISE-POSTURE !", "-75 Posture Boss & Barrière/Épines brisées !${if (broke) " 💥 RUPTURE !" else ""}")
                        refreshHud(); renderTurnControls()
                    }
                }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(4) })
                r1.addView(makeActionBtn("💧 Eau Bénite (${inv.holyWaters})", if (inv.holyWaters > 0) Color.rgb(2, 132, 199) else Color.rgb(52, 52, 66)) {
                    if (rpgState.consumeItem("holy_water")) {
                        relicsUsedThisFight++; adaptiveBrain.resetAll(); battleWeather = BattleWeather.CLEAR; charmedTurns = 0; hypnoTurns = 0
                        typeDialogue("💧 EAU BÉNITE !", "Résistance Adaptative ennemie remise à 0 % & Météo dissipée !")
                        refreshHud(); renderTurnControls()
                    }
                }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(4) })
                actionsBox.addView(r1)

                val r2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                r2.addView(makeActionBtn("⚡ Élixir Éther +100 SP (${inv.etherElixirs})", if (inv.etherElixirs > 0) Color.rgb(192, 38, 211) else Color.rgb(52, 52, 66)) {
                    if (rpgState.consumeItem("ether_elixir")) {
                        relicsUsedThisFight++; sp = 100
                        WitchCombatSystem.SoundFx.play(sfxOn, "spirit")
                        typeDialogue("⚡ ÉLIXIR D'ÉTHER !", "100 SP instantanés — Surcharge Spirituelle prête !")
                        refreshHud(); renderTurnControls()
                    }
                }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(4); topMargin = dp(4) })
                r2.addView(makeActionBtn("⏳ Sablier Répit (${inv.hourglasses})", if (inv.hourglasses > 0) Color.rgb(180, 83, 9) else Color.rgb(52, 52, 66)) {
                    if (rpgState.consumeItem("hourglass")) {
                        relicsUsedThisFight++; witchStunTurns = witchStunTurns.coerceAtLeast(1); embrace = (embrace - 35).coerceAtLeast(0)
                        typeDialogue("⏳ SABLIER DE RÉPIT !", "${curWitch.def.name} est figée 1 tour & Emprise -35 % !")
                        refreshHud(); renderTurnControls()
                    }
                }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(4); topMargin = dp(4) })
                actionsBox.addView(r2)
            }

            val nav = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            nav.addView(makeActionBtn(if (page == 0) "▶ Reliques Rares (4)" else "◀ Reliques de Base (6)", Color.rgb(56, 44, 82)) {
                showItemsMenu(if (page == 0) 1 else 0)
            }, LinearLayout.LayoutParams(0, dp(34), 1.3f).apply { marginEnd = dp(4); topMargin = dp(4) })
            nav.addView(makeActionBtn("↩ Retour", Color.rgb(42, 36, 56)) { renderTurnControls() },
                LinearLayout.LayoutParams(0, dp(34), 0.7f).apply { topMargin = dp(4) })
            actionsBox.addView(nav)
        }

        fun showRpMenu() {
            actionsBox.removeAllViews()
            actionsBox.addView(makeActionBtn("🔥 Provoquer : « Tes lois ne valent rien ! » (-22 Posture & +24 SP)", Color.rgb(225, 29, 72)) {
                rpgState.adjustCorruption(-3)
                val dmgBoss = ((26 + playerLv / 2) * forgeAtkMul).toInt()
                witchResolve = (witchResolve - dmgBoss).coerceAtLeast(0); sp = (sp + 24 + forgeSpBonus).coerceAtMost(100)
                val broke = applyPostureDamage(22)
                evaluateActionVariety("RP_DEFY"); stage.triggerWitchHitShake()
                if (witchResolve <= 0) finishBattleVictory() else if (broke) {
                    speakAi("💥 PROVOCATION CRITIQUE !", EventKind.SHIELD_BREAK, extraFooter = "RUPTURE DE POSTURE ! Étourdie 2 tours !")
                    refreshHud(); renderTurnControls()
                } else {
                    speakAi(curWitch.profile.name, EventKind.RP_DEFY)
                    val r = Runnable { executeWitchTurn(1.22f) }; pendingTurnRunnable = r; mainHandler.postDelayed(r, 650L)
                }
            })
            actionsBox.addView(makeActionBtn("🤝 Pacte Risqué (+38 PV, +32 PM mais +18 % Emprise)", Color.rgb(14, 116, 144)) {
                rpgState.adjustCorruption(+3)
                hp = (hp + 38).coerceAtMost(maxHp); mp = (mp + 32).coerceAtMost(maxMp); embrace = (embrace + 18).coerceAtMost(98)
                evaluateActionVariety("RP_BARGAIN"); speakAi(curWitch.profile.name, EventKind.RP_BARGAIN); refreshHud(); renderTurnControls()
            }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) } })
            actionsBox.addView(makeActionBtn("🥺 S'incliner (-Colère, +Emprise)", Color.rgb(126, 34, 206)) {
                rpgState.adjustCorruption(+6); settings.adjustMood(-14)
                speakAi(curWitch.profile.name, EventKind.RP_SUBMIT)
                val r = Runnable { executeWitchTurn(1.42f) }; pendingTurnRunnable = r; mainHandler.postDelayed(r, 650L)
            }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) } })
            actionsBox.addView(makeActionBtn("↩ Retour", Color.rgb(42, 36, 56)) { renderTurnControls() }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply { topMargin = dp(5) }
            })
        }

        fun applyTouchSabotageToButton(btn: Button, allowDodge: Boolean, allowFrost: Boolean, realAction: () -> Unit) {
            val gaiaImmune = activeSpirit == ElementalSpirit.GAIA
            var dodgedOnce = false
            var remainingFrost = if (!gaiaImmune && allowFrost && frostButtonTaps > 0) frostButtonTaps else 0
            val origLabel = btn.text.toString()
            if (remainingFrost > 0) btn.text = "❄️[$remainingFrost] $origLabel"
            btn.setOnClickListener {
                if (turnLocked || inVideoTrance) return@setOnClickListener
                if (!gaiaImmune && allowDodge && dodgeButtonThisTurn && !dodgedOnce) {
                    dodgedOnce = true; onVibrate(30L)
                    val dx = (if (Random.nextBoolean()) 1f else -1f) * dp(26).toFloat()
                    btn.animate().translationX(dx).translationY(-dp(12).toFloat()).setDuration(110L).withEndAction {
                        btn.animate().translationX(0f).translationY(0f).setDuration(180L).start()
                    }.start()
                    speakerTv.text = "💨 ${curWitch.def.name} dévie ton doigt !"
                    return@setOnClickListener
                }
                if (remainingFrost > 0) {
                    remainingFrost--; onVibrate(20L)
                    if (remainingFrost > 0) { btn.text = "❄️[$remainingFrost] $origLabel"; return@setOnClickListener }
                    else btn.text = origLabel
                }
                realAction()
            }
        }

        // Optional Real-Time Turn Pressure Countdown during intense Phase 2/3!
        fun armTurnPressureIfIntense() {
            turnTimerRunnable?.let { mainHandler.removeCallbacks(it) }; turnTimerRunnable = null
            if (bossPhase < 2 || captured || inEndScene || inVideoTrance || witchStunTurns > 0) return
            val deadline = SystemClock.elapsedRealtime() + 15_000L
            val r = object : Runnable {
                override fun run() {
                    if (!sessionActive || inEndScene || inVideoTrance || turnLocked || captured) return
                    val rem = ((deadline - SystemClock.elapsedRealtime()) / 1000L).toInt()
                    if (rem <= 0) {
                        executeWitchTurn(1.10f, lawViolationNote = "⏱️ HÉSITATION PUNIE ! Tu as mis trop de temps à agir !")
                    } else {
                        if (rem <= 6) speakerTv.text = "⏳ Pression du Duel : ${rem}s pour décider !"
                        mainHandler.postDelayed(this, 1_000L)
                    }
                }
            }
            turnTimerRunnable = r
            mainHandler.postDelayed(r, 1_000L)
        }

        // ==================== MAIN TACTICAL CONTROLS ====================
        renderTurnControls = {
            if (sessionActive && !inEndScene && !inVideoTrance) {
                actionsBox.removeAllViews()
                val inv = rpgState.snapshot()
                val isCharmed = charmedTurns > 0

                if (captured) {
                    val r1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    val qteLabel = when (qteVarietyCounter % 5) {
                        0 -> "🎯 Briser (Timing)"
                        1 -> "🔮 Briser (Runes)"
                        2 -> "✨ Briser (Orbes)"
                        3 -> "🧠 Briser (Pulsation)"
                        else -> "⚡ Se débattre (QTE)"
                    }
                    r1.addView(makeActionBtn(qteLabel, Color.rgb(234, 88, 12)) { if (!turnLocked) startNextVariedQte() },
                        LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) })

                    if (inv.talismans > 0 && activeLaw != InquisitionLaw.NO_ITEMS) {
                        r1.addView(makeActionBtn("📜 Talisman (${inv.talismans})", Color.rgb(217, 119, 6)) {
                            if (rpgState.consumeItem("talisman")) { relicsUsedThisFight++; onQteSuccess(34) }
                        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4) })
                    } else {
                        r1.addView(makeActionBtn("🛡️ Endurer (+24 PM)", Color.rgb(37, 99, 235)) {
                            guarding = true; mp = (mp + 24).coerceAtMost(maxMp); executeWitchTurn(0.80f)
                        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4) })
                    }
                    actionsBox.addView(r1)

                    val r2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    r2.addView(makeActionBtn("🎒 Reliques (${inv.totalItems})", Color.rgb(79, 70, 229)) { showItemsMenu(0) },
                        LinearLayout.LayoutParams(0, dp(38), 0.45f).apply { marginEnd = dp(4); topMargin = dp(5) })
                    r2.addView(makeActionBtn("💖 Céder au Drainage Final", Color.rgb(162, 28, 175)) {
                        embrace = 100; hp = 0; startCinematicDrainEndScene()
                    }, LinearLayout.LayoutParams(0, dp(38), 0.55f).apply { marginStart = dp(4); topMargin = dp(5) })
                    actionsBox.addView(r2)
                } else {
                    if (mimicTrapThisTurn) {
                        actionsBox.addView(makeActionBtn("✨ COUP CRITIQUE GRATUIT ! (Touche-moi 💖)", Color.rgb(192, 38, 211)) {
                            if (turnLocked) return@makeActionBtn
                            mimicTrapThisTurn = false; embrace = (embrace + 22).coerceAtMost(100); onVibrate(75L)
                            typeDialogue("😈 PIÈGE D'ENVOÛTEMENT !", "« Hihi ! Tu as cliqué sur mon leurre tactile ! +22 % Emprise ! 💖 »")
                            val r = Runnable { executeWitchTurn(1.25f) }; pendingTurnRunnable = r; mainHandler.postDelayed(r, 650L)
                        }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply { bottomMargin = dp(5) } })
                    }

                    val strikeForbidden = activeLaw == InquisitionLaw.NO_STRIKE && lawTurns > 0
                    val artsBtn = makeActionBtn(
                        when {
                            strikeForbidden -> "⚖️ Arts Épée (LOI !)"
                            isCharmed -> "💖 Obéir (Charmé !)"
                            witchThornCounter -> "🌹 Arts (ÉPINES !)"
                            else -> "⚔️ Arts de l'Épée ▾"
                        },
                        when {
                            strikeForbidden -> Color.rgb(120, 53, 15)
                            isCharmed -> Color.rgb(192, 38, 211)
                            witchThornCounter -> Color.rgb(159, 18, 57)
                            else -> Color.rgb(225, 29, 72)
                        }
                    ) {}
                    applyTouchSabotageToButton(artsBtn, allowDodge = true, allowFrost = true) {
                        if (isCharmed) {
                            charmedTurns = 0
                            if (curWitch.def.archetype == WitchCatalog.Archetype.CHARMER && Random.nextBoolean()) {
                                witchResolve = (witchResolve + 35).coerceAtMost(witchMaxResolve)
                                typeDialogue("💖 ENVOÛTEMENT !", "Sous son charme, ton coup hésite et nourrit son aura (+35 Boss) ! Utilise 💧 Ondine ou 🧘 Méditation !")
                                val r = Runnable { executeWitchTurn(1.15f) }; pendingTurnRunnable = r; mainHandler.postDelayed(r, 650L)
                            } else {
                                executeWitchTurn(1.25f)
                            }
                        } else {
                            showSwordArtsMenu()
                        }
                    }

                    val spiritsBtn = makeActionBtn(
                        when {
                            sp >= 100 && activeSpirit != ElementalSpirit.NONE -> "⚡ Esprits · SURCHARGE !"
                            sp >= 100 -> "💥 Esprits · JUGEMENT !"
                            sp >= 50 -> "🌟 Esprits · EXORCISME !"
                            else -> "🌀 Esprits & Magie ▾"
                        },
                        if (sp >= 50) Color.rgb(217, 119, 6) else Color.rgb(14, 116, 144)
                    ) { if (!turnLocked) showSpiritsAndMagicMenu() }

                    val guardForbidden = activeLaw == InquisitionLaw.NO_GUARD && lawTurns > 0
                    val guardHighlight = !guardForbidden && fogBlindTurns == 0 && (plannedIntent.attack.kind == AttackKind.ULTIMATE || plannedIntent.attack.kind == AttackKind.TIME_DOUBLE || plannedIntent.attack.kind == AttackKind.ARMOR_BREAK)
                    val guardBtn = makeActionBtn(
                        when {
                            guardForbidden -> "⚖️ Garde (LOI)"
                            guardHighlight -> "🛡️ PARADE ! (+24 PM)"
                            else -> "🛡️ Garde (+24 PM)"
                        },
                        when {
                            guardForbidden -> Color.rgb(120, 53, 15)
                            guardHighlight -> Color.rgb(2, 132, 199)
                            else -> Color.rgb(37, 99, 235)
                        }
                    ) {}
                    applyTouchSabotageToButton(guardBtn, allowDodge = dominanceTierFor(playerLv) >= 2, allowFrost = false) {
                        if (checkLawViolation(InquisitionLaw.NO_GUARD)) return@applyTouchSabotageToButton
                        guarding = true; mp = (mp + 24).coerceAtMost(maxMp); evaluateActionVariety("GUARD", isOffensive = false); executeWitchTurn(1.0f)
                    }

                    val bagLocked = activeLaw == InquisitionLaw.NO_ITEMS && lawTurns > 0
                    val itemsBtn = makeActionBtn(
                        if (bagLocked) "🔒 Sac (${lawTurns}t)" else "🎒 Sac (${inv.totalItems})",
                        if (bagLocked) Color.rgb(65, 55, 72) else Color.rgb(79, 70, 229)
                    ) { if (!turnLocked) showItemsMenu(0) }
                    val rpBtn = makeActionBtn("💬 RP", Color.rgb(126, 34, 206)) { if (!turnLocked) showRpMenu() }
                    val yieldBtn = makeActionBtn("💖", Color.rgb(98, 22, 108)) {
                        if (!turnLocked) { embrace = 100; hp = 0; startCinematicDrainEndScene() }
                    }

                    val topPair = if (scrambleButtonsThisTurn) listOf(spiritsBtn, artsBtn) else listOf(artsBtn, spiritsBtn)
                    val r1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    r1.addView(topPair[0], LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) })
                    r1.addView(topPair[1], LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4) })
                    actionsBox.addView(r1)

                    val botRow = if (scrambleButtonsThisTurn) listOf(itemsBtn to 0.85f, guardBtn to 1.0f, yieldBtn to 0.36f, rpBtn to 0.56f)
                    else listOf(guardBtn to 1.05f, itemsBtn to 0.78f, rpBtn to 0.56f, yieldBtn to 0.36f)
                    val r2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    botRow.forEachIndexed { idx, (btn, w) ->
                        r2.addView(btn, LinearLayout.LayoutParams(0, dp(38), w).apply { if (idx > 0) marginStart = dp(4); topMargin = dp(5) })
                    }
                    actionsBox.addView(r2)
                    armTurnPressureIfIntense()
                }
            }
        }

        onMountFullscreen(root)
        refreshHud()

        val recSpirit = WitchCombatSystem.recommendedSpiritFor(curWitch.def)
        val introExtra = buildString {
            append("📌 ${WitchCombatAi.archetypePassiveSummary(curWitch.def.archetype)}")
            append("\n${WitchCombatAi.tacticalWeaknessLabel(curWitch.def.archetype)} • Esprit conseillé : ${recSpirit.displayName}")
            if (battleMods.introContextLine.isNotBlank()) append("\n${battleMods.introContextLine}")
            if (battleMods.surpriseBossDmg > 0) append("\n⚡ Attaque surprise : -${battleMods.surpriseBossDmg} Résolution !")
            if (initialThreatApp != null && allowMidAppPowers && DemonMdmManager.hasPrivilegedControl(context)) {
                append("\n👁️ Elle guette ton appli « $initialThreatApp »…")
            }
        }

        fun beginActualBattleTurn() {
            speakAi(curWitch.profile.name, EventKind.INTRO, extraFooter = introExtra)
            renderTurnControls()
            onVibrate(65L)
            if (battleMods.forcedAppRaid || dominanceTierFor(playerLv) >= 3) {
                mainHandler.postDelayed({
                    if (sessionActive && !inEndScene) triggerMidBattleAppHostage("🚨 PLEINS POUVOIRS DU COVEN :")
                }, 1_600L)
            }
        }

        // Pre-Battle 3-Beat Visual Novel Story Cutscene (1-tap readable or skippable!)
        val cutscene = WitchLoreAndCampaign.buildPreBattleCutscene(
            def = curWitch.def,
            bondLevel = rpgState.getBondLevel(curWitch.def.id),
            corruption = rSnap0.corruption,
            demonName = cfg.avatarName,
            threatApp = initialThreatApp
        )
        var beatIdx = 0
        fun renderCutsceneBeat() {
            if (!sessionActive || inEndScene) return
            val (spkr, txt) = cutscene.beats[beatIdx]
            typeDialogue(spkr, txt)
            actionsBox.removeAllViews()
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val nextLabel = if (beatIdx < cutscene.beats.size - 1) "Suite ▶ (${beatIdx + 1}/${cutscene.beats.size})" else "⚔️ ENGAGER LE DUEL !"
            row.addView(makeActionBtn(nextLabel, Color.rgb(162, 28, 175)) {
                beatIdx++
                if (beatIdx >= cutscene.beats.size) beginActualBattleTurn() else renderCutsceneBeat()
            }, LinearLayout.LayoutParams(0, dp(42), 1.35f).apply { marginEnd = dp(4) })
            row.addView(makeActionBtn("⏩ Passer", Color.rgb(48, 40, 68)) {
                beginActualBattleTurn()
            }, LinearLayout.LayoutParams(0, dp(42), 0.65f).apply { marginStart = dp(4) })
            actionsBox.addView(row)
        }
        renderCutsceneBeat()
    }

    private fun makeActionBtn(label: String, bgColor: Int, onClick: () -> Unit): Button = Button(context).apply {
        text = label; isAllCaps = false; textSize = 10.9f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(11).toFloat(); setColor(bgColor); setStroke(dp(1), Color.argb(85, 255, 255, 255))
        }
        minHeight = 0; minimumHeight = 0; setPadding(dp(5), dp(6), dp(5), dp(6)); setOnClickListener { onClick() }
    }
}
