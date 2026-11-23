package com.demonpet.app.rpg

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * DemonMom RPG v10.0 — True Monster Girl Quest-Grade Tactical Combat Subsystems:
 * 1) 4 Elemental Spirits (Zéphyr / Gaïa / Ondine / Ignis) with real elemental weakness/resistance multipliers
 *    and unique active battle passives + Quad-Elemental Overdrive!
 * 2) Adaptive Witch Anti-Spam Brain: repeating the same action category builds 35% -> 65% -> 80% Adaptation Armor
 *    AND triggers Adaptation Counter + Boss Posture Regen!
 * 3) Battlefield Weather / Eclipse Aura (4 dynamic arenas affecting damage, MP, Embrace & Guard!).
 * 4) Procedural Combat Sound FX Synth (subtle, toggleable ToneGenerator cues for Parry, Break, Spirit, Pact!).
 */
object WitchCombatSystem {

    enum class ElementalSpirit(
        val id: String,
        val displayName: String,
        val shortBadge: String,
        val colorHex: Int,
        val mpCost: Int,
        val passiveDesc: String,
        val unlockHint: String
    ) {
        NONE("none", "Aucun Esprit", "", 0xFF64748B.toInt(), 0, "", ""),
        ZEPHYR(
            "zephyr", "🌬️ Zéphyr (Vent)", "🌬️Zéphyr", 0xFF10B981.toInt(), 14,
            "+35 % Esquive physique · Perce les Mirages · +12 SP/parade · QTE +0,8 s !",
            "Débloqué dès le Chapitre 1 !"
        ),
        GAIA(
            "gaia", "🪨 Gaïa (Terre)", "🪨Gaïa", 0xFFD97706.toInt(), 16,
            "-30 % dégâts subis · +40 % Brise-Posture · Immunité Givre & Fuite tactile !",
            "Bats Morgane (Boss Ch.1) ou débloque dans la Forge !"
        ),
        ONDINE(
            "ondine", "💧 Ondine (Eau)", "💧Ondine", 0xFF0284C7.toInt(), 16,
            "Contre auto Anti-Capture · +14 PM/tour · Immunité Charme & Hypnose !",
            "Bats Séraphina (Boss Ch.2) ou débloque dans la Forge !"
        ),
        IGNIS(
            "ignis", "🔥 Ignis (Feu)", "🔥Ignis", 0xFFDC2626.toInt(), 18,
            "+45 % Dégâts offensifs · Enflamme la Sorcière (Brûlure 3t + coupe Vol de Vie) !",
            "Bats Vespera (Boss Ch.3) ou débloque dans la Forge !"
        )
    }

    enum class BattleWeather(
        val label: String,
        val shortBadge: String,
        val effectSummary: String,
        val colorHex: Int
    ) {
        CLEAR("Ciel Runique", "", "", 0xFF64748B.toInt()),
        BLOOD_ECLIPSE(
            "🩸 Éclipse Sanglante", "🩸Éclipse",
            "Dégâts ×1.25 des deux côtés · Vampiriques +15 % soin", 0xFFBE123C.toInt()
        ),
        MIND_FOG(
            "🌫️ Brume Mentale", "🌫️Brume",
            "+7 % Emprise/tour · 🧘 Méditation la dissipe !", 0xFF7E22CE.toInt()
        ),
        ETHER_STORM(
            "⚡ Tempête d'Éther", "⚡Éther",
            "+12 PM/tour · Sorts & Esprits ×1.30 !", 0xFF0284C7.toInt()
        ),
        HEAVY_GRAVITY(
            "🪨 Gravité Lourde", "🪨Gravité",
            "Esquive réduite · Brise-Posture ×1.35 !", 0xFFB45309.toInt()
        )
    }

    /** Elemental affinity chart: returns (damageMultiplier, tagOrNull) for Active Spirit vs Witch! */
    fun spiritElementMultiplier(spirit: ElementalSpirit, witch: WitchCatalog.WitchDef): Pair<Float, String?> {
        if (spirit == ElementalSpirit.NONE) return 1.0f to null
        val e = witch.element.lowercase()
        val arch = witch.archetype
        return when (spirit) {
            ElementalSpirit.IGNIS -> when {
                e.contains("liane") || e.contains("forêt") || e.contains("soie") || e.contains("givre") ||
                    e.contains("pourpre") || arch == WitchCatalog.Archetype.BINDER || arch == WitchCatalog.Archetype.DRAINER ->
                    1.55f to "🔥 FAIBLESSE FEU (×1.55) !"
                e.contains("eau") || e.contains("flamme") -> 0.72f to "💧 Résiste au Feu (×0.72)"
                else -> 1.25f to "🔥 Aura Ignis"
            }
            ElementalSpirit.ONDINE -> when {
                e.contains("flamme") || e.contains("foudre") || e.contains("velours") ||
                    arch == WitchCatalog.Archetype.CHARMER || arch == WitchCatalog.Archetype.HYPNOTIST ->
                    1.50f to "💧 FAIBLESSE EAU (×1.50) !"
                e.contains("givre") || e.contains("eau") -> 0.75f to "❄️ Résiste à l'Eau (×0.75)"
                else -> 1.15f to "💧 Flux Ondine"
            }
            ElementalSpirit.ZEPHYR -> when {
                e.contains("brume") || e.contains("miroir") || e.contains("prisme") || e.contains("songe") ||
                    arch == WitchCatalog.Archetype.ILLUSIONIST ->
                    1.55f to "🌬️ FAIBLESSE VENT (×1.55) !"
                e.contains("obsidienne") || e.contains("acier") -> 0.75f to "🪨 Résiste au Vent (×0.75)"
                else -> 1.18f to "🌬️ Rafale Zéphyr"
            }
            ElementalSpirit.GAIA -> when {
                e.contains("foudre") || e.contains("temps") || e.contains("chrono") || e.contains("rune") ||
                    arch == WitchCatalog.Archetype.CHRONO || arch == WitchCatalog.Archetype.INQUISITOR ->
                    1.55f to "🪨 FAIBLESSE TERRE (×1.55) !"
                e.contains("vent") || e.contains("liane") -> 0.75f to "🌿 Résiste à la Terre (×0.75)"
                else -> 1.18f to "🪨 Poids Gaïa"
            }
            ElementalSpirit.NONE -> 1.0f to null
        }
    }

    fun recommendedSpiritFor(witch: WitchCatalog.WitchDef): ElementalSpirit {
        return listOf(
            ElementalSpirit.IGNIS, ElementalSpirit.ONDINE, ElementalSpirit.ZEPHYR, ElementalSpirit.GAIA
        ).maxByOrNull { spiritElementMultiplier(it, witch).first } ?: ElementalSpirit.ZEPHYR
    }

    /**
     * True Anti-Spam Adaptive Resistance Brain:
     * Tracks recent player categories; repeating an action builds heavy resistance (35% -> 65% -> 80%)
     * and triggers Witch counter-punishment unless the player switches categories or uses Holy Water!
     */
    class AdaptiveBrain {
        private val counts = HashMap<String, Int>()
        private var lastKey: String = ""
        var chainMultiplier: Float = 1.0f
            private set

        data class EvalResult(
            val damageMult: Float,
            val adaptationPct: Int,
            val triggeredPunish: Boolean,
            val feedbackNote: String
        )

        fun evaluate(categoryKey: String, isOffensive: Boolean): EvalResult {
            val prevCount = counts[categoryKey] ?: 0
            val repeatedSame = (categoryKey == lastKey)

            // Decay other categories slightly when player diversifies!
            for (k in counts.keys.toList()) {
                if (k != categoryKey) {
                    val v = (counts[k] ?: 0) - 1
                    if (v <= 0) counts.remove(k) else counts[k] = v
                }
            }

            val nextCount = (prevCount + if (repeatedSame) 2 else 1).coerceAtMost(4)
            counts[categoryKey] = nextCount
            lastKey = categoryKey

            if (!repeatedSame) {
                chainMultiplier = (chainMultiplier + 0.22f).coerceAtMost(2.30f)
                return EvalResult(chainMultiplier, 0, false, "")
            }

            val adaptPct = when {
                nextCount >= 4 -> 80
                nextCount == 3 -> 65
                nextCount == 2 -> 35
                else -> 0
            }

            return if (isOffensive && adaptPct >= 65) {
                chainMultiplier = 1.0f
                val dmgMul = (1.0f - adaptPct / 100f).coerceAtLeast(0.20f)
                EvalResult(
                    damageMult = dmgMul,
                    adaptationPct = adaptPct,
                    triggeredPunish = true,
                    feedbackNote = "🧠 ADAPTATION -$adaptPct % ! Elle lit ton spam, contre-attaque et régénère +24 Posture !"
                )
            } else if (isOffensive && adaptPct >= 35) {
                EvalResult(
                    damageMult = 0.72f,
                    adaptationPct = adaptPct,
                    triggeredPunish = false,
                    feedbackNote = "🧠 Elle s'adapte (-35 %) — varie tes Arts ou Esprits au prochain tour !"
                )
            } else {
                EvalResult(chainMultiplier, 0, false, "")
            }
        }

        fun resetAll() {
            counts.clear()
            lastKey = ""
            chainMultiplier = 1.0f
        }

        fun highestAdaptationTag(): String? {
            val top = counts.maxByOrNull { it.value } ?: return null
            if (top.value < 2) return null
            val pct = when {
                top.value >= 4 -> 80
                top.value == 3 -> 65
                else -> 35
            }
            val label = when (top.key) {
                "STRIKE" -> "Estocade"
                "BLADE" -> "Brise-Garde"
                "DANCE" -> "Danse Lames"
                "GUARD" -> "Garde"
                "MAGIC" -> "Magie"
                else -> top.key
            }
            return "🧠 Adaptée $label -$pct%"
        }
    }

    /** Lightweight procedural sound cues for crisp RPG impact feedback (respects audio toggle!). */
    object SoundFx {
        fun play(enabled: Boolean, kind: String) {
            if (!enabled) return
            Thread {
                var tg: ToneGenerator? = null
                try {
                    tg = ToneGenerator(AudioManager.STREAM_MUSIC, 55)
                    when (kind) {
                        "parry" -> { tg.startTone(ToneGenerator.TONE_PROP_ACK, 120); Thread.sleep(130); tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 160) }
                        "break" -> { tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 180) }
                        "spirit" -> { tg.startTone(ToneGenerator.TONE_PROP_BEEP, 90); Thread.sleep(100); tg.startTone(ToneGenerator.TONE_PROP_ACK, 140) }
                        "victory" -> { tg.startTone(ToneGenerator.TONE_PROP_ACK, 110); Thread.sleep(120); tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 220) }
                        "curse" -> { tg.startTone(ToneGenerator.TONE_PROP_NACK, 200) }
                        else -> { tg.startTone(ToneGenerator.TONE_PROP_BEEP, 60) }
                    }
                    Thread.sleep(260)
                } catch (_: Throwable) {
                } finally {
                    try { tg?.release() } catch (_: Throwable) {}
                }
            }.start()
        }
    }
}
