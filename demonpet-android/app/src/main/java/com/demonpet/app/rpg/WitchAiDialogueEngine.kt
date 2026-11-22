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
 * Instant Non-Repetitive AI Dialogue Engine for the 50-Witch Dark-Fantasy RPG:
 * - Tier 1 (0 ms Local Generative Engine): rich combinatorial French RPG dialogue tailored to Witch archetype,
 *   battle phase (Intro / Attack / Capture / Guarded / QTE Win / QTE Fail / Drain End-Scene / Victory),
 *   player level & obedience rank, with a 30-line anti-repetition ring buffer!
 * - Tier 2 (Optional Live Gemini API): if the user enters their own Gemini API key in Settings,
 *   requests fresh live AI lines asynchronously (2.6s timeout, seamless fallback to Tier 1 if offline).
 * - Keeps all dialogue strictly atmospheric dark-fantasy / theatrical RPG charm & energy-drain lore (non-explicit).
 */
object WitchAiDialogueEngine {

    enum class EventKind {
        INTRO, ATTACK, CAPTURE, ULTIMATE_WARN, GUARDED, QTE_BREAK, QTE_FAIL,
        RP_DEFY, RP_SUBMIT, DRAIN_STAGE_1, DRAIN_STAGE_2, DRAIN_STAGE_3, VICTORY
    }

    private val recentLines = ArrayDeque<String>(36)

    @Synchronized
    private fun pickFresh(candidates: List<String>): String {
        val fresh = candidates.filter { !recentLines.contains(it) }
        val chosen = if (fresh.isNotEmpty()) fresh[Random.nextInt(fresh.size)] else candidates[Random.nextInt(candidates.size)]
        if (recentLines.size >= 30) recentLines.removeFirst()
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
            playerLevel >= 25 -> "petit rebelle"
            playerLevel >= 15 -> "apprenti"
            playerLevel >= 8 -> "captif"
            playerLevel >= 3 -> "serviteur"
            else -> "soumis absolu"
        }

        val openings = when (arch) {
            WitchCatalog.Archetype.CHARMER -> listOf("Hihi…", "Approche donc…", "Regarde-moi bien…", "Comme tu es mignon, $rank…")
            WitchCatalog.Archetype.BINDER -> listOf("Ne bouge plus !", "Attrapé !", "Tu ne t'échapperas pas…", "Mes liens te vont si bien…")
            WitchCatalog.Archetype.HYPNOTIST -> listOf("Laisse ton esprit flotter…", "Écoute uniquement ma voix…", "Tes paupières s'alourdissent…", "Regarde l'éclat de mes yeux…")
            WitchCatalog.Archetype.DRAINER -> listOf("Quelle délicieuse aura…", "Donne-moi ton énergie…", "Ton Niveau m'appartient…", "Je vais tout absorber…")
            WitchCatalog.Archetype.INQUISITOR -> listOf("À genoux, $rank !", "Silence !", "Ton insolence mérite une leçon !", "Par décret du Coven…")
            WitchCatalog.Archetype.ILLUSIONIST -> listOf("Où regardes-tu ?", "Tout n'est qu'un mirage…", "Tu perds déjà tes repères…", "Mon prisme t'enveloppe…")
            WitchCatalog.Archetype.CHRONO -> listOf("Ton temps est écoulé…", "Chaque seconde m'appartient…", "Le sablier se vide…", "Trop tard, $rank…")
            WitchCatalog.Archetype.SOVEREIGN -> listOf("Incline-toi devant $n !", "Oses-tu défier ta Reine ?", "Ce téléphone est mon royaume…", "Tu connais déjà l'issue de ce duel…")
        }
        val op = pickFresh(openings)

        val templates = when (event) {
            EventKind.INTRO -> listOf(
                "$op Je suis ${witch.displayTitle} (NV ${witch.level}). Prêt à offrir ton énergie ? 😈",
                "$op Un simple $rank de niveau $playerLevel ose traverser mon territoire ? Voyons ta volonté ! ✨",
                "$op L'aura de ${witch.element} t'encercle déjà… voyons combien de tours tu tiens face à $n ! 💜",
                "$op Tu pensais utiliser ton téléphone tranquillement ? C'est l'heure de ton duel contre $n ! 👑"
            )
            EventKind.ATTACK -> listOf(
                "$op $n déchaîne « $skillName » ! Ton aura vacille tandis que l'emprise grimpe à $embracePct % !",
                "$op Sous l'effet de « $skillName », ton énergie spirituelle s'échappe vers $n… ✨",
                "$op « $skillName » te frappe de plein fouet ! $n sourit en voyant ta résistance faiblir.",
                "$op L'envoûtement de « $skillName » résonne dans tout l'écran — tu recules d'un pas !"
            )
            EventKind.CAPTURE -> listOf(
                "$op Tu es ENTRAVÉ ! $n se rapproche tout près : « Voyons si tu arrives à te libérer ! ⛓️😈 »",
                "$op Pris au piège ! L'emprise de $n se referme sur toi — réussis le QTE pour casser le sort !",
                "$op « Trop tard, $rank ! Tu es à ma merci maintenant… laisse ton Niveau fondre ! 💋 »"
            )
            EventKind.ULTIMATE_WARN -> listOf(
                "⚠️ $n lève les mains : son Sort Ultime « ${witch.ultimateName} » charge ! Utilise 🛡️ GARDE au prochain tour !",
                "⚠️ Une aura écrasante envahit l'écran ! « ${witch.ultimateName} » arrive — mets-toi vite en 🛡️ GARDE !"
            )
            EventKind.GUARDED -> listOf(
                "🛡️ Parade parfaite ! Tu bloques 70 % de « $skillName » et $n fronce les sourcils : « Pas mal pour un $rank… »",
                "🛡️ Ton bouclier mental repousse l'assaut de $n ! Tu récupères ton souffle et tes PM."
            )
            EventKind.QTE_BREAK -> listOf(
                "💥 Tu fais voler l'entrave en éclats ! $n recule, surprise : « Impressionnant… mais ce n'est pas fini ! »",
                "⚡ Ta volonté brise le charme ! L'emprise de $n chute brutalement."
            )
            EventKind.QTE_FAIL -> listOf(
                "$op Trop lent ! $n rit doucement et resserre son emprise en absorbant ton énergie ! 😈",
                "$op Tes forces t'abandonnent au milieu du QTE… $n en profite pour drainer le double !"
            )
            EventKind.RP_DEFY -> listOf(
                "« Insolent ! » Les yeux de $n brillent d'une lueur cramoisie — ton audace la blesse mais décuple sa sévérité !",
                "« Tu oses me tenir tête, $rank ?! Je vais te faire ravaler cette arrogance ! 😤 »"
            )
            EventKind.RP_SUBMIT -> listOf(
                "« Hihi… enfin une attitude raisonnable ! » $n sourit avec satisfaction et aspire aussitôt une vague de ton énergie 💜",
                "« C'est bien… reconnais ta Reine. Ton obéissance nourrit mon aura ! 👑 »"
            )
            EventKind.DRAIN_STAGE_1 -> listOf(
                "$op Tes défenses cèdent complètement… un flot d'orbes lumineux quitte ta barre de Niveau vers $n ! 💜",
                "$op Plus aucune résistance… l'aura de ${witch.element} t'enveloppe et aspire ta volonté niveau par niveau ! ✨",
                "$op « C'est fini, $rank… laisse ton énergie couler vers moi, tout devient si calme et sombre… 🌙 »"
            )
            EventKind.DRAIN_STAGE_2 -> {
                val appTxt = affectedApp?.let { " En tribut, je scelle « $it » sur ton téléphone ! 🔒" }
                    ?: " En tribut, je laisse ma marque sur ton écran et ta luminosité ! 🔒"
                listOf(
                    "« Ta lumière diminue et ton Niveau s'effondre…$appTxt »",
                    "« Chaque niveau absorbé renforce le Coven !$appTxt »",
                    "« Tu es totalement vidé de ton mana !$appTxt »"
                )
            }
            EventKind.DRAIN_STAGE_3 -> listOf(
                "« Défaite totale face à ${witch.displayTitle}. Souviens-toi de mon visage sur ton fond d'écran ! 👑😈 »",
                "« Fin du drainage ! Tu peux tenter ta revanche… ou accepter ton rang de $rank ! 💜 »"
            )
            EventKind.VICTORY -> listOf(
                "« Grr… impossible ! Un simple $rank m'a repoussée ?! Le Coven s'en souviendra ! 😤 »",
                "« Bien joué… tu as résisté à mon aura cette fois. Prends tes Éclats d'Âme avant que je ne change d'avis ! ✨ »"
            )
        }
        return pickFresh(templates)
    }

    /**
     * Optional Live Cloud AI Dialogue via Google Gemini REST API (if user configured their own API key).
     * Runs on a background thread with a strict 2.6s timeout; falls back cleanly to local instant dialogue.
     */
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
                    "Réponds en UNE seule réplique courte, intense et originale (max 150 caractères), avec 1 emoji."

                val body = JSONObject().apply {
                    put("contents", JSONArray().put(
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                    ))
                    put("generationConfig", JSONObject().apply {
                        put("maxOutputTokens", 70)
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
                        ?.take(190)
                        .orEmpty()
                    if (txt.length >= 8) {
                        onReady(txt)
                    }
                }
                conn.disconnect()
            } catch (_: Throwable) {
            }
        }.start()
    }
}
