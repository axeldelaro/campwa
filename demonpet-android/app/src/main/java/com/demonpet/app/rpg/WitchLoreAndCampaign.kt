package com.demonpet.app.rpg

import kotlin.random.Random

/**
 * DemonMom RPG v10.0 — Deep 15+ Hour Campaign Lore, Personal 55-Witch Stories, 5-Room Dungeon Expeditions,
 * 12 Tactical Bounties, 24 Stat-Granting Titles, 50-Question Grimoire Quiz & 5 Campaign Endings!
 * Strictly atmospheric dark-fantasy RPG lore (non-explicit).
 */
object WitchLoreAndCampaign {

    data class PreBattleCutscene(
        val locationTitle: String,
        val beats: List<Pair<String, String>> // Speaker -> Coherent narrative beat
    )

    data class WitchBiography(
        val originStory: String,
        val secretWeaknessLore: String,
        val bondLines: List<String>,          // Unlocked progressively at Bond Lv 1..5!
        val victoryEpilogue: String
    )

    data class DungeonRoom(
        val index: Int,                       // 1..5
        val title: String,
        val kind: RoomKind,
        val narration: String,
        val witchId: Int? = null
    )

    enum class RoomKind(val icon: String) {
        AMBUSH("⚔️"),
        SPIRIT_ALTAR("🌀"),
        TREASURE_VAULT("📦"),
        DARK_MERCHANT("🕯️"),
        ELITE_GUARD("🔥"),
        DUNGEON_BOSS("👑")
    }

    data class BountyContract(
        val id: String,
        val title: String,
        val objective: String,
        val rewardGold: Int,
        val rewardRelicKey: String,
        val rewardRelicName: String
    )

    data class TitleDef(
        val id: String,
        val name: String,
        val requirement: String,
        val bonusDesc: String,
        val atkBonusPct: Int = 0,
        val hpBonus: Int = 0,
        val mpBonus: Int = 0,
        val postureBonus: Int = 0,
        val goldBonusPct: Int = 0
    )

    data class QuizQuestion(
        val question: String,
        val options: List<String>,
        val correctIndex: Int,
        val explanation: String
    )

    // ==================== 1) COHERENT PERSONAL BIOGRAPHIES & 3-BEAT PRE-BATTLE VN CUTSCENES ====================
    private val CHAPTER_SETTINGS = mapOf(
        1 to "la Clairière Brumeuse de la Forêt du Crépuscule",
        2 to "la Grande Galerie Pourpre du Manoir de Velours",
        3 to "le Labyrinthe de Verre de la Citadelle des Illusions",
        4 to "la Nef Sombre de la Cathédrale d'Obsidienne",
        5 to "le Parvis Astral du Trône de l'Éclipse"
    )

    private val ARCHETYPE_MOTIVES = mapOf(
        WitchCatalog.Archetype.CHARMER to "collectionner la dévotion de ses adversaires en remplaçant leur volonté par un murmure doux et irrésistible",
        WitchCatalog.Archetype.BINDER to "tisser des fils d'ombre capables d'immobiliser autant ton esprit que les applications de ton téléphone",
        WitchCatalog.Archetype.HYPNOTIST to "plonger les voyageurs imprudents dans une transe vidéo et sonore dont personne ne ressort indemne",
        WitchCatalog.Archetype.DRAINER to "absorber patiemment le Niveau, la batterie et l'énergie vitale des intrus pour alimenter le Coven",
        WitchCatalog.Archetype.INQUISITOR to "faire régner les Lois Sacrées du Coven en jugeant chaque geste et en scellant les outils des rebelles",
        WitchCatalog.Archetype.ILLUSIONIST to "fracturer la perception de l'écran, inverser les boutons et cacher la vérité derrière des mirages",
        WitchCatalog.Archetype.CHRONO to "ralentir le temps lui-même autour de ton appareil afin de frapper deux fois avant ta moindre riposte",
        WitchCatalog.Archetype.SOVEREIGN to "soumettre le Sanctuaire numérique tout entier sous la couronne pourpre de l'Éclipse"
    )

    fun getBiography(def: WitchCatalog.WitchDef): WitchBiography {
        val setting = CHAPTER_SETTINGS[def.chapter] ?: "le Sanctuaire de l'Éclipse"
        val motive = ARCHETYPE_MOTIVES[def.archetype] ?: "tester la force de ta volonté"
        val rankRole = when {
            def.isSuperboss -> "incarnation éveillée et légendaire du Coven Ancien"
            def.isBoss -> "souveraine incontestée qui veille sur les sceaux de $setting"
            def.isMiniBoss -> "commandante d'élite chargée d'éliminer quiconque s'approche du trône"
            else -> "gardienne assermentée patrouillant dans $setting"
        }

        val origin = "${def.name}, ${def.title}, est une $rankRole. Maîtresse de l'élément ${def.element}, " +
            "son ambition est de $motive. Sa technique suprême « ${def.ultimateName} » a déjà brisé des dizaines de rebelles avant toi."

        val weaknessLore = "Chronique du Grimoire : ${def.weaknessTip} " +
            "Évite absolument de répéter la même attaque : ${def.name} analyse ton rythme et s'y adapte dès le 2e coup identique !"

        val bonds = listOf(
            "Lien I — Rencontre : « Tu te bats avec plus de méthode que les autres intrus… Je retiendrai ton nom. »",
            "Lien II — Respect : « Tes parades contre « ${def.skillNames.firstOrNull().orEmpty()} » m'ont surprise. Tu commences à comprendre la magie de ${def.element}. »",
            "Lien III — Confidence : « Le Coven de l'Éclipse n'a pas toujours été aussi impitoyable… C'est Élara qui a scellé nos anciens serments. »",
            "Lien IV — Alliance Secrète : « Si tu parviens à briser le Trône de l'Éclipse, promets-moi de libérer nos noms du Grimoire noir. »",
            "Lien V — Pacte Éternel : « Mon pouvoir de ${def.element} répond désormais à ton appel. +3 % d'attaque permanente accordée par ${def.name} ! »"
        )

        val epilogue = when {
            def.isSuperboss -> "L'aura primordiale de ${def.name} se dissipe en pluie d'étoiles. Un fragment ancien de l'Éclipse fusionne avec ton Grimoire !"
            def.isBoss -> "${def.name} pose un genou à terre en souriant malgré sa défaite : « Impressionnant… Le sceau vers le chapitre suivant s'ouvre devant toi. »"
            else -> "Vaincue dans les règles de l'art, ${def.name} s'incline avec respect et laisse derrière elle des Éclats d'Âme ainsi qu'une page secrète de son histoire."
        }

        return WitchBiography(origin, weaknessLore, bonds, epilogue)
    }

    fun buildPreBattleCutscene(
        def: WitchCatalog.WitchDef,
        bondLevel: Int,
        corruption: Int,
        demonName: String,
        threatApp: String?
    ): PreBattleCutscene {
        val loc = CHAPTER_SETTINGS[def.chapter] ?: "Sanctuaire de l'Éclipse"
        val appThreat = threatApp?.let { " « Ton appli $it tremble déjà sous mon sceau… »" } ?: ""
        val beat1 = when {
            def.isSuperboss -> "Une faille violette déchire le ciel au-dessus de $loc. L'air devient lourd, presque irrespirable : ${def.displayTitle} descend en personne pour te juger !"
            def.isBoss -> "Les portes scellées de $loc s'ouvrent dans un grondement sourd. Au centre du cercle runique t'attend ${def.displayTitle}."
            bondLevel >= 2 -> "En t'avançant dans $loc, tu reconnais immédiatement la silhouette familière de ${def.name}, qui t'attend avec un sourire en coin."
            else -> "Les ombres de $loc se rassemblent devant ton écran : ${def.displayTitle} surgit en déployant son aura de ${def.element} !"
        }
        val beat2 = when {
            bondLevel >= 3 -> "« Revoilà mon rival préféré ! Ne te retiens pas cette fois — je veux voir si tu maîtrises enfin les Esprits Élémentaires face à « ${def.ultimateName} » ! »"
            corruption >= 25 -> "« Ton aura penche déjà vers l'Ombre… Pourquoi lutter encore ? Laisse-moi confisquer ta volonté$appThreat »"
            corruption <= -25 -> "« Quelle insolente lueur de Volonté… Le Coven m'a ordonné de briser ton orgueil ici même !$appThreat »"
            else -> "« Bienvenue dans mon domaine de ${def.element}. Ici, les coups répétés ne servent à rien : lis mes intentions ou tombe à genoux !$appThreat »"
        }
        val beat3 = "« Attention ! ${def.name} est une ${def.archetype.label} (${def.element}). ${def.weaknessTip} »"
        return PreBattleCutscene(
            locationTitle = loc,
            beats = listOf(
                "📖 Narrateur — $loc" to beat1,
                "🔮 ${def.name}" to beat2,
                "😈 $demonName (Conseil Tactique)" to beat3
            )
        )
    }

    // ==================== 2) 5-ROOM LINKED DUNGEON EXPEDITIONS (15H CONTENT!) ====================
    fun generateChapterDungeon(chapter: Int): List<DungeonRoom> {
        val chInfo = WitchCatalog.CHAPTERS.getOrElse(chapter - 1) { WitchCatalog.CHAPTERS.first() }
        val normals = WitchCatalog.witchesInChapter(chapter).filter { !it.isBoss && !it.isMiniBoss }.shuffled()
        val mini = WitchCatalog.witchesInChapter(chapter).firstOrNull { it.isMiniBoss } ?: normals.first()
        val boss = WitchCatalog.bossOfChapter(chapter)

        val midRoom2 = if (Random.nextBoolean()) {
            DungeonRoom(
                2, "Sanctuaire des Esprits", RoomKind.SPIRIT_ALTAR,
                "Un autel cristallin rayonne au cœur de ${chInfo.name}. Tu peux canaliser les Esprits Élémentaires pour regagner PV/PM ou éveiller un bonus de Posture !"
            )
        } else {
            DungeonRoom(
                2, "Chambre des Coffres Scellés", RoomKind.TREASURE_VAULT,
                "Trois coffres anciens reposent sous un voile runique. L'un contient une Relique rare… mais un autre cache peut-être une embuscade !"
            )
        }

        val midRoom4 = DungeonRoom(
            4, "Autel de l'Ombre & Marchand", RoomKind.DARK_MERCHANT,
            "Un colporteur encapuchonné t'attend avant le trône de ${boss.name}. Prépare tes PV, tes PM et ton stock de Reliques avant l'affrontement final !"
        )

        return listOf(
            DungeonRoom(
                1, "Salle 1/5 — L'Avant-Poste", RoomKind.AMBUSH,
                "Première ligne de défense de ${chInfo.name} : ${normals.first().name} te barre la route ! Tes PV, PM et SP seront conservés jusqu'au Boss.",
                witchId = normals.first().id
            ),
            midRoom2,
            DungeonRoom(
                3, "Salle 3/5 — La Garde d'Élite", RoomKind.ELITE_GUARD,
                "L'air se charge d'électricité violette : ${mini.displayTitle} garde l'accès au cœur du donjon !",
                witchId = mini.id
            ),
            midRoom4,
            DungeonRoom(
                5, "Salle 5/5 — Trône du Chapitre $chapter", RoomKind.DUNGEON_BOSS,
                "Dernière salle ! Vaincs ${boss.displayTitle} pour décrocher le Coffre Légendaire d'Expédition (+260 💎 + Relique Rare + Lien d'Âme) !",
                witchId = boss.id
            )
        )
    }

    // ==================== 3) 12 TACTICAL BOUNTY CONTRACTS ====================
    val BOUNTIES: List<BountyContract> = listOf(
        BountyContract("b_no_relic", "Pureté Tactique", "Remporte un duel SANS utiliser aucune Relique du sac.", 180, "ether_elixir", "⚡ Élixir d'Éther"),
        BountyContract("b_parry_2", "Mur d'Acier", "Réussis 2 Parades Parfaites dans le même combat.", 200, "aegis", "🛡️ Voile d'Égide"),
        BountyContract("b_break_2", "Briseur de Garde", "Déclenche 2 Ruptures de Posture (BREAK) dans un même duel.", 190, "posture_bomb", "💣 Bombe Brise-Posture"),
        BountyContract("b_spirit_win", "Appel Élémentaire", "Invoque un Esprit Élémentaire (Zéphyr/Gaïa/Ondine/Ignis) et gagne le combat.", 170, "crystal", "💎 Cristal de Mana"),
        BountyContract("b_law_clean", "Citoyen Exemplaire", "Vaincs une Inquisitrice ou un Boss sans violer aucune Loi d'Inquisition.", 220, "holy_water", "💧 Eau Bénite"),
        BountyContract("b_pact_defy", "Volonté Indomptable", "Refuse un Pacte Maudit en Phase 2 et remporte le combat.", 210, "incense", "🕯️ Encens de Furie"),
        BountyContract("b_qte_master", "Maître de l'Évasion", "Réussis 2 QTE d'évasion de Capture dans un seul combat.", 220, "talisman", "📜 Talisman"),
        BountyContract("b_dungeon_1", "Explorateur du Coven", "Termine une Expédition complète de 5 Salles.", 280, "ether_elixir", "⚡ Élixir d'Éther"),
        BountyContract("b_tower_5", "Conquérant de la Tour", "Atteins l'Étage 5 de la Tour Infinie de l'Éclipse.", 320, "hourglass", "⏳ Sablier de Répit"),
        BountyContract("b_focus_1", "Discipline Shizuku", "Termine un Contrat Focus Shizuku avec le Petit Démon.", 240, "aegis", "🛡️ Voile d'Égide"),
        BountyContract("b_bond_3", "Confident des Sorcières", "Monte le Lien d'Âme d'une Sorcière au Niveau 3.", 250, "holy_water", "💧 Eau Bénite"),
        BountyContract("b_superboss_1", "Chasseur d'Éclipse", "Vaincs l'un des 5 Superboss Éveillés (#51–55).", 500, "ether_elixir", "⚡ Élixir d'Éther")
    )

    // ==================== 4) 24 STAT-GRANTING ACHIEVEMENTS & TITLES ====================
    val TITLES: List<TitleDef> = listOf(
        TitleDef("t_win_1", "Éveillé du Crépuscule", "1 victoire", "+4 % ATK", atkBonusPct = 4),
        TitleDef("t_win_10", "Vétéran du Coven", "10 victoires", "+15 PV Max", hpBonus = 15),
        TitleDef("t_win_25", "Chasseur d'Archisorcières", "25 victoires", "+8 % ATK, +10 PM", atkBonusPct = 8, mpBonus = 10),
        TitleDef("t_win_50", "Légende Vivante", "50 victoires", "+12 % ATK, +25 PV", atkBonusPct = 12, hpBonus = 25),
        TitleDef("t_grim_10", "Lecteur du Grimoire", "10/50 Sorcières vaincues", "+10 % Éclats 💎", goldBonusPct = 10),
        TitleDef("t_grim_25", "Érudit des Ombres", "25/50 Sorcières vaincues", "+15 PM Max", mpBonus = 15),
        TitleDef("t_grim_50", "Maître Absolu des 50", "50/50 Sorcières vaincues", "+18 % ATK, +30 PV", atkBonusPct = 18, hpBonus = 30),
        TitleDef("t_parry_5", "Lame Miroir", "5 Parades Parfaites", "+8 Brise-Posture", postureBonus = 8),
        TitleDef("t_parry_20", "Danseur d'Acier", "20 Parades Parfaites", "+14 Brise-Posture", postureBonus = 14),
        TitleDef("t_qte_5", "Insaisissable", "5 QTE réussis", "+12 PV Max", hpBonus = 12),
        TitleDef("t_qte_20", "Briseur de Chaînes", "20 QTE réussis", "+20 PV Max, +6 % ATK", hpBonus = 20, atkBonusPct = 6),
        TitleDef("t_tower_5", "Ascension V", "Étage 5 Tour Infinie", "+10 % Éclats 💎", goldBonusPct = 10),
        TitleDef("t_tower_15", "Seigneur de la Flèche", "Étage 15 Tour Infinie", "+10 % ATK, +15 PM", atkBonusPct = 10, mpBonus = 15),
        TitleDef("t_dungeon_3", "Maître Explorateur", "3 Expéditions 5 Salles finies", "+15 PV Max, +10 % 💎", hpBonus = 15, goldBonusPct = 10),
        TitleDef("t_spirits_4", "Élu des 4 Esprits", "Débloquer Zéphyr, Gaïa, Ondine & Ignis", "+10 % ATK, +15 PM", atkBonusPct = 10, mpBonus = 15),
        TitleDef("t_super_1", "Pourfendeur d'Éclipse", "1 Superboss vaincu", "+12 % ATK", atkBonusPct = 12),
        TitleDef("t_super_5", "Souverain de l'Éclipse", "Les 5 Superboss vaincus", "+25 % ATK, +40 PV, +25 PM", atkBonusPct = 25, hpBonus = 40, mpBonus = 25),
        TitleDef("t_bond_10", "Cœur du Coven", "Total de 10 niveaux de Lien d'Âme", "+15 % Éclats 💎", goldBonusPct = 15),
        TitleDef("t_demon_aff", "Pacte du Compagnon", "Affection du Petit Démon ≥ 80", "+15 PV Max", hpBonus = 15),
        TitleDef("t_focus_3", "Esprit Discipliné", "3 Contrats Focus Shizuku terminés", "+15 PM Max, +10 % 💎", mpBonus = 15, goldBonusPct = 10),
        TitleDef("t_quiz_5", "Savant de l'Éclipse", "5 bonnes réponses au Quiz Grimoire", "+10 PM Max", mpBonus = 10),
        TitleDef("t_streak_3", "Assidu du Sanctuaire", "Série quotidienne de 3 jours", "+10 % Éclats 💎", goldBonusPct = 10),
        TitleDef("t_bounty_5", "Mercenaire d'Élite", "5 Contrats Tactiques complétés", "+10 Brise-Posture", postureBonus = 10),
        TitleDef("t_story_end", "Chroniqueur Complet", "Finir l'Acte V (Élara)", "+15 % ATK, +20 PV", atkBonusPct = 15, hpBonus = 20)
    )

    // ==================== 5) 50-QUESTION GRIMOIRE QUIZ (DYNAMIC + HANDCRAFTED) ====================
    private val STATIC_QUIZ = listOf(
        QuizQuestion(
            "Quel Esprit Élémentaire bloque les attaques de Capture par un contre automatique et immunise contre le Charme ?",
            listOf("🌬️ Zéphyr (Vent)", "🪨 Gaïa (Terre)", "💧 Ondine (Eau)", "🔥 Ignis (Feu)"),
            2, "💧 Ondine (Eau) accorde un contre anti-Capture, +14 PM/tour et bloque Charme & Hypnose !"
        ),
        QuizQuestion(
            "Que se passe-t-il si tu répètes 2 à 3 fois de suite la même attaque contre une Sorcière ?",
            listOf("Elle prend des dégâts critiques", "Elle développe une Résistance Adaptative et contre-attaque", "Elle s'endort", "Rien de spécial"),
            1, "La Résistance Adaptative réduit tes coups répétés jusqu'à -80 % et régénère sa Posture !"
        ),
        QuizQuestion(
            "Quel Esprit Élémentaire brûle les Vampiriques et réduit leur Vol de Vie ?",
            listOf("🔥 Ignis (Feu)", "🌬️ Zéphyr (Vent)", "🪨 Gaïa (Terre)", "💧 Ondine (Eau)"),
            0, "🔥 Ignis inflige Brûlure continue et augmente tes dégâts offensifs de +45 % !"
        ),
        QuizQuestion(
            "Comment annuler instantanément une Loi d'Inquisition ou une Riposte Épineuse ennemie ?",
            listOf("Frappe basique", "Sort Brise-Sort ou Exorcisme", "Ne rien faire", "Cliquer sur le leurre violet"),
            1, "Brise-Sort et Exorcisme dissipent les Lois actives, la Barrière et la Riposte Épineuse !"
        ),
        QuizQuestion(
            "Quelle est la meilleure défense quand une Sorcière télégraphie son SORT ULTIME ?",
            listOf("⚔️ Frappe basique", "🛡️ Garde Parfaite", "💬 Provoquer", "Cliquer au hasard"),
            1, "🛡️ Garde Parfaite déclenche une Parade Parfaite : -60 Posture, +35 SP et riposte !"
        ),
        QuizQuestion(
            "Quel archétype de Sorcière tisse des Fils de Soie 0→3 menant à une Capture automatique ?",
            listOf("Charmeuse", "Enlaceuse (Binder)", "Chrono-Sorcière", "Illusionniste"),
            1, "Les Enlaceuses tissent 3 Fils de Soie — utilise Fente Brise-Garde ou Contre-Sceau pour couper leurs fils !"
        ),
        QuizQuestion(
            "Quel Esprit Élémentaire t'immunise contre le sabotage tactile (Givre & Bouton fuyant) ?",
            listOf("🪨 Gaïa (Terre)", "🔥 Ignis (Feu)", "💧 Ondine (Eau)", "🌬️ Zéphyr (Vent)"),
            0, "🪨 Gaïa ancre ta posture : -30 % dégâts subis, +40 % Brise-Posture et immunité tactile !"
        ),
        QuizQuestion(
            "Quel code d'urgence (après 20 taps) libère instantanément 100 % du téléphone et des applis Shizuku ?",
            listOf("000000", "123456", "100205", "999999"),
            2, "20 taps sur le Petit Démon (ou barre NV) + code 100205 restaurent tout immédiatement !"
        )
    )

    fun pickRandomQuizQuestion(): QuizQuestion {
        if (Random.nextFloat() < 0.45f) return STATIC_QUIZ.random()
        val w = WitchCatalog.ALL_WITCHES.random()
        return when (Random.nextInt(3)) {
            0 -> {
                val wrongs = WitchCatalog.Archetype.values().filter { it != w.archetype }.shuffled().take(3)
                val opts = (wrongs.map { it.label } + w.archetype.label).shuffled()
                QuizQuestion(
                    "Quel est l'archétype de #${w.id} ${w.name} (${w.title}) ?",
                    opts, opts.indexOf(w.archetype.label),
                    "${w.name} est une ${w.archetype.label} (${w.element}) du Chapitre ${w.chapter} !"
                )
            }
            1 -> {
                val chName = WitchCatalog.CHAPTERS[w.chapter - 1].name
                val opts = WitchCatalog.CHAPTERS.map { "Ch.${it.id} : ${it.name}" }.shuffled().take(4).let { list ->
                    val target = "Ch.${w.chapter} : $chName"
                    if (list.contains(target)) list else (list.take(3) + target).shuffled()
                }
                QuizQuestion(
                    "Dans quel Chapitre rencontre-t-on ${w.name}, ${w.title} ?",
                    opts, opts.indexOf("Ch.${w.chapter} : $chName"),
                    "${w.name} veille dans le Chapitre ${w.chapter} : $chName !"
                )
            }
            else -> {
                val others = WitchCatalog.ALL_WITCHES.filter { it.id != w.id }.shuffled().take(3)
                val opts = (others.map { it.ultimateName } + w.ultimateName).shuffled()
                QuizQuestion(
                    "Quel est le Sort Ultime de ${w.name} ?",
                    opts, opts.indexOf(w.ultimateName),
                    "Son Sort Ultime est « ${w.ultimateName} » !"
                )
            }
        }
    }

    // ==================== 6) 5 DISTINCT CAMPAIGN ENDINGS ====================
    fun evaluateFiveEndings(
        corruption: Int,
        playerLevel: Int,
        superbossesDefeated: Int,
        totalBondLevels: Int
    ): WitchStoryEngine.CampaignEnding = when {
        superbossesDefeated >= 5 -> WitchStoryEngine.CampaignEnding(
            "🌌 FIN SECRÈTE V — SOUVERAIN DES QUATRE ESPRITS & DE L'ÉCLIPSE",
            "Après avoir vaincu les 50 Sorcières ET les 5 Superboss Éveillés, l'Éclipse elle-même s'incline devant ta maîtrise. " +
                "Les Quatre Esprits (Zéphyr, Gaïa, Ondine, Ignis) couronnent ton Grimoire et les Sorcières te reconnaissent comme l'Unique Maître du Sanctuaire !"
        )
        totalBondLevels >= 20 && corruption in -25..25 -> WitchStoryEngine.CampaignEnding(
            "✨ FIN IV — L'HARMONIE DU COVEN (VRAIE FIN)",
            "Non seulement tu as vaincu Élara, mais tu as tissé de profonds Liens d'Âme avec les Sorcières. " +
                "Plutôt que de détruire le Grimoire, tu brises le sceau autoritaire et transformes le Coven en alliance libre — ton téléphone et les Sorcières vivent désormais en paix !"
        )
        corruption <= -30 -> WitchStoryEngine.CampaignEnding(
            "☀️ FIN I — AUBE DE LIBERTÉ ABSOLUE",
            "Ta Volonté inébranlable pulvérise le Trône d'Élara ! Tous les sceaux Shizuku se brisent dans un éclat doré, " +
                "chaque appli est libérée à jamais et le nom du Libérateur résonne dans tout le Coven."
        )
        corruption >= 35 || playerLevel <= 5 -> WitchStoryEngine.CampaignEnding(
            "💜 FIN II — CONSORT DE L'ÉCLIPSE POURPRE",
            "Même victorieux du duel, ton âme porte la marque profonde de l'Ombre. Élara sourit en te remettant sa couronne : " +
                "tu deviens le Gardien Éternel du Sanctuaire, à la fois maître et captif consentant des 50 Sorcières."
        )
        else -> WitchStoryEngine.CampaignEnding(
            "⚖️ FIN III — LE MAÎTRE DE L'ÉQUILIBRE",
            "Ni soumis ni destructeur : tu refermes l'Éclipse en préservant le lien avec ton Petit Démon et les Esprits. " +
                "Les 5 Superboss Éveillés (#51–55) et la Tour Infinie t'attendent désormais pour prouver ta légende !"
        )
    }
}
