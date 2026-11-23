package com.demonpet.app.rpg

import com.demonpet.app.ai.WitchSpriteEngine
import com.demonpet.app.ai.WitchSpriteEngine.AttackKind
import kotlin.random.Random

/**
 * Dedicated 8-Archetype Tactical AI Brain for DemonMom RPG v8.0:
 * - Every single Witch archetype follows a genuine stateful battle strategy (NO blind random spam!).
 * - Introduces real enforceable combat Restrictions (Inquisition Laws), Silk Threads (0..3 -> Auto-Capture),
 *   Chrono Hourglass countdown (4..0 -> Time Stop Double Turn), Relic Confiscation, SP Leech,
 *   Heal Inversion, Concrete System Raids, and Unskippable Forced Folder-Video Trances!
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
        SYSTEM_SEAL_RAID("🛒 SCEAU SYSTÈME (Play Store / Caméra / Écran)"),
        AUDIO_SILENCE_CURSE("🔇 SCEAU DE SILENCE AUDIO"),
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
        val silkThreads: Int,             // 0..3 for Binder
        val chronoHourglass: Int,         // 4..0 for Chrono
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
        WitchCatalog.Archetype.CHARMER -> "Passif : Sous Charme, tes frappes la soignent & +10% Emprise/t !"
        WitchCatalog.Archetype.BINDER -> "Passif : Tisse 1 Fil/tour → à 3/3 = Capture automatique !"
        WitchCatalog.Archetype.HYPNOTIST -> "Passif : Lance des Vidéos Hypnotiques forcées + Brouillard !"
        WitchCatalog.Archetype.DRAINER -> "Passif : Vol de vie continu + Siphon SP + Inversion Soin !"
        WitchCatalog.Archetype.INQUISITOR -> "Passif : Édicte des LOIS interdisant une commande (Sentence si violée) !"
        WitchCatalog.Archetype.ILLUSIONIST -> "Passif : Clones Mirage + Mélange tactile & Boutons pièges !"
        WitchCatalog.Archetype.CHRONO -> "Passif : Sablier 4→0 = Arrêt du Temps (Double Tour + Givre) !"
        WitchCatalog.Archetype.SOVEREIGN -> "Passif : 3 Phases Royales (Lois + Vidéo Forcée + Otage Applis) !"
    }

    fun chooseTacticalIntent(s: CombatSnapshot): PlannedIntent {
        val attacks = s.profile.attacks
        fun byKind(k: AttackKind): WitchSpriteEngine.WitchAttack =
            attacks.firstOrNull { it.kind == k } ?: attacks.first()

        // High-priority Boss / High-Turn Ultimate cadence (structured, readable, dramatic!)
        if (!s.playerCaptured && s.turnNumber >= 4 && s.turnNumber % 5 == 0) {
            val spPhone = if (s.bossPhase >= 2 && s.videoTranceCount < 2 && Random.nextFloat() < 0.55f) {
                SpecialPhoneSpell.FORCED_VIDEO_TRANCE
            } else SpecialPhoneSpell.NONE
            return PlannedIntent(
                attack = s.profile.ultimateAttack,
                phoneSpell = spPhone,
                telegraphLine = "⚠️ SORT ULTIME « ${s.witchDef.ultimateName} » ! Utilise 🛡️ GARDE pour une PARADE PARFAITE !"
            )
        }

        return when (s.witchDef.archetype) {
            WitchCatalog.Archetype.CHARMER -> {
                when {
                    !s.playerCharmed && s.turnNumber % 3 == 1 -> PlannedIntent(
                        byKind(AttackKind.ENCHANT_CHARM),
                        SpecialPhoneSpell.NONE,
                        telegraphLine = "💖 Tisse son Envoûtement Charme ! (🧘 Méditation le bloque !)"
                    )
                    s.playerCharmed && s.videoTranceCount < 2 && s.turnNumber >= 3 -> PlannedIntent(
                        byKind(AttackKind.SIPHON_DRAIN),
                        SpecialPhoneSpell.FORCED_VIDEO_TRANCE,
                        telegraphLine = "🎬 Profitant de son Charme, elle prépare une TRANSE VIDÉO FORCÉE !"
                    )
                    s.playerSp >= 50 -> PlannedIntent(
                        byKind(AttackKind.STRIKE),
                        SpecialPhoneSpell.SP_LEECH,
                        telegraphLine = "⚡ Elle vise ta jauge SP pour voler ton Exorcisme ! (🛡️ Garde ou dépense tes SP !)"
                    )
                    else -> PlannedIntent(byKind(AttackKind.BIND_CAPTURE), SpecialPhoneSpell.CONFISCATE_RELIC)
                }
            }

            WitchCatalog.Archetype.BINDER -> {
                when {
                    s.silkThreads >= 2 -> PlannedIntent(
                        byKind(AttackKind.BIND_CAPTURE),
                        if (s.bossPhase >= 2) SpecialPhoneSpell.SEIZE_APP_HOSTAGE else SpecialPhoneSpell.NONE,
                        telegraphLine = "⛓️ TOILE 2/3 — CAPTURE TOTALE IMMINENTE ! (⚡ Contre-Sceau ou 🔥 Lame pour couper les fils !)"
                    )
                    !s.witchShield && s.turnNumber % 4 == 2 -> PlannedIntent(byKind(AttackKind.BARRIER_SHIELD))
                    s.turnNumber >= 4 && s.videoTranceCount == 0 -> PlannedIntent(
                        byKind(AttackKind.BIND_CAPTURE),
                        SpecialPhoneSpell.FORCED_VIDEO_TRANCE,
                        telegraphLine = "🎬 Elle ligote ton écran pour lancer une VIDÉO FORCÉE pendant qu'elle resserre sa toile !"
                    )
                    else -> PlannedIntent(byKind(AttackKind.STRIKE), SpecialPhoneSpell.CONFISCATE_RELIC)
                }
            }

            WitchCatalog.Archetype.HYPNOTIST -> {
                when {
                    !s.playerHypno && s.turnNumber % 3 == 1 -> PlannedIntent(
                        byKind(AttackKind.ENCHANT_HYPNO),
                        SpecialPhoneSpell.AUDIO_SILENCE_CURSE,
                        telegraphLine = "🌀 Spirale Hypnotique + Silence Audio ! (🧘 Méditation immunise !)"
                    )
                    s.videoTranceCount < 2 && s.turnNumber >= 2 -> PlannedIntent(
                        byKind(AttackKind.ENCHANT_HYPNO),
                        SpecialPhoneSpell.FORCED_VIDEO_TRANCE,
                        telegraphLine = "🎬 TRANSE VIDÉO FORCÉE IMMINENTE ! (Elle agira sur ton téléphone pendant la vidéo !)"
                    )
                    else -> PlannedIntent(byKind(AttackKind.SIPHON_DRAIN), SpecialPhoneSpell.SP_LEECH)
                }
            }

            WitchCatalog.Archetype.DRAINER -> {
                when {
                    !s.playerSiphon -> PlannedIntent(
                        byKind(AttackKind.SIPHON_DRAIN),
                        SpecialPhoneSpell.NONE,
                        telegraphLine = "🩸 Prépare Marque de Siphon (auto-soin continu) ! Purifie vite !"
                    )
                    s.playerHpRatio < 0.60f && s.turnNumber % 2 == 0 -> PlannedIntent(
                        byKind(AttackKind.STRIKE),
                        SpecialPhoneSpell.HEAL_INVERSION,
                        telegraphLine = "🧪 Malédiction d'Inversion de Soin + Vol de Vie !"
                    )
                    s.playerSp >= 45 -> PlannedIntent(
                        byKind(AttackKind.SIPHON_DRAIN),
                        SpecialPhoneSpell.SP_LEECH,
                        telegraphLine = "⚡ Elle veut siphoner -30 SP de ta volonté !"
                    )
                    else -> PlannedIntent(
                        byKind(AttackKind.BIND_CAPTURE),
                        if (s.turnNumber >= 3) SpecialPhoneSpell.SEIZE_APP_HOSTAGE else SpecialPhoneSpell.NONE
                    )
                }
            }

            WitchCatalog.Archetype.INQUISITOR -> {
                val nextLaw = when ((s.turnNumber / 2) % 4) {
                    0 -> InquisitionLaw.NO_STRIKE
                    1 -> InquisitionLaw.NO_MAGIC
                    2 -> InquisitionLaw.NO_ITEMS
                    else -> InquisitionLaw.NO_GUARD
                }
                when {
                    s.activeLaw == InquisitionLaw.NONE -> PlannedIntent(
                        byKind(AttackKind.SEAL_MAGIC),
                        SpecialPhoneSpell.SYSTEM_SEAL_RAID,
                        lawToEnact = nextLaw,
                        telegraphLine = "⚖️ Prépare un DÉCRET : « ${nextLaw.label} » !"
                    )
                    !s.witchShield && s.turnNumber % 3 == 0 -> PlannedIntent(byKind(AttackKind.BARRIER_SHIELD), SpecialPhoneSpell.AUDIO_SILENCE_CURSE)
                    s.turnNumber >= 4 && s.videoTranceCount == 0 -> PlannedIntent(
                        byKind(AttackKind.SEAL_MAGIC),
                        SpecialPhoneSpell.FORCED_VIDEO_TRANCE,
                        telegraphLine = "🎬 Rééducation Vidéo Forcée de l'Inquisition !"
                    )
                    else -> PlannedIntent(byKind(AttackKind.STRIKE), SpecialPhoneSpell.SEIZE_APP_HOSTAGE)
                }
            }

            WitchCatalog.Archetype.ILLUSIONIST -> {
                when {
                    !s.witchMirage -> PlannedIntent(
                        byKind(AttackKind.MIRAGE_CLONE),
                        SpecialPhoneSpell.NONE,
                        telegraphLine = "🪞 Va invoquer ses Clones Mirage ! (🧘 Méditation ou 🌟 Exorcisme perce l'illusion)"
                    )
                    s.videoTranceCount < 2 && s.turnNumber >= 3 -> PlannedIntent(
                        byKind(AttackKind.ENCHANT_CHARM),
                        SpecialPhoneSpell.FORCED_VIDEO_TRANCE,
                        telegraphLine = "🎬 Illusion Cinématique Forcée + Mélange de tes touches !"
                    )
                    else -> PlannedIntent(byKind(AttackKind.BIND_CAPTURE), SpecialPhoneSpell.CONFISCATE_RELIC)
                }
            }

            WitchCatalog.Archetype.CHRONO -> {
                when {
                    s.chronoHourglass <= 1 -> PlannedIntent(
                        byKind(AttackKind.TIME_DOUBLE),
                        if (s.videoTranceCount < 2) SpecialPhoneSpell.FORCED_VIDEO_TRANCE else SpecialPhoneSpell.SP_LEECH,
                        telegraphLine = "⏳ SABLIER À 0 — ARRÊT DU TEMPS (Double Frappe) ! Utilise 🛡️ GARDE pour reculer le Sablier !"
                    )
                    !s.witchShield && s.turnNumber % 3 == 1 -> PlannedIntent(byKind(AttackKind.BARRIER_SHIELD))
                    else -> PlannedIntent(byKind(AttackKind.SIPHON_DRAIN), SpecialPhoneSpell.HEAL_INVERSION)
                }
            }

            WitchCatalog.Archetype.SOVEREIGN -> {
                when {
                    s.bossPhase == 1 && s.activeLaw == InquisitionLaw.NONE -> PlannedIntent(
                        byKind(AttackKind.SEAL_MAGIC),
                        SpecialPhoneSpell.SYSTEM_SEAL_RAID,
                        lawToEnact = if (Random.nextBoolean()) InquisitionLaw.NO_STRIKE else InquisitionLaw.NO_ITEMS,
                        telegraphLine = "👑 DÉCRET ROYAL : Sceau Système + Restriction d'Action !"
                    )
                    s.bossPhase >= 2 && s.videoTranceCount == 0 -> PlannedIntent(
                        byKind(AttackKind.TIME_DOUBLE),
                        SpecialPhoneSpell.FORCED_VIDEO_TRANCE,
                        telegraphLine = "👑🎬 TRANSE VIDÉO IMPÉRIALE ! Elle va agir sur ton téléphone pendant la vidéo !"
                    )
                    s.bossPhase >= 2 && s.turnNumber % 3 == 0 -> PlannedIntent(
                        s.profile.ultimateAttack,
                        SpecialPhoneSpell.SEIZE_APP_HOSTAGE,
                        telegraphLine = "👑⚠️ ULTIME SOUVERAIN + OTAGE D'APPLI ! Utilise 🛡️ GARDE !"
                    )
                    !s.witchShield && s.turnNumber % 4 == 2 -> PlannedIntent(byKind(AttackKind.BARRIER_SHIELD))
                    else -> PlannedIntent(byKind(AttackKind.BIND_CAPTURE), SpecialPhoneSpell.SP_LEECH)
                }
            }
        }
    }
}
