package com.riverrunner.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlopGtoTest {
    private fun board(s: String) = Cards.parseMany(s).toList()

    /** Same answers as tools/flop-solve/flops.py `key`. */
    @Test fun textureKeysMatchTheSolverScript() {
        val expected = mapOf(
            "Kd7s2c" to "R-U-K-0", "AsKs5s" to "M-U-A-0", "Js9s8s" to "M-U-J-1", "8h8d3c" to "R-P-9",
            "8h8d3h" to "T-P-9", "7c7d7h" to "X-3", "As2d3c" to "R-U-A-1", "Ah5h4d" to "T-U-A-1",
            "9c6d2h" to "R-U-9-0", "Td9d6h" to "T-U-T-1", "QsJh2h" to "T-U-Q-0",
        )
        for ((flop, key) in expected) assertEquals(key, FlopTexture.key(board(flop)), flop)
    }

    @Test fun drawsAndBuckets() {
        assertTrue(FlopTexture.hasDraw(Cards.parseMany("AhQh"), board("Kh7h2c")))   // flush draw
        assertTrue(FlopTexture.hasDraw(Cards.parseMany("9c8d"), board("Th7s2c")))   // open-ended
        assertFalse(FlopTexture.hasDraw(Cards.parseMany("AsAd"), board("Kh7h2c")))
        val k72 = board("Kd7s2c")
        assertEquals(9, FlopTexture.bucket(Cards.parseMany("KsKh"), k72, 0.0, 0.44))
        assertTrue(FlopTexture.bucket(Cards.parseMany("AhKc"), k72, 0.0, 0.44) >= 7)
        assertTrue(FlopTexture.bucket(Cards.parseMany("6h5h"), k72, 0.0, 0.44) <= 2)
    }

    /** Button opens, big blind calls, flop K72 rainbow: the solve says the big blind checks everything. */
    @Test fun botUsesSolvedFlopStrategy() {
        // 3-handed: seat 0 button, 1 small blind, 2 big blind
        val holes = mapOf(0 to Cards.parseMany("AhQd"), 1 to Cards.parseMany("3c4d"), 2 to Cards.parseMany("Jc9c"))
        val e = HandEngine(
            HandSetup(50, 100, 0, 0, (0..2).map { SeatSetup(it, "P$it", 10000) }),
            PresetCardSource(holes, Cards.parseMany("Kd7s2cQh3s")),
        )
        e.apply(Move.raiseTo(250))   // button opens
        e.apply(Move.FOLD)           // small blind folds
        e.apply(Move.CALL)           // big blind calls
        e.dealNextStreet()
        assertEquals(Street.FLOP, e.street)
        assertNotNull(FlopGto.node("srp", e.board, ""))
        val bb = Bot.decide(e, e.toAct, BotStyle.GTO, Random(3))
        assertTrue(bb.probability(MoveType.CHECK) > 0.95, "${bb.options}")
        e.apply(Move.CHECK)
        val btn = Bot.decide(e, e.toAct, BotStyle.GTO, Random(3))
        // the button c-bets almost always on K72r, mostly small
        assertTrue(btn.probability(MoveType.RAISE) > 0.8, "${btn.options}")
    }
}
