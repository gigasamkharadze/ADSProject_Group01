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

class BTBEntry extends Bundle {
  val valid  = Bool()
  val tag    = UInt(27.W)
  val target = UInt(32.W)
  val state  = PredictorState()
}

class BTB extends Module {
  val io = IO(new Bundle {
    val PC           = Input(UInt(32.W)) // for btb lookup
    val update        = Input(Bool()) // from exec stage, correct prediction
    val updatePC       = Input(UInt(32.W)) // For BTB update
    val updateTarget   = Input(UInt(32.W)) // For BTB update
    val mispredicted   = Input(Bool())

    val valid          = Output(Bool())
    val target         = Output(UInt(32.W))
    val predictTaken   = Output(Bool())
  })

  val NumSets = 8
  val NumWays = 2
  val predictor = Module(new BranchPredictor)

  // weakTaken because of loops
  val InitialState = PredictorState.weakTaken

  // specifications of the address construction
  def setIndex(pc: UInt): UInt = pc(4, 2)
  def tagBits(pc: UInt): UInt  = pc(31, 5)

  // default BTB table constructed; initially, all entries are invalid.
  val table = RegInit(
      VecInit(Seq.fill(NumSets)
                (VecInit(Seq.fill(NumWays)
                  (0.U.asTypeOf(new BTBEntry)))))
  )

  // In the beginning, no entry is read (least recently used)
  val mru = RegInit(VecInit(Seq.fill(NumSets)(false.B)))

  val lookupIdx = setIndex(io.PC)
  val lookupTag = tagBits(io.PC)
  val lookupSet = table(lookupIdx)

  val lookupHitWay0 = lookupSet(0).valid && (lookupSet(0).tag === lookupTag)
  val lookupHitWay1 = lookupSet(1).valid && (lookupSet(1).tag === lookupTag)

  io.valid        := lookupHitWay0 || lookupHitWay1

  // if not valid, ignored; else taken from either hit0 or hit1
  io.target       := Mux(lookupHitWay0, lookupSet(0).target, lookupSet(1).target)
  io.predictTaken := Mux(lookupHitWay0,
                          PredictorState.isTaken(lookupSet(0).state),
                          PredictorState.isTaken(lookupSet(1).state))

  val updIdx = setIndex(io.updatePC)
  val updTag = tagBits(io.updatePC)
  val updSet = table(updIdx)

  // The branch already has a BTB entry.
  val updHitWay0 = updSet(0).valid && (updSet(0).tag === updTag)
  val updHitWay1 = updSet(1).valid && (updSet(1).tag === updTag)
  val updHit     = updHitWay0 || updHitWay1

  // use state of the way that hits the correct PC
  val predictedTakenAtIssue = Mux(updHitWay0, PredictorState.isTaken(updSet(0).state),
                              Mux(updHitWay1, PredictorState.isTaken(updSet(1).state),
                                false.B))

  // If prediction was correct, actual outcome equals prediction.
  // If prediction was wrong, actual outcome is the opposite.
  val actualTaken = predictedTakenAtIssue ^ io.mispredicted

  val allocateWay1 = Mux(
    updHit,
    updHitWay1, // if hit, allocate in the way that hits the correct PC

    Mux( // if not hit, allocate invalid way
        !updSet(0).valid,
        false.B,
        Mux(

          !updSet(1).valid,
          true.B,
          !mru(updIdx) // if both ways are valid, allocate the LRU way
        )
    )
  )

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
