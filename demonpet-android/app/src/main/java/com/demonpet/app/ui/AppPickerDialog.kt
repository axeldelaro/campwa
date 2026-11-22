package com.demonpet.app.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object AppPickerDialog {

    private data class AppEntry(val packageName: String, val label: String)

    fun show(context: Context, title: String, initialSelected: Set<String>, onSave: (Set<String>) -> Unit) {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val resolved = try { pm.queryIntentActivities(mainIntent, 0) } catch (_: Throwable) { emptyList() }
        val seen = HashSet<String>()
        val entries = ArrayList<AppEntry>()
        for (ri in resolved) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == context.packageName || !seen.add(pkg)) continue
            val lbl = try { ri.loadLabel(pm).toString().ifBlank { pkg } } catch (_: Throwable) { pkg }
            entries.add(AppEntry(pkg, lbl))
        }
        entries.sortWith(compareByDescending<AppEntry> { initialSelected.contains(it.packageName) }.thenBy { it.label.lowercase() })

        val selected = HashSet(initialSelected)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            setBackgroundColor(Color.rgb(18, 18, 22))
        }
        val search = EditText(context).apply {
            hint = "Rechercher une application…"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(140, 140, 152))
            textSize = 13f
        }
        root.addView(search)

        val scroll = ScrollView(context)
        val listLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listLayout)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(330)))

        fun render(q: String) {
            listLayout.removeAllViews()
            val clean = q.trim().lowercase()
            for (item in entries) {
                if (clean.isNotEmpty() && !item.label.lowercase().contains(clean) && !item.packageName.lowercase().contains(clean)) continue
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                }
                val cb = CheckBox(context).apply {
                    isChecked = selected.contains(item.packageName)
                    setOnCheckedChangeListener { _, c -> if (c) selected.add(item.packageName) else selected.remove(item.packageName) }
                }
                val tv = TextView(context).apply {
                    text = item.label
                    setTextColor(Color.WHITE)
                    textSize = 13f
                }
                row.addView(cb)
                row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.setOnClickListener { cb.isChecked = !cb.isChecked }
                listLayout.addView(row)
            }
        }
        render("")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(root)
            .setPositiveButton("Enregistrer (${selected.size})") { _, _ -> onSave(selected) }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
