package com.aigallery.pro.data

import android.content.Context
import android.graphics.Color

class GalleryPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("simple_gallery_ai_prefs", Context.MODE_PRIVATE)

    var theme: GalleryTheme
        get() = GalleryTheme.fromId(prefs.getString("theme", GalleryTheme.DARK.id))
        set(v) = prefs.edit().putString("theme", v.id).apply()

    var accentColor: Int
        get() = prefs.getInt("accent_color", ACCENT_ORANGE)
        set(v) = prefs.edit().putInt("accent_color", v).apply()

    var folderColumns: Int
        get() = prefs.getInt("folder_cols", 2).coerceIn(2, 4)
        set(v) = prefs.edit().putInt("folder_cols", v.coerceIn(2, 4)).apply()

    var mediaColumns: Int
        get() = prefs.getInt("media_cols", 3).coerceIn(2, 5)
        set(v) = prefs.edit().putInt("media_cols", v.coerceIn(2, 5)).apply()

    var showHiddenFiles: Boolean
        get() = prefs.getBoolean("show_hidden", false)
        set(v) = prefs.edit().putBoolean("show_hidden", v).apply()

    var sortOrder: SortOrder
        get() = SortOrder.fromId(prefs.getString("sort_order", SortOrder.DATE_DESC.id))
        set(v) = prefs.edit().putString("sort_order", v.id).apply()

    var slideshowIntervalSec: Int
        get() = prefs.getInt("slideshow_sec", 4).coerceIn(2, 15)
        set(v) = prefs.edit().putInt("slideshow_sec", v.coerceIn(2, 15)).apply()

    var tinderAutoPlaySound: Boolean
        get() = prefs.getBoolean("tinder_sound", false)
        set(v) = prefs.edit().putBoolean("tinder_sound", v).apply()

    var faceMatchThresholdPct: Int
        get() = prefs.getInt("face_thresh_pct", 76).coerceIn(66, 90)
        set(v) = prefs.edit().putInt("face_thresh_pct", v.coerceIn(66, 90)).apply()

    var pinLockEnabled: Boolean
        get() = prefs.getBoolean("pin_lock_en", false)
        set(v) = prefs.edit().putBoolean("pin_lock_en", v).apply()

    var pinCode: String
        get() = prefs.getString("pin_code", "").orEmpty()
        set(v) = prefs.edit().putString("pin_code", v).apply()

    var useRecycleBin: Boolean
        get() = prefs.getBoolean("use_trash", true)
        set(v) = prefs.edit().putBoolean("use_trash", v).apply()

    fun bgColor(): Int = if (theme == GalleryTheme.AMOLED) Color.BLACK else Color.rgb(18, 18, 22)
    fun surfaceColor(): Int = if (theme == GalleryTheme.AMOLED) Color.rgb(14, 14, 18) else Color.rgb(28, 28, 34)
    fun cardColor(): Int = if (theme == GalleryTheme.AMOLED) Color.rgb(20, 20, 26) else Color.rgb(36, 36, 44)

    companion object {
        val ACCENT_ORANGE = Color.rgb(245, 124, 0)     // Classic Simple Gallery Pro Orange!
        val ACCENT_PINK = Color.rgb(236, 72, 153)
        val ACCENT_TEAL = Color.rgb(20, 184, 166)
        val ACCENT_PURPLE = Color.rgb(168, 85, 247)
        val ACCENT_BLUE = Color.rgb(59, 130, 246)
        val ACCENT_CRIMSON = Color.rgb(225, 29, 72)

        val ACCENT_PRESETS = listOf(
            "Orange Pro" to ACCENT_ORANGE,
            "Rose IA" to ACCENT_PINK,
            "Turquoise" to ACCENT_TEAL,
            "Violet" to ACCENT_PURPLE,
            "Bleu" to ACCENT_BLUE,
            "Rouge" to ACCENT_CRIMSON
        )
    }
}
