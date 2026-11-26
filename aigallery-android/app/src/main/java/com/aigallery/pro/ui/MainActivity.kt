package com.aigallery.pro.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aigallery.pro.ai.GalleryAiIndexer
import com.aigallery.pro.ai.VisualPreferenceAiEngine
import com.aigallery.pro.data.AlbumFolder
import com.aigallery.pro.data.GalleryDatabase
import com.aigallery.pro.data.GalleryPreferences
import com.aigallery.pro.data.GalleryTheme
import com.aigallery.pro.data.MediaFilter
import com.aigallery.pro.data.MediaItem
import com.aigallery.pro.data.MediaRepository
import com.aigallery.pro.data.MediaType
import com.aigallery.pro.data.PersonProfile
import com.aigallery.pro.data.SortOrder
import java.io.File
import java.util.Locale

/**
 * Simple Gallery AI Pro — Complete Standalone Android Gallery + Tinder AI + Person Recognition:
 * - Tab 0 (📂 Dossiers): Faithful Simple Gallery Pro Albums grid (pin, exclude, hidden .* & .nomedia, sort, 2..4 cols).
 * - Tab 1 (🖼️ Médias): Full timeline grid (Photos, Videos, GIFs, Favorites, search, 2..5 cols, multi-select batch ops).
 * - Tab 2 (🔥 Tinder IA): 60 FPS swipe stack learning visual tastes & per-person affinity in real time!
 * - Tab 3 (✨ Pour Toi): AI-predicted favorite probability ranking (0..99%) with transparent reasons & unseen filter!
 * - Tab 4 (👥 Personnes): Clustered eye-aligned face recognition profiles (Rename, Merge, VIP, Ignored, Filter media)!
 * - Plus Recycle Bin, Built-in Photo Editor, Slideshow Viewer, EXIF & Optional PIN Lock!
 */
class MainActivity : AppCompatActivity() {

    private data class UndoRecord(
        val item: MediaItem,
        val prevRating: Int,
        val prevFav: Boolean
    )

    private lateinit var db: GalleryDatabase
    private lateinit var prefs: GalleryPreferences
    private lateinit var indexer: GalleryAiIndexer
    private val mainHandler = Handler(Looper.getMainLooper())

    // State
    private var activeTab = 0 // 0=Dossiers, 1=Médias, 2=Tinder IA, 3=Pour Toi, 4=Personnes
    private var openAlbumPath: String? = null
    private var openAlbumTitle: String? = null
    private var openPersonId: Long? = null
    private var openPersonTitle: String? = null

    private var activeFilter = MediaFilter.ALL
    private var searchQuery = ""
    private var searchVisible = false
    private var forYouUnseenOnly = true

    private var tinderMode = VisualPreferenceAiEngine.TinderDeckMode.ACTIVE_DISCOVERY
    private var tinderFilterFolderPath: String? = null
    private var tinderFilterPersonId: Long? = null
    private var tinderFilterLabel: String? = null
    private val undoStack = ArrayDeque<UndoRecord>()

    private var allMedia: List<MediaItem> = emptyList()
    private var allAlbums: List<AlbumFolder> = emptyList()
    private var allPersons: List<PersonProfile> = emptyList()
    private var displayedMedia: List<MediaItem> = emptyList()
    private var pinUnlockedThisLaunch = false

    // Views
    private lateinit var rootFrame: FrameLayout
    private lateinit var mainContainer: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var backBtn: TextView
    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private lateinit var searchBox: EditText
    private lateinit var multiSelectBar: LinearLayout
    private lateinit var multiSelCountTv: TextView
    private lateinit var aiProgressRow: LinearLayout
    private lateinit var aiProgressTv: TextView
    private lateinit var aiProgressBar: ProgressBar
    private lateinit var filterStrip: LinearLayout
    private lateinit var contextBannerRow: LinearLayout
    private lateinit var contextBannerTv: TextView
    private lateinit var contextActionBtn: Button

    private lateinit var contentHost: FrameLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var tinderView: TinderSwipeView
    private lateinit var emptyInfoTv: TextView
    private lateinit var bottomNavBar: LinearLayout
    private lateinit var pinOverlay: LinearLayout

    private lateinit var albumAdapter: AlbumAdapter
    private lateinit var mediaAdapter: MediaGridAdapter
    private lateinit var peopleAdapter: PeopleAdapter

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = GalleryDatabase(applicationContext)
        prefs = GalleryPreferences(applicationContext)
        indexer = GalleryAiIndexer(applicationContext, db, prefs)

        buildRootLayout()
        setContentView(rootFrame)

        if (prefs.pinLockEnabled && prefs.pinCode.length >= 4 && !pinUnlockedThisLaunch) {
            showPinLockOverlay()
        }

        requestMediaPermissionsIfNeeded()
        reloadGalleryAsync(startAiIndex = true)
    }

    override fun onResume() {
        super.onResume()
        reloadGalleryAsync(startAiIndex = false)
    }

    override fun onPause() {
        tinderView.pauseVideo()
        super.onPause()
    }

    override fun onDestroy() {
        tinderView.stopVideo()
        indexer.stopAndRelease()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (pinOverlay.visibility == View.VISIBLE) {
            finish(); return
        }
        if (mediaAdapter.isSelectionMode()) {
            mediaAdapter.clearSelection()
            refreshSelectionBar()
            return
        }
        if (searchVisible) {
            toggleSearchBar(false); return
        }
        if (openAlbumPath != null || openPersonId != null) {
            openAlbumPath = null; openAlbumTitle = null
            openPersonId = null; openPersonTitle = null
            renderCurrentScreen()
            return
        }
        if (activeTab != 0) {
            switchTab(0); return
        }
        super.onBackPressed()
    }

    private fun hasBasicMediaPerm(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestMediaPermissionsIfNeeded() {
        val req = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) req.add(Manifest.permission.READ_MEDIA_IMAGES)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) req.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) req.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) req.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (req.isNotEmpty()) ActivityCompat.requestPermissions(this, req.toTypedArray(), 401)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        reloadGalleryAsync(startAiIndex = true)
    }

    // ==================== LAYOUT CONSTRUCTION ====================

    private fun buildRootLayout() {
        rootFrame = FrameLayout(this).apply { setBackgroundColor(prefs.bgColor()) }
        mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(prefs.bgColor())
        }

        // 1) Top Simple Gallery Pro Toolbar
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(prefs.surfaceColor())
            setPadding(dp(10), dp(34), dp(8), dp(10))
        }
        backBtn = TextView(this).apply {
            text = "←"; setTextColor(Color.WHITE); textSize = 20f; setPadding(dp(6), dp(4), dp(10), dp(4))
            visibility = View.GONE
            setOnClickListener {
                openAlbumPath = null; openAlbumTitle = null
                openPersonId = null; openPersonTitle = null
                renderCurrentScreen()
            }
        }
        topBar.addView(backBtn)

        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(4), 0) }
        titleTv = TextView(this).apply {
            text = "Simple Gallery AI Pro"; setTextColor(Color.WHITE); textSize = 17.5f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1
        }
        subTv = TextView(this).apply {
            text = "Chargement de ta galerie…"; setTextColor(Color.rgb(180, 180, 200)); textSize = 10.8f; maxLines = 1
        }
        titleCol.addView(titleTv); titleCol.addView(subTv)
        topBar.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        topBar.addView(makeToolbarIcon("🔍") { toggleSearchBar(!searchVisible) })
        topBar.addView(makeToolbarIcon("⇅") { showSortDialog() })
        topBar.addView(makeToolbarIcon("⊞") { cycleGridColumns() })
        topBar.addView(makeToolbarIcon("⋮") { showSettingsAndToolsDialog() })
        mainContainer.addView(topBar)

        // 2) Live Search Input (Hidden by default)
        searchBox = EditText(this).apply {
            hint = "Rechercher un dossier, fichier ou personne…"
            setTextColor(Color.WHITE); setHintTextColor(Color.rgb(145, 145, 165))
            setBackgroundColor(prefs.cardColor()); textSize = 13.5f
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString()?.trim().orEmpty()
                    renderCurrentScreen()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        mainContainer.addView(searchBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 3) Multi-Select Batch Action Bar (Simple Gallery Pro style!)
        multiSelectBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(prefs.accentColor)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = View.GONE
        }
        multiSelectBar.addView(makeToolbarIcon("✕") { mediaAdapter.clearSelection(); refreshSelectionBar() })
        multiSelCountTv = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setPadding(dp(6), 0, dp(6), 0) }
        multiSelectBar.addView(multiSelCountTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        multiSelectBar.addView(makeToolbarIcon("☑") { mediaAdapter.selectAll(); refreshSelectionBar() })
        multiSelectBar.addView(makeToolbarIcon("❤️") { batchFavoriteSelected() })
        multiSelectBar.addView(makeToolbarIcon("📤") { batchShareSelected() })
        multiSelectBar.addView(makeToolbarIcon("📂") { batchCopyOrMoveSelected() })
        multiSelectBar.addView(makeToolbarIcon("🗑️") { batchDeleteSelected() })
        mainContainer.addView(multiSelectBar)

        // 4) Non-intrusive On-Device AI & Face Indexing Bar
        aiProgressRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(26, 22, 42))
            setPadding(dp(12), dp(5), dp(12), dp(5))
            visibility = View.GONE
        }
        aiProgressTv = TextView(this).apply { setTextColor(Color.rgb(216, 180, 254)); textSize = 10.6f; typeface = Typeface.DEFAULT_BOLD }
        aiProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }
        aiProgressRow.addView(aiProgressTv)
        aiProgressRow.addView(aiProgressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(3) })
        mainContainer.addView(aiProgressRow)

        // 5) Media Filter Chips Strip (All / Photos / Videos / GIFs / Favorites)
        filterStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(4))
            visibility = View.GONE
        }
        mainContainer.addView(filterStrip)

        // 6) Context Banner (For You AI info OR "Swipe this Album / Person in Tinder!")
        contextBannerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(30, 24, 46))
            setPadding(dp(12), dp(7), dp(10), dp(7))
            visibility = View.GONE
        }
        contextBannerTv = TextView(this).apply { setTextColor(Color.rgb(236, 232, 252)); textSize = 11.4f }
        contextActionBtn = Button(this).apply {
            text = "🔥 Tinder"; isAllCaps = false; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            minHeight = 0; minimumHeight = 0; setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        contextBannerRow.addView(contextBannerTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        contextBannerRow.addView(contextActionBtn)
        mainContainer.addView(contextBannerRow)

        // 7) Main Content Host (RecyclerView OR TinderSwipeView)
        contentHost = FrameLayout(this)
        recyclerView = RecyclerView(this).apply {
            setPadding(dp(4), dp(4), dp(4), dp(10))
            clipToPadding = false
        }
        tinderView = TinderSwipeView(
            context = this,
            accentColor = prefs.accentColor,
            autoPlaySound = prefs.tinderAutoPlaySound,
            onSwiped = { item, rating -> handleTinderSwiped(item, rating) },
            onUndoRequested = { handleTinderUndo() },
            onOpenFullscreen = { item -> openMediaViewer(listOf(item), 0) },
            onTrashItem = { item -> handleTinderTrashItem(item) },
            onModeChanged = { m -> tinderMode = m; refreshTinderDeckOnly() },
            onClearFilter = {
                tinderFilterFolderPath = null; tinderFilterPersonId = null; tinderFilterLabel = null
                refreshTinderDeckOnly()
            }
        ).apply { visibility = View.GONE }

        emptyInfoTv = TextView(this).apply {
            text = ""; setTextColor(Color.rgb(175, 175, 198)); textSize = 13.5f; gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28)); visibility = View.GONE
        }

        contentHost.addView(recyclerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        contentHost.addView(tinderView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        contentHost.addView(emptyInfoTv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        mainContainer.addView(contentHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 8) 5-Tab Bottom Navigation Bar
        bottomNavBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setBackgroundColor(prefs.surfaceColor())
            setPadding(dp(4), dp(6), dp(4), dp(10))
            elevation = dp(8).toFloat()
        }
        rebuildBottomNav()
        mainContainer.addView(bottomNavBar)

        rootFrame.addView(mainContainer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 9) Optional PIN Lock Screen Overlay
        pinOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(12, 12, 18))
            setPadding(dp(24), dp(24), dp(24), dp(24))
            visibility = View.GONE; isClickable = true; isFocusable = true
        }
        rootFrame.addView(pinOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Adapters
        albumAdapter = AlbumAdapter(
            context = this,
            spanCount = prefs.folderColumns,
            accentColor = prefs.accentColor,
            onAlbumClick = { album ->
                openAlbumPath = album.path; openAlbumTitle = album.name
                openPersonId = null; openPersonTitle = null
                activeFilter = MediaFilter.ALL
                renderCurrentScreen()
            },
            onAlbumLongClick = { album -> showAlbumContextMenu(album) }
        )
        mediaAdapter = MediaGridAdapter(
            context = this,
            spanCount = prefs.mediaColumns,
            accentColor = prefs.accentColor,
            onItemClick = { _, pos -> openMediaViewer(displayedMedia, pos) },
            onItemLongClick = { item, _ ->
                mediaAdapter.toggleSelect(item.path)
                refreshSelectionBar()
            }
        )
        peopleAdapter = PeopleAdapter(
            context = this,
            onPersonClick = { person ->
                openPersonId = person.id; openPersonTitle = person.displayName()
                openAlbumPath = null; openAlbumTitle = null
                activeFilter = MediaFilter.ALL
                renderCurrentScreen()
            },
            onPersonMenuClick = { person -> showPersonManagementDialog(person) }
        )
    }

    private fun rebuildBottomNav() {
        bottomNavBar.removeAllViews()
        val tabs = listOf("📂\nDossiers", "🖼️\nMédias", "🔥\nTinder IA", "✨\nPour Toi", "👥\nPersonnes")
        tabs.forEachIndexed { idx, label ->
            val selected = activeTab == idx
            val tv = TextView(this).apply {
                text = label; gravity = Gravity.CENTER; textSize = 10.6f
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (selected) prefs.accentColor else Color.rgb(165, 165, 188))
                setPadding(dp(2), dp(5), dp(2), dp(5))
                background = if (selected) GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.argb(38, Color.red(prefs.accentColor), Color.green(prefs.accentColor), Color.blue(prefs.accentColor)))
                } else null
                setOnClickListener { switchTab(idx) }
            }
            bottomNavBar.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun switchTab(idx: Int) {
        if (activeTab == 2 && idx != 2) tinderView.stopVideo()
        activeTab = idx
        openAlbumPath = null; openAlbumTitle = null
        openPersonId = null; openPersonTitle = null
        mediaAdapter.clearSelection(); refreshSelectionBar()
        rebuildBottomNav()
        renderCurrentScreen()
    }

    // ==================== DATA LOADING & BACKGROUND AI INDEXING ====================

    private fun reloadGalleryAsync(startAiIndex: Boolean) {
        if (!hasBasicMediaPerm()) {
            subTv.text = "Autorise l'accès aux photos & vidéos"
            emptyInfoTv.text = "📂 Autorise l'accès à tes photos et vidéos pour charger la galerie !"
            emptyInfoTv.visibility = View.VISIBLE
            return
        }
        Thread {
            val scanned = MediaRepository.scanAllMedia(applicationContext, db, prefs.showHiddenFiles)
            val folderPrefs = db.loadAllFolderPrefs()
            val albums = MediaRepository.buildAlbums(scanned, folderPrefs, prefs.sortOrder)
            val persons = db.loadAllPersons()
            mainHandler.post {
                if (isFinishing) return@post
                allMedia = scanned
                allAlbums = albums
                allPersons = persons
                renderCurrentScreen()
                if (startAiIndex && scanned.isNotEmpty()) {
                    launchAiIndexing(forceRecluster = false)
                }
            }
        }.start()
    }

    private fun launchAiIndexing(forceRecluster: Boolean) {
        if (allMedia.isEmpty()) return
        indexer.startIncrementalIndexing(
            allMedia = allMedia,
            forceReclusterAll = forceRecluster,
            onProgress = { prog ->
                if (prog.running && prog.total > 0) {
                    aiProgressRow.visibility = View.VISIBLE
                    aiProgressTv.text = "🧠 IA & Reconnaissance Personnes : ${prog.done}/${prog.total} · ${prog.personsCount} personne(s)"
                    aiProgressBar.progress = ((prog.done * 100) / prog.total.coerceAtLeast(1)).coerceIn(0, 100)
                } else {
                    aiProgressRow.visibility = View.GONE
                }
            },
            onBatchUpdated = { reloadGalleryAsync(startAiIndex = false) }
        )
    }

    // ==================== SCREEN RENDERING ====================

    private fun renderCurrentScreen() {
        val accent = prefs.accentColor
        mainContainer.setBackgroundColor(prefs.bgColor())
        topBar.setBackgroundColor(prefs.surfaceColor())
        bottomNavBar.setBackgroundColor(prefs.surfaceColor())
        contextActionBtn.background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(accent) }

        val inSubMediaView = openAlbumPath != null || openPersonId != null
        backBtn.visibility = if (inSubMediaView) View.VISIBLE else View.GONE
        emptyInfoTv.visibility = View.GONE
        filterStrip.visibility = View.GONE
        contextBannerRow.visibility = View.GONE

        if (activeTab == 2 && !inSubMediaView) {
            // TAB 2: TINDER AI SWIPE DECK!
            recyclerView.visibility = View.GONE
            tinderView.visibility = View.VISIBLE
            titleTv.text = "🔥 Mode Tinder IA"
            subTv.text = "Apprentissage local + Reconnaissance Personnes"
            refreshTinderDeckOnly()
            return
        }

        tinderView.stopVideo()
        tinderView.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE

        when {
            openAlbumPath != null -> {
                titleTv.text = openAlbumTitle ?: "Dossier"
                val baseList = allMedia.filter { it.folderPath == openAlbumPath }
                renderMediaGridWithFilters(baseList, bannerText = "📂 ${baseList.size} médias dans ce dossier", tinderBtnLabel = "🔥 Swiper ce dossier") {
                    tinderFilterFolderPath = openAlbumPath
                    tinderFilterPersonId = null
                    tinderFilterLabel = "📂 ${openAlbumTitle ?: "Dossier"}"
                    switchTab(2)
                }
            }
            openPersonId != null -> {
                val p = allPersons.firstOrNull { it.id == openPersonId }
                val pName = p?.displayName() ?: openPersonTitle ?: "Personne"
                titleTv.text = "👤 $pName"
                val baseList = allMedia.filter { openPersonId in it.personIds }
                val affTxt = if (p != null) " · Affinité IA ${p.affinityPct()} %" else ""
                renderMediaGridWithFilters(baseList, bannerText = "👤 $pName · ${baseList.size} média(s)$affTxt", tinderBtnLabel = "🔥 Swiper $pName") {
                    tinderFilterFolderPath = null
                    tinderFilterPersonId = openPersonId
                    tinderFilterLabel = "👤 $pName"
                    switchTab(2)
                }
            }
            activeTab == 0 -> {
                // TAB 0: SIMPLE GALLERY PRO ALBUMS GRID
                titleTv.text = "Simple Gallery AI Pro"
                val hiddenTag = if (prefs.showHiddenFiles) " · Cachés ON" else ""
                subTv.text = "${allAlbums.size} dossiers · ${allMedia.size} médias$hiddenTag"
                val filteredAlbums = if (searchQuery.isBlank()) allAlbums
                else allAlbums.filter { it.name.contains(searchQuery, ignoreCase = true) }

                recyclerView.layoutManager = GridLayoutManager(this, prefs.folderColumns)
                recyclerView.adapter = albumAdapter
                albumAdapter.submitList(filteredAlbums, prefs.folderColumns, accent)
                if (filteredAlbums.isEmpty()) {
                    emptyInfoTv.text = if (searchQuery.isNotBlank()) "Aucun dossier trouvé pour « $searchQuery »."
                    else "📂 Aucun média trouvé.\nActive l'accès aux fichiers dans le menu ⋮ si besoin."
                    emptyInfoTv.visibility = View.VISIBLE
                }
            }
            activeTab == 1 -> {
                // TAB 1: ALL MEDIA TIMELINE GRID
                titleTv.text = "🖼️ Tous les Médias"
                renderMediaGridWithFilters(allMedia, bannerText = null, tinderBtnLabel = null, onBannerBtn = null)
            }
            activeTab == 3 -> {
                // TAB 3: ✨ POUR TOI (AI FAVORITE PREDICTIONS!)
                titleTv.text = "✨ Pour Toi (IA Locale)"
                val trainedCnt = allMedia.count { it.swipeRating != 0 }
                val base = allMedia.filter { it.aiScorePct >= 0 && (!forYouUnseenOnly || it.swipeRating == 0) }
                val ranked = base.sortedWith(compareByDescending<MediaItem> { it.aiScorePct }.thenByDescending { it.faceCount }.thenByDescending { it.dateModifiedSec })
                val searched = applySearchFilter(ranked)
                displayedMedia = searched
                subTv.text = "Prédictions classées de 99 % → 0 % · $trainedCnt swipes appris"

                contextBannerTv.text = if (forYouUnseenOnly) {
                    "🧠 Top non vus (${searched.size}) · $trainedCnt swipes · ${allPersons.size} pers."
                } else {
                    "🧠 Tous les médias notés (${searched.size}) · $trainedCnt swipes"
                }
                contextActionBtn.text = if (forYouUnseenOnly) "👁️ Voir tous" else "✨ Non vus"
                contextActionBtn.setOnClickListener { forYouUnseenOnly = !forYouUnseenOnly; renderCurrentScreen() }
                contextBannerRow.visibility = View.VISIBLE

                recyclerView.layoutManager = GridLayoutManager(this, prefs.mediaColumns)
                recyclerView.adapter = mediaAdapter
                mediaAdapter.submitList(searched, prefs.mediaColumns, accent)

                if (searched.isEmpty()) {
                    emptyInfoTv.text = "✨ Lance quelques swipes dans l'onglet 🔥 Tinder IA ou attends l'indexation IA pour voir tes pépites estimées !"
                    emptyInfoTv.visibility = View.VISIBLE
                }
            }
            activeTab == 4 -> {
                // TAB 4: 👥 PERSONNES RECONNUES
                titleTv.text = "👥 Personnes (${allPersons.size})"
                subTv.text = "Reconnaissance faciale 100 % locale (224-D · hors ligne)"
                contextBannerTv.text = "Appuie sur une personne pour voir ses photos/vidéos, ou ⚙️ pour la nommer / fusionner / marquer VIP !"
                contextActionBtn.text = "🔄 Scanner"
                contextActionBtn.setOnClickListener {
                    Toast.makeText(this, "🧠 Analyse IA & visages lancée…", Toast.LENGTH_SHORT).show()
                    launchAiIndexing(forceRecluster = false)
                }
                contextBannerRow.visibility = View.VISIBLE

                val filteredPersons = if (searchQuery.isBlank()) allPersons
                else allPersons.filter { it.displayName().contains(searchQuery, ignoreCase = true) }

                recyclerView.layoutManager = GridLayoutManager(this, 2)
                recyclerView.adapter = peopleAdapter
                peopleAdapter.submitList(filteredPersons)

                if (filteredPersons.isEmpty()) {
                    emptyInfoTv.text = "👥 Aucun visage encore regroupé.\nL'IA locale détecte et regroupe les visages automatiquement en arrière-plan…"
                    emptyInfoTv.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun renderMediaGridWithFilters(
        sourceList: List<MediaItem>,
        bannerText: String?,
        tinderBtnLabel: String?,
        onBannerBtn: (() -> Unit)?
    ) {
        // Build filter chips
        filterStrip.removeAllViews()
        val chips = listOf(MediaFilter.ALL, MediaFilter.PHOTOS, MediaFilter.VIDEOS, MediaFilter.GIFS, MediaFilter.FAVORITES, MediaFilter.AI_TOP)
        for (f in chips) {
            val active = activeFilter == f
            val b = Button(this).apply {
                text = f.label; isAllCaps = false; textSize = 10.2f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (active) Color.WHITE else Color.rgb(185, 185, 208))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(if (active) prefs.accentColor else prefs.cardColor())
                }
                minHeight = 0; minimumHeight = 0; setPadding(dp(4), dp(6), dp(4), dp(6))
                setOnClickListener { activeFilter = f; renderCurrentScreen() }
            }
            filterStrip.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
        }
        filterStrip.visibility = View.VISIBLE

        if (bannerText != null && tinderBtnLabel != null && onBannerBtn != null) {
            contextBannerTv.text = bannerText
            contextActionBtn.text = tinderBtnLabel
            contextActionBtn.setOnClickListener { onBannerBtn() }
            contextBannerRow.visibility = View.VISIBLE
        }

        val byFilter = when (activeFilter) {
            MediaFilter.ALL -> sourceList
            MediaFilter.PHOTOS -> sourceList.filter { it.type == MediaType.PHOTO }
            MediaFilter.VIDEOS -> sourceList.filter { it.type == MediaType.VIDEO }
            MediaFilter.GIFS -> sourceList.filter { it.type == MediaType.GIF }
            MediaFilter.FAVORITES -> sourceList.filter { it.isFavorite }
            MediaFilter.AI_TOP -> sourceList.filter { it.aiScorePct >= 60 }.sortedByDescending { it.aiScorePct }
        }
        val searched = applySearchFilter(byFilter)
        val sorted = if (activeFilter == MediaFilter.AI_TOP) searched else MediaRepository.sortMedia(searched, prefs.sortOrder)
        displayedMedia = sorted
        subTv.text = "${sorted.size} média(s) · ${prefs.sortOrder.label}"

        recyclerView.layoutManager = GridLayoutManager(this, prefs.mediaColumns)
        recyclerView.adapter = mediaAdapter
        mediaAdapter.submitList(sorted, prefs.mediaColumns, prefs.accentColor)
        if (sorted.isEmpty()) {
            emptyInfoTv.text = "Aucun média dans cette catégorie."
            emptyInfoTv.visibility = View.VISIBLE
        }
    }

    private fun applySearchFilter(list: List<MediaItem>): List<MediaItem> {
        if (searchQuery.isBlank()) return list
        val q = searchQuery.lowercase(Locale.US)
        val matchingPersonIds = allPersons.filter { it.displayName().lowercase(Locale.US).contains(q) }.map { it.id }.toSet()
        return list.filter { item ->
            item.name.lowercase(Locale.US).contains(q) ||
                item.folderName.lowercase(Locale.US).contains(q) ||
                item.personIds.any { it in matchingPersonIds }
        }
    }

    // ==================== TINDER AI ENGINE HANDLERS ====================

    private fun refreshTinderDeckOnly() {
        val personsMap = allPersons.associateBy { it.id }
        val queue = VisualPreferenceAiEngine.buildTinderQueue(
            allMedia = allMedia,
            mode = tinderMode,
            folderFilterPath = tinderFilterFolderPath,
            personFilterId = tinderFilterPersonId
        )
        val trainedSwipes = allMedia.count { it.swipeRating != 0 }
        tinderView.updateDeck(
            newQueue = queue,
            persons = personsMap,
            trainedSwipesCount = trainedSwipes,
            filterLabel = tinderFilterLabel,
            canUndo = undoStack.isNotEmpty(),
            soundEnabled = prefs.tinderAutoPlaySound
        )
        // Ensure top 2 cards are indexed right away so AI learns even if background indexing isn't finished yet!
        queue.firstOrNull()?.let { top ->
            if (top.aiScorePct < 0) indexer.ensureIndexedAsync(top) {}
        }
    }

    private fun handleTinderSwiped(item: MediaItem, rating: Int) {
        undoStack.addLast(UndoRecord(item, item.swipeRating, item.isFavorite))
        while (undoStack.size > 25) undoStack.removeFirst()

        Thread {
            VisualPreferenceAiEngine.applySwipeFeedback(db, item, rating)
            // Rescore gallery immediately so next AI Top Picks & Pour Toi update in real time!
            VisualPreferenceAiEngine.rescoreAllMedia(db, allMedia)
            val updatedMeta = db.loadAllMediaMeta()
            val updatedPersons = db.loadAllPersons()
            val updatedMedia = allMedia.map { m ->
                val mm = updatedMeta[m.path]
                if (mm != null) m.copy(isFavorite = mm.isFavorite, swipeRating = mm.swipeRating, aiScorePct = mm.aiScorePct, aiReason = mm.aiReason)
                else m
            }
            mainHandler.post {
                allMedia = updatedMedia
                allPersons = updatedPersons
                refreshTinderDeckOnly()
            }
        }.start()
    }

    private fun handleTinderUndo() {
        val rec = undoStack.removeLastOrNull() ?: return
        Thread {
            VisualPreferenceAiEngine.undoSwipeFeedback(db, rec.item, rec.prevRating, rec.prevFav)
            VisualPreferenceAiEngine.rescoreAllMedia(db, allMedia)
            val updatedMeta = db.loadAllMediaMeta()
            val updatedPersons = db.loadAllPersons()
            val updatedMedia = allMedia.map { m ->
                val mm = updatedMeta[m.path]
                if (mm != null) m.copy(isFavorite = mm.isFavorite, swipeRating = mm.swipeRating, aiScorePct = mm.aiScorePct, aiReason = mm.aiReason)
                else m
            }
            mainHandler.post {
                allMedia = updatedMedia
                allPersons = updatedPersons
                Toast.makeText(this, "↩ Dernier swipe annulé", Toast.LENGTH_SHORT).show()
                refreshTinderDeckOnly()
            }
        }.start()
    }

    private fun handleTinderTrashItem(item: MediaItem) {
        AlertDialog.Builder(this)
            .setTitle("🗑️ Mettre à la Corbeille ?")
            .setMessage(item.name)
            .setPositiveButton("Corbeille") { _, _ ->
                if (MediaRepository.moveToRecycleBin(this, db, item)) {
                    Toast.makeText(this, "🗑️ Déplacé dans la corbeille", Toast.LENGTH_SHORT).show()
                    reloadGalleryAsync(startAiIndex = false)
                } else {
                    Toast.makeText(this, "⚠️ Active « Accès à tous les fichiers » dans le menu ⋮.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ==================== DIALOGS: ALBUMS, PERSONS, BATCH, TRASH, SETTINGS & PIN ====================

    private fun showAlbumContextMenu(album: AlbumFolder) {
        val pinTxt = if (album.isPinned) "📌 Désépingler ce dossier" else "📌 Épingler en haut"
        val opts = arrayOf("🔥 Swiper ce dossier en mode Tinder IA", pinTxt, "🚫 Masquer / Exclure ce dossier")
        AlertDialog.Builder(this)
            .setTitle("📂 ${album.name}")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> {
                        tinderFilterFolderPath = album.path; tinderFilterPersonId = null; tinderFilterLabel = "📂 ${album.name}"
                        switchTab(2)
                    }
                    1 -> { db.setFolderPinned(album.path, !album.isPinned); reloadGalleryAsync(false) }
                    2 -> {
                        db.setFolderExcluded(album.path, true)
                        Toast.makeText(this, "Dossier masqué (réactivable dans ⋮ Paramètres).", Toast.LENGTH_SHORT).show()
                        reloadGalleryAsync(false)
                    }
                }
            }
            .show()
    }

    private fun showPersonManagementDialog(person: PersonProfile) {
        val vipLabel = if (person.isVip) "⭐ Retirer le statut VIP" else "⭐ Marquer VIP (Boost Favoris IA !)"
        val ignLabel = if (person.isIgnored) "✅ Ne plus ignorer" else "🚫 Ignorer cette personne dans l'IA"
        val opts = arrayOf("🔥 Swiper cette personne en Tinder", "✏️ Renommer", "🔗 Fusionner avec une autre personne", vipLabel, ignLabel)
        AlertDialog.Builder(this)
            .setTitle("👤 ${person.displayName()} (${person.faceCount} médias · ${person.affinityPct()} %)")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> {
                        tinderFilterFolderPath = null; tinderFilterPersonId = person.id; tinderFilterLabel = "👤 ${person.displayName()}"
                        switchTab(2)
                    }
                    1 -> {
                        val et = EditText(this).apply {
                            setText(person.name); hint = "Prénom ou surnom…"; setTextColor(Color.WHITE)
                            setPadding(dp(16), dp(12), dp(16), dp(12)); setBackgroundColor(Color.rgb(30, 30, 40))
                        }
                        AlertDialog.Builder(this)
                            .setTitle("✏️ Nommer cette personne")
                            .setView(et)
                            .setPositiveButton("Enregistrer") { _, _ ->
                                db.renamePerson(person.id, et.text?.toString().orEmpty())
                                rescoreAndReload()
                            }
                            .setNegativeButton("Annuler", null)
                            .show()
                    }
                    2 -> showMergePersonDialog(person)
                    3 -> { db.setPersonFlags(person.id, !person.isVip, false); rescoreAndReload() }
                    4 -> { db.setPersonFlags(person.id, false, !person.isIgnored); rescoreAndReload() }
                }
            }
            .show()
    }

    private fun showMergePersonDialog(source: PersonProfile) {
        val others = allPersons.filter { it.id != source.id }
        if (others.isEmpty()) {
            Toast.makeText(this, "Aucune autre personne détectée à fusionner.", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = others.map { "${it.displayName()} (${it.faceCount} médias)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("🔗 Fusionner ${source.displayName()} dans :")
            .setItems(labels) { _, which ->
                val target = others[which]
                db.mergePersons(source.id, target.id)
                Toast.makeText(this, "🔗 Fusionné dans ${target.displayName()} ✅ (précision IA améliorée !)", Toast.LENGTH_LONG).show()
                rescoreAndReload()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun rescoreAndReload() {
        Thread {
            VisualPreferenceAiEngine.rescoreAllMedia(db, allMedia)
            mainHandler.post { reloadGalleryAsync(false) }
        }.start()
    }

    private fun refreshSelectionBar() {
        val cnt = mediaAdapter.selectedCount()
        if (cnt > 0) {
            multiSelCountTv.text = "$cnt sélectionné(s)"
            multiSelectBar.visibility = View.VISIBLE
        } else {
            multiSelectBar.visibility = View.GONE
        }
    }

    private fun batchFavoriteSelected() {
        val sel = mediaAdapter.getSelectedItems()
        for (it in sel) VisualPreferenceAiEngine.applySwipeFeedback(db, it, rating = 1)
        mediaAdapter.clearSelection(); refreshSelectionBar()
        Toast.makeText(this, "❤️ ${sel.size} média(s) ajoutés aux Favoris (+ apprentissage IA)", Toast.LENGTH_SHORT).show()
        rescoreAndReload()
    }

    private fun batchShareSelected() {
        val sel = mediaAdapter.getSelectedItems()
        val intent = MediaRepository.buildShareIntent(this, sel) ?: return
        try { startActivity(Intent.createChooser(intent, "Partager ${sel.size} média(s)")) } catch (_: Throwable) {}
    }

    private fun batchCopyOrMoveSelected() {
        val sel = mediaAdapter.getSelectedItems()
        if (sel.isEmpty()) return
        val folderNames = allAlbums.map { "📂 ${it.name}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("📂 Choisir le dossier de destination")
            .setItems(folderNames) { _, idx ->
                val target = allAlbums[idx]
                AlertDialog.Builder(this)
                    .setTitle("${sel.size} fichier(s) → ${target.name}")
                    .setPositiveButton("Déplacer") { _, _ -> performBatchCopyMove(sel, target.path, move = true) }
                    .setNeutralButton("Copier") { _, _ -> performBatchCopyMove(sel, target.path, move = false) }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
            .show()
    }

    private fun performBatchCopyMove(sel: List<MediaItem>, targetPath: String, move: Boolean) {
        Thread {
            var okCnt = 0
            for (it in sel) if (MediaRepository.copyOrMoveFile(applicationContext, db, it, targetPath, move) != null) okCnt++
            mainHandler.post {
                mediaAdapter.clearSelection(); refreshSelectionBar()
                Toast.makeText(this, "$okCnt/${sel.size} fichier(s) ${if (move) "déplacé(s)" else "copié(s)"} ✅", Toast.LENGTH_SHORT).show()
                reloadGalleryAsync(false)
            }
        }.start()
    }

    private fun batchDeleteSelected() {
        val sel = mediaAdapter.getSelectedItems()
        if (sel.isEmpty()) return
        val trash = prefs.useRecycleBin
        AlertDialog.Builder(this)
            .setTitle(if (trash) "🗑️ Mettre ${sel.size} média(s) à la Corbeille ?" else "⚠️ Supprimer ${sel.size} média(s) ?")
            .setPositiveButton(if (trash) "Corbeille" else "Supprimer") { _, _ ->
                Thread {
                    var okCnt = 0
                    for (it in sel) {
                        val ok = if (trash) MediaRepository.moveToRecycleBin(applicationContext, db, it)
                        else MediaRepository.deletePermanently(applicationContext, db, it.path)
                        if (ok) okCnt++
                    }
                    mainHandler.post {
                        mediaAdapter.clearSelection(); refreshSelectionBar()
                        Toast.makeText(this, "$okCnt média(s) supprimé(s) ✅", Toast.LENGTH_SHORT).show()
                        reloadGalleryAsync(false)
                    }
                }.start()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showSortDialog() {
        val orders = SortOrder.values()
        val labels = orders.map { it.label }.toTypedArray()
        val curIdx = orders.indexOf(prefs.sortOrder).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("⇅ Trier par")
            .setSingleChoiceItems(labels, curIdx) { dlg, which ->
                prefs.sortOrder = orders[which]
                dlg.dismiss()
                reloadGalleryAsync(false)
            }
            .show()
    }

    private fun cycleGridColumns() {
        if (activeTab == 0 && openAlbumPath == null && openPersonId == null) {
            prefs.folderColumns = if (prefs.folderColumns >= 4) 2 else prefs.folderColumns + 1
            Toast.makeText(this, "Grille dossiers : ${prefs.folderColumns} colonnes", Toast.LENGTH_SHORT).show()
        } else {
            prefs.mediaColumns = if (prefs.mediaColumns >= 5) 2 else prefs.mediaColumns + 1
            Toast.makeText(this, "Grille médias : ${prefs.mediaColumns} colonnes", Toast.LENGTH_SHORT).show()
        }
        renderCurrentScreen()
    }

    private fun toggleSearchBar(show: Boolean) {
        searchVisible = show
        searchBox.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) { searchQuery = ""; searchBox.setText(""); renderCurrentScreen() }
        else searchBox.requestFocus()
    }

    private fun openMediaViewer(list: List<MediaItem>, startPos: Int) {
        if (list.isEmpty()) return
        MediaViewerActivity.sharedMediaPaths = list.map { it.path }
        startActivity(Intent(this, MediaViewerActivity::class.java).apply { putExtra(MediaViewerActivity.EXTRA_START_INDEX, startPos) })
    }

    private fun showSettingsAndToolsDialog() {
        val trashCount = db.loadRecycleBin().size
        val hiddenLabel = if (prefs.showHiddenFiles) "👁️ Masquer fichiers cachés (.* & .nomedia)" else "👁️ Afficher fichiers cachés (.* & .nomedia)"
        val soundLabel = if (prefs.tinderAutoPlaySound) "🔇 Couper le son auto des vidéos Tinder" else "🔊 Activer le son auto des vidéos Tinder"
        val themeLabel = if (prefs.theme == GalleryTheme.DARK) "🌑 Passer en thème AMOLED Noir Pur" else "🌘 Passer en thème Sombre Classique"
        val pinLabel = if (prefs.pinLockEnabled) "🔓 Désactiver / Modifier le Code PIN" else "🔒 Activer le Verrouillage Code PIN"

        val opts = arrayOf(
            hiddenLabel,
            "🗑️ Corbeille ($trashCount élément(s))",
            "🎨 Changer la couleur d'accent",
            themeLabel,
            "🎯 Précision Reconnaissance Visages (${prefs.faceMatchThresholdPct} %)",
            "🧠 Relancer l'analyse complète IA & Visages",
            soundLabel,
            pinLabel,
            "👁️ Réafficher tous les dossiers exclus",
            "📂 Accès à tous les fichiers (.* / Supprimer)",
            "♻️ Réinitialiser l'apprentissage IA uniquement"
        )

        AlertDialog.Builder(this)
            .setTitle("⚙️ Simple Gallery AI Pro")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> {
                        if (!prefs.showHiddenFiles && !MediaRepository.hasAllFilesAccess()) {
                            Toast.makeText(this, "Autorise « Accès à tous les fichiers » pour voir .* & .nomedia !", Toast.LENGTH_LONG).show()
                            requestAllFilesAccess()
                        }
                        prefs.showHiddenFiles = !prefs.showHiddenFiles
                        reloadGalleryAsync(true)
                    }
                    1 -> showRecycleBinDialog()
                    2 -> showAccentColorPicker()
                    3 -> {
                        prefs.theme = if (prefs.theme == GalleryTheme.DARK) GalleryTheme.AMOLED else GalleryTheme.DARK
                        rebuildBottomNav(); renderCurrentScreen()
                    }
                    4 -> showFaceThresholdDialog()
                    5 -> {
                        Toast.makeText(this, "🧠 Ré-analyse IA & Visages lancée…", Toast.LENGTH_SHORT).show()
                        launchAiIndexing(forceRecluster = true)
                    }
                    6 -> {
                        prefs.tinderAutoPlaySound = !prefs.tinderAutoPlaySound
                        Toast.makeText(this, if (prefs.tinderAutoPlaySound) "🔊 Son vidéo Tinder activé" else "🔇 Son vidéo Tinder coupé", Toast.LENGTH_SHORT).show()
                        if (activeTab == 2) refreshTinderDeckOnly()
                    }
                    7 -> showPinSetupDialog()
                    8 -> {
                        val prefsMap = db.loadAllFolderPrefs()
                        for ((fp, row) in prefsMap) if (row.isExcluded) db.setFolderExcluded(fp, false)
                        Toast.makeText(this, "Tous les dossiers masqués ont été restaurés ✅", Toast.LENGTH_SHORT).show()
                        reloadGalleryAsync(false)
                    }
                    9 -> requestAllFilesAccess()
                    10 -> {
                        AlertDialog.Builder(this)
                            .setTitle("♻️ Réinitialiser l'apprentissage IA ?")
                            .setMessage("Remet à zéro tes swipes et les scores IA (tes photos et tes noms de personnes sont conservés).")
                            .setPositiveButton("Réinitialiser") { _, _ ->
                                db.resetAiLearningOnly()
                                undoStack.clear()
                                Toast.makeText(this, "Apprentissage IA réinitialisé ✅", Toast.LENGTH_SHORT).show()
                                reloadGalleryAsync(false)
                            }
                            .setNegativeButton("Annuler", null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun showAccentColorPicker() {
        val names = GalleryPreferences.ACCENT_PRESETS.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("🎨 Couleur d'accent")
            .setItems(names) { _, which ->
                prefs.accentColor = GalleryPreferences.ACCENT_PRESETS[which].second
                rebuildBottomNav()
                renderCurrentScreen()
            }
            .show()
    }

    private fun showFaceThresholdDialog() {
        val levels = listOf(70 to "Souple (70 % — regroupe plus largement)", 76 to "Équilibré (76 % — recommandé)", 82 to "Strict (82 % — très précis)", 87 to "Ultra-Strict (87 %)")
        val labels = levels.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("🎯 Seuil de Reconnaissance Visages")
            .setItems(labels) { _, which ->
                prefs.faceMatchThresholdPct = levels[which].first
                Toast.makeText(this, "Seuil réglé sur ${prefs.faceMatchThresholdPct} % ✅", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showRecycleBinDialog() {
        val trashed = db.loadRecycleBin()
        if (trashed.isEmpty()) {
            Toast.makeText(this, "🗑️ La corbeille est vide.", Toast.LENGTH_SHORT).show()
            return
        }
        val names = trashed.map { "🗑️ ${it.name}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("🗑️ Corbeille (${trashed.size} élément(s))")
            .setItems(names) { _, idx ->
                val item = trashed[idx]
                AlertDialog.Builder(this)
                    .setTitle(item.name)
                    .setPositiveButton("↩ Restaurer") { _, _ ->
                        MediaRepository.restoreFromRecycleBin(this, db, item)
                        Toast.makeText(this, "Restauré ✅", Toast.LENGTH_SHORT).show()
                        reloadGalleryAsync(false)
                    }
                    .setNegativeButton("Suppr. définitivement") { _, _ ->
                        MediaRepository.deleteTrashedPermanently(db, item)
                        Toast.makeText(this, "Supprimé définitivement ✅", Toast.LENGTH_SHORT).show()
                    }
                    .setNeutralButton("Fermer", null)
                    .show()
            }
            .setNeutralButton("Tout restaurer") { _, _ ->
                for (t in trashed) MediaRepository.restoreFromRecycleBin(this, db, t)
                Toast.makeText(this, "Tous les fichiers restaurés ✅", Toast.LENGTH_SHORT).show()
                reloadGalleryAsync(false)
            }
            .setNegativeButton("Vider la corbeille") { _, _ ->
                for (t in trashed) MediaRepository.deleteTrashedPermanently(db, t)
                Toast.makeText(this, "Corbeille vidée ✅", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showPinSetupDialog() {
        val et = EditText(this).apply {
            hint = "Nouveau PIN (4 à 8 chiffres)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(Color.WHITE); setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.rgb(30, 30, 40))
        }
        AlertDialog.Builder(this)
            .setTitle("🔒 Verrouillage Galerie par PIN")
            .setView(et)
            .setPositiveButton("Activer PIN") { _, _ ->
                val code = et.text?.toString()?.trim().orEmpty()
                if (code.length < 4) {
                    Toast.makeText(this, "Entre au moins 4 chiffres.", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.pinCode = code; prefs.pinLockEnabled = true; pinUnlockedThisLaunch = true
                    Toast.makeText(this, "🔒 Code PIN activé ✅", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Désactiver") { _, _ ->
                prefs.pinLockEnabled = false; prefs.pinCode = ""
                Toast.makeText(this, "🔓 Verrouillage PIN désactivé", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showPinLockOverlay() {
        pinOverlay.removeAllViews()
        pinOverlay.addView(TextView(this).apply {
            text = "🔒 Simple Gallery AI Pro"
            setTextColor(Color.WHITE); textSize = 22f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        val dotsTv = TextView(this).apply {
            text = "Saisis ton code PIN"; setTextColor(Color.rgb(253, 224, 71)); textSize = 20f; typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(18))
        }
        pinOverlay.addView(dotsTv)

        var entered = ""
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✓"))
        for (keys in rows) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            for (k in keys) {
                val b = Button(this).apply {
                    text = k; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.rgb(34, 34, 46)) }
                    setOnClickListener {
                        when (k) {
                            "⌫" -> if (entered.isNotEmpty()) entered = entered.dropLast(1)
                            "✓" -> {
                                if (entered == prefs.pinCode) {
                                    pinUnlockedThisLaunch = true
                                    pinOverlay.visibility = View.GONE
                                } else {
                                    entered = ""
                                    dotsTv.text = "❌ Code incorrect"
                                    return@setOnClickListener
                                }
                            }
                            else -> {
                                if (entered.length < 8) entered += k
                                if (entered == prefs.pinCode) {
                                    pinUnlockedThisLaunch = true
                                    pinOverlay.visibility = View.GONE
                                    return@setOnClickListener
                                }
                            }
                        }
                        dotsTv.text = if (entered.isEmpty()) "Saisis ton code PIN" else "•".repeat(entered.length)
                    }
                }
                r.addView(b, LinearLayout.LayoutParams(dp(74), dp(58)).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
            }
            pinOverlay.addView(r)
        }
        pinOverlay.visibility = View.VISIBLE
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Throwable) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }

    private fun makeToolbarIcon(icon: String, onClick: () -> Unit) = TextView(this).apply {
        text = icon; setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
        setPadding(dp(8), dp(6), dp(8), dp(6)); setOnClickListener { onClick() }
    }
}
