package com.riverrunner.engine

/**
 * Flop texture classes and hand-strength buckets. Both must match tools/flop-solve
 * (flops.py `key`, main.rs `buckets` and `draw`), because the solved data is keyed by them.
 */
object FlopTexture {
    /** e.g. "R-U-K-0": rainbow / two-tone / monotone, unpaired / paired, high card, connected. */
    fun key(board: List<Int>): String {
        val ranks = board.take(3).map { Cards.rank(it) }.sortedDescending()
        val suits = board.take(3).map { Cards.suit(it) }.toSet().size
        val distinct = ranks.toSet().size
        if (distinct == 1) return "X-3"
        val hb = if (ranks[0] >= 8) "AKQJT"[12 - ranks[0]].toString() else "9"
        if (distinct == 2) return "${if (suits == 2) "T" else "R"}-P-$hb"
        val suit = when (suits) { 1 -> "M"; 2 -> "T"; else -> "R" }
        var span = ranks[0] - ranks[2]
        if (ranks[0] == 12) span = minOf(span, ranks[1] + 1)
        return "$suit-U-$hb-${if (span <= 4) 1 else 0}"
    }

    /** A flush draw or an 8-out straight draw that uses a hole card, without a straight or better already. */
    fun hasDraw(hole: IntArray, board: List<Int>): Boolean {
        val cards = board + hole.toList()
        if (HandEvaluator.category(HandEvaluator.evaluate(cards.toIntArray())) >= HandEvaluator.STRAIGHT) return false
        for (s in 0 until 4) {
            if (cards.count { Cards.suit(it) == s } == 4 && hole.any { Cards.suit(it) == s }) return true
        }
        fun ranks(cs: List<Int>) = cs.fold(0) { m, c -> m or (1 shl Cards.rank(c)) }
        fun straight(m: Int): Boolean {
            val x = (m shl 1) or ((m ushr 12) and 1)
            return (0..9).any { lo -> (x ushr lo) and 0b11111 == 0b11111 }
        }
        val all = ranks(cards); val brd = ranks(board)
        val outs = (0 until 13).count { r -> (all ushr r) and 1 == 0 && straight(all or (1 shl r)) && !straight(brd or (1 shl r)) }
        return outs >= 2
    }

    /** Every combo of a starting-hand class such as "AKs", as card pairs. */
    fun combos(name: String): List<IntArray> {
        val hi = Cards.RANKS.indexOf(name[0]); val lo = Cards.RANKS.indexOf(name[1])
        val out = ArrayList<IntArray>()
        for (a in 0 until 4) for (b in 0 until 4) {
            val ok = when {
                name.length == 2 -> a < b
                name[2] == 's' -> a == b
                else -> a != b
            }
            if (ok) out += intArrayOf(Cards.of(hi, a), Cards.of(lo, b))
        }
        return out
    }

    /**
     * Bucket 0..19: the hand's strength decile within the range [lo, hi) of the starting-hand
     * order on this flop (ties count half), plus 10 with a draw.
     */
    fun bucket(hole: IntArray, board: List<Int>, lo: Double, hi: Double): Int {
        val boardMask = Cards.mask(board.toIntArray())
        val mine = HandEvaluator.evaluate(boardMask or Cards.mask(hole))
        var below = 0; var equal = 0; var total = 0
        for (name in HandClasses.classesIn(lo, hi)) for (c in combos(name)) {
            val m = Cards.mask(c)
            if (m and boardMask != 0L) continue
            val s = HandEvaluator.evaluate(boardMask or m)
            total++
            if (s < mine) below++ else if (s == mine) equal++
        }
        val p = if (total == 0) 0.5 else (below + equal / 2.0) / total
        return minOf(9, (p * 10).toInt()) + if (hasDraw(hole, board)) 10 else 0
    }
}

/** Solved flop decisions (see [FlopGtoData]). */
object FlopGto {
    /** One solved decision: who acts (0 = out of position), the actions, and percentages by bucket. */
    class Node(val player: Int, val actions: List<String>, val freq: List<IntArray>)

    private val cache = HashMap<String, Node?>()

    fun node(spot: String, board: List<Int>, path: String): Node? {
        val k = "$spot/${FlopTexture.key(board)}/$path"
        return cache.getOrPut(k) {
            FlopGtoData.TABLE[k]?.let { v ->
                val (p, a, rows) = v.split('|')
                Node(p.toInt(), a.split(','), rows.split(';').map { r -> r.split(',').map { it.toInt() }.toIntArray() })
            }
        }
    }
}
