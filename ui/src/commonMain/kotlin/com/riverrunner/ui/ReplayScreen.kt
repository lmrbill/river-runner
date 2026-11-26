package com.riverrunner.ui

import com.riverrunner.PlatformBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riverrunner.engine.Action
import com.riverrunner.engine.ActionType
import com.riverrunner.engine.Bot
import com.riverrunner.engine.BotStyle
import com.riverrunner.engine.Cards
import com.riverrunner.engine.Equity
import com.riverrunner.engine.HandEngine
import com.riverrunner.engine.HandEvaluator
import com.riverrunner.engine.HandRecord
import com.riverrunner.engine.MoveType
import com.riverrunner.engine.Policy
import com.riverrunner.engine.ReplayFrame
import com.riverrunner.engine.Replayer
import com.riverrunner.engine.SizeClass
import com.riverrunner.i18n.LocalStrings
import com.riverrunner.i18n.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

@Composable
fun ReplayScreen(vm: AppViewModel, initial: HandRecord) {
    // review mode: the replay sits between hands, leaving it deals the next one.
    // peeking: opened from the table mid-session, leaving it returns to the hand in play.
    val leave: () -> Unit = {
        when {
            vm.reviewing -> vm.continueFromReview()
            vm.peeking -> vm.backToTable()
            else -> { vm.screen = Screen.History }
        }
    }
    PlatformBackHandler(onBack = leave)
    var record by remember { mutableStateOf(initial) }
    val st = LocalStrings.current
    ReplayContent(
        record = record,
        fmt = { vm.fmt(it, record.bigBlind) },
        onBack = leave,
        onStar = { vm.toggleFavorite(record.id)?.let { record = it } },
        onContinue = when {
            vm.reviewing -> vm::continueFromReview
            vm.peeking -> vm::backToTable
            else -> null
        },
        continueLabel = if (vm.peeking && !vm.reviewing) st.backToTable else null,
    )
}

internal fun sizeName(st: Strings, c: SizeClass): String = when (c) {
    SizeClass.SMALL -> st.sizeSmall
    SizeClass.MEDIUM -> st.sizeMedium
    SizeClass.LARGE -> st.sizeLarge
    SizeClass.POT -> st.sizePot
    SizeClass.OVER -> st.sizeOver
}

/** Builds the table picture for one replay frame. */
fun replayTable(st: Strings, record: HandRecord, frames: List<ReplayFrame>, idx: Int, equities: Map<Int, Double>?, fmt: (Int) -> String): TableUi {
    val frame = frames[idx]
    val names = record.players.associate { it.seat to if (it.seat == record.heroSeat) st.you else it.name }
    val styles = record.players.associate { it.seat to it.style }
    val seatsInOrder = record.players.map { it.seat }.sorted()
    val btnIdx = seatsInOrder.indexOf(record.buttonSeat)
    val order = List(seatsInOrder.size) { seatsInOrder[(btnIdx + 1 + it) % seatsInOrder.size] }
    // latest action of each seat on the current street
    val last = HashMap<Int, Action>()
    for (i in 1..idx) {
        val a = frames[i].action
        if (a == null) last.clear() else last[a.seat] = a
    }
    val live = frame.players.filter { !it.folded }
    val atShowdown = frame.isEnd && live.size >= 2 && frame.board.size == 5
    val boardMask = Cards.mask(frame.board.toIntArray())
    var highlight = emptySet<Int>()
    var banner: String? = null
    if (frame.isEnd) {
        val winners = frame.players.filter { it.stack > it.startStack }
        if (atShowdown && winners.isNotEmpty()) {
            val w = winners.maxBy { it.stack - it.startStack }
            highlight = HandEvaluator.bestFive((frame.board + w.hole.toList()).toIntArray()).toSet()
        }
        banner = winners.joinToString(" / ") { "${st.winsFor(names.getValue(it.seat))} ${fmt(it.stack - it.startStack)}" }
            .ifEmpty { null }
    }
    val seats = frame.players.sortedBy { it.seat }.map { p ->
        val a = if (frame.isEnd) null else last[p.seat]
        SeatUi(
            seat = p.seat, name = names.getValue(p.seat), stack = p.stack,
            streetBet = if (frame.isEnd) 0 else p.streetBet,
            folded = p.folded, allIn = p.allIn, isHero = p.seat == record.heroSeat,
            isButton = p.seat == record.buttonSeat, isActing = !frame.isEnd && frame.toAct == p.seat,
            hole = p.hole, faceUp = true,
            position = HandEngine.positionName(order.indexOf(p.seat), order.size),
            action = a?.let { TableMapper.actionLabel(st, it, fmt) },
            actKind = a?.let { TableMapper.actKind(it) },
            won = if (frame.isEnd) maxOf(0, p.stack - p.startStack) else 0,
            handName = if (atShowdown && !p.folded) HandEvaluator.categoryName(HandEvaluator.evaluate(boardMask or Cards.mask(p.hole)), st.isZh) else null,
            equity = if (!p.folded && !frame.isEnd) equities?.get(p.seat) else null,
            tag = if (p.seat != record.heroSeat && styles[p.seat] != BotStyle.GTO) styles[p.seat]?.let { st.styleName(it) } else null,
        )
    }
    val pot = if (frame.isEnd) 0 else frame.pot - frame.players.sumOf { it.streetBet }
    return TableUi(seats, frame.board, pot, record.bigBlind, record.id, idx, highlight, banner)
}

fun replayLog(st: Strings, record: HandRecord, frames: List<ReplayFrame>, fmt: (Int) -> String): List<LogLine> {
    val names = record.players.associate { it.seat to if (it.seat == record.heroSeat) st.you else it.name }
    return frames.mapIndexed { i, f ->
        val a = f.action
        when {
            a != null -> LogLine(LogKind.ACTION, "${names.getValue(a.seat)} ${TableMapper.actionLabel(st, a, fmt)}", a.seat)
            i == 0 -> LogLine(LogKind.STREET, streetName(st, f.street))
            else -> TableMapper.streetLine(st, f.street, f.board)
        }
    }
}

@Composable
fun ReplayContent(
    record: HandRecord,
    fmt: (Int) -> String,
    onBack: () -> Unit,
    onStar: () -> Unit,
    startIndex: Int = 0,
    onContinue: (() -> Unit)? = null,
    /** label of the [onContinue] button; defaults to "Next hand" */
    continueLabel: String? = null,
) {
    val st = LocalStrings.current
    val replayer = remember(record.id) { Replayer(record) }
    val frames = replayer.frames
    var idx by remember(record.id) { mutableIntStateOf(startIndex.coerceIn(0, frames.size - 1)) }
    val frame = frames[idx]

    // exact equity for the players still in, cached per street
    val equityCache = remember(record.id) { mutableStateMapOf<String, Map<Int, Double>>() }
    val live = frame.players.filter { !it.folded }
    val eqKey = "${frame.board.size}:" + live.joinToString(",") { it.seat.toString() }
    LaunchedEffect(eqKey) {
        if (equityCache[eqKey] == null && live.size >= 2) {
            val eq = withContext(Dispatchers.Default) {
                Equity.showdown(live.map { it.hole }, frame.board, Random(record.id), 30000)
            }
            equityCache[eqKey] = live.mapIndexed { i, p -> p.seat to eq[i] }.toMap()
        }
    }

    // the decision on this frame (just taken, or about to be taken), by any player at the table
    val actionsSoFar = frames.take(idx + 1).count { it.action != null }
    val justTaken = frame.action
    val decisionSeat = when {
        justTaken != null -> justTaken.seat
        !frame.isEnd && frame.toAct >= 0 -> frame.toAct
        else -> -1
    }
    val decisionIndex = when {
        justTaken != null -> actionsSoFar - 1
        decisionSeat >= 0 -> actionsSoFar
        else -> -1
    }
    val voluntary = record.actions.filter { !it.type.isPost }
    val decisionAction = if (decisionIndex >= 0) voluntary.getOrNull(decisionIndex) else null
    val policyCache = remember(record.id) { mutableStateMapOf<Int, Policy>() }
    LaunchedEffect(decisionIndex) {
        if (decisionIndex >= 0 && decisionSeat >= 0 && policyCache[decisionIndex] == null) {
            val p = withContext(Dispatchers.Default) {
                runCatching {
                    val e = replayer.engineBeforeAction(decisionIndex)
                    // what GTO would do with this player's own cards and what they could see
                    Bot.decide(e, decisionSeat, BotStyle.GTO, Random(record.id + decisionIndex))
                }.getOrNull()
            }
            if (p != null) policyCache[decisionIndex] = p
        }
    }
    val policy = if (decisionIndex >= 0) policyCache[decisionIndex] else null
    val table = replayTable(st, record, frames, idx, equityCache[eqKey], fmt)
    val log = remember(record.id, st) { replayLog(st, record, frames, fmt) }

    Row(Modifier.fillMaxSize().background(Palette.Bg)) {
        Box(Modifier.weight(1f).fillMaxHeight().padding(start = 4.dp, top = 4.dp, bottom = 2.dp)) {
            PokerTable(table, fmt, st.pot, Modifier.fillMaxSize(), animateDeal = false)
        }
        // same width as the table's own panel, and everything inside fits a phone held sideways:
        // the GTO card scrolls within its share of the height and the action log takes the rest
        Column(
            Modifier.width(212.dp).fillMaxHeight().background(Palette.Panel).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIcon("←", st.back, onBack)
                Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    Text(
                        "${st.replay} · ${st.handNo(record.handNumber)}", color = Palette.Text, fontSize = 14.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1,
                    )
                    Text(com.riverrunner.engine.Stats.positionOf(record), color = Palette.TextDim, fontSize = 11.sp, maxLines = 1)
                }
                RoundIcon(if (record.favorite) "★" else "☆", st.starHand, onStar)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                SmallButton("⏮", { idx = 0 }, Modifier.weight(1f))
                SmallButton("◀", { if (idx > 0) idx-- }, Modifier.weight(1.3f))
                SmallButton("▶", { if (idx < frames.size - 1) idx++ }, Modifier.weight(1.3f), highlight = true)
                SmallButton("⏭", { idx = frames.size - 1 }, Modifier.weight(1f))
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val room = maxHeight
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (decisionAction != null && decisionAction.seat == decisionSeat) {
                        val who = if (decisionSeat == record.heroSeat) null
                            else record.players.firstOrNull { it.seat == decisionSeat }?.name
                        // at most about 60% of the room, scrolling inside when the advice is longer
                        Box(Modifier.heightIn(max = room * 0.6f).verticalScroll(rememberScrollState())) {
                            AnalysisCard(decisionAction, justTaken != null, who, policy, fmt)
                        }
                    }
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        if (maxHeight > 30.dp) ActionLog(log, Modifier.fillMaxSize(), highlightIndex = idx, onClick = { idx = it })
                    }
                }
            }
            if (onContinue != null) {
                ActionButton(continueLabel ?: st.reviewNext, Palette.Gold, Modifier.fillMaxWidth().height(40.dp), textColor = Palette.Ink, onClick = onContinue)
            }
        }
    }
}

@Composable
private fun AnalysisCard(
    heroAction: Action,
    decided: Boolean,
    /** the player's name, or null for you */
    who: String?,
    policy: Policy?,
    fmt: (Int) -> String,
) {
    val st = LocalStrings.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.PanelHi).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        if (decided) {
            val label = if (who == null) st.yourAction else st.actionOf(who)
            Text("$label: ${TableMapper.actionLabel(st, heroAction, fmt)}", color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        } else if (who != null) {
            Text(st.toActNow(who), color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        if (policy == null) {
            Text(st.computing, color = Palette.TextDim, fontSize = 12.sp)
            return@Column
        }
        val dist = policy.options.groupBy { it.first.type }.mapValues { e -> e.value.sumOf { it.second } }
        val parts = listOf(MoveType.FOLD, MoveType.CHECK, MoveType.CALL, MoveType.RAISE).mapNotNull { t ->
            dist[t]?.let { p ->
                val name = when (t) { MoveType.FOLD -> st.fold; MoveType.CHECK -> st.check; MoveType.CALL -> st.call; MoveType.RAISE -> st.raise }
                "$name ${Format.pct(p)}"
            }
        }
        Text("${st.gtoSuggest}: " + parts.joinToString(" / "), color = Palette.Accent, fontSize = 13.sp)
        // bet-size mix of the raises, measured against the pot before the decision
        val sizes = policy.options.filter { it.first.type == MoveType.RAISE }
            .groupBy { SizeClass.of(it.first.raiseTo, policy.pot) }
            .mapValues { e -> e.value.sumOf { it.second } }
        if (sizes.isNotEmpty()) {
            val line = SizeClass.entries.mapNotNull { c -> sizes[c]?.let { "${sizeName(st, c)} ${Format.pct(it)}" } }
            Text("${st.gtoSizing}: " + line.joinToString(" / "), color = Palette.Accent, fontSize = 12.sp)
        }
        if (decided) {
            val t = when (heroAction.type) {
                ActionType.FOLD -> MoveType.FOLD; ActionType.CHECK -> MoveType.CHECK
                ActionType.CALL -> MoveType.CALL; else -> MoveType.RAISE
            }
            val ok = (dist[t] ?: 0.0) >= com.riverrunner.engine.Deviation.ACTION_MIN
            Text(
                if (ok) "✓ ${st.inLine}" else "⚠ ${st.deviation}",
                color = if (ok) Palette.Green else Palette.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            )
            if (t == MoveType.RAISE) {
                val mine = SizeClass.of(heroAction.streetTotal, policy.pot)
                val sizeOk = (sizes[mine] ?: 0.0) >= com.riverrunner.engine.Deviation.SIZE_MIN
                Text(
                    if (sizeOk) "✓ ${sizeName(st, mine)}" else "⚠ ${st.sizeOff}: ${sizeName(st, mine)}",
                    color = if (sizeOk) Palette.Green else Palette.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        // equity and the equity a call needs share one line
        val eqParts = listOfNotNull(
            policy.equity?.let { "${st.estEquity} ${Format.pct(it)}" },
            if (policy.potOdds > 0) "${st.needEquity} ${Format.pct(policy.potOdds)}" else null,
        )
        if (eqParts.isNotEmpty()) Text(eqParts.joinToString(" · "), color = Palette.TextDim, fontSize = 11.sp, lineHeight = 14.sp)
    }
}
