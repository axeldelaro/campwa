package com.aigallery.pro.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aigallery.pro.data.PersonProfile
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import java.io.File

/**
 * Recognized People Grid Adapter:
 * - Displays circular eye-aligned face avatars, person name, photo/video count,
 *   learned Bayesian AI Affinity %, VIP/Ignored status, and long-press/menu management!
 */
class PeopleAdapter(
    private val context: Context,
    private val onPersonClick: (PersonProfile) -> Unit,
    private val onPersonMenuClick: (PersonProfile) -> Unit
) : RecyclerView.Adapter<PeopleAdapter.PersonVH>() {

    private var items: List<PersonProfile> = emptyList()
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    fun submitList(list: List<PersonProfile>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PersonVH {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.rgb(28, 28, 36))
                setStroke(dp(1), Color.argb(55, 255, 255, 255))
            }
            setPadding(dp(10), dp(12), dp(10), dp(10))
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(5), dp(5), dp(5), dp(5))
            }
        }

        val avatar = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(42, 42, 54))
            }
            clipToOutline = true
        }
        card.addView(avatar, LinearLayout.LayoutParams(dp(84), dp(84)).apply { bottomMargin = dp(8) })

        val nameTv = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 13.4f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; maxLines = 1
        }
        card.addView(nameTv)

        val countTv = TextView(context).apply {
            setTextColor(Color.rgb(180, 180, 200)); textSize = 11f; gravity = Gravity.CENTER
        }
        card.addView(countTv)

        val affTv = TextView(context).apply {
            textSize = 11f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(3))
        }
        card.addView(affTv)

        val affBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 50
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(65, 255, 255, 255))
        }
        card.addView(affBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)).apply { bottomMargin = dp(6) })

        val editBtn = TextView(context).apply {
            text = "⚙️ Nommer / Fusionner"
            setTextColor(Color.rgb(216, 180, 254)); textSize = 10.6f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.rgb(42, 34, 62)) }
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }
        card.addView(editBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        return PersonVH(card, avatar, nameTv, countTv, affTv, affBar, editBtn)
    }

    override fun onBindViewHolder(holder: PersonVH, position: Int) {
        val p = items[position]
        val badge = when {
            p.isVip -> "⭐ "
            p.isIgnored -> "🚫 "
            else -> ""
        }
        holder.nameTv.text = "$badge${p.displayName()}"
        holder.countTv.text = "${p.faceCount} média(s) · ❤️${p.likesCount + p.superLikesCount} ✕${p.dislikesCount}"

        val aff = p.affinityPct()
        holder.affTv.text = when {
            p.isIgnored -> "🚫 Personne ignorée"
            p.isVip -> "⭐ VIP · $aff % affinité IA"
            else -> "🧠 Affinité IA : $aff %"
        }
        val affColor = when {
            p.isIgnored || aff < 35 -> Color.rgb(244, 63, 94)
            p.isVip || aff >= 72 -> Color.rgb(16, 185, 129)
            else -> Color.rgb(250, 204, 21)
        }
        holder.affTv.setTextColor(affColor)
        holder.affBar.progress = aff
        holder.affBar.progressTintList = ColorStateList.valueOf(affColor)

        if (p.avatarPath.isNotBlank() && File(p.avatarPath).exists()) {
            Glide.with(holder.avatar).load(File(p.avatarPath)).circleCrop().diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).into(holder.avatar)
        } else {
            holder.avatar.setImageDrawable(null)
        }

        holder.itemView.setOnClickListener { onPersonClick(p) }
        holder.itemView.setOnLongClickListener { onPersonMenuClick(p); true }
        holder.editBtn.setOnClickListener { onPersonMenuClick(p) }
    }

    class PersonVH(
        root: LinearLayout,
        val avatar: ImageView,
        val nameTv: TextView,
        val countTv: TextView,
        val affTv: TextView,
        val affBar: ProgressBar,
        val editBtn: TextView
    ) : RecyclerView.ViewHolder(root)
}
