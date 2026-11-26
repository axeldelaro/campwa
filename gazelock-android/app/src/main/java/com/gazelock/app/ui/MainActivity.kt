package com.gazelock.app.ui

import android.Manifest
import android.app.AlertDialog
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
import android.provider.Settings
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
import com.gazelock.app.ai.GazeTrackerEngine
import com.gazelock.app.config.GazeConfig
import com.gazelock.app.config.GazeConfigSnapshot
import com.gazelock.app.config.OverlayMode
import com.gazelock.app.config.PenaltyMode
import com.gazelock.app.config.PopupAnimStyle
import com.gazelock.app.config.PopupFrameStyle
import com.gazelock.app.config.PopupSpawnZone
import com.gazelock.app.config.PopupTouchMode
import com.gazelock.app.config.StormPreset
import com.gazelock.app.config.StrictnessPreset
import com.gazelock.app.media.VideoFeedRepository
import com.gazelock.app.overlay.AccessCodeWaitSpamView
import com.gazelock.app.service.GazeAccessibilityService
import com.gazelock.app.service.GazeSessionService
import com.gazelock.app.service.ImagePopupStormService
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * GazeLock AI v7.0 TITAN EDITION — 5-Tab Control Center:
 * - Tab 0 (Rapide): Post-Session Grade Report, Daily Contract & Rank, Quick Launch, TITAN OVERLORD 1-Tap, Time Bomb, Folder.
 * - Tab 1 (Pop-Ups): 4 Storm Presets + GPU 60 FPS Unlimited Pop-Ups + Physics Drift/Pulse/Dodge/Surge/Spiral/Binaural/Gaze-Boost.
 * - Tab 2 (Vidéo & IA): Sensory Saturation (Breathing, Glitch, Dynamic Speed, Marquee, Freeze Shock, Heartbeat, Induction) + AI Anti-Cheat.
 * - Tab 3 (Portail 18 Jeux): 18 Visual Mini-Games, 3..12 Challenges, Cancel Tax, Mandatory 6-Digit Keypad, Hardcore x2 Mistakes.
 * - Tab 4 (Forteresse): Config Cooldown Lock (1h/6h/24h), FLAG_SECURE, Anti-Split-Screen, Watchdog, Screen-Off Tax, Permissions & Stats.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var gazeConfig: GazeConfig
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var rootFrame: FrameLayout
    private lateinit var mainScroll: ScrollView
    private lateinit var contentBox: LinearLayout
    private lateinit var spamWaitHost: FrameLayout

    private var selectedTab = 0 // 0=Rapide, 1=Pop-Ups, 2=Vidéo&IA, 3=Portail, 4=Forteresse
    private var videoCountTv: TextView? = null
    private var popupFolderCountTv: TextView? = null
    private var calibStatusTv: TextView? = null
    private var calibToggleBtn: Button? = null
    private var calibrationTracker: GazeTrackerEngine? = null
    private var calibrationActive = false
    private var activeMainWaitView: AccessCodeWaitSpamView? = null

    private val pickSafLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
        gazeConfig.update { putString("folder_path", ""); putString("tree_uri", uri.toString()) }
        Toast.makeText(this, "Dossier SAF enregistré ✅", Toast.LENGTH_SHORT).show()
        refreshUi()
    }

    private val pickPopupSafLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
        gazeConfig.update { putString("popup_folder_path_v51", ""); putString("popup_tree_uri_v51", uri.toString()) }
        Toast.makeText(this, "Dossier Images Pop-Ups enregistré ✅", Toast.LENGTH_SHORT).show()
        refreshUi()
    }

    private val stateRefresh: () -> Unit = {
        mainHandler.post { if (!isFinishing) refreshUi() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gazeConfig = GazeConfig(applicationContext)

        rootFrame = FrameLayout(this).apply { setBackgroundColor(BG_DARK) }
        mainScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        contentBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(16), dp(14), dp(36))
        }
        mainScroll.addView(contentBox)

        spamWaitHost = FrameLayout(this).apply { visibility = View.GONE; isClickable = true; isFocusable = true }
        rootFrame.addView(mainScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        rootFrame.addView(spamWaitHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(rootFrame)

        requestBasicPermissions()
        refreshUi()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (activeMainWaitView != null) {
            Toast.makeText(this, "Portail en cours ! Appuie sur Annuler en bas si tu veux quitter.", Toast.LENGTH_SHORT).show()
            return
        }
        super.onBackPressed()
    }

    override fun onStart() {
        super.onStart()
        GazeAccessibilityService.addStateListener(stateRefresh)
        GazeSessionService.addStateListener(stateRefresh)
        ImagePopupStormService.addStateListener(stateRefresh)
    }

    override fun onResume() { super.onResume(); refreshUi() }
    override fun onPause() { stopCalibrationPreview(); super.onPause() }
    override fun onStop() {
        GazeAccessibilityService.removeStateListener(stateRefresh)
        GazeSessionService.removeStateListener(stateRefresh)
        ImagePopupStormService.removeStateListener(stateRefresh)
        super.onStop()
    }
    override fun onDestroy() {
        stopCalibrationPreview()
        activeMainWaitView?.stopAndRelease(); activeMainWaitView = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun hasCameraPerm() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun hasOverlayPerm() = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun requestBasicPermissions() {
        val req = ArrayList<String>()
        if (!hasCameraPerm()) req.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) req.add(Manifest.permission.POST_NOTIFICATIONS)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) req.add(Manifest.permission.READ_MEDIA_VIDEO)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) req.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            req.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (req.isNotEmpty()) ActivityCompat.requestPermissions(this, req.toTypedArray(), 301)
    }

    private fun toggleCalibrationPreview() {
        if (calibrationActive) {
            stopCalibrationPreview()
            calibToggleBtn?.text = "📷 Tester le Suivi Regard + Iris + Anti-Triche en direct"
            calibStatusTv?.text = "Teste tes angles, tes pupilles, la distance et la détection caméra bouchée en direct."
            calibStatusTv?.setTextColor(Color.rgb(190, 190, 212))
            return
        }
        if (GazeSessionService.isSessionRunning) return
        if (!hasCameraPerm()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 302)
            Toast.makeText(this, "Autorise la caméra frontale !", Toast.LENGTH_LONG).show()
            return
        }
        calibrationActive = true
        calibToggleBtn?.text = "⏹️ Arrêter le Test Caméra IA"
        val tr = GazeTrackerEngine(
            context = applicationContext,
            onReading = { r ->
                mainHandler.post {
                    if (!calibrationActive) return@post
                    val statusLine = if (r.isLookingAtScreen) "✅ REGARD & IRIS FIXÉS — CHRONO ACTIF" else "⏸️ ${r.reason} — CHRONO FIGÉ !"
                    val zTag = if (r.zeroCalibrated) " • 🎯 ZeroOK" else ""
                    val detail = if (r.faceDetected) {
                        val irisTxt = if (r.irisMeasured) " • 👁️ Iris : ${r.irisOffsetPct}%" else " • 👁️ Iris : …"
                        "\nYaw : ${r.yawDeg.toInt()}° • Pitch : ${r.pitchDeg.toInt()}°$irisTxt • Yeux : ${(r.eyeOpenAvg * 100).toInt()}%$zTag"
                    } else "\n${if (r.cameraCovered) "🚫 Objectif obstrué / noir !" else "Aucun visage détecté devant la caméra frontale."}"
                    calibStatusTv?.text = "$statusLine$detail"
                    calibStatusTv?.setTextColor(if (r.isLookingAtScreen) Color.rgb(134, 239, 172) else Color.rgb(252, 165, 165))
                }
            },
            onCameraError = { err -> mainHandler.post { calibStatusTv?.text = "⚠️ $err"; calibStatusTv?.setTextColor(Color.rgb(253, 186, 116)) } }
        )
        calibrationTracker = tr
        tr.start(this, gazeConfig.snapshot())
    }

    private fun stopCalibrationPreview() {
        calibrationActive = false; calibrationTracker?.shutdown(); calibrationTracker = null
    }

    private fun launchThreeMinuteCodeWaitScreen() {
        if (GazeSessionService.isSessionRunning) {
            GazeSessionService.openUnlockPortalOverlay()
            return
        }
        val cfg = gazeConfig.snapshot()
        stopCalibrationPreview()
        spamWaitHost.removeAllViews()
        val wv = AccessCodeWaitSpamView(
            context = this,
            cfg = cfg,
            gazeLookingSupplier = null,
            onOverloadSprintChanged = null,
            onIssueCode = { gazeConfig.issueUnlockedAccessCode() },
            onRecordDailyPortalCompletion = { gazeConfig.recordPortalUnlockCompletedToday() },
            onCodeUnlockedAndExit = null,
            onCancelWait = {
                activeMainWaitView?.stopAndRelease(); activeMainWaitView = null
                spamWaitHost.removeAllViews(); spamWaitHost.visibility = View.GONE
                refreshUi()
            }
        )
        activeMainWaitView = wv
        spamWaitHost.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        spamWaitHost.visibility = View.VISIBLE
        wv.startThreeMinuteSpamWait()
    }

    private fun showTrappedAppsPickerDialog(cfg: GazeConfigSnapshot) {
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try { packageManager.queryIntentActivities(intent, 0) } catch (_: Throwable) { emptyList() }
        val apps = resolved
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == packageName) return@mapNotNull null
                val label = try { ri.loadLabel(packageManager).toString() } catch (_: Throwable) { pkg }
                pkg.lowercase() to label
            }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }

        if (apps.isEmpty()) {
            Toast.makeText(this, "Aucune appli détectée — présélections conservées.", Toast.LENGTH_SHORT).show()
            return
        }
        val selectedSet = cfg.trappedPackageSet().toMutableSet()
        val labels = apps.map { "${it.second} (${it.first.substringAfterLast('.')})" }.toTypedArray()
        val checked = BooleanArray(apps.size) { apps[it].first in selectedSet }

        AlertDialog.Builder(this)
            .setTitle("🪤 #21 Applis Piégées (ouvre = Overlay forcé !)")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val pkg = apps[which].first
                if (isChecked) selectedSet.add(pkg) else selectedSet.remove(pkg)
            }
            .setPositiveButton("Enregistrer (${selectedSet.size})") { _, _ ->
                gazeConfig.update {
                    putString("trapped_pkgs_v5", selectedSet.joinToString(","))
                    if (selectedSet.isNotEmpty()) putBoolean("app_trap_en_v5", true)
                }
                Toast.makeText(this, "${selectedSet.size} appli(s) piégée(s) ✅", Toast.LENGTH_SHORT).show()
                refreshUi()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showEditMantrasDialog(cfg: GazeConfigSnapshot) {
        val edit = EditText(this).apply {
            setText(cfg.subliminalMantras); minLines = 5; maxLines = 10
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); hint = "Une phrase par ligne…"
            setPadding(dp(14), dp(12), dp(14), dp(12)); setBackgroundColor(Color.rgb(26, 22, 42))
        }
        AlertDialog.Builder(this)
            .setTitle("✨ Mantras Néon, Filigranes & Voix TTS")
            .setView(edit)
            .setPositiveButton("Enregistrer") { _, _ ->
                val txt = edit.text?.toString()?.trim().orEmpty().ifBlank { GazeConfig.DEFAULT_MANTRAS_TEXT }
                gazeConfig.update { putString("subliminal_mantras_v3", txt) }
                Toast.makeText(this, "Mantras mis à jour ✅", Toast.LENGTH_SHORT).show(); refreshUi()
            }
            .setNeutralButton("Réinitialiser") { _, _ ->
                gazeConfig.update { putString("subliminal_mantras_v3", GazeConfig.DEFAULT_MANTRAS_TEXT) }; refreshUi()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun launchGazeLockSession(chaosRoulette: Boolean = false) {
        val preCfg = gazeConfig.snapshot()
        if (!hasCameraPerm()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 302)
            Toast.makeText(this, "⚠️ Autorise la Caméra frontale !", Toast.LENGTH_LONG).show(); return
        }
        if (!GazeAccessibilityService.isServiceActive() && !hasOverlayPerm()) {
            Toast.makeText(this, "⚠️ Active l'Accessibilité !", Toast.LENGTH_LONG).show()
            openAccessibilitySettings(); return
        }
        if (preCfg.folderPath.isBlank() && preCfg.treeUri.isBlank()) {
            Toast.makeText(this, "📂 Sélectionne d'abord un dossier contenant des vidéos !", Toast.LENGTH_LONG).show(); return
        }
        if (GazeSessionService.isSessionRunning) {
            Toast.makeText(this, "⚠️ Une session Vidéo GazeLock est déjà active !", Toast.LENGTH_SHORT).show()
            return
        }
        if (ImagePopupStormService.isRunning) {
            if (ImagePopupStormService.isExitLocked) {
                Toast.makeText(this, "🔒 Pop-ups d'images verrouillés ! Passe par le portail d'abord.", Toast.LENGTH_SHORT).show()
                return
            } else ImagePopupStormService.stop(applicationContext)
        }
        stopCalibrationPreview()
        val chaosSummary = if (chaosRoulette) gazeConfig.applyChaosRouletteRoll().second else null
        val cfg = gazeConfig.snapshot()

        Thread {
            val list = VideoFeedRepository.loadVideoPlaylist(applicationContext, cfg)
            mainHandler.post {
                if (list.isEmpty()) {
                    Toast.makeText(this, "⚠️ Aucune vidéo trouvée dans ce dossier !", Toast.LENGTH_LONG).show()
                } else {
                    val toastMsg = chaosSummary ?: run {
                        val modeTxt = if (cfg.overlayMode == OverlayMode.GHOST_PASSTHROUGH) "👻 Ghost ${cfg.ghostOpacityPct}%" else "🔒 Verrouillé"
                        val durTxt = if (cfg.infiniteUntilUnlock) "∞" else if (cfg.blindTimerEnabled) "??:??" else formatDuration(cfg.durationSec)
                        "$modeTxt · ${list.size} vidéos · $durTxt !"
                    }
                    Toast.makeText(this, toastMsg, Toast.LENGTH_LONG).show()
                    mainHandler.postDelayed({ GazeSessionService.startSession(applicationContext) }, 200L)
                }
            }
        }.start()
    }

    private fun launchImagePopupStormMode() {
        val cfg = gazeConfig.snapshot()
        if (!GazeAccessibilityService.isServiceActive() && !hasOverlayPerm()) {
            Toast.makeText(this, "⚠️ Active l'Accessibilité (recommandé) ou Superposition pour les pop-ups !", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }
        if (cfg.popupFolderPath.isBlank() && cfg.popupTreeUri.isBlank() &&
            cfg.folderPath.isBlank() && cfg.treeUri.isBlank()
        ) {
            Toast.makeText(this, "📂 Choisis d'abord un dossier contenant des images !", Toast.LENGTH_LONG).show()
            return
        }
        if (cfg.popupGazeBoostEnabled && !hasCameraPerm()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 302)
            Toast.makeText(this, "📷 Autorise la caméra pour le Boost Regard IA !", Toast.LENGTH_SHORT).show()
        }
        stopCalibrationPreview()
        ImagePopupStormService.start(applicationContext)
    }

    private fun refreshUi() {
        val cfg = gazeConfig.snapshot()
        val lockedVideo = GazeSessionService.isSessionRunning
        val lockedPopups = ImagePopupStormService.isRunning && ImagePopupStormService.isExitLocked
        val lockedNow = lockedVideo || lockedPopups
        val portalMin = cfg.currentPortalWaitSec() / 60
        videoCountTv = null
        popupFolderCountTv = null
        contentBox.removeAllViews()

        // ==================== HEADER ====================
        val hdr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(10)) }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(this).apply {
            text = "👁️ GazeLock AI v7.0 TITAN"
            setTextColor(Color.WHITE); textSize = 21f; typeface = Typeface.DEFAULT_BOLD
        })
        titleCol.addView(TextView(this).apply {
            text = "${cfg.rankTitle()} · 18 Mini-Jeux · Pop-Ups ∞ 60 FPS · Forteresse"
            setTextColor(Color.rgb(253, 224, 71)); textSize = 10.3f
        })
        hdr.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val a11yOk = GazeAccessibilityService.isServiceActive()
        hdr.addView(TextView(this).apply {
            text = if (a11yOk) "⚡ A11Y ON" else "⚠️ A11Y OFF"
            setTextColor(if (a11yOk) Color.rgb(167, 243, 208) else Color.rgb(253, 224, 71))
            textSize = 10.4f; typeface = Typeface.DEFAULT_BOLD
            background = roundedBg(if (a11yOk) Color.argb(45, 16, 185, 129) else Color.argb(45, 245, 158, 11), 12)
            setPadding(dp(9), dp(5), dp(9), dp(5))
            setOnClickListener { if (!lockedNow) openAccessibilitySettings() }
        })
        contentBox.addView(hdr)

        // ==================== ACTIVE SESSION LOCKDOWN ====================
        if (lockedNow) {
            val activeCard = makeCard(accentStroke = Color.rgb(225, 29, 72))
            activeCard.addView(sectionTitle(
                if (lockedPopups) "🔒 POP-UPS TITAN VERROUILLÉS EN COURS"
                else "🔒 SESSION TITAN EN COURS — VIDÉOS VERROUILLÉES"
            ))
            activeCard.addView(sectionSub(
                if (lockedPopups) "Les Pop-Ups d'Images TITAN tournent en mode verrouillé (survie Immortelle + blocage volet & volume actifs). Seul le portail imposé de $portalMin min (${cfg.portalMinigameCount} défis / 18 jeux) permet d'arrêter !"
                else "Les vidéos tournent en overlay Forteresse. Tous les réglages sont scellés.\nSeul le portail imposé de $portalMin min en overlay (${cfg.portalMinigameCount} défis tirés de 18 mini-jeux + clavier code 6 chiffres) permet de retirer les vidéos !"
            ))
            activeCard.addView(makeAccentButton("⏳ OUVRIR LE PORTAIL $portalMin MIN EN OVERLAY", Color.rgb(162, 28, 175)) {
                if (lockedPopups) ImagePopupStormService.openUnlockPortal()
                else GazeSessionService.openUnlockPortalOverlay()
            }.apply { textSize = 13.5f; setPadding(dp(14), dp(14), dp(14), dp(14)) })
            contentBox.addView(activeCard)
            return
        }

        // ==================== 5-TAB NAVIGATION BAR (#40) ====================
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBg(Color.rgb(16, 12, 28), 14, strokeColor = Color.argb(55, 255, 255, 255), strokeDp = 1)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        }
        val tabNames = listOf("🔥 Rapide", "🖼️ Pop-Ups", "🎬 Vidéo/IA", "🧩 Portail", "🛡️ Forteresse")
        tabNames.forEachIndexed { idx, label ->
            val active = selectedTab == idx
            tabBar.addView(Button(this).apply {
                text = label; isAllCaps = false; textSize = 9.8f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (active) Color.WHITE else Color.rgb(172, 168, 198))
                background = roundedBg(if (active) Color.rgb(225, 29, 72) else Color.TRANSPARENT, 10)
                minHeight = 0; minimumHeight = 0; setPadding(dp(2), dp(8), dp(2), dp(8))
                setOnClickListener {
                    if (selectedTab != idx) {
                        selectedTab = idx
                        stopCalibrationPreview()
                        refreshUi()
                        mainScroll.scrollTo(0, 0)
                    }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        contentBox.addView(tabBar)

        when (selectedTab) {
            0 -> renderQuickLaunchTab(cfg, portalMin)
            1 -> renderPopupStormTab(cfg, portalMin)
            2 -> renderVideoAndAiTab(cfg)
            3 -> renderPortalTab(cfg, portalMin)
            4 -> renderFortressTab(cfg)
        }
    }

    // ==================== TAB 0: RAPIDE, CONTRAT, BOMBE, TITAN OVERLORD ====================
    private fun renderQuickLaunchTab(cfg: GazeConfigSnapshot, portalMin: Int) {
        // #40 Post-Session Report Card
        cfg.lastReport?.let { rep ->
            val repCard = makeCard(accentStroke = Color.rgb(250, 204, 21))
            val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            topRow.addView(sectionTitle("🏆 Dernier Bilan — Note ${rep.grade} (${rep.focusPct} % fixé)"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            topRow.addView(TextView(this).apply {
                text = "✕"; setTextColor(Color.rgb(180, 180, 200)); textSize = 14f; setPadding(dp(8), dp(2), dp(4), dp(2))
                setOnClickListener { gazeConfig.clearSessionReport(); refreshUi() }
            })
            repCard.addView(topRow)
            repCard.addView(TextView(this).apply {
                text = "⏱️ Fixé : ${formatDuration(rep.focusedSec)} • 👀 Écarts : ${rep.lookAways} • 🔥 Série : ${rep.bestStreakSec} s • 🛡️ Évasions : ${rep.escapesBlocked}"
                setTextColor(Color.rgb(235, 230, 250)); textSize = 11.4f; setPadding(0, dp(4), 0, 0)
            })
            contentBox.addView(repCard)
        }

        // #38 Daily Focus Contract & Rank Card
        val contractCard = makeCard(accentStroke = Color.rgb(16, 185, 129))
        val todayMin = cfg.dailyFocusedSecToday / 60
        val pctGoal = ((cfg.dailyFocusedSecToday * 100) / (cfg.dailyContractGoalMin * 60).coerceAtLeast(1)).coerceIn(0, 100)
        contractCard.addView(sectionTitle("📜 Contrat du Jour : $todayMin / ${cfg.dailyContractGoalMin} min ($pctGoal %) · 🔥 ${cfg.dailyContractStreakDays} j"))
        contractCard.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = pctGoal
            progressTintList = ColorStateList.valueOf(if (pctGoal >= 100) Color.rgb(16, 185, 129) else Color.rgb(250, 204, 21))
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(65, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)).apply { topMargin = dp(6); bottomMargin = dp(6) }
        })
        val gRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(15 to "15m/j", 30 to "30m/j", 60 to "60m/j", 120 to "120m/j").forEachIndexed { idx, (m, lbl) ->
            gRow.addView(makeChip(lbl, cfg.dailyContractGoalMin == m) {
                gazeConfig.update { putInt("v7_contract_goal_min", m) }; refreshUi()
            }, gridLp(if (idx > 0) dp(3) else 0, if (idx < 3) dp(3) else 0))
        }
        contractCard.addView(gRow)
        contentBox.addView(contractCard)

        // Core Launch Card
        val launchCard = makeCard(accentStroke = Color.argb(225, 225, 29, 72))
        launchCard.addView(sectionTitle("👻 Mode d'Affichage Overlay & Lancement"))
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(4)) }
        OverlayMode.values().forEachIndexed { idx, m ->
            modeRow.addView(makeChip(m.label, cfg.overlayMode == m) {
                gazeConfig.update { putString("overlay_mode_v3", m.id) }; refreshUi()
            }, gridLp(if (idx > 0) dp(4) else 0, if (idx < 1) dp(4) else 0))
        }
        launchCard.addView(modeRow)
        launchCard.addView(TextView(this).apply {
            text = "→ ${cfg.overlayMode.desc}"
            setTextColor(Color.rgb(216, 180, 254)); textSize = 11f; setPadding(0, 0, 0, dp(4))
        })

        if (cfg.overlayMode == OverlayMode.GHOST_PASSTHROUGH) {
            val opRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(4)) }
            listOf(45 to "45 %", 48 to "48 % (Idéal)", 50 to "50 %", 55 to "55 %").forEachIndexed { idx, (pct, lbl) ->
                opRow.addView(makeChip(lbl, cfg.ghostOpacityPct == pct) {
                    gazeConfig.update { putInt("ghost_opacity_v3", pct) }; refreshUi()
                }, gridLp(if (idx > 0) dp(3) else 0, if (idx < 3) dp(3) else 0))
            }
            launchCard.addView(opRow)
            launchCard.addView(makeSliderRow("Opacité de base Ghost", 35, 65, cfg.ghostOpacityPct, " %") { pct ->
                gazeConfig.update { putInt("ghost_opacity_v3", pct) }
            })
        }

        if (cfg.pendingTimeDebtSec > 0 && cfg.timeDebtEnabled) {
            launchCard.addView(TextView(this).apply {
                text = "💸 DETTE EN ATTENTE : +${formatDuration(cfg.pendingTimeDebtSec)} (ajoutée d'office à la prochaine session !)"
                setTextColor(Color.rgb(253, 224, 71)); textSize = 11.2f; typeface = Typeface.DEFAULT_BOLD
                background = roundedBg(Color.argb(65, 245, 158, 11), 10, strokeColor = Color.rgb(245, 158, 11), strokeDp = 1)
                setPadding(dp(10), dp(7), dp(10), dp(7))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(4) }
            })
        }

        val durHeader = if (cfg.infiniteUntilUnlock) "🎬 Durée : ∞ Infini (Portail $portalMin min seul)" else "🎬 Durée : ${formatDuration(cfg.durationSec)}"
        launchCard.addView(smallLabel(durHeader))
        val durRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(4)) }
        listOf(180 to "3 min", 300 to "5 min", 600 to "10 min").forEachIndexed { idx, (sec, lbl) ->
            durRow1.addView(makeChip(lbl, !cfg.infiniteUntilUnlock && cfg.durationSec == sec) {
                gazeConfig.update { putBoolean("infinite_unlock_v3", false); putInt("duration_sec", sec) }; refreshUi()
            }, gridLp(if (idx > 0) dp(4) else 0, if (idx < 2) dp(4) else 0))
        }
        launchCard.addView(durRow1)
        val durRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(6)) }
        durRow2.addView(makeChip("15 min", !cfg.infiniteUntilUnlock && cfg.durationSec == 900) {
            gazeConfig.update { putBoolean("infinite_unlock_v3", false); putInt("duration_sec", 900) }; refreshUi()
        }, gridLp(endMargin = dp(4)))
        durRow2.addView(makeChip("30 min", !cfg.infiniteUntilUnlock && cfg.durationSec == 1800) {
            gazeConfig.update { putBoolean("infinite_unlock_v3", false); putInt("duration_sec", 1800) }; refreshUi()
        }, gridLp(startMargin = dp(4), endMargin = dp(4)))
        durRow2.addView(makeChip("∞ Infini", cfg.infiniteUntilUnlock) {
            gazeConfig.update { putBoolean("infinite_unlock_v3", true) }; refreshUi()
        }, gridLp(startMargin = dp(4)))
        launchCard.addView(durRow2)
        if (!cfg.infiniteUntilUnlock) {
            launchCard.addView(makeSliderRow("Durée personnalisée", 1, 60, (cfg.durationSec / 60).coerceIn(1, 60), " min") { min ->
                gazeConfig.update { putInt("duration_sec", min * 60) }
            })
        }

        val d = if (cfg.infiniteUntilUnlock) "∞" else formatDuration(cfg.durationSec)
        val ctaLabel = if (cfg.overlayMode == OverlayMode.GHOST_PASSTHROUGH) "👻🎬 LANCER OVERLAY GHOST (${cfg.ghostOpacityPct}% · $d)"
        else "🔒🎬 LANCER PLEIN ÉCRAN VERROUILLÉ ($d)"
        launchCard.addView(makeAccentButton(ctaLabel, Color.rgb(225, 29, 72)) { launchGazeLockSession(false) }.apply {
            textSize = 13.6f; setPadding(dp(12), dp(14), dp(12), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        })
        launchCard.addView(makeAccentButton("🎲 ROULETTE CHAOS TITAN (Règles Secrètes + Boss 30s)", Color.rgb(126, 34, 206)) {
            launchGazeLockSession(true)
        }.apply {
            textSize = 12f; setPadding(dp(12), dp(11), dp(12), dp(11))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        })
        // #36 Red Button TITAN OVERLORD (1-Tap Extreme Preset!)
        launchCard.addView(makeAccentButton("☢️ BOUTON ROUGE TITAN OVERLORD (Active les 40 Features !)", Color.rgb(159, 18, 57)) {
            gazeConfig.applyTitanOverlordPreset()
            Toast.makeText(this, "☢️ Arsenal TITAN OVERLORD 100 % armé !", Toast.LENGTH_LONG).show()
            refreshUi()
        }.apply {
            textSize = 11.8f; setPadding(dp(12), dp(11), dp(12), dp(11))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        })
        // Quick shortcut to Pop-Up Storm!
        val popRunning = ImagePopupStormService.isRunning
        launchCard.addView(makeAccentButton(
            if (popRunning) "⏹️ ARRÊTER POP-UPS D'IMAGES EN COURS" else "🖼️⚡ LANCER TEMPÊTE POP-UPS D'IMAGES (${formatMs(cfg.popupIntervalMs)})",
            if (popRunning) Color.rgb(185, 28, 28) else Color.rgb(219, 39, 119)
        ) {
            if (popRunning) ImagePopupStormService.stop(applicationContext) else launchImagePopupStormMode()
        }.apply {
            textSize = 12f; setPadding(dp(12), dp(11), dp(12), dp(11))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        })
        contentBox.addView(launchCard)

        // #37 Time Bomb Card
        val bombCard = makeCard(accentStroke = Color.rgb(245, 158, 11))
        bombCard.addView(sectionTitle("💣 Bombe à Retardement Surprise (#37)"))
        val nowMs = System.currentTimeMillis()
        if (cfg.timeBombArmedUntilMs > nowMs) {
            val remMin = ((cfg.timeBombArmedUntilMs - nowMs) / 60_000L).coerceAtLeast(1L)
            bombCard.addView(TextView(this).apply {
                text = "🔥 BOMBE ARMÉE ! Explosion aléatoire dans ~≤ $remMin min (session de ${cfg.timeBombDurationSec / 60} min) !"
                setTextColor(Color.rgb(253, 224, 71)); textSize = 11.6f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(4), 0, dp(6))
            })
            if (!cfg.isConfigLockedNow()) {
                bombCard.addView(makeSecondaryBtn("🧯 Désamorcer la bombe") {
                    gazeConfig.disarmTimeBomb(); Toast.makeText(this, "Bombe désamorcée.", Toast.LENGTH_SHORT).show(); refreshUi()
                })
            } else {
                bombCard.addView(sectionSub("🔒 Impossible de désamorcer tant que le Verrou Forteresse est actif !"))
            }
        } else {
            bombCard.addView(sectionSub("Arme une session qui explosera à un instant ALÉATOIRE même si tu fermes l'application !"))
            bombCard.addView(makeSliderRow("Durée de la session bombe", 1, 20, (cfg.timeBombDurationSec / 60).coerceIn(1, 20), " min") { m ->
                gazeConfig.update { putInt("v7_bomb_dur", m * 60) }
            })
            val bRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
            listOf(Triple(10, 30, "10–30 min"), Triple(30, 90, "30–90 min"), Triple(60, 180, "1–3 h")).forEachIndexed { idx, (mn, mx, lbl) ->
                bRow.addView(makeAccentButton("💣 $lbl", Color.rgb(180, 83, 9)) {
                    gazeConfig.armTimeBomb(mn, mx, gazeConfig.snapshot().timeBombDurationSec)
                    Toast.makeText(this, "💣 Bombe armée ($lbl) !", Toast.LENGTH_LONG).show()
                    refreshUi()
                }, gridLp(if (idx > 0) dp(3) else 0, if (idx < 2) dp(3) else 0))
            }
            bombCard.addView(bRow)
        }
        contentBox.addView(bombCard)

        // Folder & Quick Permissions Card
        contentBox.addView(buildFolderCard(cfg))
        refreshVideoCountAsync(cfg)
    }

    // ==================== TAB 1: MODE POP-UPS D'IMAGES TITAN ====================
    private fun renderPopupStormTab(cfg: GazeConfigSnapshot, portalMin: Int) {
        contentBox.addView(buildStandaloneImagePopupCard(cfg, portalMin))
        refreshPopupFolderCountAsync(cfg)
    }

    // ==================== TAB 2: VIDÉO, SATURATION SENSORIELLE & IA ANTI-TRICHE ====================
    private fun renderVideoAndAiTab(cfg: GazeConfigSnapshot) {
        // v7.0 Sensory Saturation Card (#15..#21)
        val v7Sens = makeCard(accentStroke = Color.rgb(236, 72, 153))
        v7Sens.addView(sectionTitle("⚡ Saturation Vidéo & Sensorielle TITAN (#15–#21)"))
        v7Sens.addView(makeSwitchRow("💓 #15 Respiration Opacité Ghost (±7 % en sinusoïde lente)", cfg.ghostBreathingOpacityEnabled) {
            gazeConfig.update { putBoolean("v7_ghost_breathe", it) }
        })
        v7Sens.addView(makeSwitchRow("⚡ #16 Glitch Chromatique & Secousse Vidéo sur chaque faute", cfg.faultGlitchEnabled) {
            gazeConfig.update { putBoolean("v7_fault_glitch", it) }
        })
        v7Sens.addView(makeSwitchRow("🎬 #17 Vitesse Vidéo Dynamique (0,86× transe → 1,30× pression)", cfg.dynamicPlaybackSpeedEnabled) {
            gazeConfig.update { putBoolean("v7_dyn_speed", it) }
        })
        v7Sens.addView(makeSwitchRow("📜 #18 Bandeaux Filigranes Mantras Défilants 60 FPS", cfg.scrollingMarqueeEnabled) {
            gazeConfig.update { putBoolean("v7_marquee", it) }
        })
        v7Sens.addView(makeSwitchRow("📸 #19 Freeze-Frame Photo-Bombe (flash 94 % / 0,9 s surprise)", cfg.freezeFrameShockEnabled) {
            gazeConfig.update { putBoolean("v7_freeze_shock", it) }
        })
        v7Sens.addView(makeSwitchRow("💓 #20 Haptique Cardiaque Rythmique (lub-dub en forte emprise)", cfg.heartbeatHapticsEnabled) {
            gazeConfig.update { putBoolean("v7_heartbeat_hap", it) }
        })
        v7Sens.addView(makeSwitchRow("🔥 #21 Tunnel d'Induction 10 s à 95 % avant d'ouvrir Ghost", cfg.inductionTunnelEnabled) {
            gazeConfig.update { putBoolean("v7_induction", it) }
        })
        contentBox.addView(v7Sens)

        // v7.0 AI Anti-Cheat & Calibration Card (#8..#14)
        val rulesCard = makeCard(accentStroke = Color.rgb(16, 185, 129))
        rulesCard.addView(sectionTitle("👁️ IA Regard, Pupilles & Anti-Triche Biométrique (#8–#14)"))
        val cTv = TextView(this).apply {
            text = "Teste tes angles, tes pupilles, la distance et l'anti-cache objectif en direct."
            setTextColor(Color.rgb(190, 190, 212)); textSize = 11.2f
            background = roundedBg(Color.rgb(24, 20, 38), 11); setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        calibStatusTv = cTv
        rulesCard.addView(cTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(6) })
        val cBtn = makeAccentButton(
            if (calibrationActive) "⏹️ Arrêter le Test Caméra IA" else "📷 Tester Regard + Iris + Anti-Triche en direct",
            Color.rgb(16, 128, 96)
        ) { toggleCalibrationPreview() }
        calibToggleBtn = cBtn
        rulesCard.addView(cBtn)

        val preRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(6)) }
        StrictnessPreset.values().forEachIndexed { idx, p ->
            preRow.addView(makeChip("${p.emoji} ${p.label}", cfg.preset == p) {
                gazeConfig.applyPreset(p); Toast.makeText(this, "Profil ${p.label} appliqué ${p.emoji}", Toast.LENGTH_SHORT).show(); refreshUi()
            }, gridLp(if (idx > 0) dp(4) else 0, if (idx < 2) dp(4) else 0))
        }
        rulesCard.addView(preRow)

        val pmRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(4)) }
        PenaltyMode.values().forEachIndexed { idx, m ->
            pmRow.addView(makeChip(m.label, cfg.penaltyMode == m) {
                gazeConfig.update { putString("penalty_mode_v2", m.id) }; refreshUi()
            }, gridLp(if (idx > 0) dp(3) else 0, if (idx < 2) dp(3) else 0))
        }
        rulesCard.addView(pmRow)

        rulesCard.addView(makeSwitchRow("🎯 #8 Calibration Auto Point Zéro (3 s au départ)", cfg.autoZeroCalibrationEnabled) {
            gazeConfig.update { putBoolean("v7_auto_zero", it) }; calibrationTracker?.updateConfig(gazeConfig.snapshot())
        })
        rulesCard.addView(makeSwitchRow("🚫 #9 Anti-Caméra Obstruée (doigt sur objectif = +10 s & +12 % + sirène)", cfg.antiCameraCoverEnabled) {
            gazeConfig.update { putBoolean("v7_anti_cam_cover", it) }; calibrationTracker?.updateConfig(gazeConfig.snapshot())
        })
        rulesCard.addView(makeSwitchRow("📏 #10 Garde Distance Visage (trop loin = chrono figé !)", cfg.faceDistanceGuardEnabled) {
            gazeConfig.update { putBoolean("v7_face_dist", it) }; calibrationTracker?.updateConfig(gazeConfig.snapshot())
        })
        rulesCard.addView(makeSwitchRow("😴 #11 Anti-Somnolence (yeux mi-clos > 1,6 s → sirène & vibration)", cfg.antiDrowsyEnabled) {
            gazeConfig.update { putBoolean("v7_anti_drowsy", it) }; calibrationTracker?.updateConfig(gazeConfig.snapshot())
        })
        rulesCard.addView(makeSwitchRow("🔥 #12 Rigueur Croissante (resserre les seuils toutes les 2 min / 3 fautes)", cfg.escalatingStrictnessEnabled) {
            gazeConfig.update { putBoolean("v7_esc_strict", it) }
        })
        rulesCard.addView(makeSwitchRow("👥 #14 Garde Solo-Visage (bloque substitution par un tiers)", cfg.soloFaceGuardEnabled) {
            gazeConfig.update { putBoolean("v7_solo_face", it) }; calibrationTracker?.updateConfig(gazeConfig.snapshot())
        })
        rulesCard.addView(makeSwitchRow("👁️ Suivi Précis Pupilles / Iris", cfg.irisTrackingEnabled) {
            gazeConfig.update { putBoolean("iris_en_v2", it) }; calibrationTracker?.updateConfig(gazeConfig.snapshot())
        })
        rulesCard.addView(makeSliderRow("Sensibilité Iris (plus bas = strict)", 18, 52, cfg.irisThresholdPct, " %") { pct ->
            gazeConfig.update { putInt("iris_pct_v2", pct) }; calibrationTracker?.updateConfig(gazeConfig.snapshot())
        })
        rulesCard.addView(makeSliderRow("Tolérance tête (Yaw/Pitch)", 12, 38, cfg.yawThresholdDeg, "°") { deg ->
            gazeConfig.update { putInt("yaw_deg", deg); putInt("pitch_deg", (deg + 2).coerceAtMost(40)) }
            calibrationTracker?.updateConfig(gazeConfig.snapshot())
        })
        rulesCard.addView(makeSwitchRow("⏸️ Geler vidéo si regard détourné", cfg.pauseVideoOnLookAway) { gazeConfig.update { putBoolean("pause_video_away", it) } })
        rulesCard.addView(makeSwitchRow("🙈 Exiger yeux ouverts", cfg.requireEyesOpen) { gazeConfig.update { putBoolean("eyes_open", it) } })
        rulesCard.addView(makeSwitchRow("🗣️ Alertes vocales + 📳 Vibrations", cfg.voiceAlertEnabled) {
            gazeConfig.update { putBoolean("voice_alert", it); putBoolean("vibrate_alert", it) }
        })
        contentBox.addView(rulesCard)

        // Arsenal v5.0 & Emprise Ghost Card
        val v5Card = makeCard(accentStroke = Color.rgb(250, 204, 21))
        v5Card.addView(sectionTitle("👑 Boss Final · Emprise Ghost · Applis Piégées"))
        v5Card.addView(makeSwitchRow("👑 #76 Boss Final (30 s à 100 % Zéro Faute — échec = +60 s !)", cfg.finalBossEnabled) {
            gazeConfig.update { putBoolean("final_boss_v5", it) }
        })
        v5Card.addView(makeSwitchRow("👁️ #11 Défi Anti-Clignement (+15 s & +8 % si clignement)", cfg.noBlinkChallengeEnabled) {
            gazeConfig.update { putBoolean("no_blink_en_v5", it) }
        })
        v5Card.addView(makeSliderRow("Durée Anti-Clignement", 8, 20, cfg.noBlinkDurationSec, " s") { v ->
            gazeConfig.update { putInt("no_blink_sec_v5", v) }
        })
        v5Card.addView(makeSwitchRow("📈 Emprise Progressive (Opacité monte 48% → 96%)", cfg.livingOpacityEnabled) {
            gazeConfig.update { putBoolean("living_opacity_v4", it) }
        })
        v5Card.addView(makeSwitchRow("⚡ Coupures d'Emprise (passe à 96% tactile bloqué)", cfg.attentionHijackEnabled) {
            gazeConfig.update { putBoolean("hijack_en_v4", it) }
        })
        v5Card.addView(makeSliderRow("Fréquence Coupures d'Emprise (toutes les)", 1, 8, cfg.attentionHijackIntervalMin, " min") { v ->
            gazeConfig.update { putInt("hijack_int_min_v4", v) }
        })
        v5Card.addView(makeSliderRow("Regard continu exigé", 8, 25, cfg.attentionHijackFocusSec, " s") { v ->
            gazeConfig.update { putInt("hijack_focus_sec_v4", v) }
        })
        v5Card.addView(makeSwitchRow("🖼️ Micro-Pop-Ups Fantômes pendant l'usage (ignorer = +6 %)", cfg.ghostMicroPopupsEnabled) {
            gazeConfig.update { putBoolean("ghost_popups_en_v4", it) }
        })
        v5Card.addView(makeSliderRow("Intervalle Micro-Pop-Ups", 20, 90, cfg.ghostMicroPopupIntervalSec, " s") { v ->
            gazeConfig.update { putInt("ghost_popups_int_v4", v) }
        })
        v5Card.addView(makeSwitchRow("🚫 Zones Mortes Tactiles Punitives sur faute", cfg.deadZonesEnabled) {
            gazeConfig.update { putBoolean("dead_zones_en_v4", it) }
        })
        v5Card.addView(makeSwitchRow("🔀 Taxe Changement d'Appli (> 3 applis/min = +10 s & +5 %)", cfg.appSwitchTaxEnabled) {
            gazeConfig.update { putBoolean("app_switch_tax_v5", it) }
        })
        v5Card.addView(makeSwitchRow("🔔 Conditionnement Sonore Pavlovien", cfg.pavlovSoundEnabled) {
            gazeConfig.update { putBoolean("pavlov_sound_v5", it) }
        })
        v5Card.addView(makeSwitchRow("💸 Dette de Temps sur sortie anticipée", cfg.timeDebtEnabled) {
            gazeConfig.update { putBoolean("time_debt_en_v4", it) }
        })
        val debtRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(4)) }
        listOf(50 to "Dette 50%", 75 to "Dette 75%", 100 to "Dette 100%").forEachIndexed { idx, (pct, lbl) ->
            debtRow.addView(makeChip(lbl, cfg.timeDebtCarryPct == pct) {
                gazeConfig.update { putInt("time_debt_pct_v4", pct) }; refreshUi()
            }, gridLp(if (idx > 0) dp(4) else 0, if (idx < 2) dp(4) else 0))
        }
        v5Card.addView(debtRow)
        v5Card.addView(makeSwitchRow("🌀 Spirale Hypnotique 60 FPS", cfg.hypnoticSpiralEnabled) {
            gazeConfig.update { putBoolean("spiral_en_v4", it) }
        })
        v5Card.addView(makeSliderRow("Opacité Spirale", 12, 55, cfg.spiralOpacityPct, " %") { v ->
            gazeConfig.update { putInt("spiral_opacity_v4", v) }
        })
        v5Card.addView(makeSwitchRow("🎙️ Chuchotements TTS Graves", cfg.ttsMantraWhisperEnabled) {
            gazeConfig.update { putBoolean("tts_whisper_v4", it) }
        })
        v5Card.addView(makeSwitchRow("🙈 Chrono Aveugle (??:??)", cfg.blindTimerEnabled) {
            gazeConfig.update { putBoolean("blind_timer_v4", it) }
        })
        v5Card.addView(makeSwitchRow("📐 Anti-Pose Accéléromètre (table > 6 s → 90 %)", cfg.antiFlatSensorEnabled) {
            gazeConfig.update { putBoolean("anti_flat_v4", it) }
        })
        v5Card.addView(makeSwitchRow("🪤 #21 Embuscades sur Applis Piégées", cfg.appTrapEnabled) {
            gazeConfig.update { putBoolean("app_trap_en_v5", it) }
        })
        v5Card.addView(makeSecondaryBtn("📱 Choisir les applis piégées (${cfg.trappedPackageSet().size} sélectionnée(s))") {
            showTrappedAppsPickerDialog(cfg)
        })
        v5Card.addView(makeSliderRow("Durée sur appli piégée", 1, 10, (cfg.appTrapDurationSec / 60).coerceIn(1, 10), " min") { m ->
            gazeConfig.update { putInt("app_trap_dur_v5", m * 60) }
        })
        contentBox.addView(v5Card)

        // Subliminal & Audio / Binaural Card
        val sensoryCard = makeCard(accentStroke = Color.argb(175, 14, 165, 233))
        sensoryCard.addView(sectionTitle("✨ Subliminal · 🔊 Volume Absolu · 🎧 Binaural"))
        sensoryCard.addView(makeSwitchRow("✨ Flashs Subliminaux (0,08–0,22 s) + Mantras Néon", cfg.subliminalEnabled) {
            gazeConfig.update { putBoolean("subliminal_en_v3", it) }
        })
        sensoryCard.addView(makeSliderRow("Intervalle Flashs Subliminaux", 2, 20, cfg.subliminalIntervalSec, " s") { v ->
            gazeConfig.update { putInt("subliminal_int_v3", v) }
        })
        sensoryCard.addView(makeSliderRow("Durée Flash Subliminal", 80, 220, cfg.subliminalDurationMs, " ms") { v ->
            gazeConfig.update { putInt("subliminal_dur_v3", v) }
        })
        sensoryCard.addView(makeSecondaryBtn("✏️ Éditer mantras, filigranes & voix TTS (${cfg.mantraList().size} phrases)") { showEditMantrasDialog(cfg) })
        sensoryCard.addView(makeSwitchRow("🔒 Verrou Volume Absolu + Blocage Touches", cfg.volumeLockEnabled) {
            gazeConfig.update { putBoolean("volume_lock", it) }
        })
        sensoryCard.addView(makeSliderRow("Plancher Volume Média", 40, 100, cfg.volumeFloorPct, " %") { v ->
            gazeConfig.update { putInt("volume_floor_v3", v) }
        })
        sensoryCard.addView(makeSwitchRow("☀️ Luminosité Overlay forcée à 100 %", cfg.forceMaxBrightness) {
            gazeConfig.update { putBoolean("force_brightness_v3", it) }
        })
        sensoryCard.addView(makeSwitchRow("🎧 Battement Binaural Hypnotique (118 BPM en Sprint/Boss)", cfg.binauralBeatEnabled) {
            gazeConfig.update { putBoolean("binaural_en_v3", it) }
        })
        sensoryCard.addView(makeSliderRow("Intensité Binaurale", 10, 80, cfg.binauralVolumePct, " %") { v ->
            gazeConfig.update { putInt("binaural_vol_v3", v) }
        })
        sensoryCard.addView(makeSliderRow("Fréquence Binaurale (4–12 Hz)", 4, 12, cfg.binauralFreqHz, " Hz") { v ->
            gazeConfig.update { putInt("binaural_hz_v3", v) }
        })
        contentBox.addView(sensoryCard)
    }

    // ==================== TAB 3: PORTAIL 18 MINI-JEUX & DURCISSEMENTS TITAN ====================
    private fun renderPortalTab(cfg: GazeConfigSnapshot, portalMin: Int) {
        val codeCard = makeCard(accentStroke = Color.argb(220, 192, 38, 211))
        codeCard.addView(sectionTitle("🧩 Portail Overlay TITAN ($portalMin min · 18 Mini-Jeux !)"))
        val dayNote = if (cfg.dailyEscalatingPortalEnabled) {
            "Durée actuelle aujourd'hui : $portalMin min (déblocage #${cfg.portalUsesToday + 1} · escalade 3m → 4m → 5m → 6m)."
        } else "Durée fixe : 3 min."
        codeCard.addView(sectionSub(
            "$dayNote\n18 mini-jeux visuels avec tes photos (dont 4 nouveaux v7.0 : 🎯 Cibles Mouvantes, 🔐 Code Visuel de Photos, 🔍 Loupe Micro-Détail 3×, ⚖️ Photo Jumelle ou Piège + Gratte & Identifie, Mahjong 4×4, Copie Altérée, Symboles, Verrous Précision, Bandes 5×1, Taquin 3×3/4×4, Chasse 9 img/s, Échange, Rotation, Memory, Séquence & Intrus) + 🔥 Combo ON FIRE !"
        ))

        codeCard.addView(smallLabel("🎯 Nombre de mini-jeux pendant les $portalMin min (${cfg.portalMinigameCount} défis) :"))
        val mgRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(2)) }
        listOf(3 to "3 Défis", 6 to "6 (Intense)", 9 to "9 (Extrême)", 12 to "12 (Marathon)").forEachIndexed { idx, (cnt, lbl) ->
            mgRow.addView(makeChip(lbl, cfg.portalMinigameCount == cnt) {
                gazeConfig.update { putInt("portal_game_count_v6", cnt) }; refreshUi()
            }, gridLp(if (idx > 0) dp(3) else 0, if (idx < 3) dp(3) else 0))
        }
        codeCard.addView(mgRow)
        codeCard.addView(makeSliderRow("Nombre exact de mini-jeux par portail", 3, 12, cfg.portalMinigameCount, " défis") { v ->
            gazeConfig.update { putInt("portal_game_count_v6", v) }
        })

        codeCard.addView(smallLabel("💀 Durcissements TITAN du Portail (#34–#35) :"))
        codeCard.addView(makeFortressSwitchRow(cfg, "⚠️ #34a Taxe d'Annulation (+30 s & +10 % opacité si annulé !)", cfg.portalCancelTaxEnabled) {
            gazeConfig.update { putBoolean("v7_portal_cancel_tax", it) }
        })
        codeCard.addView(makeSwitchRow("🔢 #34b Double Saisie Obligatoire (clavier tactile 6 chiffres final)", cfg.portalRequireCodeEntryEnabled) {
            gazeConfig.update { putBoolean("v7_portal_code_entry", it) }
        })
        codeCard.addView(makeSwitchRow("💀 #34c Mode Zéro Tolérance (erreurs doublées +10 s + pop-ups pièges)", cfg.portalHardcorePenaltiesEnabled) {
            gazeConfig.update { putBoolean("v7_portal_hardcore", it) }
        })
        codeCard.addView(makeSwitchRow("🔥 #51 Taquin 4×4 Mode Enfer (15 tuiles)", cfg.hellPuzzle4x4Enabled) {
            gazeConfig.update { putBoolean("hell_4x4_v5", it) }
        })
        codeCard.addView(makeSwitchRow("⚡ #54 Chasse au Motif Éclair à 9 images/s (111 ms)", cfg.flashHunt9FpsEnabled) {
            gazeConfig.update { putBoolean("flash_hunt_9fps_v5", it) }
        })
        codeCard.addView(makeSwitchRow("📈 #70 Portail Progressif Journalier (3 → 4 → 5 → 6 min)", cfg.dailyEscalatingPortalEnabled) {
            gazeConfig.update { putBoolean("daily_esc_portal_v5", it) }; refreshUi()
        })
        codeCard.addView(makeSwitchRow("🔔 Pop-ups chronométrés (+8–10 s si ignorés)", cfg.unlockPopupsEnabled) {
            gazeConfig.update { putBoolean("unlock_popups_v3", it) }
        })
        codeCard.addView(makeSwitchRow("🧩 Mini-jeux obligatoires (pausent le décompte)", cfg.unlockMinigamesEnabled) {
            gazeConfig.update { putBoolean("unlock_minigames_v3", it) }
        })
        codeCard.addView(makeSwitchRow("🔥 #17 Sprint Surcharge 00:45→00:00 (20 FPS · Échec = retour 01:00)", cfg.overloadSprintEnabled) {
            gazeConfig.update { putBoolean("overload_sprint_v4", it) }
        })
        codeCard.addView(makeSwitchRow("👁️ #22 Portail Verrouillé par le Regard Caméra (+5 s si détourné)", cfg.gazeGatedPortalEnabled) {
            gazeConfig.update { putBoolean("gaze_portal_v4", it) }
        })
        codeCard.addView(makeAccentButton("⏳ Tester / Lancer le Portail ($portalMin min · ${cfg.portalMinigameCount} Défis / 18 Jeux)", Color.rgb(162, 28, 175)) {
            launchThreeMinuteCodeWaitScreen()
        }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) } })
        contentBox.addView(codeCard)
    }

    // ==================== TAB 4: FORTERESSE ANTI-ÉVASION, VERROU RÉGLAGES & STATS ====================
    private fun renderFortressTab(cfg: GazeConfigSnapshot) {
        val nowMs = System.currentTimeMillis()
        val lockedConfig = cfg.isConfigLockedNow(nowMs)

        // #39 Hardcore Settings Cooldown Lock Card
        val lockCard = makeCard(accentStroke = if (lockedConfig) Color.rgb(244, 63, 94) else Color.rgb(168, 85, 247))
        lockCard.addView(sectionTitle("🔐 Verrou Anti-Désactivation Forteresse (#39)"))
        if (lockedConfig) {
            val remMin = ((cfg.configLockUntilMs - nowMs) / 60_000L).coerceAtLeast(1L)
            lockCard.addView(TextView(this).apply {
                text = "🔒 VERROU ACTIF ENCORE ${remMin / 60} h ${remMin % 60} min — Impossible de désactiver les sécurités critiques ci-dessous avant expiration !"
                setTextColor(Color.rgb(252, 165, 165)); textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(4), 0, dp(4))
            })
        } else {
            lockCard.addView(sectionSub("Verrouille les sécurités critiques (Anti-Évasion, FLAG_SECURE, Anti-Split-Screen, Mode Immortel, Taxe Écran Éteint & Annulation) pour t'empêcher de les couper sur un coup de tête !"))
            val lRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
            listOf(1 to "🔒 1 heure", 6 to "🔒 6 heures", 24 to "🔒 24 heures").forEachIndexed { idx, (hrs, lbl) ->
                lRow.addView(makeAccentButton(lbl, Color.rgb(159, 18, 57)) {
                    AlertDialog.Builder(this)
                        .setTitle("🔐 Confirmer le Verrou $hrs h ?")
                        .setMessage("Toutes les protections Forteresse seront activées et impossibles à désactiver pendant $hrs heure(s) !")
                        .setPositiveButton("Verrouiller $hrs h") { _, _ ->
                            gazeConfig.lockCriticalSettingsForHours(hrs)
                            Toast.makeText(this, "🔒 Forteresse scellée pour $hrs h !", Toast.LENGTH_LONG).show()
                            refreshUi()
                        }
                        .setNegativeButton("Annuler", null)
                        .show()
                }, gridLp(if (idx > 0) dp(3) else 0, if (idx < 2) dp(3) else 0))
            }
            lockCard.addView(lRow)
        }
        contentBox.addView(lockCard)

        // Fortress Protections (#1..#7)
        val fortCard = makeCard(accentStroke = Color.rgb(225, 29, 72))
        fortCard.addView(sectionTitle("🛡️ Forteresse Anti-Évasion TITAN (#1–#7)"))
        fortCard.addView(makeFortressSwitchRow(cfg, "🔒 #1 FLAG_SECURE (bloque screenshots sur Portail, Boss & Verrou)", cfg.portalFlagSecureEnabled) {
            gazeConfig.update { putBoolean("v7_flag_secure", it) }
        })
        fortCard.addView(makeFortressSwitchRow(cfg, "🛡️ #2 Anti-Écran Scindé & Anti-Fenêtre Flottante (+15 s & +10 %)", cfg.antiSplitScreenEnabled) {
            gazeConfig.update { putBoolean("v7_anti_split", it) }
        })
        fortCard.addView(makeFortressSwitchRow(cfg, "⚡ #3 Watchdog Auto-Guérisseur Overlay (remonte Z-order / 1,5 s)", cfg.overlayWatchdogEnabled) {
            gazeConfig.update { putBoolean("v7_watchdog", it) }
        })
        fortCard.addView(makeFortressSwitchRow(cfg, "🔊 #4 Garde Audio Stricte (bloque volume même en Pop-Ups Verrouillés)", cfg.strictAudioGuardEnabled) {
            gazeConfig.update { putBoolean("v7_strict_audio", it) }
        })
        fortCard.addView(makeFortressSwitchRow(cfg, "🚫 #5 Bloquer Volet Notifs / Quick Settings en Ghost & Pop-Ups", cfg.blockShadeInGhostEnabled) {
            gazeConfig.update { putBoolean("block_shade_ghost_v5", it); putBoolean("v7_popup_block_shade", it) }
        })
        fortCard.addView(makeFortressSwitchRow(cfg, "🛑 #7 Taxe Écran Éteint (éteindre l'écran en session = +20 s & +10 %)", cfg.screenOffPenaltyEnabled) {
            gazeConfig.update { putBoolean("v7_screen_off_tax", it) }
        })
        fortCard.addView(makeFortressSwitchRow(cfg, "🚨 Anti-Évasion Paramètres / Désinstallation / Force-Stop (+15 s)", cfg.antiEscapeLockEnabled) {
            gazeConfig.update { putBoolean("anti_escape_v2", it) }
        })
        fortCard.addView(makeFortressSwitchRow(cfg, "🔁 Mode Immortel Vidéo & Pop-Ups (+45–60 s après reboot/kill)", cfg.rebootSurvivalEnabled) {
            gazeConfig.update { putBoolean("reboot_survival_v4", it); putBoolean("v7_popup_immortal", it) }
        })
        contentBox.addView(fortCard)

        // Auto-Ambush + Permissions + Stats
        val autoCard = makeCard(accentStroke = Color.argb(145, 14, 165, 233))
        autoCard.addView(sectionTitle("⏰ Embuscades Auto · 🛡️ Autorisations · 📊 Stats"))
        autoCard.addView(makeSwitchRow("Déclencher l'overlay automatiquement par durée d'écran", cfg.autoTriggerEnabled) {
            gazeConfig.update { putBoolean("auto_en", it) }
        })
        autoCard.addView(makeSliderRow("Toutes les", 5, 60, cfg.autoTriggerEveryMin, " min d'écran") { m ->
            gazeConfig.update { putInt("auto_every_min", m) }
        })
        autoCard.addView(makeSliderRow("Durée surprise", 1, 10, (cfg.autoTriggerDurationSec / 60).coerceIn(1, 10), " min") { m ->
            gazeConfig.update { putInt("auto_dur_sec", m * 60) }
        })

        val pRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(5)) }
        pRow1.addView(makePermChip("Accessibilité", GazeAccessibilityService.isServiceActive()) { openAccessibilitySettings() }, gridLp(endMargin = dp(4)))
        pRow1.addView(makePermChip("Caméra IA", hasCameraPerm()) { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 302) }, gridLp(startMargin = dp(4)))
        autoCard.addView(pRow1)

        val pRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pRow2.addView(makePermChip("Fichiers .*", VideoFeedRepository.hasAllFilesAccess()) { requestAllFilesAccess() }, gridLp(endMargin = dp(4)))
        pRow2.addView(makePermChip("Superposition", hasOverlayPerm()) { openOverlaySettings() }, gridLp(startMargin = dp(4)))
        autoCard.addView(pRow2)

        if (!GazeAccessibilityService.isServiceActive() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            autoCard.addView(makeSecondaryBtn("ℹ️ Android 13+ grisé ? Infos Appli (⋮ → Paramètres restreints)") {
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Throwable) {}
            })
        }

        val totalMin = cfg.totalFocusedSec / 60L
        autoCard.addView(TextView(this).apply {
            text = "\n🏅 Rang : ${cfg.rankTitle()} • 🔥 Streak contrat : ${cfg.dailyContractStreakDays} j\n" +
                "📊 Sessions : ${cfg.sessionsCompleted} • Fixé : $totalMin min ${cfg.totalFocusedSec % 60L} s\n" +
                "👀 Écarts : ${cfg.totalLookAways} • 🛡️ Évasions bloquées : ${cfg.escapeBlocksCount} • 🏆 Record : ${cfg.bestStreakSec} s"
            setTextColor(Color.rgb(218, 218, 240)); textSize = 11.4f
        })
        contentBox.addView(autoCard)
    }

    private fun buildFolderCard(cfg: GazeConfigSnapshot): LinearLayout {
        val folderCard = makeCard(accentStroke = Color.argb(145, 168, 85, 247))
        folderCard.addView(sectionTitle("📂 Dossier Vidéos & Images (.* & .nomedia inclus)"))
        val vcTv = TextView(this).apply {
            text = "⏳ Analyse du dossier…"; setTextColor(Color.rgb(216, 180, 254)); textSize = 11.4f; typeface = Typeface.MONOSPACE
            background = roundedBg(Color.rgb(26, 20, 42), 10); setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        videoCountTv = vcTv
        folderCard.addView(vcTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(8) })
        folderCard.addView(makeAccentButton("🔍 Parcourir dossiers (masqués .* inclus)", Color.rgb(147, 51, 234)) {
            if (!VideoFeedRepository.hasAllFilesAccess()) {
                Toast.makeText(this, "Autorise « Accès à tous les fichiers » !", Toast.LENGTH_LONG).show(); requestAllFilesAccess()
            } else {
                FolderPickerDialog.show(this, cfg.folderPath) { chosen ->
                    gazeConfig.update { putString("folder_path", chosen); putString("tree_uri", "") }; refreshUi()
                }
            }
        })
        folderCard.addView(makeSecondaryBtn("📁 Ou sélecteur Android standard (SAF)") { pickSafLauncher.launch(null) })
        folderCard.addView(makeSwitchRow("🔀 Ordre aléatoire", cfg.shuffleVideos) { gazeConfig.update { putBoolean("shuffle", it) } })
        folderCard.addView(makeSwitchRow("⏭ Autoriser passer à la vidéo suivante", cfg.allowSwipeNext) { gazeConfig.update { putBoolean("swipe_next", it) } })
        val clipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        listOf(0 to "Complète", 15 to "15 s", 30 to "30 s", 60 to "60 s").forEachIndexed { idx, (sec, lbl) ->
            clipRow.addView(makeChip(lbl, cfg.maxClipSec == sec) {
                gazeConfig.update { putInt("max_clip_sec", sec) }; refreshUi()
            }, gridLp(if (idx > 0) dp(3) else 0, if (idx < 3) dp(3) else 0))
        }
        folderCard.addView(clipRow)
        return folderCard
    }

    private fun buildStandaloneImagePopupCard(cfg: GazeConfigSnapshot, portalMin: Int): LinearLayout {
        val popRunning = ImagePopupStormService.isRunning
        val card = makeCard(accentStroke = if (popRunning) Color.rgb(16, 185, 129) else Color.rgb(236, 72, 153))
        card.addView(sectionTitle("🖼️🔥 Mode Pop-Ups d'Images TITAN (Hors Vidéos · 60 FPS)"))
        card.addView(sectionSub(
            "Jusqu'à 33 pop-ups/s, salves ×12, nombre ∞ ILLIMITÉ (GPU 60 FPS), physique rebond, pulsation, esquive, déferlantes SURGE ×3, spirale, audio binaural & boost regard IA !"
        ))

        // #29 4 Storm Presets (1-Tap)
        card.addView(smallLabel("🚀 Presets Tempête 1-Tap (#29) :"))
        val pRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(3)) }
        pRow1.addView(makeAccentButton(StormPreset.GENTLE.label, Color.rgb(14, 116, 144)) {
            gazeConfig.applyStormPreset(StormPreset.GENTLE); Toast.makeText(this, "Preset Doux appliqué", Toast.LENGTH_SHORT).show(); refreshUi()
        }, gridLp(endMargin = dp(3)))
        pRow1.addView(makeAccentButton(StormPreset.INTENSE.label, Color.rgb(147, 51, 234)) {
            gazeConfig.applyStormPreset(StormPreset.INTENSE); Toast.makeText(this, "Preset Intensif appliqué ⚡", Toast.LENGTH_SHORT).show(); refreshUi()
        }, gridLp(startMargin = dp(3)))
        card.addView(pRow1)
        val pRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(6)) }
        pRow2.addView(makeAccentButton(StormPreset.HURRICANE.label, Color.rgb(190, 24, 93)) {
            gazeConfig.applyStormPreset(StormPreset.HURRICANE); Toast.makeText(this, "Preset Ouragan ∞ appliqué 🌪️", Toast.LENGTH_SHORT).show(); refreshUi()
        }, gridLp(endMargin = dp(3)))
        pRow2.addView(makeAccentButton(StormPreset.APOCALYPSE.label, Color.rgb(159, 18, 57)) {
            gazeConfig.applyStormPreset(StormPreset.APOCALYPSE); Toast.makeText(this, "☢️ Preset APOCALYPSE ∞ armé !", Toast.LENGTH_LONG).show(); refreshUi()
        }, gridLp(startMargin = dp(3)))
        card.addView(pRow2)

        val pCountTv = TextView(this).apply {
            text = "⏳ Analyse du dossier images…"
            setTextColor(Color.rgb(249, 168, 212)); textSize = 11.2f; typeface = Typeface.MONOSPACE
            background = roundedBg(Color.rgb(28, 18, 42), 10); setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        popupFolderCountTv = pCountTv
        card.addView(pCountTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2); bottomMargin = dp(6) })

        val folderBtnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(4)) }
        folderBtnRow.addView(makeAccentButton("📂 Dossier Images dédié (.*)", Color.rgb(147, 51, 234)) {
            if (!VideoFeedRepository.hasAllFilesAccess()) {
                Toast.makeText(this, "Autorise « Accès à tous les fichiers » !", Toast.LENGTH_LONG).show(); requestAllFilesAccess()
            } else {
                FolderPickerDialog.show(this, cfg.popupFolderPath.ifBlank { cfg.folderPath }) { chosen ->
                    gazeConfig.update { putString("popup_folder_path_v51", chosen); putString("popup_tree_uri_v51", "") }; refreshUi()
                }
            }
        }, gridLp(endMargin = dp(4)))
        folderBtnRow.addView(makeAccentButton("📁 SAF", Color.rgb(79, 70, 229)) {
            pickPopupSafLauncher.launch(null)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
        card.addView(folderBtnRow)

        if (cfg.popupFolderPath.isNotBlank() || cfg.popupTreeUri.isNotBlank()) {
            card.addView(makeSecondaryBtn("↩ Réutiliser le même dossier que les vidéos") {
                gazeConfig.update { putString("popup_folder_path_v51", ""); putString("popup_tree_uri_v51", "") }; refreshUi()
            })
        }

        // Launch / Stop button right at the top too for instant access!
        if (popRunning) {
            card.addView(makeAccentButton("⏹️ ARRÊTER LES POP-UPS D'IMAGES", Color.rgb(225, 29, 72)) {
                ImagePopupStormService.stop(applicationContext)
            }.apply { textSize = 13.6f; setPadding(dp(12), dp(13), dp(12), dp(13)); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(6) } })
        } else {
            val lockTag = if (cfg.popupLockExitWithPortal) " · 🔒" else ""
            val maxShort = if (cfg.popupMaxSimultaneous == 0) "∞" else "${cfg.popupMaxSimultaneous}"
            card.addView(makeAccentButton("🖼️🔥 LANCER POP-UPS (${formatMs(cfg.popupIntervalMs)} · ×${cfg.popupBurstCount} · $maxShort$lockTag)", Color.rgb(219, 39, 119)) {
                launchImagePopupStormMode()
            }.apply { textSize = 13f; setPadding(dp(12), dp(13), dp(12), dp(13)); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(6) } })
        }

        // ---- SIZE MIN / MAX ----
        val curMinSz = cfg.popupMinSizePct.coerceAtMost(cfg.popupSizePct)
        val curMaxSz = cfg.popupSizePct.coerceAtLeast(curMinSz)
        card.addView(smallLabel("📐 Taille aléatoire ($curMinSz % → $curMaxSz %) :"))
        val szRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(2)) }
        listOf(Triple(15, 35, "Vignettes"), Triple(30, 62, "Moyen"), Triple(65, 96, "Géant"), Triple(15, 100, "Chaos ∞")).forEachIndexed { idx, (mn, mx, lbl) ->
            szRow.addView(makeChip(lbl, curMinSz == mn && curMaxSz == mx) {
                gazeConfig.update { putInt("popup_min_size_v6", mn); putInt("popup_size_pct_v51", mx) }; refreshUi()
            }, gridLp(if (idx > 0) dp(3) else 0, if (idx < 3) dp(3) else 0))
        }
        card.addView(szRow)
        card.addView(makeSliderRow("Taille MIN", 12, 100, curMinSz, " %") { v ->
            val maxNow = gazeConfig.snapshot().popupSizePct
            gazeConfig.update { putInt("popup_min_size_v6", v); if (v > maxNow) putInt("popup_size_pct_v51", v.coerceAtLeast(15)) }
        })
        card.addView(makeSliderRow("Taille MAX", 15, 100, curMaxSz, " %") { v ->
            val minNow = gazeConfig.snapshot().popupMinSizePct
            gazeConfig.update { putInt("popup_size_pct_v51", v); if (v < minNow) putInt("popup_min_size_v6", v.coerceAtLeast(12)) }
        })

        // ---- FREQUENCY (30ms..60s) ----
        card.addView(smallLabel("⚡ Fréquence (${formatMs(cfg.popupIntervalMs)} · ${formatRatePerSec(cfg.popupIntervalMs)}) :"))
        val fqRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(3)) }
        listOf(30 to "30ms (33/s🔥)", 50 to "50ms (20/s)", 100 to "0,1s (10/s)", 250 to "0,25s").forEachIndexed { idx, (ms, lbl) ->
            fqRow1.addView(makeChip(lbl, cfg.popupIntervalMs == ms) {
                gazeConfig.update { putInt("popup_int_ms_v6", ms) }; refreshUi()
            }, gridLp(if (idx > 0) dp(3) else 0, if (idx < 3) dp(3) else 0))
        }
        card.addView(fqRow1)
        val fqRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(2)) }
        listOf(500 to "0,5 s", 1500 to "1,5 s", 5000 to "5 s", 15000 to "15 s").forEachIndexed { idx, (ms, lbl) ->
            fqRow2.addView(makeChip(lbl, cfg.popupIntervalMs == ms) {
                gazeConfig.update { putInt("popup_int_ms_v6", ms) }; refreshUi()
            }, gridLp(if (idx > 0) dp(3) else 0, if (idx < 3) dp(3) else 0))
        }
        card.addView(fqRow2)
        card.addView(makeMsLogSliderRow("Fréquence exacte (30 ms → 60 s)", 30, 60_000, cfg.popupIntervalMs) { ms ->
            gazeConfig.update { putInt("popup_int_ms_v6", ms) }
        })

        // ---- BURSTS (1..12) ----
        card.addView(smallLabel("💥 Salves simultanées (${cfg.popupBurstCount} img/tick) :"))
        val burstRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(2)) }
        listOf(1 to "×1", 2 to "×2", 4 to "×4", 8 to "×8", 12 to "×12 🔥").forEachIndexed { idx, (b, lbl) ->
            burstRow.addView(makeChip(lbl, cfg.popupBurstCount == b) {
                gazeConfig.update { putInt("popup_burst_cnt_v6", b) }; refreshUi()
            }, gridLp(if (idx > 0) dp(2) else 0, if (idx < 4) dp(2) else 0))
        }
        card.addView(burstRow)
        card.addView(makeSliderRow("Nombre par salve", 1, 12, cfg.popupBurstCount, " img") { v ->
            gazeConfig.update { putInt("popup_burst_cnt_v6", v) }
        })

        // ---- MAX SIMULTANEOUS (1..150 or ∞) ----
        val maxLblText = if (cfg.popupMaxSimultaneous == 0) "∞ ILLIMITÉ (GPU 60 FPS)" else "${cfg.popupMaxSimultaneous} pop-ups max"
        card.addView(smallLabel("🌪️ Simultanés à l'écran ($maxLblText) :"))
        val maxRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(3)) }
        listOf(5 to "5", 15 to "15", 40 to "40", 100 to "100", 0 to "∞ ILLIMITÉ").forEachIndexed { idx, (cnt, lbl) ->
            maxRow.addView(makeChip(lbl, cfg.popupMaxSimultaneous == cnt) {
                gazeConfig.update { putInt("popup_max_simul_v6", cnt) }; refreshUi()
            }, gridLp(if (idx > 0) dp(2) else 0, if (idx < 4) dp(2) else 0))
        }
        card.addView(maxRow)
        if (cfg.popupMaxSimultaneous > 0) {
            card.addView(makeSliderRow("Limite exacte simultanés", 1, 150, cfg.popupMaxSimultaneous, " pop-ups") { v ->
                gazeConfig.update { putInt("popup_max_simul_v6", v) }
            })
        }

        // ---- DURATION (0.1s..60s or ∞ Persistent) ----
        val durLblText = if (cfg.popupDurationMs == 0) "∞ PERSISTANT" else formatMs(cfg.popupDurationMs)
        card.addView(smallLabel("⏱️ Durée par pop-up ($durLblText) :"))
        val duRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(3)) }
        listOf(300 to "0,3 s", 1000 to "1 s", 3000 to "3 s").forEachIndexed { idx, (ms, lbl) ->
            duRow1.addView(makeChip(lbl, cfg.popupDurationMs == ms) {
                gazeConfig.update { putInt("popup_dur_ms_v6", ms) }; refreshUi()
            }, gridLp(if (idx > 0) dp(3) else 0, if (idx < 2) dp(3) else 0))
        }
        card.addView(duRow1)
        val duRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(2)) }
        listOf(10_000 to "10 s", 30_000 to "30 s", 0 to "∞ Persistant").forEachIndexed { idx, (ms, lbl) ->
            duRow2.addView(makeChip(lbl, cfg.popupDurationMs == ms) {
                gazeConfig.update { putInt("popup_dur_ms_v6", ms) }; refreshUi()
            }, gridLp(if (idx > 0) dp(3) else 0, if (idx < 2) dp(3) else 0))
        }
        card.addView(duRow2)
        if (cfg.popupDurationMs > 0) {
            card.addView(makeMsLogSliderRow("Durée exacte (0,1 s → 60 s)", 100, 60_000, cfg.popupDurationMs) { ms ->
                gazeConfig.update { putInt("popup_dur_ms_v6", ms) }
            })
        }

        // ---- TITAN POP-UP UPGRADES (#22..#28) ----
        card.addView(smallLabel("🔥 Effets & Armes Tempête TITAN (#22–#28) :"))
        card.addView(makeSwitchRow("🌪️ #22 Physique Dérive & Rebond 60 FPS sur les bords", cfg.popupDriftPhysicsEnabled) {
            gazeConfig.update { putBoolean("v7_popup_drift", it) }
        })
        card.addView(makeSwitchRow("💓 #23 Pulsation Respiratoire continue des images", cfg.popupPulseScaleEnabled) {
            gazeConfig.update { putBoolean("v7_popup_pulse", it) }
        })
        card.addView(makeSwitchRow("🎯 #24 Pop-Ups Esquiveurs (35 % esquivent le 1er clic !)", cfg.popupEvasiveDodgeEnabled) {
            gazeConfig.update { putBoolean("v7_popup_dodge", it) }
        })
        card.addView(makeSwitchRow("⚡ #25 Déferlantes SURGE (×3 vitesse pendant 6 s / 25 s !)", cfg.popupSurgeWavesEnabled) {
            gazeConfig.update { putBoolean("v7_popup_surge", it) }
        })
        card.addView(makeSwitchRow("🌀 #26 Spirale Hypnotique 60 FPS sous les pop-ups", cfg.popupSpiralOverlayEnabled) {
            gazeConfig.update { putBoolean("v7_popup_spiral", it) }
        })
        card.addView(makeSwitchRow("🎧 #27 Audio Binaural Hypnotique en mode Pop-Ups", cfg.popupBinauralEnabled) {
            gazeConfig.update { putBoolean("v7_popup_binaural", it) }
        })
        card.addView(makeSwitchRow("📷 #28 Boost Regard IA (détourne les yeux = ×2 vitesse !)", cfg.popupGazeBoostEnabled) {
            gazeConfig.update { putBoolean("v7_popup_gaze_boost", it) }
        })

        // ---- OPACITY, ZONES, ANIMS, FRAMES, TOUCH ----
        card.addView(makeSliderRow("Opacité des pop-ups", 15, 100, cfg.popupOpacityPct, " %") { v ->
            gazeConfig.update { putInt("popup_opacity_v51", v) }
        })
        card.addView(makeSwitchRow("🎲 Opacité aléatoire par image", cfg.popupRandomOpacity) {
            gazeConfig.update { putBoolean("popup_rand_op_v6", it) }
        })

        card.addView(smallLabel("🗺️ Zone d'apparition :"))
        val zoneRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(4)) }
        PopupSpawnZone.values().forEachIndexed { idx, z ->
            zoneRow.addView(makeChip(z.label, cfg.popupSpawnZone == z) {
                gazeConfig.update { putString("popup_zone_v6", z.id) }; refreshUi()
            }, gridLp(if (idx > 0) dp(2) else 0, if (idx < 3) dp(2) else 0))
        }
        card.addView(zoneRow)

        card.addView(smallLabel("🎬 Animation d'entrée :"))
        val animVals = PopupAnimStyle.values()
        val animRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(3)) }
        for (i in 0 until 3) {
            val a = animVals[i]
            animRow1.addView(makeChip(a.label, cfg.popupAnimStyle == a) {
                gazeConfig.update { putString("popup_anim_v6", a.id) }; refreshUi()
            }, gridLp(if (i > 0) dp(3) else 0, if (i < 2) dp(3) else 0))
        }
        card.addView(animRow1)
        val animRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(4)) }
        for (i in 3 until animVals.size) {
            val a = animVals[i]
            animRow2.addView(makeChip(a.label, cfg.popupAnimStyle == a) {
                gazeConfig.update { putString("popup_anim_v6", a.id) }; refreshUi()
            }, gridLp(if (i > 3) dp(3) else 0, if (i < animVals.size - 1) dp(3) else 0))
        }
        card.addView(animRow2)

        card.addView(smallLabel("🖼️ Style de cadre :"))
        val frameVals = PopupFrameStyle.values()
        val frameRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(3)) }
        for (i in 0 until 3) {
            val f = frameVals[i]
            frameRow1.addView(makeChip(f.label, cfg.popupFrameStyle == f) {
                gazeConfig.update { putString("popup_frame_v6", f.id) }; refreshUi()
            }, gridLp(if (i > 0) dp(3) else 0, if (i < 2) dp(3) else 0))
        }
        card.addView(frameRow1)
        val frameRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(4)) }
        for (i in 3 until frameVals.size) {
            val f = frameVals[i]
            frameRow2.addView(makeChip(f.label, cfg.popupFrameStyle == f) {
                gazeConfig.update { putString("popup_frame_v6", f.id) }; refreshUi()
            }, gridLp(if (i > 3) dp(3) else 0, if (i < frameVals.size - 1) dp(3) else 0))
        }
        card.addView(frameRow2)

        card.addView(smallLabel("👆 Comportement tactile :"))
        val tmRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(4)) }
        PopupTouchMode.values().forEachIndexed { idx, tm ->
            tmRow.addView(makeChip(tm.label, cfg.popupTouchMode == tm) {
                gazeConfig.update { putString("popup_touch_mode_v51", tm.id) }; refreshUi()
            }, gridLp(if (idx > 0) dp(3) else 0, if (idx < 2) dp(3) else 0))
        }
        card.addView(tmRow)
        if (cfg.popupTouchMode == PopupTouchMode.TAP_DISMISS) {
            val tapRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(4)) }
            listOf(1 to "1 clic", 2 to "2 clics", 3 to "3 clics").forEachIndexed { idx, (t, lbl) ->
                tapRow.addView(makeChip(lbl, cfg.popupTapsToClose == t) {
                    gazeConfig.update { putInt("popup_taps_close_v51", t) }; refreshUi()
                }, gridLp(if (idx > 0) dp(4) else 0, if (idx < 2) dp(4) else 0))
            }
            card.addView(tapRow)
            card.addView(makeSwitchRow("💨 Balayer (Swipe) pour éjecter un pop-up", cfg.popupSwipeDismiss) {
                gazeConfig.update { putBoolean("popup_swipe_dismiss_v6", it) }
            })
            card.addView(makeSwitchRow("🧬 Mitose : un pop-up ignoré se divise en 2 !", cfg.popupMitosisEnabled) {
                gazeConfig.update { putBoolean("popup_mitosis_v51", it) }
            })
        }

        card.addView(makeSwitchRow("🌊 Rampe d'Accélération (vitesse ×4 sur 3 min !)", cfg.popupRampUpEnabled) {
            gazeConfig.update { putBoolean("popup_ramp_up_v6", it) }
        })
        card.addView(makeSwitchRow("🔀 Inclinaison aléatoire (±13°)", cfg.popupRandomRotation) {
            gazeConfig.update { putBoolean("popup_rotation_v51", it) }
        })
        card.addView(makeSwitchRow("✨ Mantra Néon sur chaque image", cfg.popupShowMantra) {
            gazeConfig.update { putBoolean("popup_mantra_v51", it) }
        })
        card.addView(makeSwitchRow("📳 Vibration sur chaque salve", cfg.popupVibrate) {
            gazeConfig.update { putBoolean("popup_vibrate_v51", it) }
        })
        card.addView(makeSwitchRow("🔊 Son Pavlov sur salve & fermeture", cfg.popupSoundOnSpawn) {
            gazeConfig.update { putBoolean("popup_sound_spawn_v6", it) }
        })
        card.addView(makeSwitchRow("🔒 Verrouiller la sortie (Portail $portalMin min + Anti-Évasion + Survie Reboot)", cfg.popupLockExitWithPortal) {
            gazeConfig.update { putBoolean("popup_lock_exit_v51", it) }; refreshUi()
        })

        card.addView(smallLabel("⏳ Durée de la session Pop-Ups :"))
        val sdRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(3)) }
        listOf(0 to "∞ Infini", 60 to "1 min", 180 to "3 min", 300 to "5 min").forEachIndexed { idx, (sec, lbl) ->
            sdRow1.addView(makeChip(lbl, cfg.popupSessionDurationSec == sec) {
                gazeConfig.update { putInt("popup_sess_dur_v51", sec) }; refreshUi()
            }, gridLp(if (idx > 0) dp(3) else 0, if (idx < 3) dp(3) else 0))
        }
        card.addView(sdRow1)
        val sdRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(6)) }
        listOf(900 to "15 min", 1800 to "30 min", 3600 to "60 min").forEachIndexed { idx, (sec, lbl) ->
            sdRow2.addView(makeChip(lbl, cfg.popupSessionDurationSec == sec) {
                gazeConfig.update { putInt("popup_sess_dur_v51", sec) }; refreshUi()
            }, gridLp(if (idx > 0) dp(3) else 0, if (idx < 2) dp(3) else 0))
        }
        card.addView(sdRow2)
        return card
    }

    private fun refreshPopupFolderCountAsync(cfg: GazeConfigSnapshot) {
        val tv = popupFolderCountTv ?: return
        Thread {
            val imgs = VideoFeedRepository.countImagesForPopupMode(applicationContext, cfg)
            val srcLabel = when {
                cfg.popupFolderPath.isNotBlank() -> cfg.popupFolderPath
                cfg.popupTreeUri.isNotBlank() -> "Dossier Images dédié (SAF)"
                cfg.folderPath.isNotBlank() -> "Dossier principal : ${cfg.folderPath}"
                cfg.treeUri.isNotBlank() -> "Dossier principal (SAF)"
                else -> "Aucun dossier choisi"
            }
            val countStr = if (imgs > 0) "🖼️ $imgs image(s) prête(s)" else "🎞️ extraction auto depuis vidéos si 0 photo"
            mainHandler.post { tv.text = "$srcLabel\n→ $countStr" }
        }.start()
    }

    private fun refreshVideoCountAsync(cfg: GazeConfigSnapshot) {
        val tv = videoCountTv ?: return
        Thread {
            val (vids, imgs) = VideoFeedRepository.countMediaInConfig(applicationContext, cfg)
            val imgNote = if (imgs > 0) "🖼️ $imgs image(s) (spam, subliminal & 18 mini-jeux)" else "🎞️ frames vidéo auto (18 mini-jeux)"
            val text = when {
                cfg.folderPath.isNotBlank() -> "${cfg.folderPath}\n→ 🎬 $vids vidéo(s) • $imgNote"
                cfg.treeUri.isNotBlank() -> "Dossier SAF\n→ 🎬 $vids vidéo(s) • $imgNote"
                else -> "Aucun dossier choisi — sélectionne ton dossier de vidéos ci-dessous !"
            }
            mainHandler.post { tv.text = text }
        }.start()
    }

    private fun formatMs(ms: Int): String = when {
        ms < 1000 -> "${ms} ms"
        ms % 1000 == 0 -> "${ms / 1000} s"
        ms < 10_000 -> String.format("%.2f s", ms / 1000.0).replace('.', ',')
        else -> String.format("%.1f s", ms / 1000.0).replace('.', ',')
    }

    private fun formatRatePerSec(ms: Int): String {
        val r = 1000.0 / ms.coerceAtLeast(30)
        return if (r >= 1.0) String.format("%.1f/s", r).replace(".0", "") else String.format("1/%ds", (ms / 1000.0).roundToInt().coerceAtLeast(1))
    }

    private fun makeMsLogSliderRow(title: String, minMs: Int, maxMs: Int, initialMs: Int, onChange: (Int) -> Unit): View {
        val steps = 200
        val lnMin = ln(minMs.toDouble())
        val lnMax = ln(maxMs.toDouble())
        fun progressToMs(p: Int): Int {
            val frac = p.coerceIn(0, steps).toDouble() / steps.toDouble()
            val raw = exp(lnMin + frac * (lnMax - lnMin)).roundToInt().coerceIn(minMs, maxMs)
            return when {
                raw < 100 -> ((raw + 2) / 5) * 5
                raw < 1000 -> ((raw + 12) / 25) * 25
                raw < 10_000 -> ((raw + 50) / 100) * 100
                else -> ((raw + 250) / 500) * 500
            }.coerceIn(minMs, maxMs)
        }
        fun msToProgress(ms: Int): Int {
            val clamped = ms.coerceIn(minMs, maxMs).toDouble()
            return (((ln(clamped) - lnMin) / (lnMax - lnMin)) * steps).roundToInt().coerceIn(0, steps)
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        val tv = TextView(this).apply { text = "$title : ${formatMs(initialMs)}"; setTextColor(Color.rgb(216, 216, 232)); textSize = 11.4f }
        val sb = SeekBar(this).apply {
            max = steps; progress = msToProgress(initialMs)
            thumbTintList = ColorStateList.valueOf(Color.rgb(236, 72, 153))
            progressTintList = ColorStateList.valueOf(Color.rgb(236, 72, 153))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = progressToMs(p); tv.text = "$title : ${formatMs(v)}"; if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(tv); box.addView(sb); return box
    }

    /** #39 Protected Switch: blocks disabling critical security settings while Config Lock is active! */
    private fun makeFortressSwitchRow(cfg: GazeConfigSnapshot, label: String, initial: Boolean, onChange: (Boolean) -> Unit): Switch {
        return Switch(this).apply {
            text = label; isChecked = initial; setTextColor(Color.rgb(235, 235, 248)); textSize = 11.6f
            setPadding(0, dp(5), 0, dp(5))
            setOnCheckedChangeListener { btn, checked ->
                if (!checked && cfg.isConfigLockedNow()) {
                    btn.isChecked = true
                    val remMin = ((cfg.configLockUntilMs - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1L)
                    Toast.makeText(this@MainActivity, "🔒 Verrou Forteresse actif encore $remMin min !", Toast.LENGTH_SHORT).show()
                } else {
                    onChange(checked)
                }
            }
        }
    }

    private fun openAccessibilitySettings() { try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Throwable) {} }
    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Throwable) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
        }
    }
    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Throwable) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 303)
    }
    private fun formatDuration(sec: Int) = if (sec % 60 == 0) "${sec / 60} min" else String.format("%d:%02d", sec / 60, sec % 60)

    private fun makeCard(accentStroke: Int = Color.argb(40, 255, 255, 255)) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(CARD_DARK, 18, strokeColor = accentStroke, strokeDp = 1)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
    }
    private fun sectionTitle(t: String) = TextView(this).apply { text = t; setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD }
    private fun sectionSub(t: String) = TextView(this).apply { text = t; setTextColor(Color.rgb(168, 168, 192)); textSize = 11.2f; setPadding(0, dp(3), 0, dp(6)) }
    private fun smallLabel(t: String) = TextView(this).apply { text = t; setTextColor(Color.rgb(212, 212, 232)); textSize = 11.4f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(8), 0, dp(4)) }
    private fun makeSwitchRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label; isChecked = initial; setTextColor(Color.rgb(232, 232, 245)); textSize = 11.6f
        setPadding(0, dp(5), 0, dp(5)); setOnCheckedChangeListener { _, c -> onChange(c) }
    }
    private fun makeSliderRow(title: String, min: Int, max: Int, initial: Int, unit: String, onChange: (Int) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(5), 0, dp(4)) }
        val tv = TextView(this).apply { text = "$title : $initial$unit"; setTextColor(Color.rgb(216, 216, 232)); textSize = 11.4f }
        val sb = SeekBar(this).apply {
            this.max = (max - min).coerceAtLeast(1); progress = (initial - min).coerceIn(0, this.max)
            thumbTintList = ColorStateList.valueOf(Color.rgb(225, 29, 72))
            progressTintList = ColorStateList.valueOf(Color.rgb(225, 29, 72))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + p; tv.text = "$title : $v$unit"; if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(tv); box.addView(sb); return box
    }
    private fun makeChip(label: String, active: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 10.4f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (active) Color.WHITE else Color.rgb(195, 195, 215))
        background = roundedBg(if (active) Color.rgb(225, 29, 72) else Color.rgb(30, 25, 44), 11, strokeColor = if (active) Color.rgb(253, 164, 175) else Color.argb(38, 255, 255, 255), strokeDp = 1)
        minHeight = 0; minimumHeight = 0; setPadding(dp(4), dp(8), dp(4), dp(8)); setOnClickListener { onClick() }
    }
    private fun makePermChip(label: String, ok: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = if (ok) "✅ $label" else "⚠️ $label"; isAllCaps = false; textSize = 11.2f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (ok) Color.rgb(167, 243, 208) else Color.rgb(254, 240, 138))
        background = roundedBg(if (ok) Color.argb(42, 16, 185, 129) else Color.argb(45, 245, 158, 11), 11, strokeColor = if (ok) Color.argb(120, 16, 185, 129) else Color.argb(120, 245, 158, 11), strokeDp = 1)
        minHeight = 0; minimumHeight = 0; setPadding(dp(6), dp(9), dp(6), dp(9)); setOnClickListener { onClick() }
    }
    private fun makeAccentButton(label: String, bg: Int, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 11.8f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        background = roundedBg(bg, 12); setPadding(dp(10), dp(11), dp(10), dp(11)); minHeight = 0; minimumHeight = 0; setOnClickListener { onClick() }
    }
    private fun makeSecondaryBtn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 11.2f; setTextColor(Color.rgb(225, 225, 240))
        background = roundedBg(Color.rgb(30, 26, 46), 11, strokeColor = Color.argb(42, 255, 255, 255), strokeDp = 1)
        setPadding(dp(10), dp(9), dp(10), dp(9)); minHeight = 0; minimumHeight = 0
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        setOnClickListener { onClick() }
    }
    private fun gridLp(startMargin: Int = 0, endMargin: Int = 0) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = startMargin; marginEnd = endMargin }
    private fun roundedBg(fill: Int, radiusDp: Int, strokeColor: Int = Color.TRANSPARENT, strokeDp: Int = 0) =
        GradientDrawable().apply { cornerRadius = dp(radiusDp).toFloat(); setColor(fill); if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor) }

    companion object {
        private val BG_DARK = Color.rgb(9, 7, 18)
        private val CARD_DARK = Color.rgb(20, 16, 32)
    }
}
