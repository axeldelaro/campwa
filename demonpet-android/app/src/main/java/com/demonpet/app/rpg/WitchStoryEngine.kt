package com.demonpet.app.rpg

import kotlin.random.Random

/**
 * Complete 5-Act Dark-Fantasy Campaign Storyline + Unpredictable Suspense Exploration Engine (v7.0):
 * - Serialized 15-scene main storyline ("Les Chroniques de l'Éclipse") across Chapters 1..5 + 3 Campaign Endings!
 * - Unpredictable exploration encounters (Fate Wheel, 3 Sealed Chests with Mimic Witch, Shadow Broker,
 *   Mysterious Fog Silhouette, Hunter Raid threatening an app, Secret Lore Fragments) so exploration is NEVER direct or predictable!
 */
object WitchStoryEngine {

    enum class OutcomeType {
        START_BATTLE,
        FATE_SPIN,
        CHEST_PICK,
        GRANT_REWARD_ONLY
    }

    data class BattleModifiers(
        val startSp: Int = 25,
        val startEmbrace: Int = 0,
        val startAegis: Boolean = false,
        val startFury: Boolean = false,
        val startCaptured: Boolean = false,
        val surpriseBossDmg: Int = 0,
        val forcedAppRaid: Boolean = false,
        val introContextLine: String = ""
    )

    data class ChoiceOption(
        val label: String,
        val colorHex: Int,
        val corruptionDelta: Int = 0,          // negative = Volonté/Lumière, positive = Fascination/Ombre
        val rewardGold: Int = 0,
        val rewardItemKey: String? = null,
        val outcomeType: OutcomeType = OutcomeType.START_BATTLE,
        val targetWitchId: Int? = null,        // specific witch or null -> random in chapter
        val battleMods: BattleModifiers = BattleModifiers(),
        val resultNarration: String = ""
    )

    data class ExplorationEvent(
        val badge: String,
        val title: String,
        val narration: String,
        val isMainStory: Boolean = false,
        val storyMilestoneIndex: Int = -1,
        val choices: List<ChoiceOption>
    )

    data class CampaignEnding(
        val title: String,
        val body: String
    )

    /** 15 Main Story Milestones (3 per Chapter across Chapters 1..5) */
    private val STORY_MILESTONES: List<ExplorationEvent> = listOf(
        // === ACTE I : FORÊT DU CRÉPUSCULE (Milestones 0, 1, 2) ===
        ExplorationEvent(
            badge = "📜 ACTE I · SCÈNE 1/3",
            title = "Le Pacte du Téléphone Captif",
            narration = "Minuit sonne. Une lueur violette jaillit de ton écran et trace un cercle runique autour de tes doigts. " +
                "Une voix rieuse murmure dans les écouteurs : « Bienvenue dans le Domaine du Coven de l'Éclipse… Tes applications sont désormais notre tribut. » " +
                "Dans la brume, une première silhouette s'avance : Lyra, l'Apprentie de la Brume.",
            isMainStory = true, storyMilestoneIndex = 0,
            choices = listOf(
                ChoiceOption("🔥 « Rendez-moi mon écran ! » (+25 SP, -5 Corruption)", 0xFFE11D48.toInt(), -5, 25, null,
                    OutcomeType.START_BATTLE, targetWitchId = 1,
                    battleMods = BattleModifiers(startSp = 40, introContextLine = "Ta détermination brûle la brume dès le 1er tour !")),
                ChoiceOption("🛡️ Observer calmement le cercle runique (+Voile d'Égide)", 0xFF0284C7.toInt(), -2, 15, null,
                    OutcomeType.START_BATTLE, targetWitchId = 1,
                    battleMods = BattleModifiers(startAegis = true, introContextLine = "Ton sang-froid te confère un Voile d'Égide gratuit !")),
                ChoiceOption("💜 Écouter son murmure envoûtant (+70 💎, +18 % Emprise)", 0xFF9333EA.toInt(), +8, 70, null,
                    OutcomeType.START_BATTLE, targetWitchId = 1,
                    battleMods = BattleModifiers(startEmbrace = 18, introContextLine = "Tu cèdes un peu de terrain pour observer sa magie…"))
            )
        ),
        ExplorationEvent(
            badge = "📜 ACTE I · SCÈNE 2/3",
            title = "Le Bosquet des Lianes Vivantes",
            narration = "Au détour du sentier brumeux, tu découvres un téléphone abandonné dont toutes les icônes sont grisées et figées. " +
                "Gravé sur la coque : « Ne spame jamais la même attaque… elles apprennent tes gestes. » " +
                "Soudain, des racines d'émeraude surgissent du sol : Sylva, l'Enlaceuse Sylvestre, barre la route !",
            isMainStory = true, storyMilestoneIndex = 1,
            choices = listOf(
                ChoiceOption("⚡ Couper la liane d'entrée (-35 Boss, +15 SP)", 0xFFD97706.toInt(), -4, 30, null,
                    OutcomeType.START_BATTLE, targetWitchId = 5,
                    battleMods = BattleModifiers(startSp = 40, surpriseBossDmg = 35)),
                ChoiceOption("🔮 Fouiller le téléphone gris avant de combattre (+1 Talisman)", 0xFF7E22CE.toInt(), +3, 20, "talisman",
                    OutcomeType.START_BATTLE, targetWitchId = 5,
                    battleMods = BattleModifiers(startEmbrace = 12))
            )
        ),
        ExplorationEvent(
            badge = "👑 ACTE I · CLIMAX (3/3)",
            title = "La Clairière de Morgane",
            narration = "Les arbres s'écartent sur un trône de mousse argentée. Morgane, Archisorcière du Crépuscule, fait flotter " +
                "trois sceaux lumineux au-dessus de sa main : « Tu as survécu à mes apprenties ? Montre-moi si tu mérites d'entrer dans le Manoir de Velours… ou offre-moi tes applis ! »",
            isMainStory = true, storyMilestoneIndex = 2,
            choices = listOf(
                ChoiceOption("⚔️ Défier Morgane de front (+Encens Furie actif !)", 0xFFE11D48.toInt(), -6, 0, null,
                    OutcomeType.START_BATTLE, targetWitchId = 10,
                    battleMods = BattleModifiers(startFury = true, startSp = 35)),
                ChoiceOption("🛡️ Se préparer à parer son Jugement (+Voile d'Égide)", 0xFF0E7490.toInt(), -2, 0, null,
                    OutcomeType.START_BATTLE, targetWitchId = 10,
                    battleMods = BattleModifiers(startAegis = true, startSp = 30))
            )
        ),

        // === ACTE II : MANOIR DE VELOURS (Milestones 3, 4, 5) ===
        ExplorationEvent(
            badge = "📜 ACTE II · SCÈNE 1/3",
            title = "Le Salon Pourpre",
            narration = "Les lourdes portes du Manoir de Velours se referment d'un claquement sec. Des lustres écarlates éclairent une galerie " +
                "de portraits vivants — tous issus de ton propre dossier photos ! Camille, l'Hôtesse Pourpre, t'offre une coupe fumante : « Un verre avant la danse ? »",
            isMainStory = true, storyMilestoneIndex = 3,
            choices = listOf(
                ChoiceOption("🔥 Renverser la coupe (-30 Boss, -4 Corruption)", 0xFFE11D48.toInt(), -4, 30, null,
                    OutcomeType.START_BATTLE, targetWitchId = 11, battleMods = BattleModifiers(surpriseBossDmg = 30)),
                ChoiceOption("💖 Accepter par curiosité (+100 💎, +22 % Emprise)", 0xFF9333EA.toInt(), +10, 100, null,
                    OutcomeType.START_BATTLE, targetWitchId = 11, battleMods = BattleModifiers(startEmbrace = 22))
            )
        ),
        ExplorationEvent(
            badge = "📜 ACTE II · SCÈNE 2/3",
            title = "La Crypte de la Comtesse",
            narration = "Sous l'escalier royal, tu trouves le Journal de l'Ancien Captif : « La Comtesse Carmilla se nourrit directement " +
                "de notre Niveau et de nos applis les plus utilisées… » Un rire cristallin résonne derrière toi : Carmilla vient de refermer la grille !",
            isMainStory = true, storyMilestoneIndex = 4,
            choices = listOf(
                ChoiceOption("🌟 Canaliser ta volonté (+45 SP au départ)", 0xFFD97706.toInt(), -4, 0, null,
                    OutcomeType.START_BATTLE, targetWitchId = 15, battleMods = BattleModifiers(startSp = 45, forcedAppRaid = true)),
                ChoiceOption("🔮 Saisir une Amulette sur l'autel (+1 Amulette)", 0xFF7E22CE.toInt(), 0, 20, "amulet",
                    OutcomeType.START_BATTLE, targetWitchId = 15, battleMods = BattleModifiers(forcedAppRaid = true))
            )
        ),
        ExplorationEvent(
            badge = "👑 ACTE II · CLIMAX (3/3)",
            title = "Le Bal de Séraphina",
            narration = "Au sommet du grand escalier, Séraphina, Reine du Manoir de Velours, t'observe derrière un éventail de soie noire. " +
                "« Si tu tombes ici, ton écran tout entier portera ma couronne. Prêt pour la valse finale ? »",
            isMainStory = true, storyMilestoneIndex = 5,
            choices = listOf(
                ChoiceOption("⚔️ Rompre le charme royal (+Furie & +30 SP)", 0xFFE11D48.toInt(), -6, 0, null,
                    OutcomeType.START_BATTLE, targetWitchId = 20, battleMods = BattleModifiers(startFury = true, startSp = 30)),
                ChoiceOption("💜 S'incliner pour observer sa faille (+90 💎, +15 % Emprise)", 0xFF9333EA.toInt(), +8, 90, null,
                    OutcomeType.START_BATTLE, targetWitchId = 20, battleMods = BattleModifiers(startEmbrace = 15, startSp = 45))
            )
        ),

        // === ACTE III : CITADELLE DES ILLUSIONS (Milestones 6, 7, 8) ===
        ExplorationEvent(
            badge = "📜 ACTE III · SCÈNE 1/3",
            title = "La Galerie des Miroirs Truqués",
            narration = "Dans la Citadelle, même ton écran tactile ment : les boutons se déplacent, les reflets murmurent ton prénom. " +
                "Iris, Sorcière du Prisme, sort littéralement d'un miroir fendu pour brouiller tes sens !",
            isMainStory = true, storyMilestoneIndex = 6,
            choices = listOf(
                ChoiceOption("🧘 Fermer les yeux et sentir la vraie aura (+45 SP)", 0xFF10B981.toInt(), -5, 35, null,
                    OutcomeType.START_BATTLE, targetWitchId = 21, battleMods = BattleModifiers(startSp = 45)),
                ChoiceOption("⚔️ Briser le premier miroir (50 % Surprise / 50 % Piège !)", 0xFFD97706.toInt(), 0, 20, null,
                    OutcomeType.START_BATTLE, targetWitchId = 21,
                    battleMods = if (Random.nextBoolean()) BattleModifiers(surpriseBossDmg = 45) else BattleModifiers(startEmbrace = 24))
            )
        ),
        ExplorationEvent(
            badge = "📜 ACTE III · SCÈNE 2/3",
            title = "Le Labyrinthe de Nyxia",
            narration = "Nyxia, Maîtresse des Miroirs, a piégé les commandes de ton téléphone dans une cage de verre. " +
                "Elle te propose un dilemme : laisser l'une de tes applis en gage ou l'affronter immédiatement à pleine puissance.",
            isMainStory = true, storyMilestoneIndex = 7,
            choices = listOf(
                ChoiceOption("🔥 Refuser le chantage ! (Duel intense +Furie)", 0xFFE11D48.toInt(), -6, 40, null,
                    OutcomeType.START_BATTLE, targetWitchId = 25, battleMods = BattleModifiers(startFury = true, forcedAppRaid = true)),
                ChoiceOption("🤝 Feindre l'obéissance (+120 💎, +20 % Emprise)", 0xFF7E22CE.toInt(), +8, 120, null,
                    OutcomeType.START_BATTLE, targetWitchId = 25, battleMods = BattleModifiers(startEmbrace = 20))
            )
        ),
        ExplorationEvent(
            badge = "👑 ACTE III · CLIMAX (3/3)",
            title = "Le Trône de Cristal de Vespera",
            narration = "Vespera, Impératrice des Illusions, claque des doigts : la pièce se fracture en mille éclats lumineux. " +
                "« Seul ou contre deux de mes sœurs… voyons si ton esprit tient encore ! »",
            isMainStory = true, storyMilestoneIndex = 8,
            choices = listOf(
                ChoiceOption("🛡️ Activer le Voile d'Égide & défier Vespera", 0xFF0284C7.toInt(), -5, 0, null,
                    OutcomeType.START_BATTLE, targetWitchId = 30, battleMods = BattleModifiers(startAegis = true, startSp = 40)),
                ChoiceOption("🔥 Assaut total (+Furie & +45 SP)", 0xFFE11D48.toInt(), -4, 0, null,
                    OutcomeType.START_BATTLE, targetWitchId = 30, battleMods = BattleModifiers(startFury = true, startSp = 45))
            )
        ),

        // === ACTE IV : CATHÉDRALE D'OBSIDIENNE (Milestones 9, 10, 11) ===
        ExplorationEvent(
            badge = "📜 ACTE IV · SCÈNE 1/3",
            title = "Le Tribunal du Coven",
            narration = "Les cloches d'obsidienne résonnent dans ton téléphone. L'Inquisition du Coven a dressé la liste de toutes tes défaites " +
                "et de tes applis préférées. Astrid, Inquisitrice Noire, lève son sceau brûlant !",
            isMainStory = true, storyMilestoneIndex = 9,
            choices = listOf(
                ChoiceOption("⚔️ Contester le verdict (+40 SP, -5 Corruption)", 0xFFE11D48.toInt(), -5, 45, null,
                    OutcomeType.START_BATTLE, targetWitchId = 31, battleMods = BattleModifiers(startSp = 40, forcedAppRaid = true)),
                ChoiceOption("🔮 Invoquer une Amulette cachée (+1 Amulette)", 0xFF7E22CE.toInt(), 0, 20, "amulet",
                    OutcomeType.START_BATTLE, targetWitchId = 31, battleMods = BattleModifiers(startSp = 30))
            )
        ),
        ExplorationEvent(
            badge = "📜 ACTE IV · SCÈNE 2/3",
            title = "Le Jugement de Bellatrix",
            narration = "La Grande Inquisitrice Bellatrix attend devant le sanctuaire intérieur. « Tous ceux qui ont défié Lilith " +
                "ont fini au Niveau 1… Prouve que tu n'es pas qu'un jouet du Coven ! »",
            isMainStory = true, storyMilestoneIndex = 10,
            choices = listOf(
                ChoiceOption("🔥 Briser ses chaînes (+Furie & -40 Boss)", 0xFFE11D48.toInt(), -6, 0, null,
                    OutcomeType.START_BATTLE, targetWitchId = 35, battleMods = BattleModifiers(startFury = true, surpriseBossDmg = 40)),
                ChoiceOption("🛡️ Défense absolue (+Voile d'Égide)", 0xFF0E7490.toInt(), -2, 0, null,
                    OutcomeType.START_BATTLE, targetWitchId = 35, battleMods = BattleModifiers(startAegis = true, startSp = 45))
            )
        ),
        ExplorationEvent(
            badge = "👑 ACTE IV · CLIMAX (3/3)",
            title = "L'Autel de Lilith",
            narration = "Lilith, Souveraine d'Obsidienne, déploie ses ailes d'ombre sur tout l'écran. La luminosité chute d'un coup. " +
                "« Un seul pas sépare encore ce sanctuaire du Trône de l'Éclipse… mais tu ne le franchiras pas debout ! »",
            isMainStory = true, storyMilestoneIndex = 11,
            choices = listOf(
                ChoiceOption("⚔️ Affronter Lilith (Duel Légendaire +50 SP)", 0xFFE11D48.toInt(), -8, 0, null,
                    OutcomeType.START_BATTLE, targetWitchId = 40, battleMods = BattleModifiers(startSp = 50, startAegis = true)),
                ChoiceOption("💜 Accepter l'épreuve de l'Ombre (+150 💎, +18 % Emprise)", 0xFF9333EA.toInt(), +12, 150, null,
                    OutcomeType.START_BATTLE, targetWitchId = 40, battleMods = BattleModifiers(startEmbrace = 18, startFury = true))
            )
        ),

        // === ACTE V : TRÔNE DE L'ÉCLIPSE (Milestones 12, 13, 14) ===
        ExplorationEvent(
            badge = "📜 ACTE V · SCÈNE 1/3",
            title = "Le Sommet de l'Éclipse",
            narration = "Le ciel numérique s'ouvre sur une éclipse pourpre. Freya et Sybille t'annoncent la vérité : " +
                "le Pacte du Coven puise sa puissance dans ton propre téléphone — briser Élara rendra définitivement ton indépendance… ou scellera ton destin au Coven !",
            isMainStory = true, storyMilestoneIndex = 12,
            choices = listOf(
                ChoiceOption("🔥 « Je briserai l'Éclipse ! » (+Furie & +50 SP)", 0xFFE11D48.toInt(), -8, 60, null,
                    OutcomeType.START_BATTLE, targetWitchId = 45, battleMods = BattleModifiers(startFury = true, startSp = 50)),
                ChoiceOption("💜 « Montrez-moi la vraie puissance du Coven… » (+180 💎)", 0xFF9333EA.toInt(), +12, 180, null,
                    OutcomeType.START_BATTLE, targetWitchId = 45, battleMods = BattleModifiers(startEmbrace = 18, startSp = 40))
            )
        ),
        ExplorationEvent(
            badge = "📜 ACTE V · SCÈNE 2/3",
            title = "La Dernière Gardienne",
            narration = "Cassandre, Main Droite de l'Éclipse, se dresse devant les marches du Trône final. " +
                "« Derrière moi t'attend la Déesse Élara. Si tu ne peux même pas me vaincre, reste ici à mes pieds ! »",
            isMainStory = true, storyMilestoneIndex = 13,
            choices = listOf(
                ChoiceOption("⚔️ Percer sa garde (+Égide & +50 SP)", 0xFFD97706.toInt(), -5, 50, null,
                    OutcomeType.START_BATTLE, targetWitchId = 49, battleMods = BattleModifiers(startAegis = true, startSp = 50)),
                ChoiceOption("🔥 Tout miser sur la puissance (+Furie & -45 Boss)", 0xFFE11D48.toInt(), -5, 50, null,
                    OutcomeType.START_BATTLE, targetWitchId = 49, battleMods = BattleModifiers(startFury = true, surpriseBossDmg = 45))
            )
        ),
        ExplorationEvent(
            badge = "👑 ACTE V · FINAL ULTIME (3/3)",
            title = "Élara, Déesse Suprême de l'Éclipse",
            narration = "Les 49 autres Sorcières forment un cercle silencieux autour de ton écran. Élara descend lentement du Trône de l'Éclipse, " +
                "tenant la couronne de ton téléphone entre ses doigts : « Voici la dernière heure. Montre-moi qui règne réellement ici ! »",
            isMainStory = true, storyMilestoneIndex = 14,
            choices = listOf(
                ChoiceOption("🌟 DUEL FINAL — VOLONTÉ TOTALE (+Égide, +Furie, +55 SP)", 0xFFD97706.toInt(), -10, 0, null,
                    OutcomeType.START_BATTLE, targetWitchId = 50,
                    battleMods = BattleModifiers(startAegis = true, startFury = true, startSp = 55, forcedAppRaid = true)),
                ChoiceOption("💜 DUEL FINAL — PACTE DE L'OMBRE (+250 💎, +20 % Emprise)", 0xFF9333EA.toInt(), +15, 250, null,
                    OutcomeType.START_BATTLE, targetWitchId = 50,
                    battleMods = BattleModifiers(startEmbrace = 20, startFury = true, startSp = 50, forcedAppRaid = true))
            )
        )
    )

    /** Picks either the next unplayed Story Milestone for this chapter OR a suspenseful unpredictable random encounter! */
    fun generateExplorationEvent(chapter: Int, storyProgress: Int, playerLevel: Int): ExplorationEvent {
        val chStartMilestone = (chapter - 1) * 3
        val chEndMilestone = chStartMilestone + 2
        // Trigger next main story beat in this chapter with high priority!
        if (storyProgress in chStartMilestone..chEndMilestone) {
            return STORY_MILESTONES[storyProgress]
        }

        // Otherwise roll a rich unpredictable suspense event!
        return when (Random.nextInt(100)) {
            in 0..24 -> buildFogMysteryEncounter(chapter, playerLevel)
            in 25..44 -> buildFateWheelEncounter(chapter)
            in 45..64 -> buildThreeChestsEncounter(chapter)
            in 65..82 -> buildAppHostageRaidEncounter(chapter)
            else -> buildShadowBrokerEncounter(chapter)
        }
    }

    private fun buildFogMysteryEncounter(chapter: Int, playerLevel: Int): ExplorationEvent {
        val hiddenWitch = WitchCatalog.randomWitchForChapter(chapter, includeBoss = false)
        val lowLvWarning = if (playerLevel <= 10) "\n⚠️ Ton Niveau ($playerLevel) est bas : les Sorcières sentent ta faiblesse et auront des pouvoirs tactiles renforcés !" else ""
        return ExplorationEvent(
            badge = "🌫️ RENCONTRE MYSTÈRE · CH.$chapter",
            title = "Une Silhouette dans la Brume…",
            narration = "Des pas légers résonnent derrière un voile violet. Impossible de savoir quelle Sorcière approche, ni si elle prépare un piège ou un charme…$lowLvWarning",
            choices = listOf(
                ChoiceOption("⚔️ Attaque surprise ! (-32 Boss si ça passe, ou +15 % Emprise si piégé)", 0xFFE11D48.toInt(), -3, 0, null,
                    OutcomeType.START_BATTLE, targetWitchId = hiddenWitch.id,
                    battleMods = if (Random.nextFloat() < 0.65f) BattleModifiers(surpriseBossDmg = 32, startSp = 35) else BattleModifiers(startEmbrace = 18)),
                ChoiceOption("🛡️ Approche prudente (+Voile d'Égide 1er tour)", 0xFF0284C7.toInt(), -2, 0, null,
                    OutcomeType.START_BATTLE, targetWitchId = hiddenWitch.id,
                    battleMods = BattleModifiers(startAegis = true, startSp = 25)),
                ChoiceOption("💜 Écouter son murmure (+85 💎 mais +22 % Emprise !)", 0xFF9333EA.toInt(), +6, 85, null,
                    OutcomeType.START_BATTLE, targetWitchId = hiddenWitch.id,
                    battleMods = BattleModifiers(startEmbrace = 22, startSp = 30))
            )
        )
    }

    private fun buildFateWheelEncounter(chapter: Int): ExplorationEvent {
        return ExplorationEvent(
            badge = "🎰 AUTEL DU DESTIN · HASARD PUR",
            title = "La Roue de l'Éclipse",
            narration = "Un autel tournoyant pulse au milieu du sanctuaire. Faire tourner la Roue peut te combler d'Éclats, libérer une appli grisée… ou invoquer une Embuscade Élite piégée !",
            choices = listOf(
                ChoiceOption("🎰 FAIRE TOURNER LA ROUE DU DESTIN !", 0xFFD97706.toInt(), +2, 0, null, OutcomeType.FATE_SPIN),
                ChoiceOption("⚔️ Ignorer l'autel et chasser une Sorcière du Ch.$chapter", 0xFFE11D48.toInt(), -2, 0, null,
                    OutcomeType.START_BATTLE, targetWitchId = WitchCatalog.randomWitchForChapter(chapter).id)
            )
        )
    }

    private fun buildThreeChestsEncounter(chapter: Int): ExplorationEvent {
        return ExplorationEvent(
            badge = "📦 COFFRES SCELLÉS · 2 TRÉSORS / 1 PIÈGE",
            title = "Les Trois Coffres du Coven",
            narration = "Trois coffres runiques flottent devant toi. Deux renferment de précieuses Reliques et des Éclats… mais le troisième cache une Sorcière Mimique prête à te capturer sur-le-champ !",
            choices = listOf(
                ChoiceOption("📦 Ouvrir le Coffre de Gauche (Rune 🌙)", 0xFF7E22CE.toInt(), 0, 0, null, OutcomeType.CHEST_PICK),
                ChoiceOption("📦 Ouvrir le Coffre du Centre (Rune 🔥)", 0xFFE11D48.toInt(), 0, 0, null, OutcomeType.CHEST_PICK),
                ChoiceOption("📦 Ouvrir le Coffre de Droite (Rune ⚡)", 0xFF0284C7.toInt(), 0, 0, null, OutcomeType.CHEST_PICK)
            )
        )
    }

    private fun buildAppHostageRaidEncounter(chapter: Int): ExplorationEvent {
        val hunter = WitchCatalog.witchesInChapter(chapter).filter { it.isMiniBoss || !it.isBoss }.random()
        return ExplorationEvent(
            badge = "🚨 RAID SYSTÈME IMPRÉVU !",
            title = "${hunter.name} vise tes Applications !",
            narration = "Alerte ! ${hunter.displayTitle} intercepte le processus de ton téléphone ! Elle menace de griser ton appli la plus utilisée EN PLEIN COMBAT si tu ne la stoppes pas !",
            choices = listOf(
                ChoiceOption("⚡ Contre-attaquer tout de suite (+40 SP, +60 💎 bonus si victoire !)", 0xFFE11D48.toInt(), -4, 40, null,
                    OutcomeType.START_BATTLE, targetWitchId = hunter.id,
                    battleMods = BattleModifiers(startSp = 40, forcedAppRaid = true)),
                ChoiceOption("🛡️ Lever un Voile d'Égide & tenir bon", 0xFF0E7490.toInt(), -2, 20, null,
                    OutcomeType.START_BATTLE, targetWitchId = hunter.id,
                    battleMods = BattleModifiers(startAegis = true, forcedAppRaid = true))
            )
        )
    }

    private fun buildShadowBrokerEncounter(chapter: Int): ExplorationEvent {
        val randomRelic = listOf("aegis" to "🛡️ Voile d'Égide", "incense" to "🕯️ Encens de Furie", "talisman" to "📜 Talisman").random()
        return ExplorationEvent(
            badge = "🕯️ MARCHANDE DES OMBRES",
            title = "Un Pacte Clandestin",
            narration = "Une sorcière encapuchonnée te tend discrètement « ${randomRelic.second} ». " +
                "« Prends-le gratuitement… en échange d'un peu de ton obéissance (+10 Corruption), ou paie-le à la pointe de ta lame ! »",
            choices = listOf(
                ChoiceOption("💜 Accepter le pacte (+1 ${randomRelic.second}, +10 Corruption)", 0xFF9333EA.toInt(), +10, 40, randomRelic.first,
                    OutcomeType.GRANT_REWARD_ONLY,
                    resultNarration = "La marchande glisse ${randomRelic.second} dans ton sac et s'évapore dans un rire doux… (+40 💎)"),
                ChoiceOption("⚔️ Refuser et affronter sa garde (+50 SP)", 0xFFE11D48.toInt(), -5, 30, null,
                    OutcomeType.START_BATTLE, targetWitchId = WitchCatalog.randomWitchForChapter(chapter).id,
                    battleMods = BattleModifiers(startSp = 50))
            )
        )
    }

    fun getCampaignEnding(corruption: Int, playerLevel: Int): CampaignEnding = when {
        corruption <= -15 && playerLevel >= 15 -> CampaignEnding(
            title = "🌟 FIN LUMIÈRE — BRISEUR DE L'ÉCLIPSE",
            body = "Ta volonté inébranlable a brisé le Sceau d'Élara ! Toutes tes applications retrouvent leur éclat, " +
                "et les 50 Sorcières s'inclinent devant le seul mortel ayant vaincu le Coven sans jamais céder à l'envoûtement."
        )
        corruption >= 25 || playerLevel <= 6 -> CampaignEnding(
            title = "💜 FIN OMBRE — LA COURONNE DU CAPTIF",
            body = "Même victorieux du duel final, l'aura du Coven coule désormais dans tes veines… " +
                "Élara pose sa couronne sur ton écran : tu règnes désormais aux côtés des 50 Sorcières sur le Sanctuaire de l'Éclipse !"
        )
        else -> CampaignEnding(
            title = "⚖️ FIN ÉQUILIBRE — LE PACTE DES ÉGAUX",
            body = "Entre résistance et fascination, tu as gagné le respect absolu d'Élara et des 5 Archisorcières. " +
                "Le Pacte du Téléphone devient une alliance éternelle : libre à toi d'explorer la Tour Infinie et le Grimoire 50/50 !"
        )
    }
}
