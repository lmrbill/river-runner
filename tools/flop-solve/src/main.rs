//! Solves heads-up postflop spots with postflop-solver and writes the decisions on the flop,
//! turn and river, bucketed by hand strength, as JSON. Licensed AGPL-3.0-or-later because it
//! links postflop-solver; only its output (numbers) goes into the app.
//!
//! usage: rr-flop-solve <spot> <flop> [max_iters] [target_pct]
//!   srp: button opens 2.5 bb, big blind calls, 100 bb (big blind out of position)
//!   3bp: button opens, big blind 3-bets to 9.5 bb, button calls (3-bettor out of position)
//!
//! Every decision is keyed by its line. A street's line is its actions joined by '-', with bets
//! labelled B33/B75 on the flop and B on the turn and river. Turn keys add the turn card's class
//! and river keys the river card's class (see `card_class`); river keys use the flop family and
//! whether each earlier street was checked through ("x") or bet and called ("b").
//! For each key and strength bucket the file holds the summed weighted action frequencies and
//! the summed reach, so files can be added together.

use postflop_solver::*;
use std::collections::BTreeMap;
use std::time::Instant;

const ORDER: &str = include_str!("../order.txt");
const NB: usize = 20;

// card = rank * 4 + suit
fn rank(c: u8) -> u8 { c / 4 }
fn suit(c: u8) -> u8 { c % 4 }

/// Score of the best 5-card hand among the given cards (higher is better).
fn eval(cards: &[u8]) -> u32 {
    let mut suit_mask = [0u16; 4];
    let mut counts = [0u8; 13];
    for &c in cards {
        suit_mask[suit(c) as usize] |= 1 << rank(c);
        counts[rank(c) as usize] += 1;
    }
    fn straight_hi(m: u16) -> Option<u32> {
        // wheel: A-2-3-4-5 counts A as low
        let m = (m as u32) << 1 | ((m as u32 >> 12) & 1);
        for hi in (4..=13).rev() {
            let need = 0b11111u32 << (hi - 4);
            if m & need == need { return Some(hi as u32 - 1); }
        }
        None
    }
    for &sm in &suit_mask {
        if sm.count_ones() >= 5 {
            if let Some(h) = straight_hi(sm) { return (8 << 20) | h; }
            let mut k = 0u32; let mut n = 0;
            for r in (0..13).rev() { if sm >> r & 1 == 1 && n < 5 { k = k << 4 | r; n += 1; } }
            return (5 << 20) | k;
        }
    }
    let all: u16 = suit_mask.iter().fold(0, |a, b| a | b);
    let mut quads = None; let mut trips = vec![]; let mut pairs = vec![];
    for r in (0..13u32).rev() {
        match counts[r as usize] { 4 => quads = Some(r), 3 => trips.push(r), 2 => pairs.push(r), _ => {} }
    }
    let kick = |excl: &[u32], n: usize| -> u32 {
        let mut k = 0; let mut got = 0;
        for r in (0..13u32).rev() {
            if got == n { break; }
            if all >> r & 1 == 1 && !excl.contains(&r) { k = k << 4 | r; got += 1; }
        }
        k
    };
    if let Some(q) = quads { return (7 << 20) | q << 4 | kick(&[q], 1); }
    if !trips.is_empty() && (trips.len() >= 2 || !pairs.is_empty()) {
        let t = trips[0];
        let p = if trips.len() >= 2 { trips[1].max(*pairs.first().unwrap_or(&0)) } else { pairs[0] };
        return (6 << 20) | t << 4 | p;
    }
    if let Some(h) = straight_hi(all) { return (4 << 20) | h; }
    if let Some(&t) = trips.first() { return (3 << 20) | t << 8 | kick(&[t], 2); }
    if pairs.len() >= 2 { return (2 << 20) | pairs[0] << 8 | pairs[1] << 4 | kick(&[pairs[0], pairs[1]], 1); }
    if let Some(&p) = pairs.first() { return (1 << 20) | p << 12 | kick(&[p], 3); }
    kick(&[], 5)
}




fn combos_of(n: &str) -> usize { if n.len() == 2 { 6 } else if n.ends_with('s') { 4 } else { 12 } }

/// Classes whose percentile midpoint lies in [lo, hi) of River Runner's 169-hand order.
fn range_text(lo: f64, hi: f64) -> Vec<String> {
    let mut cum = 0usize; let mut out = vec![];
    for n in ORDER.split_whitespace() {
        let c = combos_of(n);
        let p = (cum as f64 + c as f64 / 2.0) / 1326.0;
        cum += c;
        if p >= lo && p < hi { out.push(n.to_string()); }
    }
    out
}

fn straight(m: u16) -> bool { let m = (m as u32) << 1 | ((m as u32 >> 12) & 1); (0..=9).any(|lo| m >> lo & 0b11111 == 0b11111) }
fn rank_mask(cs: &[u8]) -> u16 { cs.iter().fold(0u16, |m, c| m | 1 << rank(*c)) }

/// A flush draw or an 8-out straight draw using a hole card, not already a straight or better.
/// Only before the river.
fn draw(hole: [u8; 2], board: &[u8]) -> bool {
    if board.len() >= 5 { return false; }
    let mut cards = board.to_vec(); cards.extend_from_slice(&hole);
    if eval(&cards) >> 20 >= 4 { return false; }
    for s in 0..4 {
        let n = cards.iter().filter(|c| suit(**c) == s).count();
        if n == 4 && hole.iter().any(|c| suit(*c) == s) { return true; }
    }
    let all = rank_mask(&cards); let brd = rank_mask(board);
    (0..13).filter(|r| all >> r & 1 == 0 && straight(all | 1 << r) && !straight(brd | 1 << r)).count() >= 2
}

/// What a new board card does: F completes a possible flush (third of a suit), P pairs the board,
/// S makes a straight possible with two hole cards for the first time, O is a new high card, else B.
fn card_class(before: &[u8], card: u8) -> char {
    let same = before.iter().filter(|c| suit(**c) == suit(card)).count();
    if same == 2 { return 'F'; }
    if before.iter().any(|c| rank(*c) == rank(card)) { return 'P'; }
    let possible = |m: u16| (0..13).any(|a| (0..a).any(|b| straight(m | 1 << a | 1 << b)));
    let bm = rank_mask(before);
    if !possible(bm) && possible(bm | 1 << rank(card)) { return 'S'; }
    if before.iter().all(|c| rank(*c) < rank(card)) { return 'O'; }
    'B'
}

/// Flop family for river keys: M monotone, P paired (or trips), U otherwise.
fn flop_family(flop: &[u8]) -> char {
    let suits: std::collections::HashSet<u8> = flop.iter().map(|c| suit(*c)).collect();
    let ranks: std::collections::HashSet<u8> = flop.iter().map(|c| rank(*c)).collect();
    if ranks.len() < 3 { 'P' } else if suits.len() == 1 { 'M' } else { 'U' }
}

/// Bucket 0..19 of every hand: strength decile within the player's own starting range on this
/// board (ties count half), plus 10 with a draw. Blocked hands get bucket 0 (their weight is 0).
fn buckets(range: &[(u8, u8)], board: &[u8]) -> Vec<usize> {
    let bmask: u64 = board.iter().fold(0, |m, c| m | 1 << c);
    let live: Vec<bool> = range.iter().map(|(a, b)| bmask >> a & 1 == 0 && bmask >> b & 1 == 0).collect();
    let score: Vec<u32> = range.iter().map(|(a, b)| { let mut v = board.to_vec(); v.push(*a); v.push(*b); eval(&v) }).collect();
    let mut sorted: Vec<u32> = (0..range.len()).filter(|i| live[*i]).map(|i| score[i]).collect();
    sorted.sort_unstable();
    let total = sorted.len() as f64;
    range.iter().enumerate().map(|(i, (a, b))| {
        if !live[i] { return 0; }
        let below = sorted.partition_point(|s| *s < score[i]) as f64;
        let equal = sorted.partition_point(|s| *s <= score[i]) as f64 - below;
        let p = (below + equal / 2.0) / total;
        ((p * 10.0) as usize).min(9) + if draw([*a, *b], board) { 10 } else { 0 }
    }).collect()
}

fn label(a: &Action, pot: i32, street: usize) -> String {
    match a {
        Action::Fold => "F".into(), Action::Check => "X".into(), Action::Call => "C".into(),
        Action::Bet(x) if street == 0 => if (*x as f64) < 0.54 * pot as f64 { "B33".into() } else { "B75".into() },
        Action::Bet(_) => "B".into(),
        Action::Raise(_) => "R".into(), Action::AllIn(_) => "A".into(), _ => "?".into(),
    }
}

#[derive(Default)]
struct Acc { player: usize, actions: Vec<String>, sum: Vec<Vec<f64>>, reach: Vec<f64> }

struct Walker<'a> {
    pot: i32,
    flop: [u8; 3],
    /// sum of the normalized weights at the root
    init: [f64; 2],
    out: BTreeMap<String, Acc>,
    bucket_cache: BTreeMap<(usize, Vec<u8>), Vec<usize>>,
    private: [&'a [(u8, u8)]; 2],
    max_depth: usize,
}

impl<'a> Walker<'a> {
    fn buckets_for(&mut self, p: usize, board: &[u8]) -> Vec<usize> {
        let k = (p, board.to_vec());
        if !self.bucket_cache.contains_key(&k) {
            let b = buckets(self.private[p], board);
            self.bucket_cache.insert(k.clone(), b);
        }
        self.bucket_cache[&k].clone()
    }

    /// street 0 flop, 1 turn, 2 river. `prefix` is the key up to this street; `line` this street's actions.
    fn walk(&mut self, game: &mut PostFlopGame, street: usize, prefix: &str, line: Vec<String>, chance_w: f64, lines: &[String]) {
        if game.is_terminal_node() { return; }
        if game.is_chance_node() {
            if street >= 2 { return; }
            // the street ended with a call or check-check: deal every possible next card
            let kind = if line.last().map(|s| s == "C").unwrap_or(false) { "b" } else { "x" };
            let mut all_lines = lines.to_vec(); all_lines.push(line.join("-"));
            let board_before = game.current_board();
            let cards = game.possible_cards();
            let n = cards.count_ones() as f64;
            let hist = game.history().to_vec();
            for c in 0..52u8 {
                if cards >> c & 1 == 0 { continue; }
                let cls = card_class(&board_before, c);
                game.play(c as usize);
                let next_prefix = if street == 0 {
                    format!("T/{}/{}", all_lines[0], cls)
                } else {
                    let k0 = if all_lines[0].ends_with('C') { "b" } else { "x" };
                    format!("R/{}/{}{}/{}", flop_family(&self.flop), k0, kind, cls)
                };
                self.walk(game, street + 1, &next_prefix, vec![], chance_w / n, &all_lines);
                game.apply_history(&hist);
            }
            return;
        }
        if line.len() >= self.max_depth { return; }
        game.cache_normalized_weights();
        let p = game.current_player();
        let acts = game.available_actions();
        let bet = game.total_bet_amount();
        let pot_now = self.pot + bet[0] + bet[1];
        let labels: Vec<String> = acts.iter().map(|a| label(a, pot_now, street)).collect();
        let board = game.current_board();
        let bk = self.buckets_for(p, &board);
        let s = game.strategy();
        let n = game.private_cards(p).len();
        let w = game.normalized_weights(p).to_vec();
        let key = if street == 0 { format!("F/{}", line.join("-")) } else { format!("{}/{}", prefix, line.join("-")) };
        {
            let e = self.out.entry(key).or_default();
            e.player = p;
            if e.actions.is_empty() { e.actions = labels.clone(); e.sum = vec![vec![0.0; labels.len()]; NB]; e.reach = vec![0.0; NB]; }
            for h in 0..n {
                let r = w[h] as f64 / self.init[p] * chance_w;
                if r == 0.0 { continue; }
                let b = bk[h];
                e.reach[b] += r;
                for (i, l) in labels.iter().enumerate() {
                    let j = match e.actions.iter().position(|x| x == l) { Some(j) => j, None => { e.actions.push(l.clone()); for row in e.sum.iter_mut() { row.push(0.0); } e.actions.len() - 1 } };
                    e.sum[b][j] += r * s[i * n + h] as f64;
                }
            }
        }
        let hist = game.history().to_vec();
        for (i, l) in labels.iter().enumerate() {
            if l == "F" { continue; }
            game.play(i);
            let mut nl = line.clone(); nl.push(l.clone());
            self.walk(game, street, prefix, nl, chance_w, lines);
            game.apply_history(&hist);
        }
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let spot = args[1].clone(); let flop = args[2].clone();
    let iters: u32 = args.get(3).and_then(|s| s.parse().ok()).unwrap_or(300);
    let target: f32 = args.get(4).and_then(|s| s.parse().ok()).unwrap_or(1.0);
    // pot and stacks in tenths of a big blind; ranges as [OOP, IP]
    let (oop_rng, ip_rng, pot, stack) = match spot.as_str() {
        // BTN opens the top 44%, BB calls with 13.2%..75% (the rest 3-bets or folds)
        "srp" => (range_text(0.132, 0.75).join(","), range_text(0.0, 0.44).join(","), 55, 975),
        // BB 3-bets the top 13.2% plus suited wheel aces 40% of the time; BTN calls 4.4%..13.9%
        // and 4-bets the rest (the bots' own preflop rules, Bot.preflop)
        "3bp" => {
            let mut o = range_text(0.0, 0.132);
            for h in ["A5s", "A4s", "A3s", "A2s"] { if !o.iter().any(|x| x == h) { o.push(format!("{h}:0.4")); } }
            (o.join(","), range_text(0.0436, 0.139).join(","), 195, 905)
        }
        _ => panic!("unknown spot"),
    };
    let board = flop_from_str(&flop).unwrap();
    let card_config = CardConfig { range: [oop_rng.parse().unwrap(), ip_rng.parse().unwrap()], flop: board, turn: NOT_DEALT, river: NOT_DEALT };
    let f = BetSizeOptions::try_from(("33%, 75%", "3x")).unwrap();
    let t = BetSizeOptions::try_from(("66%", "3x")).unwrap();
    let r = BetSizeOptions::try_from(("75%", "3x")).unwrap();
    let tree_config = TreeConfig { initial_state: BoardState::Flop, starting_pot: pot, effective_stack: stack, rake_rate: 0.0, rake_cap: 0.0,
        flop_bet_sizes: [f.clone(), f], turn_bet_sizes: [t.clone(), t], river_bet_sizes: [r.clone(), r],
        turn_donk_sizes: None, river_donk_sizes: None, add_allin_threshold: 1.5, force_allin_threshold: 0.15, merging_threshold: 0.1 };
    let mut game = PostFlopGame::with_config(card_config, ActionTree::new(tree_config).unwrap()).unwrap();
    game.allocate_memory(true);
    let t0 = Instant::now();
    let expl = solve(&mut game, iters, pot as f32 * target / 100.0, false);
    eprintln!("{spot} {flop}: exploitability {:.2}% pot in {:.0}s", 100.0 * expl / pot as f32, t0.elapsed().as_secs_f64());

    let private: [Vec<(u8, u8)>; 2] = [game.private_cards(0).to_vec(), game.private_cards(1).to_vec()];
    // normalized weights count joint combinations, so their sum at a node over the sum at the
    // root is the probability that play reaches it (the same for both players)
    game.cache_normalized_weights();
    let init = [0, 1].map(|p| game.normalized_weights(p).iter().map(|x| *x as f64).sum::<f64>());
    let mut wk = Walker { pot, flop: board, init, out: BTreeMap::new(), bucket_cache: BTreeMap::new(), private: [&private[0], &private[1]], max_depth: 3 };
    wk.walk(&mut game, 0, "", vec![], 1.0, &[]);
    eprintln!("{} decisions recorded in {:.0}s", wk.out.len(), t0.elapsed().as_secs_f64());

    let mut entries = vec![];
    for (k, e) in &wk.out {
        let sum: Vec<String> = e.sum.iter().map(|row| format!("[{}]", row.iter().map(|x| format!("{:.6}", x)).collect::<Vec<_>>().join(","))).collect();
        let reach: Vec<String> = e.reach.iter().map(|x| format!("{:.6}", x)).collect();
        entries.push(format!("{{\"key\":\"{k}\",\"player\":{},\"actions\":[{}],\"sum\":[{}],\"reach\":[{}]}}",
            e.player, e.actions.iter().map(|a| format!("\"{a}\"")).collect::<Vec<_>>().join(","), sum.join(","), reach.join(",")));
    }
    println!("{{\"spot\":\"{spot}\",\"flop\":\"{flop}\",\"exploitability\":{:.4},\"entries\":[{}]}}", expl / pot as f32, entries.join(","));
}
