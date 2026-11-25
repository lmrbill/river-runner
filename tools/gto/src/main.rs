//! Offline generator for River Runner's GTO data.
//!
//! 1. Preflop all-in equity of every starting-hand class against every other (169 x 169),
//!    with card removal: each class pair is the average over its non-conflicting combo pairs.
//! 2. Heads-up push/fold Nash equilibrium (SB shoves or folds, BB calls or folds, no ante)
//!    for effective stacks on a grid, solved by fictitious play on that matrix.
//!
//! Output: Kotlin source for engine/.../PushFoldNash.kt on stdout, diagnostics on stderr.

use rayon::prelude::*;

const RANKS: &[u8] = b"23456789TJQKA";

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

struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 { self.0 ^= self.0 << 13; self.0 ^= self.0 >> 7; self.0 ^= self.0 << 17; self.0 }
    fn below(&mut self, n: u64) -> u64 { self.next() % n }
}

/// The 169 classes with their combos.
fn classes() -> Vec<(String, Vec<[u8; 2]>)> {
    let mut out = vec![];
    for hi in (0..13u8).rev() {
        for lo in (0..=hi).rev() {
            let h = RANKS[hi as usize] as char; let l = RANKS[lo as usize] as char;
            if hi == lo {
                let mut v = vec![];
                for a in 0..4 { for b in a + 1..4 { v.push([hi * 4 + a, hi * 4 + b]); } }
                out.push((format!("{h}{l}"), v));
            } else {
                let s: Vec<_> = (0..4).map(|x| [hi * 4 + x, lo * 4 + x]).collect();
                let mut o = vec![];
                for a in 0..4 { for b in 0..4 { if a != b { o.push([hi * 4 + a, lo * 4 + b]); } } }
                out.push((format!("{h}{l}s"), s));
                out.push((format!("{h}{l}o"), o));
            }
        }
    }
    out
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let trials: usize = args.get(1).and_then(|s| s.parse().ok()).unwrap_or(30000);
    let cls = classes();
    let n = cls.len();
    assert_eq!(n, 169);

    // eq[i][j] = equity of class i vs class j (ties count half); w[i][j] = non-conflicting combo pairs
    let rows: Vec<(Vec<f64>, Vec<f64>)> = (0..n).into_par_iter().map(|i| {
        let mut eq = vec![0.0; n]; let mut w = vec![0.0; n];
        let mut rng = Rng(0x9E3779B97F4A7C15 ^ (i as u64 + 1).wrapping_mul(0x2545F4914F6CDD1D));
        for j in 0..n {
            let pairs: Vec<([u8; 2], [u8; 2])> = cls[i].1.iter().flat_map(|a| cls[j].1.iter().map(move |b| (*a, *b)))
                .filter(|(a, b)| a[0] != b[0] && a[0] != b[1] && a[1] != b[0] && a[1] != b[1]).collect();
            w[j] = pairs.len() as f64;
            if pairs.is_empty() { continue; }
            let per = (trials / pairs.len()).max(50);
            let mut won = 0.0; let mut tot = 0.0;
            for (a, b) in &pairs {
                let dead: u64 = 1 << a[0] | 1 << a[1] | 1 << b[0] | 1 << b[1];
                for _ in 0..per {
                    let mut board = [0u8; 5]; let mut used = dead; let mut k = 0;
                    while k < 5 { let c = rng.below(52) as u8; if used >> c & 1 == 0 { used |= 1 << c; board[k] = c; k += 1; } }
                    let mut ha = [0u8; 7]; let mut hb = [0u8; 7];
                    ha[..5].copy_from_slice(&board); hb[..5].copy_from_slice(&board);
                    ha[5] = a[0]; ha[6] = a[1]; hb[5] = b[0]; hb[6] = b[1];
                    let (x, y) = (eval(&ha), eval(&hb));
                    won += if x > y { 1.0 } else if x == y { 0.5 } else { 0.0 };
                    tot += 1.0;
                }
            }
            eq[j] = won / tot;
        }
        (eq, w)
    }).collect();
    let eq: Vec<Vec<f64>> = rows.iter().map(|r| r.0.clone()).collect();
    let w: Vec<Vec<f64>> = rows.iter().map(|r| r.1.clone()).collect();
    eprintln!("AA vs KK {:.3}  AKs vs QQ {:.3}  72o vs AA {:.3}  AKo vs 22 {:.3}",
        eq[idx(&cls, "AA")][idx(&cls, "KK")], eq[idx(&cls, "AKs")][idx(&cls, "QQ")],
        eq[idx(&cls, "72o")][idx(&cls, "AA")], eq[idx(&cls, "AKo")][idx(&cls, "22")]);

    let grid: Vec<f64> = (2..=50).map(|k| k as f64 * 0.5).collect(); // 1.0 .. 25.0
    let mut push_at = vec![vec![0.0; n]; grid.len()];
    let mut call_at = vec![vec![0.0; n]; grid.len()];
    for (g, &s) in grid.iter().enumerate() {
        let (p, c) = nash(&eq, &w, s);
        push_at[g] = p; call_at[g] = c;
        let pct = |v: &Vec<f64>| v.iter().zip(&cls).map(|(f, c)| f * c.1.len() as f64).sum::<f64>() / 1326.0;
        eprintln!("{s:>5.1}bb  push {:5.1}%  call {:5.1}%", 100.0 * pct(&push_at[g]), 100.0 * pct(&call_at[g]));
    }

    let threshold = |tab: &Vec<Vec<f64>>, i: usize| -> f64 {
        let mut best = 0.0;
        for (g, &s) in grid.iter().enumerate() { if tab[g][i] >= 0.5 { best = s; } }
        best
    };
    let mut nonmono = 0;
    for tab in [&push_at, &call_at] {
        for i in 0..n { for g in 1..grid.len() { if tab[g][i] >= 0.5 && tab[g - 1][i] < 0.5 { nonmono += 1; } } }
    }
    eprintln!("non-monotone entries: {nonmono}");
    for name in ["AA", "A2o", "K2o", "Q2o", "J2o", "T2o", "72o", "32o", "K7o", "Q9o", "J5s", "64s", "22"] {
        let i = idx(&cls, name);
        eprintln!("{name:>4}  push <= {:>4.1}  call <= {:>4.1}", threshold(&push_at, i), threshold(&call_at, i));
    }

    println!("package com.riverrunner.engine\n");
    println!("/**");
    println!(" * Heads-up push/fold Nash equilibrium (no ante), generated by tools/gto from a 169 x 169");
    println!(" * preflop all-in equity matrix with card removal ({trials} boards per class pair).");
    println!(" * Each number is the largest effective stack, in big blinds, at which the hand shoves");
    println!(" * from the small blind (PUSH) or calls a small-blind shove from the big blind (CALL).");
    println!(" * {:.1} means at every stack the table covers. Do not edit by hand: re-run the tool.", grid[grid.len() - 1]);
    println!(" */");
    println!("object PushFoldNash {{");
    println!("    const val MAX_STACK = {:.1}\n", grid[grid.len() - 1]);
    for (name, tab) in [("PUSH", &push_at), ("CALL", &call_at)] {
        println!("    private val {name}: Map<String, Double> = mapOf(");
        let mut line = String::from("       ");
        for i in 0..n {
            let item = format!(" \"{}\" to {:.1},", cls[i].0, threshold(tab, i));
            if line.len() + item.len() > 110 { println!("{line}"); line = String::from("       "); }
            line.push_str(&item);
        }
        println!("{line}");
        println!("    )\n");
    }
    println!("    /** Largest stack (bb) at which [className] shoves from the small blind. */");
    println!("    fun pushUpTo(className: String): Double = PUSH.getValue(className)\n");
    println!("    /** Largest stack (bb) at which [className] calls a small-blind shove in the big blind. */");
    println!("    fun callUpTo(className: String): Double = CALL.getValue(className)\n");
    println!("    /** Share of all 1326 starting hands the small blind shoves at [stack] bb. */");
    println!("    fun pushFraction(stack: Double): Double =");
    println!("        PUSH.entries.sumOf {{ (k, v) -> if (v >= stack) combos(k) else 0 }} / 1326.0\n");
    println!("    private fun combos(name: String): Int = when {{");
    println!("        name.length == 2 -> 6");
    println!("        name.endsWith(\"s\") -> 4");
    println!("        else -> 12");
    println!("    }}");
    println!("}}");
}

fn idx(cls: &[(String, Vec<[u8; 2]>)], name: &str) -> usize { cls.iter().position(|c| c.0 == name).unwrap() }

/// Fictitious play for SB push / BB call at effective stack `s` bb. Returns averaged strategies.
fn nash(eq: &[Vec<f64>], w: &[Vec<f64>], s: f64) -> (Vec<f64>, Vec<f64>) {
    let n = eq.len();
    let mut push = vec![1.0; n]; let mut call = vec![0.5; n];
    let mut push_sum = vec![0.0; n]; let mut call_sum = vec![0.0; n];
    let iters = 4000;
    for t in 1..=iters {
        // SB: shoving vs folding (folding loses the 0.5 small blind)
        let br_push: Vec<f64> = (0..n).map(|i| {
            let (mut tot, mut ev) = (0.0, 0.0);
            for j in 0..n {
                let ww = w[i][j]; if ww == 0.0 { continue; }
                tot += ww;
                ev += ww * (call[j] * (eq[i][j] * 2.0 * s - s) + (1.0 - call[j]) * 1.0);
            }
            if ev / tot > -0.5 { 1.0 } else { 0.0 }
        }).collect();
        // BB: calling vs folding (folding loses the 1 bb big blind)
        let br_call: Vec<f64> = (0..n).map(|j| {
            let (mut tot, mut ev) = (0.0, 0.0);
            for i in 0..n {
                let ww = w[j][i] * push[i]; if ww == 0.0 { continue; }
                tot += ww;
                ev += ww * (eq[j][i] * 2.0 * s - s);
            }
            if tot > 0.0 && ev / tot > -1.0 { 1.0 } else { 0.0 }
        }).collect();
        let a = 1.0 / (t as f64 + 1.0);
        for k in 0..n {
            push[k] += a * (br_push[k] - push[k]);
            call[k] += a * (br_call[k] - call[k]);
            if t > iters / 2 { push_sum[k] += push[k]; call_sum[k] += call[k]; }
        }
    }
    let m = (iters - iters / 2) as f64;
    (push_sum.iter().map(|x| x / m).collect(), call_sum.iter().map(|x| x / m).collect())
}
