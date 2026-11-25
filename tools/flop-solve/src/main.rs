//! Solves heads-up flop spots with postflop-solver and writes the flop decisions,
//! bucketed by hand strength, as JSON. Licensed AGPL-3.0-or-later because it links
//! postflop-solver; only its output (numbers) goes into the app.
//!
//! usage: rr-flop-solve <spot> <flop> [max_iters] [target_pct]
//!   spot: srp (BTN open vs BB call, 100 bb)

use postflop_solver::*;
use std::time::Instant;

const ORDER: &str = include_str!("../order.txt");

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
fn range_text(lo: f64, hi: f64) -> String {
    let names: Vec<&str> = ORDER.split_whitespace().collect();
    let mut cum = 0usize; let mut out = vec![];
    for n in names {
        let c = combos_of(n);
        let p = (cum as f64 + c as f64 / 2.0) / 1326.0;
        cum += c;
        if p >= lo && p < hi { out.push(n.to_string()); }
    }
    out.join(",")
}

/// 0 = no draw, 1 = flush draw or 8-out straight draw (only when not already a straight or better).
fn draw(hole: [u8; 2], board: &[u8]) -> bool {
    let mut cards = board.to_vec(); cards.extend_from_slice(&hole);
    if eval(&cards) >> 20 >= 4 { return false; }
    for s in 0..4 {
        let n = cards.iter().filter(|c| suit(**c) == s).count();
        if n == 4 && hole.iter().any(|c| suit(*c) == s) { return true; }
    }
    let ranks = |cs: &[u8]| -> u16 { cs.iter().fold(0u16, |m, c| m | 1 << rank(*c)) };
    let all = ranks(&cards); let brd = ranks(board);
    let straight = |m: u16| -> bool { let m = (m as u32) << 1 | ((m as u32 >> 12) & 1); (0..=9).any(|lo| m >> lo & 0b11111 == 0b11111) };
    let outs = (0..13).filter(|r| all >> r & 1 == 0 && straight(all | 1 << r) && !straight(brd | 1 << r)).count();
    outs >= 2
}

/// Bucket 0..19: strength decile within the player's own starting range on this flop, +10 with a draw.
fn buckets(range: &[(u8, u8)], weights: &[f32], board: &[u8]) -> Vec<usize> {
    let score: Vec<u32> = range.iter().map(|(a, b)| { let mut v = board.to_vec(); v.push(*a); v.push(*b); eval(&v) }).collect();
    let total: f64 = weights.iter().map(|w| *w as f64).sum();
    range.iter().enumerate().map(|(i, (a, b))| {
        let (mut below, mut equal) = (0.0, 0.0);
        for j in 0..range.len() {
            if score[j] < score[i] { below += weights[j] as f64 } else if score[j] == score[i] { equal += weights[j] as f64 }
        }
        let p = (below + equal / 2.0) / total;
        ((p * 10.0) as usize).min(9) + if draw([*a, *b], board) { 10 } else { 0 }
    }).collect()
}

fn label(a: &Action, pot: i32) -> String {
    match a {
        Action::Fold => "F".into(), Action::Check => "X".into(), Action::Call => "C".into(),
        Action::Bet(x) => format!("B{}", (100.0 * *x as f64 / pot as f64).round() as i32),
        Action::Raise(_) => "R".into(), Action::AllIn(_) => "A".into(), _ => "?".into(),
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let spot = args[1].clone(); let flop = args[2].clone();
    let iters: u32 = args.get(3).and_then(|s| s.parse().ok()).unwrap_or(300);
    let target: f32 = args.get(4).and_then(|s| s.parse().ok()).unwrap_or(1.0);
    // pot and stacks in tenths of a big blind
    let (oop_rng, ip_rng, pot, stack) = match spot.as_str() {
        // BTN opens 2.5 bb with the top 44%, BB calls with 13.2%..75% (the rest 3-bets or folds)
        "srp" => (range_text(0.132, 0.75), range_text(0.0, 0.44), 55, 975),
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
    eprintln!("{flop}: exploitability {:.2}% pot in {:.0}s", 100.0 * expl / pot as f32, t0.elapsed().as_secs_f64());

    let bk: Vec<Vec<usize>> = (0..2).map(|p| buckets(game.private_cards(p), game.initial_weights(p), &board)).collect();
    let mut nodes = vec![];
    // walk the flop decisions up to three actions deep
    fn walk(game: &mut PostFlopGame, path: Vec<String>, bk: &[Vec<usize>], pot: i32, out: &mut Vec<String>) {
        if game.is_terminal_node() || game.is_chance_node() || path.len() >= 3 { return; }
        game.cache_normalized_weights();
        let p = game.current_player();
        let acts = game.available_actions();
        let bet = game.total_bet_amount();
        let pot_now = pot + bet[0] + bet[1];
        let labels: Vec<String> = acts.iter().map(|a| label(a, pot_now)).collect();
        let s = game.strategy(); let n = game.private_cards(p).len(); let w = game.normalized_weights(p);
        let mut reach = vec![0.0f64; 20]; let mut freq = vec![vec![0.0f64; acts.len()]; 20];
        for h in 0..n {
            let b = bk[p][h]; reach[b] += w[h] as f64;
            for a in 0..acts.len() { freq[b][a] += w[h] as f64 * s[a * n + h] as f64; }
        }
        let rows: Vec<String> = (0..20).map(|b| {
            let v: Vec<String> = (0..acts.len()).map(|a| if reach[b] > 0.0 { format!("{:.0}", 100.0 * freq[b][a] / reach[b]) } else { "-1".into() }).collect();
            format!("[{}]", v.join(","))
        }).collect();
        let total: f64 = reach.iter().sum();
        let reach_s: Vec<String> = reach.iter().map(|r| format!("{:.3}", r / total.max(1e-12))).collect();
        out.push(format!("{{\"path\":\"{}\",\"player\":{},\"actions\":[{}],\"freq\":[{}],\"reach\":[{}]}}",
            path.join("-"), p, labels.iter().map(|l| format!("\"{l}\"")).collect::<Vec<_>>().join(","), rows.join(","), reach_s.join(",")));
        for (i, l) in labels.iter().enumerate() {
            if l == "F" { continue; }
            let hist = game.history().to_vec();
            game.play(i);
            let mut np = path.clone(); np.push(l.clone());
            walk(game, np, bk, pot, out);
            game.apply_history(&hist);
        }
    }
    walk(&mut game, vec![], &bk, pot, &mut nodes);
    println!("{{\"spot\":\"{spot}\",\"flop\":\"{flop}\",\"exploitability\":{:.4},\"nodes\":[{}]}}", expl / pot as f32, nodes.join(","));
}
