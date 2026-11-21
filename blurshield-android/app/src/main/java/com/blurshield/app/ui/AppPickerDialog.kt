package com.blurshield.app.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

data class AppEntry(val pkg: String, val label: String)

object AppPickerDialog {

    fun show(
        context: Context,
        title: String,
        initialSelected: Set<String>,
        onSaved: (Set<String>) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val selected = HashSet(initialSelected)
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(mainIntent, 0)
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                val label = try { ri.loadLabel(pm)?.toString()?.trim().orEmpty() } catch (_: Throwable) { pkg }
                AppEntry(pkg, label.ifEmpty { pkg })
            }
            .distinctBy { it.pkg }
            .sortedWith(
                compareByDescending<AppEntry> { selected.contains(it.pkg) }
                    .thenBy { it.label.lowercase(Locale.FRENCH) }
            )

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            setBackgroundColor(Color.rgb(18, 18, 22))
        }

        val search = EditText(context).apply {
            hint = "Rechercher TikTok, Instagram, Chrome..."
            setHintTextColor(Color.rgb(113, 113, 122))
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.rgb(9, 9, 11))
                setStroke(dp(1), Color.argb(40, 255, 255, 255))
            }
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })

        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(context).apply {
            addView(listContainer)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340)))

        fun renderList(query: String) {
            listContainer.removeAllViews()
            val q = query.trim().lowercase(Locale.FRENCH)
            val filtered = if (q.isEmpty()) apps else apps.filter {
                it.label.lowercase(Locale.FRENCH).contains(q) || it.pkg.lowercase(Locale.US).contains(q)
            }
            for (app in filtered) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                }
                val cb = CheckBox(context).apply {
                    isChecked = selected.contains(app.pkg)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selected.add(app.pkg) else selected.remove(app.pkg)
                    }
                }
                val txt = TextView(context).apply {
                    text = app.label
                    setTextColor(Color.rgb(244, 244, 245))
                    textSize = 13f
                    setPadding(dp(6), 0, 0, 0)
                }
                row.addView(cb)
                row.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.setOnClickListener { cb.isChecked = !cb.isChecked }
                listContainer.addView(row)
            }
        }

        renderList("")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderList(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setView(root)
            .setPositiveButton("Enregistrer") { _, _ -> onSaved(selected) }
            .setNeutralButton("Tout effacer") { _, _ -> onSaved(emptySet()) }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
