// ADS I Class Project
// Pipelined RISC-V Core - IF Stage
//
// Chair of Electronic Design Automation, RPTU in Kaiserslautern
// File created on 01/09/2026 by Tobias Jauch (@tojauch)

/*
The Instruction Fetch (IF) stage is the first stage of the pipeline and handles instruction retrieval from memory.

Memory:
    IMem: instruction memory with 4096 32-bit unsigned integer entires, loaded from a binary file at compile time

Internal Registers:
    PC: 32-bit unsigned integer register, initialized to 0 holding the current program counter address

Internal Signals:
    none

Functionality:
    Fetch the instruction at the current PC (word-aligned addressing)
    Determine the next PC with the following priority (cf. Figure 1 of Assignment 6):
        1. flush from EX (misprediction / unconditional jump resolved) -> resolvedPC from EX
        2. BTB predicts a valid taken branch for the current PC -> BTB target (dynamic prediction)
        3. otherwise -> PC + 4 (static "assume not taken" fallback, identical to Assignment 5)
    The BTB inputs are wired up externally at the core level: when the core is built with
    useBTB = false, they are tied to constants (valid = false) so this stage transparently
    degrades to the Assignment 5 static prediction scheme without any changes to this file.

Parameters:
    BinaryFile: String - path to the binary file to load into instruction memory

Inputs:
    flush: from EX stage, asserted when the fetched instruction stream must be redirected
    resolvedPC: the corrected PC to redirect to when flush is asserted
    btbValid, btbTarget, btbPredictTaken: BTB lookup result for the current PC

Outputs:
    instr: send the fetched instruction to IF Barrier
    pc: current fetch PC (also used to query the BTB)
*/

package core_tile

import chisel3._
import chisel3.util.experimental.loadMemoryFromFile

// -----------------------------------------
// Fetch Stage
// -----------------------------------------

class IF (BinaryFile: String) extends Module {
  val io = IO(new Bundle {
    val instr            = Output(UInt(32.W))
    val pc               = Output(UInt(32.W))

    val flush            = Input(Bool())
    val resolvedPC       = Input(UInt(32.W))

    val btbValid         = Input(Bool())
    val btbTarget        = Input(UInt(32.W))
    val btbPredictTaken  = Input(Bool())
  })

  val IMem = Mem(4096, UInt(32.W))
  loadMemoryFromFile(IMem, BinaryFile)

  val PC = RegInit(0.U(32.W))

  io.instr := IMem(PC >> 2)
  io.pc    := PC

  // Update PC based on flush (misprediction / jump) and BTB dynamic prediction
  when(io.flush) {
    PC := io.resolvedPC
  }.elsewhen(io.btbValid && io.btbPredictTaken) {
    PC := io.btbTarget
  }.otherwise {
    PC := PC + 4.U
  }
}
