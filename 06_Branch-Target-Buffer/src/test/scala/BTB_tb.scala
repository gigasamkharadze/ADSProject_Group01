// ADS I Class Project
// Branch Target Buffer - Unit Tests (Task 6.5)
//
// Chair of Electronic Design Automation, RPTU in Kaiserslautern
// File created on 07/12/2026

/*
Direct, isolated unit tests for BranchPredictor and BTB, poking their ports
directly instead of driving the whole pipeline. This makes it tractable to hit
precise LRU-eviction and FSM-transition edge cases that would otherwise need
long, fragile end-to-end instruction sequences.

Task 6.5 requires demonstrating:
    1. Correct predictions for valid-entry PCs
    2. Correct BTB update handling
    3. Correct LRU eviction
    4. Accurate FSM state transitions and predictions based on current state
*/

package core_tile

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class BTBTest extends AnyFlatSpec with ChiselScalatestTester {

  // ── 4. FSM state transitions (BranchPredictor in isolation) ──────────────
  // Full transition table of the 2-bit saturating counter (cf. slide 6-47):
  // a prediction must be wrong twice in a row before the predicted direction
  // itself flips, since every transition moves exactly one step and has to
  // pass through the corresponding "weak" state first.

  "BranchPredictor" should "follow the 2-bit saturating counter transition table" in {
    test(new BranchPredictor) { dut =>
      // strongNotTaken: stays on repeated not-taken (saturation), moves to
      // weakNotTaken on a single taken observation
      dut.io.currentState.poke(PredictorState.strongNotTaken)
      dut.io.taken.poke(false.B)
      dut.io.nextState.expect(PredictorState.strongNotTaken)

      dut.io.currentState.poke(PredictorState.strongNotTaken)
      dut.io.taken.poke(true.B)
      dut.io.nextState.expect(PredictorState.weakNotTaken)

      // weakNotTaken: taken -> weakTaken (direction flips only here, after
      // having already taken one step away from strongNotTaken)
      dut.io.currentState.poke(PredictorState.weakNotTaken)
      dut.io.taken.poke(true.B)
      dut.io.nextState.expect(PredictorState.weakTaken)

      dut.io.currentState.poke(PredictorState.weakNotTaken)
      dut.io.taken.poke(false.B)
      dut.io.nextState.expect(PredictorState.strongNotTaken)

      // weakTaken: not-taken -> weakNotTaken (direction flips), taken -> strongTaken
      dut.io.currentState.poke(PredictorState.weakTaken)
      dut.io.taken.poke(false.B)
      dut.io.nextState.expect(PredictorState.weakNotTaken)

      dut.io.currentState.poke(PredictorState.weakTaken)
      dut.io.taken.poke(true.B)
      dut.io.nextState.expect(PredictorState.strongTaken)

      // strongTaken: stays on repeated taken (saturation), moves to weakTaken
      // on a single not-taken observation
      dut.io.currentState.poke(PredictorState.strongTaken)
      dut.io.taken.poke(true.B)
      dut.io.nextState.expect(PredictorState.strongTaken)

      dut.io.currentState.poke(PredictorState.strongTaken)
      dut.io.taken.poke(false.B)
      dut.io.nextState.expect(PredictorState.weakTaken)
    }
  }

  "BranchPredictor" should "require two consecutive wrong predictions before flipping direction" in {
    test(new BranchPredictor) { dut =>
      // Start at strongNotTaken (predicts not-taken); one "taken" observation
      // only weakens the prediction, it does not yet predict "taken"
      dut.io.currentState.poke(PredictorState.strongNotTaken)
      dut.io.taken.poke(true.B)
      dut.io.nextState.expect(PredictorState.weakNotTaken)

      // A second consecutive "taken" observation is required to actually
      // predict "taken" from here on
      dut.io.currentState.poke(PredictorState.weakNotTaken)
      dut.io.taken.poke(true.B)
      dut.io.nextState.expect(PredictorState.weakTaken)
    }
  }

  // ── Helpers ────────────────────────────────────────────────────────────
  // PC values chosen so PC[4:2] (the set index) is identical for all three
  // (index 0) while PC[31:5] (the tag) differs, guaranteeing they alias into
  // the same 2-way set for the LRU eviction test.
  val pcA = "h00000000".U(32.W) // index 0, tag 0
  val pcB = "h00000020".U(32.W) // index 0, tag 1
  val pcC = "h00000040".U(32.W) // index 0, tag 2

  // ── 1 & 2. Correct predictions for valid entries + correct update handling ──

  "BTB" should "report no valid prediction for an empty table" in {
    test(new BTB) { dut =>
      dut.io.PC.poke(pcA)
      dut.io.valid.expect(false.B)
    }
  }

  it should "allocate a new entry on update and then predict correctly for it" in {
    test(new BTB) { dut =>
      // No prior entry: the initial state of a freshly allocated entry is
      // weakTaken regardless of the mispredicted input (cf. BTB.scala docstring)
      dut.io.update.poke(true.B)
      dut.io.updatePC.poke(pcA)
      dut.io.updateTarget.poke("h00001000".U(32.W))
      dut.io.mispredicted.poke(false.B)
      dut.clock.step(1)
      dut.io.update.poke(false.B)

      dut.io.PC.poke(pcA)
      dut.io.valid.expect(true.B)
      dut.io.target.expect("h00001000".U(32.W))
      dut.io.predictTaken.expect(true.B) // weakTaken predicts taken
    }
  }

  it should "overwrite the stored target on a hit while keeping the tag/way" in {
    test(new BTB) { dut =>
      dut.io.update.poke(true.B)
      dut.io.updatePC.poke(pcA)
      dut.io.updateTarget.poke("h00001000".U(32.W))
      dut.io.mispredicted.poke(false.B)
      dut.clock.step(1)

      // Same PC, new target: must be treated as a hit and just refresh the target
      dut.io.updatePC.poke(pcA)
      dut.io.updateTarget.poke("h00002000".U(32.W))
      dut.io.mispredicted.poke(false.B)
      dut.clock.step(1)
      dut.io.update.poke(false.B)

      dut.io.PC.poke(pcA)
      dut.io.valid.expect(true.B)
      dut.io.target.expect("h00002000".U(32.W))
    }
  }

  it should "not affect unrelated sets when updating one entry" in {
    test(new BTB) { dut =>
      dut.io.update.poke(true.B)
      dut.io.updatePC.poke(pcA) // index 0
      dut.io.updateTarget.poke("h00001000".U(32.W))
      dut.io.mispredicted.poke(false.B)
      dut.clock.step(1)
      dut.io.update.poke(false.B)

      // A PC mapping to a different set (index 1) must still miss
      dut.io.PC.poke("h00000004".U(32.W)) // index 1
      dut.io.valid.expect(false.B)
    }
  }

  // ── 3. Correct LRU eviction across a 2-way set ────────────────────────────

  it should "evict the least-recently-used way when a third tag maps to a full set" in {
    test(new BTB) { dut =>
      // 1) Allocate way0 with pcA (set 0 starts fully invalid)
      dut.io.update.poke(true.B)
      dut.io.updatePC.poke(pcA)
      dut.io.updateTarget.poke("h00000100".U(32.W))
      dut.io.mispredicted.poke(false.B)
      dut.clock.step(1)

      // 2) Allocate way1 with pcB (way0 valid/pcA, way1 still invalid)
      dut.io.updatePC.poke(pcB)
      dut.io.updateTarget.poke("h00000200".U(32.W))
      dut.io.mispredicted.poke(false.B)
      dut.clock.step(1)

      // Both ways now hold valid, distinct entries (pcA, pcB); way1 (pcB) is MRU,
      // so way0 (pcA) is the LRU way and must be the one evicted next.
      dut.io.update.poke(false.B)
      dut.io.PC.poke(pcA)
      dut.io.valid.expect(true.B)
      dut.io.PC.poke(pcB)
      dut.io.valid.expect(true.B)

      // 3) A third, distinct tag (pcC) mapping to the same set must evict the LRU way (pcA)
      dut.io.update.poke(true.B)
      dut.io.updatePC.poke(pcC)
      dut.io.updateTarget.poke("h00000300".U(32.W))
      dut.io.mispredicted.poke(false.B)
      dut.clock.step(1)
      dut.io.update.poke(false.B)

      // pcA was evicted...
      dut.io.PC.poke(pcA)
      dut.io.valid.expect(false.B)

      // ...while pcB (the more recently used entry) and the newly inserted pcC survive
      dut.io.PC.poke(pcB)
      dut.io.valid.expect(true.B)
      dut.io.target.expect("h00000200".U(32.W))

      dut.io.PC.poke(pcC)
      dut.io.valid.expect(true.B)
      dut.io.target.expect("h00000300".U(32.W))
    }
  }

  // ── Integration: predictor state evolves correctly through the BTB's update path ──

  it should "strengthen an entry's prediction on repeated correct taken predictions" in {
    test(new BTB) { dut =>
      // Allocate at weakTaken
      dut.io.update.poke(true.B)
      dut.io.updatePC.poke(pcA)
      dut.io.updateTarget.poke("h00000100".U(32.W))
      dut.io.mispredicted.poke(false.B)
      dut.clock.step(1)

      // Branch was taken again exactly as predicted (weakTaken predicts taken,
      // mispredicted = false -> actual = taken) -> saturates to strongTaken
      dut.io.updatePC.poke(pcA)
      dut.io.mispredicted.poke(false.B)
      dut.clock.step(1)
      dut.io.update.poke(false.B)

      dut.io.PC.poke(pcA)
      dut.io.predictTaken.expect(true.B)
    }
  }

  it should "weaken and eventually flip an entry's prediction after repeated mispredictions" in {
    test(new BTB) { dut =>
      // Allocate at weakTaken (predicts taken)
      dut.io.update.poke(true.B)
      dut.io.updatePC.poke(pcA)
      dut.io.updateTarget.poke("h00000100".U(32.W))
      dut.io.mispredicted.poke(false.B)
      dut.clock.step(1)

      // 1st misprediction: predicted taken, actually not taken -> weakTaken -> weakNotTaken
      dut.io.updatePC.poke(pcA)
      dut.io.mispredicted.poke(true.B)
      dut.clock.step(1)

      dut.io.update.poke(false.B)
      dut.io.PC.poke(pcA)
      dut.io.predictTaken.expect(false.B) // weakNotTaken already predicts not-taken

      // 2nd consecutive "not taken" outcome: predicted not-taken (correctly, mispredicted=false)
      // -> weakNotTaken -> strongNotTaken; still predicts not-taken
      dut.io.update.poke(true.B)
      dut.io.updatePC.poke(pcA)
      dut.io.mispredicted.poke(false.B)
      dut.clock.step(1)
      dut.io.update.poke(false.B)

      dut.io.PC.poke(pcA)
      dut.io.predictTaken.expect(false.B)
    }
  }

  // ── Task 6.4: prediction accuracy vs. the static "assume not taken" baseline ──
  // Simulates the exact branch/target/outcome sequence produced by the
  // BinaryFile_btb_loop benchmark (cf. generate_btb_loop_bin.py): a
  // loop-closing BNE at PC 16, branching back to PC 8, taken on 4 of its 5
  // executions (iterations 1-4) and not-taken on the 5th (loop exit).
  it should "achieve higher prediction accuracy than the static baseline over a repeated loop branch" in {
    test(new BTB) { dut =>
      val branchPC   = "h00000010".U(32.W) // PC 16 (BNE)
      val loopTarget = "h00000008".U(32.W) // PC  8 (LOOP)

      val actualOutcomes = Seq(true, true, true, true, false)

      var correctPredictions = 0
      actualOutcomes.foreach { actualTaken =>
        // 1) Lookup, exactly as IF does before the branch is resolved in EX
        dut.io.PC.poke(branchPC)
        val predValid = dut.io.valid.peek().litToBoolean
        val predTaken = predValid && dut.io.predictTaken.peek().litToBoolean
        if (predTaken == actualTaken) correctPredictions += 1

        // 2) Update, exactly as EX does once the real outcome is known
        val mispredicted = predTaken != actualTaken
        dut.io.update.poke(true.B)
        dut.io.updatePC.poke(branchPC)
        dut.io.updateTarget.poke(loopTarget)
        dut.io.mispredicted.poke(mispredicted.B)
        dut.clock.step(1)
        dut.io.update.poke(false.B)
      }

      // BTB: cold miss on iteration 1, correct predictions on 2-4 (state
      // saturates at strongTaken after iteration 2), mispredicts once more
      // on the exiting 5th iteration -> 3/5 = 60% accuracy.
      // Static "assume not taken" baseline: only ever correct on the single
      // not-taken (exiting) iteration -> 1/5 = 20% accuracy.
      val btbAccuracy    = correctPredictions.toDouble / actualOutcomes.size
      val staticAccuracy = actualOutcomes.count(_ == false).toDouble / actualOutcomes.size

      assert(correctPredictions == 3)
      assert(btbAccuracy > staticAccuracy)
    }
  }
}
