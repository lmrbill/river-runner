package com.riverrunner.engine

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PushFoldNashTest {
    @Test fun tableCoversEveryClass() {
        for (name in HandClasses.names) {
            assertTrue(PushFoldNash.pushUpTo(name) in 0.0..PushFoldNash.MAX_STACK, name)
            assertTrue(PushFoldNash.callUpTo(name) in 0.0..PushFoldNash.MAX_STACK, name)
        }
    }

    @Test fun matchesKnownNashShape() {
        assertEquals(25.0, PushFoldNash.pushUpTo("AA"))
        assertEquals(25.0, PushFoldNash.pushUpTo("A2o"))
        assertTrue(PushFoldNash.pushUpTo("32o") <= 2.0)
        assertTrue(PushFoldNash.pushUpTo("K2o") in 8.0..14.0)
        // published heads-up Nash: about 58% shoves and 37% calls at 10 bb
        assertTrue(abs(PushFoldNash.pushFraction(10.0) - 0.58) < 0.03, "${PushFoldNash.pushFraction(10.0)}")
        assertTrue(PushFoldNash.pushFraction(5.0) > PushFoldNash.pushFraction(15.0))
    }

    /** Three-handed, button folds, small blind decides; 10 bb stacks. */
    private fun sbSpot(sbHand: String, bbHand: String = "2c3d"): HandEngine {
        val holes = mapOf(0 to Cards.parseMany("7h2s"), 1 to Cards.parseMany(sbHand), 2 to Cards.parseMany(bbHand))
        val e = HandEngine(
            HandSetup(50, 100, 0, 0, (0..2).map { SeatSetup(it, "P$it", 1000) }),
            PresetCardSource(holes, Cards.parseMany("2d4h8cJdKs")),
        )
        e.apply(Move.FOLD)
        return e
    }

    @Test fun smallBlindShovesAndFoldsByTheTable() {
        val jam = sbSpot("Kh9d")   // K9o shoves up to 24 bb
        val p1 = Bot.decide(jam, jam.toAct, BotStyle.GTO, Random(1))
        assertTrue(p1.probability(MoveType.RAISE) > 0.95)
        val fold = sbSpot("7c2d")  // 72o only shoves at 1.5 bb
        val p2 = Bot.decide(fold, fold.toAct, BotStyle.GTO, Random(1))
        assertTrue(p2.probability(MoveType.FOLD) > 0.95)
    }

    @Test fun bigBlindCallsShoveByTheTable() {
        fun bbAfterShove(bbHand: String): Policy {
            val e = sbSpot("AsKs", bbHand)
            e.apply(Move.raiseTo(e.legalActions().maxRaiseTo))
            return Bot.decide(e, e.toAct, BotStyle.GTO, Random(2))
        }
        assertTrue(bbAfterShove("Ad2c").probability(MoveType.CALL) > 0.95)  // A2o calls up to ~15 bb
        assertTrue(bbAfterShove("Jd4c").probability(MoveType.FOLD) > 0.95)  // J4o calls only very short
    }
}
