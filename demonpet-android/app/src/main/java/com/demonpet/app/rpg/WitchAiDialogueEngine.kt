package com.demonpet.app.rpg

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import kotlin.random.Random

/**
 * Rich Non-Repetitive AI Dialogue Engine for the 50-Witch Dark-Fantasy RPG (v6.0):
 * - Tier 1 (0 ms Local Combinatorial Engine): 25+ battle events, 8 Witch archetypes, 45-line anti-repetition memory,
 *   dynamically reflecting Combo, Spam-Read counters, Perfect Parries, Shields, Mirages, Boss Enrage Phases & System Curses!
 * - Tier 2 (Optional Live Gemini 2.0 Flash API): asynchronous live AI dialogue when user adds their own API key.
 * - Strictly atmospheric dark-fantasy RPG / theatrical charm & energy-drain lore (non-explicit).
 */
object WitchAiDialogueEngine {

    enum class EventKind {
        INTRO, ATTACK, ENCHANT_CHARM, ENCHANT_HYPNO, SIPHON_MARK, SHIELD_UP, MIRAGE_CAST, TIME_DOUBLE,
        CAPTURE, ULTIMATE_WARN, GUARDED, PERFECT_PARRY, COUNTER_HIT, SHIELD_BREAK, SPAM_READ,
        QTE_BREAK, QTE_FAIL, BOSS_PHASE_2, BOSS_PHASE_3, EXORCISM_HIT, JUDGMENT_ULT,
        RP_DEFY, RP_BARGAIN, RP_SUBMIT,
        DRAIN_STAGE_1, DRAIN_STAGE_2, DRAIN_STAGE_3, DRAIN_STAGE_4, VICTORY
    }

    private val recentLines = ArrayDeque<String>(52)

    @Synchronized
    private fun pickFresh(candidates: List<String>): String {
        val fresh = candidates.filter { !recentLines.contains(it) }
        val chosen = if (fresh.isNotEmpty()) fresh[Random.nextInt(fresh.size)] else candidates[Random.nextInt(candidates.size)]
        if (recentLines.size >= 45) recentLines.removeFirst()
        recentLines.addLast(chosen)
        return chosen
    }

    fun generateInstantLine(
        witch: WitchCatalog.WitchDef,
        event: EventKind,
        skillName: String = "",
        playerLevel: Int = 20,
        embracePct: Int = 0,
        affectedApp: String? = null
    ): String {
        val n = witch.name
        val arch = witch.archetype
        val rank = when {
            playerLevel >= 25 -> "rebelle effronté"
            playerLevel >= 15 -> "apprenti"
            playerLevel >= 8 -> "captif"
            playerLevel >= 3 -> "serviteur"
            else -> "soumis absolu"
        }

        val openings = when (arch) {
            WitchCatalog.Archetype.CHARMER -> listOf("Hihi…", "Approche donc…", "Regarde-moi dans les yeux…", "Comme tu es attendrissant, $rank…", "Laisse-toi bercer…")
            WitchCatalog.Archetype.BINDER -> listOf("Ne bouge plus !", "Attrapé !", "Mes liens se resserrent…", "Inutile de te débattre…", "Tu ne m'échapperas pas !")
            WitchCatalog.Archetype.HYPNOTIST -> listOf("Laisse ton esprit flotter…", "Écoute uniquement ma voix…", "Tes pensées s'évaporent…", "Plonge dans mon regard…", "Ton esprit s'engourdit…")
            WitchCatalog.Archetype.DRAINER -> listOf("Quelle délicieuse aura…", "Offre-moi ton mana…", "Ton Niveau m'appartient…", "Je vais tout siphonner…", "J'ai soif de ton énergie…")
            WitchCatalog.Archetype.INQUISITOR -> listOf("À genoux, $rank !", "Silence !", "Ton arrogance va te coûter cher !", "Par décret du Coven…", "Reçois ta sentence !")
            WitchCatalog.Archetype.ILLUSIONIST -> listOf("Où frappes-tu donc ?", "Tout n'est qu'un mirage…", "Mon prisme t'enveloppe…", "Tu perds tous tes repères…", "Vrai ou illusion ?")
            WitchCatalog.Archetype.CHRONO -> listOf("Ton temps s'arrête ici…", "Chaque seconde m'appartient…", "Le sablier se renverse…", "Trop lent, $rank…", "Le futur est déjà scellé…")
            WitchCatalog.Archetype.SOVEREIGN -> listOf("Incline-toi devant $n !", "Oses-tu défier ta Reine ?", "Ce téléphone est mon domaine…", "L'issue de ce duel est déjà écrite…", "Ressens ma souveraineté !")
        }
        val op = pickFresh(openings)

        val templates = when (event) {
            EventKind.INTRO -> listOf(
                "$op Je suis ${witch.displayTitle} (NV ${witch.level}). Voyons si ta tactique vaut mieux que du simple hasard ! 😈",
                "$op Un $rank de niveau $playerLevel ose pénétrer mon sanctuaire de ${witch.element} ? Prépare tes défenses ! ✨",
                "$op Observe bien mes intentions, lis mes sorts… ou regarde ton énergie fondre entre mes mains ! 💜",
                "$op Le cercle runique se referme derrière toi. Seule la maîtrise tactique te sauvera de $n ! 👑"
            )
            EventKind.ATTACK -> listOf(
                "$op $n déchaîne « $skillName » ! L'onde de ${witch.element} secoue ton aura (Emprise $embracePct %) !",
                "$op « $skillName » fend tes défenses ! $n sourit en sentant ta résistance plier.",
                "$op Sous la pression de « $skillName », ton énergie spirituelle glisse doucement vers $n ! ✨"
            )
            EventKind.ENCHANT_CHARM -> listOf(
                "$op Envoûtement « $skillName » ! Tes pensées s'embrouillent — tes propres commandes veulent obéir à $n ! 💖",
                "$op Un parfum magique d'envoûtement envahit l'écran ! Résiste au charme ou utilise 🧘 Méditation !"
            )
            EventKind.ENCHANT_HYPNO -> listOf(
                "$op Spirale hypnotique « $skillName » ! Des anneaux violets pulsent et siphonnent ton mana à chaque tour ! 🌀",
                "$op « Ne pense plus à te battre… » L'hypnose de $n embrume ta concentration !"
            )
            EventKind.SIPHON_MARK -> listOf(
                "$op $n grave une Marque de Siphon (« $skillName ») sur ton aura ! Elle aspire tes PV pour se soigner à chaque tour ! 🩸",
                "$op Un lien pourpre relie ton cœur à $n — purifie-le vite avec 🔮 Amulette ou 🌟 Exorcisme !"
            )
            EventKind.SHIELD_UP -> listOf(
                "🛡️ $n érige un Bouclier Runique (« $skillName ») ! Les frappes simples ricochent — brise-le avec 🔥 Lame Spirituelle !",
                "🛡️ Une barrière hexagonale protège $n ! Utilise un sort Brise-Bouclier pour l'étourdir !"
            )
            EventKind.MIRAGE_CAST -> listOf(
                "🪞 $n se dédouble avec « $skillName » ! Ses clones brouillent tes frappes — seul 🌟 Exorcisme ou 🧘 Focus perce l'illusion !",
                "🪞 « Laquelle suis-je vraiment ? Hihi… » Deux silhouettes fantomatiques encerclent l'écran !"
            )
            EventKind.TIME_DOUBLE -> listOf(
                "⏳ Distorsion Temporelle ! $n fige les secondes autour de toi et enchaîne une DOUBLE attaque impitoyable !",
                "⏳ Le sablier d'ombre accélère : $n frappe deux fois avant que tu puisses reprendre ton souffle !"
            )
            EventKind.CAPTURE -> listOf(
                "$op ENTRAVÉ ! $n se rapproche tout près : « Voyons quel réflexe te sortira de mes liens ! ⛓️😈 »",
                "$op Pris au piège ! L'emprise de $n se referme — réussis l'épreuve d'évasion ou utilise un 📜 Talisman !"
            )
            EventKind.ULTIMATE_WARN -> listOf(
                "⚠️ $n concentre une magie immense : « ${witch.ultimateName} » va frapper ! Utilise 🛡️ GARDE pour une PARADE PARFAITE !",
                "⚠️ L'air vibre sous l'incantation de « ${witch.ultimateName} » ! Prépare 🛡️ GARDE ou 🛡️ Voile d'Égide !"
            )
            EventKind.GUARDED -> listOf(
                "🛡️ Garde solide ! Tu encaisses « $skillName » avec -75 % de dégâts et protèges ton Niveau !",
                "🛡️ Ton rempart mental contient l'assaut de $n tandis que tu reprends +22 PM."
            )
            EventKind.PERFECT_PARRY -> listOf(
                "⚡ PARADE PARFAITE !! Tu dévies « $skillName » en plein vol ! $n est ÉTOURDIE et tu gagnes +30 SP ! 🔥",
                "✨ CONTRE ABSOLU ! Ton timing parfait renvoie l'onde de $n contre elle — ouverture critique !"
            )
            EventKind.COUNTER_HIT -> listOf(
                "⚡ CONTRE-SCEAU DÉCLENCHÉ ! Tu interceptes l'élan de $n en plein vol pour des dégâts massifs ! 💥",
                "⚡ Trop prévisible ! Ton Contre-Sceau retourne la magie de $n contre elle et la fait chanceler !"
            )
            EventKind.SHIELD_BREAK -> listOf(
                "💥 BOUCLIER PULVÉRISÉ !! La barrière de $n vole en éclats — elle est ÉTOURDIE (dégâts ×1.8) ! 🔥",
                "💥 CRASH ! Ta Lame fend sa barrière runique et la laisse totalement exposée pour 1 tour !"
            )
            EventKind.SPAM_READ -> listOf(
                "😈 « Encore la même attaque ?! Je lis ton jeu comme un livre ouvert ! » $n esquive partiellement et contre-attaque !",
                "😈 $n ricane : « Trop répétitif, $rank ! Varie tes techniques si tu veux survivre ! »"
            )
            EventKind.QTE_BREAK -> listOf(
                "💥 Tu fais voler l'emprise en éclats ! $n recule, soufflée : « Impressionnant… quelle volonté ! »",
                "⚡ Évasion magistrale ! Le sortilège de capture explose et réduit son Emprise de -45 % !"
            )
            EventKind.QTE_FAIL -> listOf(
                "$op Trop tard ! $n sourit avec triomphe et resserre son emprise en absorbant une vague d'énergie ! 😈",
                "$op Ton évasion échoue au dernier instant — $n exploite la brèche pour drainer le double !"
            )
            EventKind.BOSS_PHASE_2 -> listOf(
                "🔥 PHASE 2 — AURA DÉCHAÎNÉE ! Les yeux de $n s'illuminent : « Assez joué… voici ma vraie puissance ! 👑 »",
                "🔥 L'air s'embrase ! $n brise ses propres limites et déploie son Aura de Domination (+Emprise par tour) !"
            )
            EventKind.BOSS_PHASE_3 -> listOf(
                "👑 PHASE FINALE — ARCHISORCIÈRE ABSOLUE ! $n s'élève dans le cercle runique : « Tout se décide maintenant ! »",
                "🌑 L'Éclipse tombe sur le champ de bataille ! $n libère sa forme ultime !"
            )
            EventKind.EXORCISM_HIT -> listOf(
                "🌟 SCEAU D'EXORCISME ! Une déflagration sacrée dissipe ses illusions, purge tes malédictions et fait chuter son Emprise !",
                "✨ La lumière runique balaie l'aura sombre de $n ! Toutes ses altérations s'évaporent !"
            )
            EventKind.JUDGMENT_ULT -> listOf(
                "💥 JUGEMENT ULTIME (100 SP) !! Une lame astrale traverse tout l'écran et ébranle jusqu'aux fondations du Coven !",
                "🔥 LIBÉRATION TOTALE ! Ton Jugement Ultime fracasse les défenses de $n et restaure ta volonté !"
            )
            EventKind.RP_DEFY -> listOf(
                "« Insolent ! » Les yeux de $n brillent d'une fureur pourpre — ta provocation la déstabilise mais renforce ses coups !",
                "« Tu oses me narguer, $rank ?! Très bien, encaisse toute ma colère ! 😤 »"
            )
            EventKind.RP_BARGAIN -> listOf(
                "« Négocier avec moi ? Intéressant… je te laisse reprendre ton souffle contre un peu de ton Emprise ! 😈 »",
                "« Hihi… un pacte temporaire ? Tu récupères tes PM, mais mon emprise grandit ! 💜 »"
            )
            EventKind.RP_SUBMIT -> listOf(
                "« Hihi… enfin raisonnable ! » $n sourit avec délectation et aspire une vague de ton énergie 💜",
                "« C'est bien… reconnais la supériorité du Coven. Ton obéissance nourrit mon aura ! 👑 »"
            )
            EventKind.DRAIN_STAGE_1 -> listOf(
                "$op Tes défenses s'effondrent enfin… des torrents d'orbes lumineux quittent ta barre de Niveau vers $n ! 💜",
                "$op Plus aucune force pour riposter… l'aura de ${witch.element} t'enveloppe entièrement tandis que ton mana s'évapore ! ✨"
            )
            EventKind.DRAIN_STAGE_2 -> {
                val appTxt = affectedApp?.let { " En tribut, je grise « $it » sur ton téléphone ! 🔒" }
                    ?: " En tribut, je scelle ta luminosité et laisse ma marque sur ton écran ! 🔒"
                listOf(
                    "« Sens ton Niveau glisser vers moi…$appTxt »",
                    "« Chaque point d'énergie absorbé renforce mon règne !$appTxt »"
                )
            }
            EventKind.DRAIN_STAGE_3 -> listOf(
                "« Ton regard se voile dans la transe violette… le cercle runique se referme autour de ton esprit vaincu. 🌙💜 »",
                "« Plus de PM, plus de volonté… tu contemples simplement le triomphe absolu de ${witch.displayTitle}. 👑 »"
            )
            EventKind.DRAIN_STAGE_4 -> listOf(
                "« Fin du drainage ! Mon portrait reste gravé sur ton fond d'écran en trophée. Oseras-tu demander ta revanche ? 😈 »",
                "« Défaite gravée dans le Grimoire ! Relève-toi plus fort via la Forge d'Âme… ou incline-toi devant le Coven ! 💜 »"
            )
            EventKind.VICTORY -> listOf(
                "« Grr… impossible ! Ta stratégie a percé tous mes envoûtements ?! Le Coven n'en restera pas là ! 😤 »",
                "« Quelle maîtrise… tu as survécu à tous mes pièges. Prends ces Éclats d'Âme, tu les as mérités ! ✨ »"
            )
        }
        return pickFresh(templates)
    }

    /** Optional Live Cloud AI Dialogue via Google Gemini REST API (strictly non-explicit dark-fantasy RPG). */
    fun generateLiveAiLineIfConfigured(
        apiKey: String,
        witch: WitchCatalog.WitchDef,
        event: EventKind,
        skillName: String,
        playerLevel: Int,
        embracePct: Int,
        onReady: (String) -> Unit
    ) {
        if (apiKey.isBlank()) return
        Thread {
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 2_400
                    readTimeout = 2_600
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                val prompt = "Tu incarnes ${witch.name} (${witch.title}, archétype ${witch.archetype.label}, élément ${witch.element}) " +
                    "dans un jeu RPG Visual Novel dark-fantasy en français (style Monster Girl Quest, registre dramatique/charme mystique/drainage d'énergie magique, non explicite). " +
                    "Événement: $event, sort: $skillName, Niveau du joueur: $playerLevel, Emprise: $embracePct%. " +
                    "Réponds en UNE seule réplique vivante, tactique et originale (max 155 caractères), avec 1 emoji."

                val body = JSONObject().apply {
                    put("contents", JSONArray().put(
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                    ))
                    put("generationConfig", JSONObject().apply {
                        put("maxOutputTokens", 75)
                        put("temperature", 0.95)
                    })
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
                if (conn.responseCode in 200..299) {
                    val raw = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                    val json = JSONObject(raw)
                    val txt = json.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text", "")
                        ?.trim()
                        ?.replace("\n", " ")
                        ?.take(195)
                        .orEmpty()
                    if (txt.length >= 8) onReady(txt)
                }
                conn.disconnect()
            } catch (_: Throwable) {
            }
        }.start()
    }
}
