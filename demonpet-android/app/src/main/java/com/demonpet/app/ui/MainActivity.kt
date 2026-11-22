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
import com.demonpet.app.service.DemonAccessibilityService
import com.demonpet.app.service.DemonCompanionService
import rikka.shizuku.Shizuku

/**
 * DemonMom RPG v5.0 — Complete 50-Witch Dark-Fantasy RPG Game & System Punishment Hub:
 * - Tab 0: 🗺️ Aventure (RPG Profile, 5 Chapters, Explore Random Witch, Challenge Boss, Shizuku Status)
 * - Tab 1: 📖 Grimoire (Complete 50-Witch Bestiary with Chapter/Boss filters & 1-Tap Battle Launch)
 * - Tab 2: 🎒 Sac & Démon (Relics Inventory & Soul Shard Shop + Living Demon Overlay & Punishments)
 * - Tab 3: ⚙️ Dossier & IA (Hidden Folder .* Media Picker, Instant Local AI + Optional Gemini API Key, Shizuku & Permissions)
 * - Fullscreen battles run directly inside [battleHostFrame] (works immediately without requiring Overlay permission!)
 *   while also supporting live system ambushes via Accessibility + real Shizuku/MDM defeat punishments!
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

    // Live UI refs
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

    private val accessListener: () -> Unit = {
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
                    Thread { DemonShizukuBridge.ensureBound() }.start()
                    Toast.makeText(this, "⚡ Shizuku autorisé ! Punitions système par ordre d'usage ACTIVES !", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Autorisation Shizuku refusée.", Toast.LENGTH_SHORT).show()
                }
                if (!settings.challengeLocked && !inAppBattle.isBattleActive()) {
                    renderActiveTab(settings.snapshot())
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = DemonSettings(applicationContext)
        rpgState = WitchRpgState(applicationContext)

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
            },
            onDismissFullscreen = {
                battleHostFrame.removeAllViews()
                battleHostFrame.visibility = View.GONE
                clearWindowBrightnessDrain()
            },
            onApplyVisualDrain = { pct, cfg ->
                applyWindowBrightnessDrain(pct, cfg)
            },
            onClearVisualDrain = {
                clearWindowBrightnessDrain()
            },
            onSayPet = { msg ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            },
            onSpawnTrappedAltar = { _ ->
                DemonAccessibilityService.activeInstance?.triggerManualTest("altar")
            },
            onVibrate = { ms -> vibrateShort(ms) },
            onSecretEmergencyExit = { showExitPinDialog() },
            onBattleFinished = {
                if (!settings.challengeLocked) {
                    renderActiveTab(settings.snapshot())
                }
            }
        )

        requestBasicRuntimePermissionsIfNeeded()
        renderScreen(settings.snapshot())
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (inAppBattle.isBattleActive()) {
            Toast.makeText(this, "Duel en cours ! Termine le combat (ou 20 taps sur la barre NV + 100205).", Toast.LENGTH_SHORT).show()
            return
        }
        super.onBackPressed()
    }

    override fun onStart() {
        super.onStart()
        DemonSettings.addListener(cfgListener)
        DemonAccessibilityService.addStateListener(accessListener)
        try { Shizuku.addRequestPermissionResultListener(shizukuPermListener) } catch (_: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        val snap = settings.snapshot()
        if (DemonShizukuBridge.hasShizukuPermission()) {
            Thread { DemonShizukuBridge.ensureBound() }.start()
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
        DemonAccessibilityService.removeStateListener(accessListener)
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

    private fun startInAppDuel(witchDef: WitchCatalog.WitchDef) {
        if (inAppBattle.isBattleActive()) return
        Toast.makeText(this, "⚔️ Invocation de ${witchDef.name} (NV ${witchDef.level})…", Toast.LENGTH_SHORT).show()
        inAppBattle.launchBattleForWitch(settings.snapshot(), witchDef)
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
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        wrap.addView(TextView(this).apply {
            text = "Paramètres Android, Administrateur et réglages protégés.\nTu peux continuer à combattre les Sorcières, ou tapoter le démon 20× + code 100205 pour déverrouiller."
            setTextColor(Color.rgb(185, 185, 200))
            textSize = 12.8f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(18))
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
            val w = WitchCatalog.randomWitchForChapter(rpgState.selectedChapter, includeBoss = true)
            startInAppDuel(w)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(18)
            }
        })

        val hintTv = TextView(this).apply {
            text = "Tapote le démon 20× de suite pour débloquer"
            setTextColor(Color.rgb(253, 164, 175))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(6))
        }
        lockedTapHintTv = hintTv
        wrap.addView(hintTv)

        wrap.addView(TextView(this).apply {
            text = "NV ${snap.playerLevel} • ${snap.mood.emoji} ${snap.mood.label} • Marche aussi sur le démon flottant"
            setTextColor(Color.rgb(145, 145, 162))
            textSize = 11.5f
            gravity = Gravity.CENTER
        })

        return wrap
    }

    private fun showExitPinDialog() {
        val input = EditText(this).apply {
            hint = "Code secret (100205)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(145, 145, 162))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBg(Color.rgb(30, 27, 42), 12)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(8))
            setBackgroundColor(Color.rgb(20, 18, 28))
            addView(TextView(this@MainActivity).apply {
                text = "Saisis le code pour déverrouiller les Paramètres, réactiver 100 % des applis suspendues et mettre le démon en pause :"
                setTextColor(Color.rgb(225, 225, 234))
                textSize = 13f
                setPadding(0, 0, 0, dp(12))
            })
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("🔓 Déverrouillage DemonMom RPG")
            .setView(box)
            .setPositiveButton("Déverrouiller") { _, _ ->
                if (settings.verifyExitPin(input.text?.toString().orEmpty())) {
                    performFullUnlock()
                } else {
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
        when {
            DemonShizukuBridge.hasShizukuPermission() -> {
                Thread { DemonShizukuBridge.ensureBound() }.start()
                Toast.makeText(this, "⚡ Shizuku déjà actif et connecté !", Toast.LENGTH_SHORT).show()
            }
            DemonShizukuBridge.isShizukuRunning() -> {
                DemonShizukuBridge.requestShizukuPermission()
            }
            DemonShizukuBridge.isShizukuInstalled(this) -> {
                Toast.makeText(this, "Lance Shizuku via « Débogage sans fil », puis reviens ici !", Toast.LENGTH_LONG).show()
                DemonShizukuBridge.openShizukuApp(this)
            }
            else -> {
                Toast.makeText(this, "Installe l'app gratuite Shizuku (Play Store ou GitHub) !", Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases/latest")))
                } catch (_: Throwable) {}
            }
        }
    }

    private fun copyTermuxMdmScript() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("DemonMom Termux MDM", DemonMdmManager.buildTermuxOneLineScript()))
        Toast.makeText(this, "📋 Script Termux copié ! Colle-le dans Termux.", Toast.LENGTH_LONG).show()
    }

    // -------------------------------------------------------------------------
    // 4-TAB FULL RPG GAME SHELL
    // -------------------------------------------------------------------------
    private fun buildTabbedShell(snap: DemonConfigSnapshot): View {
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_DARK)
        }

        // Sleek RPG Top Bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        header.addView(TextView(this).apply {
            text = "🔮 DemonMom RPG"
            setTextColor(Color.WHITE)
            textSize = 19.5f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val rSnap = rpgState.snapshot()
        val pill = TextView(this).apply {
            text = "NV ${snap.playerLevel} • 💎 ${rSnap.gold}"
            setTextColor(Color.WHITE)
            textSize = 11.8f
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBg(Color.rgb(42, 20, 54), 14, strokeColor = Color.argb(165, 217, 70, 239), strokeDp = 1)
            setPadding(dp(11), dp(5), dp(11), dp(5))
            setOnClickListener {
                activeTab = 2
                updateTabBarStyles()
                renderActiveTab(settings.snapshot())
            }
        }
        headerPillTv = pill
        header.addView(pill)
        shell.addView(header)

        // 4 RPG Tabs
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(2), dp(12), dp(10))
        }
        tabButtons.clear()
        val titles = listOf("🗺️ Aventure", "📖 Grimoire", "🎒 Sac & Démon", "⚙️ Dossier & IA")
        titles.forEachIndexed { idx, label ->
            val b = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                minHeight = 0; minimumHeight = 0
                setPadding(dp(3), dp(8), dp(3), dp(8))
                setOnClickListener {
                    activeTab = idx
                    updateTabBarStyles()
                    renderActiveTab(settings.snapshot())
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
                if (sel) Color.rgb(162, 28, 175) else Color.rgb(25, 21, 38),
                12,
                strokeColor = if (sel) Color.rgb(240, 171, 252) else Color.argb(35, 255, 255, 255),
                strokeDp = 1
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
            1 -> populateGrimoireTab(inner, snap)
            2 -> populateBagAndDemonTab(inner, snap)
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

    // ==================== TAB 0 : 🗺️ AVENTURE (COMPLETE WITCH RPG HUB) ====================
    private fun populateAdventureTab(root: LinearLayout, snap: DemonConfigSnapshot) {
        val rSnap = rpgState.snapshot()

        // 1. Player RPG Hero Card
        val hero = makeCard(accentStroke = Color.argb(145, 217, 70, 239))
        val heroRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val pv = DemonPetView(
            context = this,
            onDragMove = { _, _ -> },
            onDragRelease = { _, _, _ -> },
            onGentlePet = { settings.adjustMood(-5) },
            onSecretTwentyTaps = {}
        ).apply {
            updateConfig(snap)
            pose = DemonPetView.PetPose.WALKING
        }
        previewPetView = pv
        heroRow.addView(pv, LinearLayout.LayoutParams(dp(96), dp(116)))

        val statsCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        statsCol.addView(TextView(this).apply {
            text = "NV ${snap.playerLevel} · ${obedienceRankFor(snap.playerLevel)}"
            setTextColor(Color.WHITE)
            textSize = 17.5f
            typeface = Typeface.DEFAULT_BOLD
        })
        statsCol.addView(TextView(this).apply {
            text = "PV ${snap.playerHp}/100  •  XP ${rSnap.xp}/100  •  💎 ${rSnap.gold} Éclats"
            setTextColor(Color.rgb(240, 171, 252))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(3), 0, dp(4))
        })

        val xpBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = rSnap.xp.coerceIn(0, 100)
            progressTintList = ColorStateList.valueOf(Color.rgb(217, 70, 239))
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(42, 34, 58))
        }
        statsCol.addView(xpBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply {
            bottomMargin = dp(6)
        })

        val aiBadge = if (rSnap.geminiApiKey.isNotBlank()) "✨ IA Gemini + Locale" else "⚡ IA Locale 0 ms"
        statsCol.addView(TextView(this).apply {
            text = "📖 Grimoire : ${rSnap.defeatedIds.size}/50 vaincues • ${rSnap.totalWins}V / ${rSnap.totalDefeats}D\n🧠 Dialogues : $aiBadge"
            setTextColor(Color.rgb(180, 180, 200))
            textSize = 11.4f
        })
        heroRow.addView(statsCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hero.addView(heroRow)

        // Prompt to pick photo folder if not yet set
        if (snap.mediaFolderPath.isBlank() && snap.mediaTreeUri.isBlank()) {
            hero.addView(makeAccentButton("📂 Choisir ton dossier photos pour les 50 Sorcières →", Color.rgb(126, 34, 206)) {
                activeTab = 3
                updateTabBarStyles()
                renderActiveTab(settings.snapshot())
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(10)
                }
            })
        }
        root.addView(hero)

        // 2. 5-Chapter World Map & Battle Launch Card
        val chCard = makeCard(accentStroke = Color.argb(135, 244, 63, 94))
        chCard.addView(sectionTitle("🗺️ Monde du Coven (5 Chapitres · 50 Sorcières)"))

        val curCh = rSnap.selectedChapter.coerceIn(1, 5)
        val chRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(10))
        }
        WitchCatalog.CHAPTERS.forEachIndexed { idx, ch ->
            val unlocked = ch.id <= rSnap.unlockedChapter
            val selected = ch.id == curCh
            val btn = Button(this).apply {
                text = if (unlocked) "Ch.${ch.id}" else "🔒${ch.id}"
                isAllCaps = false
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(when {
                    selected -> Color.WHITE
                    unlocked -> Color.rgb(225, 210, 245)
                    else -> Color.rgb(125, 125, 145)
                })
                background = roundedBg(
                    when {
                        selected -> Color.rgb(225, 29, 72)
                        unlocked -> Color.rgb(36, 26, 52)
                        else -> Color.rgb(22, 19, 32)
                    },
                    10,
                    strokeColor = if (selected) Color.rgb(253, 164, 175) else Color.argb(40, 255, 255, 255),
                    strokeDp = 1
                )
                minHeight = 0; minimumHeight = 0
                setPadding(dp(2), dp(8), dp(2), dp(8))
                setOnClickListener {
                    if (!unlocked) {
                        val prevBoss = WitchCatalog.bossOfChapter(ch.id - 1)
                        Toast.makeText(this@MainActivity, "Bats d'abord ${prevBoss.name} (Boss Ch.${ch.id - 1}) pour débloquer le Chapitre ${ch.id} !", Toast.LENGTH_LONG).show()
                    } else {
                        rpgState.selectedChapter = ch.id
                        grimoireFilter = ch.id
                        renderActiveTab(settings.snapshot())
                    }
                }
            }
            chRow.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (idx > 0) marginStart = dp(5)
            })
        }
        chCard.addView(chRow)

        val chInfo = WitchCatalog.CHAPTERS[curCh - 1]
        val chWitches = WitchCatalog.witchesInChapter(curCh)
        val chDefeated = chWitches.count { rSnap.defeatedIds.contains(it.id) }
        val chBoss = WitchCatalog.bossOfChapter(curCh)
        val bossBeat = rSnap.defeatedIds.contains(chBoss.id)

        val chDetail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Color.rgb(28, 18, 42), 14, strokeColor = Color.argb(75, 217, 70, 239), strokeDp = 1)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        chDetail.addView(TextView(this).apply {
            text = "Chapitre $curCh : ${chInfo.name} (NV ${chInfo.minLevel}–${chInfo.maxLevel})"
            setTextColor(Color.WHITE)
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
        })
        chDetail.addView(TextView(this).apply {
            text = "${chInfo.subtitle}\nProgression : $chDefeated/10 Sorcières vaincues • Boss : ${chBoss.name} ${if (bossBeat) "✅" else "👑"}"
            setTextColor(Color.rgb(208, 196, 228))
            textSize = 11.8f
            setPadding(0, dp(3), 0, 0)
        })
        chCard.addView(chDetail)

        // GIANT EXPLORE BUTTON
        chCard.addView(makeAccentButton("⚔️ EXPLORER — SORCIÈRE ALÉATOIRE (Ch.$curCh)", Color.rgb(225, 29, 72)) {
            val w = WitchCatalog.randomWitchForChapter(curCh, includeBoss = false)
            startInAppDuel(w)
        }.apply {
            textSize = 13.8f
            setPadding(dp(12), dp(13), dp(12), dp(13))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
        })

        // BOSS CHALLENGE BUTTON
        chCard.addView(makeAccentButton("👑 DÉFIER LE BOSS : ${chBoss.name} (NV ${chBoss.level})", Color.rgb(162, 28, 175)) {
            startInAppDuel(chBoss)
        }.apply {
            textSize = 13f
            setPadding(dp(12), dp(11), dp(12), dp(11))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        })

        // Secondary Row: Random 1..50 + Grimoire
        val subRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        subRow.addView(makeGridTestBtn("🎲 Aléatoire (1–50)") {
            startInAppDuel(WitchCatalog.ALL_WITCHES.random())
        }, gridLp(endMargin = dp(5)))
        subRow.addView(makeGridTestBtn("📖 Voir les 50 Sorcières") {
            activeTab = 1
            updateTabBarStyles()
            renderActiveTab(settings.snapshot())
        }, gridLp(startMargin = dp(5)))
        chCard.addView(subRow)
        root.addView(chCard)

        // 3. System Punishments (Shizuku / MDM) & Guaranteed Full Restore Card
        val sysCard = makeCard(accentStroke = Color.argb(115, 16, 185, 129))
        val privOk = DemonMdmManager.hasPrivilegedControl(this)
        val susp = DemonMdmManager.getSuspendedPackages(this)
        val hid = DemonMdmManager.getHiddenPackages(this)
        val curses = DemonMdmManager.getActiveCurses(this)

        sysCard.addView(sectionTitle("⚡ Punitions Système en cas de Défaite"))
        sysCard.addView(TextView(this).apply {
            text = "${DemonMdmManager.getControlStatusLabel(this@MainActivity)}\n" +
                "• ${susp.size + hid.size} appli(s) gelée(s) par ordre d'usage • ${curses.size} sceau(x) actif(s)\n" +
                "• Gagner un combat libère +1 appli • Tout est 100 % réversible !"
            setTextColor(if (privOk) Color.rgb(167, 243, 208) else Color.rgb(253, 186, 116))
            textSize = 11.6f
            setPadding(0, dp(4), 0, dp(8))
        })

        val sysRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (!privOk) {
            sysRow.addView(makeAccentButton("⚡ Autoriser Shizuku", Color.rgb(126, 34, 206)) {
                handleShizukuAction()
            }, gridLp(endMargin = dp(5)))
        }
        sysRow.addView(makeAccentButton("🕊️ Fin du jeu : Tout restaurer", Color.rgb(16, 128, 96)) {
            performEndGameRestoreAll()
        }, gridLp(startMargin = if (!privOk) dp(5) else 0))
        sysCard.addView(sysRow)
        root.addView(sysCard)
    }

    // ==================== TAB 1 : 📖 GRIMOIRE (ALL 50 WITCHES BESTIARY) ====================
    private fun populateGrimoireTab(root: LinearLayout, snap: DemonConfigSnapshot) {
        val rSnap = rpgState.snapshot()

        val headCard = makeCard(accentStroke = Color.argb(120, 217, 70, 239))
        headCard.addView(sectionTitle("📖 Grimoire des 50 Sorcières"))
        headCard.addView(sectionSub(
            "${rSnap.defeatedIds.size}/50 vaincues  •  ${rSnap.encounteredIds.size}/50 rencontrées  •  ${rSnap.lostToIds.size} défaite(s)\n" +
                "Chaque Sorcière reçoit aléatoirement un portrait HD détouré depuis ton dossier photos !"
        ))

        // Filter Chips: Ch.1..Ch.5 + Boss(10)
        val filterRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(5)) }
        listOf(1 to "Ch.1", 2 to "Ch.2", 3 to "Ch.3").forEachIndexed { idx, (code, label) ->
            filterRow1.addView(makeFilterChip(label, grimoireFilter == code) {
                grimoireFilter = code
                renderActiveTab(settings.snapshot())
            }, gridLp(startMargin = if (idx > 0) dp(4) else 0, endMargin = if (idx < 2) dp(4) else 0))
        }
        headCard.addView(filterRow1)

        val filterRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(4 to "Ch.4", 5 to "Ch.5", 0 to "👑 Boss (10)").forEachIndexed { idx, (code, label) ->
            filterRow2.addView(makeFilterChip(label, grimoireFilter == code) {
                grimoireFilter = code
                renderActiveTab(settings.snapshot())
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

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val statusIcon = when {
                defeated -> "✅ Vaincue"
                lostTo -> "💋 Défaite"
                seen -> "👁️ Vue"
                else -> "❓ Nouvelle"
            }
            topRow.addView(TextView(this).apply {
                text = "#${w.id} ${w.displayTitle}"
                setTextColor(if (w.isBoss) Color.rgb(253, 224, 71) else Color.WHITE)
                textSize = 13.8f
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            topRow.addView(TextView(this).apply {
                text = statusIcon
                setTextColor(when {
                    defeated -> Color.rgb(167, 243, 208)
                    lostTo -> Color.rgb(244, 114, 182)
                    else -> Color.rgb(185, 185, 205)
                })
                textSize = 11.2f
                typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(topRow)

            card.addView(TextView(this).apply {
                text = "NV ${w.level} • ${w.archetype.label} • ${w.element} • Résol. ${w.maxResolve} • +${w.rewardXp} XP • +${w.rewardGold} 💎"
                setTextColor(Color.rgb(208, 198, 232))
                textSize = 11.3f
                setPadding(0, dp(3), 0, dp(4))
            })

            card.addView(TextView(this).apply {
                text = "Ultime : « ${w.ultimateName} »\n💡 ${w.weaknessTip}"
                setTextColor(Color.rgb(156, 156, 178))
                textSize = 11.2f
                setPadding(0, 0, 0, dp(8))
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
        text = label
        isAllCaps = false
        textSize = 11.5f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (active) Color.WHITE else Color.rgb(190, 190, 210))
        background = roundedBg(
            if (active) Color.rgb(225, 29, 72) else Color.rgb(30, 26, 44),
            10,
            strokeColor = if (active) Color.rgb(253, 164, 175) else Color.argb(35, 255, 255, 255),
            strokeDp = 1
        )
        minHeight = 0; minimumHeight = 0
        setPadding(dp(4), dp(7), dp(4), dp(7))
        setOnClickListener { onClick() }
    }

    // ==================== TAB 2 : 🎒 SAC, BOUTIQUE & COMPAGNON DÉMON ====================
    private fun populateBagAndDemonTab(root: LinearLayout, snap: DemonConfigSnapshot) {
        val rSnap = rpgState.snapshot()

        // 1. Relics Inventory & Soul Shard Shop
        val shopCard = makeCard(accentStroke = Color.argb(135, 245, 158, 11))
        shopCard.addView(sectionTitle("🎒 Sac & Boutique d'Éclats (💎 ${rSnap.gold})"))
        shopCard.addView(sectionSub("Achète des Reliques avec les Éclats gagnés en combat pour survivre aux Archisorcières Boss !"))

        data class ShopItem(val key: String, val title: String, val desc: String, val count: Int, val cost: Int, val color: Int)
        val items = listOf(
            ShopItem("elixir", "🧪 Élixir de Volonté", "+60 PV en combat", rSnap.elixirs, 35, Color.rgb(16, 128, 96)),
            ShopItem("crystal", "💎 Cristal de Mana", "+40 PM et +20 SP", rSnap.crystals, 30, Color.rgb(37, 99, 235)),
            ShopItem("talisman", "📜 Talisman de Rupture", "Brise instantanément une Capture", rSnap.talismans, 55, Color.rgb(180, 83, 9)),
            ShopItem("amulet", "🔮 Amulette de Clarté", "Purifie Statuts + Drainage visuel", rSnap.amulets, 45, Color.rgb(126, 34, 206))
        )

        for (shopItem in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBg(Color.rgb(28, 22, 42), 12)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(6)
                }
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(TextView(this).apply {
                text = "${shopItem.title}  (x${shopItem.count})"
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
            })
            info.addView(TextView(this).apply {
                text = shopItem.desc
                setTextColor(Color.rgb(175, 175, 196))
                textSize = 11.2f
            })
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val canBuy = rSnap.gold >= shopItem.cost
            row.addView(Button(this).apply {
                text = "Acheter ${shopItem.cost} 💎"
                isAllCaps = false
                textSize = 11.2f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = roundedBg(if (canBuy) shopItem.color else Color.rgb(52, 48, 64), 10)
                minHeight = 0; minimumHeight = 0
                setPadding(dp(10), dp(7), dp(10), dp(7))
                setOnClickListener {
                    if (rpgState.buyItem(shopItem.key, shopItem.cost)) {
                        Toast.makeText(this@MainActivity, "${shopItem.title} acheté !", Toast.LENGTH_SHORT).show()
                        renderActiveTab(settings.snapshot())
                    } else {
                        Toast.makeText(this@MainActivity, "Pas assez d'Éclats 💎 ! Gagne des combats.", Toast.LENGTH_SHORT).show()
                    }
                }
            })
            shopCard.addView(row)
        }
        root.addView(shopCard)

        // 2. Living Demon Companion & System Ambushes Card
        val demonCard = makeCard(accentStroke = Color.argb(120, 244, 63, 94))
        demonCard.addView(sectionTitle("😈 Compagnon Démon & Embuscades Système"))
        demonCard.addView(sectionSub("Active le compagnon vivant par-dessus toutes tes applis pour déclencher des embuscades Sorcières surprises !"))

        val sw = Switch(this).apply {
            isChecked = snap.enabled
            text = if (snap.enabled) "Compagnon Démon & Embuscades ACTIFS" else "Activer le Compagnon flottant"
            setTextColor(Color.WHITE)
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(8))
            setOnCheckedChangeListener { _, checked ->
                text = if (checked) "Compagnon Démon & Embuscades ACTIFS" else "Activer le Compagnon flottant"
                settings.enabled = checked
                if (checked) DemonCompanionService.start(applicationContext)
                else DemonCompanionService.stop(applicationContext)
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
                isAllCaps = false
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (active) Color.WHITE else Color.rgb(195, 195, 210))
                background = roundedBg(
                    if (active) Color.rgb(225, 29, 72) else Color.rgb(32, 28, 46),
                    11,
                    strokeColor = if (active) Color.rgb(253, 164, 175) else Color.argb(35, 255, 255, 255),
                    strokeDp = 1
                )
                minHeight = 0; minimumHeight = 0
                setPadding(dp(4), dp(7), dp(4), dp(7))
                setOnClickListener {
                    settings.applySeverityPreset(preset)
                    Toast.makeText(this@MainActivity, "Profil ${preset.label} appliqué ${preset.emoji}", Toast.LENGTH_SHORT).show()
                    renderActiveTab(settings.snapshot())
                }
            }
            presetRow.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (idx > 0) marginStart = dp(5)
            })
        }
        demonCard.addView(presetRow)

        demonCard.addView(makeSwitchRow("Embuscades Sorcières surprise hors de l'app", snap.rpgSeductionEnabled) { settings.rpgSeductionEnabled = it })
        demonCard.addView(makeSwitchRow("Drainage de luminosité en combat / défaite", snap.drainBrightnessEnabled) { settings.drainBrightnessEnabled = it })
        demonCard.addView(makeSwitchRow("Drainage qualité visuelle / transe hypnotique", snap.drainQualityEnabled) { settings.drainQualityEnabled = it })
        demonCard.addView(makeSwitchRow("Autel Photo Piégé (#87) après défaite", snap.unremovableAltarEnabled) { settings.unremovableAltarEnabled = it })
        demonCard.addView(makeSwitchRow("Fond d'écran trophée de la Sorcière gagnante", snap.wallpaperPrankEnabled) { settings.wallpaperPrankEnabled = it })
        demonCard.addView(makeSwitchRow("Au Coin Vidéo (#38) & Lignes de punition (#39)", snap.timeoutCornerEnabled) {
            settings.timeoutCornerEnabled = it
            settings.punishmentLinesEnabled = it
        })
        demonCard.addView(makeSwitchRow("Pause écran (#68) & Tournoi médias (#69)", snap.screenBreakEnabled) {
            settings.screenBreakEnabled = it
            settings.entryTournamentEnabled = it
        })
        demonCard.addView(makeSwitchRow("Sabotage Accessibilité (#26/#27/#29/#31/#71)", snap.counterScrollEnabled) {
            settings.update {
                putBoolean("counter_scroll", it)
                putBoolean("compulsive_back", it)
                putBoolean("force_pause_video", it)
                putBoolean("click_steal_shield", it)
                putBoolean("block_store_buy", it)
            }
        })

        demonCard.addView(makeAccentButton("🔒 Verrouiller (Paramètres + Admin • Sortie 20× + 100205)", Color.rgb(159, 18, 57)) {
            confirmAndEnableChallengeLock()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
        })

        // Quick overlay tests
        val tRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        tRow.addView(makeGridTestBtn("⚔️ Embuscade") { runLiveTest("rpg") }, gridLp(endMargin = dp(4)))
        tRow.addView(makeGridTestBtn("🔒 Autel #87") { runLiveTest("altar") }, gridLp(startMargin = dp(4), endMargin = dp(4)))
        tRow.addView(makeGridTestBtn("🎬 Au Coin") { runLiveTest("corner") }, gridLp(startMargin = dp(4)))
        demonCard.addView(tRow)
        root.addView(demonCard)

        // 3. AI Vision Censor Options
        val aiVisCard = makeCard()
        aiVisCard.addView(sectionTitle("👀 Censure IA & Bêtises Overlay"))
        aiVisCard.addView(smallLabel("Censure des visages :"))
        aiVisCard.addView(makeChipGroup(FaceCensorMode.values().toList(), snap.faceCensorMode, { it.label }) { settings.faceCensorMode = it })
        aiVisCard.addView(smallLabel("Filtre :"))
        aiVisCard.addView(makeChipGroup(GenderFilter.values().toList(), snap.faceGenderFilter, { it.label }) { settings.faceGenderFilter = it })
        aiVisCard.addView(makeSwitchRow("Bouclier NSFW dramatique", snap.nsfwShieldEnabled) { settings.nsfwShieldEnabled = it })
        aiVisCard.addView(makeSwitchRow("Pop-ups photo (#15) & Flash (#16)", snap.draggedPopupEnabled) {
            settings.draggedPopupEnabled = it
            settings.fullscreenFlashEnabled = it
        })
        root.addView(aiVisCard)
    }

    // ==================== TAB 3 : ⚙️ DOSSIER PHOTOS, IA & SHIZUKU ====================
    private fun populateFolderAndAiTab(root: LinearLayout, snap: DemonConfigSnapshot) {
        val rSnap = rpgState.snapshot()

        // 1. Witch Media Folder (Hidden .* & .nomedia Supported!)
        val medCard = makeCard(accentStroke = Color.argb(145, 168, 85, 247))
        medCard.addView(sectionTitle("📂 Dossier Photos des 50 Sorcières (.* inclus)"))
        medCard.addView(sectionSub("Chaque photo est détourée automatiquement par IA (ML Kit) pour incarner les 50 Sorcières en plein écran !"))

        val sumTv = TextView(this).apply {
            setTextColor(Color.rgb(216, 180, 254))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            background = roundedBg(Color.rgb(26, 20, 40), 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
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
        medCard.addView(makeSecondaryBtn("📁 Ou sélecteur Android standard (SAF)") {
            pickSafTreeLauncher.launch(null)
        })

        val resBtn = makeSecondaryBtn("♻️ Restaurer mon fond d'écran d'origine") {
            Thread {
                val ok = MediaFolderHelper.restoreOriginalWallpaper(applicationContext)
                mainHandler.post {
                    Toast.makeText(this, if (ok) "Fond d'écran restauré ✅" else "Aucune sauvegarde.", Toast.LENGTH_SHORT).show()
                }
            }.start()
        }
        resBtn.visibility = if (MediaFolderHelper.hasWallpaperBackup(this)) View.VISIBLE else View.GONE
        restoreWallBtn = resBtn
        medCard.addView(resBtn)
        root.addView(medCard)

        // 2. Instant AI Dialogue Engine (+ Optional Gemini Cloud AI Key)
        val aiCard = makeCard(accentStroke = Color.argb(135, 14, 165, 233))
        aiCard.addView(sectionTitle("🧠 Dialogue IA Instantané (Non Répétitif)"))
        aiCard.addView(sectionSub(
            "• Moteur Local IA (0 ms, hors-ligne) : ACTIF ✅ — génère des dialogues variés selon l'archétype, la phase, ton Niveau et l'appli scellée (mémoire anti-répétition 30 répliques).\n" +
                "• Optionnel : ajoute ta clé gratuite Google Gemini (aistudio.google.com) pour des répliques 100 % générées par IA en direct !"
        ))

        val keyEt = EditText(this).apply {
            setText(rSnap.geminiApiKey)
            hint = "Clé API Gemini optionnelle (AIza…)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(130, 130, 152))
            textSize = 12.5f
            background = roundedBg(Color.rgb(26, 24, 42), 10)
            setPadding(dp(10), dp(9), dp(10), dp(9))
        }
        aiCard.addView(keyEt)

        val aiRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        aiRow.addView(makeAccentButton("💾 Enregistrer Clé IA", Color.rgb(2, 132, 199)) {
            rpgState.geminiApiKey = keyEt.text?.toString().orEmpty()
            val has = rpgState.geminiApiKey.isNotBlank()
            Toast.makeText(this, if (has) "✨ IA Gemini Cloud activée !" else "⚡ Mode Local IA 0 ms uniquement.", Toast.LENGTH_SHORT).show()
            renderActiveTab(settings.snapshot())
        }, gridLp(endMargin = dp(5)))
        aiRow.addView(makeGridTestBtn("🗑️ Effacer") {
            rpgState.geminiApiKey = ""
            keyEt.setText("")
            Toast.makeText(this, "Clé IA effacée (Mode Local actif).", Toast.LENGTH_SHORT).show()
        }, gridLp(startMargin = dp(5)))
        aiCard.addView(aiRow)
        root.addView(aiCard)

        // 3. Shizuku / MDM & Android Permissions
        val permCard = makeCard(accentStroke = Color.argb(120, 16, 185, 129))
        val shizukuOk = DemonMdmManager.isShizukuActive()
        val doActive = DemonMdmManager.isDeviceOwner(this)
        val usageOk = DemonMdmManager.hasUsageStatsAccess(this)
        val accessRunning = DemonAccessibilityService.isServiceActive()
        val adminOk = DemonDeviceAdminReceiver.isAdminActive(this)
        val filesOk = MediaFolderHelper.hasAllFilesAccess()
        val ovOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val wsOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(this)

        permCard.addView(sectionTitle("🛡️ Shizuku & Autorisations Système"))
        permCard.addView(sectionSub(
            when {
                shizukuOk -> "⚡ Shizuku ACTIF ✅ : suspension d'applis par ordre d'usage sur Android 11–16 sans reset usine !"
                doActive -> "👑 MDM Device Owner ACTIF ✅"
                else -> "Pour les punitions système sur Xiaomi HyperOS / Android 16 : lance Shizuku (Débogage sans fil) puis touche Autoriser !"
            }
        ))

        permCard.addView(makeAccentButton(
            if (shizukuOk) "✅ Shizuku connecté" else "⚡ Autoriser / Ouvrir Shizuku (Sans reset)",
            if (shizukuOk) Color.rgb(16, 128, 96) else Color.rgb(126, 34, 206)
        ) {
            handleShizukuAction()
        })

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

        permCard.addView(makeSecondaryBtn("📋 Copier script Termux (Permissions appops)") {
            copyTermuxMdmScript()
        })
        permCard.addView(makeSecondaryBtn("👀 Applis surveillées (${snap.watchedPackages.size})") {
            AppPickerDialog.show(this, "Applis surveillées", settings.watchedPackages) {
                settings.watchedPackages = it
                renderActiveTab(settings.snapshot())
            }
        })
        permCard.addView(makeSecondaryBtn("🚫 Liste Noire Totale (${snap.blacklistPackages.size})") {
            AppPickerDialog.show(this, "Liste Noire Totale", settings.blacklistPackages) {
                settings.blacklistPackages = it
                renderActiveTab(settings.snapshot())
            }
        })
        if (!accessRunning && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permCard.addView(makeSecondaryBtn("ℹ️ Android 13+ grisé ? Ouvrir Infos Appli (⋮ → Paramètres restreints)") {
                openAppDetails()
            })
        }
        root.addView(permCard)

        // 4. Demon Companion Portrait & Difficulty Sliders
        val avCard = makeCard()
        avCard.addView(sectionTitle("🎭 Compagnon Démon & Difficulté"))
        avCard.addView(makeAccentButton("📸 Choisir portrait du Compagnon Démon") {
            pickAvatarLauncher.launch(arrayOf("image/*"))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        })
        avCard.addView(makeSwitchRow("Inclure cheveux & buste HD", snap.cutoutIncludeHair) { checked ->
            settings.cutoutIncludeHair = checked
            Thread {
                val rebuilt = AvatarCutoutEngine.rebuildFromSavedSource(applicationContext, checked)
                if (rebuilt != null) {
                    mainHandler.post {
                        settings.avatarPath = rebuilt
                        Toast.makeText(this, "Portrait mis à jour ✨", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        })
        avCard.addView(smallLabel("Style du compagnon :"))
        avCard.addView(makeChipGroup(AvatarStyle.values().toList(), snap.avatarStyle, { it.label }) { settings.avatarStyle = it })
        avCard.addView(makeSliderRow("Intensité / Chaos (≥ 8 = Duo Sorcières)", 1, 10, snap.chaosLevel, "/10") { settings.chaosLevel = it })
        avCard.addView(makeSliderRow("Intervalle min entre embuscades", 10, 300, snap.minIntervalSec, " s") { settings.minIntervalSec = it })
        avCard.addView(smallLabel("Cible du fond d'écran trophée :"))
        avCard.addView(makeChipGroup(WallpaperTarget.values().toList(), snap.wallpaperTarget, { it.label }) { settings.wallpaperTarget = it })
        root.addView(avCard)
    }

    // -------------------------------------------------------------------------
    // ACTIONS & HELPERS
    // -------------------------------------------------------------------------
    private fun runLiveTest(kind: String) {
        if (kind == "rpg") {
            startInAppDuel(WitchCatalog.randomWitchForChapter(rpgState.selectedChapter))
            return
        }
        if (!settings.enabled) {
            settings.enabled = true
            DemonCompanionService.start(applicationContext)
        }
        DemonAccessibilityService.activeInstance?.triggerManualTest(kind)?.also { return }
        DemonCompanionService.instance?.triggerFallbackTest(kind)?.also { return }
        DemonCompanionService.start(applicationContext)
        mainHandler.postDelayed({
            DemonAccessibilityService.activeInstance?.triggerManualTest(kind)
                ?: DemonCompanionService.instance?.triggerFallbackTest(kind)
                ?: Toast.makeText(this, "Active d'abord l'Accessibilité ou la Superposition !", Toast.LENGTH_LONG).show()
        }, 550L)
    }

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
            .setMessage(
                "Une fois activé :\n" +
                    "• Les Paramètres Android, la désinstallation et les réglages sont verrouillés.\n" +
                    "• Pour sortir : tapote le démon 20 fois de suite (ou la barre NV en combat) puis saisis 100205."
            )
            .setPositiveButton("Verrouiller") { _, _ ->
                settings.update {
                    putBoolean("enabled", true)
                    putBoolean("strict_settings_lock", true)
                    putBoolean("challenge_locked", true)
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
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, DemonDeviceAdminReceiver.getComponentName(this@MainActivity))
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Verrouillage Défi DemonMom RPG (sortie : 20 taps + 100205).")
            }
            startActivity(intent)
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
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        catch (_: Throwable) {}
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

    // -------------------------------------------------------------------------
    // SLEEK DARK-FANTASY UI HELPERS
    // -------------------------------------------------------------------------
    private fun makeCard(accentStroke: Int = Color.argb(38, 255, 255, 255)): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(CARD_DARK, 18, strokeColor = accentStroke, strokeDp = 1)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(11)
        }
    }

    private fun makePermChip(label: String, ok: Boolean, onClick: () -> Unit): Button = Button(this).apply {
        text = if (ok) "✅ $label" else "⚠️ $label"
        isAllCaps = false
        textSize = 10.6f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (ok) Color.rgb(167, 243, 208) else Color.rgb(254, 240, 138))
        background = roundedBg(
            if (ok) Color.argb(42, 16, 185, 129) else Color.argb(45, 245, 158, 11),
            10,
            strokeColor = if (ok) Color.argb(120, 16, 185, 129) else Color.argb(120, 245, 158, 11),
            strokeDp = 1
        )
        minHeight = 0; minimumHeight = 0
        setPadding(dp(4), dp(8), dp(4), dp(8))
        setOnClickListener { onClick() }
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 14.8f; typeface = Typeface.DEFAULT_BOLD
    }

    private fun sectionSub(text: String): TextView = TextView(this).apply {
        this.text = text; setTextColor(Color.rgb(162, 162, 182)); textSize = 11.8f; setPadding(0, dp(2), 0, dp(6))
    }

    private fun smallLabel(text: String): TextView = TextView(this).apply {
        this.text = text; setTextColor(Color.rgb(208, 208, 224)); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(8), 0, dp(4))
    }

    private fun makeSwitchRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit): Switch = Switch(this).apply {
        text = label; isChecked = initial; setTextColor(Color.rgb(232, 232, 242)); textSize = 12.5f
        setPadding(0, dp(5), 0, dp(5))
        setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun makeSliderRow(title: String, min: Int, max: Int, initial: Int, unit: String, onChange: (Int) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(4)) }
        val tv = TextView(this).apply { text = "$title : $initial$unit"; setTextColor(Color.rgb(212, 212, 224)); textSize = 12f }
        val sb = SeekBar(this).apply {
            this.max = (max - min).coerceAtLeast(1)
            progress = (initial - min).coerceIn(0, this.max)
            thumbTintList = ColorStateList.valueOf(Color.rgb(217, 70, 239))
            progressTintList = ColorStateList.valueOf(Color.rgb(217, 70, 239))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = min + progress
                    tv.text = "$title : $v$unit"
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        box.addView(tv); box.addView(sb)
        return box
    }

    private fun <T> makeChipGroup(items: List<T>, selected: T, labelOf: (T) -> String, onSelect: (T) -> Unit): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var current = selected
        val btns = ArrayList<Pair<T, Button>>()
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
                    text = labelOf(item); isAllCaps = false; textSize = 11.6f; minHeight = 0; minimumHeight = 0
                    setPadding(dp(8), dp(7), dp(8), dp(7))
                    setOnClickListener { current = item; styleAll(); onSelect(item) }
                }
                btns.add(Pair(item, b))
                row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx == 0 && rowItems.size > 1) marginEnd = dp(5)
                    if (idx == 1) marginStart = dp(5)
                })
            }
            wrap.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(5)
            })
        }
        styleAll()
        return wrap
    }

    private fun makeAccentButton(label: String, bgColor: Int = Color.rgb(225, 29, 72), onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12.6f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        background = roundedBg(bgColor, 12); setPadding(dp(12), dp(10), dp(12), dp(10)); minHeight = 0; minimumHeight = 0
        setOnClickListener { onClick() }
    }

    private fun makeSecondaryBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 11.8f; setTextColor(Color.rgb(224, 224, 236))
        background = roundedBg(Color.rgb(32, 28, 46), 11, strokeColor = Color.argb(45, 255, 255, 255), strokeDp = 1)
        setPadding(dp(10), dp(8), dp(10), dp(8)); minHeight = 0; minimumHeight = 0
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6); bottomMargin = dp(2)
        }
        setOnClickListener { onClick() }
    }

    private fun makeGridTestBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 11.4f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        background = roundedBg(Color.rgb(38, 24, 50), 11, strokeColor = Color.argb(90, 217, 70, 239), strokeDp = 1)
        setPadding(dp(6), dp(9), dp(6), dp(9)); minHeight = 0; minimumHeight = 0
        setOnClickListener { onClick() }
    }

    private fun gridLp(startMargin: Int = 0, endMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = startMargin; marginEnd = endMargin
        }

    private fun roundedBg(fillColor: Int, radiusDp: Int, strokeColor: Int = Color.TRANSPARENT, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fillColor)
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    companion object {
        private val BG_DARK = Color.rgb(11, 9, 18)
        private val CARD_DARK = Color.rgb(21, 17, 32)
    }
}
