package com.riverrunner.engine

import kotlin.math.pow
import kotlin.random.Random

enum class GameMode(val code: String) { CASH("C"), TOURNAMENT("T");
    companion object { fun fromCode(c: String) = entries.first { it.code == c } }
}

enum class TableType { GTO, MIXED }

data class SessionConfig(
    val mode: GameMode,
    /** 2, 5, 6, 7 or 8 */
    val tableSize: Int,
    /** starting stack in big blinds */
    val startingBB: Int,
    val tableType: TableType,
    /** tournament: hands per blind level */
    val handsPerLevel: Int = 10,
    /** cash: ante as a fraction of the big blind (0 = none) */
    val cashAnteFraction: Double = 0.0,
    val seed: Long = Random.nextLong(),
    /** mixed table line-up; null = every bot seat draws a random style (GTO included) */
    val styleMix: StyleMix? = null,
)

data class BlindLevel(val level: Int, val smallBlind: Int, val bigBlind: Int, val ante: Int)

class SessionPlayer(
    val seat: Int,
    val name: String,
    val style: BotStyle,
    val isHuman: Boolean,
    var stack: Int,
    /** buy-in of the session; a resumed session keeps the original one */
    val startStack: Int = stack,
) {
    /** 0 while still playing, otherwise finishing place (1 = winner) */
    var place: Int = 0
    val alive: Boolean get() = place == 0 && stack > 0
}

data class Standing(val seat: Int, val name: String, val place: Int, val stack: Int, val isHuman: Boolean)

data class SessionSummary(
    val mode: GameMode,
    val standings: List<Standing>,
    val heroPlace: Int,
    /** cash: chips won/lost in big blinds; tournament: prize points minus a 100-point buy-in */
    val heroProfit: Double,
    val handsPlayed: Int,
    val endedEarly: Boolean,
)

/** One seat of a [SessionSnapshot]. */
data class SeatSnapshot(
    val seat: Int, val name: String, val style: BotStyle, val isHuman: Boolean,
    val stack: Int, val startStack: Int, val place: Int,
)

/** Where a session stood after its last finished hand, so it can be picked up again later. */
data class SessionSnapshot(
    val config: SessionConfig,
    val handNumber: Int,
    val buttonSeat: Int,
    val seats: List<SeatSnapshot>,
    /** history ids of the hands played so far (for the HUD and "replay last hand") */
    val handIds: List<Long> = emptyList(),
) {
    val hero: SeatSnapshot? get() = seats.firstOrNull { it.isHuman }
    /** True when the session can still go on: you have chips and someone is left to play. */
    val playable: Boolean get() = (hero?.let { it.place == 0 && it.stack > 0 } ?: false) &&
        seats.count { it.place == 0 && it.stack > 0 } >= 2
}

/**
 * Drives a whole cash session or single-table tournament. The human always sits in seat 0.
 * With [resume], picks up a session where it stopped: same seats, stacks, blind level and hand count.
 */
class GameSession(val config: SessionConfig, resume: SessionSnapshot? = null) {
    val random = Random(config.seed)
    val players: List<SessionPlayer>
    var handNumber = 0; private set
    var buttonSeat: Int; private set
    var current: HandEngine? = null; private set
    private var finished: SessionSummary? = null

    companion object {
        const val HERO_SEAT = 0
        const val BASE_BB = 100
        private val BOT_NAMES = listOf("Ace", "Blaze", "Cobra", "Dune", "Echo", "Flint", "Ghost", "Hawk", "Iris", "Jade", "Koda", "Luna", "Maverick", "Nova", "Onyx", "Pike")
        private val MIXED_POOL = listOf(BotStyle.GTO, BotStyle.GTO, BotStyle.TAG, BotStyle.LAG, BotStyle.STATION, BotStyle.NIT, BotStyle.MANIAC)
        private val BB_SCHEDULE = intArrayOf(100, 150, 200, 300, 400, 500, 600, 800, 1000, 1200, 1500, 2000, 2500, 3000, 4000, 5000, 6000, 8000, 10000, 12000, 15000, 20000)

        fun levelFor(index: Int): BlindLevel {
            val bb = if (index < BB_SCHEDULE.size) BB_SCHEDULE[index]
            else (BB_SCHEDULE.last() * 1.25.pow((index - BB_SCHEDULE.size + 1).toDouble())).toInt() / 100 * 100
            val ante = if (index >= 3) (bb / 8) / 5 * 5 else 0
            return BlindLevel(index + 1, bb / 2, bb, ante)
        }
    }

    init {
        require(config.tableSize in 2..9)
        if (resume != null) {
            require(resume.seats.size == config.tableSize)
            players = resume.seats.sortedBy { it.seat }.map { r ->
                SessionPlayer(r.seat, r.name, r.style, r.isHuman, r.stack, r.startStack).also { it.place = r.place }
            }
            handNumber = resume.handNumber
            buttonSeat = resume.buttonSeat
        } else {
            val names = BOT_NAMES.shuffled(random)
            val start = config.startingBB * BASE_BB
            val bots = config.tableSize - 1
            val mix = config.styleMix
            val botStyles: List<BotStyle> = when {
                config.tableType == TableType.GTO -> List(bots) { BotStyle.GTO }
                mix == null -> List(bots) { MIXED_POOL[random.nextInt(MIXED_POOL.size)] }
                else -> mix.assign(bots, random)
            }
            players = List(config.tableSize) { i ->
                if (i == HERO_SEAT) SessionPlayer(i, "You", BotStyle.GTO, true, start)
                else SessionPlayer(i, names[i - 1], botStyles[i - 1], false, start)
            }
            buttonSeat = random.nextInt(config.tableSize)
        }
    }

    /** The session as it stands between hands. */
    fun snapshot(handIds: List<Long> = emptyList()): SessionSnapshot = SessionSnapshot(
        config, handNumber, buttonSeat,
        players.map { SeatSnapshot(it.seat, it.name, it.style, it.isHuman, it.stack, it.startStack, it.place) },
        handIds,
    )

    val hero: SessionPlayer get() = players[HERO_SEAT]
    val alivePlayers: List<SessionPlayer> get() = players.filter { it.alive }
    val payouts: DoubleArray get() = Icm.payoutsFor(config.tableSize)

    fun currentLevel(): BlindLevel = when (config.mode) {
        GameMode.TOURNAMENT -> levelFor(handNumber / config.handsPerLevel.coerceAtLeast(1))
        GameMode.CASH -> BlindLevel(1, BASE_BB / 2, BASE_BB, (BASE_BB * config.cashAnteFraction).toInt())
    }

    val isOver: Boolean get() = finished != null || alivePlayers.size <= 1 || !hero.alive

    fun tournamentContext(): TournamentContext? =
        if (config.mode == GameMode.TOURNAMENT) TournamentContext(alivePlayers.associate { it.seat to it.stack }, payouts) else null

    fun startHand(): HandEngine {
        check(!isOver)
        val alive = alivePlayers
        // move the button to the next live seat (simple moving button)
        if (handNumber > 0) {
            var b = buttonSeat
            do { b = (b + 1) % players.size } while (!players[b].alive)
            buttonSeat = b
        } else if (!players[buttonSeat].alive) {
            buttonSeat = alive.first().seat
        }
        val lvl = currentLevel()
        val setup = HandSetup(
            smallBlind = lvl.smallBlind, bigBlind = lvl.bigBlind, ante = lvl.ante,
            buttonSeat = buttonSeat,
            seats = alive.map { SeatSetup(it.seat, it.name, it.stack) },
        )
        val engine = HandEngine(setup, RandomCardSource(random))
        current = engine
        return engine
    }

    /** Applies the finished hand to the session and returns its history record. */
    fun completeHand(engine: HandEngine): HandRecord {
        val result = checkNotNull(engine.result) { "Hand not finished" }
        val aliveBefore = alivePlayers.size
        for (ps in engine.players) players[ps.seat].stack = ps.stack
        // assign places to busted players: bigger starting stack finishes higher
        val busted = engine.players.filter { it.stack == 0 }.sortedBy { it.startStack }
        var place = aliveBefore
        for (b in busted) { players[b.seat].place = place; place-- }
        handNumber++
        val record = HandRecord.from(engine, config.mode, handNumber, HERO_SEAT, config.tableSize,
            styles = players.associate { it.seat to it.style })
        current = null
        return record.copy(won = result.won)
    }

    /** Ends the session (normally or early) and computes standings. */
    fun finish(early: Boolean = false): SessionSummary {
        finished?.let { return it }
        val alive = alivePlayers.sortedByDescending { it.stack }
        alive.forEachIndexed { i, p -> p.place = i + 1 }
        val standings = players.map { Standing(it.seat, it.name, it.place, it.stack, it.isHuman) }.sortedBy { it.place }
        val heroPlace = hero.place
        val profit = when (config.mode) {
            GameMode.CASH -> (hero.stack - hero.startStack).toDouble() / BASE_BB
            GameMode.TOURNAMENT -> {
                val pool = 100.0 * config.tableSize
                val pay = payouts.getOrNull(heroPlace - 1) ?: 0.0
                pool * pay - 100.0
            }
        }
        val s = SessionSummary(config.mode, standings, heroPlace, profit, handNumber, early)
        finished = s
        return s
    }
}
