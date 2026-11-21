package com.blurshield.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.blurshield.app.config.AppFilterMode
import com.blurshield.app.config.BlurConfigSnapshot
import com.blurshield.app.config.BlurSettings
import com.blurshield.app.config.BlurStyle
import com.blurshield.app.config.EmojiPack
import com.blurshield.app.config.FaceGenderMode
import com.blurshield.app.config.FaceSubZone
import com.blurshield.app.config.MaskShape
import com.blurshield.app.config.SensitivityMode
import com.blurshield.app.config.SmoothnessMode
import com.blurshield.app.service.BlurAccessibilityService
import com.blurshield.app.service.TurboCaptureService
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File
import java.io.FileOutputStream

/**
 * BlurShield AI v2.0 Pure Native Kotlin Pro Control Dashboard:
 * - Features #1 (Silhouette Segmentation), #2 (Kalman 0-Lag Tracking), #3 (Face Sub-Zones),
 *   #4 (3D Head Euler Tracking), #5 (Skin-Tone Refinement), #9 (AGSL GPU Shaders 120Hz),
 *   #10 (Reactive Emoji & Custom Gallery Image), plus Edge Feathering, Opacity & 35Hz Turbo Video.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: BlurSettings
    private lateinit var previewView: BlurPreviewView
    private lateinit var statusTitle: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var statusBadge: TextView
    private lateinit var a11yBanner: LinearLayout
    private lateinit var turboButton: Button
    private lateinit var masterSwitch: MaterialSwitch
    private lateinit var appsButton: Button
    private lateinit var emojiPackBox: View
    private lateinit var customImageBox: LinearLayout
    private lateinit var pickImageButton: Button

    private var updatingUi = false

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            TurboCaptureService.start(this, res.data!!)
        }
        refreshStatusBanner()
    }

    private val pickCustomImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            savePickedMaskImage(uri)
        }
    }

    private val serviceListener: () -> Unit = {
        runOnUiThread { refreshStatusBanner() }
    }

    private val settingsListener: (BlurConfigSnapshot) -> Unit = { snap ->
        runOnUiThread {
            previewView.setConfig(snap)
            refreshStatusBanner()
            refreshContextualStylePanels(snap)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = BlurSettings(this)
        setContentView(buildRootLayout())
        BlurAccessibilityService.addStateListener(serviceListener)
        BlurSettings.addListener(settingsListener)
    }

    override fun onResume() {
        super.onResume()
        val snap = settings.snapshot()
        refreshStatusBanner()
        refreshContextualStylePanels(snap)
        previewView.setConfig(snap)
    }

    override fun onDestroy() {
        BlurAccessibilityService.removeStateListener(serviceListener)
        BlurSettings.removeListener(settingsListener)
        super.onDestroy()
    }

    private fun savePickedMaskImage(uri: Uri) {
        try {
            val raw = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            if (raw == null) {
                Toast.makeText(this, "Impossible de lire cette image", Toast.LENGTH_SHORT).show()
                return
            }
            val maxDim = 512f
            val scale = (maxDim / maxOf(raw.width, raw.height).coerceAtLeast(1)).coerceAtMost(1f)
            val finalBmp = if (scale < 0.99f) {
                val scaled = Bitmap.createScaledBitmap(
                    raw,
                    (raw.width * scale).toInt().coerceAtLeast(32),
                    (raw.height * scale).toInt().coerceAtLeast(32),
                    true
                )
                if (scaled !== raw) raw.recycle()
                scaled
            } else raw

            val outFile = File(filesDir, "custom_mask_image.png")
            FileOutputStream(outFile).use { fos ->
                finalBmp.compress(Bitmap.CompressFormat.PNG, 95, fos)
            }
            if (!finalBmp.isRecycled) finalBmp.recycle()

            settings.update {
                putString("custom_img_path", outFile.absolutePath)
                putString("blur_style", BlurStyle.CUSTOM_IMAGE.id)
            }
            Toast.makeText(this, "Image de masquage enregistrée", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            Toast.makeText(this, "Erreur lors de l'import de l'image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun cardDrawable(strokeColor: Int = Color.argb(26, 255, 255, 255), bgColor: Int = Color.rgb(19, 19, 24)): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(bgColor)
            setStroke(dp(1), strokeColor)
        }
    }

    private fun pillDrawable(active: Boolean, accentColor: Int = Color.rgb(16, 185, 129)): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            if (active) {
                setColor(Color.argb(52, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)))
                setStroke(dp(1), accentColor)
            } else {
                setColor(Color.rgb(11, 11, 14))
                setStroke(dp(1), Color.argb(25, 255, 255, 255))
            }
        }
    }

    private fun buildRootLayout(): View {
        val snap = settings.snapshot()
        val scroll = NestedScrollView(this).apply {
            setBackgroundColor(Color.rgb(9, 9, 11))
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(36))
        }

        // ==================== HEADER & MASTER CONTROL ====================
        val heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(Color.argb(70, 16, 185, 129), Color.rgb(16, 21, 22))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusTitle = TextView(this).apply {
            text = "BlurShield AI v2.0"
            setTextColor(Color.WHITE)
            textSize = 18.5f
            typeface = Typeface.DEFAULT_BOLD
        }
        statusSubtitle = TextView(this).apply {
            text = "Silhouette IA • Kalman 120Hz • Shaders GPU • 100 % local"
            setTextColor(Color.rgb(161, 161, 170))
            textSize = 11.5f
        }
        titleCol.addView(statusTitle)
        titleCol.addView(statusSubtitle)

        masterSwitch = MaterialSwitch(this).apply {
            isChecked = snap.enabled
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.rgb(16, 185, 129), Color.rgb(63, 63, 70))
            )
            setOnCheckedChangeListener { _, checked ->
                if (!updatingUi) settings.enabled = checked
            }
        }
        headerRow.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(masterSwitch)
        heroCard.addView(headerRow)

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        statusBadge = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.rgb(110, 231, 183))
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = pillDrawable(true)
        }
        turboButton = Button(this).apply {
            text = "Mode Turbo Vidéo 35Hz"
            isAllCaps = false
            textSize = 11.5f
            setTextColor(Color.rgb(224, 242, 254))
            background = pillDrawable(false, Color.rgb(56, 189, 248))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            minHeight = 0
            minimumHeight = 0
            setOnClickListener {
                if (TurboCaptureService.isRunning) {
                    TurboCaptureService.stop(this@MainActivity)
                } else {
                    if (!BlurAccessibilityService.isServiceActive()) {
                        openAccessibilitySettings()
                        return@setOnClickListener
                    }
                    try {
                        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        modeRow.addView(statusBadge)
        modeRow.addView(View(this), LinearLayout.LayoutParams(dp(8), 1, 1f))
        modeRow.addView(turboButton)
        heroCard.addView(modeRow)
        root.addView(heroCard, marginBottom(12))

        // ==================== ACCESSIBILITY SETUP BANNER ====================
        a11yBanner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(Color.argb(120, 245, 158, 11), Color.rgb(34, 25, 14))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        a11yBanner.addView(TextView(this).apply {
            text = "Service d’accessibilité requis (1 seule fois)"
            setTextColor(Color.rgb(254, 243, 199))
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
        })
        a11yBanner.addView(TextView(this).apply {
            text = "Activez « BlurShield AI » dans Accessibilité pour dessiner le flou au-dessus de toutes les applis sans aucune popup."
            setTextColor(Color.rgb(212, 212, 216))
            textSize = 11.5f
            setPadding(0, dp(4), 0, dp(10))
        })
        val a11yBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val openA11yBtn = Button(this).apply {
            text = "Activer Accessibilité"
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.rgb(9, 9, 11))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.rgb(251, 191, 36))
            }
            setOnClickListener { openAccessibilitySettings() }
        }
        val restrictedBtn = Button(this).apply {
            text = "Android 13+ (⋮ Restreints)"
            isAllCaps = false
            textSize = 11.5f
            setTextColor(Color.rgb(253, 230, 138))
            background = pillDrawable(false, Color.rgb(251, 191, 36))
            setOnClickListener { openAppDetailsSettings() }
        }
        a11yBtns.addView(openA11yBtn, LinearLayout.LayoutParams(0, dp(40), 1.15f).apply { marginEnd = dp(8) })
        a11yBtns.addView(restrictedBtn, LinearLayout.LayoutParams(0, dp(40), 1f))
        a11yBanner.addView(a11yBtns)
        root.addView(a11yBanner, marginBottom(12))

        // ==================== LIVE ANIMATED VISUAL PREVIEW ====================
        val previewCard = createSectionCard("Aperçu temps réel 60 FPS (Contours, Shaders & 3D)")
        previewView = BlurPreviewView(this).apply { setConfig(snap) }
        previewCard.addView(previewView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(164)))
        root.addView(previewCard, marginBottom(12))

        // ==================== 1. CONTOURS IA, VISAGES & NSFW (#1, #3, #4, #5) ====================
        val zonesCard = createSectionCard("Contours IA Ultra-Précis & Zones (#1, #3, #4, #5)")
        zonesCard.addView(createToggleRow("Visages (133 points Bézier IA)", snap.blurFaces) { settings.blurFaces = it })
        zonesCard.addView(
            createChipSelector(
                label = "Filtre genre visages",
                options = FaceGenderMode.values().map { it to it.label },
                initial = snap.faceGenderMode
            ) { settings.faceGenderMode = it }
        )
        zonesCard.addView(
            createChipSelector(
                label = "Sous-zone chirurgicale du visage (#3)",
                options = FaceSubZone.values().map { it to it.label },
                initial = snap.faceSubZone,
                accentColor = Color.rgb(56, 189, 248)
            ) { settings.faceSubZone = it }
        )
        zonesCard.addView(createToggleRow("Suivi 3D angle de tête Euler X/Y/Z (#4)", snap.headPose3dTracking) { settings.headPose3dTracking = it })
        zonesCard.addView(createDivider())
        zonesCard.addView(createToggleRow("Détourage Silhouette IA au pixel près (#1)", snap.silhouetteSegmentation) { settings.silhouetteSegmentation = it })
        zonesCard.addView(createToggleRow("Flouter toute la silhouette corps entier (#1)", snap.blurWholeBodySilhouette) { settings.blurWholeBodySilhouette = it })
        zonesCard.addView(createDivider())
        zonesCard.addView(createToggleRow("Zones intimes explicites", snap.blurGenitalia) { settings.blurGenitalia = it })
        zonesCard.addView(createToggleRow("Poitrine / Torse exposé", snap.blurBreasts) { settings.blurBreasts = it })
        zonesCard.addView(createToggleRow("Fesses exposées", snap.blurButtocks) { settings.blurButtocks = it })
        zonesCard.addView(createToggleRow("Ventre / Nombril exposé", snap.blurBelly) { settings.blurBelly = it })
        zonesCard.addView(createToggleRow("Mode strict (maillots / zones couvertes)", snap.blurCoveredSensitive) { settings.blurCoveredSensitive = it })
        zonesCard.addView(createToggleRow("Affinage adaptatif couleur de peau NSFW (#5)", snap.skinToneRefinement) { settings.skinToneRefinement = it })
        root.addView(zonesCard, marginBottom(12))

        // ==================== 2. 12 STYLES, SHADERS GPU & REMPLACEMENT (#9 & #10) ====================
        val gpuTag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "AGSL 120Hz natif" else "Mode GPU natif"
        val styleCard = createSectionCard("12 Styles, Shaders GPU ($gpuTag) & Remplacement (#9, #10)")

        val styleSelectorRef = createChipSelector(
            label = "Style de masquage (Classique • GPU Shaders • Emoji / Image)",
            options = BlurStyle.values().map { it to it.label },
            initial = snap.blurStyle,
            perRowOverride = 3,
            accentColor = Color.rgb(168, 85, 247)
        ) { settings.blurStyle = it }
        styleCard.addView(styleSelectorRef)

        // Contextual Emoji Pack selector (#10)
        emojiPackBox = createChipSelector(
            label = "Pack Emoji dynamique (#10 — suit sourire & clin d'œil)",
            options = EmojiPack.values().map { it to it.label },
            initial = snap.emojiPack,
            perRowOverride = 3,
            accentColor = Color.rgb(251, 191, 36)
        ) { settings.emojiPack = it }
        styleCard.addView(emojiPackBox)

        // Contextual Custom Gallery Image picker (#10)
        customImageBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        pickImageButton = Button(this).apply {
            isAllCaps = false
            textSize = 12.5f
            setTextColor(Color.WHITE)
            background = pillDrawable(true, Color.rgb(168, 85, 247))
            setOnClickListener {
                try {
                    pickCustomImageLauncher.launch(arrayOf("image/*"))
                } catch (_: Throwable) {
                }
            }
        }
        customImageBox.addView(pickImageButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        styleCard.addView(customImageBox)

        styleCard.addView(createDivider())
        styleCard.addView(
            createChipSelector(
                label = "Forme du masque",
                options = MaskShape.values().map { it to it.label },
                initial = snap.maskShape,
                perRowOverride = 2
            ) { settings.maskShape = it }
        )
        root.addView(styleCard, marginBottom(12))

        // ==================== 3. RÉGLAGES CHIRURGICAUX & KALMAN 0-LAG (#2) ====================
        val kalmanCard = createSectionCard("Fluidité Extrême 120Hz, Kalman 0-Lag (#2) & Finition")
        kalmanCard.addView(
            createSliderRow("Intensité du flou / shader / mosaïque", snap.blurIntensity, 1, 10, "") {
                settings.blurIntensity = it
            }
        )
        kalmanCard.addView(
            createSliderRow("Marge de sécurité autour de la zone", snap.maskPaddingPct, 0, 45, " %") {
                settings.maskPaddingPct = it
            }
        )
        kalmanCard.addView(
            createSliderRow("Fondu progressif des bords (Feathering)", snap.edgeFeatherPct, 0, 100, " %") {
                settings.edgeFeatherPct = it
            }
        )
        kalmanCard.addView(
            createSliderRow("Opacité du masque", snap.maskOpacityPct, 35, 100, " %") {
                settings.maskOpacityPct = it
            }
        )
        kalmanCard.addView(createDivider())
        kalmanCard.addView(
            createChipSelector(
                label = "Profil de suivi & fluidité",
                options = SmoothnessMode.values().map { it to it.label },
                initial = snap.smoothnessMode,
                perRowOverride = 2,
                accentColor = Color.rgb(56, 189, 248)
            ) { settings.smoothnessMode = it }
        )
        kalmanCard.addView(
            createToggleRow("Suivi prédictif Kalman + vecteur vitesse (#2)", snap.kalmanPrediction) {
                settings.kalmanPrediction = it
            }
        )
        kalmanCard.addView(
            createSliderRow("Anticipation prédictive Kalman (0-Lag vidéo)", snap.kalmanLeadMs, 0, 140, " ms") {
                settings.kalmanLeadMs = it
            }
        )
        root.addView(kalmanCard, marginBottom(12))

        // ==================== 4. ZONE D'ÉCRAN (ROI) & SENSIBILITÉ ====================
        val roiCard = createSectionCard("Zone d’écran active (ROI) & Sensibilité IA")
        roiCard.addView(
            createChipSelector(
                label = "Sensibilité de détection",
                options = SensitivityMode.values().map { it to it.label },
                initial = snap.sensitivityMode
            ) { settings.sensitivityMode = it }
        )
        roiCard.addView(
            createSliderRow("Limite haute d’écran ignorée", snap.roiTopPct, 0, 40, " %") {
                settings.roiTopPct = it
            }
        )
        roiCard.addView(
            createSliderRow("Limite basse d’écran active", snap.roiBottomPct, 60, 100, " %") {
                settings.roiBottomPct = it
            }
        )
        roiCard.addView(
            createToggleRow("Exclure barre d’état & navigation", snap.excludeSystemBars) {
                settings.excludeSystemBars = it
            }
        )
        root.addView(roiCard, marginBottom(12))

        // ==================== 5. APPLICATIONS CIBLÉES ====================
        val appsCard = createSectionCard("Applications ciblées / exclues")
        appsCard.addView(
            createChipSelector(
                label = "Mode de filtrage",
                options = AppFilterMode.values().map { it to it.label },
                initial = snap.appFilterMode
            ) {
                settings.appFilterMode = it
                refreshAppsButtonLabel()
            }
        )
        appsButton = Button(this).apply {
            isAllCaps = false
            textSize = 12.5f
            setTextColor(Color.WHITE)
            background = pillDrawable(false, Color.rgb(56, 189, 248))
            setOnClickListener {
                val cur = settings.snapshot()
                val dialogTitle = if (cur.appFilterMode == AppFilterMode.ONLY_SELECTED) {
                    "Applications où activer le flou"
                } else {
                    "Applications à exclure du flou"
                }
                AppPickerDialog.show(this@MainActivity, dialogTitle, cur.selectedPackages) { newSet ->
                    settings.selectedPackages = newSet
                    refreshAppsButtonLabel()
                }
            }
        }
        refreshAppsButtonLabel()
        appsCard.addView(appsButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
            topMargin = dp(8)
        })
        root.addView(appsCard, marginBottom(12))

        // ==================== 6. CONFORT & PERFORMANCE ====================
        val perfCard = createSectionCard("Confort, contrôle rapide & batterie")
        perfCard.addView(
            createToggleRow("Pastille flottante Pause / Reprise (1 tap)", snap.showFloatingPill) {
                settings.showFloatingPill = it
            }
        )
        perfCard.addView(
            createToggleRow("Afficher le compteur FPS & Kalman HUD", snap.showPerfHud) {
                settings.showPerfHud = it
            }
        )
        perfCard.addView(
            createToggleRow("Éco-batterie intelligent sur écran immobile", snap.ecoStaticSkip) {
                settings.ecoStaticSkip = it
            }
        )
        perfCard.addView(TextView(this).apply {
            text = "Astuce : active le Mode Turbo Vidéo 35Hz + Kalman 0-Lag pour une fluidité quasi parfaite sur TikTok, Reels et YouTube."
            setTextColor(Color.rgb(113, 113, 122))
            textSize = 11f
            setPadding(0, dp(6), 0, 0)
        })
        root.addView(perfCard, marginBottom(16))

        scroll.addView(root)
        refreshContextualStylePanels(snap)
        refreshStatusBanner()
        return scroll
    }

    private fun refreshContextualStylePanels(snap: BlurConfigSnapshot) {
        emojiPackBox.visibility = if (snap.blurStyle == BlurStyle.EMOJI_REACTIVE) View.VISIBLE else View.GONE
        customImageBox.visibility = if (snap.blurStyle == BlurStyle.CUSTOM_IMAGE) View.VISIBLE else View.GONE
        val hasImg = snap.customMaskImagePath.isNotBlank() && File(snap.customMaskImagePath).exists()
        pickImageButton.text = if (hasImg) {
            "Changer l'image perso (Galerie) ✓"
        } else {
            "Choisir une image perso dans la Galerie"
        }
    }

    private fun createSectionCard(title: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable()
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 13.8f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(10))
            })
        }
    }

    private fun createDivider(): View {
        return View(this).apply {
            setBackgroundColor(Color.argb(18, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
        }
    }

    private fun createToggleRow(label: String, initial: Boolean, onChanged: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val tv = TextView(this).apply {
            text = label
            setTextColor(Color.rgb(228, 228, 231))
            textSize = 12.8f
        }
        val sw = MaterialSwitch(this).apply {
            isChecked = initial
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.rgb(16, 185, 129), Color.rgb(63, 63, 70))
            )
            setOnCheckedChangeListener { _, checked -> onChanged(checked) }
        }
        row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sw)
        return row
    }

    private fun <T> createChipSelector(
        label: String,
        options: List<Pair<T, String>>,
        initial: T,
        perRowOverride: Int? = null,
        accentColor: Int = Color.rgb(16, 185, 129),
        onSelected: (T) -> Unit
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        box.addView(TextView(this).apply {
            text = label
            setTextColor(Color.rgb(161, 161, 170))
            textSize = 11.5f
            setPadding(0, 0, 0, dp(6))
        })

        var current = initial
        val chipViews = ArrayList<Pair<T, TextView>>()
        var currentRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        box.addView(currentRow)

        fun refreshChips() {
            for ((value, tv) in chipViews) {
                val active = value == current
                tv.background = pillDrawable(active, accentColor)
                tv.setTextColor(if (active) Color.WHITE else Color.rgb(161, 161, 170))
                tv.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }

        val perRow = perRowOverride ?: if (options.size <= 3) options.size else 3
        options.forEachIndexed { index, (value, title) ->
            if (index > 0 && index % perRow == 0) {
                currentRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(6), 0, 0)
                }
                box.addView(currentRow)
            }
            val tv = TextView(this).apply {
                text = title
                textSize = 11.2f
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), dp(8))
                setOnClickListener {
                    current = value
                    refreshChips()
                    onSelected(value)
                }
            }
            chipViews.add(value to tv)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index % perRow != perRow - 1 && index != options.lastIndex) marginEnd = dp(6)
            }
            currentRow.addView(tv, lp)
        }
        refreshChips()
        return box
    }

    private fun createSliderRow(
        label: String,
        initial: Int,
        minVal: Int,
        maxVal: Int,
        suffix: String,
        onChanged: (Int) -> Unit
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lbl = TextView(this).apply {
            text = label
            setTextColor(Color.rgb(212, 212, 216))
            textSize = 12.5f
        }
        val valTv = TextView(this).apply {
            text = "$initial$suffix"
            setTextColor(Color.rgb(52, 211, 153))
            textSize = 12.5f
            typeface = Typeface.MONOSPACE
        }
        top.addView(lbl, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(valTv)
        box.addView(top)

        val seek = SeekBar(this).apply {
            max = maxVal - minVal
            progress = (initial - minVal).coerceIn(0, maxVal - minVal)
            progressTintList = ColorStateList.valueOf(Color.rgb(16, 185, 129))
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val actual = minVal + progress
                    valTv.text = "$actual$suffix"
                    if (fromUser) onChanged(actual)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        box.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return box
    }

    private fun marginBottom(v: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(v)
        }
    }

    private fun refreshAppsButtonLabel() {
        val snap = settings.snapshot()
        val count = snap.selectedPackages.size
        appsButton.text = if (snap.appFilterMode == AppFilterMode.ONLY_SELECTED) {
            "Choisir les applications ciblées ($count sélectionnée${if (count > 1) "s" else ""})"
        } else {
            "Choisir les applications exclues ($count exclue${if (count > 1) "s" else ""})"
        }
    }

    private fun refreshStatusBanner() {
        val snap = settings.snapshot()
        val a11yActive = BlurAccessibilityService.isServiceActive()
        val turbo = TurboCaptureService.isRunning

        updatingUi = true
        masterSwitch.isChecked = snap.enabled
        updatingUi = false

        a11yBanner.visibility = if (a11yActive) View.GONE else View.VISIBLE

        val lat = BlurAccessibilityService.lastInferenceMs
        statusBadge.text = when {
            !a11yActive -> "Accessibilité inactive"
            !snap.enabled -> "En pause"
            turbo -> "Turbo 35Hz • ${lat}ms"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> "Autonome Actif • ${lat}ms"
            else -> "Accessibilité active (Turbo requis)"
        }
        statusBadge.background = pillDrawable(a11yActive && snap.enabled)
        statusBadge.setTextColor(
            if (a11yActive && snap.enabled) Color.rgb(110, 231, 183) else Color.rgb(253, 230, 138)
        )

        turboButton.text = if (turbo) "Arrêter Turbo 35Hz" else "Mode Turbo Vidéo 35Hz"
        turboButton.background = pillDrawable(turbo, Color.rgb(56, 189, 248))
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Throwable) {
        }
    }

    private fun openAppDetailsSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        } catch (_: Throwable) {
        }
    }
}
