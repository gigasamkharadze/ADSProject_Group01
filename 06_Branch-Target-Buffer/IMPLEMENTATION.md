# Assignment 6: Branch Target Buffer Implementation

## Task 6.1 Preparation - Questions and Answers

### 1. How does a higher associativity affect the performance of a cache?

Higher associativity reduces conflict misses: with more ways per set, more distinct addresses that map to the same index can be resident simultaneously, so entries are less likely to evict each other purely because they alias to the same set. This raises the effective hit rate for a given total capacity. The cost is that every lookup must compare the tag against every way in the set in parallel (more comparators), and the replacement policy (e.g. LRU) becomes more complex to track as the number of ways grows. Applied to a BTB specifically: with only 1 way (direct-mapped), two frequently-taken branches that happen to share the same index bits would constantly evict each other's entry, destroying prediction accuracy for both; with 2 ways, as implemented here, both branches can coexist in the same set as long as at most 2 distinct branches ever map to that index at once.

### 2. What's the best initial state for the 2-bit predictor FSM? What effects would different initial states have in regular program patterns (e.g., loops)?

`weakTaken` was chosen as the initial state for newly allocated entries (`BTB.scala`, `InitialState`). The most common branch pattern in real programs is the loop-closing backward branch (e.g. a `bne`/`blt` at the bottom of a loop body), which is taken on every iteration except the last. Starting a fresh entry at `weakTaken` means the very first prediction for such a branch is already correct, and a single further "taken" observation immediately saturates it to `strongTaken`; a loop that runs for many iterations therefore reaches its stable, correct prediction state after just one iteration.

Comparing initial states:
- `strongNotTaken`: safest for forward, rarely-taken branches (e.g. error-handling branches), but costs an extra misprediction for every loop-closing branch, since the state must first transition to `weakNotTaken` before crossing into `weakTaken`/`strongTaken` territory — i.e. it needs two consecutive "taken" observations before predicting "taken", so the first two loop iterations are always mispredicted.
- `weakTaken` (chosen): correct immediately for loop-closing branches (the dominant case). For a branch that is genuinely rarely taken, it costs exactly one misprediction (on the first execution) before flipping to `weakNotTaken`, i.e. the same one-misprediction cost that `strongNotTaken` would pay for a loop branch, just for the opposite (less common) pattern.
- `strongTaken`: also predicts loops correctly immediately, but needs two consecutive "not taken" observations to flip, which unnecessarily delays adapting to non-loop, rarely-taken branches.

`weakTaken` is the best compromise: it is correct on the first prediction for the dominant loop pattern, while only being one misprediction away (not two) from adapting correctly to the opposite pattern.

### Implementation details (register structure, index bits, tag bits)

1. **Register structure**: The BTB cannot use `Mem`/`SyncReadMem` because every lookup must compare the tag against *both* ways of the indexed set in parallel (an associative search), which single-port memory classes do not support. Instead, `BTB.scala` uses `RegInit(VecInit(Seq.fill(NumSets)(VecInit(Seq.fill(NumWays)(0.U.asTypeOf(new BTBEntry))))))` — an explicit 8×2 register array of `BTBEntry` bundles (`valid`, `tag`, `target`, `state`), giving direct combinational read access to both ways of a set at once.
2. **Index bits**: With 8 sets, `log2(8) = 3` index bits are needed. Since RV32I instructions are word-aligned, `PC[1:0]` is always `"00"` and carries no addressing information, so the index is taken from the next 3 bits up: `PC[4:2]`.
3. **Tag bits**: The tag must be the remaining PC bits above the index, i.e. `32 − 3 (index) − 2 (always-zero alignment) = 27` bits: `PC[31:5]`. Together, tag + index + alignment bits exactly reconstruct the full 32-bit PC with no aliasing between distinct instruction addresses.

## Overview

Assignment 6 extends the Assignment 5 pipeline with a **Branch Target Buffer (BTB)**: a 2-way set-associative cache of branch targets and 2-bit saturating-counter prediction state, consulted by IF and updated by EX. The BTB applies only to conditional branches (`beq`, `bne`, `blt`, `bge`, `bltu`, `bgeu`); unconditional jumps (`jal`, `jalr`) are always treated as taken and never consult the BTB, exactly as in Assignment 5.

## Project Structure (files added/changed relative to Directory 5)

- **`BranchPredictor.scala`** (new): standalone, reusable 2-bit saturating-counter module (`PredictorState` ChiselEnum with `strongNotTaken`/`weakNotTaken`/`weakTaken`/`strongTaken`). Combinationally computes `nextState` from `currentState` and the actual branch outcome; holds no state of its own.
- **`BTB.scala`** (new): the 2-way, 8-set BTB module described above. Instantiates `BranchPredictor` internally and drives it with the reconstructed actual outcome (`predictedTakenAtIssue ^ mispredicted`) on every update.
- **`IFstage.scala`**: PC-selection priority extended to (1) `flush` from EX → `resolvedPC`, (2) BTB valid + taken prediction → `btbTarget`, (3) otherwise `PC + 4` (the Assignment 5 static fallback). BTB inputs are wired in externally, so this file transparently degrades to Assignment 5 behaviour when `useBTB = false`.
- **`EXstage.scala`**: now also resolves the fall-through-vs-taken target (`resolvedPC`), compares it against the prediction piped in from IF (`predictTaken`/`predictTarget`), and drives the BTB update interface (`btbUpdate`, `btbUpdatePC`, `btbUpdateTarget`, `btbMispredicted`). A conditional branch only flushes on an actual misprediction (wrong direction, or "taken" with a stale predicted target); jumps always flush, matching Assignment 5.
- **`IFbarrier.scala` / `IDbarrier.scala`**: extended to pipe `predictTaken`/`predictTarget` alongside the instruction from IF through ID into EX, so EX can compare the original IF-time prediction against the outcome it just computed.
- **`core.scala`**: instantiates the BTB and wires it between IF (lookup) and EX (update) when `useBTB = true` (default); ties IF's prediction inputs to "no prediction" when `useBTB = false` (see Task 6.3 below).

## Task 6.3: Modular Static/Dynamic Switching

`PipelinedRV32Icore` takes a Scala-level (compile-time, not hardware-level) constructor parameter `useBTB: Boolean = true`. `core.scala` wraps the BTB instantiation and wiring in a Scala `if (useBTB) { ... } else { ... }` block:

- `useBTB = true`: the BTB module is instantiated and wired to IF (lookup) and EX (update), exactly as specified.
- `useBTB = false`: the BTB is not instantiated at all; IF's `btbValid`/`btbTarget`/`btbPredictTaken` inputs are tied to constants (`false.B`/`0.U`/`false.B`), so IF's existing priority mux falls through to its Assignment 5 static "assume not taken" behaviour with no changes to IF/EX code required.

Since no code in IF or EX needs to change between the two modes, and a branch that is executed for the very first time has no BTB entry regardless (so `useBTB = true` behaves identically to `useBTB = false` for it), every Assignment 5 regression test continues to pass unmodified under the default `useBTB = true` configuration.

## Task 6.4: Performance Evaluation

`BTB_tb.scala` includes a directed accuracy test that replays a fixed 5-branch outcome sequence (4× taken, 1× not-taken, matching a typical 5-iteration loop) directly against the BTB module and counts correct predictions: the BTB achieves 3/5 (60%) accuracy against this sequence (cold-miss on the first execution, correct for the 3 subsequent repeats, since the saturating counter needs one observation to lock onto the loop's dominant "taken" pattern from its `weakTaken` initial state) versus a 1/5 (20%) accuracy for a fixed "always predict not-taken" static baseline (correct only on the final, non-taken iteration).

`BTBPerformance_tb.scala` complements this with an end-to-end cycle-count comparison: `generate_btb_loop_bin.py` assembles a 5-iteration countdown loop (`BinaryFile_btb_loop`) whose closing `bne` is taken on iterations 1-4 and not-taken on iteration 5 (loop exit). The test instantiates `PipelinedRV32Icore` twice, once with `useBTB = true` and once with `useBTB = false`, and counts clock cycles until the loop-exit marker instruction reaches the WB stage. The static core mispredicts (and pays a pipeline-flush penalty) on every one of the 4 taken iterations, while the BTB-enabled core only mispredicts on the first (cold-miss) and last (loop-exit) iterations — so it reaches the same final state in measurably fewer cycles (`cyclesWithBTB < cyclesStatic`, confirmed passing).

## Task 6.5: BTB Test Coverage (`BTB_tb.scala`)

- **FSM correctness**: exhaustive test of all 8 `(state, outcome)` transitions of the 2-bit saturating counter, plus a dedicated test demonstrating that two consecutive wrong predictions are required before the predicted direction flips (`strongNotTaken` → taken → `weakNotTaken` → taken → `weakTaken`, i.e. 2 steps).
- **Predictions for valid entries**: verifies an empty table reports `valid = false` for any PC, and that a freshly allocated entry (via `update`) reports the correct target and a `weakTaken`-derived "taken" prediction on the very next lookup.
- **Update handling**: verifies that updating an existing entry (same tag, same way) overwrites its stored target while preserving its tag/way, and that updating one set never disturbs entries in unrelated sets.
- **LRU eviction**: allocates two different tags into the two ways of the same set, then updates a third, different tag mapping to the same set; verifies the least-recently-used way (the one that was not the most recently written/hit) is evicted (its old entry becomes replaced) while the more recently used way's entry survives untouched.
- **State transitions under real usage**: verifies an entry strengthens from `weakTaken` to `strongTaken` after a repeated correct "taken" prediction, and weakens/flips from `weakTaken` through `weakNotTaken` to `strongNotTaken` after repeated mispredictions.

All 9 BTB-specific tests, plus all pre-existing Assignment 5 regression tests (basic RV32I test + 4 control-hazard tests) and the Task 6.4 performance comparison test, pass under `sbt test` (16/16).

## Known Limitations Inherited from Directory 5 (not fixed — out of scope)

Two pre-existing latent quirks in `IDstage.scala` (unchanged from Directory 5, since this assignment restricts changes to IF/EX) were discovered while building the loop benchmark for Task 6.4 and had to be worked around at the benchmark-program level rather than fixed:

1. For B-type instructions, `IDstage` always overwrites `operandB` with the branch immediate; the correct rs2 value for the comparison is only restored if EX's forwarding logic happens to match rs2 against a recently-written destination register.
2. `IDstage` sets `io.rs2 := instr[24:20]` unconditionally for every instruction type, including I-type instructions, where those bits are actually the low 5 bits of the immediate rather than a real register index. If that value coincidentally matches a register written 1-2 instructions earlier, EX's forwarding logic spuriously forwards that register's value into what should be the immediate operand.

`generate_btb_loop_bin.py` avoids triggering quirk 2 by choosing all I-type immediates as multiples of 32, so their low 5 bits (and hence any spurious rs2) always resolve to `x0`, which the forwarding guard already excludes.
