package com.riverrunner.engine

/**
 * Board classes and hand-strength buckets. All must match tools/flop-solve (flops.py `key`,
 * main.rs `buckets`, `draw`, `card_class` and `flop_family`), because the solved data is keyed by them.
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

    /** M monotone, P paired (or trips), U otherwise; the river data is grouped by it. */
    fun family(flop: List<Int>): Char = when {
        flop.take(3).map { Cards.rank(it) }.toSet().size < 3 -> 'P'
        flop.take(3).map { Cards.suit(it) }.toSet().size == 1 -> 'M'
        else -> 'U'
    }

    private fun straight(m: Int): Boolean {
        val x = (m shl 1) or ((m ushr 12) and 1)
        return (0..9).any { lo -> (x ushr lo) and 0b11111 == 0b11111 }
    }

    private fun rankMask(cs: List<Int>) = cs.fold(0) { m, c -> m or (1 shl Cards.rank(c)) }

    /**
     * What a new board card does: F third card of a suit, P pairs the board, S first card that
     * lets two hole cards make a straight, O new high card, else B (blank).
     */
    fun cardClass(before: List<Int>, card: Int): Char {
        if (before.count { Cards.suit(it) == Cards.suit(card) } == 2) return 'F'
        if (before.any { Cards.rank(it) == Cards.rank(card) }) return 'P'
        fun possible(m: Int) = (0 until 13).any { a -> (0 until a).any { b -> straight(m or (1 shl a) or (1 shl b)) } }
        val bm = rankMask(before)
        if (!possible(bm) && possible(bm or (1 shl Cards.rank(card)))) return 'S'
        if (before.all { Cards.rank(it) < Cards.rank(card) }) return 'O'
        return 'B'
    }

    /**
     * A flush draw or an 8-out straight draw that uses a hole card, without a straight or better
     * already. Never on the river.
     */
    fun hasDraw(hole: IntArray, board: List<Int>): Boolean {
        if (board.size >= 5) return false
        val cards = board + hole.toList()
        if (HandEvaluator.category(HandEvaluator.evaluate(cards.toIntArray())) >= HandEvaluator.STRAIGHT) return false
        for (s in 0 until 4) {
            if (cards.count { Cards.suit(it) == s } == 4 && hole.any { Cards.suit(it) == s }) return true
        }
        val all = rankMask(cards); val brd = rankMask(board)
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
     * order on this board (ties count half), plus 10 with a draw.
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

/** Solved postflop decisions (see [GtoData]). */
object Gto {
    /** One solved decision: who acts (0 = out of position), the actions, and frequencies by bucket. */
    class Node(val player: Int, val actions: List<String>, val freq: List<DoubleArray>)

    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz+/"

    private val table: Map<String, Node> by lazy {
        val map = HashMap<String, Node>()
        for (line in GtoData.CHUNKS.joinToString("").split('\n')) {
            val f = line.split('\t')
            if (f.size != 4) continue
            val actions = f[2].split(',')
            val n = actions.size
            val freq = List(f[3].length / n) { b -> DoubleArray(n) { i -> ALPHABET.indexOf(f[3][b * n + i]) / 63.0 } }
            map[f[0]] = Node(f[1].toInt(), actions, freq)
        }
        map
    }

    fun node(key: String): Node? = table[key]

    val size: Int get() = table.size
}
