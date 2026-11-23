package com.demonpet.app.ui

import android.Manifest
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.demonpet.app.ai.AvatarCutoutEngine
import com.demonpet.app.config.AvatarStyle
import com.demonpet.app.config.DemonConfigSnapshot
import com.demonpet.app.config.DemonSettings
import com.demonpet.app.config.FaceCensorMode
import com.demonpet.app.config.GenderFilter
import com.demonpet.app.config.SeverityPreset
import com.demonpet.app.config.WallpaperTarget
import com.demonpet.app.mdm.DemonMdmManager
import com.demonpet.app.mdm.DemonShizukuBridge
import com.demonpet.app.media.MediaFolderHelper
import com.demonpet.app.overlay.DemonPetView
import com.demonpet.app.overlay.WitchRpgBattleOverlay
import com.demonpet.app.receiver.DemonDeviceAdminReceiver
import com.demonpet.app.rpg.WitchCatalog
import com.demonpet.app.rpg.WitchRpgState
import com.demonpet.app.rpg.WitchStoryEngine
import com.demonpet.app.service.DemonAccessibilityService
import com.demonpet.app.service.DemonCompanionService
import rikka.shizuku.Shizuku
import kotlin.random.Random

/**
 * DemonMom RPG v6.0 — Complete 50-Witch Tactical Dark-Fantasy RPG & Reactive Shizuku Punishment Hub:
 * - Tab 0: 🗺️ Aventure (RPG Profile, 5 Chapters, Explore, Boss, Infinite Witch Tower, Claimable Quests, Live Shizuku Status)
 * - Tab 1: 📖 Grimoire (Complete 50-Witch Bestiary with Chapter/Boss filters & 1-Tap Battle Launch)
 * - Tab 2: 🎒 Forge & Sac (Permanent Soul Forge Upgrades, 6 Relics Shop, Living Demon Overlay & Punishments)
 * - Tab 3: ⚙️ Dossier & IA (Hidden Folder .* Picker, Instant AI + Optional Gemini Key, Shizuku 1-Tap Test & Permissions)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: DemonSettings
    private lateinit var rpgState: WitchRpgState
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var rootFrame: FrameLayout
    private lateinit var mainContentHost: FrameLayout
    private lateinit var battleHostFrame: FrameLayout
    private lateinit var inAppBattle: WitchRpgBattleOverlay

    private var lastRenderedLockState: Boolean? = null
    private var activeTab = 0
    private var grimoireFilter = 1 // 1..5 = Chapter 1..5, 0 = Bosses (10)

    private var previewPetView: DemonPetView? = null
    private var lockedPetAnimView: DemonPetView? = null
    private var lockedTapHintTv: TextView? = null
    private var headerPillTv: TextView? = null
    private var masterSwitch: Switch? = null
    private var mediaSummaryTv: TextView? = null
    private var restoreWallBtn: Button? = null
    private var tabButtons = ArrayList<Button>()
    private var tabContentContainer: FrameLayout? = null

    private val animTick = object : Runnable {
        override fun run() {
            val v = lockedPetAnimView ?: previewPetView
            if (v != null) {
                v.animPhase += 0.11f
                v.invalidate()
                mainHandler.postDelayed(this, 32L)
            }
        }
    }

    private val cfgListener: (DemonConfigSnapshot) -> Unit = { snap ->
        mainHandler.post {
            if (inAppBattle.isBattleActive()) {
                // Never rebuild or disturb the active battle/end-scene view!
                updateHeaderPill(snap)
                return@post
            }
            if (lastRenderedLockState != snap.challengeLocked) {
                renderScreen(snap)
            } else {
                previewPetView?.updateConfig(snap)
                updateHeaderPill(snap)
                if (masterSwitch?.isChecked != snap.enabled) masterSwitch?.isChecked = snap.enabled
                refreshMediaSummaryAsync(snap)
            }
        }
    }

    private val reactiveUiRefresh: () -> Unit = {
        mainHandler.post {
            if (!settings.challengeLocked && !inAppBattle.isBattleActive()) {
                renderActiveTab(settings.snapshot())
            }
        }
    }

    private val pickAvatarLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        Toast.makeText(this, "Détourage HD photo-réaliste en cours… ✨", Toast.LENGTH_SHORT).show()
        val includeHair = settings.cutoutIncludeHair
        Thread {
            val outPath = AvatarCutoutEngine.createAvatarFromUri(applicationContext, uri, includeHair)
            mainHandler.post {
                if (outPath != null) {
                    settings.avatarPath = outPath
                    Toast.makeText(this, "Portrait du compagnon prêt ! 😈", Toast.LENGTH_SHORT).show()
                    renderActiveTab(settings.snapshot())
                } else {
                    Toast.makeText(this, "Impossible de lire cette photo.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private val pickSafTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {
        }
        settings.update {
            putString("media_folder", "")
            putString("media_tree_uri", uri.toString())
        }
        Toast.makeText(this, "Dossier photos SAF sélectionné ✅", Toast.LENGTH_SHORT).show()
        refreshMediaSummaryAsync(settings.snapshot())
    }

    private val shizukuPermListener = Shizuku.OnRequestPermissionResultListener { reqCode, grantResult ->
        if (reqCode == DemonShizukuBridge.REQ_CODE_SHIZUKU) {
            mainHandler.post {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "⚡ Shizuku autorisé ! Liaison et activation UsageStats…", Toast.LENGTH_LONG).show()
                    Thread {
                        DemonShizukuBridge.ensureBound()
                        DemonShizukuBridge.autoGrantSafeAppOps()
                        mainHandler.post {
                            if (!settings.challengeLocked && !inAppBattle.isBattleActive()) {
                                renderActiveTab(settings.snapshot())
                            }
                        }
                    }.start()
                } else {
                    Toast.makeText(this, "Autorisation Shizuku refusée.", Toast.LENGTH_SHORT).show()
                    if (!settings.challengeLocked && !inAppBattle.isBattleActive()) {
                        renderActiveTab(settings.snapshot())
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = DemonSettings(applicationContext)
        rpgState = WitchRpgState(applicationContext)

        // Initialize Shizuku sticky binder listeners IMMEDIATELY on startup!
        DemonShizukuBridge.init(applicationContext)

        rootFrame = FrameLayout(this).apply { setBackgroundColor(BG_DARK) }
        mainContentHost = FrameLayout(this)
        battleHostFrame = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.rgb(10, 6, 16))
            isClickable = true
            isFocusable = true
        }
        rootFrame.addView(mainContentHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        rootFrame.addView(battleHostFrame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(rootFrame)

        inAppBattle = WitchRpgBattleOverlay(
            context = this,
            settings = settings,
            onMountFullscreen = { battleView ->
                battleHostFrame.removeAllViews()
                battleHostFrame.addView(
                    battleView,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                battleHostFrame.visibility = View.VISIBLE
                if (rpgState.forcedFullscreen) setImmersiveFullscreen(true)
            },
            onDismissFullscreen = {
                battleHostFrame.removeAllViews()
                battleHostFrame.visibility = View.GONE
                clearWindowBrightnessDrain()
                setImmersiveFullscreen(false)
            },
            onApplyVisualDrain = { pct, cfg -> applyWindowBrightnessDrain(pct, cfg) },
            onClearVisualDrain = { clearWindowBrightnessDrain() },
            onSayPet = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() },
            onSpawnTrappedAltar = { _ -> DemonAccessibilityService.activeInstance?.triggerManualTest("altar") },
            onVibrate = { ms -> vibrateShort(ms) },
            onSecretEmergencyExit = { showExitPinDialog() },
            onBattleFinished = {
                if (!settings.challengeLocked) renderActiveTab(settings.snapshot())
            }
        )

        requestBasicRuntimePermissionsIfNeeded()
        renderScreen(settings.snapshot())
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (inAppBattle.isBattleActive()) {
            Toast.makeText(this, "Duel en cours ! Termine le combat (ou 20 taps barre NV + 100205).", Toast.LENGTH_SHORT).show()
            return
        }
        super.onBackPressed()
    }

    override fun onStart() {
        super.onStart()
        DemonSettings.addListener(cfgListener)
        DemonAccessibilityService.addStateListener(reactiveUiRefresh)
        DemonShizukuBridge.addStateListener(reactiveUiRefresh)
        try { Shizuku.addRequestPermissionResultListener(shizukuPermListener) } catch (_: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        DemonShizukuBridge.init(applicationContext)
        val snap = settings.snapshot()
        if (DemonShizukuBridge.hasShizukuPermission()) {
            Thread {
                DemonShizukuBridge.ensureBound()
                DemonShizukuBridge.autoGrantSafeAppOps()
            }.start()
        }
        if (lastRenderedLockState != snap.challengeLocked) {
            renderScreen(snap)
        } else if (!snap.challengeLocked && !inAppBattle.isBattleActive()) {
            renderActiveTab(snap)
        }
        if (snap.enabled && !DemonCompanionService.isRunning) {
            DemonCompanionService.start(applicationContext)
        }
    }

    override fun onStop() {
        super.onStop()
        DemonSettings.removeListener(cfgListener)
        DemonAccessibilityService.removeStateListener(reactiveUiRefresh)
        DemonShizukuBridge.removeStateListener(reactiveUiRefresh)
        try { Shizuku.removeRequestPermissionResultListener(shizukuPermListener) } catch (_: Throwable) {}
        mainHandler.removeCallbacks(animTick)
    }

    override fun onDestroy() {
        inAppBattle.forceCleanup()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun applyWindowBrightnessDrain(pct: Int, cfg: DemonConfigSnapshot) {
        if (!cfg.drainBrightnessEnabled) return
        try {
            val lp = window.attributes
            lp.screenBrightness = (1f - (pct / 100f) * 0.85f).coerceIn(0.08f, 1f)
            window.attributes = lp
        } catch (_: Throwable) {}
    }

    private fun clearWindowBrightnessDrain() {
        try {
            val lp = window.attributes
            lp.screenBrightness = -1f
            window.attributes = lp
        } catch (_: Throwable) {}
    }

    @Suppress("DEPRECATION")
    private fun vibrateShort(ms: Long) {
        if (!settings.vibrateOnPrank) return
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vib.vibrate(ms)
            }
        } catch (_: Throwable) {}
    }

    @Suppress("DEPRECATION")
    private fun setImmersiveFullscreen(enable: Boolean) {
        try {
            if (enable) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.insetsController?.let { ctrl ->
                        ctrl.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                        ctrl.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                } else {
                    window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        )
                }
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                } else {
                    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                }
            }
        } catch (_: Throwable) {}
    }

    private fun startInAppDuel(
        witchDef: WitchCatalog.WitchDef,
        battleMods: WitchStoryEngine.BattleModifiers = WitchStoryEngine.BattleModifiers(),
        storyMilestoneIndex: Int = -1
    ) {
        if (inAppBattle.isBattleActive()) return
        // Optional Forced System-Wide Overlay Battle Mode (blocks entire OS via Accessibility Overlay!)
        if (rpgState.forcedOverlayBattle && DemonAccessibilityService.isServiceActive()) {
            val ok = DemonAccessibilityService.activeInstance?.launchForcedFullscreenWitchBattle(witchDef) == true
            if (ok) {
                Toast.makeText(this, "🔒 Embuscade Plein Écran Forcé : ${witchDef.name} !", Toast.LENGTH_SHORT).show()
                return
            }
        }
        Toast.makeText(this, "⚔️ Invocation de ${witchDef.name} (NV ${witchDef.level})…", Toast.LENGTH_SHORT).show()
        inAppBattle.launchBattleForWitch(
            cfg = settings.snapshot(),
            witchDef = witchDef,
            battleMods = battleMods,
            storyMilestoneIndex = storyMilestoneIndex
        )
    }

    /** Launches an atmospheric 5-Act Story Milestone OR an unpredictable suspense encounter (Fate Wheel, 3 Chests, Fog, Raid)! */
    private fun launchChapterExploration(chapter: Int) {
        if (inAppBattle.isBattleActive()) return
        val rSnap = rpgState.snapshot()
        val ev = WitchStoryEngine.generateExplorationEvent(chapter, rSnap.storyStep, settings.snapshot().playerLevel)
        showStoryEncounterDialog(ev, chapter)
    }

    private fun showStoryEncounterDialog(ev: WitchStoryEngine.ExplorationEvent, chapter: Int) {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 11, 28))
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        wrap.addView(TextView(this).apply {
            text = ev.badge
            setTextColor(Color.rgb(240, 171, 252)); textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD
        })
        wrap.addView(TextView(this).apply {
            text = ev.title
            setTextColor(Color.WHITE); textSize = 17.5f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(3), 0, dp(8))
        })
        wrap.addView(TextView(this).apply {
            text = ev.narration
            setTextColor(Color.rgb(225, 220, 240)); textSize = 13f; setPadding(0, 0, 0, dp(14))
        })

        val dlg = AlertDialog.Builder(this).setView(wrap).setCancelable(true).create()
        for (choice in ev.choices) {
            wrap.addView(makeAccentButton(choice.label, choice.colorHex) {
                dlg.dismiss()
                if (choice.corruptionDelta != 0) rpgState.adjustCorruption(choice.corruptionDelta)
                if (choice.rewardGold != 0 || choice.rewardItemKey != null) {
                    rpgState.grantReward(choice.rewardGold, choice.rewardItemKey)
                }
                when (choice.outcomeType) {
                    WitchStoryEngine.OutcomeType.START_BATTLE -> {
                        val target = choice.targetWitchId?.let { WitchCatalog.getById(it) }
                            ?: WitchCatalog.randomWitchForChapter(chapter, includeBoss = false)
                        startInAppDuel(target, choice.battleMods, ev.storyMilestoneIndex)
                    }
                    WitchStoryEngine.OutcomeType.FATE_SPIN -> handleFateWheelSpin(chapter)
                    WitchStoryEngine.OutcomeType.CHEST_PICK -> handleThreeChestsPick(chapter)
                    WitchStoryEngine.OutcomeType.GRANT_REWARD_ONLY -> {
                        AlertDialog.Builder(this)
                            .setTitle("✨ Destin du Coven")
                            .setMessage(choice.resultNarration)
                            .setPositiveButton("Continuer") { _, _ -> renderActiveTab(settings.snapshot()) }
                            .show()
                    }
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(7)
                }
            })
        }
        dlg.show()
    }

    private fun handleFateWheelSpin(chapter: Int) {
        when (Random.nextInt(5)) {
            0 -> {
                rpgState.grantReward(140, "aegis")
                AlertDialog.Builder(this)
                    .setTitle("🎰 JACKPOT DE L'ÉCLIPSE !")
                    .setMessage("La Roue s'arrête sur l'Or Runique !\nTu remportes +140 💎 et 1 🛡️ Voile d'Égide !")
                    .setPositiveButton("Super !") { _, _ -> renderActiveTab(settings.snapshot()) }
                    .show()
            }
            1 -> {
                Thread {
                    val freed = DemonMdmManager.healOneSuspendedApp(applicationContext)
                    mainHandler.post {
                        settings.update { putInt("player_hp", 100); putInt("active_drain_pct", 0) }
                        val appMsg = freed?.let { "\n🕊️ Shizuku : « $it » a été dégrisée !" } ?: ""
                        AlertDialog.Builder(this)
                            .setTitle("🎰 GRÂCE INESPÉRÉE !")
                            .setMessage("Une vague lumineuse restaure 100 % de tes PV et dissipe ton drainage !$appMsg")
                            .setPositiveButton("Continuer") { _, _ -> renderActiveTab(settings.snapshot()) }
                            .show()
                    }
                }.start()
            }
            2 -> {
                rpgState.grantReward(50, "incense")
                rpgState.grantReward(0, "crystal")
                AlertDialog.Builder(this)
                    .setTitle("🎰 PACTE DU MANA !")
                    .setMessage("Tu obtiens +50 💎, 1 🕯️ Encens de Furie et 1 💎 Cristal de Mana !")
                    .setPositiveButton("Continuer") { _, _ -> renderActiveTab(settings.snapshot()) }
                    .show()
            }
            3 -> {
                val elite = WitchCatalog.witchesInChapter(chapter).firstOrNull { it.isMiniBoss }
                    ?: WitchCatalog.randomWitchForChapter(chapter)
                Toast.makeText(this, "🎰 PIÈGE DE LA ROUE ! Embuscade Élite : ${elite.name} !", Toast.LENGTH_LONG).show()
                startInAppDuel(
                    elite,
                    WitchStoryEngine.BattleModifiers(startEmbrace = 24, forcedAppRaid = true, introContextLine = "La Roue du Destin t'a piégé devant ${elite.name} !")
                )
            }
            else -> {
                rpgState.grantReward(110, "talisman")
                settings.adjustPlayerStats(levelDelta = -1, hpDelta = 0, drainDelta = 0)
                AlertDialog.Builder(this)
                    .setTitle("🎰 TRIBUT DES OMBRES")
                    .setMessage("La Roue aspire -1 NV, mais te récompense de +110 💎 et 1 📜 Talisman de Rupture !")
                    .setPositiveButton("Continuer") { _, _ -> renderActiveTab(settings.snapshot()) }
                    .show()
            }
        }
    }

    private fun handleThreeChestsPick(chapter: Int) {
        val isMimicTrap = Random.nextInt(3) == 0 // 1 in 3 chance of a Mimic Witch Trap!
        if (isMimicTrap) {
            val w = WitchCatalog.randomWitchForChapter(chapter, includeBoss = false)
            Toast.makeText(this, "📦 PIÈGE ! Une Sorcière Mimique bondit du coffre !", Toast.LENGTH_LONG).show()
            startInAppDuel(
                w,
                WitchStoryEngine.BattleModifiers(
                    startEmbrace = 26,
                    startCaptured = true,
                    introContextLine = "PIÈGE DU COFFRE ! ${w.name} t'attrape par surprise dès le 1er tour !"
                )
            )
        } else {
            val prize = listOf("elixir" to "🧪 Élixir", "talisman" to "📜 Talisman", "aegis" to "🛡️ Voile d'Égide", "incense" to "🕯️ Encens de Furie").random()
            val gold = Random.nextInt(75, 135)
            rpgState.grantReward(gold, prize.first)
            AlertDialog.Builder(this)
                .setTitle("📦 TRÉSOR DU COVEN !")
                .setMessage("Bonne pioche ! Le coffre contient +$gold 💎 et 1 ${prize.second} !")
                .setPositiveButton("Génial !") { _, _ -> renderActiveTab(settings.snapshot()) }
                .show()
        }
    }

    private fun startInfiniteTower() {
        if (inAppBattle.isBattleActive()) return
        Toast.makeText(this, "🗼 Ascension de la Tour Infinie — Étage 1 !", Toast.LENGTH_SHORT).show()
        inAppBattle.launchTowerRun(settings.snapshot(), floor = 1)
    }

    private fun runShizukuSuspendTest() {
        Toast.makeText(this, "🧪 Test Shizuku en cours (grise 1 appli 2.5s puis restaure)…", Toast.LENGTH_SHORT).show()
        Thread {
            val report = DemonMdmManager.testShizukuSuspendDiagnostic(applicationContext)
            mainHandler.post {
                AlertDialog.Builder(this)
                    .setTitle("🧪 Diagnostic Shizuku")
                    .setMessage(report)
                    .setPositiveButton("OK") { _, _ -> renderActiveTab(settings.snapshot()) }
                    .show()
            }
        }.start()
    }

    private fun renderScreen(snap: DemonConfigSnapshot) {
        lastRenderedLockState = snap.challengeLocked
        mainHandler.removeCallbacks(animTick)
        previewPetView = null
        lockedPetAnimView = null
        lockedTapHintTv = null
        mainContentHost.removeAllViews()

        if (snap.challengeLocked) {
            mainContentHost.addView(buildLockedChallengeGate(snap))
            mainHandler.post(animTick)
        } else {
            mainContentHost.addView(buildTabbedShell(snap))
            renderActiveTab(snap)
        }
    }

    private fun updateHeaderPill(snap: DemonConfigSnapshot) {
        val rSnap = rpgState.snapshot()
        headerPillTv?.text = "NV ${snap.playerLevel} • 💎 ${rSnap.gold}"
    }

    // -------------------------------------------------------------------------
    // LOCKED CHALLENGE GATE (20 taps + 100205)
    // -------------------------------------------------------------------------
    private fun buildLockedChallengeGate(snap: DemonConfigSnapshot): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BG_DARK)
            setPadding(dp(24), dp(36), dp(24), dp(36))
        }

        wrap.addView(TextView(this).apply {
            text = "🔒 ${snap.avatarName} verrouille les réglages"
            setTextColor(Color.WHITE); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        wrap.addView(TextView(this).apply {
            text = "Paramètres Android, Administrateur et réglages protégés.\nTu peux combattre les Sorcières, ou tapoter le démon 20× + code 100205 pour déverrouiller."
            setTextColor(Color.rgb(185, 185, 200)); textSize = 12.8f; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(18))
        })

        var tapCounter = 0
        var lastTapMs = 0L

        val petView = DemonPetView(
            context = this,
            onDragMove = { _, _ -> },
            onDragRelease = { _, _, wasTap ->
                if (wasTap) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapMs > 1800L) tapCounter = 1 else tapCounter++
                    lastTapMs = now
                    lockedTapHintTv?.text = when {
                        tapCounter >= DemonSettings.REQUIRED_EXIT_TAPS -> "🔓 Saisis le code !"
                        tapCounter >= 5 -> "Tapotes : $tapCounter / ${DemonSettings.REQUIRED_EXIT_TAPS}…"
                        else -> "Tapote le démon 20× de suite pour débloquer"
                    }
                }
            },
            onGentlePet = {},
            onSecretTwentyTaps = { showExitPinDialog() }
        ).apply {
            updateConfig(snap)
            pose = DemonPetView.PetPose.WALKING
            say("Tu ne peux pas fuir le Coven ! 😈", 4_000L)
        }
        lockedPetAnimView = petView

        val petCard = FrameLayout(this).apply {
            background = roundedBg(CARD_DARK, 28, strokeColor = Color.argb(145, 217, 70, 239), strokeDp = 2)
            addView(petView, FrameLayout.LayoutParams(dp(200), dp(228), Gravity.CENTER))
        }
        wrap.addView(petCard, LinearLayout.LayoutParams(dp(230), dp(250)))

        wrap.addView(makeAccentButton("⚔️ Lancer un Duel Sorcière (Mode Verrouillé)", Color.rgb(162, 28, 175)) {
            startInAppDuel(WitchCatalog.randomWitchForChapter(rpgState.selectedChapter, includeBoss = true))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) }
        })

        val hintTv = TextView(this).apply {
            text = "Tapote le démon 20× de suite pour débloquer"
            setTextColor(Color.rgb(253, 164, 175)); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(6))
        }
        lockedTapHintTv = hintTv
        wrap.addView(hintTv)

        wrap.addView(TextView(this).apply {
            text = "NV ${snap.playerLevel} • ${snap.mood.emoji} ${snap.mood.label} • Marche aussi sur le démon flottant"
            setTextColor(Color.rgb(145, 145, 162)); textSize = 11.5f; gravity = Gravity.CENTER
        })
        return wrap
    }

    private fun showExitPinDialog() {
        val input = EditText(this).apply {
            hint = "Code secret (100205)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(Color.WHITE); setHintTextColor(Color.rgb(145, 145, 162))
            setPadding(dp(14), dp(12), dp(14), dp(12)); background = roundedBg(Color.rgb(30, 27, 42), 12)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(14), dp(20), dp(8)); setBackgroundColor(Color.rgb(20, 18, 28))
            addView(TextView(this@MainActivity).apply {
                text = "Saisis le code pour déverrouiller les Paramètres, dégriser 100 % des applis et mettre le démon en pause :"
                setTextColor(Color.rgb(225, 225, 234)); textSize = 13f; setPadding(0, 0, 0, dp(12))
            })
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("🔓 Déverrouillage DemonMom RPG")
            .setView(box)
            .setPositiveButton("Déverrouiller") { _, _ ->
                if (settings.verifyExitPin(input.text?.toString().orEmpty())) performFullUnlock()
                else {
                    Toast.makeText(this, "Mauvais code ! 😈", Toast.LENGTH_SHORT).show()
                    lockedPetAnimView?.say("Raté ! Mauvais code 😈", 3_000L)
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun performFullUnlock() {
        Thread {
            val msg = DemonMdmManager.restoreEverything(applicationContext, releaseDeviceOwner = false)
            mainHandler.post {
                DemonDeviceAdminReceiver.removeAdminSafely(applicationContext)
                DemonAccessibilityService.activeInstance?.performSecretExitUnlock()
                settings.update {
                    putBoolean("challenge_locked", false)
                    putBoolean("strict_settings_lock", false)
                    putBoolean("enabled", false)
                    putInt("active_drain_pct", 0)
                    putInt("player_hp", 100)
                }
                DemonCompanionService.stop(applicationContext)
                Toast.makeText(this, "🔓 Code 100205 accepté ! $msg", Toast.LENGTH_LONG).show()
                renderScreen(settings.snapshot())
            }
        }.start()
    }

    private fun performEndGameRestoreAll() {
        Toast.makeText(this, "Restauration complète du téléphone en cours…", Toast.LENGTH_SHORT).show()
        Thread {
            val msg = DemonMdmManager.restoreEverything(applicationContext, releaseDeviceOwner = false)
            mainHandler.post {
                settings.update {
                    putInt("player_level", 20)
                    putInt("player_hp", 100)
                    putInt("active_drain_pct", 0)
                    putInt("mood_score", 30)
                }
                clearWindowBrightnessDrain()
                Toast.makeText(this, "🕊️ Fin du jeu ! $msg", Toast.LENGTH_LONG).show()
                renderActiveTab(settings.snapshot())
            }
        }.start()
    }

    private fun handleShizukuAction() {
        DemonShizukuBridge.init(applicationContext)
        when {
            DemonShizukuBridge.hasShizukuPermission() -> {
                Thread {
                    DemonShizukuBridge.ensureBound()
                    DemonShizukuBridge.autoGrantSafeAppOps()
                    mainHandler.post {
                        Toast.makeText(this, "⚡ Shizuku connecté ! ${DemonShizukuBridge.getDiagnostics(this)}", Toast.LENGTH_LONG).show()
                        renderActiveTab(settings.snapshot())
                    }
                }.start()
            }
            DemonShizukuBridge.isShizukuRunning() -> {
                DemonShizukuBridge.requestShizukuPermission()
            }
            DemonShizukuBridge.isShizukuInstalled(this) -> {
                Toast.makeText(this, "Lance Shizuku via « Débogage sans fil » (statut: en cours), puis reviens ici !", Toast.LENGTH_LONG).show()
                DemonShizukuBridge.openShizukuApp(this)
            }
            else -> {
                Toast.makeText(this, "Installe d'abord l'app gratuite Shizuku (Play Store ou GitHub) !", Toast.LENGTH_LONG).show()
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases/latest"))) } catch (_: Throwable) {}
            }
        }
    }

    private fun copyTermuxMdmScript() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("DemonMom Termux MDM", DemonMdmManager.buildTermuxOneLineScript()))
        Toast.makeText(this, "📋 Script Termux copié !", Toast.LENGTH_LONG).show()
    }

    // -------------------------------------------------------------------------
    // 4-TAB FULL RPG GAME SHELL
    // -------------------------------------------------------------------------
    private fun buildTabbedShell(snap: DemonConfigSnapshot): View {
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_DARK)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        header.addView(TextView(this).apply {
            text = "🔮 DemonMom RPG"
            setTextColor(Color.WHITE); textSize = 19.5f; typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val rSnap = rpgState.snapshot()
        val pill = TextView(this).apply {
            text = "NV ${snap.playerLevel} • 💎 ${rSnap.gold}"
            setTextColor(Color.WHITE); textSize = 11.8f; typeface = Typeface.DEFAULT_BOLD
            background = roundedBg(Color.rgb(42, 20, 54), 14, strokeColor = Color.argb(165, 217, 70, 239), strokeDp = 1)
            setPadding(dp(11), dp(5), dp(11), dp(5))
            setOnClickListener {
                activeTab = 2; updateTabBarStyles(); renderActiveTab(settings.snapshot())
            }
        }
        headerPillTv = pill
        header.addView(pill)
        shell.addView(header)

        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(2), dp(12), dp(10))
        }
        tabButtons.clear()
        val titles = listOf("🗺️ Aventure", "📖 Grimoire", "⚒️ Forge & Sac", "⚙️ Dossier & IA")
        titles.forEachIndexed { idx, label ->
            val b = Button(this).apply {
                text = label; isAllCaps = false; textSize = 10.8f; typeface = Typeface.DEFAULT_BOLD
                minHeight = 0; minimumHeight = 0; setPadding(dp(3), dp(8), dp(3), dp(8))
                setOnClickListener {
                    activeTab = idx; updateTabBarStyles(); renderActiveTab(settings.snapshot())
                }
            }
            tabButtons.add(b)
            tabBar.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (idx > 0) marginStart = dp(5)
            })
        }
        shell.addView(tabBar)

        val container = FrameLayout(this)
        tabContentContainer = container
        shell.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        updateTabBarStyles()
        return shell
    }

    private fun updateTabBarStyles() {
        tabButtons.forEachIndexed { idx, b ->
            val sel = idx == activeTab
            b.background = roundedBg(
                if (sel) Color.rgb(162, 28, 175) else Color.rgb(25, 21, 38), 12,
                strokeColor = if (sel) Color.rgb(240, 171, 252) else Color.argb(35, 255, 255, 255), strokeDp = 1
            )
            b.setTextColor(if (sel) Color.WHITE else Color.rgb(175, 175, 195))
        }
    }

    private fun renderActiveTab(snap: DemonConfigSnapshot) {
        val box = tabContentContainer ?: return
        updateHeaderPill(snap)
        mainHandler.removeCallbacks(animTick)
        previewPetView = null
        box.removeAllViews()

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(28))
        }
        scroll.addView(inner)

        when (activeTab) {
            0 -> populateAdventureTab(inner, snap)
            1 -> populateGrimoireTab(inner)
            2 -> populateForgeAndDemonTab(inner, snap)
            3 -> populateFolderAndAiTab(inner, snap)
        }
        box.addView(scroll)
        if (previewPetView != null) mainHandler.post(animTick)
        refreshMediaSummaryAsync(snap)
    }

    private fun obedienceRankFor(level: Int): String = when {
        level >= 25 -> "Rebelle"
        level >= 15 -> "Apprenti"
        level >= 8 -> "Captif"
        level >= 3 -> "Serviteur"
        else -> "Soumis Absolu"
    }

    // ==================== TAB 0 : 🗺️ AVENTURE (WORLD MAP, TOWER, QUESTS, SHIZUKU) ====================
    private fun populateAdventureTab(root: LinearLayout, snap: DemonConfigSnapshot) {
        val rSnap = rpgState.snapshot()

        // 1. Player Hero RPG Card
        val hero = makeCard(accentStroke = Color.argb(145, 217, 70, 239))
        val heroRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

        val pv = DemonPetView(
            context = this, onDragMove = { _, _ -> }, onDragRelease = { _, _, _ -> },
            onGentlePet = { settings.adjustMood(-5) }, onSecretTwentyTaps = {}
        ).apply { updateConfig(snap); pose = DemonPetView.PetPose.WALKING }
        previewPetView = pv
        heroRow.addView(pv, LinearLayout.LayoutParams(dp(94), dp(114)))

        val statsCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), 0, 0, 0) }
        statsCol.addView(TextView(this).apply {
            text = "NV ${snap.playerLevel} · ${obedienceRankFor(snap.playerLevel)}"
            setTextColor(Color.WHITE); textSize = 17f; typeface = Typeface.DEFAULT_BOLD
        })
        statsCol.addView(TextView(this).apply {
            text = "PV ${rSnap.maxHp} • PM ${rSnap.maxMp} • ATK ×${String.format("%.2f", rSnap.attackMultiplier)} • 💎 ${rSnap.gold}"
            setTextColor(Color.rgb(240, 171, 252)); textSize = 11.4f; typeface = Typeface.MONOSPACE; setPadding(0, dp(3), 0, dp(4))
        })

        val xpBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = rSnap.xp.coerceIn(0, 100)
            progressTintList = ColorStateList.valueOf(Color.rgb(217, 70, 239))
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(42, 34, 58))
        }
        statsCol.addView(xpBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)).apply { bottomMargin = dp(5) })

        val aiBadge = if (rSnap.geminiApiKey.isNotBlank()) "✨ Gemini + Locale" else "⚡ IA Locale 0 ms"
        statsCol.addView(TextView(this).apply {
            text = "📜 Intrigue : ${rSnap.storyStep}/15 • ${rSnap.alignmentLabel}\n📖 ${rSnap.defeatedIds.size}/50 • 🗼 Tour #${rSnap.towerBestFloor} • $aiBadge"
            setTextColor(Color.rgb(180, 180, 202)); textSize = 11.1f
        })
        heroRow.addView(statsCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hero.addView(heroRow)

        if (snap.mediaFolderPath.isBlank() && snap.mediaTreeUri.isBlank()) {
            hero.addView(makeAccentButton("📂 Choisir ton dossier photos pour les 50 Sorcières →", Color.rgb(126, 34, 206)) {
                activeTab = 3; updateTabBarStyles(); renderActiveTab(settings.snapshot())
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            })
        }
        root.addView(hero)

        // 2. 5-Chapter World Map + Infinite Witch Tower Card
        val chCard = makeCard(accentStroke = Color.argb(135, 244, 63, 94))
        chCard.addView(sectionTitle("🗺️ Campagne du Coven (5 Chapitres · 50 Sorcières)"))

        val curCh = rSnap.selectedChapter.coerceIn(1, 5)
        val chRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(10)) }
        WitchCatalog.CHAPTERS.forEachIndexed { idx, ch ->
            val unlocked = ch.id <= rSnap.unlockedChapter
            val selected = ch.id == curCh
            val btn = Button(this).apply {
                text = if (unlocked) "Ch.${ch.id}" else "🔒${ch.id}"
                isAllCaps = false; textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(when { selected -> Color.WHITE; unlocked -> Color.rgb(225, 210, 245); else -> Color.rgb(125, 125, 145) })
                background = roundedBg(
                    when { selected -> Color.rgb(225, 29, 72); unlocked -> Color.rgb(36, 26, 52); else -> Color.rgb(22, 19, 32) }, 10,
                    strokeColor = if (selected) Color.rgb(253, 164, 175) else Color.argb(40, 255, 255, 255), strokeDp = 1
                )
                minHeight = 0; minimumHeight = 0; setPadding(dp(2), dp(8), dp(2), dp(8))
                setOnClickListener {
                    if (!unlocked) {
                        val prevBoss = WitchCatalog.bossOfChapter(ch.id - 1)
                        Toast.makeText(this@MainActivity, "Bats ${prevBoss.name} (Boss Ch.${ch.id - 1}) pour débloquer le Chapitre ${ch.id} !", Toast.LENGTH_LONG).show()
                    } else {
                        rpgState.selectedChapter = ch.id; grimoireFilter = ch.id; renderActiveTab(settings.snapshot())
                    }
                }
            }
            chRow.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (idx > 0) marginStart = dp(5) })
        }
        chCard.addView(chRow)

        val chInfo = WitchCatalog.CHAPTERS[curCh - 1]
        val chDefeated = WitchCatalog.witchesInChapter(curCh).count { rSnap.defeatedIds.contains(it.id) }
        val chBoss = WitchCatalog.bossOfChapter(curCh)
        val bossBeat = rSnap.defeatedIds.contains(chBoss.id)

        val chDetail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Color.rgb(28, 18, 42), 14, strokeColor = Color.argb(75, 217, 70, 239), strokeDp = 1)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        chDetail.addView(TextView(this).apply {
            text = "Chapitre $curCh : ${chInfo.name} (NV ${chInfo.minLevel}–${chInfo.maxLevel})"
            setTextColor(Color.WHITE); textSize = 14.3f; typeface = Typeface.DEFAULT_BOLD
        })
        chDetail.addView(TextView(this).apply {
            text = "${chInfo.subtitle}\nProgression : $chDefeated/10 vaincues • Boss : ${chBoss.name} ${if (bossBeat) "✅" else "👑"}"
            setTextColor(Color.rgb(208, 196, 228)); textSize = 11.6f; setPadding(0, dp(3), 0, 0)
        })
        chCard.addView(chDetail)

        val storyInCh = rSnap.storyStep in ((curCh - 1) * 3)..((curCh - 1) * 3 + 2)
        val exploreLabel = if (storyInCh) {
            "📜 EXPLORER — SUITE DE L'INTRIGUE (Ch.$curCh)"
        } else {
            "🎲 EXPLORER — HASARD, COFFRES & DUEL (Ch.$curCh)"
        }
        chCard.addView(makeAccentButton(exploreLabel, Color.rgb(225, 29, 72)) {
            launchChapterExploration(curCh)
        }.apply {
            textSize = 13.4f; setPadding(dp(12), dp(13), dp(12), dp(13))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        })

        chCard.addView(makeAccentButton("👑 DÉFIER LE BOSS : ${chBoss.name} (NV ${chBoss.level})", Color.rgb(162, 28, 175)) {
            startInAppDuel(chBoss)
        }.apply {
            textSize = 12.8f; setPadding(dp(12), dp(11), dp(12), dp(11))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        })

        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        modeRow.addView(makeAccentButton("🗼 Tour Infinie (#${rSnap.towerBestFloor})", Color.rgb(180, 83, 9)) {
            startInfiniteTower()
        }, gridLp(endMargin = dp(5)))
        modeRow.addView(makeGridTestBtn("🎲 Aléatoire (1–50)") {
            startInAppDuel(WitchCatalog.ALL_WITCHES.random())
        }, gridLp(startMargin = dp(5)))
        chCard.addView(modeRow)
        root.addView(chCard)

        // 3. Claimable RPG Quests Card
        val quests = rpgState.getQuests().filter { !it.claimed }
        if (quests.isNotEmpty()) {
            val qCard = makeCard(accentStroke = Color.argb(120, 245, 158, 11))
            qCard.addView(sectionTitle("🏆 Quêtes & Défis Tactiques"))
            for (q in quests.take(4)) {
                val qRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = roundedBg(Color.rgb(28, 22, 42), 11); setPadding(dp(10), dp(7), dp(10), dp(7))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
                }
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(this).apply {
                    text = "${q.title} (${q.currentProgress.coerceAtMost(q.targetProgress)}/${q.targetProgress})"
                    setTextColor(Color.WHITE); textSize = 12.2f; typeface = Typeface.DEFAULT_BOLD
                })
                col.addView(TextView(this).apply {
                    text = "Récompense : ${q.rewardText}"
                    setTextColor(Color.rgb(253, 224, 71)); textSize = 11f
                })
                qRow.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (q.completed) {
                    qRow.addView(Button(this).apply {
                        text = "🎁 Réclamer"
                        isAllCaps = false; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                        background = roundedBg(Color.rgb(16, 128, 96), 9); minHeight = 0; minimumHeight = 0; setPadding(dp(10), dp(6), dp(10), dp(6))
                        setOnClickListener {
                            if (rpgState.claimQuest(q.id)) {
                                Toast.makeText(this@MainActivity, "🎁 Quête réclamée : ${q.rewardText} !", Toast.LENGTH_SHORT).show()
                                renderActiveTab(settings.snapshot())
                            }
                        }
                    })
                }
                qCard.addView(qRow)
            }
            root.addView(qCard)
        }

        // 4. Shizuku Live System Punishments & Instant Test Card
        val sysCard = makeCard(accentStroke = Color.argb(125, 16, 185, 129))
        val privOk = DemonMdmManager.hasPrivilegedControl(this)
        val susp = DemonMdmManager.getSuspendedPackages(this)
        val hid = DemonMdmManager.getHiddenPackages(this)
        val curses = DemonMdmManager.getActiveCurses(this)

        sysCard.addView(sectionTitle("⚡ Punitions Système (Shizuku Android 16)"))
        sysCard.addView(TextView(this).apply {
            text = "${DemonShizukuBridge.getDiagnostics(this@MainActivity)}\n" +
                "• ${susp.size + hid.size} appli(s) grisée(s) par ordre d'usage • ${curses.size} sceau(x)\n" +
                "• Victoire = +1 appli libérée • Restauration 100 % garantie !"
            setTextColor(if (privOk) Color.rgb(167, 243, 208) else Color.rgb(253, 186, 116))
            textSize = 11.4f; setPadding(0, dp(4), 0, dp(8))
        })

        val sysRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (!privOk) {
            sysRow.addView(makeAccentButton("⚡ Autoriser Shizuku", Color.rgb(126, 34, 206)) {
                handleShizukuAction()
            }, gridLp(endMargin = dp(5)))
        } else {
            sysRow.addView(makeAccentButton("🧪 Tester (Griser 1 app)", Color.rgb(2, 132, 199)) {
                runShizukuSuspendTest()
            }, gridLp(endMargin = dp(5)))
        }
        sysRow.addView(makeAccentButton("🕊️ Tout restaurer", Color.rgb(16, 128, 96)) {
            performEndGameRestoreAll()
        }, gridLp(startMargin = dp(5)))
        sysCard.addView(sysRow)
        root.addView(sysCard)
    }

    // ==================== TAB 1 : 📖 GRIMOIRE (ALL 50 WITCHES BESTIARY) ====================
    private fun populateGrimoireTab(root: LinearLayout) {
        val rSnap = rpgState.snapshot()

        val headCard = makeCard(accentStroke = Color.argb(120, 217, 70, 239))
        headCard.addView(sectionTitle("📖 Grimoire des 50 Sorcières"))
        headCard.addView(sectionSub(
            "${rSnap.defeatedIds.size}/50 vaincues  •  ${rSnap.encounteredIds.size}/50 rencontrées  •  ${rSnap.lostToIds.size} défaite(s)\n" +
                "6 attaques spéciales & envoûtements uniques par Sorcière + portrait HD détouré depuis ton dossier !"
        ))

        val filterRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(5)) }
        listOf(1 to "Ch.1", 2 to "Ch.2", 3 to "Ch.3").forEachIndexed { idx, (code, label) ->
            filterRow1.addView(makeFilterChip(label, grimoireFilter == code) {
                grimoireFilter = code; renderActiveTab(settings.snapshot())
            }, gridLp(startMargin = if (idx > 0) dp(4) else 0, endMargin = if (idx < 2) dp(4) else 0))
        }
        headCard.addView(filterRow1)

        val filterRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(4 to "Ch.4", 5 to "Ch.5", 0 to "👑 Boss (10)").forEachIndexed { idx, (code, label) ->
            filterRow2.addView(makeFilterChip(label, grimoireFilter == code) {
                grimoireFilter = code; renderActiveTab(settings.snapshot())
            }, gridLp(startMargin = if (idx > 0) dp(4) else 0, endMargin = if (idx < 2) dp(4) else 0))
        }
        headCard.addView(filterRow2)
        root.addView(headCard)

        val list = if (grimoireFilter == 0) {
            WitchCatalog.ALL_WITCHES.filter { it.isBoss || it.isMiniBoss }
        } else {
            WitchCatalog.witchesInChapter(grimoireFilter)
        }

        for (w in list) {
            val defeated = rSnap.defeatedIds.contains(w.id)
            val lostTo = rSnap.lostToIds.contains(w.id)
            val seen = rSnap.encounteredIds.contains(w.id)

            val stroke = when {
                w.isBoss -> Color.argb(190, 245, 158, 11)
                w.isMiniBoss -> Color.argb(165, 244, 63, 94)
                defeated -> Color.argb(120, 16, 185, 129)
                else -> Color.argb(65, 217, 70, 239)
            }
            val card = makeCard(accentStroke = stroke)

            val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val statusIcon = when { defeated -> "✅ Vaincue"; lostTo -> "💋 Défaite"; seen -> "👁️ Vue"; else -> "❓ Nouvelle" }
            topRow.addView(TextView(this).apply {
                text = "#${w.id} ${w.displayTitle}"
                setTextColor(if (w.isBoss) Color.rgb(253, 224, 71) else Color.WHITE); textSize = 13.6f; typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            topRow.addView(TextView(this).apply {
                text = statusIcon
                setTextColor(when { defeated -> Color.rgb(167, 243, 208); lostTo -> Color.rgb(244, 114, 182); else -> Color.rgb(185, 185, 205) })
                textSize = 11.2f; typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(topRow)

            card.addView(TextView(this).apply {
                text = "NV ${w.level} • ${w.archetype.label} • ${w.element} • Résol. ${w.maxResolve} • +${w.rewardXp} XP • +${w.rewardGold} 💎"
                setTextColor(Color.rgb(208, 198, 232)); textSize = 11.2f; setPadding(0, dp(3), 0, dp(4))
            })
            card.addView(TextView(this).apply {
                text = "Ultime : « ${w.ultimateName} »\n📌 ${com.demonpet.app.rpg.WitchCombatAi.archetypePassiveSummary(w.archetype)}\n💡 ${w.weaknessTip}"
                setTextColor(Color.rgb(156, 156, 178)); textSize = 11.1f; setPadding(0, 0, 0, dp(8))
            })
            card.addView(makeAccentButton(
                if (w.isBoss) "👑 Combattre le Boss ${w.name}" else "⚔️ Affronter ${w.name}",
                if (w.isBoss) Color.rgb(180, 83, 9) else if (w.isMiniBoss) Color.rgb(190, 24, 93) else Color.rgb(126, 34, 206)
            ) {
                startInAppDuel(w)
            })
            root.addView(card)
        }
    }

    private fun makeFilterChip(label: String, active: Boolean, onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (active) Color.WHITE else Color.rgb(190, 190, 210))
        background = roundedBg(
            if (active) Color.rgb(225, 29, 72) else Color.rgb(30, 26, 44), 10,
            strokeColor = if (active) Color.rgb(253, 164, 175) else Color.argb(35, 255, 255, 255), strokeDp = 1
        )
        minHeight = 0; minimumHeight = 0; setPadding(dp(4), dp(7), dp(4), dp(7))
        setOnClickListener { onClick() }
    }

    // ==================== TAB 2 : ⚒️ FORGE D'ÂME, BOUTIQUE 6 RELIQUES & COMPAGNON DÉMON ====================
    private fun populateForgeAndDemonTab(root: LinearLayout, snap: DemonConfigSnapshot) {
        val rSnap = rpgState.snapshot()

        // 1. Permanent Soul Forge Upgrades (Rank 1..10!)
        val forgeCard = makeCard(accentStroke = Color.argb(145, 234, 88, 12))
        forgeCard.addView(sectionTitle("⚒️ Forge d'Âme — Améliorations Permanentes (💎 ${rSnap.gold})"))
        forgeCard.addView(sectionSub("Renforce définitivement ton héros pour vaincre les Archisorcières et grimper dans la Tour Infinie !"))

        data class ForgeSlot(val key: String, val name: String, val desc: String, val rank: Int, val color: Int)
        val slots = listOf(
            ForgeSlot("blade", "🗡️ Lame de Volonté", "+12 % Dégâts (actuel ×${String.format("%.2f", rSnap.attackMultiplier)})", rSnap.bladeRank, Color.rgb(225, 29, 72)),
            ForgeSlot("cloak", "🛡️ Manteau Runique", "+14 PV Max & -4 % Emprise (PV Max ${rSnap.maxHp})", rSnap.cloakRank, Color.rgb(16, 128, 96)),
            ForgeSlot("grimoire", "🔮 Grimoire Intérieur", "+10 PM Max & +2 SP/tour (PM Max ${rSnap.maxMp})", rSnap.grimoireRank, Color.rgb(37, 99, 235)),
            ForgeSlot("ward", "🧿 Sceau Anti-Charme", "Résistance Envoûtements & fenêtre QTE", rSnap.wardRank, Color.rgb(126, 34, 206))
        )

        for (slot in slots) {
            val cost = rpgState.forgeUpgradeCost(slot.rank)
            val isMax = slot.rank >= 10
            val canAfford = !isMax && rSnap.gold >= cost
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = roundedBg(Color.rgb(28, 22, 42), 12); setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(TextView(this).apply {
                text = "${slot.name}  [Nv.${slot.rank}/10]"
                setTextColor(Color.WHITE); textSize = 12.8f; typeface = Typeface.DEFAULT_BOLD
            })
            info.addView(TextView(this).apply {
                text = slot.desc; setTextColor(Color.rgb(175, 175, 198)); textSize = 11f
            })
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(Button(this).apply {
                text = if (isMax) "MAX ✅" else "Forger $cost 💎"
                isAllCaps = false; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                background = roundedBg(if (canAfford) slot.color else Color.rgb(52, 48, 64), 10)
                minHeight = 0; minimumHeight = 0; setPadding(dp(10), dp(7), dp(10), dp(7))
                setOnClickListener {
                    if (isMax) return@setOnClickListener
                    if (rpgState.upgradeForge(slot.key)) {
                        Toast.makeText(this@MainActivity, "⚒️ ${slot.name} améliorée au Nv.${slot.rank + 1} !", Toast.LENGTH_SHORT).show()
                        renderActiveTab(settings.snapshot())
                    } else {
                        Toast.makeText(this@MainActivity, "Besoin de $cost 💎 !", Toast.LENGTH_SHORT).show()
                    }
                }
            })
            forgeCard.addView(row)
        }
        root.addView(forgeCard)

        // 2. 6 Consumable Relics Shop
        val shopCard = makeCard(accentStroke = Color.argb(135, 245, 158, 11))
        shopCard.addView(sectionTitle("🎒 Sac & Boutique des 6 Reliques"))
        data class ShopItem(val key: String, val title: String, val desc: String, val count: Int, val cost: Int, val color: Int)
        val items = listOf(
            ShopItem("elixir", "🧪 Élixir de Volonté", "+65 PV en combat", rSnap.elixirs, 35, Color.rgb(16, 128, 96)),
            ShopItem("crystal", "💎 Cristal de Mana", "+45 PM et +25 SP", rSnap.crystals, 30, Color.rgb(37, 99, 235)),
            ShopItem("talisman", "📜 Talisman de Rupture", "Brise instantanément Capture + -50 % Emprise", rSnap.talismans, 55, Color.rgb(180, 83, 9)),
            ShopItem("amulet", "🔮 Amulette de Clarté", "Purifie Charme, Hypnose, Siphon & Drain", rSnap.amulets, 45, Color.rgb(126, 34, 206)),
            ShopItem("incense", "🕯️ Encens de Furie", "Attaques ×1.6 pendant 3 tours", rSnap.incenses, 60, Color.rgb(190, 24, 93)),
            ShopItem("aegis", "🛡️ Voile d'Égide", "Absorbe 100 % de la prochaine attaque/capture", rSnap.aegisVeils, 75, Color.rgb(14, 116, 144))
        )

        for (shopItem in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = roundedBg(Color.rgb(28, 22, 42), 12); setPadding(dp(10), dp(7), dp(10), dp(7))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) }
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(TextView(this).apply {
                text = "${shopItem.title}  (x${shopItem.count})"
                setTextColor(Color.WHITE); textSize = 12.6f; typeface = Typeface.DEFAULT_BOLD
            })
            info.addView(TextView(this).apply {
                text = shopItem.desc; setTextColor(Color.rgb(175, 175, 196)); textSize = 11f
            })
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val canBuy = rSnap.gold >= shopItem.cost
            row.addView(Button(this).apply {
                text = "${shopItem.cost} 💎"
                isAllCaps = false; textSize = 11.2f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                background = roundedBg(if (canBuy) shopItem.color else Color.rgb(52, 48, 64), 10)
                minHeight = 0; minimumHeight = 0; setPadding(dp(12), dp(7), dp(12), dp(7))
                setOnClickListener {
                    if (rpgState.buyItem(shopItem.key, shopItem.cost)) {
                        Toast.makeText(this@MainActivity, "${shopItem.title} acheté !", Toast.LENGTH_SHORT).show()
                        renderActiveTab(settings.snapshot())
                    } else {
                        Toast.makeText(this@MainActivity, "Pas assez d'Éclats 💎 !", Toast.LENGTH_SHORT).show()
                    }
                }
            })
            shopCard.addView(row)
        }
        root.addView(shopCard)

        // 3. Witch Full Powers & Forced Fullscreen Controls Card
        val fullPowCard = makeCard(accentStroke = Color.argb(150, 217, 70, 239))
        fullPowCard.addView(sectionTitle("👑 Pleins Pouvoirs des Sorcières & Plein Écran"))
        fullPowCard.addView(sectionSub("Contrôle jusqu'où les Sorcières peuvent influencer ton téléphone et ton écran tactile selon ton Niveau !"))
        fullPowCard.addView(makeSwitchRow("🔒 Plein Écran Immersif Forcé en combat (masque barres système)", rSnap.forcedFullscreen) {
            rpgState.forcedFullscreen = it
        })
        fullPowCard.addView(makeSwitchRow("🖥️ Forcer le combat en Overlay Système (bloque tout l'OS via Accessibilité)", rSnap.forcedOverlayBattle) {
            rpgState.forcedOverlayBattle = it
            if (it && !DemonAccessibilityService.isServiceActive()) {
                Toast.makeText(this, "Active l'Accessibilité dans ⚙️ Dossier & IA pour le mode Overlay Système !", Toast.LENGTH_LONG).show()
            }
        })
        fullPowCard.addView(makeSwitchRow("⚡ Attaques sur tes Applis EN PLEIN COMBAT (Otage Shizuku)", rSnap.midBattleAppPowers) {
            rpgState.midBattleAppPowers = it
        })
        fullPowCard.addView(makeSwitchRow("🖐️ Sabotage Tactile en combat (boutons fuyants, givre, leurres, brouillard)", rSnap.touchSabotageEnabled) {
            rpgState.touchSabotageEnabled = it
        })
        root.addView(fullPowCard)

        // 4. Living Demon Companion & System Ambushes Card
        val demonCard = makeCard(accentStroke = Color.argb(120, 244, 63, 94))
        demonCard.addView(sectionTitle("😈 Compagnon Démon & Embuscades Système"))
        val sw = Switch(this).apply {
            isChecked = snap.enabled
            text = if (snap.enabled) "Compagnon Démon & Embuscades ACTIFS" else "Activer le Compagnon flottant"
            setTextColor(Color.WHITE); textSize = 13.4f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(4), 0, dp(8))
            setOnCheckedChangeListener { _, checked ->
                text = if (checked) "Compagnon Démon & Embuscades ACTIFS" else "Activer le Compagnon flottant"
                settings.enabled = checked
                if (checked) DemonCompanionService.start(applicationContext) else DemonCompanionService.stop(applicationContext)
            }
        }
        masterSwitch = sw
        demonCard.addView(sw)

        demonCard.addView(smallLabel("Sévérité des embuscades & punitions :"))
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        SeverityPreset.values().forEachIndexed { idx, preset ->
            val active = snap.severityPreset == preset
            val btn = Button(this).apply {
                text = "${preset.emoji}\n${preset.label}"
                isAllCaps = false; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (active) Color.WHITE else Color.rgb(195, 195, 210))
                background = roundedBg(
                    if (active) Color.rgb(225, 29, 72) else Color.rgb(32, 28, 46), 11,
                    strokeColor = if (active) Color.rgb(253, 164, 175) else Color.argb(35, 255, 255, 255), strokeDp = 1
                )
                minHeight = 0; minimumHeight = 0; setPadding(dp(4), dp(7), dp(4), dp(7))
                setOnClickListener {
                    settings.applySeverityPreset(preset)
                    Toast.makeText(this@MainActivity, "Profil ${preset.label} appliqué ${preset.emoji}", Toast.LENGTH_SHORT).show()
                    renderActiveTab(settings.snapshot())
                }
            }
            presetRow.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (idx > 0) marginStart = dp(5) })
        }
        demonCard.addView(presetRow)

        demonCard.addView(makeSwitchRow("Embuscades Sorcières surprise hors de l'app", snap.rpgSeductionEnabled) { settings.rpgSeductionEnabled = it })
        demonCard.addView(makeSwitchRow("Drainage de luminosité en combat / défaite", snap.drainBrightnessEnabled) { settings.drainBrightnessEnabled = it })
        demonCard.addView(makeSwitchRow("Drainage qualité visuelle / transe hypnotique", snap.drainQualityEnabled) { settings.drainQualityEnabled = it })
        demonCard.addView(makeSwitchRow("Autel Photo Piégé (#87) après défaite", snap.unremovableAltarEnabled) { settings.unremovableAltarEnabled = it })
        demonCard.addView(makeSwitchRow("Fond d'écran trophée de la Sorcière gagnante", snap.wallpaperPrankEnabled) { settings.wallpaperPrankEnabled = it })
        demonCard.addView(makeSwitchRow("Au Coin Vidéo (#38) & Lignes (#39)", snap.timeoutCornerEnabled) {
            settings.timeoutCornerEnabled = it; settings.punishmentLinesEnabled = it
        })

        demonCard.addView(makeAccentButton("🔒 Verrouiller (Paramètres + Admin • 20× + 100205)", Color.rgb(159, 18, 57)) {
            confirmAndEnableChallengeLock()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        })
        root.addView(demonCard)
    }

    // ==================== TAB 3 : ⚙️ DOSSIER PHOTOS, IA & SHIZUKU DIAGNOSTIC ====================
    private fun populateFolderAndAiTab(root: LinearLayout, snap: DemonConfigSnapshot) {
        val rSnap = rpgState.snapshot()

        // 1. Witch Media Folder
        val medCard = makeCard(accentStroke = Color.argb(145, 168, 85, 247))
        medCard.addView(sectionTitle("📂 Dossier Photos des 50 Sorcières (.* inclus)"))
        medCard.addView(sectionSub("Chaque photo est détourée automatiquement par IA (ML Kit) pour incarner les 50 Sorcières en plein écran !"))

        val sumTv = TextView(this).apply {
            setTextColor(Color.rgb(216, 180, 254)); textSize = 12f; typeface = Typeface.MONOSPACE
            background = roundedBg(Color.rgb(26, 20, 40), 10); setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        mediaSummaryTv = sumTv
        medCard.addView(sumTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4); bottomMargin = dp(8)
        })

        medCard.addView(makeAccentButton("🔍 Parcourir dossiers (masqués .* & .nomedia inclus)", Color.rgb(147, 51, 234)) {
            if (!MediaFolderHelper.hasAllFilesAccess()) {
                Toast.makeText(this, "Autorise « Accès à tous les fichiers » pour voir les dossiers masqués !", Toast.LENGTH_LONG).show()
                requestAllFilesAccess()
            } else {
                FolderPickerDialog.show(this, settings.mediaFolderPath) { chosenPath ->
                    settings.mediaFolderPath = chosenPath
                    Toast.makeText(this, "Dossier des Sorcières enregistré ✅", Toast.LENGTH_SHORT).show()
                    refreshMediaSummaryAsync(settings.snapshot())
                }
            }
        })
        medCard.addView(makeSecondaryBtn("📁 Ou sélecteur Android standard (SAF)") { pickSafTreeLauncher.launch(null) })

        val resBtn = makeSecondaryBtn("♻️ Restaurer mon fond d'écran d'origine") {
            Thread {
                val ok = MediaFolderHelper.restoreOriginalWallpaper(applicationContext)
                mainHandler.post { Toast.makeText(this, if (ok) "Fond d'écran restauré ✅" else "Aucune sauvegarde.", Toast.LENGTH_SHORT).show() }
            }.start()
        }
        resBtn.visibility = if (MediaFolderHelper.hasWallpaperBackup(this)) View.VISIBLE else View.GONE
        restoreWallBtn = resBtn
        medCard.addView(resBtn)
        root.addView(medCard)

        // 2. Instant AI Dialogue Engine (+ Optional Gemini API Key)
        val aiCard = makeCard(accentStroke = Color.argb(135, 14, 165, 233))
        aiCard.addView(sectionTitle("🧠 Dialogue IA Instantané (Non Répétitif)"))
        aiCard.addView(sectionSub(
            "• Moteur Local IA 0 ms : ACTIF ✅ (28 événements, 8 archétypes, mémoire anti-répétition 45 répliques).\n" +
                "• Optionnel : colle une clé API Google Gemini (aistudio.google.com) pour générer des répliques inédites par IA cloud !"
        ))

        val keyEt = EditText(this).apply {
            setText(rSnap.geminiApiKey); hint = "Clé API Gemini optionnelle (AIza…)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setTextColor(Color.WHITE); setHintTextColor(Color.rgb(130, 130, 152)); textSize = 12.5f
            background = roundedBg(Color.rgb(26, 24, 42), 10); setPadding(dp(10), dp(9), dp(10), dp(9))
        }
        aiCard.addView(keyEt)

        val aiRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        aiRow.addView(makeAccentButton("💾 Enregistrer Clé IA", Color.rgb(2, 132, 199)) {
            rpgState.geminiApiKey = keyEt.text?.toString().orEmpty()
            Toast.makeText(this, if (rpgState.geminiApiKey.isNotBlank()) "✨ IA Gemini Cloud activée !" else "⚡ Mode Local IA 0 ms.", Toast.LENGTH_SHORT).show()
            renderActiveTab(settings.snapshot())
        }, gridLp(endMargin = dp(5)))
        aiRow.addView(makeGridTestBtn("🗑️ Effacer") {
            rpgState.geminiApiKey = ""; keyEt.setText("")
            Toast.makeText(this, "Clé IA effacée.", Toast.LENGTH_SHORT).show()
        }, gridLp(startMargin = dp(5)))
        aiCard.addView(aiRow)
        root.addView(aiCard)

        // 3. Shizuku / MDM & Live Diagnostic Card
        val permCard = makeCard(accentStroke = Color.argb(135, 16, 185, 129))
        val shizukuOk = DemonMdmManager.isShizukuActive()
        val usageOk = DemonMdmManager.hasUsageStatsAccess(this)
        val accessRunning = DemonAccessibilityService.isServiceActive()
        val adminOk = DemonDeviceAdminReceiver.isAdminActive(this)
        val filesOk = MediaFolderHelper.hasAllFilesAccess()
        val ovOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val wsOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(this)

        permCard.addView(sectionTitle("🛡️ Shizuku & Autorisations Système"))
        permCard.addView(TextView(this).apply {
            text = DemonShizukuBridge.getDiagnostics(this@MainActivity)
            setTextColor(if (shizukuOk) Color.rgb(167, 243, 208) else Color.rgb(253, 224, 71))
            textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(4), 0, dp(8))
        })

        val shizRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        shizRow.addView(makeAccentButton(
            if (shizukuOk) "✅ Shizuku OK" else "⚡ Autoriser Shizuku",
            if (shizukuOk) Color.rgb(16, 128, 96) else Color.rgb(126, 34, 206)
        ) { handleShizukuAction() }, gridLp(endMargin = dp(5)))
        shizRow.addView(makeAccentButton("🧪 Tester (Griser 1 app)", Color.rgb(2, 132, 199)) {
            runShizukuSuspendTest()
        }, gridLp(startMargin = dp(5)))
        permCard.addView(shizRow)

        val pRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(6)) }
        pRow1.addView(makePermChip("Accessibilité", accessRunning) { openAccessibility() }, gridLp(endMargin = dp(4)))
        pRow1.addView(makePermChip("Fichiers .*", filesOk) { requestAllFilesAccess() }, gridLp(startMargin = dp(4), endMargin = dp(4)))
        pRow1.addView(makePermChip("UsageStats", usageOk) {
            try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch (_: Throwable) {}
        }, gridLp(startMargin = dp(4)))
        permCard.addView(pRow1)

        val pRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pRow2.addView(makePermChip("Superposition", ovOk) { openOverlayPerm() }, gridLp(endMargin = dp(4)))
        pRow2.addView(makePermChip("Luminosité", wsOk) { openWriteSettingsPerm() }, gridLp(startMargin = dp(4), endMargin = dp(4)))
        pRow2.addView(makePermChip("Admin", adminOk) { requestDeviceAdmin() }, gridLp(startMargin = dp(4)))
        permCard.addView(pRow2)

        permCard.addView(makeSecondaryBtn("🕊️ Tout réactiver & dégriser immédiatement") { performEndGameRestoreAll() })
        permCard.addView(makeSecondaryBtn("📋 Copier script Termux (optionnel)") { copyTermuxMdmScript() })
        permCard.addView(makeSecondaryBtn("👀 Applis surveillées (${snap.watchedPackages.size})") {
            AppPickerDialog.show(this, "Applis surveillées", settings.watchedPackages) {
                settings.watchedPackages = it; renderActiveTab(settings.snapshot())
            }
        })
        if (!accessRunning && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permCard.addView(makeSecondaryBtn("ℹ️ Android 13+ grisé ? Infos Appli (⋮ → Paramètres restreints)") { openAppDetails() })
        }
        root.addView(permCard)

        // 4. Demon Companion Portrait & Difficulty Sliders
        val avCard = makeCard()
        avCard.addView(sectionTitle("🎭 Compagnon Démon & Difficulté"))
        avCard.addView(makeAccentButton("📸 Choisir portrait du Compagnon Démon") {
            pickAvatarLauncher.launch(arrayOf("image/*"))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        })
        avCard.addView(makeSwitchRow("Inclure cheveux & buste HD", snap.cutoutIncludeHair) { checked ->
            settings.cutoutIncludeHair = checked
            Thread {
                val rebuilt = AvatarCutoutEngine.rebuildFromSavedSource(applicationContext, checked)
                if (rebuilt != null) mainHandler.post {
                    settings.avatarPath = rebuilt; Toast.makeText(this, "Portrait mis à jour ✨", Toast.LENGTH_SHORT).show()
                }
            }.start()
        })
        avCard.addView(smallLabel("Style du compagnon :"))
        avCard.addView(makeChipGroup(AvatarStyle.values().toList(), snap.avatarStyle, { it.label }) { settings.avatarStyle = it })
        avCard.addView(makeSliderRow("Intensité / Chaos (≥ 8 = Duo Sorcières)", 1, 10, snap.chaosLevel, "/10") { settings.chaosLevel = it })
        avCard.addView(smallLabel("Cible du fond d'écran trophée :"))
        avCard.addView(makeChipGroup(WallpaperTarget.values().toList(), snap.wallpaperTarget, { it.label }) { settings.wallpaperTarget = it })
        avCard.addView(smallLabel("Censure IA (Overlay) :"))
        avCard.addView(makeChipGroup(FaceCensorMode.values().toList(), snap.faceCensorMode, { it.label }) { settings.faceCensorMode = it })
        avCard.addView(makeChipGroup(GenderFilter.values().toList(), snap.faceGenderFilter, { it.label }) { settings.faceGenderFilter = it })
        root.addView(avCard)
    }

    // -------------------------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------------------------
    private fun refreshMediaSummaryAsync(snap: DemonConfigSnapshot) {
        val tv = mediaSummaryTv ?: return
        Thread {
            val (imgs, vids) = MediaFolderHelper.countConfiguredMedia(applicationContext, snap)
            val txt = when {
                snap.mediaFolderPath.isNotBlank() -> "${snap.mediaFolderPath}\n→ $imgs photo(s) • $vids vidéo(s) prêtes pour les 50 Sorcières"
                snap.mediaTreeUri.isNotBlank() -> "Dossier SAF\n→ $imgs photo(s) • $vids vidéo(s) prêtes pour les 50 Sorcières"
                else -> "Aucun dossier choisi (silhouettes gothiques par défaut — sélectionne un dossier photos ci-dessous !)"
            }
            mainHandler.post {
                tv.text = txt
                restoreWallBtn?.visibility = if (MediaFolderHelper.hasWallpaperBackup(this)) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun confirmAndEnableChallengeLock() {
        val adminActive = DemonDeviceAdminReceiver.isAdminActive(this)
        AlertDialog.Builder(this)
            .setTitle("🔒 Verrouiller Paramètres & Appli ?")
            .setMessage("Une fois activé :\n• Paramètres Android, désinstallation et réglages verrouillés.\n• Sortie : tapoter le démon 20× (ou barre NV en combat) + code 100205.")
            .setPositiveButton("Verrouiller") { _, _ ->
                settings.update {
                    putBoolean("enabled", true); putBoolean("strict_settings_lock", true); putBoolean("challenge_locked", true)
                }
                DemonCompanionService.start(applicationContext)
                if (!adminActive) requestDeviceAdmin()
                renderScreen(settings.snapshot())
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun openAccessibility() {
        try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        catch (_: Throwable) { Toast.makeText(this, "Paramètres → Accessibilité → DemonMom RPG", Toast.LENGTH_LONG).show() }
    }

    private fun openOverlayPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Throwable) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
        }
    }

    private fun openWriteSettingsPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }
            catch (_: Throwable) { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)) }
        }
    }

    private fun requestDeviceAdmin() {
        try {
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, DemonDeviceAdminReceiver.getComponentName(this@MainActivity))
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Verrouillage Défi DemonMom RPG (sortie : 20 taps + 100205).")
            })
        } catch (_: Throwable) {}
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Throwable) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 202)
        }
    }

    private fun openAppDetails() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Throwable) {}
    }

    private fun requestBasicRuntimePermissionsIfNeeded() {
        val req = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) req.add(Manifest.permission.POST_NOTIFICATIONS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            req.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (req.isNotEmpty()) ActivityCompat.requestPermissions(this, req.toTypedArray(), 201)
    }

    private fun makeCard(accentStroke: Int = Color.argb(38, 255, 255, 255)): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(CARD_DARK, 18, strokeColor = accentStroke, strokeDp = 1)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(11) }
    }

    private fun makePermChip(label: String, ok: Boolean, onClick: () -> Unit): Button = Button(this).apply {
        text = if (ok) "✅ $label" else "⚠️ $label"
        isAllCaps = false; textSize = 10.6f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (ok) Color.rgb(167, 243, 208) else Color.rgb(254, 240, 138))
        background = roundedBg(
            if (ok) Color.argb(42, 16, 185, 129) else Color.argb(45, 245, 158, 11), 10,
            strokeColor = if (ok) Color.argb(120, 16, 185, 129) else Color.argb(120, 245, 158, 11), strokeDp = 1
        )
        minHeight = 0; minimumHeight = 0; setPadding(dp(4), dp(8), dp(4), dp(8)); setOnClickListener { onClick() }
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 14.6f; typeface = Typeface.DEFAULT_BOLD
    }

    private fun sectionSub(text: String): TextView = TextView(this).apply {
        this.text = text; setTextColor(Color.rgb(162, 162, 182)); textSize = 11.7f; setPadding(0, dp(2), 0, dp(6))
    }

    private fun smallLabel(text: String): TextView = TextView(this).apply {
        this.text = text; setTextColor(Color.rgb(208, 208, 224)); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(8), 0, dp(4))
    }

    private fun makeSwitchRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit): Switch = Switch(this).apply {
        text = label; isChecked = initial; setTextColor(Color.rgb(232, 232, 242)); textSize = 12.4f
        setPadding(0, dp(5), 0, dp(5)); setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun makeSliderRow(title: String, min: Int, max: Int, initial: Int, unit: String, onChange: (Int) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(4)) }
        val tv = TextView(this).apply { text = "$title : $initial$unit"; setTextColor(Color.rgb(212, 212, 224)); textSize = 12f }
        val sb = SeekBar(this).apply {
            this.max = (max - min).coerceAtLeast(1); progress = (initial - min).coerceIn(0, this.max)
            thumbTintList = ColorStateList.valueOf(Color.rgb(217, 70, 239)); progressTintList = ColorStateList.valueOf(Color.rgb(217, 70, 239))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = min + progress; tv.text = "$title : $v$unit"; if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        box.addView(tv); box.addView(sb); return box
    }

    private fun <T> makeChipGroup(items: List<T>, selected: T, labelOf: (T) -> String, onSelect: (T) -> Unit): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var current = selected; val btns = ArrayList<Pair<T, Button>>()
        fun styleAll() {
            for ((item, b) in btns) {
                val active = item == current
                b.background = roundedBg(
                    if (active) Color.rgb(162, 28, 175) else Color.rgb(32, 28, 46), 11,
                    strokeColor = if (active) Color.rgb(240, 171, 252) else Color.argb(35, 255, 255, 255), strokeDp = 1
                )
                b.setTextColor(if (active) Color.WHITE else Color.rgb(208, 208, 220))
            }
        }
        for (rowItems in items.chunked(2)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEachIndexed { idx, item ->
                val b = Button(this).apply {
                    text = labelOf(item); isAllCaps = false; textSize = 11.4f; minHeight = 0; minimumHeight = 0
                    setPadding(dp(8), dp(7), dp(8), dp(7))
                    setOnClickListener { current = item; styleAll(); onSelect(item) }
                }
                btns.add(Pair(item, b))
                row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx == 0 && rowItems.size > 1) marginEnd = dp(5); if (idx == 1) marginStart = dp(5)
                })
            }
            wrap.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(5) })
        }
        styleAll(); return wrap
    }

    private fun makeAccentButton(label: String, bgColor: Int = Color.rgb(225, 29, 72), onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12.4f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        background = roundedBg(bgColor, 12); setPadding(dp(10), dp(10), dp(10), dp(10)); minHeight = 0; minimumHeight = 0
        setOnClickListener { onClick() }
    }

    private fun makeSecondaryBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 11.7f; setTextColor(Color.rgb(224, 224, 236))
        background = roundedBg(Color.rgb(32, 28, 46), 11, strokeColor = Color.argb(45, 255, 255, 255), strokeDp = 1)
        setPadding(dp(10), dp(8), dp(10), dp(8)); minHeight = 0; minimumHeight = 0
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(2) }
        setOnClickListener { onClick() }
    }

    private fun makeGridTestBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 11.4f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        background = roundedBg(Color.rgb(38, 24, 50), 11, strokeColor = Color.argb(90, 217, 70, 239), strokeDp = 1)
        setPadding(dp(6), dp(9), dp(6), dp(9)); minHeight = 0; minimumHeight = 0; setOnClickListener { onClick() }
    }

    private fun gridLp(startMargin: Int = 0, endMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = startMargin; marginEnd = endMargin }

    private fun roundedBg(fillColor: Int, radiusDp: Int, strokeColor: Int = Color.TRANSPARENT, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat(); setColor(fillColor)
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    companion object {
        private val BG_DARK = Color.rgb(11, 9, 18)
        private val CARD_DARK = Color.rgb(21, 17, 32)
    }
}
