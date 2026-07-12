// ADS I Class Project
// Pipelined RISC-V Core - 2-bit Dynamic Branch Predictor
//
// Chair of Electronic Design Automation, RPTU in Kaiserslautern
// File created on 07/12/2026

/*
2-bit saturating counter branch predictor (cf. slide 6-47 of the ADS I lecture slides).

PredictorState: the four states of the saturating counter, ordered so that the
MSB of the encoding directly indicates the predicted direction (0/1 = strongNotTaken/
weakNotTaken predict "not taken", 2/3 = weakTaken/strongTaken predict "taken"):

    0 = strongNotTaken
    1 = weakNotTaken
    2 = weakTaken
    3 = strongTaken

A prediction must be wrong twice in a row before the predicted direction actually
flips, since the state only crosses the strongNotTaken/weakNotTaken <-> weakTaken/
strongTaken boundary one step at a time and has to pass through the weak state first.

BranchPredictor is implemented as a separate, reusable module: given the current
2-bit state of a BTB entry and the actual outcome of the branch (taken/not taken),
it combinationally computes the next state. It holds no state of its own -- the
BTB is responsible for storing the per-entry state in its table and feeding it
back into this module on every update.

Inputs:
    currentState: the current 2-bit predictor state of the entry being updated
    taken: the actual outcome of the branch (true = taken, false = not taken)

Outputs:
    nextState: the updated 2-bit predictor state after observing the outcome
*/

package core_tile

import chisel3._
import chisel3.experimental.ChiselEnum

// -----------------------------------------
// Predictor State Encoding
// -----------------------------------------

object PredictorState extends ChiselEnum {
  val strongNotTaken, weakNotTaken, weakTaken, strongTaken = Value

  def isTaken(s: PredictorState.Type): Bool = (s === weakTaken) || (s === strongTaken)
}

// -----------------------------------------
// 2-bit Saturating Counter Branch Predictor
// -----------------------------------------

class BranchPredictor extends Module {
  val io = IO(new Bundle {
    val currentState = Input(PredictorState())
    val taken        = Input(Bool())

    val nextState     = Output(PredictorState())
  })

  io.nextState := io.currentState

  when(io.taken) {
    // move towards strongTaken, saturate at the top
    when(io.currentState =/= PredictorState.strongTaken) {
      io.nextState := PredictorState(io.currentState.asUInt + 1.U)
    }
  }.otherwise {
    // move towards strongNotTaken, saturate at the bottom
    when(io.currentState =/= PredictorState.strongNotTaken) {
      io.nextState := PredictorState(io.currentState.asUInt - 1.U)
    }
  }
}
