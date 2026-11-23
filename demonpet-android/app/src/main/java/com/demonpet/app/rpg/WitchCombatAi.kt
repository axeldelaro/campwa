package com.demonpet.app.rpg

import com.demonpet.app.ai.WitchSpriteEngine
import com.demonpet.app.ai.WitchSpriteEngine.AttackKind
import kotlin.random.Random

/**
 * Dedicated 8-Archetype Tactical AI Brain for DemonMom RPG v9.0:
 * - Each of the 8 Witch archetypes wields unique real Shizuku system curses, enforceable Inquisition Laws,
 *   Unskippable Forced Video Trances, Silk Threads, Chrono Hourglass, Relic Confiscation, SP Leech & Heal Inversion!
 */
object WitchCombatAi {

    enum class InquisitionLaw(val label: String, val shortTag: String) {
        NONE("", ""),
        NO_STRIKE("⚖️ LOI : Frappe Interdite ! (Sentence si violée)", "⚖️NO-FRAPPE"),
        NO_MAGIC("⚖️ LOI : Sorts PM Interdits ! (Sentence si violée)", "⚖️NO-MAGIE"),
        NO_ITEMS("⚖️ LOI : Reliques Confisquées !", "⚖️NO-SAC"),
        NO_GUARD("⚖️ LOI : Garde Interdite ! (Utilise Contre ou Égide)", "⚖️NO-GARDE")
    }

    enum class SpecialPhoneSpell(val label: String) {
        NONE(""),
        FORCED_VIDEO_TRANCE("🎬 TRANSE VIDÉO FORCÉE (agit en arrière-plan !)"),
        SEIZE_APP_HOSTAGE("🔒 PRISE D'OTAGE D'APPLI (Shizuku)"),
        CATEGORY_HOSTAGE_SWEEP("🔒🔒 DOUBLE OTAGE D'APPLIS (Shizuku !)"),
        SYSTEM_SEAL_RAID("🛒 SCEAU PLAY STORE / CAMÉRA"),
        AUDIO_SILENCE_CURSE("🔇 SCEAU DE SILENCE AUDIO"),
        CHRONO_SLOWMO("⏳ RALENTISSEMENT ANDROID ×3,5 !"),
        FAKE_LOW_BATTERY("🔋 ILLUSION BATTERIE CRITIQUE 3 % !"),
        SCREEN_DENSITY_WARP("🪞 DISTORSION ZOOM ÉCRAN !"),
        ISOLATION_NOTIF_SEAL("🔕 ISOLEMENT : NOTIFS COUPÉES + DND !"),
        MIC_CLIPBOARD_SEAL("🎤 SCEAU MICRO & PRESSE-PAPIERS !"),
        ECLIPSE_DARK_POWER("🌑 MODE SOMBRE + ÉCONOMIE ÉNERGIE !"),
        SCREEN_SLEEP_SNAP("😴 CLAQUEMENT D'ÉCRAN ÉTEINT !"),
        CONFISCATE_RELIC("🎒 VOL DE RELIQUE TEMPORAIRE"),
        SP_LEECH("⚡ SIPHON DE VOLONTÉ (-30 SP)"),
        HEAL_INVERSION("🧪 MALÉDICTION INVERSION DE SOIN")
    }

    data class CombatSnapshot(
        val witchDef: WitchCatalog.WitchDef,
        val profile: WitchSpriteEngine.WitchProfile,
        val turnNumber: Int,
        val bossPhase: Int,
        val witchShield: Boolean,
        val witchMirage: Boolean,
        val silkThreads: Int,
        val chronoHourglass: Int,
        val playerHpRatio: Float,
        val playerMp: Int,
        val playerSp: Int,
        val playerLevel: Int,
        val playerCaptured: Boolean,
        val playerCharmed: Boolean,
        val playerHypno: Boolean,
        val playerSiphon: Boolean,
        val activeLaw: InquisitionLaw,
        val videoTranceCount: Int
    )

    data class PlannedIntent(
        val attack: WitchSpriteEngine.WitchAttack,
        val phoneSpell: SpecialPhoneSpell = SpecialPhoneSpell.NONE,
        val lawToEnact: InquisitionLaw = InquisitionLaw.NONE,
        val telegraphLine: String = attack.telegraphHint
    )

    fun archetypePassiveSummary(arch: WitchCatalog.Archetype): String = when (arch) {
        WitchCatalog.Archetype.CHARMER -> "Passif : Sous Charme tes frappes la soignent + Isolement Notifs !"
        WitchCatalog.Archetype.BINDER -> "Passif : Toile 0→3 = Capture auto + Double Otage d'applis !"
        WitchCatalog.Archetype.HYPNOTIST -> "Passif : Vidéo Hypnotique Forcée + Isolement DND + Brouillard !"
        WitchCatalog.Archetype.DRAINER -> "Passif : Vol de Vie + Illusion Batterie 3 % + Inversion Soin !"
        WitchCatalog.Archetype.INQUISITOR -> "Passif : Lois Sacrées + Sceau Micro/Presse-papiers & Play Store !"
        WitchCatalog.Archetype.ILLUSIONIST -> "Passif : Mirage + Distorsion Zoom Écran + Mélange tactile !"
        WitchCatalog.Archetype.CHRONO -> "Passif : Sablier 4→0 + Ralentissement Android ×3,5 + Givre ×3 !"
        WitchCatalog.Archetype.SOVEREIGN -> "Passif : 3 Phases Royales (Épinglage, Vidéo, Double Otage, Éclipse) !"
    }

    fun tacticalWeaknessLabel(arch: WitchCatalog.Archetype): String = when (arch) {
        WitchCatalog.Archetype.CHARMER -> "🎯 Faiblesse : 🧘 Méditation puis 🌟 Exorcisme"
        WitchCatalog.Archetype.BINDER -> "🎯 Faiblesse : 🔥 Lame & ⚡ Contre coupent ses Fils"
        WitchCatalog.Archetype.HYPNOTIST -> "🎯 Faiblesse : 🧘 Méditation perce Brouillard & Transe"
        WitchCatalog.Archetype.DRAINER -> "🎯 Faiblesse : 🔮 Amulette + Burst Combo ×1.8"
        WitchCatalog.Archetype.INQUISITOR -> "🎯 Faiblesse : Respecte sa Loi orange → Brise sa Posture !"
        WitchCatalog.Archetype.ILLUSIONIST -> "🎯 Faiblesse : 🌟 Exorcisme détruit ses Mirages"
        WitchCatalog.Archetype.CHRONO -> "🎯 Faiblesse : 🛡️ Parade Parfaite recule son Sablier +2 !"
        WitchCatalog.Archetype.SOVEREIGN -> "🎯 Faiblesse : Brise sa Posture (×2.0) puis 💥 Jugement 100 SP !"
    }

    fun chooseTacticalIntent(s: CombatSnapshot): PlannedIntent {
        val attacks = s.profile.attacks
        fun byKind(k: AttackKind): WitchSpriteEngine.WitchAttack =
            attacks.firstOrNull { it.kind == k } ?: attacks.first()

        if (s.playerCaptured) {
            return PlannedIntent(
                attack = byKind(AttackKind.BIND_ESCALATE),
                phoneSpell = if (s.bossPhase >= 2 && Random.nextFloat() < 0.40f) SpecialPhoneSpell.SEIZE_APP_HOSTAGE else SpecialPhoneSpell.NONE,
                telegraphLine = "⛓️⛓️ ÉTREINTE PROFONDE ! Libère-toi par QTE ou 📜 Talisman avant l'Emprise Totale !"
            )
        }

        // Bosses & Phase 2+ Witches weave Thorn Counter Traps and Weather Shifts!
        if (s.bossPhase >= 2 && s.turnNumber % 4 == 3 && Random.nextFloat() < 0.55f) {
            return PlannedIntent(
                attack = byKind(AttackKind.THORN_COUNTER),
                phoneSpell = SpecialPhoneSpell.NONE,
                telegraphLine = "🌹 Posture de RIPOSTE ÉPINEUSE ! Ne frappe pas direct — utilise 🌀 Brise-Sort ou 🛡️ Garde !"
            )
        }
        if (s.turnNumber == 3 || (s.bossPhase >= 2 && s.turnNumber % 6 == 2)) {
            return PlannedIntent(
                attack = byKind(AttackKind.WEATHER_SHIFT),
                phoneSpell = if (s.witchDef.isSuperboss) SpecialPhoneSpell.ECLIPSE_DARK_POWER else SpecialPhoneSpell.NONE,
                telegraphLine = "🌑 ALTÉRATION DE DOMAINE ! La météo du champ de bataille va basculer !"
            )
        }

        if (!s.playerCaptured && s.turnNumber >= 4 && s.turnNumber % 5 == 0) {
            val spPhone = when {
                s.bossPhase >= 2 && s.videoTranceCount < 2 && Random.nextFloat() < 0.50f -> SpecialPhoneSpell.FORCED_VIDEO_TRANCE
                s.witchDef.isBoss && s.bossPhase >= 3 -> SpecialPhoneSpell.SCREEN_SLEEP_SNAP
                else -> SpecialPhoneSpell.NONE
            }
            return PlannedIntent(
                attack = s.profile.ultimateAttack,
                phoneSpell = spPhone,
                telegraphLine = "⚠️ SORT ULTIME « ${s.witchDef.ultimateName} » ! Utilise 🛡️ GARDE pour une PARADE PARFAITE !"
            )
        }

        return when (s.witchDef.archetype) {
            WitchCatalog.Archetype.CHARMER -> when {
                !s.playerCharmed && s.turnNumber % 3 == 1 -> PlannedIntent(
                    byKind(AttackKind.ENCHANT_CHARM),
                    if (s.turnNumber >= 3) SpecialPhoneSpell.ISOLATION_NOTIF_SEAL else SpecialPhoneSpell.NONE,
                    telegraphLine = "💖 Envoûtement Charme + Isolement des Notifications ! (🧘 Méditation !)"
                )
                s.playerCharmed && s.videoTranceCount < 2 && s.turnNumber >= 3 -> PlannedIntent(
                    byKind(AttackKind.SIPHON_DRAIN), SpecialPhoneSpell.FORCED_VIDEO_TRANCE,
                    telegraphLine = "🎬 Profitant de son Charme, elle lance une TRANSE VIDÉO FORCÉE !"
                )
                s.playerSp >= 50 -> PlannedIntent(byKind(AttackKind.STRIKE), SpecialPhoneSpell.SP_LEECH)
                else -> PlannedIntent(byKind(AttackKind.BIND_CAPTURE), SpecialPhoneSpell.CONFISCATE_RELIC)
            }

            WitchCatalog.Archetype.BINDER -> when {
                s.silkThreads >= 2 -> PlannedIntent(
                    byKind(AttackKind.BIND_CAPTURE),
                    if (s.bossPhase >= 2) SpecialPhoneSpell.CATEGORY_HOSTAGE_SWEEP else SpecialPhoneSpell.SEIZE_APP_HOSTAGE,
                    telegraphLine = "⛓️ TOILE 2/3 — CAPTURE + OTAGE D'APPLIS ! (⚡ Contre-Sceau ou 🔥 Lame !)"
                )
                !s.witchShield && s.turnNumber % 4 == 2 -> PlannedIntent(byKind(AttackKind.BARRIER_SHIELD))
                s.turnNumber >= 4 && s.videoTranceCount == 0 -> PlannedIntent(
                    byKind(AttackKind.BIND_CAPTURE), SpecialPhoneSpell.FORCED_VIDEO_TRANCE,
                    telegraphLine = "🎬 Ligotage + VIDÉO FORCÉE pendant qu'elle agit en arrière-plan !"
                )
                else -> PlannedIntent(byKind(AttackKind.STRIKE), SpecialPhoneSpell.CONFISCATE_RELIC)
            }

            WitchCatalog.Archetype.HYPNOTIST -> when {
                !s.playerHypno && s.turnNumber % 3 == 1 -> PlannedIntent(
                    byKind(AttackKind.ENCHANT_HYPNO), SpecialPhoneSpell.ISOLATION_NOTIF_SEAL,
                    telegraphLine = "🌀 Hypnose + Isolement DND ! (🧘 Méditation immunise !)"
                )
                s.videoTranceCount < 2 && s.turnNumber >= 2 -> PlannedIntent(
                    byKind(AttackKind.ENCHANT_HYPNO), SpecialPhoneSpell.FORCED_VIDEO_TRANCE,
                    telegraphLine = "🎬 TRANSE VIDÉO FORCÉE IMMINENTE ! (Actions système en arrière-plan !)"
                )
                else -> PlannedIntent(byKind(AttackKind.SIPHON_DRAIN), SpecialPhoneSpell.AUDIO_SILENCE_CURSE)
            }

            WitchCatalog.Archetype.DRAINER -> when {
                !s.playerSiphon -> PlannedIntent(
                    byKind(AttackKind.SIPHON_DRAIN), SpecialPhoneSpell.FAKE_LOW_BATTERY,
                    telegraphLine = "🩸 Marque de Siphon + Illusion Batterie 3 % !"
                )
                s.playerHpRatio < 0.60f && s.turnNumber % 2 == 0 -> PlannedIntent(byKind(AttackKind.STRIKE), SpecialPhoneSpell.HEAL_INVERSION)
                s.playerSp >= 45 -> PlannedIntent(byKind(AttackKind.SIPHON_DRAIN), SpecialPhoneSpell.SP_LEECH)
                else -> PlannedIntent(byKind(AttackKind.BIND_CAPTURE), SpecialPhoneSpell.SEIZE_APP_HOSTAGE)
            }

            WitchCatalog.Archetype.INQUISITOR -> {
                val nextLaw = when ((s.turnNumber / 2) % 4) {
                    0 -> InquisitionLaw.NO_STRIKE; 1 -> InquisitionLaw.NO_MAGIC
                    2 -> InquisitionLaw.NO_ITEMS; else -> InquisitionLaw.NO_GUARD
                }
                when {
                    s.activeLaw == InquisitionLaw.NONE -> PlannedIntent(
                        byKind(AttackKind.SEAL_MAGIC), SpecialPhoneSpell.MIC_CLIPBOARD_SEAL,
                        lawToEnact = nextLaw,
                        telegraphLine = "⚖️ DÉCRET : « ${nextLaw.label} » + Sceau Micro/Presse-papiers !"
                    )
                    !s.witchShield && s.turnNumber % 3 == 0 -> PlannedIntent(byKind(AttackKind.BARRIER_SHIELD), SpecialPhoneSpell.SYSTEM_SEAL_RAID)
                    s.turnNumber >= 4 && s.videoTranceCount == 0 -> PlannedIntent(byKind(AttackKind.SEAL_MAGIC), SpecialPhoneSpell.FORCED_VIDEO_TRANCE)
                    else -> PlannedIntent(byKind(AttackKind.STRIKE), SpecialPhoneSpell.SEIZE_APP_HOSTAGE)
                }
            }

            WitchCatalog.Archetype.ILLUSIONIST -> when {
                !s.witchMirage -> PlannedIntent(
                    byKind(AttackKind.MIRAGE_CLONE),
                    if (s.bossPhase >= 2) SpecialPhoneSpell.SCREEN_DENSITY_WARP else SpecialPhoneSpell.FAKE_LOW_BATTERY,
                    telegraphLine = "🪞 Mirage + Distorsion visuelle système ! (🌟 Exorcisme perce !)"
                )
                s.videoTranceCount < 2 && s.turnNumber >= 3 -> PlannedIntent(byKind(AttackKind.ENCHANT_CHARM), SpecialPhoneSpell.FORCED_VIDEO_TRANCE)
                else -> PlannedIntent(byKind(AttackKind.BIND_CAPTURE), SpecialPhoneSpell.CONFISCATE_RELIC)
            }

            WitchCatalog.Archetype.CHRONO -> when {
                s.chronoHourglass <= 1 -> PlannedIntent(
                    byKind(AttackKind.TIME_DOUBLE), SpecialPhoneSpell.CHRONO_SLOWMO,
                    telegraphLine = "⏳ SABLIER À 0 — ARRÊT DU TEMPS + RALENTISSEMENT ANDROID ×3,5 ! (🛡️ GARDE !)"
                )
                !s.witchShield && s.turnNumber % 3 == 1 -> PlannedIntent(byKind(AttackKind.BARRIER_SHIELD), SpecialPhoneSpell.CHRONO_SLOWMO)
                else -> PlannedIntent(byKind(AttackKind.SIPHON_DRAIN), SpecialPhoneSpell.HEAL_INVERSION)
            }

            WitchCatalog.Archetype.SOVEREIGN -> when {
                s.bossPhase == 1 && s.activeLaw == InquisitionLaw.NONE -> PlannedIntent(
                    byKind(AttackKind.SEAL_MAGIC), SpecialPhoneSpell.ECLIPSE_DARK_POWER,
                    lawToEnact = if (Random.nextBoolean()) InquisitionLaw.NO_STRIKE else InquisitionLaw.NO_ITEMS,
                    telegraphLine = "👑 DÉCRET ROYAL + Mode Éclipse Sombre/Éco !"
                )
                s.bossPhase >= 2 && s.videoTranceCount == 0 -> PlannedIntent(
                    byKind(AttackKind.TIME_DOUBLE), SpecialPhoneSpell.FORCED_VIDEO_TRANCE,
                    telegraphLine = "👑🎬 TRANSE VIDÉO IMPÉRIALE ! (Actions Shizuku en arrière-plan !)"
                )
                s.bossPhase >= 2 && s.turnNumber % 3 == 0 -> PlannedIntent(
                    s.profile.ultimateAttack, SpecialPhoneSpell.CATEGORY_HOSTAGE_SWEEP,
                    telegraphLine = "👑⚠️ ULTIME SOUVERAIN + DOUBLE OTAGE D'APPLIS ! Utilise 🛡️ GARDE !"
                )
                !s.witchShield && s.turnNumber % 4 == 2 -> PlannedIntent(byKind(AttackKind.BARRIER_SHIELD), SpecialPhoneSpell.CHRONO_SLOWMO)
                else -> PlannedIntent(byKind(AttackKind.BIND_CAPTURE), SpecialPhoneSpell.ISOLATION_NOTIF_SEAL)
            }
        }
    }
}
