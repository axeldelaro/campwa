package com.demonpet.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.demonpet.app.ai.WitchSpriteEngine
import com.demonpet.app.ai.WitchSpriteEngine.AttackKind
import com.demonpet.app.config.DemonConfigSnapshot
import com.demonpet.app.config.DemonSettings
import com.demonpet.app.mdm.DemonMdmManager
import com.demonpet.app.media.MediaFolderHelper
import com.demonpet.app.rpg.WitchAiDialogueEngine
import com.demonpet.app.rpg.WitchAiDialogueEngine.EventKind
import com.demonpet.app.rpg.WitchCatalog
import com.demonpet.app.rpg.WitchRpgState
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * Deep Multi-Phase 50-Witch Monster Girl Quest-Inspired RPG Battle & Cinematic End-Scene Engine (v6.0):
 * - Long, intense, strategic battles with NO button-spam:
 *   • Live Witch Intention Telegraph (read & counter her next spell!)
 *   • Combo Multiplier (x1.0 -> x2.0) + Anti-Spam Read Counter (repeating 3x lets her read & counter you!)
 *   • Boss Multi-Phase Enrage (Phase 2 Barrier + Domination Aura at 55%, Phase 3 / Duo Ally at 25%)
 *   • 6+ Witch Special Spells & Enchantments (Charme, Hypnose, Siphon, Barrière Runique, Mirage, Sceau, Double Temps, Capture, Ultime)
 *   • 6 Player Tactical Skills (Frappe, Lame Brise-Bouclier, Contre-Sceau, Méditation Anti-Charme, Garde/Parade Parfaite, Exorcisme 50 SP, Jugement 100 SP)
 *   • 6 Consumable Relics + 4 Permanent Soul Forge Upgrades
 *   • 3 Varied Phase-2 Capture Escape Mini-Games (Rapid Mash, Critical Timing Gauge, 3-Rune Exorcism Sequence!)
 *   • Infinite Witch Tower mode (carry-over gauges & escalating multipliers!)
 * - 100% Crash-Free 5-Step Cinematic Drain End-Scene (zero HWUI bitmap recycle crash, live Shizuku app-greying report, tactical MGQ post-mortem!).
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
    private var typewriterRunnable: Runnable? = null
    private var qteTickRunnable: Runnable? = null
    private var pendingTurnRunnable: Runnable? = null

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
        val def = if (isBossFloor) {
            WitchCatalog.bossOfChapter(targetChapter)
        } else {
            WitchCatalog.randomWitchForChapter(targetChapter, includeBoss = false)
        }
        launchBattleForWitch(cfg, def, towerFloor = floor, carryHp = carryHp, carryMp = carryMp, carrySp = carrySp, carryEmbrace = carryEmbrace)
    }

    fun launchBattleForWitch(
        cfg: DemonConfigSnapshot,
        witchDef: WitchCatalog.WitchDef,
        towerFloor: Int = 0,
        carryHp: Int = -1,
        carryMp: Int = -1,
        carrySp: Int = 25,
        carryEmbrace: Int = 0
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

                mainHandler.post {
                    if (!sessionActive) return@post
                    activeWitch = witch1
                    secondWitch = witch2
                    mountBattleUi(witch1, witch2, cfg, towerFloor, carryHp, carryMp, carrySp, carryEmbrace)
                }
            } catch (_: Throwable) {
                sessionActive = false
            }
        }.start()
    }

    fun forceCleanup() {
        sessionActive = false
        typewriterRunnable?.let { mainHandler.removeCallbacks(it) }
        typewriterRunnable = null
        qteTickRunnable?.let { mainHandler.removeCallbacks(it) }
        qteTickRunnable = null
        pendingTurnRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingTurnRunnable = null
        try { activeStageRef?.clearBitmaps() } catch (_: Throwable) {}
        activeStageRef = null
        activeWitch = null
        secondWitch = null
    }

    private fun obedienceRankFor(level: Int): String = when {
        level >= 25 -> "Rebelle"
        level >= 15 -> "Apprenti"
        level >= 8 -> "Captif"
        level >= 3 -> "Serviteur"
        else -> "Soumis Absolu"
    }

    private fun mountBattleUi(
        initialWitch: WitchSpriteEngine.LoadedWitch,
        reinforcement: WitchSpriteEngine.LoadedWitch?,
        cfg: DemonConfigSnapshot,
        towerFloor: Int,
        carryHp: Int,
        carryMp: Int,
        carrySp: Int,
        carryEmbrace: Int
    ) {
        var curWitch = initialWitch
        var duoSpawned = false
        val snap0 = settings.snapshot()
        val rSnap0 = rpgState.snapshot()
        val apiKey = rSnap0.geminiApiKey

        // Player Gauges scaled by Soul Forge upgrades!
        val maxHp = rSnap0.maxHp
        val maxMp = rSnap0.maxMp
        val forgeAtkMul = rSnap0.attackMultiplier
        val forgeEmbraceResist = rSnap0.embraceResistMult
        val forgeSpBonus = rSnap0.spBonusPerAction
        val wardBonus = rSnap0.wardRank

        var playerLv = snap0.playerLevel
        var hp = if (carryHp > 0) carryHp.coerceIn(35, maxHp) else maxHp
        var mp = if (carryMp >= 0) carryMp.coerceIn(15, maxMp) else maxMp
        var sp = carrySp.coerceIn(0, 100)
        var embrace = carryEmbrace.coerceIn(0, 80)

        // Witch Gauges & Multi-Phase State
        val towerScale = if (towerFloor > 1) 1f + (towerFloor - 1) * 0.14f else 1f
        var witchMaxResolve = (curWitch.profile.maxResolve * towerScale).toInt().coerceAtLeast(150)
        var witchResolve = witchMaxResolve
        var bossPhase = 1                    // 1 -> 2 (<=55%) -> 3 (<=25% on Boss/MiniBoss)
        var witchStunTurns = 0
        var witchShield = false              // Broken by Lame Spirituelle / Exorcisme / Jugement!
        var witchMirage = false              // Pierced by Méditation / Exorcisme / Jugement!
        var dominationAura = false           // +6% Emprise per turn in Boss Phase 2/3!

        // Player Tactical Stances, Buffs & Statuses
        var captured = false
        var guarding = false
        var counterStance = false
        var meditateImmunity = false
        var aegisActive = false
        var furyTurns = 0                    // Encens de Furie (x1.6 dmg for 3 turns!)

        var charmedTurns = 0
        var hypnoTurns = 0
        var sealedTurns = 0
        var siphonTurns = 0                  // Drains HP & heals Witch every turn until purified!

        // Anti-Spam & Combo Engine
        var lastActionKey = ""
        var repeatCount = 0
        var comboMult = 1.0f                 // 1.0 -> 1.2 -> 1.45 -> 1.7 -> 2.0
        var turnNumber = 1
        var spamPenaltyCount = 0
        var missedUltimateGuards = 0
        var qteFails = 0

        var drainPct = snap0.activeDrainPct
        var inEndScene = false
        var turnLocked = false
        var dialogueEpoch = 0
        var qteVarietyCounter = 0

        // Planned Witch Next Action (Telegraphed live in HUD!)
        fun pickNextWitchIntent(): WitchSpriteEngine.WitchAttack {
            val pool = curWitch.profile.attacks
            if (pool.isEmpty()) return curWitch.profile.ultimateAttack
            val ultRate = when {
                bossPhase >= 3 -> 0.34f
                curWitch.def.isBoss -> 0.25f
                turnNumber >= 4 && turnNumber % 4 == 0 -> 0.55f
                else -> 0.18f
            }
            if (!captured && turnNumber >= 2 && Random.nextFloat() < ultRate) {
                return curWitch.profile.ultimateAttack
            }
            // Avoid redundant shield/mirage if already active
            val filtered = pool.filter { a ->
                !(a.kind == AttackKind.BARRIER_SHIELD && witchShield) &&
                    !(a.kind == AttackKind.MIRAGE_CLONE && witchMirage) &&
                    !(a.kind == AttackKind.SIPHON_DRAIN && siphonTurns > 0)
            }
            return if (filtered.isNotEmpty()) filtered.random() else pool.random()
        }

        var plannedNextAttack: WitchSpriteEngine.WitchAttack = pickNextWitchIntent()

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.rgb(10, 6, 16))
        }

        // Layer 1: Fullscreen 60 FPS Live2D-lite Stage (Crash-Free!)
        val stage = WitchSpriteStageView(context).apply {
            setWitchBitmaps(curWitch.spriteBitmap, curWitch.backdropBitmap)
            hpRatio = hp.toFloat() / maxHp
            drainIntensity = drainPct / 100f
        }
        activeStageRef = stage
        root.addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        fun closeBattleAndDismiss() {
            // SAFE ORDER: detach view & clear bitmaps FIRST (zero HWUI recycled-bitmap crash!)
            sessionActive = false
            typewriterRunnable?.let { mainHandler.removeCallbacks(it) }
            typewriterRunnable = null
            qteTickRunnable?.let { mainHandler.removeCallbacks(it) }
            qteTickRunnable = null
            pendingTurnRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingTurnRunnable = null
            try { stage.clearBitmaps() } catch (_: Throwable) {}
            try { onDismissFullscreen() } catch (_: Throwable) {}
            activeStageRef = null
            activeWitch = null
            secondWitch = null
        }

        fun closeBattleAndNotify() {
            closeBattleAndDismiss()
            try { onBattleFinished?.invoke() } catch (_: Throwable) {}
        }

        // Layer 2: Rich Tactical Top RPG HUD (20-tap on NV bar = secret unlock 100205!)
        val topHud = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(34), dp(11), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(202, 14, 8, 26))
                setStroke(dp(1), Color.argb(135, 217, 70, 239))
            }
        }
        val bossTitleTv = TextView(context).apply {
            setTextColor(Color.rgb(245, 208, 254))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        var emergTaps = 0
        var emergTapMs = 0L
        val playerStatsTv = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, dp(2))
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
            setTextColor(Color.rgb(253, 164, 175))
            textSize = 10.8f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        val intentBannerTv = TextView(context).apply {
            setTextColor(Color.rgb(253, 224, 71))
            textSize = 11.2f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(4), dp(6), dp(2))
        }
        topHud.addView(bossTitleTv)
        topHud.addView(playerStatsTv)
        topHud.addView(bossBarTv)
        topHud.addView(intentBannerTv)
        root.addView(topHud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Layer 3: Bottom Visual Novel AI Dialogue Box + Tactical Commands
        val bottomPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(9), dp(11), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.argb(236, 15, 9, 26))
                setStroke(dp(2), Color.argb(200, 217, 70, 239))
            }
        }
        val speakerTv = TextView(context).apply {
            setTextColor(Color.rgb(244, 114, 182))
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
        }
        val dialogueTv = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12.4f
            minLines = 3
            setPadding(0, dp(3), 0, dp(7))
        }
        val actionsBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        bottomPanel.addView(speakerTv)
        bottomPanel.addView(dialogueTv)
        bottomPanel.addView(actionsBox)

        root.addView(
            bottomPanel,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                marginStart = dp(9); marginEnd = dp(9); bottomMargin = dp(9)
            }
        )

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
                    if (idx < fullText.length) {
                        mainHandler.postDelayed(this, 16L)
                    }
                }
            }
            typewriterRunnable = run
            mainHandler.post(run)
        }

        fun speakAi(
            speaker: String,
            event: EventKind,
            skillName: String = "",
            extraFooter: String = "",
            affectedApp: String? = null
        ) {
            dialogueEpoch++
            val myEpoch = dialogueEpoch
            val instant = WitchAiDialogueEngine.generateInstantLine(
                witch = curWitch.def,
                event = event,
                skillName = skillName,
                playerLevel = playerLv,
                embracePct = embrace,
                affectedApp = affectedApp
            )
            val fullLocal = if (extraFooter.isNotBlank()) "$instant\n$extraFooter" else instant
            typeDialogue(speaker, fullLocal)

            if (apiKey.isNotBlank()) {
                WitchAiDialogueEngine.generateLiveAiLineIfConfigured(
                    apiKey = apiKey,
                    witch = curWitch.def,
                    event = event,
                    skillName = skillName,
                    playerLevel = playerLv,
                    embracePct = embrace
                ) { aiLine ->
                    mainHandler.post {
                        if (sessionActive && dialogueEpoch == myEpoch) {
                            typewriterRunnable?.let { mainHandler.removeCallbacks(it) }
                            val combined = if (extraFooter.isNotBlank()) "$aiLine\n$extraFooter" else aiLine
                            speakerTv.text = "$speaker • ✨ IA"
                            dialogueTv.text = combined
                        }
                    }
                }
            }
        }

        fun buildStatusSummary(): String {
            val parts = ArrayList<String>()
            if (charmedTurns > 0) parts.add("💖Charmé($charmedTurns)")
            if (hypnoTurns > 0) parts.add("🌀Hypno($hypnoTurns)")
            if (sealedTurns > 0) parts.add("🔒Scellé($sealedTurns)")
            if (siphonTurns > 0) parts.add("🩸Siphon($siphonTurns)")
            if (furyTurns > 0) parts.add("🕯️Furie($furyTurns)")
            if (aegisActive) parts.add("🛡️Égide")
            return if (parts.isEmpty()) "" else " • " + parts.joinToString(" ")
        }

        fun refreshHud() {
            if (!sessionActive) return
            val towerTag = if (towerFloor > 0) "🗼Étage $towerFloor • " else ""
            val privTag = when {
                DemonMdmManager.isShizukuActive() -> " • ⚡SHIZUKU"
                DemonMdmManager.isDeviceOwner(context) -> " • 👑MDM"
                else -> ""
            }
            val phaseStr = if (bossPhase > 1) " [P$bossPhase]" else ""
            bossTitleTv.text = "$towerTag#${curWitch.def.id}/50 ${curWitch.profile.name}$phaseStr (NV ${curWitch.profile.bossLevel})$privTag"

            val comboTag = if (comboMult > 1.05f) String.format(" • 🔥×%.1f", comboMult) else ""
            playerStatsTv.text = "NV $playerLv (${obedienceRankFor(playerLv)}) | PV $hp/$maxHp | PM $mp/$maxMp | SP $sp$comboTag${buildStatusSummary()}"

            val bossPct = ((witchResolve * 100f) / witchMaxResolve.coerceAtLeast(1)).toInt().coerceIn(0, 100)
            val wTags = ArrayList<String>()
            if (witchStunTurns > 0) wTags.add("💫ÉTOURDIE")
            if (witchShield) wTags.add("🛡️BARRIÈRE")
            if (witchMirage) wTags.add("🪞MIRAGE")
            if (dominationAura) wTags.add("👑AURA+")
            val wTagStr = if (wTags.isEmpty()) "" else " • " + wTags.joinToString(" ")
            bossBarTv.text = "Tour $turnNumber | Boss $witchResolve/$witchMaxResolve ($bossPct%) | Emprise $embrace%$wTagStr"

            // Update 60 FPS Stage FX
            stage.hpRatio = (hp.toFloat() / maxHp).coerceIn(0f, 1f)
            stage.drainIntensity = (drainPct / 100f).coerceAtLeast(if (captured) 0.48f else 0f)
            stage.shieldActive = witchShield
            stage.mirrorCloneActive = witchMirage
            stage.enrageAuraActive = bossPhase >= 2
            stage.hypnoticSpiralActive = hypnoTurns > 0 || charmedTurns > 0
            stage.captureZoomFactor = when {
                inEndScene -> 1.0f
                captured -> 0.68f
                embrace >= 65 -> 0.35f
                else -> 0f
            }

            // Live Intention Telegraph Banner!
            intentBannerTv.text = when {
                inEndScene -> "💋 SCÈNE FINALE — DRAINAGE D'ÉNERGIE & MALÉDICTION"
                captured -> "⛓️ CAPTURÉ (Phase 2) ! Réussis l'épreuve d'évasion ou utilise un 📜 Talisman !"
                witchStunTurns > 0 -> "💫 ${curWitch.def.name} est ÉTOURDIE ! Profite de cette ouverture pour tes plus gros coups (×1.5) !"
                else -> "🎯 Intention : ${plannedNextAttack.telegraphHint}"
            }
            intentBannerTv.setTextColor(
                when {
                    plannedNextAttack.kind == AttackKind.ULTIMATE && !inEndScene && witchStunTurns == 0 -> Color.rgb(252, 165, 165)
                    witchStunTurns > 0 -> Color.rgb(134, 239, 172)
                    else -> Color.rgb(253, 224, 71)
                }
            )
        }

        lateinit var renderTurnControls: () -> Unit

        // Registers action variety for Combo Multiplier & Anti-Spam Read Counter!
        fun evaluateActionVariety(actionKey: String): Pair<Float, Boolean> {
            if (actionKey == lastActionKey) {
                repeatCount++
            } else {
                lastActionKey = actionKey
                repeatCount = 1
                comboMult = (comboMult + 0.22f).coerceAtMost(2.0f)
            }
            val spammed = repeatCount >= 3 && (actionKey == "STRIKE" || actionKey == "BLADE")
            if (spammed) {
                comboMult = 1.0f
                spamPenaltyCount++
                return 0.65f to true
            }
            return comboMult to false
        }

        // ==================== VICTORY ====================
        fun finishBattleVictory() {
            if (inEndScene) return
            inEndScene = true
            turnLocked = true
            qteTickRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingTurnRunnable?.let { mainHandler.removeCallbacks(it) }

            val towerMult = if (towerFloor > 0) 1.0f + towerFloor * 0.25f else 1.0f
            if (towerFloor > 0) rpgState.recordTowerFloor(towerFloor)
            val (levelUps, unlockedNewCh, droppedRelic) = rpgState.recordVictory(curWitch.def, bonusMultiplier = towerMult)
            val totalLvGain = (if (curWitch.def.isBoss) 2 else 1) + levelUps
            settings.adjustPlayerStats(levelDelta = totalLvGain, hpDelta = 100, drainDelta = -100)
            settings.adjustMood(-20)
            try { onClearVisualDrain() } catch (_: Throwable) {}

            val rewardParts = ArrayList<String>()
            rewardParts.add("+$totalLvGain NV")
            rewardParts.add("+${(curWitch.def.rewardXp * towerMult).toInt()} XP")
            rewardParts.add("+${(curWitch.def.rewardGold * towerMult).toInt()} 💎")
            if (droppedRelic != null) rewardParts.add("🎁 +1 $droppedRelic")
            if (unlockedNewCh) rewardParts.add("🗺️ Chapitre ${curWitch.def.chapter + 1} DÉBLOQUÉ !")
            val rewardFooter = "🏆 Récompenses : ${rewardParts.joinToString(" • ")}"

            speakAi("✨ VICTOIRE — #${curWitch.def.id}/50 VAINCUE !", EventKind.VICTORY, extraFooter = rewardFooter)
            actionsBox.removeAllViews()

            if (towerFloor > 0) {
                val nextFloor = towerFloor + 1
                val nextHp = (hp + 30).coerceAtMost(maxHp)
                val nextMp = (mp + 24).coerceAtMost(maxMp)
                val nextSp = sp.coerceAtLeast(25)
                val nextEmb = (embrace - 30).coerceAtLeast(0)

                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(makeActionBtn("🗼 Étage $nextFloor (+${((1f + nextFloor * 0.25f) * 100).toInt()}% 💎)", Color.rgb(217, 119, 6)) {
                    closeBattleAndDismiss()
                    mainHandler.postDelayed({
                        launchTowerRun(settings.snapshot(), floor = nextFloor, carryHp = nextHp, carryMp = nextMp, carrySp = nextSp, carryEmbrace = nextEmb)
                    }, 220L)
                }, LinearLayout.LayoutParams(0, dp(42), 1.3f).apply { marginEnd = dp(4) })
                row.addView(makeActionBtn("🏆 Quitter & Garder", Color.rgb(16, 128, 96)) {
                    closeBattleAndNotify()
                }, LinearLayout.LayoutParams(0, dp(42), 0.8f).apply { marginStart = dp(4) })
                actionsBox.addView(row)
            } else {
                actionsBox.addView(makeActionBtn("🏆 Continuer l'aventure", Color.rgb(16, 185, 129)) {
                    closeBattleAndNotify()
                    onSayPet("Bien joué ! ${curWitch.def.name} vaincue 🏆")
                })
            }

            Thread {
                try {
                    val healedApp = DemonMdmManager.healOneSuspendedApp(context)
                    if (healedApp != null) {
                        mainHandler.post {
                            if (sessionActive) {
                                dialogueTv.text = "${dialogueTv.text}\n🕊️ Shizuku/MDM : « $healedApp » est dégrisée & libérée !"
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }.start()
        }

        // ==================== PHASE 3 : 100% CRASH-FREE 5-STEP CINEMATIC DRAIN END-SCENE ====================
        fun startCinematicDrainEndScene() {
            if (inEndScene) return
            inEndScene = true
            turnLocked = true
            qteTickRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingTurnRunnable?.let { mainHandler.removeCallbacks(it) }

            stage.captureZoomFactor = 1.0f
            stage.drainIntensity = 1.0f
            stage.hypnoticSpiralActive = true
            try { onApplyVisualDrain(85, cfg) } catch (_: Throwable) {}
            try { rpgState.recordDefeat(curWitch.def) } catch (_: Throwable) {}
            refreshHud()

            val severe = playerLv <= 6 || cfg.chaosLevel >= 8 || curWitch.def.isBoss
            val totalLevelsLost = when {
                playerLv <= 3 -> 1
                curWitch.def.isBoss -> Random.nextInt(3, 6)
                else -> Random.nextInt(2, 4)
            }
            val targetLv = (playerLv - totalLevelsLost).coerceAtLeast(1)

            // Apply Trophy Wallpaper + REAL Shizuku/MDM app greying safely on background thread
            val wPath = curWitch.sourceFilePath
            val wUri = curWitch.sourceUri
            val mdmSummaryRef = AtomicReference("⏳ Application du sceau système Shizuku/MDM…")
            val mdmAffectedAppRef = AtomicReference<String?>(null)
            var currentStepIndex = 0

            Thread {
                try {
                    if (cfg.wallpaperPrankEnabled && (wPath != null || wUri != null)) {
                        MediaFolderHelper.applySpecificWallpaper(context, cfg, wPath, wUri)
                    }
                } catch (_: Throwable) {}
                try {
                    val rep = DemonMdmManager.applyDefeatMdmDamage(context, playerLv, severe)
                    mdmSummaryRef.set(rep.summary)
                    mdmAffectedAppRef.set(rep.affectedAppLabel)
                    mainHandler.post {
                        if (sessionActive && currentStepIndex == 1) {
                            speakAi(
                                speaker = "🔒 Malédiction Système Active (2/5)",
                                event = EventKind.DRAIN_STAGE_2,
                                extraFooter = rep.summary,
                                affectedApp = rep.affectedAppLabel
                            )
                        }
                    }
                } catch (t: Throwable) {
                    mdmSummaryRef.set("⚠️ Sceau système : ${t.message.orEmpty()}")
                }
            }.start()

            // Smooth cascading Level Drain animation
            val drainStep = object : Runnable {
                override fun run() {
                    if (!sessionActive) return
                    try {
                        if (playerLv > targetLv) {
                            playerLv--
                            settings.adjustPlayerStats(levelDelta = -1, hpDelta = 0, drainDelta = +8)
                            onVibrate(65L)
                            stage.triggerWitchAttackLunge(intense = true)
                            refreshHud()
                            mainHandler.postDelayed(this, 430L)
                        }
                    } catch (_: Throwable) {}
                }
            }
            mainHandler.postDelayed(drainStep, 350L)

            val totalSteps = 5
            val dynamicAdvice = buildString {
                append(curWitch.profile.mgqAdviceLine)
                when {
                    spamPenaltyCount > 0 -> append("\n💡 Analyse : tu as répété la même attaque $spamPenaltyCount× — varie entre Frappe, Lame, Garde et Méditation pour monter ton Combo ×2.0 !")
                    missedUltimateGuards > 0 -> append("\n💡 Analyse : tu as encaissé son Sort Ultime sans 🛡️ Garde ! Une Parade Parfaite l'étourdit et donne +30 SP.")
                    qteFails > 0 -> append("\n💡 Analyse : une Capture t'a coûté cher — garde un 📜 Talisman ou utilise ⚡ Contre-Sceau quand elle annonce une Capture !")
                    siphonTurns > 0 -> append("\n💡 Analyse : sa Marque de Siphon l'a soignée chaque tour — purifie-la avec 🔮 Amulette ou 🌟 Exorcisme !")
                }
            }

            fun showSceneStep() {
                if (!sessionActive) return
                try {
                    stage.triggerWitchAttackLunge(intense = currentStepIndex < 3)
                    onVibrate(50L)
                    when (currentStepIndex) {
                        0 -> speakAi(
                            speaker = "💋 ${curWitch.profile.name} — Emprise Totale (1/5)",
                            event = EventKind.DRAIN_STAGE_1,
                            extraFooter = "📉 Perte de -$totalLevelsLost NV • Luminosité & qualité drainées !"
                        )
                        1 -> speakAi(
                            speaker = "🔒 Malédiction Système (2/5)",
                            event = EventKind.DRAIN_STAGE_2,
                            extraFooter = mdmSummaryRef.get(),
                            affectedApp = mdmAffectedAppRef.get()
                        )
                        2 -> {
                            if (targetLv <= 1) {
                                typeDialogue(
                                    "💀 BAD END ULTIME — NIVEAU 1 (3/5)",
                                    "Ton Niveau atteint 1 (Soumis Absolu) ! Ton mana est totalement épuisé face à ${curWitch.profile.name}. Son aura règne sur tout l'écran ! 👑💜"
                                )
                            } else {
                                speakAi(
                                    speaker = "🌙 Transe du Coven (3/5)",
                                    event = EventKind.DRAIN_STAGE_3
                                )
                            }
                        }
                        3 -> speakAi(
                            speaker = "👑 Trophée de ${curWitch.def.name} (4/5)",
                            event = EventKind.DRAIN_STAGE_4,
                            extraFooter = "🎁 Consolation : +15 💎 (améliore ton équipement dans 🎒 Sac & Forge !)"
                        )
                        else -> typeDialogue("🔮 Oracle Post-Défaite MGQ (5/5)", dynamicAdvice)
                    }

                    actionsBox.removeAllViews()
                    if (currentStepIndex < totalSteps - 1) {
                        actionsBox.addView(makeActionBtn("Suite ▶ (${currentStepIndex + 1}/$totalSteps)", Color.rgb(162, 28, 175)) {
                            currentStepIndex++
                            showSceneStep()
                        })
                    } else {
                        val rematchDef = curWitch.def
                        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                        row.addView(makeActionBtn("⚔️ Revanche !", Color.rgb(225, 29, 72)) {
                            settings.update { putInt("player_hp", 100) }
                            closeBattleAndDismiss()
                            mainHandler.postDelayed({ launchBattleForWitch(settings.snapshot(), rematchDef, towerFloor = 0) }, 240L)
                        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })

                        row.addView(makeActionBtn("😈 Accepter le Sort", Color.rgb(64, 24, 78)) {
                            settings.update { putInt("player_hp", 80) }
                            settings.adjustMood(+18)
                            closeBattleAndNotify()
                            onSayPet("💋 Défaite face à ${curWitch.def.name} ! ${mdmAffectedAppRef.get()?.let { "$it grisée !" } ?: "Énergie drainée !"}")
                            if (cfg.unremovableAltarEnabled) {
                                try { onSpawnTrappedAltar(cfg) } catch (_: Throwable) {}
                            }
                        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(5) })
                        actionsBox.addView(row)
                    }
                } catch (_: Throwable) {
                    closeBattleAndNotify()
                }
            }
            showSceneStep()
        }

        // ==================== CHECK BOSS PHASE SHIFTS ====================
        fun checkBossPhaseShift(): Boolean {
            val ratio = witchResolve.toFloat() / witchMaxResolve.coerceAtLeast(1)
            if (bossPhase == 1 && ratio <= 0.55f && witchResolve > 0) {
                bossPhase = 2
                witchShield = true
                if (curWitch.def.isBoss || curWitch.def.isMiniBoss) dominationAura = true
                stage.triggerWitchAttackLunge(intense = true)
                onVibrate(85L)
                plannedNextAttack = curWitch.profile.attacks.firstOrNull { it.kind == AttackKind.BIND_CAPTURE } ?: pickNextWitchIntent()
                speakAi(
                    speaker = "🔥 PHASE 2 — ${curWitch.profile.name} !",
                    event = EventKind.BOSS_PHASE_2,
                    extraFooter = "🛡️ Barrière Runique activée ! Utilise 🔥 Lame Spirituelle pour la briser !"
                )
                refreshHud()
                return true
            }
            if (bossPhase == 2 && ratio <= 0.25f && witchResolve > 0 && (curWitch.def.isBoss || curWitch.def.isMiniBoss)) {
                bossPhase = 3
                if (!duoSpawned && reinforcement != null) {
                    duoSpawned = true
                    curWitch = reinforcement
                    witchMaxResolve = (curWitch.profile.maxResolve * 0.58f).toInt().coerceAtLeast(110)
                    witchResolve = witchMaxResolve
                    rpgState.markEncountered(curWitch.def.id)
                    stage.setWitchBitmaps(curWitch.spriteBitmap, curWitch.backdropBitmap)
                    stage.triggerWitchAttackLunge(intense = true)
                    onVibrate(100L)
                    plannedNextAttack = curWitch.profile.ultimateAttack
                    speakAi(
                        speaker = "⚡ RENFORT DUO — ${curWitch.profile.name} !",
                        event = EventKind.BOSS_PHASE_3,
                        extraFooter = "Une 2e Sorcière surgit et charge son Sort Ultime ! Prépare 🛡️ GARDE !"
                    )
                } else {
                    witchMirage = true
                    plannedNextAttack = curWitch.profile.ultimateAttack
                    stage.triggerWitchAttackLunge(intense = true)
                    onVibrate(90L)
                    speakAi(
                        speaker = "👑 PHASE 3 — FORME ULTIME !",
                        event = EventKind.BOSS_PHASE_3,
                        extraFooter = "🪞 Mirage actif + Sort Ultime imminent ! Prépare 🛡️ GARDE !"
                    )
                }
                refreshHud()
                return true
            }
            return false
        }

        // ==================== WITCH TURN EXECUTION ====================
        fun executeWitchTurn(bonusMult: Float = 1f, forcedSpamCounter: Boolean = false) {
            if (!sessionActive || inEndScene) return
            turnLocked = true
            turnNumber++

            val wasGuarding = guarding
            val wasCountering = counterStance
            val hadMeditateImm = meditateImmunity
            guarding = false
            counterStance = false
            meditateImmunity = false
            if (furyTurns > 0) furyTurns--

            // End-of-turn passive effects (Domination Aura / Siphon Mark / Hypnosis MP drain)
            val passiveNotes = ArrayList<String>()
            if (dominationAura && !wasGuarding) {
                embrace = (embrace + (6 * forgeEmbraceResist).toInt().coerceAtLeast(3)).coerceAtMost(100)
                passiveNotes.add("👑 Aura +Emprise")
            }
            if (siphonTurns > 0) {
                siphonTurns--
                val drainHp = 8
                val healW = 18
                hp = (hp - drainHp).coerceAtLeast(0)
                witchResolve = (witchResolve + healW).coerceAtMost(witchMaxResolve)
                passiveNotes.add("🩸 Siphon -$drainHp PV / Boss +$healW")
            }
            if (hypnoTurns > 0) {
                mp = (mp - 7).coerceAtLeast(0)
                passiveNotes.add("🌀 Hypnose -7 PM")
            }
            if (charmedTurns > 0) charmedTurns--
            if (hypnoTurns > 0) hypnoTurns--
            if (sealedTurns > 0) sealedTurns--

            // Check if Witch is STUNNED!
            if (witchStunTurns > 0) {
                witchStunTurns--
                plannedNextAttack = pickNextWitchIntent()
                typeDialogue("💫 ${curWitch.def.name} est ÉTOURDIE !", "Elle perd son tour et ne peut pas attaquer ! Profites-en pour enchaîner tes combos ! 🔥")
                refreshHud()
                turnLocked = false
                renderTurnControls()
                return
            }

            // Check Boss Phase Shift before executing attack
            if (checkBossPhaseShift()) {
                turnLocked = false
                renderTurnControls()
                return
            }

            val attack = plannedNextAttack
            val extraFooterList = ArrayList<String>()
            if (passiveNotes.isNotEmpty()) extraFooterList.add(passiveNotes.joinToString(" • "))

            // 1) AEGIS VEIL BLOCKS EVERYTHING COMPLETELY!
            if (aegisActive) {
                aegisActive = false
                stage.triggerPerfectParryFlash()
                onVibrate(45L)
                plannedNextAttack = pickNextWitchIntent()
                typeDialogue(
                    "🛡️ VOILE D'ÉGIDE ACTIVÉ !",
                    "Ton bouclier sacré absorbe 100 % de « ${attack.name} » ! Aucun dégât, aucun envoûtement !"
                )
                refreshHud()
                turnLocked = false
                renderTurnControls()
                return
            }

            // 2) COUNTER-SEAL INTERCEPTS CAPTURE OR STRIKE!
            if (wasCountering && (attack.kind == AttackKind.BIND_CAPTURE || attack.kind == AttackKind.STRIKE || attack.kind == AttackKind.TIME_DOUBLE)) {
                val cDmg = ((38 + playerLv) * forgeAtkMul * comboMult).toInt()
                witchResolve = (witchResolve - cDmg).coerceAtLeast(0)
                witchShield = false
                witchStunTurns = 1
                sp = (sp + 22 + forgeSpBonus).coerceAtMost(100)
                stage.triggerPerfectParryFlash()
                onVibrate(55L)
                if (witchResolve <= 0) {
                    finishBattleVictory()
                    return
                }
                plannedNextAttack = pickNextWitchIntent()
                speakAi(
                    speaker = "⚡ CONTRE-SCEAU PARFAIT !",
                    event = EventKind.COUNTER_HIT,
                    skillName = attack.name,
                    extraFooter = "💥 -$cDmg Boss • Sort « ${attack.name} » annulé • Sorcière ÉTOURDIE !"
                )
                refreshHud()
                turnLocked = false
                renderTurnControls()
                return
            }

            // 3) PERFECT PARRY WHEN GUARDING AGAINST TELEGRAPHED ULTIMATE OR TIME_DOUBLE!
            if (wasGuarding && (attack.kind == AttackKind.ULTIMATE || attack.kind == AttackKind.TIME_DOUBLE)) {
                rpgState.recordPerfectParry()
                val parryDmg = (attack.hpDamage * 0.18f).toInt().coerceAtLeast(4)
                hp = (hp - parryDmg).coerceAtLeast(1)
                sp = (sp + 30 + forgeSpBonus).coerceAtMost(100)
                witchStunTurns = 1
                val riposte = ((26 + playerLv / 2) * forgeAtkMul).toInt()
                witchResolve = (witchResolve - riposte).coerceAtLeast(0)
                stage.triggerPerfectParryFlash()
                onVibrate(50L)
                if (witchResolve <= 0) {
                    finishBattleVictory()
                    return
                }
                plannedNextAttack = pickNextWitchIntent()
                speakAi(
                    speaker = "⚡ PARADE PARFAITE !",
                    event = EventKind.PERFECT_PARRY,
                    skillName = attack.name,
                    extraFooter = "🛡️ Dégâts -82 % (-$parryDmg PV) • +30 SP • Riposte -$riposte • Sorcière ÉTOURDIE !"
                )
                refreshHud()
                turnLocked = false
                renderTurnControls()
                return
            }

            if (attack.kind == AttackKind.ULTIMATE && !wasGuarding) {
                missedUltimateGuards++
            }

            // 4) Special Buff / Illusion / Barrier Spells
            if (attack.kind == AttackKind.BARRIER_SHIELD) {
                witchShield = true
            } else if (attack.kind == AttackKind.MIRAGE_CLONE) {
                witchMirage = true
            }

            val guardMul = if (wasGuarding) 0.25f else 1.0f
            val captureMul = if (captured) 1.28f else 1.0f
            val doubleMul = if (attack.kind == AttackKind.TIME_DOUBLE) 1.35f else 1.0f
            val dmg = (attack.hpDamage * bonusMult * guardMul * captureMul * doubleMul).toInt().coerceAtLeast(5)
            val embGain = (attack.embraceGain * bonusMult * guardMul * forgeEmbraceResist).toInt().coerceAtLeast(5)
            val lvLoss = if (wasGuarding || hadMeditateImm) 0 else attack.levelDrain

            hp = (hp - dmg).coerceAtLeast(0)
            embrace = (embrace + embGain).coerceAtMost(100)
            sp = (sp + (if (wasGuarding) 15 else 11) + forgeSpBonus).coerceAtMost(100)
            if (lvLoss > 0) playerLv = (playerLv - lvLoss).coerceAtLeast(1)
            drainPct = (drainPct + if (wasGuarding) 4 else 14).coerceAtMost(95)

            // Apply Enchantments / Captures (blocked if Méditation immunity active!)
            var statusAppliedMsg = ""
            if (!hadMeditateImm && !wasGuarding) {
                val resistRoll = Random.nextFloat() < (wardBonus - 1) * 0.05f
                if (!resistRoll) {
                    when (attack.statusEffect) {
                        "CAPTURE" -> { captured = true; statusAppliedMsg = " • ⛓️ CAPTURÉ !" }
                        "CHARMED" -> { charmedTurns = 2; statusAppliedMsg = " • 💖 CHARMÉ !" }
                        "HYPNOTIZED" -> { hypnoTurns = 2; statusAppliedMsg = " • 🌀 HYPNOTISÉ !" }
                        "SEALED" -> { sealedTurns = 2; statusAppliedMsg = " • 🔒 SCELLÉ !" }
                        "SIPHON" -> { siphonTurns = 3; statusAppliedMsg = " • 🩸 SIPHON (3t) !" }
                    }
                } else if (attack.statusEffect.isNotEmpty()) {
                    statusAppliedMsg = " • 🧿 Résisté !"
                }
            } else if (hadMeditateImm && attack.statusEffect.isNotEmpty()) {
                statusAppliedMsg = " • 🧘 Immunité Méditation !"
            }
            if (!captured && embrace >= 62 && !wasGuarding) {
                captured = true
                statusAppliedMsg = " • ⛓️ EMPRISE CAPTURE !"
            }

            settings.adjustPlayerStats(levelDelta = -lvLoss, hpDelta = -dmg, drainDelta = +10)
            try { onApplyVisualDrain(drainPct, cfg) } catch (_: Throwable) {}
            stage.triggerWitchAttackLunge(intense = !wasGuarding)
            onVibrate(if (wasGuarding) 25L else 75L)

            val guardTxt = if (wasGuarding) " (🛡️ -75 %)" else ""
            val lvTxt = if (lvLoss > 0) " • -$lvLoss NV" else ""
            extraFooterList.add("💥 « ${attack.name} » : -$dmg PV$lvTxt • Emprise $embrace%$guardTxt$statusAppliedMsg")

            val ev = when {
                forcedSpamCounter -> EventKind.SPAM_READ
                wasGuarding -> EventKind.GUARDED
                attack.kind == AttackKind.BARRIER_SHIELD -> EventKind.SHIELD_UP
                attack.kind == AttackKind.MIRAGE_CLONE -> EventKind.MIRAGE_CAST
                attack.kind == AttackKind.ENCHANT_CHARM -> EventKind.ENCHANT_CHARM
                attack.kind == AttackKind.ENCHANT_HYPNO -> EventKind.ENCHANT_HYPNO
                attack.kind == AttackKind.SIPHON_DRAIN -> EventKind.SIPHON_MARK
                attack.kind == AttackKind.TIME_DOUBLE -> EventKind.TIME_DOUBLE
                captured && attack.statusEffect == "CAPTURE" -> EventKind.CAPTURE
                else -> EventKind.ATTACK
            }

            plannedNextAttack = pickNextWitchIntent()
            speakAi(curWitch.profile.name, ev, skillName = attack.name, extraFooter = extraFooterList.joinToString("\n"))
            refreshHud()

            if (hp <= 0 || embrace >= 100) {
                actionsBox.removeAllViews()
                val r = Runnable { startCinematicDrainEndScene() }
                pendingTurnRunnable = r
                mainHandler.postDelayed(r, 950L)
            } else {
                turnLocked = false
                renderTurnControls()
            }
        }

        // ==================== 3 VARIED PHASE-2 CAPTURE ESCAPE MINI-GAMES ====================
        fun onQteSuccess(bonusDmg: Int = 26) {
            qteTickRunnable?.let { mainHandler.removeCallbacks(it) }
            qteTickRunnable = null
            rpgState.recordQteBreak()
            captured = false
            embrace = (embrace - 45).coerceAtLeast(0)
            sp = (sp + 20 + forgeSpBonus).coerceAtMost(100)
            witchResolve = (witchResolve - (bonusDmg * forgeAtkMul).toInt()).coerceAtLeast(0)
            witchStunTurns = 1
            stage.triggerWitchHitShake(critical = true)
            onVibrate(45L)
            speakAi("💥 ÉVASION PARFAITE !", EventKind.QTE_BREAK, extraFooter = "Emprise -45 % • +20 SP • Sorcière ÉTOURDIE !")
            refreshHud()
            if (witchResolve <= 0) finishBattleVictory() else {
                turnLocked = false
                renderTurnControls()
            }
        }

        fun onQteFailure() {
            qteTickRunnable?.let { mainHandler.removeCallbacks(it) }
            qteTickRunnable = null
            qteFails++
            speakAi(curWitch.profile.name, EventKind.QTE_FAIL)
            val r = Runnable { executeWitchTurn(bonusMult = 1.30f) }
            pendingTurnRunnable = r
            mainHandler.postDelayed(r, 520L)
        }

        // QTE 1: Rapid Mash (11-12 taps)
        fun startMashQte() {
            actionsBox.removeAllViews()
            var taps = 0
            val required = (if (curWitch.def.isBoss) 12 else 10)
            val windowMs = 3_800L + (wardBonus - 1) * 140L
            val endElapsed = SystemClock.elapsedRealtime() + windowMs

            val qteBtn = makeActionBtn("⚡ MARTÈLE VITE ! (0/$required)", Color.rgb(234, 88, 12)) {}
            val timerTv = TextView(context).apply {
                text = "⏳ Libère-toi !"
                setTextColor(Color.rgb(253, 224, 71))
                textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0)
            }
            actionsBox.addView(qteBtn)
            actionsBox.addView(timerTv)

            qteBtn.setOnClickListener {
                taps++
                onVibrate(14L)
                qteBtn.text = "⚡ MARTÈLE VITE ! ($taps/$required)"
                if (taps >= required) onQteSuccess(28)
            }

            val tick = object : Runnable {
                override fun run() {
                    if (!sessionActive || inEndScene) return
                    val remMs = endElapsed - SystemClock.elapsedRealtime()
                    if (remMs <= 0L) onQteFailure()
                    else {
                        timerTv.text = String.format("⏳ %.1fs restantes !", remMs / 1000f)
                        mainHandler.postDelayed(this, 90L)
                    }
                }
            }
            qteTickRunnable = tick
            mainHandler.postDelayed(tick, 90L)
        }

        // QTE 2: Critical Timing Sweet-Spot Bar!
        fun startTimingQte() {
            actionsBox.removeAllViews()
            var pos = 0
            var dir = 1
            val slots = 16
            val endElapsed = SystemClock.elapsedRealtime() + 5_500L

            val barTv = TextView(context).apply {
                setTextColor(Color.rgb(253, 224, 71))
                textSize = 15f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(6))
            }
            val hitBtn = makeActionBtn("🎯 BRISER DANS LA ZONE DORÉE [★] !", Color.rgb(217, 119, 6)) {
                if (pos in 6..10) {
                    onQteSuccess(42) // Bonus critical counter dmg!
                } else {
                    onQteFailure()
                }
            }
            actionsBox.addView(barTv)
            actionsBox.addView(hitBtn)

            val tick = object : Runnable {
                override fun run() {
                    if (!sessionActive || inEndScene) return
                    val remMs = endElapsed - SystemClock.elapsedRealtime()
                    if (remMs <= 0L) {
                        onQteFailure()
                        return
                    }
                    pos += dir
                    if (pos >= slots - 1) { pos = slots - 1; dir = -1 }
                    if (pos <= 0) { pos = 0; dir = 1 }
                    val sb = StringBuilder()
                    for (i in 0 until slots) {
                        sb.append(
                            when {
                                i == pos -> "💎"
                                i in 6..10 -> "★"
                                else -> "─"
                            }
                        )
                    }
                    barTv.text = sb.toString()
                    mainHandler.postDelayed(this, 68L)
                }
            }
            qteTickRunnable = tick
            mainHandler.post(tick)
        }

        // QTE 3: 3-Rune Exorcism Sequence!
        fun startRuneSequenceQte() {
            actionsBox.removeAllViews()
            val runes = listOf("🌙", "🔥", "⚡", "✨")
            val seq = List(3) { runes.random() }
            var idx = 0
            val endElapsed = SystemClock.elapsedRealtime() + 5_200L + (wardBonus - 1) * 180L

            val seqTv = TextView(context).apply {
                text = "🔮 Tape dans l'ordre : ${seq.joinToString("  →  ")}"
                setTextColor(Color.rgb(240, 171, 252))
                textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, dp(6))
            }
            actionsBox.addView(seqTv)

            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            runes.forEachIndexed { rIdx, rune ->
                row.addView(makeActionBtn(rune, Color.rgb(126, 34, 206)) {
                    onVibrate(18L)
                    if (rune == seq[idx]) {
                        idx++
                        seqTv.text = "🔮 Validé $idx/3 : ${seq.joinToString("  →  ")}"
                        if (idx >= seq.size) onQteSuccess(36)
                    } else {
                        onQteFailure()
                    }
                }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (rIdx > 0) marginStart = dp(4) })
            }
            actionsBox.addView(row)

            val tick = object : Runnable {
                override fun run() {
                    if (!sessionActive || inEndScene) return
                    val remMs = endElapsed - SystemClock.elapsedRealtime()
                    if (remMs <= 0L) onQteFailure()
                    else mainHandler.postDelayed(this, 120L)
                }
            }
            qteTickRunnable = tick
            mainHandler.postDelayed(tick, 120L)
        }

        fun startNextVariedQte() {
            when (qteVarietyCounter++ % 3) {
                0 -> startTimingQte()
                1 -> startRuneSequenceQte()
                else -> startMashQte()
            }
        }

        // ==================== PLAYER SKILLS SUB-MENU ====================
        fun showSkillsMenu() {
            val isSealed = sealedTurns > 0
            actionsBox.removeAllViews()

            val r1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            r1.addView(makeActionBtn(
                if (isSealed) "🔒 Lame Scellée" else "🔥 Lame Brise-Garde (-14 PM)",
                if (isSealed || mp < 14) Color.rgb(58, 54, 72) else Color.rgb(234, 88, 12)
            ) {
                when {
                    isSealed -> typeDialogue("🔒 Sorts Scellés", "Utilise ⚔️ Frappe, 🛡️ Garde ou une 🔮 Amulette !")
                    mp < 14 -> typeDialogue("⚡ PM insuffisants", "Utilise 🛡️ Garde (+22 PM) ou 🧘 Méditation (+28 PM) !")
                    else -> {
                        mp -= 14
                        sp = (sp + 12 + forgeSpBonus).coerceAtMost(100)
                        val (vMul, spammed) = evaluateActionVariety("BLADE")
                        val furyMul = if (furyTurns > 0) 1.6f else 1.0f
                        val stunMul = if (witchStunTurns > 0) 1.5f else 1.0f

                        if (witchShield) {
                            witchShield = false
                            witchStunTurns = 1
                            val dmgBoss = (Random.nextInt(54, 70) * forgeAtkMul * furyMul).toInt()
                            witchResolve = (witchResolve - dmgBoss).coerceAtLeast(0)
                            stage.triggerWitchHitShake(critical = true)
                            onVibrate(55L)
                            if (witchResolve <= 0) { finishBattleVictory(); return@makeActionBtn }
                            speakAi("💥 BOUCLIER BRISÉ !", EventKind.SHIELD_BREAK, extraFooter = "-$dmgBoss Boss • Sorcière ÉTOURDIE 1 tour !")
                            refreshHud()
                            renderTurnControls()
                        } else {
                            val dmgBoss = ((Random.nextInt(38, 54) + playerLv / 2) * forgeAtkMul * vMul * furyMul * stunMul).toInt()
                            witchResolve = (witchResolve - dmgBoss).coerceAtLeast(0)
                            stage.triggerWitchHitShake(critical = vMul >= 1.5f)
                            onVibrate(35L)
                            if (witchResolve <= 0) finishBattleVictory() else executeWitchTurn(if (spammed) 1.25f else 1.0f, forcedSpamCounter = spammed)
                        }
                    }
                }
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) })

            r1.addView(makeActionBtn(
                if (isSealed) "🔒 Contre Scellé" else "⚡ Contre-Sceau (-12 PM)",
                if (isSealed || mp < 12) Color.rgb(58, 54, 72) else Color.rgb(14, 116, 144)
            ) {
                when {
                    isSealed -> typeDialogue("🔒 Sorts Scellés", "Sorts bloqués ! Utilise 🛡️ Garde ou 🔮 Amulette.")
                    mp < 12 -> typeDialogue("⚡ PM insuffisants", "Besoin de 12 PM pour Contre-Sceau !")
                    else -> {
                        mp -= 12
                        counterStance = true
                        evaluateActionVariety("COUNTER")
                        executeWitchTurn(1.0f)
                    }
                }
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4) })
            actionsBox.addView(r1)

            val r2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            r2.addView(makeActionBtn("🧘 Méditation (+28 PM · Anti-Charme)", Color.rgb(16, 128, 96)) {
                mp = (mp + 28).coerceAtMost(maxMp)
                sp = (sp + 14 + forgeSpBonus).coerceAtMost(100)
                charmedTurns = 0
                hypnoTurns = 0
                witchMirage = false
                meditateImmunity = true
                evaluateActionVariety("MEDITATE")
                executeWitchTurn(0.90f)
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4); topMargin = dp(5) })

            val canJudg = sp >= 100
            val canExor = sp >= 50
            val spLabel = when {
                canJudg -> "💥 JUGEMENT ! (100 SP)"
                canExor -> "🌟 EXORCISME ! (50 SP)"
                else -> "🌟 Exorcisme ($sp/50 SP)"
            }
            r2.addView(makeActionBtn(
                spLabel,
                when {
                    canJudg -> Color.rgb(220, 38, 38)
                    canExor -> Color.rgb(217, 119, 6)
                    else -> Color.rgb(54, 50, 68)
                }
            ) {
                when {
                    canJudg -> {
                        sp = 0
                        witchShield = false; witchMirage = false
                        charmedTurns = 0; hypnoTurns = 0; sealedTurns = 0; siphonTurns = 0
                        embrace = (embrace - 60).coerceAtLeast(0)
                        hp = (hp + 35).coerceAtMost(maxHp)
                        val dmgBoss = ((110 + playerLv * 2) * forgeAtkMul * comboMult).toInt()
                        witchResolve = (witchResolve - dmgBoss).coerceAtLeast(0)
                        witchStunTurns = 1
                        stage.triggerWitchHitShake(critical = true)
                        onVibrate(75L)
                        if (witchResolve <= 0) finishBattleVictory() else {
                            speakAi("💥 JUGEMENT ULTIME (100 SP) !", EventKind.JUDGMENT_ULT, extraFooter = "-$dmgBoss Boss • +35 PV • Emprise -60 % • ÉTOURDIE !")
                            refreshHud()
                            renderTurnControls()
                        }
                    }
                    canExor -> {
                        sp -= 50
                        witchShield = false; witchMirage = false
                        charmedTurns = 0; hypnoTurns = 0; sealedTurns = 0; siphonTurns = 0
                        embrace = (embrace - 45).coerceAtLeast(0)
                        evaluateActionVariety("EXORCISM")
                        val dmgBoss = ((62 + playerLv) * forgeAtkMul * comboMult).toInt()
                        witchResolve = (witchResolve - dmgBoss).coerceAtLeast(0)
                        stage.triggerWitchHitShake(critical = true)
                        onVibrate(55L)
                        if (witchResolve <= 0) finishBattleVictory() else {
                            speakAi("🌟 SCEAU D'EXORCISME !", EventKind.EXORCISM_HIT, extraFooter = "-$dmgBoss Boss • Emprise -45 % • Illusions & Siphon purifiés !")
                            refreshHud()
                            val r = Runnable { executeWitchTurn(0.85f) }
                            pendingTurnRunnable = r
                            mainHandler.postDelayed(r, 700L)
                        }
                    }
                    else -> typeDialogue("✨ SP insuffisants", "Charge 50 SP (Exorcisme) ou 100 SP (Jugement Ultime) avec Frappe, Garde & Contre !")
                }
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4); topMargin = dp(5) })
            actionsBox.addView(r2)

            actionsBox.addView(makeActionBtn("↩ Retour", Color.rgb(42, 36, 56)) {
                renderTurnControls()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply { topMargin = dp(5) }
            })
        }

        // ==================== 6 RELICS INVENTORY SUB-MENU ====================
        fun showItemsMenu() {
            val inv = rpgState.snapshot()
            actionsBox.removeAllViews()

            val r1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            r1.addView(makeActionBtn("🧪 Élixir +65 PV (${inv.elixirs})", if (inv.elixirs > 0) Color.rgb(16, 128, 96) else Color.rgb(52, 52, 66)) {
                if (rpgState.consumeItem("elixir")) {
                    hp = (hp + 65).coerceAtMost(maxHp)
                    typeDialogue("🧪 Élixir de Volonté", "Tu récupères +65 PV ! (PV : $hp/$maxHp)")
                    refreshHud(); executeWitchTurn(0.88f)
                }
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(4) })

            r1.addView(makeActionBtn("💎 Cristal +45 PM (${inv.crystals})", if (inv.crystals > 0) Color.rgb(37, 99, 235) else Color.rgb(52, 52, 66)) {
                if (rpgState.consumeItem("crystal")) {
                    mp = (mp + 45).coerceAtMost(maxMp)
                    sp = (sp + 25).coerceAtMost(100)
                    typeDialogue("💎 Cristal de Mana", "+45 PM et +25 SP !")
                    refreshHud(); executeWitchTurn(0.88f)
                }
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(4) })
            actionsBox.addView(r1)

            val r2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            r2.addView(makeActionBtn("📜 Talisman (${inv.talismans})", if (inv.talismans > 0) Color.rgb(217, 119, 6) else Color.rgb(52, 52, 66)) {
                if (rpgState.consumeItem("talisman")) {
                    captured = false; embrace = (embrace - 50).coerceAtLeast(0)
                    stage.triggerWitchHitShake()
                    typeDialogue("📜 Talisman de Rupture", "Liens pulvérisés ! Emprise -50 % !")
                    refreshHud(); renderTurnControls()
                }
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(4); topMargin = dp(4) })

            r2.addView(makeActionBtn("🔮 Amulette (${inv.amulets})", if (inv.amulets > 0) Color.rgb(126, 34, 206) else Color.rgb(52, 52, 66)) {
                if (rpgState.consumeItem("amulet")) {
                    charmedTurns = 0; hypnoTurns = 0; sealedTurns = 0; siphonTurns = 0; drainPct = 0
                    try { onClearVisualDrain() } catch (_: Throwable) {}
                    typeDialogue("🔮 Amulette de Clarté", "TOUS les statuts, Siphon et drainage visuel purifiés ! ✨")
                    refreshHud(); renderTurnControls()
                }
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(4); topMargin = dp(4) })
            actionsBox.addView(r2)

            val r3 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            r3.addView(makeActionBtn("🕯️ Furie ×1.6 (${inv.incenses})", if (inv.incenses > 0) Color.rgb(190, 24, 93) else Color.rgb(52, 52, 66)) {
                if (rpgState.consumeItem("incense")) {
                    furyTurns = 3
                    typeDialogue("🕯️ Encens de Furie", "Toutes tes attaques infligent ×1.6 pendant 3 tours ! 🔥")
                    refreshHud(); renderTurnControls()
                }
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(4); topMargin = dp(4) })

            r3.addView(makeActionBtn("🛡️ Égide (${inv.aegisVeils})", if (inv.aegisVeils > 0) Color.rgb(14, 116, 144) else Color.rgb(52, 52, 66)) {
                if (rpgState.consumeItem("aegis")) {
                    aegisActive = true
                    typeDialogue("🛡️ Voile d'Égide", "Un bouclier absolu absorbera la prochaine attaque/capture de ${curWitch.def.name} !")
                    refreshHud(); renderTurnControls()
                }
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(4); topMargin = dp(4) })
            actionsBox.addView(r3)

            actionsBox.addView(makeActionBtn("↩ Retour", Color.rgb(42, 36, 56)) {
                renderTurnControls()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply { topMargin = dp(4) }
            })
        }

        // ==================== RP MENTAL DUEL SUB-MENU ====================
        fun showRpMenu() {
            actionsBox.removeAllViews()
            actionsBox.addView(makeActionBtn("🔥 Provoquer : « Tes tours ne m'impressionnent pas ! » (+Dégâts & +22 SP)", Color.rgb(225, 29, 72)) {
                val dmgBoss = ((28 + playerLv / 2) * forgeAtkMul).toInt()
                witchResolve = (witchResolve - dmgBoss).coerceAtLeast(0)
                sp = (sp + 22 + forgeSpBonus).coerceAtMost(100)
                evaluateActionVariety("RP_DEFY")
                stage.triggerWitchHitShake()
                if (witchResolve <= 0) finishBattleVictory() else {
                    speakAi(curWitch.profile.name, EventKind.RP_DEFY)
                    val r = Runnable { executeWitchTurn(1.22f) }
                    pendingTurnRunnable = r
                    mainHandler.postDelayed(r, 650L)
                }
            })
            actionsBox.addView(makeActionBtn("🤝 Pacte Risqué : « Laisse-moi reprendre mon souffle… » (+35 PV, +30 PM mais +18 % Emprise)", Color.rgb(14, 116, 144)) {
                hp = (hp + 35).coerceAtMost(maxHp)
                mp = (mp + 30).coerceAtMost(maxMp)
                embrace = (embrace + 18).coerceAtMost(98)
                evaluateActionVariety("RP_BARGAIN")
                speakAi(curWitch.profile.name, EventKind.RP_BARGAIN)
                refreshHud()
                renderTurnControls()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) }
            })
            actionsBox.addView(makeActionBtn("🥺 S'incliner : « Votre aura est irrésistible… » (-Colère, +Emprise)", Color.rgb(126, 34, 206)) {
                settings.adjustMood(-14)
                speakAi(curWitch.profile.name, EventKind.RP_SUBMIT)
                val r = Runnable { executeWitchTurn(1.42f) }
                pendingTurnRunnable = r
                mainHandler.postDelayed(r, 650L)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) }
            })
            actionsBox.addView(makeActionBtn("↩ Retour", Color.rgb(42, 36, 56)) { renderTurnControls() }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply { topMargin = dp(5) }
            })
        }

        // ==================== MAIN TACTICAL CONTROLS ====================
        renderTurnControls = {
            if (sessionActive && !inEndScene) {
                actionsBox.removeAllViews()
                val inv = rpgState.snapshot()
                val isCharmed = charmedTurns > 0

                if (captured) {
                    val r1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    val qteLabel = when (qteVarietyCounter % 3) {
                        0 -> "🎯 Briser (Timing)"
                        1 -> "🔮 Briser (Runes)"
                        else -> "⚡ Se débattre (QTE)"
                    }
                    r1.addView(makeActionBtn(qteLabel, Color.rgb(234, 88, 12)) {
                        if (!turnLocked) startNextVariedQte()
                    }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) })

                    if (inv.talismans > 0) {
                        r1.addView(makeActionBtn("📜 Talisman (${inv.talismans})", Color.rgb(217, 119, 6)) {
                            if (rpgState.consumeItem("talisman")) onQteSuccess(30)
                        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4) })
                    } else {
                        r1.addView(makeActionBtn("🛡️ Endurer (+22 PM)", Color.rgb(37, 99, 235)) {
                            guarding = true
                            mp = (mp + 22).coerceAtMost(maxMp)
                            executeWitchTurn(0.80f)
                        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4) })
                    }
                    actionsBox.addView(r1)

                    val r2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    r2.addView(makeActionBtn("🎒 Reliques (${inv.totalItems})", Color.rgb(79, 70, 229)) {
                        showItemsMenu()
                    }, LinearLayout.LayoutParams(0, dp(38), 0.45f).apply { marginEnd = dp(4); topMargin = dp(5) })
                    r2.addView(makeActionBtn("💖 Céder au Drainage Final", Color.rgb(162, 28, 175)) {
                        embrace = 100; hp = 0; startCinematicDrainEndScene()
                    }, LinearLayout.LayoutParams(0, dp(38), 0.55f).apply { marginStart = dp(4); topMargin = dp(5) })
                    actionsBox.addView(r2)
                } else {
                    val r1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    val strikeLabel = if (isCharmed) "💖 Obéir (Charmé !)" else "⚔️ Frappe (+18 SP)"
                    r1.addView(makeActionBtn(strikeLabel, if (isCharmed) Color.rgb(192, 38, 211) else Color.rgb(225, 29, 72)) {
                        if (turnLocked) return@makeActionBtn
                        if (isCharmed) {
                            charmedTurns = 0
                            executeWitchTurn(1.25f)
                        } else {
                            sp = (sp + 18 + forgeSpBonus).coerceAtMost(100)
                            val (vMul, spammed) = evaluateActionVariety("STRIKE")
                            // Mirage 50% miss check on basic Strike!
                            if (witchMirage && Random.nextBoolean()) {
                                typeDialogue("🪞 MIRAGE !", "Ta frappe traverse un clone illusoire ! Utilise 🧘 Méditation ou 🌟 Exorcisme pour dissiper son Mirage !")
                                val r = Runnable { executeWitchTurn(1.0f) }
                                pendingTurnRunnable = r
                                mainHandler.postDelayed(r, 650L)
                                return@makeActionBtn
                            }
                            val shieldMul = if (witchShield) 0.32f else 1.0f
                            val furyMul = if (furyTurns > 0) 1.6f else 1.0f
                            val stunMul = if (witchStunTurns > 0) 1.5f else 1.0f
                            val dmgBoss = ((Random.nextInt(22, 32) + playerLv / 3) * forgeAtkMul * vMul * shieldMul * furyMul * stunMul).toInt().coerceAtLeast(6)
                            witchResolve = (witchResolve - dmgBoss).coerceAtLeast(0)
                            stage.triggerWitchHitShake(critical = vMul >= 1.5f && !witchShield)
                            onVibrate(22L)
                            if (witchResolve <= 0) finishBattleVictory()
                            else executeWitchTurn(if (spammed) 1.25f else 1.0f, forcedSpamCounter = spammed)
                        }
                    }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) })

                    val skillsGlow = if (sp >= 50) Color.rgb(217, 119, 6) else Color.rgb(234, 88, 12)
                    val skillsLabel = when {
                        sp >= 100 -> "💥 Sorts · JUGEMENT !"
                        sp >= 50 -> "🌟 Sorts · EXORCISME !"
                        else -> "🔥 Sorts & Contre ▾"
                    }
                    r1.addView(makeActionBtn(skillsLabel, skillsGlow) {
                        if (!turnLocked) showSkillsMenu()
                    }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4) })
                    actionsBox.addView(r1)

                    val r2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    val guardHighlight = plannedNextAttack.kind == AttackKind.ULTIMATE || plannedNextAttack.kind == AttackKind.TIME_DOUBLE
                    r2.addView(makeActionBtn(
                        if (guardHighlight) "🛡️ PARADE ! (+22 PM)" else "🛡️ Garde (+22 PM)",
                        if (guardHighlight) Color.rgb(2, 132, 199) else Color.rgb(37, 99, 235)
                    ) {
                        if (turnLocked) return@makeActionBtn
                        guarding = true
                        mp = (mp + 22).coerceAtMost(maxMp)
                        evaluateActionVariety("GUARD")
                        executeWitchTurn(1.0f)
                    }, LinearLayout.LayoutParams(0, dp(38), 1.05f).apply { marginEnd = dp(4); topMargin = dp(5) })

                    r2.addView(makeActionBtn("🎒 Sac (${inv.totalItems})", Color.rgb(79, 70, 229)) {
                        if (!turnLocked) showItemsMenu()
                    }, LinearLayout.LayoutParams(0, dp(38), 0.78f).apply { marginEnd = dp(4); topMargin = dp(5) })

                    r2.addView(makeActionBtn("💬 RP", Color.rgb(126, 34, 206)) {
                        if (!turnLocked) showRpMenu()
                    }, LinearLayout.LayoutParams(0, dp(38), 0.56f).apply { marginEnd = dp(4); topMargin = dp(5) })

                    r2.addView(makeActionBtn("💖", Color.rgb(98, 22, 108)) {
                        if (!turnLocked) {
                            embrace = 100; hp = 0; startCinematicDrainEndScene()
                        }
                    }, LinearLayout.LayoutParams(0, dp(38), 0.36f).apply { topMargin = dp(5) })
                    actionsBox.addView(r2)
                }
            }
        }

        onMountFullscreen(root)
        refreshHud()
        speakAi(curWitch.profile.name, EventKind.INTRO)
        renderTurnControls()
        onVibrate(65L)
    }

    private fun makeActionBtn(label: String, bgColor: Int, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 11.1f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(11).toFloat()
            setColor(bgColor)
            setStroke(dp(1), Color.argb(85, 255, 255, 255))
        }
        minHeight = 0; minimumHeight = 0
        setPadding(dp(5), dp(6), dp(5), dp(6))
        setOnClickListener { onClick() }
    }
}
