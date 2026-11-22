package com.demonpet.app.rpg

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent Full-RPG Progression, Forge Equipment, 6 Consumable Relics, Quests, Infinite Tower & AI Store (v6.0):
 * - Permanent Forge upgrades (Blade, Runic Cloak, Inner Grimoire, Anti-Charm Ward) up to Rank 10!
 * - 6 Strategic Battle Relics (Élixir, Cristal, Talisman, Amulette, Encens de Furie, Voile d'Égide).
 * - Claimable RPG Quests & Infinite Witch Tower Best Floor record.
 * - Complete 50-Witch Bestiary tracking + optional Gemini API key.
 */
class WitchRpgState(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class QuestDef(
        val id: String,
        val title: String,
        val rewardText: String,
        val rewardGold: Int,
        val bonusItemKey: String?,
        val currentProgress: Int,
        val targetProgress: Int,
        val claimed: Boolean
    ) {
        val completed: Boolean get() = currentProgress >= targetProgress
    }

    data class Snapshot(
        val xp: Int,
        val gold: Int,
        val unlockedChapter: Int,
        val selectedChapter: Int,
        // 6 Consumable Relics
        val elixirs: Int,          // +65 PV
        val crystals: Int,         // +45 PM, +25 SP
        val talismans: Int,        // Instant escape from Capture + -50% Emprise
        val amulets: Int,          // Purifies ALL curses/status/siphon + visual drain
        val incenses: Int,         // x1.6 attack damage for 3 turns!
        val aegisVeils: Int,       // Absorbs next Witch attack/capture completely!
        // 4 Permanent Soul Forge Upgrades (Rank 1..10)
        val bladeRank: Int,        // +12% dmg per rank
        val cloakRank: Int,        // Max PV = 100 + (rank-1)*14, -4% Emprise gain per rank
        val grimoireRank: Int,     // Max PM = 60 + (rank-1)*10, +2 SP per rank
        val wardRank: Int,         // Status resistance + QTE bonus
        // Bestiary & Records
        val encounteredIds: Set<Int>,
        val defeatedIds: Set<Int>,
        val lostToIds: Set<Int>,
        val totalWins: Int,
        val totalDefeats: Int,
        val perfectParries: Int,
        val qteBreaks: Int,
        val towerBestFloor: Int,
        val claimedQuests: Set<String>,
        val geminiApiKey: String,
        // v7.0 Story, Alignment & Full Powers
        val storyStep: Int,                    // 0..15 (3 scenes per chapter!)
        val corruption: Int,                   // -100 (Volonté) .. +100 (Ombre/Fascination)
        val forcedFullscreen: Boolean,         // Immersive sticky fullscreen during battles
        val forcedOverlayBattle: Boolean,      // Lock battle above entire OS via Accessibility Overlay when available
        val midBattleAppPowers: Boolean,       // Witches seize/grey real apps LIVE mid-combat!
        val touchSabotageEnabled: Boolean      // Witches hijack buttons, dodge fingers, frost/scramble touch!
    ) {
        val maxHp: Int get() = 100 + (cloakRank - 1) * 14
        val maxMp: Int get() = 60 + (grimoireRank - 1) * 10
        val attackMultiplier: Float get() = 1.0f + (bladeRank - 1) * 0.12f
        val embraceResistMult: Float get() = (1.0f - (cloakRank - 1) * 0.04f - (wardRank - 1) * 0.03f).coerceAtLeast(0.50f)
        val spBonusPerAction: Int get() = (grimoireRank - 1) * 2
        val totalItems: Int get() = elixirs + crystals + talismans + amulets + incenses + aegisVeils
        val alignmentLabel: String get() = when {
            corruption <= -15 -> "🔥 Volonté ($corruption)"
            corruption >= 20 -> "💜 Ombre (+$corruption)"
            else -> "⚖️ Équilibre ($corruption)"
        }
    }

    fun snapshot(): Snapshot = Snapshot(
        xp = prefs.getInt(KEY_XP, 0),
        gold = prefs.getInt(KEY_GOLD, 120),
        unlockedChapter = prefs.getInt(KEY_UNLOCKED_CH, 1).coerceIn(1, 5),
        selectedChapter = prefs.getInt(KEY_SELECTED_CH, 1).coerceIn(1, 5),
        elixirs = prefs.getInt(KEY_ELIXIRS, 2).coerceAtLeast(0),
        crystals = prefs.getInt(KEY_CRYSTALS, 2).coerceAtLeast(0),
        talismans = prefs.getInt(KEY_TALISMANS, 2).coerceAtLeast(0),
        amulets = prefs.getInt(KEY_AMULETS, 2).coerceAtLeast(0),
        incenses = prefs.getInt(KEY_INCENSES, 1).coerceAtLeast(0),
        aegisVeils = prefs.getInt(KEY_AEGIS, 1).coerceAtLeast(0),
        bladeRank = prefs.getInt(KEY_BLADE_RANK, 1).coerceIn(1, 10),
        cloakRank = prefs.getInt(KEY_CLOAK_RANK, 1).coerceIn(1, 10),
        grimoireRank = prefs.getInt(KEY_GRIMOIRE_RANK, 1).coerceIn(1, 10),
        wardRank = prefs.getInt(KEY_WARD_RANK, 1).coerceIn(1, 10),
        encounteredIds = readIntSet(KEY_ENCOUNTERED),
        defeatedIds = readIntSet(KEY_DEFEATED),
        lostToIds = readIntSet(KEY_LOST_TO),
        totalWins = prefs.getInt(KEY_WINS, 0),
        totalDefeats = prefs.getInt(KEY_DEFEATS, 0),
        perfectParries = prefs.getInt(KEY_PARRIES, 0),
        qteBreaks = prefs.getInt(KEY_QTE_BREAKS, 0),
        towerBestFloor = prefs.getInt(KEY_TOWER_BEST, 0),
        claimedQuests = HashSet(prefs.getStringSet(KEY_CLAIMED_QUESTS, emptySet()).orEmpty()),
        geminiApiKey = prefs.getString(KEY_GEMINI_KEY, "").orEmpty(),
        storyStep = prefs.getInt(KEY_STORY_STEP, 0).coerceIn(0, 15),
        corruption = prefs.getInt(KEY_CORRUPTION, 0).coerceIn(-100, 100),
        forcedFullscreen = prefs.getBoolean(KEY_FORCED_FS, true),
        forcedOverlayBattle = prefs.getBoolean(KEY_FORCED_OV, false),
        midBattleAppPowers = prefs.getBoolean(KEY_MID_APP_POWERS, true),
        touchSabotageEnabled = prefs.getBoolean(KEY_TOUCH_SABOTAGE, true)
    )

    private fun readIntSet(key: String): Set<Int> =
        prefs.getStringSet(key, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()

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

    fun recordPerfectParry() {
        prefs.edit().putInt(KEY_PARRIES, prefs.getInt(KEY_PARRIES, 0) + 1).apply()
    }

    fun recordQteBreak() {
        prefs.edit().putInt(KEY_QTE_BREAKS, prefs.getInt(KEY_QTE_BREAKS, 0) + 1).apply()
    }

    fun recordTowerFloor(floor: Int) {
        val best = prefs.getInt(KEY_TOWER_BEST, 0)
        if (floor > best) prefs.edit().putInt(KEY_TOWER_BEST, floor).apply()
    }

    /** Records Victory: awards XP, Gold (with Tower bonus multiplier!), unlocks next chapter on Boss defeat, drops relic! */
    fun recordVictory(witch: WitchCatalog.WitchDef, bonusMultiplier: Float = 1.0f): Triple<Int, Boolean, String?> {
        val snap = snapshot()
        val gainXp = (witch.rewardXp * bonusMultiplier).toInt()
        val gainGold = (witch.rewardGold * bonusMultiplier).toInt()
        val newXp = snap.xp + gainXp
        val levelUps = newXp / 100
        val remXp = newXp % 100
        val newGold = snap.gold + gainGold
        val newDefeated = snap.defeatedIds + witch.id
        val newEnc = snap.encounteredIds + witch.id
        var nextCh = snap.unlockedChapter
        var unlockedNewChapter = false
        if (witch.isBoss && witch.chapter >= nextCh && nextCh < 5) {
            nextCh = witch.chapter + 1
            unlockedNewChapter = true
        }
        val drop = when {
            witch.isBoss -> "Voile d'Égide"
            witch.isMiniBoss -> "Encens de Furie"
            else -> null
        }

        prefs.edit().apply {
            putInt(KEY_XP, remXp)
            putInt(KEY_GOLD, newGold)
            putInt(KEY_UNLOCKED_CH, nextCh)
            putInt(KEY_WINS, snap.totalWins + 1)
            if (drop == "Voile d'Égide") putInt(KEY_AEGIS, snap.aegisVeils + 1)
            if (drop == "Encens de Furie") putInt(KEY_INCENSES, snap.incenses + 1)
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
            putInt(KEY_GOLD, snap.gold + 15)
            saveIntSet(this, KEY_LOST_TO, newLost)
            saveIntSet(this, KEY_ENCOUNTERED, newEnc)
        }.apply()
    }

    private fun itemPrefKey(kind: String): String? = when (kind) {
        "elixir" -> KEY_ELIXIRS
        "crystal" -> KEY_CRYSTALS
        "talisman" -> KEY_TALISMANS
        "amulet" -> KEY_AMULETS
        "incense" -> KEY_INCENSES
        "aegis" -> KEY_AEGIS
        else -> null
    }

    fun consumeItem(kind: String): Boolean {
        val key = itemPrefKey(kind) ?: return false
        val cur = prefs.getInt(key, 0)
        if (cur <= 0) return false
        prefs.edit().putInt(key, cur - 1).apply()
        return true
    }

    fun buyItem(kind: String, cost: Int): Boolean {
        val key = itemPrefKey(kind) ?: return false
        val gold = prefs.getInt(KEY_GOLD, 0)
        if (gold < cost) return false
        val cur = prefs.getInt(key, 0)
        prefs.edit()
            .putInt(KEY_GOLD, gold - cost)
            .putInt(key, cur + 1)
            .apply()
        return true
    }

    fun forgeUpgradeCost(currentRank: Int): Int = 55 + currentRank * 45

    fun upgradeForge(slot: String): Boolean {
        val key = when (slot) {
            "blade" -> KEY_BLADE_RANK
            "cloak" -> KEY_CLOAK_RANK
            "grimoire" -> KEY_GRIMOIRE_RANK
            "ward" -> KEY_WARD_RANK
            else -> return false
        }
        val cur = prefs.getInt(key, 1).coerceIn(1, 10)
        if (cur >= 10) return false
        val cost = forgeUpgradeCost(cur)
        val gold = prefs.getInt(KEY_GOLD, 0)
        if (gold < cost) return false
        prefs.edit()
            .putInt(KEY_GOLD, gold - cost)
            .putInt(key, cur + 1)
            .apply()
        return true
    }

    fun getQuests(): List<QuestDef> {
        val s = snapshot()
        val bossesDefeated = WitchCatalog.ALL_WITCHES.count { it.isBoss && s.defeatedIds.contains(it.id) }
        return listOf(
            QuestDef("q_first_win", "Première Victoire", "+90 💎 + 1 Élixir", 90, "elixir", s.totalWins, 1, s.claimedQuests.contains("q_first_win")),
            QuestDef("q_parry_3", "Maître de la Parade (3 Parades Parfaites)", "+160 💎 + 1 Encens", 160, "incense", s.perfectParries, 3, s.claimedQuests.contains("q_parry_3")),
            QuestDef("q_qte_3", "Briseur d'Entraves (3 QTE réussis)", "+150 💎 + 1 Talisman", 150, "talisman", s.qteBreaks, 3, s.claimedQuests.contains("q_qte_3")),
            QuestDef("q_boss_1", "Tombeur d'Archisorcière (1 Boss)", "+240 💎 + 1 Voile d'Égide", 240, "aegis", bossesDefeated, 1, s.claimedQuests.contains("q_boss_1")),
            QuestDef("q_tower_3", "Ascension (Étage 3 Tour Infinie)", "+220 💎 + 1 Cristal", 220, "crystal", s.towerBestFloor, 3, s.claimedQuests.contains("q_tower_3")),
            QuestDef("q_grim_10", "Érudit du Coven (10/50 Sorcières)", "+350 💎 + 1 Voile d'Égide", 350, "aegis", s.defeatedIds.size, 10, s.claimedQuests.contains("q_grim_10"))
        )
    }

    fun claimQuest(questId: String): Boolean {
        val q = getQuests().firstOrNull { it.id == questId } ?: return false
        if (!q.completed || q.claimed) return false
        val claimed = HashSet(prefs.getStringSet(KEY_CLAIMED_QUESTS, emptySet()).orEmpty())
        claimed.add(questId)
        val gold = prefs.getInt(KEY_GOLD, 0) + q.rewardGold
        val ed = prefs.edit().putInt(KEY_GOLD, gold).putStringSet(KEY_CLAIMED_QUESTS, claimed)
        q.bonusItemKey?.let { bk ->
            itemPrefKey(bk)?.let { pk -> ed.putInt(pk, prefs.getInt(pk, 0) + 1) }
        }
        ed.apply()
        return true
    }

    var forcedFullscreen: Boolean
        get() = prefs.getBoolean(KEY_FORCED_FS, true)
        set(v) = prefs.edit().putBoolean(KEY_FORCED_FS, v).apply()

    var forcedOverlayBattle: Boolean
        get() = prefs.getBoolean(KEY_FORCED_OV, false)
        set(v) = prefs.edit().putBoolean(KEY_FORCED_OV, v).apply()

    var midBattleAppPowers: Boolean
        get() = prefs.getBoolean(KEY_MID_APP_POWERS, true)
        set(v) = prefs.edit().putBoolean(KEY_MID_APP_POWERS, v).apply()

    var touchSabotageEnabled: Boolean
        get() = prefs.getBoolean(KEY_TOUCH_SABOTAGE, true)
        set(v) = prefs.edit().putBoolean(KEY_TOUCH_SABOTAGE, v).apply()

    fun advanceStoryIfAt(milestoneIndex: Int) {
        if (milestoneIndex < 0) return
        val cur = prefs.getInt(KEY_STORY_STEP, 0)
        if (cur == milestoneIndex) {
            prefs.edit().putInt(KEY_STORY_STEP, (cur + 1).coerceAtMost(15)).apply()
        }
    }

    fun adjustCorruption(delta: Int) {
        if (delta == 0) return
        val cur = prefs.getInt(KEY_CORRUPTION, 0)
        prefs.edit().putInt(KEY_CORRUPTION, (cur + delta).coerceIn(-100, 100)).apply()
    }

    fun grantReward(goldDelta: Int, itemKey: String? = null) {
        val ed = prefs.edit()
        if (goldDelta != 0) ed.putInt(KEY_GOLD, (prefs.getInt(KEY_GOLD, 0) + goldDelta).coerceAtLeast(0))
        if (itemKey != null) {
            itemPrefKey(itemKey)?.let { pk -> ed.putInt(pk, prefs.getInt(pk, 0) + 1) }
        }
        ed.apply()
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
        private const val KEY_INCENSES = "incenses"
        private const val KEY_AEGIS = "aegis_veils"
        private const val KEY_BLADE_RANK = "forge_blade"
        private const val KEY_CLOAK_RANK = "forge_cloak"
        private const val KEY_GRIMOIRE_RANK = "forge_grimoire"
        private const val KEY_WARD_RANK = "forge_ward"
        private const val KEY_ENCOUNTERED = "encountered_ids"
        private const val KEY_DEFEATED = "defeated_ids"
        private const val KEY_LOST_TO = "lost_to_ids"
        private const val KEY_WINS = "total_wins"
        private const val KEY_DEFEATS = "total_defeats"
        private const val KEY_PARRIES = "perfect_parries"
        private const val KEY_QTE_BREAKS = "qte_breaks"
        private const val KEY_TOWER_BEST = "tower_best_floor"
        private const val KEY_CLAIMED_QUESTS = "claimed_quests"
        private const val KEY_GEMINI_KEY = "gemini_api_key"
        private const val KEY_STORY_STEP = "story_step_v7"
        private const val KEY_CORRUPTION = "corruption_v7"
        private const val KEY_FORCED_FS = "forced_fs_v7"
        private const val KEY_FORCED_OV = "forced_ov_v7"
        private const val KEY_MID_APP_POWERS = "mid_app_powers_v7"
        private const val KEY_TOUCH_SABOTAGE = "touch_sabotage_v7"
    }
}
