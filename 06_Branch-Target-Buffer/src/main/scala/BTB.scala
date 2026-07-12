// ADS I Class Project
// Pipelined RISC-V Core - Branch Target Buffer
//
// Chair of Electronic Design Automation, RPTU in Kaiserslautern
// File created on 05/12/2026 by Tobias Jauch (@tojauch)

/*
Branch Target Buffer (BTB): a hardware component that predicts the target address of conditional branch instructions to improve pipeline performance

Functionality (cf. slide 6-48 of the lecture slides):
    Stores target addresses and prediction information for conditional branch instructions
    On a branch instruction, checks if the instruction is in the BTB and retrieves the predicted target address and prediction state
    If the prediction is taken, the processor fetches the instruction from the predicted target address; if not taken, it continues sequentially
    Updates the BTB entry based on the actual outcome of the branch instruction (taken or not taken) and updates the prediction state accordingly

Organization:
    2-way set-associative, 8 sets (cf. slide 7-23 for the associativity concept applied to a cache-like structure).
    RV32I instructions are word-aligned, so PC[1:0] is always "00" and carries no information.
    PC[4:2] (3 bits) selects one of the 8 sets.
    PC[31:5] (27 bits) is stored as the tag of an entry.
    3 (index) + 27 (tag) + 2 (always-zero alignment bits) = 32 bits, i.e. tag+index reconstruct
    the full PC with no aliasing between distinct instruction addresses.

    The table is implemented as a register-of-vectors structure (Vec of Vec of Bundle registers)
    instead of Mem/SyncReadMem, since every set needs two tag comparators evaluated in parallel
    on every lookup (associative search), which the standard single-port memory classes do not
    support directly.

    Each set keeps a 1-bit "most recently used way" pointer for its LRU replacement policy. Since
    there are only two ways, "least recently used" is simply "the other way".

Inputs:
    PC: A 32-bit program counter representing the address of the branch instruction being fetched or executed.
    update: A 1-bit signal indicating whether the BTB should be updated with new information.
    updatePC: A 32-bit program counter associated with the branch instruction being updated.
    updateTarget: A 32-bit branch target address to be stored in the BTB.
    mispredicted: A 1-bit signal indicating whether the prediction turned out to be incorrect during execution (used to update the predictor).

Outputs:
    valid: A 1-bit signal indicating whether the BTB has a valid prediction for the provided program counter.
    target: A 32-bit signal representing the predicted branch target address when a valid prediction exists.
    predictTaken: A 1-bit signal indicating whether the branch is predicted to be taken or not.

*/

package core_tile

import chisel3._
import chisel3.util._

// -----------------------------------------
// BTB Entry
// -----------------------------------------

class BTBEntry extends Bundle {
  val valid  = Bool()
  val tag    = UInt(27.W)
  val target = UInt(32.W)
  val state  = PredictorState()
}

// -----------------------------------------
// Branch Target Buffer
// -----------------------------------------

class BTB extends Module {
  val io = IO(new Bundle {
    val PC           = Input(UInt(32.W))
    val update        = Input(Bool())
    val updatePC       = Input(UInt(32.W))
    val updateTarget   = Input(UInt(32.W))
    val mispredicted   = Input(Bool())

    val valid          = Output(Bool())
    val target         = Output(UInt(32.W))
    val predictTaken   = Output(Bool())
  })

  val NumSets = 8
  val NumWays = 2

  // Initial predictor state assigned to newly allocated entries. WeakTaken is chosen
  // because conditional branches in typical programs are dominated by backward
  // (loop-closing) branches, which are taken far more often than not; starting one
  // step into "taken" territory reaches the correct prediction faster for loops
  // than starting at strongNotTaken, while still flipping after a single misprediction
  // if the branch turns out to be a forward/rarely-taken one (cf. Task 6.1 answers
  // in IMPLEMENTATION.md).
  val InitialState = PredictorState.weakTaken

  def setIndex(pc: UInt): UInt = pc(4, 2)
  def tagBits(pc: UInt): UInt  = pc(31, 5)

  // 8 sets x 2 ways of BTB entries, reset to all-invalid
  val table = RegInit(VecInit(Seq.fill(NumSets)(VecInit(Seq.fill(NumWays)(0.U.asTypeOf(new BTBEntry))))))

  // 1-bit "most recently used way" pointer per set, used for LRU eviction
  val mru = RegInit(VecInit(Seq.fill(NumSets)(false.B))) // false = way0 MRU, true = way1 MRU

  val predictor = Module(new BranchPredictor)

  // ------------------------------------------------------------------
  // Lookup path (combinational): driven by the fetch PC every cycle
  // ------------------------------------------------------------------

  val lookupIdx = setIndex(io.PC)
  val lookupTag = tagBits(io.PC)
  val lookupSet = table(lookupIdx)

  val lookupHitWay0 = lookupSet(0).valid && (lookupSet(0).tag === lookupTag)
  val lookupHitWay1 = lookupSet(1).valid && (lookupSet(1).tag === lookupTag)

  io.valid        := lookupHitWay0 || lookupHitWay1
  io.target       := Mux(lookupHitWay0, lookupSet(0).target, lookupSet(1).target)
  io.predictTaken := Mux(lookupHitWay0,
                          PredictorState.isTaken(lookupSet(0).state),
                          PredictorState.isTaken(lookupSet(1).state))

  // ------------------------------------------------------------------
  // Update path (synchronous): driven by the EX stage once the actual
  // branch outcome and target are known
  // ------------------------------------------------------------------

  val updIdx = setIndex(io.updatePC)
  val updTag = tagBits(io.updatePC)
  val updSet = table(updIdx)

  val updHitWay0 = updSet(0).valid && (updSet(0).tag === updTag)
  val updHitWay1 = updSet(1).valid && (updSet(1).tag === updTag)
  val updHit     = updHitWay0 || updHitWay1

  // Reconstruct the actual outcome of the branch from the prediction that was
  // handed out for it (the state currently stored for this entry) and whether
  // that prediction turned out to be wrong. This lets the BTB derive the outcome
  // needed to drive the saturating counter from the single "mispredicted" input
  // signal specified by the interface, without having to pipe a separate
  // "actually taken" signal in from the core.
  val predictedTakenAtIssue = Mux(updHitWay0, PredictorState.isTaken(updSet(0).state),
                              Mux(updHitWay1, PredictorState.isTaken(updSet(1).state),
                                  false.B)) // no entry existed yet -> IF defaulted to "not taken"
  val actualTaken = predictedTakenAtIssue ^ io.mispredicted

  // Way selected to receive the write: the hit way on an update, otherwise a free
  // (invalid) way if one exists, otherwise the LRU way of the two.
  val allocateWay1 = Mux(updHit, updHitWay1,
                      Mux(!updSet(0).valid, false.B,
                      Mux(!updSet(1).valid, true.B,
                          !mru(updIdx)))) // both ways in use -> evict the LRU (non-MRU) way

  predictor.io.currentState := Mux(allocateWay1, updSet(1).state, updSet(0).state)
  predictor.io.taken        := actualTaken

  when(io.update) {
    val newState = Mux(updHit, predictor.io.nextState, InitialState)

    when(allocateWay1) {
      table(updIdx)(1).valid  := true.B
      table(updIdx)(1).tag    := updTag
      table(updIdx)(1).target := io.updateTarget
      table(updIdx)(1).state  := newState
    }.otherwise {
      table(updIdx)(0).valid  := true.B
      table(updIdx)(0).tag    := updTag
      table(updIdx)(0).target := io.updateTarget
      table(updIdx)(0).state  := newState
    }

    mru(updIdx) := allocateWay1
  }
}
