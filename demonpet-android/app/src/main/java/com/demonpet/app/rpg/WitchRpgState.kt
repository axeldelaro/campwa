package com.demonpet.app.rpg

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/**
 * Persistent Full-RPG & Little Demon Progression Engine (v10.0 — 15+ Hours Campaign):
 * - 6 Permanent Soul Forge Upgrades (Rank 1..15): Blade, Cloak, Grimoire, Anti-Charm Ward, Spirit Ring, Demon Heart.
 * - 10 Strategic Battle Relics (Elixir, Crystal, Talisman, Amulet, Incense, Aegis, Posture Bomb, Holy Water, Ether Elixir, Hourglass).
 * - 4 Elemental Spirits (Zéphyr, Gaïa, Ondine, Ignis), 55-Witch Bestiary + Bond Levels (0..5), 5 Secret Superbosses.
 * - 12 Tactical Bounties, 24 Stat-Granting Titles, 5-Room Expeditions, Daily 7-Day Streak, Quiz Score.
 * - Little Demon Evolution (4 Forms), Affection/Hunger/XP, Shizuku Focus Contracts & Smart Quiet Hours!
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

    data class DemonForm(val index: Int, val name: String, val badge: String, val perkDesc: String)

    data class Snapshot(
        val xp: Int,
        val gold: Int,
        val unlockedChapter: Int,
        val selectedChapter: Int,
        // 10 Consumable Relics
        val elixirs: Int,          // +72 PV
        val crystals: Int,         // +48 PM, +25 SP
        val talismans: Int,        // Instant escape from Capture + -50% Emprise
        val amulets: Int,          // Purifies ALL curses/status/siphon + visual drain
        val incenses: Int,         // x1.6 attack damage for 3 turns!
        val aegisVeils: Int,       // Absorbs next Witch attack/capture completely!
        val postureBombs: Int,     // -75 Posture Boss + breaks Barrier/Thorn!
        val holyWaters: Int,       // Resets Witch Adaptive Anti-Spam 100% + clears Weather!
        val etherElixirs: Int,     // +100 SP instant Overdrive!
        val hourglasses: Int,      // Stuns Witch 1 turn + -35% Emprise!
        // 6 Permanent Soul Forge Upgrades (Rank 1..15)
        val bladeRank: Int,
        val cloakRank: Int,
        val grimoireRank: Int,
        val wardRank: Int,
        val spiritRingRank: Int,
        val demonHeartRank: Int,
        // Bestiary, Superbosses, Bonds & Records
        val encounteredIds: Set<Int>,
        val defeatedIds: Set<Int>,
        val lostToIds: Set<Int>,
        val defeatedSuperbossIds: Set<Int>,
        val unlockedSpirits: Set<String>,
        val totalWins: Int,
        val totalDefeats: Int,
        val perfectParries: Int,
        val qteBreaks: Int,
        val towerBestFloor: Int,
        val dungeonsCompleted: Int,
        val quizCorrectCount: Int,
        val claimedQuests: Set<String>,
        val completedBounties: Set<String>,
        val claimedBounties: Set<String>,
        val dailyStreak: Int,
        val canClaimDailyToday: Boolean,
        val geminiApiKey: String,
        // Story, Alignment & Full Powers
        val storyStep: Int,
        val corruption: Int,
        val forcedFullscreen: Boolean,
        val shizukuTaskPinning: Boolean,
        val forcedOverlayBattle: Boolean,
        val midBattleAppPowers: Boolean,
        val touchSabotageEnabled: Boolean,
        val combatSfxEnabled: Boolean,
        // Little Demon Evolution, Care, Focus & Quiet Hours
        val demonAffection: Int,   // 0..100
        val demonHunger: Int,      // 0..100
        val demonXp: Int,
        val focusSessionsDone: Int,
        val activeFocusUntilMs: Long,
        val quietHoursEnabled: Boolean,
        val quietStartHour: Int,
        val quietEndHour: Int,
        val totalBondLevels: Int,
        // Aggregated Title Bonuses
        val unlockedTitleIds: Set<String>,
        val titleAtkPct: Int,
        val titleHpBonus: Int,
        val titleMpBonus: Int,
        val titlePostureBonus: Int,
        val titleGoldPct: Int
    ) {
        val maxHp: Int get() = 110 + (cloakRank - 1) * 16 + titleHpBonus
        val maxMp: Int get() = 65 + (grimoireRank - 1) * 11 + titleMpBonus
        val attackMultiplier: Float get() = 1.0f + (bladeRank - 1) * 0.12f + (titleAtkPct / 100f)
        val embraceResistMult: Float get() = (1.0f - (cloakRank - 1) * 0.035f - (wardRank - 1) * 0.025f).coerceAtLeast(0.45f)
        val spBonusPerAction: Int get() = (grimoireRank - 1) * 2
        val spiritPowerMult: Float get() = 1.0f + (spiritRingRank - 1) * 0.08f
        val spiritMpDiscount: Int get() = ((spiritRingRank - 1) / 2).coerceAtMost(6)
        val demonAssistChance: Float get() = (0.14f + (demonAffection / 100f) * 0.24f + (demonHeartRank - 1) * 0.03f).coerceAtMost(0.55f)
        val goldBonusMult: Float get() = 1.0f + (titleGoldPct / 100f)
        val totalItems: Int get() = elixirs + crystals + talismans + amulets + incenses + aegisVeils + postureBombs + holyWaters + etherElixirs + hourglasses
        val alignmentLabel: String get() = when {
            corruption <= -20 -> "☀️ Volonté ($corruption)"
            corruption >= 25 -> "💜 Ombre (+$corruption)"
            else -> "⚖️ Équilibre ($corruption)"
        }
        val demonForm: DemonForm get() = when {
            demonXp >= 900 -> DemonForm(3, "Seigneur de l'Éclipse", "👑🌑", "Aide combat massive (+20 SP, brise Givre & Leurre) + Aura Royale")
            demonXp >= 420 -> DemonForm(2, "Archidémon", "🔥😈", "Interventions fréquentes, alerte Mimique & +15 SP en duel")
            demonXp >= 150 -> DemonForm(1, "Gardien Cornu", "⚔️😈", "Casse le Givre tactile et encourage ton héros en combat")
            else -> DemonForm(0, "Diablotin", "😈", "Compagnon espiègle Shizuku + Accessibilité")
        }
    }

    private fun todayKey(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    fun getBondLevel(witchId: Int): Int = prefs.getInt("bond_w_$witchId", 0).coerceIn(0, 5)

    fun incrementBond(witchId: Int): Int {
        val next = (getBondLevel(witchId) + 1).coerceAtMost(5)
        prefs.edit().putInt("bond_w_$witchId", next).apply()
        return next
    }

    fun computeTotalBondLevels(): Int {
        var sum = 0
        for (id in 1..55) sum += prefs.getInt("bond_w_$id", 0).coerceIn(0, 5)
        return sum
    }

    fun snapshot(): Snapshot {
        val defeated = readIntSet(KEY_DEFEATED)
        val superDef = readIntSet(KEY_SUPER_DEFEATED)
        val wins = prefs.getInt(KEY_WINS, 0)
        val parries = prefs.getInt(KEY_PARRIES, 0)
        val qtes = prefs.getInt(KEY_QTE_BREAKS, 0)
        val towerBest = prefs.getInt(KEY_TOWER_BEST, 0)
        val dungeons = prefs.getInt(KEY_DUNGEONS_DONE, 0)
        val quiz = prefs.getInt(KEY_QUIZ_OK, 0)
        val focusDone = prefs.getInt(KEY_FOCUS_DONE, 0)
        val streak = prefs.getInt(KEY_DAILY_STREAK, 0)
        val aff = prefs.getInt(KEY_DEMON_AFFECTION, 55).coerceIn(0, 100)
        val totalBonds = computeTotalBondLevels()
        val compBounties = HashSet(prefs.getStringSet(KEY_COMP_BOUNTIES, emptySet()).orEmpty())

        val spirits = HashSet(prefs.getStringSet(KEY_SPIRITS, setOf("zephyr")).orEmpty())
        spirits.add("zephyr")
        if (defeated.contains(10)) spirits.add("gaia")
        if (defeated.contains(20)) spirits.add("ondine")
        if (defeated.contains(30)) spirits.add("ignis")

        // Dynamically evaluate all 24 stat-granting RPG Titles!
        val unlockedTitles = HashSet<String>()
        if (wins >= 1) unlockedTitles.add("t_win_1")
        if (wins >= 10) unlockedTitles.add("t_win_10")
        if (wins >= 25) unlockedTitles.add("t_win_25")
        if (wins >= 50) unlockedTitles.add("t_win_50")
        if (defeated.count { it in 1..50 } >= 10) unlockedTitles.add("t_grim_10")
        if (defeated.count { it in 1..50 } >= 25) unlockedTitles.add("t_grim_25")
        if (defeated.count { it in 1..50 } >= 50) unlockedTitles.add("t_grim_50")
        if (parries >= 5) unlockedTitles.add("t_parry_5")
        if (parries >= 20) unlockedTitles.add("t_parry_20")
        if (qtes >= 5) unlockedTitles.add("t_qte_5")
        if (qtes >= 20) unlockedTitles.add("t_qte_20")
        if (towerBest >= 5) unlockedTitles.add("t_tower_5")
        if (towerBest >= 15) unlockedTitles.add("t_tower_15")
        if (dungeons >= 3) unlockedTitles.add("t_dungeon_3")
        if (spirits.size >= 4) unlockedTitles.add("t_spirits_4")
        if (superDef.size >= 1) unlockedTitles.add("t_super_1")
        if (superDef.size >= 5) unlockedTitles.add("t_super_5")
        if (totalBonds >= 10) unlockedTitles.add("t_bond_10")
        if (aff >= 80) unlockedTitles.add("t_demon_aff")
        if (focusDone >= 3) unlockedTitles.add("t_focus_3")
        if (quiz >= 5) unlockedTitles.add("t_quiz_5")
        if (streak >= 3) unlockedTitles.add("t_streak_3")
        if (compBounties.size >= 5) unlockedTitles.add("t_bounty_5")
        if (defeated.contains(50)) unlockedTitles.add("t_story_end")

        var tAtk = 0; var tHp = 0; var tMp = 0; var tPos = 0; var tGold = 0
        for (td in WitchLoreAndCampaign.TITLES) {
            if (unlockedTitles.contains(td.id)) {
                tAtk += td.atkBonusPct
                tHp += td.hpBonus
                tMp += td.mpBonus
                tPos += td.postureBonus
                tGold += td.goldBonusPct
            }
        }
        // Bond Lv 5 Witches grant +3% permanent ATK each!
        for (id in 1..55) {
            if (prefs.getInt("bond_w_$id", 0) >= 5) tAtk += 3
        }

        val lastDay = prefs.getInt(KEY_DAILY_LAST_DAY, 0)
        val canClaimDaily = lastDay != todayKey()

        return Snapshot(
            xp = prefs.getInt(KEY_XP, 0),
            gold = prefs.getInt(KEY_GOLD, 160),
            unlockedChapter = prefs.getInt(KEY_UNLOCKED_CH, 1).coerceIn(1, 5),
            selectedChapter = prefs.getInt(KEY_SELECTED_CH, 1).coerceIn(1, 5),
            elixirs = prefs.getInt(KEY_ELIXIRS, 3).coerceAtLeast(0),
            crystals = prefs.getInt(KEY_CRYSTALS, 2).coerceAtLeast(0),
            talismans = prefs.getInt(KEY_TALISMANS, 2).coerceAtLeast(0),
            amulets = prefs.getInt(KEY_AMULETS, 2).coerceAtLeast(0),
            incenses = prefs.getInt(KEY_INCENSES, 1).coerceAtLeast(0),
            aegisVeils = prefs.getInt(KEY_AEGIS, 1).coerceAtLeast(0),
            postureBombs = prefs.getInt(KEY_POSTURE_BOMB, 1).coerceAtLeast(0),
            holyWaters = prefs.getInt(KEY_HOLY_WATER, 1).coerceAtLeast(0),
            etherElixirs = prefs.getInt(KEY_ETHER_ELIXIR, 0).coerceAtLeast(0),
            hourglasses = prefs.getInt(KEY_HOURGLASS, 0).coerceAtLeast(0),
            bladeRank = prefs.getInt(KEY_BLADE_RANK, 1).coerceIn(1, 15),
            cloakRank = prefs.getInt(KEY_CLOAK_RANK, 1).coerceIn(1, 15),
            grimoireRank = prefs.getInt(KEY_GRIMOIRE_RANK, 1).coerceIn(1, 15),
            wardRank = prefs.getInt(KEY_WARD_RANK, 1).coerceIn(1, 15),
            spiritRingRank = prefs.getInt(KEY_SPIRIT_RING, 1).coerceIn(1, 15),
            demonHeartRank = prefs.getInt(KEY_DEMON_HEART, 1).coerceIn(1, 15),
            encounteredIds = readIntSet(KEY_ENCOUNTERED),
            defeatedIds = defeated,
            lostToIds = readIntSet(KEY_LOST_TO),
            defeatedSuperbossIds = superDef,
            unlockedSpirits = spirits,
            totalWins = wins,
            totalDefeats = prefs.getInt(KEY_DEFEATS, 0),
            perfectParries = parries,
            qteBreaks = qtes,
            towerBestFloor = towerBest,
            dungeonsCompleted = dungeons,
            quizCorrectCount = quiz,
            claimedQuests = HashSet(prefs.getStringSet(KEY_CLAIMED_QUESTS, emptySet()).orEmpty()),
            completedBounties = compBounties,
            claimedBounties = HashSet(prefs.getStringSet(KEY_CLAIMED_BOUNTIES, emptySet()).orEmpty()),
            dailyStreak = streak,
            canClaimDailyToday = canClaimDaily,
            geminiApiKey = prefs.getString(KEY_GEMINI_KEY, "").orEmpty(),
            storyStep = prefs.getInt(KEY_STORY_STEP, 0).coerceIn(0, 15),
            corruption = prefs.getInt(KEY_CORRUPTION, 0).coerceIn(-100, 100),
            forcedFullscreen = prefs.getBoolean(KEY_FORCED_FS, true),
            shizukuTaskPinning = prefs.getBoolean(KEY_SHIZUKU_PIN, false),
            forcedOverlayBattle = prefs.getBoolean(KEY_FORCED_OV, false),
            midBattleAppPowers = prefs.getBoolean(KEY_MID_APP_POWERS, true),
            touchSabotageEnabled = prefs.getBoolean(KEY_TOUCH_SABOTAGE, true),
            combatSfxEnabled = prefs.getBoolean(KEY_COMBAT_SFX, true),
            demonAffection = aff,
            demonHunger = prefs.getInt(KEY_DEMON_HUNGER, 80).coerceIn(0, 100),
            demonXp = prefs.getInt(KEY_DEMON_XP, 0).coerceAtLeast(0),
            focusSessionsDone = focusDone,
            activeFocusUntilMs = prefs.getLong(KEY_FOCUS_UNTIL, 0L),
            quietHoursEnabled = prefs.getBoolean(KEY_QUIET_EN, false),
            quietStartHour = prefs.getInt(KEY_QUIET_START, 23).coerceIn(0, 23),
            quietEndHour = prefs.getInt(KEY_QUIET_END, 7).coerceIn(0, 23),
            totalBondLevels = totalBonds,
            unlockedTitleIds = unlockedTitles,
            titleAtkPct = tAtk,
            titleHpBonus = tHp,
            titleMpBonus = tMp,
            titlePostureBonus = tPos,
            titleGoldPct = tGold
        )
    }

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

    var forcedFullscreen: Boolean
        get() = prefs.getBoolean(KEY_FORCED_FS, true)
        set(v) = prefs.edit().putBoolean(KEY_FORCED_FS, v).apply()

    var shizukuTaskPinning: Boolean
        get() = prefs.getBoolean(KEY_SHIZUKU_PIN, false)
        set(v) = prefs.edit().putBoolean(KEY_SHIZUKU_PIN, v).apply()

    var forcedOverlayBattle: Boolean
        get() = prefs.getBoolean(KEY_FORCED_OV, false)
        set(v) = prefs.edit().putBoolean(KEY_FORCED_OV, v).apply()

    var midBattleAppPowers: Boolean
        get() = prefs.getBoolean(KEY_MID_APP_POWERS, true)
        set(v) = prefs.edit().putBoolean(KEY_MID_APP_POWERS, v).apply()

    var touchSabotageEnabled: Boolean
        get() = prefs.getBoolean(KEY_TOUCH_SABOTAGE, true)
        set(v) = prefs.edit().putBoolean(KEY_TOUCH_SABOTAGE, v).apply()

    var combatSfxEnabled: Boolean
        get() = prefs.getBoolean(KEY_COMBAT_SFX, true)
        set(v) = prefs.edit().putBoolean(KEY_COMBAT_SFX, v).apply()

    var quietHoursEnabled: Boolean
        get() = prefs.getBoolean(KEY_QUIET_EN, false)
        set(v) = prefs.edit().putBoolean(KEY_QUIET_EN, v).apply()

    var quietStartHour: Int
        get() = prefs.getInt(KEY_QUIET_START, 23).coerceIn(0, 23)
        set(v) = prefs.edit().putInt(KEY_QUIET_START, v.coerceIn(0, 23)).apply()

    var quietEndHour: Int
        get() = prefs.getInt(KEY_QUIET_END, 7).coerceIn(0, 23)
        set(v) = prefs.edit().putInt(KEY_QUIET_END, v.coerceIn(0, 23)).apply()

    fun isQuietHourNow(): Boolean {
        if (!quietHoursEnabled) return false
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val s = quietStartHour; val e = quietEndHour
        return if (s <= e) h in s until e else (h >= s || h < e)
    }

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
        if (floor >= 5) markBountyCompleted("b_tower_5")
    }

    fun recordDungeonComplete() {
        prefs.edit().putInt(KEY_DUNGEONS_DONE, prefs.getInt(KEY_DUNGEONS_DONE, 0) + 1).apply()
        markBountyCompleted("b_dungeon_1")
        addDemonCare(affDelta = +8, hungerDelta = +5, xpDelta = +45)
    }

    fun recordQuizCorrect() {
        prefs.edit().putInt(KEY_QUIZ_OK, prefs.getInt(KEY_QUIZ_OK, 0) + 1).apply()
        addDemonCare(affDelta = +4, hungerDelta = 0, xpDelta = +15)
    }

    fun unlockSpirit(spiritId: String, goldCost: Int = 0): Boolean {
        val cur = HashSet(prefs.getStringSet(KEY_SPIRITS, setOf("zephyr")).orEmpty())
        if (cur.contains(spiritId)) return true
        val gold = prefs.getInt(KEY_GOLD, 0)
        if (gold < goldCost) return false
        cur.add(spiritId)
        prefs.edit().putInt(KEY_GOLD, gold - goldCost).putStringSet(KEY_SPIRITS, cur).apply()
        return true
    }

    fun addDemonCare(affDelta: Int, hungerDelta: Int, xpDelta: Int) {
        val aff = (prefs.getInt(KEY_DEMON_AFFECTION, 55) + affDelta).coerceIn(0, 100)
        val hun = (prefs.getInt(KEY_DEMON_HUNGER, 80) + hungerDelta).coerceIn(0, 100)
        val xp = (prefs.getInt(KEY_DEMON_XP, 0) + xpDelta).coerceAtLeast(0)
        prefs.edit().putInt(KEY_DEMON_AFFECTION, aff).putInt(KEY_DEMON_HUNGER, hun).putInt(KEY_DEMON_XP, xp).apply()
    }

    fun setActiveFocusUntil(untilMs: Long) {
        prefs.edit().putLong(KEY_FOCUS_UNTIL, untilMs).apply()
    }

    fun completeFocusSession(): Pair<Int, Int> {
        val done = prefs.getInt(KEY_FOCUS_DONE, 0) + 1
        val heartBonus = (prefs.getInt(KEY_DEMON_HEART, 1) - 1) * 20
        val goldReward = 150 + heartBonus
        prefs.edit()
            .putInt(KEY_FOCUS_DONE, done)
            .putLong(KEY_FOCUS_UNTIL, 0L)
            .putInt(KEY_GOLD, prefs.getInt(KEY_GOLD, 0) + goldReward)
            .apply()
        addDemonCare(affDelta = +15, hungerDelta = +20, xpDelta = +85)
        markBountyCompleted("b_focus_1")
        return goldReward to done
    }

    fun claimDailyGift(): String? {
        val today = todayKey()
        val last = prefs.getInt(KEY_DAILY_LAST_DAY, 0)
        if (last == today) return null
        val prevStreak = prefs.getInt(KEY_DAILY_STREAK, 0)
        val nextStreak = if (today - last == 1) (prevStreak % 7) + 1 else 1
        val goldGain = 75 + nextStreak * 35
        val relic = when (nextStreak) {
            1 -> "elixir" to "🧪 Élixir"
            2 -> "crystal" to "💎 Cristal"
            3 -> "posture_bomb" to "💣 Bombe Brise-Posture"
            4 -> "holy_water" to "💧 Eau Bénite"
            5 -> "talisman" to "📜 Talisman"
            6 -> "aegis" to "🛡️ Voile d'Égide"
            else -> "ether_elixir" to "⚡ Élixir d'Éther (Jour 7 !)"
        }
        prefs.edit()
            .putInt(KEY_DAILY_LAST_DAY, today)
            .putInt(KEY_DAILY_STREAK, nextStreak)
            .putInt(KEY_GOLD, prefs.getInt(KEY_GOLD, 0) + goldGain)
            .apply()
        grantReward(0, relic.first)
        addDemonCare(affDelta = +6, hungerDelta = +10, xpDelta = +30)
        return "🎁 Pacte Quotidien Jour $nextStreak/7 : +$goldGain 💎 + 1 ${relic.second} !"
    }

    fun markBountyCompleted(bountyId: String) {
        val cur = HashSet(prefs.getStringSet(KEY_COMP_BOUNTIES, emptySet()).orEmpty())
        if (cur.add(bountyId)) {
            prefs.edit().putStringSet(KEY_COMP_BOUNTIES, cur).apply()
        }
    }

    fun claimBounty(bountyId: String): Boolean {
        val def = WitchLoreAndCampaign.BOUNTIES.firstOrNull { it.id == bountyId } ?: return false
        val comp = prefs.getStringSet(KEY_COMP_BOUNTIES, emptySet()).orEmpty()
        val claimed = HashSet(prefs.getStringSet(KEY_CLAIMED_BOUNTIES, emptySet()).orEmpty())
        if (!comp.contains(bountyId) || claimed.contains(bountyId)) return false
        claimed.add(bountyId)
        prefs.edit()
            .putStringSet(KEY_CLAIMED_BOUNTIES, claimed)
            .putInt(KEY_GOLD, prefs.getInt(KEY_GOLD, 0) + def.rewardGold)
            .apply()
        grantReward(0, def.rewardRelicKey)
        return true
    }

    /** Records Victory: awards XP, Gold (with Title + Tower multiplier!), unlocks next chapter, spirits, bonds & drops! */
    fun recordVictory(witch: WitchCatalog.WitchDef, bonusMultiplier: Float = 1.0f): Triple<Int, Boolean, String?> {
        val snap = snapshot()
        val gainXp = (witch.rewardXp * bonusMultiplier).toInt()
        val gainGold = (witch.rewardGold * bonusMultiplier * snap.goldBonusMult).toInt()
        val newXp = snap.xp + gainXp
        val levelUps = newXp / 100
        val remXp = newXp % 100
        val newGold = snap.gold + gainGold
        val newDefeated = snap.defeatedIds + witch.id
        val newEnc = snap.encounteredIds + witch.id
        val newSuper = if (witch.isSuperboss) snap.defeatedSuperbossIds + witch.id else snap.defeatedSuperbossIds

        var nextCh = snap.unlockedChapter
        var unlockedNewChapter = false
        if (witch.isBoss && !witch.isSuperboss && witch.chapter >= nextCh && nextCh < 5) {
            nextCh = witch.chapter + 1
            unlockedNewChapter = true
        }

        val unlockedSpiritName = when (witch.id) {
            10 -> { unlockSpirit("gaia"); "🪨 Esprit Gaïa éveillé !" }
            20 -> { unlockSpirit("ondine"); "💧 Esprit Ondine éveillé !" }
            30 -> { unlockSpirit("ignis"); "🔥 Esprit Ignis éveillé !" }
            else -> null
        }

        val drop = when {
            witch.isSuperboss -> "Élixir d'Éther"
            witch.isBoss -> "Voile d'Égide"
            witch.isMiniBoss -> "Bombe Brise-Posture"
            else -> null
        }

        prefs.edit().apply {
            putInt(KEY_XP, remXp)
            putInt(KEY_GOLD, newGold)
            putInt(KEY_UNLOCKED_CH, nextCh)
            putInt(KEY_WINS, snap.totalWins + 1)
            if (drop == "Élixir d'Éther") putInt(KEY_ETHER_ELIXIR, snap.etherElixirs + 1)
            if (drop == "Voile d'Égide") putInt(KEY_AEGIS, snap.aegisVeils + 1)
            if (drop == "Bombe Brise-Posture") putInt(KEY_POSTURE_BOMB, snap.postureBombs + 1)
            saveIntSet(this, KEY_DEFEATED, newDefeated)
            saveIntSet(this, KEY_ENCOUNTERED, newEnc)
            saveIntSet(this, KEY_SUPER_DEFEATED, newSuper)
        }.apply()

        val newBond = incrementBond(witch.id)
        if (newBond >= 3) markBountyCompleted("b_bond_3")
        if (witch.isSuperboss) markBountyCompleted("b_superboss_1")
        addDemonCare(affDelta = +5, hungerDelta = -4, xpDelta = if (witch.isBoss) 55 else 24)

        val dropText = listOfNotNull(drop, unlockedSpiritName).joinToString(" • ").ifBlank { null }
        return Triple(levelUps, unlockedNewChapter, dropText)
    }

    fun recordDefeat(witch: WitchCatalog.WitchDef) {
        val snap = snapshot()
        val newLost = snap.lostToIds + witch.id
        val newEnc = snap.encounteredIds + witch.id
        prefs.edit().apply {
            putInt(KEY_DEFEATS, snap.totalDefeats + 1)
            putInt(KEY_GOLD, snap.gold + 20)
            saveIntSet(this, KEY_LOST_TO, newLost)
            saveIntSet(this, KEY_ENCOUNTERED, newEnc)
        }.apply()
        addDemonCare(affDelta = -3, hungerDelta = -5, xpDelta = +10)
    }

    private fun itemPrefKey(kind: String): String? = when (kind) {
        "elixir" -> KEY_ELIXIRS
        "crystal" -> KEY_CRYSTALS
        "talisman" -> KEY_TALISMANS
        "amulet" -> KEY_AMULETS
        "incense" -> KEY_INCENSES
        "aegis" -> KEY_AEGIS
        "posture_bomb" -> KEY_POSTURE_BOMB
        "holy_water" -> KEY_HOLY_WATER
        "ether_elixir" -> KEY_ETHER_ELIXIR
        "hourglass" -> KEY_HOURGLASS
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
        prefs.edit().putInt(KEY_GOLD, gold - cost).putInt(key, cur + 1).apply()
        return true
    }

    fun forgeUpgradeCost(currentRank: Int): Int = 55 + currentRank * 48

    fun upgradeForge(slot: String): Boolean {
        val key = when (slot) {
            "blade" -> KEY_BLADE_RANK
            "cloak" -> KEY_CLOAK_RANK
            "grimoire" -> KEY_GRIMOIRE_RANK
            "ward" -> KEY_WARD_RANK
            "spirit_ring" -> KEY_SPIRIT_RING
            "demon_heart" -> KEY_DEMON_HEART
            else -> return false
        }
        val cur = prefs.getInt(key, 1).coerceIn(1, 15)
        if (cur >= 15) return false
        val cost = forgeUpgradeCost(cur)
        val gold = prefs.getInt(KEY_GOLD, 0)
        if (gold < cost) return false
        prefs.edit().putInt(KEY_GOLD, gold - cost).putInt(key, cur + 1).apply()
        return true
    }

    fun getQuests(): List<QuestDef> {
        val s = snapshot()
        val bossesDefeated = WitchCatalog.ALL_WITCHES.count { it.isBoss && s.defeatedIds.contains(it.id) }
        return listOf(
            QuestDef("q_first_win", "Première Victoire", "+95 💎 + 1 Élixir", 95, "elixir", s.totalWins, 1, s.claimedQuests.contains("q_first_win")),
            QuestDef("q_parry_3", "Maître de la Parade (3 Parades Parfaites)", "+170 💎 + 1 Bombe Posture", 170, "posture_bomb", s.perfectParries, 3, s.claimedQuests.contains("q_parry_3")),
            QuestDef("q_qte_3", "Briseur d'Entraves (3 QTE réussis)", "+160 💎 + 1 Talisman", 160, "talisman", s.qteBreaks, 3, s.claimedQuests.contains("q_qte_3")),
            QuestDef("q_boss_1", "Tombeur d'Archisorcière (1 Boss)", "+260 💎 + 1 Eau Bénite", 260, "holy_water", bossesDefeated, 1, s.claimedQuests.contains("q_boss_1")),
            QuestDef("q_tower_3", "Ascension (Étage 3 Tour Infinie)", "+240 💎 + 1 Sablier", 240, "hourglass", s.towerBestFloor, 3, s.claimedQuests.contains("q_tower_3")),
            QuestDef("q_grim_10", "Érudit du Coven (10/50 Sorcières)", "+380 💎 + 1 Élixir d'Éther", 380, "ether_elixir", s.defeatedIds.count { it in 1..50 }, 10, s.claimedQuests.contains("q_grim_10"))
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
        private const val KEY_POSTURE_BOMB = "posture_bombs_v10"
        private const val KEY_HOLY_WATER = "holy_waters_v10"
        private const val KEY_ETHER_ELIXIR = "ether_elixirs_v10"
        private const val KEY_HOURGLASS = "hourglasses_v10"
        private const val KEY_BLADE_RANK = "forge_blade"
        private const val KEY_CLOAK_RANK = "forge_cloak"
        private const val KEY_GRIMOIRE_RANK = "forge_grimoire"
        private const val KEY_WARD_RANK = "forge_ward"
        private const val KEY_SPIRIT_RING = "forge_spirit_ring_v10"
        private const val KEY_DEMON_HEART = "forge_demon_heart_v10"
        private const val KEY_ENCOUNTERED = "encountered_ids"
        private const val KEY_DEFEATED = "defeated_ids"
        private const val KEY_LOST_TO = "lost_to_ids"
        private const val KEY_SUPER_DEFEATED = "super_defeated_v10"
        private const val KEY_SPIRITS = "unlocked_spirits_v10"
        private const val KEY_WINS = "total_wins"
        private const val KEY_DEFEATS = "total_defeats"
        private const val KEY_PARRIES = "perfect_parries"
        private const val KEY_QTE_BREAKS = "qte_breaks"
        private const val KEY_TOWER_BEST = "tower_best_floor"
        private const val KEY_DUNGEONS_DONE = "dungeons_done_v10"
        private const val KEY_QUIZ_OK = "quiz_ok_v10"
        private const val KEY_CLAIMED_QUESTS = "claimed_quests"
        private const val KEY_COMP_BOUNTIES = "comp_bounties_v10"
        private const val KEY_CLAIMED_BOUNTIES = "claimed_bounties_v10"
        private const val KEY_DAILY_STREAK = "daily_streak_v10"
        private const val KEY_DAILY_LAST_DAY = "daily_last_day_v10"
        private const val KEY_GEMINI_KEY = "gemini_api_key"
        private const val KEY_STORY_STEP = "story_step_v7"
        private const val KEY_CORRUPTION = "corruption_v7"
        private const val KEY_FORCED_FS = "forced_fs_v7"
        private const val KEY_SHIZUKU_PIN = "shizuku_pin_v9"
        private const val KEY_FORCED_OV = "forced_ov_v7"
        private const val KEY_MID_APP_POWERS = "mid_app_powers_v7"
        private const val KEY_TOUCH_SABOTAGE = "touch_sabotage_v7"
        private const val KEY_COMBAT_SFX = "combat_sfx_v10"
        private const val KEY_DEMON_AFFECTION = "demon_aff_v10"
        private const val KEY_DEMON_HUNGER = "demon_hunger_v10"
        private const val KEY_DEMON_XP = "demon_xp_v10"
        private const val KEY_FOCUS_DONE = "focus_done_v10"
        private const val KEY_FOCUS_UNTIL = "focus_until_v10"
        private const val KEY_QUIET_EN = "quiet_en_v10"
        private const val KEY_QUIET_START = "quiet_start_v10"
        private const val KEY_QUIET_END = "quiet_end_v10"
    }
}
