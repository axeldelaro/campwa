package com.demonpet.app.rpg

/**
 * Complete Bestiary of 50 Unique Witches across 5 Dark-Fantasy Chapters (40 Witches + 10 Major Bosses).
 * Each Witch automatically receives a randomly assigned AI-segmented sprite from the user's chosen media folder!
 */
object WitchCatalog {

    enum class Archetype(val label: String, val accentHex: Int) {
        CHARMER("Charmeuse", 0xFFE879F9.toInt()),
        BINDER("Enlaceuse", 0xFFF43F5E.toInt()),
        HYPNOTIST("Hypnotiseuse", 0xFFA855F7.toInt()),
        DRAINER("Vampirique", 0xFFDC2626.toInt()),
        INQUISITOR("Inquisitrice", 0xFFEA580C.toInt()),
        ILLUSIONIST("Illusionniste", 0xFF6366F1.toInt()),
        CHRONO("Chrono-Sorcière", 0xFF0EA5E9.toInt()),
        SOVEREIGN("Souveraine", 0xFFF59E0B.toInt())
    }

    data class ChapterInfo(
        val id: Int,
        val name: String,
        val subtitle: String,
        val minLevel: Int,
        val maxLevel: Int
    )

    data class WitchDef(
        val id: Int,                  // 1..50
        val chapter: Int,             // 1..5
        val name: String,
        val title: String,
        val archetype: Archetype,
        val element: String,
        val level: Int,
        val isMiniBoss: Boolean,
        val isBoss: Boolean,
        val maxResolve: Int,
        val rewardGold: Int,
        val rewardXp: Int,
        val skillNames: List<String>,
        val ultimateName: String,
        val weaknessTip: String
    ) {
        val displayTitle: String
            get() = when {
                isBoss -> "👑 BOSS · $name, $title"
                isMiniBoss -> "⚔️ ÉLITE · $name, $title"
                else -> "$name, $title"
            }
    }

    val CHAPTERS = listOf(
        ChapterInfo(1, "Forêt du Crépuscule", "Sentiers brumeux & premiers charmes", 1, 10),
        ChapterInfo(2, "Manoir de Velours", "Salons pourpres & drain d'énergie", 11, 20),
        ChapterInfo(3, "Citadelle des Illusions", "Miroirs hypnotiques & entraves", 21, 30),
        ChapterInfo(4, "Cathédrale d'Obsidienne", "Inquisitrices & sceaux sévères", 31, 40),
        ChapterInfo(5, "Trône de l'Éclipse", "Archisorcières & domination absolue", 41, 50)
    )

    private data class Seed(
        val name: String,
        val title: String,
        val arch: Archetype,
        val elem: String,
        val skills: List<String>,
        val ult: String,
        val tip: String
    )

    private val SEEDS: List<Seed> = listOf(
        // === CHAPITRE 1 : FORÊT DU CRÉPUSCULE (1..10) ===
        Seed("Lyra", "Apprentie de la Brume", Archetype.CHARMER, "Brume 🌫️",
            listOf("Murmure Doux", "Voile de Rosée", "Regard Taquin"), "Étreinte de Brume",
            "Garde lors de son Voile de Brume puis contre-attaque avec Lame Spirituelle."),
        Seed("Mireille", "Cueilleuse d'Ombres", Archetype.BINDER, "Liane 🌿",
            listOf("Lianes Vives", "Piège de Ronces", "Serrage Sylvestre"), "Cocon de Ronces",
            "Réussis le QTE rapidement quand ses lianes t'entravent."),
        Seed("Séléné", "Guetteuse Lunaire", Archetype.HYPNOTIST, "Lune 🌙",
            listOf("Reflet d'Argent", "Berceuse Nocturne", "Éclat Hypnotique"), "Éclipse Lunaire",
            "Utilise une Amulette de Clarté si elle t'hypnotise."),
        Seed("Talia", "Fée Malicieuse", Archetype.ILLUSIONIST, "Pollen ✨",
            listOf("Poudre d'Étoiles", "Double Mirage", "Sourire Piégé"), "Tourbillon d'Illusions",
            "Frappe régulièrement pour charger 50 SP et lancer Sceau d'Exorcisme."),
        Seed("Sylva", "L'Enlaceuse Sylvestre", Archetype.BINDER, "Forêt 🌲",
            listOf("Racines Impérieuses", "Étreinte de Sève", "Drain Végétal"), "Prison d'Émeraude",
            "Mini-Boss : prépare un Talisman de Rupture contre sa Prison d'Émeraude."),
        Seed("Noémie", "Sorcière des Lucioles", Archetype.CHARMER, "Lumière 🕯️",
            listOf("Danse Dorée", "Flamme Douce", "Appel Envoûtant"), "Cascade de Lucioles",
            "Utilise Garde quand sa Cascade de Lucioles se charge."),
        Seed("Fiora", "Tisseuse de Soie", Archetype.BINDER, "Soie 🕸️",
            listOf("Fil d'Argent", "Nœud Coulant", "Toile de Velours"), "Cocon Impérial",
            "Ne la laisse pas monter à 100 % d'Emprise, garde tes SP."),
        Seed("Oriane", "Chuchoteuse du Vent", Archetype.HYPNOTIST, "Vent 🍃",
            listOf("Souffle Troubles", "Écho de Soumission", "Rafale d'Oubli"), "Tempête Murmurante",
            "Réponds avec fermeté ou utilise Garde pour regagner tes PM."),
        Seed("Klara", "Gardienne du Bosquet", Archetype.DRAINER, "Sève 🍇",
            listOf("Siphon Doux", "Baiser d'Ombre", "Vol d'Élan"), "Absorption Sylvestre",
            "Elle draine tes PV et ton Niveau : garde tes Élixirs !"),
        Seed("Morgane", "Archisorcière du Crépuscule", Archetype.SOVEREIGN, "Crépuscule 🌆",
            listOf("Commandement Royal", "Chaînes du Soir", "Drain Crépusculaire"), "Jugement de la Forêt",
            "BOSS CH.1 : Pare absolument son Jugement de la Forêt avec Garde !"),

        // === CHAPITRE 2 : MANOIR DE VELOURS (11..20) ===
        Seed("Camille", "Hôtesse Pourpre", Archetype.CHARMER, "Velours 🌹",
            listOf("Invitation Impérieuse", "Parfum Capiteux", "Regard de Maîtresse"), "Sérénade Pourpre",
            "Ses charmes changent tes boutons en « Obéir » : reste attentif !"),
        Seed("Valéria", "Dame aux Rubans", Archetype.BINDER, "Ruban 🎀",
            listOf("Ruban de Satin", "Entrave Élégante", "Étreinte Serrée"), "Ligotage de Gala",
            "Entraîne tes réflexes QTE : elle capture très souvent."),
        Seed("Roxane", "Somnambule d'Or", Archetype.HYPNOTIST, "Rêve 💫",
            listOf("Pendule Doré", "Voix de Velours", "Transe Profonde"), "Sommeil Éternel",
            "Dissipe son hypnose avant que la qualité d'écran ne chute."),
        Seed("Inès", "Gouvernante Sévère", Archetype.INQUISITOR, "Discipline ⚖️",
            listOf("Rappel à l'Ordre", "Sceau de Silence", "Regard Glacial"), "Sentence du Manoir",
            "Elle adore sceller tes compétences : garde 20 PM en réserve."),
        Seed("Carmilla", "La Comtesse Sanguine", Archetype.DRAINER, "Pourpre 🦇",
            listOf("Baiser Vampirique", "Siphon d'Aura", "Étreinte Nocturne"), "Festin d'Énergie",
            "Mini-Boss : son Festin d'Énergie draine jusqu'à 3 Niveaux si non paré !"),
        Seed("Élodie", "Pianiste Envoûtante", Archetype.HYPNOTIST, "Mélodie 🎵",
            listOf("Accord Captivant", "Nocturne de Soumission", "Valse Hypnotique"), "Symphonie Finale",
            "Utilise Exorcisme (50 SP) pour casser son rythme."),
        Seed("Salomé", "Danseuse des Voiles", Archetype.CHARMER, "Soie ✨",
            listOf("Voile Translucide", "Pas Hypnotique", "Grâce Écrasante"), "Danse des Sept Voiles",
            "Garde face à sa Danse finale, puis enchaîne 2 Lames Spirituelles."),
        Seed("Héloïse", "Gardienne des Clés", Archetype.INQUISITOR, "Fer 🗝️",
            listOf("Verrou de Volonté", "Chaîne d'Argent", "Ordre Absolu"), "Cachot de Velours",
            "Prévois des Élixirs : ses coups sur les PV sont lourds."),
        Seed("Diane", "Chasseresse de Minuit", Archetype.BINDER, "Ombre 🏹",
            listOf("Filet d'Ombre", "Poursuite Implacable", "Prise Rapide"), "Capture de Minuit",
            "Brise sa capture au 1er tour ou son Emprise grimpe en flèche."),
        Seed("Séraphina", "Reine du Manoir de Velours", Archetype.SOVEREIGN, "Charme 👑",
            listOf("Aura Souveraine", "Drain Royal", "Soumission Impériale"), "Couronnement de Velours",
            "BOSS CH.2 : Garde sur son Couronnement et garde un Talisman de Rupture !"),

        // === CHAPITRE 3 : CITADELLE DES ILLUSIONS (21..30) ===
        Seed("Iris", "Sorcière du Prisme", Archetype.ILLUSIONIST, "Prisme 💎",
            listOf("Éclat aveuglant", "Reflet Trompeur", "Kaléidoscope"), "Labyrinthe de Cristal",
            "Son Prisme brouille tes sens : utilise Exorcisme dès 50 SP."),
        Seed("Mélina", "Tisseuse de Songes", Archetype.HYPNOTIST, "Songe ☁️",
            listOf("Brume Onirique", "Suggestion Douce", "Vertige Violet"), "Rêve sans Fin",
            "Maintiens tes PV au-dessus de 50 % pour éviter la capture auto."),
        Seed("Cléo", "Oracle des Sabliers", Archetype.CHRONO, "Temps ⏳",
            listOf("Ralentissement", "Temps Volé", "Boucle de Soumission"), "Arrêt du Temps",
            "Garde dès qu'elle prépare son Arrêt du Temps !"),
        Seed("Ambre", "Alchimiste des Philtres", Archetype.CHARMER, "Philtre 🧪",
            listOf("Vapeur Sucrée", "Élixir de Docilité", "Brume Rose"), "Distillation d'Âme",
            "Elle fait grimper l'Emprise très vite : attaque sans relâche."),
        Seed("Nyxia", "Maîtresse des Miroirs", Archetype.ILLUSIONIST, "Miroir 🪞",
            listOf("Reflet Captif", "Cage de Verre", "Drain Réfléchi"), "Monde Inversé",
            "Mini-Boss : alterne Garde et Lame Spirituelle pour économiser tes PM."),
        Seed("Zara", "Dompteuse d'Éclairs", Archetype.INQUISITOR, "Foudre ⚡",
            listOf("Étincelle Sévère", "Collier de Foudre", "Choc d'Obéissance"), "Orage Impérial",
            "Haute attaque PV : garde 2 Élixirs de Volonté dans ton sac."),
        Seed("Léna", "Sirène des Brumes", Archetype.CHARMER, "Eau 🌊",
            listOf("Chant Abyssal", "Courant Enlaçant", "Goutte d'Oubli"), "Marée Envoûtante",
            "Ne cède pas sur ses répliques RP si tu veux garder tes applis actives !"),
        Seed("Sora", "Marionnettiste d'Or", Archetype.BINDER, "Fils 🎭",
            listOf("Fils Invisibles", "Contrôle des Gestes", "Étreinte de Pantin"), "Théâtre de Soumission",
            "Un Talisman de Rupture coupe net ses fils magiques."),
        Seed("Yuna", "Gardienne du Sablier", Archetype.CHRONO, "Chrono 🕰️",
            listOf("Décompte Fatal", "Drain d'Heures", "Paradoxe"), "Éternité Captive",
            "Son Drain d'Heures vise directement ton Niveau RPG."),
        Seed("Vespera", "Impératrice des Illusions", Archetype.SOVEREIGN, "Illusion 🔮",
            listOf("Sceptre d'Hypnose", "Prison de Miroirs", "Drain d'Esprit"), "Mirage Absolu",
            "BOSS CH.3 : Peut appeler une 2e Sorcière en renfort à mi-combat !"),

        // === CHAPITRE 4 : CATHÉDRALE D'OBSIDIENNE (31..40) ===
        Seed("Astrid", "Inquisitrice Noire", Archetype.INQUISITOR, "Obsidienne 🖤",
            listOf("Sentence Froide", "Sceau d'Obéissance", "Verdict"), "Jugement d'Obsidienne",
            "Scelle souvent Riposte : profite du tour pour faire Garde (+20 PM)."),
        Seed("Nadia", "Valkyrie des Ombres", Archetype.BINDER, "Acier ⚔️",
            listOf("Prise de Fer", "Verrouillage", "Domination Directe"), "Étau de la Valkyrie",
            "Capture brutale : réussis le QTE ou utilise un Talisman."),
        Seed("Céleste", "Séraphine Déchue", Archetype.CHARMER, "Flamme 🕯️",
            listOf("Auréole Brisée", "Grâce Fatale", "Baiser de Cendre"), "Chute Céleste",
            "Équilibre entre attaque et soins : son Emprise monte vite."),
        Seed("Mona", "Sorcère du Voile Noir", Archetype.DRAINER, "Nuit 🌑",
            listOf("Ténèbres Douces", "Drain de Lumière", "Voile Épais"), "Nuit Sans Fin",
            "Draine fortement la luminosité : garde une Amulette de Clarté !"),
        Seed("Bellatrix", "Grande Inquisitrice", Archetype.INQUISITOR, "Flamme Noire 🔥",
            listOf("Décret Absolu", "Chaînes Runiques", "Châtiment"), "Tribunal des Sorcières",
            "Mini-Boss redoutable : n'encaisse jamais son Tribunal sans Garde !"),
        Seed("Kira", "Lame d'Améthyste", Archetype.DRAINER, "Cristal 💜",
            listOf("Entaille d'Aura", "Absorption Pure", "Éclat Pourpre"), "Siphon d'Améthyste",
            "Charge ton Sceau d'Exorcisme (50 SP) pour renverser le duel."),
        Seed("Maëva", "Prêtresse du Silence", Archetype.HYPNOTIST, "Silence 🤫",
            listOf("Doigt sur les Lèvres", "Calme Imposé", "Transe Muette"), "Règne du Silence",
            "Blinde tes PM avec Garde quand elle scelle tes attaques."),
        Seed("Éva", "Archiviste des Sceaux", Archetype.CHRONO, "Rune 📜",
            listOf("Grimoire Ouvert", "Sceau de Niveau", "Ligne Infinie"), "Contrat Éternel",
            "Évite de tomber sous 30 PV ou elle lance son Contrat Éternel."),
        Seed("Tessa", "Gardienne de la Crypte", Archetype.BINDER, "Chaîne ⛓️",
            listOf("Anneaux d'Ombre", "Entrave Double", "Garde Rapprochée"), "Scellement Total",
            "Garde un Talisman de Rupture pour briser son Scellement Total."),
        Seed("Lilith", "Souveraine d'Obsidienne", Archetype.SOVEREIGN, "Ténèbres 😈",
            listOf("Trône d'Ombre", "Baiser Souverain", "Drain Impérial"), "Domination d'Obsidienne",
            "BOSS CH.4 : Combat long & intense — prépare 3 Élixirs et 2 Talismans !"),

        // === CHAPITRE 5 : TRÔNE DE L'ÉCLIPSE (41..50) ===
        Seed("Freya", "Héraut de l'Éclipse", Archetype.CHARMER, "Éclipse 🌒",
            listOf("Aura Magnétique", "Sourire Royal", "Charme Suprême"), "Appel de l'Éclipse",
            "Chaque tour non paré fait grimper son Emprise de +30 %."),
        Seed("Sybille", "Prophétesse Pourpre", Archetype.CHRONO, "Destin 🔮",
            listOf("Destin Scellé", "Vol de Futur", "Écho Fatal"), "Prophétie de Défaite",
            "Garde systématiquement lorsqu'elle annonce sa Prophétie."),
        Seed("Aurore", "Reine de l'Aube Noire", Archetype.DRAINER, "Crépuscule 🌅",
            listOf("Lumière Volée", "Siphon Total", "Baiser d'Aube"), "Crépuscule Absolu",
            "Protège ton Niveau avec Garde et purifie les drains !"),
        Seed("Mégane", "Domina des Runes", Archetype.INQUISITOR, "Rune ⚡",
            listOf("Cercle de Soumission", "Ordre Suprême", "Sceau Triple"), "Loi de la Domina",
            "Très sévère : privilégie les coups sûrs et Exorcisme."),
        Seed("Astréa", "La Dévoreuse d'Étoiles", Archetype.DRAINER, "Cosmos 🌌",
            listOf("Gravité Douce", "Orbe Vorace", "Drain Stellaire"), "Trou Noir de Volonté",
            "Mini-Boss ultime : 180 % de Résolution, utilise tous tes objets !"),
        Seed("Nora", "Impératrice de Glace", Archetype.HYPNOTIST, "Givre ❄️",
            listOf("Souffle Glacé", "Regard Gelé", "Transe Cristalline"), "Hiver d'Obéissance",
            "Ne la laisse pas cumuler Hypnose + Capture."),
        Seed("Saphira", "Matriarche des Saphirs", Archetype.ILLUSIONIST, "Saphir 💠",
            listOf("Éclat Royal", "Double Emprise", "Mirage Parfait"), "Palais de Saphir",
            "Forte en Emprise : lance Sceau d'Exorcisme dès que possible."),
        Seed("Victoria", "Générale du Coven", Archetype.BINDER, "Écarlate 🎖️",
            listOf("Capture Tactique", "Verrou Militaire", "Discipline Totale"), "Capitulation Totale",
            "Reste prêt pour le QTE à chaque tour !"),
        Seed("Cassandre", "Main Droite de l'Éclipse", Archetype.SOVEREIGN, "Ombre 👑",
            listOf("Sceptre Noir", "Drain de Rang", "Emprise Finale"), "Couronne d'Ombre",
            "Dernière gardienne avant le Boss Ultime du jeu !"),
        Seed("Élara", "Déesse Suprême de l'Éclipse", Archetype.SOVEREIGN, "Éclipse Totale 🌑👑",
            listOf("Décret Divin", "Chaînes d'Étoiles", "Drain Absolu"), "ÉCLIPSE DE SOUMISSION",
            "BOSS ULTIME (50/50) : Maîtrise Garde, QTE, Exorcisme et tes Reliques pour la vaincre !")
    )

    val ALL_WITCHES: List<WitchDef> = SEEDS.mapIndexed { idx, s ->
        val id = idx + 1
        val chapter = (idx / 10) + 1
        val posInChap = (idx % 10) + 1
        val isMiniBoss = posInChap == 5
        val isBoss = posInChap == 10
        val lv = id
        val resolve = when {
            isBoss -> 170 + chapter * 22
            isMiniBoss -> 130 + chapter * 16
            else -> 95 + chapter * 10
        }
        val gold = when {
            isBoss -> 120 + chapter * 50
            isMiniBoss -> 65 + chapter * 25
            else -> 28 + chapter * 10
        }
        val xp = when {
            isBoss -> 160 + chapter * 55
            isMiniBoss -> 90 + chapter * 30
            else -> 40 + chapter * 14
        }
        WitchDef(
            id = id,
            chapter = chapter,
            name = s.name,
            title = s.title,
            archetype = s.arch,
            element = s.elem,
            level = lv,
            isMiniBoss = isMiniBoss,
            isBoss = isBoss,
            maxResolve = resolve,
            rewardGold = gold,
            rewardXp = xp,
            skillNames = s.skills,
            ultimateName = s.ult,
            weaknessTip = s.tip
        )
    }

    fun getById(id: Int): WitchDef = ALL_WITCHES.firstOrNull { it.id == id } ?: ALL_WITCHES.first()

    fun witchesInChapter(chapter: Int): List<WitchDef> = ALL_WITCHES.filter { it.chapter == chapter }

    fun bossOfChapter(chapter: Int): WitchDef =
        ALL_WITCHES.firstOrNull { it.chapter == chapter && it.isBoss } ?: ALL_WITCHES.last()

    fun randomWitchForChapter(chapter: Int, includeBoss: Boolean = false): WitchDef {
        val pool = witchesInChapter(chapter).filter { includeBoss || !it.isBoss }
        return if (pool.isNotEmpty()) pool.random() else ALL_WITCHES.random()
    }
}
