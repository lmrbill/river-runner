"""Texture classes for flops, and the representative flops the solver runs on.

The class key here must match FlopTexture.key in the engine. Run:
    python3 flops.py > flops.txt
"""
import itertools
import random

RANKS = "23456789TJQKA"


def key(flop):
    """flop: list of 3 card strings like 'Kd'. Returns e.g. 'R-U-K-1'."""
    ranks = sorted((RANKS.index(c[0]) for c in flop), reverse=True)
    suits = len({c[1] for c in flop})
    suit = {1: "M", 2: "T", 3: "R"}[suits]
    distinct = len(set(ranks))
    if distinct == 1:
        return "X-3"  # trips: one class
    high = ranks[0]
    hb = "AKQJT"[12 - high] if high >= 8 else "9"
    if distinct == 2:
        return f"{'T' if suits == 2 else 'R'}-P-{hb}"
    span = ranks[0] - ranks[2]
    if ranks[0] == 12:  # ace can play low
        span = min(span, ranks[1] + 1)
    return f"{suit}-U-{hb}-{1 if span <= 4 else 0}"


def candidates():
    for r in itertools.combinations_with_replacement(range(12, -1, -1), 3):
        r = sorted(r, reverse=True)
        a, b, c = (RANKS[x] for x in r)
        if r[0] == r[1] == r[2]:
            yield [a + "s", b + "h", c + "d"]
        elif r[0] == r[1] or r[1] == r[2]:
            yield [a + "s", b + "h", c + "d"]
            yield [a + "s", b + "h", c + ("s" if r[0] != r[1] else "h")]
        else:
            yield [a + "s", b + "h", c + "d"]
            yield [a + "s", b + "s", c + "h"]
            yield [a + "s", b + "h", c + "s"]
            yield [a + "s", b + "s", c + "s"]


def main(per_class=2, seed=1):
    groups = {}
    for f in candidates():
        groups.setdefault(key(f), []).append(f)
    rng = random.Random(seed)
    for k in sorted(groups):
        for f in rng.sample(groups[k], min(per_class, len(groups[k]))):
            print(k, "".join(f))


if __name__ == "__main__":
    main()
