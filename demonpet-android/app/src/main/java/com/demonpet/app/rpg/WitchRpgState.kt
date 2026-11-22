package com.demonpet.app.rpg

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent Full-RPG Progression, Inventory, Bestiary & AI Key Store for DemonMom RPG v5.0:
 * - Tracks XP, Soul Shards (Gold), Unlocked Chapters (1..5), 50-Witch Bestiary (Encountered / Defeated / LostTo),
 *   Consumable Relics (Elixir, Crystal, Talisman, Amulet), and optional user Gemini API key for live cloud AI dialogue.
 */
class WitchRpgState(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Snapshot(
        val xp: Int,
        val gold: Int,
        val unlockedChapter: Int,
        val selectedChapter: Int,
        val elixirs: Int,          // +60 HP
        val crystals: Int,         // +40 MP
        val talismans: Int,        // Instant escape from Capture!
        val amulets: Int,          // Purifies status + visual/brightness drain!
        val encounteredIds: Set<Int>,
        val defeatedIds: Set<Int>,
        val lostToIds: Set<Int>,
        val totalWins: Int,
        val totalDefeats: Int,
        val geminiApiKey: String
    )

    fun snapshot(): Snapshot = Snapshot(
        xp = prefs.getInt(KEY_XP, 0),
        gold = prefs.getInt(KEY_GOLD, 90),
        unlockedChapter = prefs.getInt(KEY_UNLOCKED_CH, 1).coerceIn(1, 5),
        selectedChapter = prefs.getInt(KEY_SELECTED_CH, 1).coerceIn(1, 5),
        elixirs = prefs.getInt(KEY_ELIXIRS, 2).coerceAtLeast(0),
        crystals = prefs.getInt(KEY_CRYSTALS, 2).coerceAtLeast(0),
        talismans = prefs.getInt(KEY_TALISMANS, 2).coerceAtLeast(0),
        amulets = prefs.getInt(KEY_AMULETS, 1).coerceAtLeast(0),
        encounteredIds = readIntSet(KEY_ENCOUNTERED),
        defeatedIds = readIntSet(KEY_DEFEATED),
        lostToIds = readIntSet(KEY_LOST_TO),
        totalWins = prefs.getInt(KEY_WINS, 0),
        totalDefeats = prefs.getInt(KEY_DEFEATS, 0),
        geminiApiKey = prefs.getString(KEY_GEMINI_KEY, "").orEmpty()
    )

    private fun readIntSet(key: String): Set<Int> {
        return prefs.getStringSet(key, emptySet())
            .orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .toSet()
    }

    private fun saveIntSet(editor: SharedPreferences.Editor, key: String, set: Set<Int>) {
        editor.putStringSet(key, set.map { it.toString() }.toSet())
    }

    var selectedChapter: Int
        get() = prefs.getInt(KEY_SELECTED_CH, 1).coerceIn(1, 5)
        set(v) = prefs.edit().putInt(KEY_SELECTED_CH, v.coerceIn(1, 5)).apply()

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_KEY, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_GEMINI_KEY, v.trim()).apply()

    fun markEncountered(witchId: Int) {
        val next = readIntSet(KEY_ENCOUNTERED) + witchId
        prefs.edit().apply { saveIntSet(this, KEY_ENCOUNTERED, next) }.apply()
    }

    /** Records Victory: awards XP, Gold, unlocks next chapter on Boss defeat, and drops occasional bonus relic! */
    fun recordVictory(witch: WitchCatalog.WitchDef): Triple<Int, Boolean, String?> {
        val snap = snapshot()
        val newXp = snap.xp + witch.rewardXp
        val levelUps = newXp / 100
        val remXp = newXp % 100
        val newGold = snap.gold + witch.rewardGold
        val newDefeated = snap.defeatedIds + witch.id
        val newEnc = snap.encounteredIds + witch.id
        var nextCh = snap.unlockedChapter
        var unlockedNewChapter = false
        if (witch.isBoss && witch.chapter >= nextCh && nextCh < 5) {
            nextCh = witch.chapter + 1
            unlockedNewChapter = true
        }
        val drop = when {
            witch.isBoss -> "Talisman de Rupture"
            witch.isMiniBoss -> "Élixir de Volonté"
            else -> null
        }

        prefs.edit().apply {
            putInt(KEY_XP, remXp)
            putInt(KEY_GOLD, newGold)
            putInt(KEY_UNLOCKED_CH, nextCh)
            putInt(KEY_WINS, snap.totalWins + 1)
            if (drop == "Talisman de Rupture") putInt(KEY_TALISMANS, snap.talismans + 1)
            if (drop == "Élixir de Volonté") putInt(KEY_ELIXIRS, snap.elixirs + 1)
            saveIntSet(this, KEY_DEFEATED, newDefeated)
            saveIntSet(this, KEY_ENCOUNTERED, newEnc)
        }.apply()

        return Triple(levelUps, unlockedNewChapter, drop)
    }

    fun recordDefeat(witch: WitchCatalog.WitchDef) {
        val snap = snapshot()
        val newLost = snap.lostToIds + witch.id
        val newEnc = snap.encounteredIds + witch.id
        prefs.edit().apply {
            putInt(KEY_DEFEATS, snap.totalDefeats + 1)
            putInt(KEY_GOLD, snap.gold + 10) // Small consolation shard drop
            saveIntSet(this, KEY_LOST_TO, newLost)
            saveIntSet(this, KEY_ENCOUNTERED, newEnc)
        }.apply()
    }

    fun consumeItem(kind: String): Boolean {
        val key = when (kind) {
            "elixir" -> KEY_ELIXIRS
            "crystal" -> KEY_CRYSTALS
            "talisman" -> KEY_TALISMANS
            "amulet" -> KEY_AMULETS
            else -> return false
        }
        val cur = prefs.getInt(key, 0)
        if (cur <= 0) return false
        prefs.edit().putInt(key, cur - 1).apply()
        return true
    }

    fun buyItem(kind: String, cost: Int): Boolean {
        val key = when (kind) {
            "elixir" -> KEY_ELIXIRS
            "crystal" -> KEY_CRYSTALS
            "talisman" -> KEY_TALISMANS
            "amulet" -> KEY_AMULETS
            else -> return false
        }
        val gold = prefs.getInt(KEY_GOLD, 0)
        if (gold < cost) return false
        val cur = prefs.getInt(key, 0)
        prefs.edit()
            .putInt(KEY_GOLD, gold - cost)
            .putInt(key, cur + 1)
            .apply()
        return true
    }

    companion object {
        private const val PREFS_NAME = "demonmom_rpg_save_v5"
        private const val KEY_XP = "xp"
        private const val KEY_GOLD = "gold"
        private const val KEY_UNLOCKED_CH = "unlocked_ch"
        private const val KEY_SELECTED_CH = "selected_ch"
        private const val KEY_ELIXIRS = "elixirs"
        private const val KEY_CRYSTALS = "crystals"
        private const val KEY_TALISMANS = "talismans"
        private const val KEY_AMULETS = "amulets"
        private const val KEY_ENCOUNTERED = "encountered_ids"
        private const val KEY_DEFEATED = "defeated_ids"
        private const val KEY_LOST_TO = "lost_to_ids"
        private const val KEY_WINS = "total_wins"
        private const val KEY_DEFEATS = "total_defeats"
        private const val KEY_GEMINI_KEY = "gemini_api_key"
    }
}
