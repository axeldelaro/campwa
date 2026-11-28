package com.sentinelcam.app.selfcontrol

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * SentinelCam v28.0 — Conscious Friction Pause Screen (Breathing 4-7-8 Dopamine Breaker):
 * Intercepts impulsive opens of tempting apps with a calm countdown + breathing ring.
 * - Big primary emerald button closes the app and credits +10 min reclaimed time!
 * - Subtle secondary "Ouvrir quand même (10 min)" only unlocks once countdown hits 0.
 */
class MindfulFrictionActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var scStore: SelfControlStore

    private var targetPkg: String = ""
    private var targetAppName: String = ""
    private var remainingSec: Int = 20

    private lateinit var breathRing: View
    private lateinit var breathHintView: TextView
    private lateinit var countdownView: TextView
    private lateinit var continueBtn: Button
    private var pulseAnim: ObjectAnimator? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (remainingSec <= 0) {
                countdownView.text = "Prêt"
                breathHintView.text = "Décision consciente — voulez-vous vraiment entrer ?"
                continueBtn.isEnabled = true
                continueBtn.alpha = 1f
                continueBtn.text = "Ouvrir $targetAppName (10 min)"
                return
            }
            countdownView.text = "${remainingSec}s"
            val phase = remainingSec % 12
            breathHintView.text = when {
                phase >= 8 -> "Inspirez lentement par le nez…"
                phase >= 4 -> "Retenez doucement…"
                else -> "Expirez calmement…"
            }
            remainingSec--
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scStore = SelfControlStore(this)
        targetPkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        targetAppName = intent.getStringExtra(EXTRA_APP_NAME).orEmpty().ifBlank { "cette application" }
        remainingSec = intent.getIntExtra(EXTRA_DELAY_SEC, scStore.frictionDelaySec).coerceIn(10, 60)

        setContentView(buildUi())
        startBreathAnimation()
        handler.post(tickRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        pulseAnim?.cancel()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android API 33")
    override fun onBackPressed() {
        abortAndGoHome()
    }

    private fun buildUi(): View {
        val dp = resources.displayMetrics.density
        fun px(v: Int): Int = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(px(28), px(40), px(28), px(40))
        }

        val badge = TextView(this).apply {
            text = "PAUSE CONSCIENTE"
            textSize = 11f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(px(14), px(5), px(14), px(5))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 999f
            }
        }
        root.addView(badge)

        val title = TextView(this).apply {
            text = "Pourquoi ouvrir $targetAppName ?"
            textSize = 22f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, px(18), 0, px(8))
        }
        root.addView(title)

        breathHintView = TextView(this).apply {
            text = "Prenez une grande respiration…"
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, px(28))
        }
        root.addView(breathHintView)

        val ringContainer = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(px(160), px(160))
        }

        breathRing = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(px(124), px(124))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#164E63"))
                setStroke(px(3), Color.parseColor("#38BDF8"))
            }
        }

        countdownView = TextView(this).apply {
            text = "${remainingSec}s"
            textSize = 30f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        (breathRing as LinearLayout).addView(countdownView)
        ringContainer.addView(breathRing)
        root.addView(ringContainer)

        val statNote = TextView(this).apply {
            text = "Ouvertures impulsives évitées : ${scStore.abortedFrictionOpens} • Temps regagné : ${scStore.reclaimedMinutesTotal} min"
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(0, px(24), 0, px(24))
        }
        root.addView(statNote)

        val closeBtn = Button(this).apply {
            text = "Fermer et revenir à l’essentiel"
            isAllCaps = false
            textSize = 15f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(px(20), px(15), px(20), px(15))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#10B981"))
                cornerRadius = px(14).toFloat()
            }
            setOnClickListener { abortAndGoHome() }
        }
        root.addView(
            closeBtn,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        continueBtn = Button(this).apply {
            text = "Patientez…"
            isAllCaps = false
            isEnabled = false
            alpha = 0.35f
            textSize = 13f
            setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL)
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(px(18), px(12), px(18), px(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = px(12).toFloat()
                setStroke(px(1), Color.parseColor("#334155"))
            }
            setOnClickListener {
                if (targetPkg.isNotEmpty()) {
                    scStore.grantFrictionPass(targetPkg, minutes = 10)
                }
                finish()
            }
        }
        root.addView(
            continueBtn,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(12)
            }
        )

        return root
    }

    private fun startBreathAnimation() {
        pulseAnim = ObjectAnimator.ofFloat(breathRing, "scaleX", 0.92f, 1.16f).apply {
            duration = 4_000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                breathRing.scaleY = v
            }
            start()
        }
    }

    private fun abortAndGoHome() {
        if (targetPkg.isNotEmpty()) {
            scStore.recordReclaimedAction(targetPkg, "friction_aborted", estimatedSavedMin = 10)
        }
        try {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(home)
        } catch (_: Throwable) {
        }
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "extra_pkg"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_DELAY_SEC = "extra_delay_sec"
    }
}
