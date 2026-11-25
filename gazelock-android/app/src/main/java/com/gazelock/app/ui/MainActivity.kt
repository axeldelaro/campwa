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
import com.gazelock.app.config.PopupTouchMode
import com.gazelock.app.config.StrictnessPreset
import com.gazelock.app.media.VideoFeedRepository
import com.gazelock.app.overlay.AccessCodeWaitSpamView
import com.gazelock.app.service.GazeAccessibilityService
import com.gazelock.app.service.GazeSessionService
import com.gazelock.app.service.ImagePopupStormService

/**
 * GazeLock AI v5.0 APOCALYPSE EDITION:
 * - Includes ALL v1..v4 features + 9 new v5.0 extremes:
 *   #11 Anti-Blink Streak (+15s), #21 Trapped Apps Ambush (with full App Picker!),
 *   #29 Block Quick Settings/Shade in Ghost Mode, #30 App-Switch Tax (>3/min -> +10s & +5% opacity),
 *   #36 Pavlovian Audio Conditioning, #51 4x4 Hell Sliding Puzzle (15 tiles),
 *   #54 Flash Target Hunt at 9 img/s (111ms), #70 Daily Escalating Portal (3m->4m->5m->6m),
 *   and #76 Final 30s Zero-Fault Boss (100% locked, fail = +60s)!
 */
class MainActivity : AppCompatActivity() {

    private lateinit var gazeConfig: GazeConfig
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var rootFrame: FrameLayout
    private lateinit var contentBox: LinearLayout
    private lateinit var spamWaitHost: FrameLayout

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
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        contentBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(18), dp(15), dp(36))
        }
        scroll.addView(contentBox)

        spamWaitHost = FrameLayout(this).apply { visibility = View.GONE; isClickable = true; isFocusable = true }
        rootFrame.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
            calibToggleBtn?.text = "📷 Tester le Suivi Regard + Iris IA en direct"
            calibStatusTv?.text = "Appuie ci-dessous pour tester en direct tes angles de tête et tes pupilles (Iris)."
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
                    val detail = if (r.faceDetected) {
                        val irisTxt = if (r.irisMeasured) " • 👁️ Iris G/D : ${r.irisOffsetPct} %" else " • 👁️ Iris : calibrage…"
                        "\nYaw : ${r.yawDeg.toInt()}° • Pitch : ${r.pitchDeg.toInt()}°$irisTxt • Yeux : ${(r.eyeOpenAvg * 100).toInt()} %"
                    } else "\nAucun visage détecté devant la caméra frontale."
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

    /** #21 Interactive Installed Launchable Apps Picker for App-Trap Ambushes! */
    private fun showTrappedAppsPickerDialog(cfg: GazeConfigSnapshot) {
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            packageManager.queryIntentActivities(intent, 0)
        } catch (_: Throwable) { emptyList() }

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
            Toast.makeText(this, "Aucune appli détectée — utilise les présélections par défaut.", Toast.LENGTH_SHORT).show()
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
            .setTitle("✨ Mantras Néon & Voix TTS (1 par ligne)")
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
            } else {
                ImagePopupStormService.stop(applicationContext)
            }
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

    private fun refreshUi() {
        val cfg = gazeConfig.snapshot()
        val lockedVideo = GazeSessionService.isSessionRunning
        val lockedPopups = ImagePopupStormService.isRunning && ImagePopupStormService.isExitLocked
        val lockedNow = lockedVideo || lockedPopups
        val portalMin = cfg.currentPortalWaitSec() / 60
        contentBox.removeAllViews()

        // Header
        val hdr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(12)) }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(this).apply {
            text = "👁️ GazeLock AI v5.1"
            setTextColor(Color.WHITE); textSize = 22f; typeface = Typeface.DEFAULT_BOLD
        })
        titleCol.addView(TextView(this).apply {
            text = "Vidéo Ghost · Mode Pop-Ups Images · Boss 30s · 7 Mini-Jeux"
            setTextColor(Color.rgb(253, 224, 71)); textSize = 10.5f
        })
        hdr.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val a11yOk = GazeAccessibilityService.isServiceActive()
        hdr.addView(TextView(this).apply {
            text = if (a11yOk) "⚡ A11Y ACTIF" else "⚠️ A11Y OFF"
            setTextColor(if (a11yOk) Color.rgb(167, 243, 208) else Color.rgb(253, 224, 71))
            textSize = 10.6f; typeface = Typeface.DEFAULT_BOLD
            background = roundedBg(if (a11yOk) Color.argb(45, 16, 185, 129) else Color.argb(45, 245, 158, 11), 12)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener { if (!lockedNow) openAccessibilitySettings() }
        })
        contentBox.addView(hdr)

        // ==================== ACTIVE SESSION LOCKDOWN ====================
        if (lockedNow) {
            val activeCard = makeCard(accentStroke = Color.rgb(225, 29, 72))
            activeCard.addView(sectionTitle(
                if (lockedPopups) "🔒 POP-UPS D'IMAGES VERROUILLÉS EN COURS"
                else "🔒 SESSION APOCALYPSE EN COURS — VIDÉOS VERROUILLÉES"
            ))
            activeCard.addView(sectionSub(
                if (lockedPopups) "Le mode Pop-Ups d'Images tourne en mode verrouillé. Seul le portail imposé de $portalMin min en overlay permet d'arrêter les pop-ups !"
                else "Les vidéos tournent en overlay. Tous les réglages sont scellés.\nSeul le portail imposé de $portalMin min en overlay (spam 0,1 s → Sprint 20 FPS + pop-ups + 7 mini-jeux) permet de retirer les vidéos !"
            ))
            activeCard.addView(makeAccentButton("⏳ OUVRIR LE PORTAIL $portalMin MIN EN OVERLAY", Color.rgb(162, 28, 175)) {
                if (lockedPopups) ImagePopupStormService.openUnlockPortal()
                else GazeSessionService.openUnlockPortalOverlay()
            }.apply { textSize = 13.5f; setPadding(dp(14), dp(14), dp(14), dp(14)) })
            contentBox.addView(activeCard)
            return
        }

        // ==================== 0. STANDALONE IMAGE POP-UPS MODE (OUTSIDE VIDEOS!) ====================
        contentBox.addView(buildStandaloneImagePopupCard(cfg, portalMin))
        refreshPopupFolderCountAsync(cfg)

        // ==================== 1. OVERLAY MODE, DEBT & LAUNCH ====================
        val launchCard = makeCard(accentStroke = Color.argb(215, 225, 29, 72))
        launchCard.addView(sectionTitle("👻 Mode d'Affichage Overlay"))
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(4)) }
        OverlayMode.values().forEachIndexed { idx, m ->
            modeRow.addView(makeChip(m.label, cfg.overlayMode == m) {
                gazeConfig.update { putString("overlay_mode_v3", m.id) }; refreshUi()
            }, gridLp(if (idx > 0) dp(4) else 0, if (idx < 1) dp(4) else 0))
        }
        launchCard.addView(modeRow)
        launchCard.addView(TextView(this).apply {
            text = "→ ${cfg.overlayMode.desc}"
            setTextColor(Color.rgb(216, 180, 254)); textSize = 11.1f; setPadding(0, 0, 0, dp(6))
        })

        if (cfg.overlayMode == OverlayMode.GHOST_PASSTHROUGH) {
            launchCard.addView(smallLabel("Opacité de départ (tactile 100 % déverrouillé) :"))
            val opRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(4)) }
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
                text = "💸 DETTE DE TEMPS EN ATTENTE : +${formatDuration(cfg.pendingTimeDebtSec)} (ajoutée d'office à la prochaine session — soldée en finissant jusqu'à 00:00 !)"
                setTextColor(Color.rgb(253, 224, 71)); textSize = 11.3f; typeface = Typeface.DEFAULT_BOLD
                background = roundedBg(Color.argb(65, 245, 158, 11), 10, strokeColor = Color.rgb(245, 158, 11), strokeDp = 1)
                setPadding(dp(10), dp(7), dp(10), dp(7))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(4) }
            })
        }

        val durHeader = if (cfg.infiniteUntilUnlock) "🎬 Durée : ∞ Infini (Portail $portalMin min seul)" else "🎬 Durée : ${formatDuration(cfg.durationSec)}"
        launchCard.addView(smallLabel(durHeader))

        val durRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(5)) }
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

        val ctaLabel = if (cfg.overlayMode == OverlayMode.GHOST_PASSTHROUGH) {
            val d = if (cfg.infiniteUntilUnlock) "∞" else formatDuration(cfg.durationSec)
            "👻🎬 LANCER OVERLAY GHOST (${cfg.ghostOpacityPct}% · $d)"
        } else {
            val d = if (cfg.infiniteUntilUnlock) "∞" else formatDuration(cfg.durationSec)
            "🔒🎬 LANCER PLEIN ÉCRAN VERROUILLÉ ($d)"
        }
        launchCard.addView(makeAccentButton(ctaLabel, Color.rgb(225, 29, 72)) { launchGazeLockSession(false) }.apply {
            textSize = 13.8f; setPadding(dp(12), dp(14), dp(12), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        })
        launchCard.addView(makeAccentButton("🎲 ROULETTE CHAOS v5 (Règles Secrètes + Boss Final 30s !)", Color.rgb(126, 34, 206)) {
            launchGazeLockSession(true)
        }.apply {
            textSize = 12f; setPadding(dp(12), dp(11), dp(12), dp(11))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) }
        })
        contentBox.addView(launchCard)

        // ==================== 2. NOUVEAUTÉS v5.0 (#11, #21, #29, #30, #36, #76) ====================
        val v5Card = makeCard(accentStroke = Color.rgb(250, 204, 21))
        v5Card.addView(sectionTitle("👑 Arsenal v5.0 (#11 · #21 · #29 · #30 · #36 · #76)"))
        v5Card.addView(makeSwitchRow("👑 #76 Boss Final (30 s à 100 % Zéro Faute — 1 écart = +60 s !)", cfg.finalBossEnabled) {
            gazeConfig.update { putBoolean("final_boss_v5", it) }
        })
        v5Card.addView(makeSwitchRow("👁️ #11 Défi Anti-Clignement (ne cligne pas → échec = +15 s & +8 % opacité)", cfg.noBlinkChallengeEnabled) {
            gazeConfig.update { putBoolean("no_blink_en_v5", it) }
        })
        v5Card.addView(makeSliderRow("Durée Défi Anti-Clignement", 8, 20, cfg.noBlinkDurationSec, " s") { v ->
            gazeConfig.update { putInt("no_blink_sec_v5", v) }
        })
        v5Card.addView(makeSwitchRow("🚫 #29 Bloquer Panneau Rapide / Volet Notifs même en mode Ghost", cfg.blockShadeInGhostEnabled) {
            gazeConfig.update { putBoolean("block_shade_ghost_v5", it) }
        })
        v5Card.addView(makeSwitchRow("🔀 #30 Taxe Changement d'Appli (> 3 applis/min = +10 s & +5 % opacité)", cfg.appSwitchTaxEnabled) {
            gazeConfig.update { putBoolean("app_switch_tax_v5", it) }
        })
        v5Card.addView(makeSwitchRow("🔔 #36 Conditionnement Sonore Pavlovien (carillon 30 s / buzzer faute)", cfg.pavlovSoundEnabled) {
            gazeConfig.update { putBoolean("pavlov_sound_v5", it) }
        })
        v5Card.addView(makeSwitchRow("🪤 #21 Embuscades sur Applis Piégées (ouvre une appli → overlay immédiat !)", cfg.appTrapEnabled) {
            gazeConfig.update { putBoolean("app_trap_en_v5", it) }
        })
        val trappedCount = cfg.trappedPackageSet().size
        v5Card.addView(makeSecondaryBtn("📱 #21 Choisir les applis piégées ($trappedCount sélectionnée(s))") {
            showTrappedAppsPickerDialog(cfg)
        })
        v5Card.addView(makeSliderRow("Durée de la session sur appli piégée", 1, 10, (cfg.appTrapDurationSec / 60).coerceIn(1, 10), " min") { m ->
            gazeConfig.update { putInt("app_trap_dur_v5", m * 60) }
        })
        contentBox.addView(v5Card)

        // ==================== 3. PORTAIL OVERLAY (#51 4×4, #54 9 IMG/S, #70 PROGRESSIF, #17 SURCHARGE, #22 REGARD) ====================
        val codeCard = makeCard(accentStroke = Color.argb(195, 192, 38, 211))
        codeCard.addView(sectionTitle("🧩 Portail Overlay ($portalMin min aujourd'hui · #51 · #54 · #70 · #17)"))
        val dayNote = if (cfg.dailyEscalatingPortalEnabled) {
            "Durée actuelle aujourd'hui : $portalMin min (déblocage #${cfg.portalUsesToday + 1} · escalade 3m → 4m → 5m → 6m) !"
        } else "Durée fixe : 3 min."
        codeCard.addView(sectionSub("$dayNote\n7 mini-jeux visuels avec les images du dossier (sans dictée) : Taquin 4×4 Enfer, Chasse 9 img/s, Échange 3×3, Rotation 3×3, Memory 4×3, Séquence 1→9 & Intrus !"))
        codeCard.addView(makeSwitchRow("🔥 #51 Taquin 4×4 Mode Enfer (15 tuiles + aide optimale)", cfg.hellPuzzle4x4Enabled) {
            gazeConfig.update { putBoolean("hell_4x4_v5", it) }
        })
        codeCard.addView(makeSwitchRow("⚡ #54 Chasse au Motif Éclair à 9 images/seconde (111 ms)", cfg.flashHunt9FpsEnabled) {
            gazeConfig.update { putBoolean("flash_hunt_9fps_v5", it) }
        })
        codeCard.addView(makeSwitchRow("📈 #70 Portail Progressif Journalier (3 min → 4 → 5 → 6 min)", cfg.dailyEscalatingPortalEnabled) {
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
        codeCard.addView(makeAccentButton("⏳ Tester / Lancer le Portail ($portalMin min · 7 Mini-Jeux)", Color.rgb(162, 28, 175)) {
            launchThreeMinuteCodeWaitScreen()
        })
        contentBox.addView(codeCard)

        // ==================== 4. EMPRISE GHOST (#1, #2, #5, #9, #18) & ULTRA (#21, #23, #24, #25, #26) ====================
        val extremeCard = makeCard(accentStroke = Color.argb(185, 236, 72, 153))
        extremeCard.addView(sectionTitle("🔥 Emprise Ghost & Arsenal Ultra (#1·#2·#5·#9·#18·#21·#23–#26)"))
        extremeCard.addView(makeSwitchRow("📈 #1 Emprise Progressive (Opacité monte 48% → 96%)", cfg.livingOpacityEnabled) {
            gazeConfig.update { putBoolean("living_opacity_v4", it) }
        })
        extremeCard.addView(makeSwitchRow("⚡ #2 Coupures d'Emprise (passe à 96% tactile bloqué)", cfg.attentionHijackEnabled) {
            gazeConfig.update { putBoolean("hijack_en_v4", it) }
        })
        extremeCard.addView(makeSliderRow("Fréquence Coupures d'Emprise (toutes les)", 1, 8, cfg.attentionHijackIntervalMin, " min") { v ->
            gazeConfig.update { putInt("hijack_int_min_v4", v) }
        })
        extremeCard.addView(makeSliderRow("Regard continu exigé pour libérer le tactile", 8, 25, cfg.attentionHijackFocusSec, " s") { v ->
            gazeConfig.update { putInt("hijack_focus_sec_v4", v) }
        })
        extremeCard.addView(makeSwitchRow("🖼️ #5 Micro-Pop-Ups Fantômes pendant l'usage (ignorer = +6 %)", cfg.ghostMicroPopupsEnabled) {
            gazeConfig.update { putBoolean("ghost_popups_en_v4", it) }
        })
        extremeCard.addView(makeSliderRow("Intervalle Micro-Pop-Ups", 20, 90, cfg.ghostMicroPopupIntervalSec, " s") { v ->
            gazeConfig.update { putInt("ghost_popups_int_v4", v) }
        })
        extremeCard.addView(makeSwitchRow("🚫 #9 Zones Mortes Tactiles Punitives sur faute", cfg.deadZonesEnabled) {
            gazeConfig.update { putBoolean("dead_zones_en_v4", it) }
        })
        extremeCard.addView(makeSwitchRow("💸 #18 Dette de Temps sur sortie anticipée", cfg.timeDebtEnabled) {
            gazeConfig.update { putBoolean("time_debt_en_v4", it) }
        })
        val debtRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(4)) }
        listOf(50 to "Dette 50%", 75 to "Dette 75%", 100 to "Dette 100%").forEachIndexed { idx, (pct, lbl) ->
            debtRow.addView(makeChip(lbl, cfg.timeDebtCarryPct == pct) {
                gazeConfig.update { putInt("time_debt_pct_v4", pct) }; refreshUi()
            }, gridLp(if (idx > 0) dp(4) else 0, if (idx < 2) dp(4) else 0))
        }
        extremeCard.addView(debtRow)
        extremeCard.addView(makeSwitchRow("🌀 Spirale Hypnotique 60 FPS synchronisée", cfg.hypnoticSpiralEnabled) {
            gazeConfig.update { putBoolean("spiral_en_v4", it) }
        })
        extremeCard.addView(makeSliderRow("Opacité Spirale", 12, 55, cfg.spiralOpacityPct, " %") { v ->
            gazeConfig.update { putInt("spiral_opacity_v4", v) }
        })
        extremeCard.addView(makeSwitchRow("🎙️ Chuchotements TTS Graves de tes mantras", cfg.ttsMantraWhisperEnabled) {
            gazeConfig.update { putBoolean("tts_whisper_v4", it) }
        })
        extremeCard.addView(makeSwitchRow("🙈 Chrono Aveugle (??:??)", cfg.blindTimerEnabled) {
            gazeConfig.update { putBoolean("blind_timer_v4", it) }
        })
        extremeCard.addView(makeSwitchRow("📐 Anti-Pose Accéléromètre (table > 6 s → 90 %)", cfg.antiFlatSensorEnabled) {
            gazeConfig.update { putBoolean("anti_flat_v4", it) }
        })
        extremeCard.addView(makeSwitchRow("🔁 Mode Immortel (+45 s après reboot / déverrouillage)", cfg.rebootSurvivalEnabled) {
            gazeConfig.update { putBoolean("reboot_survival_v4", it) }
        })
        contentBox.addView(extremeCard)

        // ==================== 5. #7 SUBLIMINAL & #8 AUDIO / BRIGHTNESS ====================
        val sensoryCard = makeCard(accentStroke = Color.argb(165, 14, 165, 233))
        sensoryCard.addView(sectionTitle("⚡ #7 Subliminal & 🔊 #8 Audio Absolu / Binaural / Luminosité"))
        sensoryCard.addView(makeSwitchRow("✨ #7 Flashs Subliminaux (0,08–0,22 s) + Mantras Néon", cfg.subliminalEnabled) {
            gazeConfig.update { putBoolean("subliminal_en_v3", it) }
        })
        sensoryCard.addView(makeSliderRow("Intervalle Flashs Subliminaux", 2, 20, cfg.subliminalIntervalSec, " s") { v ->
            gazeConfig.update { putInt("subliminal_int_v3", v) }
        })
        sensoryCard.addView(makeSliderRow("Durée Flash Subliminal", 80, 220, cfg.subliminalDurationMs, " ms") { v ->
            gazeConfig.update { putInt("subliminal_dur_v3", v) }
        })
        sensoryCard.addView(makeSecondaryBtn("✏️ Éditer mantras néon & voix TTS (${cfg.mantraList().size} phrases)") { showEditMantrasDialog(cfg) })
        sensoryCard.addView(makeSwitchRow("🔒 #8 Verrou Volume Absolu + Blocage Touches Volume", cfg.volumeLockEnabled) {
            gazeConfig.update { putBoolean("volume_lock", it) }
        })
        sensoryCard.addView(makeSliderRow("Plancher Volume Média", 40, 100, cfg.volumeFloorPct, " %") { v ->
            gazeConfig.update { putInt("volume_floor_v3", v) }
        })
        sensoryCard.addView(makeSwitchRow("☀️ #8 Luminosité Overlay forcée à 100 %", cfg.forceMaxBrightness) {
            gazeConfig.update { putBoolean("force_brightness_v3", it) }
        })
        sensoryCard.addView(makeSwitchRow("🎧 #8 Battement Binaural Hypnotique (118 BPM en Sprint/Boss)", cfg.binauralBeatEnabled) {
            gazeConfig.update { putBoolean("binaural_en_v3", it) }
        })
        sensoryCard.addView(makeSliderRow("Intensité Binaurale", 10, 80, cfg.binauralVolumePct, " %") { v ->
            gazeConfig.update { putInt("binaural_vol_v3", v) }
        })
        sensoryCard.addView(makeSliderRow("Fréquence Binaurale (4–12 Hz)", 4, 12, cfg.binauralFreqHz, " Hz") { v ->
            gazeConfig.update { putInt("binaural_hz_v3", v) }
        })
        contentBox.addView(sensoryCard)

        // ==================== 6. VIDEO & IMAGE FOLDER ====================
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
        contentBox.addView(folderCard)
        refreshVideoCountAsync(cfg)

        // ==================== 7. AI CALIBRATION, IRIS, PENALTIES & ANTI-ESCAPE ====================
        val rulesCard = makeCard(accentStroke = Color.argb(150, 16, 185, 129))
        rulesCard.addView(sectionTitle("👁️ IA Regard/Iris · Sanctions · Anti-Évasion"))
        val cTv = TextView(this).apply {
            text = "Teste en direct tes angles de tête et tes pupilles (Iris)."
            setTextColor(Color.rgb(190, 190, 212)); textSize = 11.2f
            background = roundedBg(Color.rgb(24, 20, 38), 11); setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        calibStatusTv = cTv
        rulesCard.addView(cTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6); bottomMargin = dp(6) })
        val cBtn = makeAccentButton(
            if (calibrationActive) "⏹️ Arrêter le Test Caméra IA" else "📷 Tester Regard + Iris IA en direct",
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
        rulesCard.addView(makeSwitchRow("🚨 Anti-Évasion (Paramètres / Désinstallation / Force-Stop +15 s)", cfg.antiEscapeLockEnabled) {
            gazeConfig.update { putBoolean("anti_escape_v2", it) }
        })
        rulesCard.addView(makeSwitchRow("⏸️ Geler vidéo si regard détourné", cfg.pauseVideoOnLookAway) { gazeConfig.update { putBoolean("pause_video_away", it) } })
        rulesCard.addView(makeSwitchRow("🙈 Exiger yeux ouverts", cfg.requireEyesOpen) { gazeConfig.update { putBoolean("eyes_open", it) } })
        rulesCard.addView(makeSwitchRow("🗣️ Alertes vocales + 📳 Vibrations", cfg.voiceAlertEnabled) {
            gazeConfig.update { putBoolean("voice_alert", it); putBoolean("vibrate_alert", it) }
        })
        contentBox.addView(rulesCard)

        // ==================== 8. AUTO-AMBUSH & PERMISSIONS / STATS ====================
        val autoCard = makeCard(accentStroke = Color.argb(120, 14, 165, 233))
        autoCard.addView(sectionTitle("⏰ Embuscades Auto & 🛡️ Autorisations"))
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
            text = "\n📊 Sessions : ${cfg.sessionsCompleted} • Fixé : $totalMin min ${cfg.totalFocusedSec % 60L} s\n" +
                "👀 Écarts : ${cfg.totalLookAways} • 🚨 Évasions bloquées : ${cfg.escapeBlocksCount} • 🔥 Record : ${cfg.bestStreakSec} s"
            setTextColor(Color.rgb(210, 210, 232)); textSize = 11.4f
        })
        contentBox.addView(autoCard)
    }

    private fun buildStandaloneImagePopupCard(cfg: GazeConfigSnapshot, portalMin: Int): LinearLayout {
        val popRunning = ImagePopupStormService.isRunning
        val card = makeCard(accentStroke = if (popRunning) Color.rgb(16, 185, 129) else Color.rgb(236, 72, 153))
        card.addView(sectionTitle("🖼️ Mode Pop-Ups d'Images (Hors Vidéos)"))
        card.addView(sectionSub("Affiche des pop-ups d'images du dossier par-dessus toutes tes applis sans aucune vidéo ! Taille, fréquence, durée, opacité, mode tactile et verrouillage entièrement réglables en direct."))

        val pCountTv = TextView(this).apply {
            text = "⏳ Analyse du dossier images…"
            setTextColor(Color.rgb(249, 168, 212)); textSize = 11.2f; typeface = Typeface.MONOSPACE
            background = roundedBg(Color.rgb(28, 18, 42), 10); setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        popupFolderCountTv = pCountTv
        card.addView(pCountTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(6) })

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

        // Size presets + slider (25..92%)
        card.addView(smallLabel("📐 Taille des pop-ups (${cfg.popupSizePct} % de la largeur écran) :"))
        val szRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(2)) }
        listOf(35 to "Petit 35%", 55 to "Moyen 55%", 75 to "Grand 75%", 90 to "Énorme 90%").forEachIndexed { idx, (pct, lbl) ->
            szRow.addView(makeChip(lbl, cfg.popupSizePct == pct) {
                gazeConfig.update { putInt("popup_size_pct_v51", pct) }; refreshUi()
            }, gridLp(if (idx > 0) dp(3) else 0, if (idx < 3) dp(3) else 0))
        }
        card.addView(szRow)
        card.addView(makeSliderRow("Taille précise", 25, 92, cfg.popupSizePct, " %") { v ->
            gazeConfig.update { putInt("popup_size_pct_v51", v) }
        })

        // Frequency presets + decimal slider (0.3s .. 30.0s)
        card.addView(smallLabel("⚡ Fréquence d'apparition (toutes les ${formatDeciSec(cfg.popupIntervalDeciSec)}) :"))
        val fqRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(2)) }
        listOf(5 to "0,5 s (Spam)", 15 to "1,5 s", 30 to "3 s", 80 to "8 s").forEachIndexed { idx, (ds, lbl) ->
            fqRow.addView(makeChip(lbl, cfg.popupIntervalDeciSec == ds) {
                gazeConfig.update { putInt("popup_int_ds_v51", ds) }; refreshUi()
            }, gridLp(if (idx > 0) dp(3) else 0, if (idx < 3) dp(3) else 0))
        }
        card.addView(fqRow)
        card.addView(makeDecimalSliderRow("Fréquence exacte", 3, 300, cfg.popupIntervalDeciSec) { ds ->
            gazeConfig.update { putInt("popup_int_ds_v51", ds) }
        })

        // Display duration per pop-up (0.4s .. 15.0s)
        card.addView(smallLabel("⏱️ Durée d'affichage par pop-up (${formatDeciSec(cfg.popupDurationDeciSec)}) :"))
        val duRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(2)) }
        listOf(10 to "1 s", 30 to "3 s", 60 to "6 s", 120 to "12 s").forEachIndexed { idx, (ds, lbl) ->
            duRow.addView(makeChip(lbl, cfg.popupDurationDeciSec == ds) {
                gazeConfig.update { putInt("popup_dur_ds_v51", ds) }; refreshUi()
            }, gridLp(if (idx > 0) dp(3) else 0, if (idx < 3) dp(3) else 0))
        }
        card.addView(duRow)
        card.addView(makeDecimalSliderRow("Durée exacte d'un pop-up", 4, 150, cfg.popupDurationDeciSec) { ds ->
            gazeConfig.update { putInt("popup_dur_ds_v51", ds) }
        })

        // Max simultaneous & Opacity
        card.addView(makeSliderRow("Nombre max simultanés à l'écran", 1, 8, cfg.popupMaxSimultaneous, " pop-ups") { v ->
            gazeConfig.update { putInt("popup_max_simul_v51", v) }
        })
        card.addView(makeSliderRow("Opacité des pop-ups", 35, 100, cfg.popupOpacityPct, " %") { v ->
            gazeConfig.update { putInt("popup_opacity_v51", v) }
        })

        // Touch mode selector
        card.addView(smallLabel("👆 Comportement tactile des pop-ups :"))
        val tmRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(4)) }
        PopupTouchMode.values().forEachIndexed { idx, tm ->
            tmRow.addView(makeChip(tm.label, cfg.popupTouchMode == tm) {
                gazeConfig.update { putString("popup_touch_mode_v51", tm.id) }; refreshUi()
            }, gridLp(if (idx > 0) dp(3) else 0, if (idx < 2) dp(3) else 0))
        }
        card.addView(tmRow)
        card.addView(TextView(this).apply {
            text = "→ ${cfg.popupTouchMode.desc}"
            setTextColor(Color.rgb(216, 180, 254)); textSize = 10.8f; setPadding(0, 0, 0, dp(4))
        })

        if (cfg.popupTouchMode == PopupTouchMode.TAP_DISMISS) {
            val tapRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(4)) }
            listOf(1 to "1 clic", 2 to "2 clics", 3 to "3 clics").forEachIndexed { idx, (t, lbl) ->
                tapRow.addView(makeChip(lbl, cfg.popupTapsToClose == t) {
                    gazeConfig.update { putInt("popup_taps_close_v51", t) }; refreshUi()
                }, gridLp(if (idx > 0) dp(4) else 0, if (idx < 2) dp(4) else 0))
            }
            card.addView(tapRow)
            card.addView(makeSwitchRow("🧬 Mitose : un pop-up ignoré se divise en 2 pop-ups !", cfg.popupMitosisEnabled) {
                gazeConfig.update { putBoolean("popup_mitosis_v51", it) }
            })
        }

        card.addView(makeSwitchRow("🔀 Inclinaison aléatoire des photos (±13°)", cfg.popupRandomRotation) {
            gazeConfig.update { putBoolean("popup_rotation_v51", it) }
        })
        card.addView(makeSwitchRow("✨ Afficher un Mantra Néon sur chaque image", cfg.popupShowMantra) {
            gazeConfig.update { putBoolean("popup_mantra_v51", it) }
        })
        card.addView(makeSwitchRow("📳 Vibration subtile à chaque pop-up", cfg.popupVibrate) {
            gazeConfig.update { putBoolean("popup_vibrate_v51", it) }
        })
        card.addView(makeSwitchRow("🔒 Verrouiller la sortie (Portail $portalMin min + Anti-Évasion obligatoires)", cfg.popupLockExitWithPortal) {
            gazeConfig.update { putBoolean("popup_lock_exit_v51", it) }; refreshUi()
        })

        // Session duration chips
        card.addView(smallLabel("⏳ Durée de la session Pop-Ups :"))
        val sdRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(8)) }
        listOf(0 to "∞ Infini", 180 to "3 min", 300 to "5 min", 900 to "15 min").forEachIndexed { idx, (sec, lbl) ->
            sdRow.addView(makeChip(lbl, cfg.popupSessionDurationSec == sec) {
                gazeConfig.update { putInt("popup_sess_dur_v51", sec) }; refreshUi()
            }, gridLp(if (idx > 0) dp(3) else 0, if (idx < 3) dp(3) else 0))
        }
        card.addView(sdRow)

        if (popRunning) {
            card.addView(makeAccentButton("⏹️ ARRÊTER LES POP-UPS D'IMAGES", Color.rgb(225, 29, 72)) {
                ImagePopupStormService.stop(applicationContext)
            }.apply { textSize = 13.8f; setPadding(dp(12), dp(14), dp(12), dp(14)) })
        } else {
            val lockTag = if (cfg.popupLockExitWithPortal) " · 🔒 VERROUILLÉ" else ""
            card.addView(makeAccentButton("🖼️⚡ LANCER POP-UPS D'IMAGES (${cfg.popupSizePct}% · ${formatDeciSec(cfg.popupIntervalDeciSec)}$lockTag)", Color.rgb(219, 39, 119)) {
                launchImagePopupStormMode()
            }.apply { textSize = 13.2f; setPadding(dp(12), dp(14), dp(12), dp(14)) })
        }
        return card
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
        stopCalibrationPreview()
        ImagePopupStormService.start(applicationContext)
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

    private fun formatDeciSec(ds: Int): String =
        if (ds % 10 == 0) "${ds / 10} s" else String.format("%d,%d s", ds / 10, ds % 10)

    private fun makeDecimalSliderRow(title: String, minDs: Int, maxDs: Int, initialDs: Int, onChange: (Int) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        val tv = TextView(this).apply { text = "$title : ${formatDeciSec(initialDs)}"; setTextColor(Color.rgb(216, 216, 232)); textSize = 11.4f }
        val sb = SeekBar(this).apply {
            this.max = (maxDs - minDs).coerceAtLeast(1); progress = (initialDs - minDs).coerceIn(0, this.max)
            thumbTintList = ColorStateList.valueOf(Color.rgb(236, 72, 153))
            progressTintList = ColorStateList.valueOf(Color.rgb(236, 72, 153))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = minDs + p; tv.text = "$title : ${formatDeciSec(v)}"; if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(tv); box.addView(sb); return box
    }

    private fun refreshVideoCountAsync(cfg: GazeConfigSnapshot) {
        val tv = videoCountTv ?: return
        Thread {
            val (vids, imgs) = VideoFeedRepository.countMediaInConfig(applicationContext, cfg)
            val imgNote = if (imgs > 0) "🖼️ $imgs image(s) (spam, subliminal & 7 mini-jeux)" else "🎞️ frames vidéo auto (spam, subliminal & 7 mini-jeux)"
            val text = when {
                cfg.folderPath.isNotBlank() -> "${cfg.folderPath}\n→ 🎬 $vids vidéo(s) • $imgNote"
                cfg.treeUri.isNotBlank() -> "Dossier SAF\n→ 🎬 $vids vidéo(s) • $imgNote"
                else -> "Aucun dossier choisi — sélectionne ton dossier de vidéos ci-dessous !"
            }
            mainHandler.post { tv.text = text }
        }.start()
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
        text = label; isAllCaps = false; textSize = 10.6f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (active) Color.WHITE else Color.rgb(195, 195, 215))
        background = roundedBg(if (active) Color.rgb(225, 29, 72) else Color.rgb(30, 25, 44), 11, strokeColor = if (active) Color.rgb(253, 164, 175) else Color.argb(38, 255, 255, 255), strokeDp = 1)
        minHeight = 0; minimumHeight = 0; setPadding(dp(5), dp(8), dp(5), dp(8)); setOnClickListener { onClick() }
    }
    private fun makePermChip(label: String, ok: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = if (ok) "✅ $label" else "⚠️ $label"; isAllCaps = false; textSize = 11.2f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (ok) Color.rgb(167, 243, 208) else Color.rgb(254, 240, 138))
        background = roundedBg(if (ok) Color.argb(42, 16, 185, 129) else Color.argb(45, 245, 158, 11), 11, strokeColor = if (ok) Color.argb(120, 16, 185, 129) else Color.argb(120, 245, 158, 11), strokeDp = 1)
        minHeight = 0; minimumHeight = 0; setPadding(dp(6), dp(9), dp(6), dp(9)); setOnClickListener { onClick() }
    }
    private fun makeAccentButton(label: String, bg: Int, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12.1f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        background = roundedBg(bg, 12); setPadding(dp(12), dp(11), dp(12), dp(11)); minHeight = 0; minimumHeight = 0; setOnClickListener { onClick() }
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
