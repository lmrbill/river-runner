package com.riverrunner.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GtoTest {
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

    @Test fun cardClasses() {
        val flop = board("Kd7s2c")
        assertEquals('O', FlopTexture.cardClass(flop, Cards.parse("As")))
        assertEquals('P', FlopTexture.cardClass(flop, Cards.parse("7h")))
        assertEquals('B', FlopTexture.cardClass(flop, Cards.parse("9s")))   // only one spade on the board
        assertEquals('F', FlopTexture.cardClass(board("Kd7s2s"), Cards.parse("9s")))
        assertEquals('S', FlopTexture.cardClass(board("Td8s2c"), Cards.parse("9h")))
        assertEquals('M', FlopTexture.family(board("As7s2s")))
        assertEquals('P', FlopTexture.family(board("7s7d2c")))
        assertEquals('U', FlopTexture.family(flop))
    }

    /** Three-handed: seat 0 button, 1 small blind, 2 big blind; deep stacks. */
    private fun hand(btn: String, bb: String, board: String): HandEngine = HandEngine(
        HandSetup(50, 100, 0, 0, (0..2).map { SeatSetup(it, "P$it", 10000) }),
        PresetCardSource(mapOf(0 to Cards.parseMany(btn), 1 to Cards.parseMany("3c4d"), 2 to Cards.parseMany(bb)), Cards.parseMany(board)),
    )

    private fun total(p: Policy) = p.options.sumOf { it.second }

    /** Button opens, big blind calls, flop K72 rainbow: the solve says the big blind checks everything. */
    @Test fun singleRaisedPotAllStreets() {
        assertTrue(Gto.size > 1000, "${Gto.size} decisions")
        val e = hand("AhQd", "Jc9c", "Kd7s2cQh3s")
        e.apply(Move.raiseTo(250)); e.apply(Move.FOLD); e.apply(Move.CALL); e.dealNextStreet()
        val bb = Bot.decide(e, e.toAct, BotStyle.GTO, Random(3))
        assertTrue(bb.probability(MoveType.CHECK) > 0.9, "${bb.options}")
        e.apply(Move.CHECK)
        val btn = Bot.decide(e, e.toAct, BotStyle.GTO, Random(3))
        assertTrue(btn.probability(MoveType.RAISE) > 0.7, "${btn.options}")   // c-bets almost always on K72r
        e.apply(Move.CHECK); e.dealNextStreet()                               // checked through: turn Qh
        val key = "srp/R-U-K-0/T/X-X/B/"   // Qh is a blank under the king
        assertNotNull(Gto.node(key), key)
        val turn = Bot.decide(e, e.toAct, BotStyle.GTO, Random(3))
        assertEquals(1.0, total(turn), 1e-9)
        e.apply(Move.CHECK); e.apply(Move.CHECK); e.dealNextStreet()          // river 3s
        val rkey = "srp/R/U/xx/B/"
        assertNotNull(Gto.node(rkey), rkey)
        assertEquals(1.0, total(Bot.decide(e, e.toAct, BotStyle.GTO, Random(3))), 1e-9)
    }

    @Test fun threeBetPot() {
        val e = hand("AhQd", "KcKs", "Kd7s2cQh3s")
        e.apply(Move.raiseTo(250)); e.apply(Move.FOLD); e.apply(Move.raiseTo(950)); e.apply(Move.CALL); e.dealNextStreet()
        assertNotNull(Gto.node("3bp/R-U-K-0/F/"))
        val bb = Bot.decide(e, e.toAct, BotStyle.GTO, Random(4))
        assertEquals(1.0, total(bb), 1e-9)
        // a set of kings bets or checks, never folds
        assertTrue(bb.probability(MoveType.FOLD) == 0.0)
    }
}
