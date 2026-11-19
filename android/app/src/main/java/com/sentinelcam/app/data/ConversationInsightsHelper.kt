package com.sentinelcam.app.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.provider.CallLog
import android.provider.Telephony
import com.sentinelcam.app.control.ScreenTimeHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale

/**
 * 100% On-Device Behavioral & Conversation Intelligence Engine (v24):
 * - Synthesizes WHO the child communicates with the most across SMS + Calls + Social Apps (WhatsApp/Insta/Snap/Discord).
 * - Synthesizes RECURRING TOPICS & recurring meaningful keywords locally (0 external cloud!).
 * - Detects hidden NIGHTTIME SCREEN USAGE (22:00 – 06:00) over the last 7 days.
 */
object ConversationInsightsHelper {

    private data class TopicDef(
        val id: String,
        val label: String,
        val alert: Boolean,
        val stems: List<String>
    )

    private val TOPICS = listOf(
        TopicDef(
            id = "school",
            label = "École & Devoirs",
            alert = false,
            stems = listOf(
                "cours", "devoir", "devoirs", "prof", "profs", "ecole", "college", "lycee", "classe",
                "controle", "exam", "examen", "note", "notes", "maths", "francais", "anglais",
                "histoire", "cantine", "recre", "pronote", "exercice", "revision", "expose", "bulletin"
            )
        ),
        TopicDef(
            id = "hangouts",
            label = "Sorties & Rendez-vous",
            alert = false,
            stems = listOf(
                "sortir", "sortie", "soiree", "rdv", "rendez", "rejoindre", "rejoins", "viens",
                "parc", "ville", "centre", "cinema", "cine", "mcdo", "kebab", "bus", "metro",
                "tram", "gare", "dehors", "chez", "invite", "fete", "anniv", "anniversaire", "weekend"
            )
        ),
        TopicDef(
            id = "gaming",
            label = "Jeux vidéo & Réseaux",
            alert = false,
            stems = listOf(
                "jeu", "jeux", "game", "gaming", "fortnite", "roblox", "minecraft", "brawl", "fifa",
                "ps5", "xbox", "switch", "discord", "vocal", "voc", "live", "stream", "tiktok",
                "snap", "insta", "video", "youtube", "skin", "serveur", "partie", "ranked", "valorant"
            )
        ),
        TopicDef(
            id = "romance",
            label = "Relations & Amour",
            alert = false,
            stems = listOf(
                "crush", "couple", "copain", "copine", "mec", "meuf", "bisou", "bisous", "coeur",
                "amour", "aime", "jaloux", "jalouse", "ex", "date", "flirt", "beau", "belle", "mignon"
            )
        ),
        TopicDef(
            id = "money",
            label = "Argent & Achats",
            alert = false,
            stems = listOf(
                "euro", "euros", "argent", "payer", "paye", "achete", "acheter", "achat", "carte",
                "virement", "paypal", "compte", "commande", "livraison", "prix", "cher", "thune", "tune", "bourse"
            )
        ),
        TopicDef(
            id = "family",
            label = "Famille & Maison",
            alert = false,
            stems = listOf(
                "maman", "papa", "mere", "pere", "frere", "soeur", "mamie", "papi", "maison",
                "rentre", "rentrer", "manger", "repas", "diner", "table", "chambre", "famille"
            )
        ),
        TopicDef(
            id = "conflict",
            label = "Tensions, Conflits & Stress",
            alert = true,
            stems = listOf(
                "harcele", "harcelement", "insulte", "bagarre", "frappe", "frapper", "menace",
                "chantage", "embrouille", "dispute", "enerve", "triste", "pleure", "pleurer",
                "marre", "nul", "degoute", "peur", "anxieux", "stress", "seul", "seule", "deteste",
                "con", "connard", "pute", "fdp", "tg", "ferme", "degage"
            )
        ),
        TopicDef(
            id = "risk",
            label = "Sujets à risque (Vigilance)",
            alert = true,
            stems = listOf(
                "nude", "nudes", "photo intime", "sexe", "porn", "drogue", "shit", "weed", "joint",
                "clope", "cigarette", "puff", "vape", "alcool", "vodka", "biere", "bourre", "soiree alcool",
                "fugue", "suicide", "mourir", "scarif", "cachet", "secret dis rien", "supprime"
            )
        )
    )

    private val FRENCH_STOPWORDS = setOf(
        "avec", "pour", "dans", "plus", "mais", "comme", "tout", "tous", "toute", "toutes",
        "etre", "avoir", "faire", "fait", "fais", "sont", "etait", "suis", "vous", "nous",
        "elle", "elles", "leur", "leurs", "notre", "votre", "cette", "cet", "ces", "mes",
        "tes", "ses", "mon", "ton", "son", "des", "les", "une", "sur", "sous", "par", "pas",
        "que", "qui", "quoi", "dont", "quand", "alors", "donc", "aussi", "bien", "tres",
        "trop", "peut", "peux", "veux", "veut", "vais", "alle", "aller", "vient", "deja",
        "encore", "toujours", "jamais", "rien", "personne", "quelque", "chose", "ici", "la",
        "oui", "non", "ouais", "mdr", "ptdr", "lol", "okay", "dacc", "merci", "salut", "coucou",
        "bonjour", "bonsoir", "stp", "svp", "cest", "jai", "tas", "quil", "quelle", "mais",
        "meme", "apres", "avant", "entre", "chez", "vers", "sans", "comment", "pourquoi",
        "parce", "voila", "genre", "truc", "machin", "bah", "ben", "euh", "hein", "bon",
        "vrai", "juste", "faut", "dire", "dit", "vois", "voir", "sais", "sait", "crois",
        "pense", "temps", "jour", "heure", "heures", "aujourdhui", "demain", "hier", "soir",
        "matin", "maintenant", "bientot", "vite", "grave", "trop", "super", "cool", "photo",
        "message", "appel", "envoye", "envoyee", "manque", "nouveau", "nouveaux"
    )

    private fun normalize(raw: String): String {
        val nfd = Normalizer.normalize(raw.lowercase(Locale.FRENCH), Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    }

    fun buildInsightsReport(context: Context): JSONObject {
        val now = System.currentTimeMillis()
        val thirtyDaysAgo = now - 30L * 86_400_000L
        val sevenDaysAgo = now - 7L * 86_400_000L

        data class ContactStats(
            var displayName: String,
            var phoneOrHandle: String,
            var smsCount: Int = 0,
            var callCount: Int = 0,
            var socialCount: Int = 0,
            val apps: LinkedHashSet<String> = LinkedHashSet(),
            var firstSeenMs: Long = Long.MAX_VALUE,
            var lastSeenMs: Long = 0L
        ) {
            val totalScore: Int get() = smsCount + socialCount + callCount * 2
        }

        val contactsMap = LinkedHashMap<String, ContactStats>()
        val corpus = ArrayList<String>(1200)

        fun touchContact(
            key: String,
            name: String,
            handle: String,
            ts: Long,
            channel: String,
            appLabel: String? = null
        ) {
            if (key.isBlank()) return
            val entry = contactsMap.getOrPut(key) {
                ContactStats(
                    displayName = name.ifBlank { handle },
                    phoneOrHandle = handle
                )
            }
            if (name.isNotBlank() && (entry.displayName == entry.phoneOrHandle || entry.displayName.isBlank())) {
                entry.displayName = name
            }
            when (channel) {
                "sms" -> entry.smsCount++
                "call" -> entry.callCount++
                "social" -> entry.socialCount++
            }
            if (!appLabel.isNullOrBlank()) entry.apps.add(appLabel)
            if (ts > 0L) {
                if (ts < entry.firstSeenMs) entry.firstSeenMs = ts
                if (ts > entry.lastSeenMs) entry.lastSeenMs = ts
            }
        }

        // 1. Scan Social Messages Archive (WhatsApp, Insta, Snap, Discord, etc.)
        val socialMsgs = SocialMessageStore.getAllMessages(context)
        for (m in socialMsgs) {
            if (m.timestamp in thirtyDaysAgo..now) {
                val key = "soc:${normalize(m.sender)}"
                touchContact(key, m.sender, m.appLabel, m.timestamp, "social", m.appLabel)
                if (corpus.size < 1800) corpus.add(m.body)
            }
        }

        // 2. Scan SMS (last 30 days, up to 1200 messages)
        if (SmsInboxHelper.hasReadSmsPermission(context)) {
            val contactNameCache = HashMap<String, String>()
            try {
                context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                    "${Telephony.Sms.DATE} >= ?",
                    arrayOf(thirtyDaysAgo.toString()),
                    "${Telephony.Sms.DATE} DESC"
                )?.use { c ->
                    var count = 0
                    while (c.moveToNext() && count < 1200) {
                        count++
                        val addr = c.getString(0)?.trim().orEmpty()
                        val body = c.getString(1).orEmpty()
                        val date = c.getLong(2)
                        if (addr.isEmpty()) continue
                        val normKey = CallLogHelper.normalizePhoneKey(addr)
                        val key = if (normKey.isNotEmpty()) "tel:$normKey" else "tel:${addr.lowercase()}"
                        val resolved = contactNameCache.getOrPut(addr) { resolveContactQuick(context, addr) }
                        touchContact(key, resolved, addr, date, "sms", "SMS")
                        if (body.isNotBlank() && corpus.size < 2400) corpus.add(body)
                    }
                }
            } catch (_: Throwable) {
            }
        }

        // 3. Scan Call Log (last 30 days, up to 250 calls)
        if (CallLogHelper.hasCallLogPermission(context)) {
            try {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE),
                    "${CallLog.Calls.DATE} >= ?",
                    arrayOf(thirtyDaysAgo.toString()),
                    "${CallLog.Calls.DATE} DESC"
                )?.use { c ->
                    var count = 0
                    while (c.moveToNext() && count < 250) {
                        count++
                        val num = c.getString(0)?.trim().orEmpty()
                        val name = c.getString(1)?.trim().orEmpty()
                        val date = c.getLong(2)
                        if (num.isEmpty()) continue
                        val normKey = CallLogHelper.normalizePhoneKey(num)
                        val key = if (normKey.isNotEmpty()) "tel:$normKey" else "tel:${num.lowercase()}"
                        touchContact(key, name, num, date, "call", "Appels")
                    }
                }
            } catch (_: Throwable) {
            }
        }

        // Merge contacts that share the exact same normalized display name across tel: & soc:
        val mergedByName = LinkedHashMap<String, ContactStats>()
        for ((k, st) in contactsMap) {
            val mergeKey = normalize(st.displayName).takeIf { it.length >= 3 } ?: k
            val existing = mergedByName[mergeKey]
            if (existing == null) {
                mergedByName[mergeKey] = st
            } else {
                existing.smsCount += st.smsCount
                existing.callCount += st.callCount
                existing.socialCount += st.socialCount
                existing.apps.addAll(st.apps)
                if (st.firstSeenMs < existing.firstSeenMs) existing.firstSeenMs = st.firstSeenMs
                if (st.lastSeenMs > existing.lastSeenMs) existing.lastSeenMs = st.lastSeenMs
            }
        }

        val topContacts = mergedByName.values
            .sortedByDescending { it.totalScore }
            .take(12)

        val topContactsArr = JSONArray()
        for (c in topContacts) {
            val isNewIntensive = c.firstSeenMs >= sevenDaysAgo && c.totalScore >= 12
            topContactsArr.put(
                JSONObject().apply {
                    put("name", c.displayName)
                    put("handle", c.phoneOrHandle)
                    put("smsCount", c.smsCount)
                    put("callCount", c.callCount)
                    put("socialCount", c.socialCount)
                    put("totalInteractions", c.smsCount + c.callCount + c.socialCount)
                    put("channels", JSONArray(c.apps.toList()))
                    put("lastSeenMs", c.lastSeenMs)
                    put("isNewIntensive", isNewIntensive)
                }
            )
        }

        // 4. Local Topic & Recurring Words Synthesis across all collected messages
        val topicHits = HashMap<String, Int>()
        val topicMatchedWords = HashMap<String, LinkedHashSet<String>>()
        val wordCounts = HashMap<String, Int>()

        for (rawText in corpus) {
            val norm = normalize(rawText)
            val tokens = norm.split(Regex("[^a-z0-9]+")).filter { it.length >= 4 && !it.all { ch -> ch.isDigit() } }
            val uniqueTokensInMsg = HashSet<String>(tokens)

            for (tok in uniqueTokensInMsg) {
                if (tok !in FRENCH_STOPWORDS && tok.length <= 20) {
                    wordCounts[tok] = (wordCounts[tok] ?: 0) + 1
                }
            }

            for (topic in TOPICS) {
                var matched = false
                for (stem in topic.stems) {
                    val hit = if (stem.contains(' ')) {
                        norm.contains(stem)
                    } else {
                        uniqueTokensInMsg.any { it == stem || (stem.length >= 5 && it.startsWith(stem)) }
                    }
                    if (hit) {
                        matched = true
                        val bag = topicMatchedWords.getOrPut(topic.id) { LinkedHashSet() }
                        if (bag.size < 5) bag.add(stem)
                    }
                }
                if (matched) {
                    topicHits[topic.id] = (topicHits[topic.id] ?: 0) + 1
                }
            }
        }

        val totalTopicHits = topicHits.values.sum().coerceAtLeast(1)
        val topicsArr = JSONArray()
        for (topic in TOPICS.sortedByDescending { topicHits[it.id] ?: 0 }) {
            val hits = topicHits[topic.id] ?: 0
            if (hits <= 0) continue
            val pct = ((hits * 100) / totalTopicHits).coerceIn(1, 100)
            topicsArr.put(
                JSONObject().apply {
                    put("id", topic.id)
                    put("label", topic.label)
                    put("count", hits)
                    put("percent", pct)
                    put("alert", topic.alert)
                    put("samples", JSONArray(topicMatchedWords[topic.id]?.toList().orEmpty()))
                }
            )
        }

        val topWordsArr = JSONArray()
        for ((word, cnt) in wordCounts.entries.filter { it.value >= 2 }.sortedByDescending { it.value }.take(14)) {
            topWordsArr.put(
                JSONObject().apply {
                    put("word", word)
                    put("count", cnt)
                }
            )
        }

        // 5. Nighttime Usage Analysis (22:00 – 06:00 over last 7 days)
        val nightObj = computeNighttimeUsage(context, now)

        return JSONObject().apply {
            put("analyzedMessagesCount", corpus.size)
            put("topContacts", topContactsArr)
            put("topics", topicsArr)
            put("recurringWords", topWordsArr)
            put("nightUsage", nightObj)
        }
    }

    private fun computeNighttimeUsage(context: Context, now: Long): JSONObject {
        if (!ScreenTimeHelper.hasUsageAccess(context)) {
            return JSONObject().apply {
                put("available", false)
                put("totalNightMinutes7d", 0)
                put("latestNightTimestamp", 0L)
                put("topNightApps", JSONArray())
            }
        }

        val nightMsByPkg = HashMap<String, Long>()
        var latestNightTs = 0L
        val sevenDaysAgo = now - 7L * 86_400_000L

        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val events = usm.queryEvents(sevenDaysAgo, now)
            val ev = UsageEvents.Event()
            val activeStart = HashMap<String, Long>()
            val cal = Calendar.getInstance()

            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                val pkg = ev.packageName ?: continue
                if (ScreenTimeHelper.isProtectedSystemPackage(context, pkg)) continue
                val ts = ev.timeStamp
                val isResumed = ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    @Suppress("DEPRECATION") (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND)
                val isPaused = ev.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                    @Suppress("DEPRECATION") (ev.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND)

                if (isResumed) {
                    activeStart[pkg] = ts
                    cal.timeInMillis = ts
                    val h = cal.get(Calendar.HOUR_OF_DAY)
                    if ((h >= 22 || h < 6) && ts > latestNightTs) {
                        latestNightTs = ts
                    }
                } else if (isPaused) {
                    val start = activeStart.remove(pkg) ?: continue
                    if (ts > start) {
                        cal.timeInMillis = start
                        val h = cal.get(Calendar.HOUR_OF_DAY)
                        if (h >= 22 || h < 6) {
                            val delta = (ts - start).coerceAtMost(2 * 3600_000L)
                            nightMsByPkg[pkg] = (nightMsByPkg[pkg] ?: 0L) + delta
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }

        val pm = context.packageManager
        val totalMin = (nightMsByPkg.values.sum() / 60_000L).toInt()
        val appsArr = JSONArray()
        for ((pkg, ms) in nightMsByPkg.entries.sortedByDescending { it.value }.take(6)) {
            val mins = (ms / 60_000L).toInt()
            if (mins <= 0) continue
            appsArr.put(
                JSONObject().apply {
                    put("packageName", pkg)
                    put("appName", ScreenTimeHelper.resolveAppName(pm, pkg))
                    put("minutes", mins)
                }
            )
        }

        return JSONObject().apply {
            put("available", true)
            put("totalNightMinutes7d", totalMin)
            put("latestNightTimestamp", latestNightTs)
            put("topNightApps", appsArr)
        }
    }

    private fun resolveContactQuick(context: Context, phone: String): String {
        return try {
            val uri = android.net.Uri.withAppendedPath(
                android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(phone)
            )
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else "" } ?: ""
        } catch (_: Throwable) {
            ""
        }
    }
}
