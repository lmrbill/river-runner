# Flop solver (offline)

Solves heads-up flop spots with [postflop-solver](https://github.com/b-inary/postflop-solver)
and writes the flop decisions, bucketed by hand strength, as JSON. The app never runs this:
`combine.py` turns the JSON into `engine/.../FlopGto.kt`, which is plain data.

**License:** this tool links postflop-solver, so this folder is AGPL-3.0-or-later. Its output is
numbers, and the app itself stays MIT.

- `flops.py` groups all flops into 49 texture classes and picks two representative flops per class
  (`flops.txt`). The class key must match `FlopTexture.key` in the engine.
- `src/main.rs` solves one flop. Spot `srp`: button opens 2.5 bb with the top 44% of River Runner's
  hand order, the big blind calls with 13.2%–75%, 100 bb stacks. Bet sizes: flop 33%/75% with 3x
  raises, turn 66%, river 75%; solved to about 1% of the pot.
- Buckets: a hand's strength percentile within its own range on that flop (deciles), plus 10 when it
  has a flush draw or an 8-out straight draw. The engine computes the same buckets at the table.
- `.github/workflows/solve-flops.yml` runs every flop on GitHub Actions (20 jobs in parallel) and
  pushes the results to the `gto-data` branch.

```bash
RUSTFLAGS=--cap-lints=warn cargo build --release
./target/release/rr-flop-solve srp Kd7s2c > out.json   # about 5 minutes on 4 cores
python3 combine.py path/to/json/dir > ../../engine/src/commonMain/kotlin/com/riverrunner/engine/FlopGto.kt
```
