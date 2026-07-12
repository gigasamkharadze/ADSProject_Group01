// ADS I Class Project
// Pipelined RISC-V Core - IF Barrier
//
// Chair of Electronic Design Automation, RPTU in Kaiserslautern
// File created on 01/09/2026 by Tobias Jauch (@tojauch)

/*
IF-Barrier: pipeline register between Fetch and Decode stages

Functionality:
    On flush: insert NOP (addi x0,x0,0) to squash wrong-path instruction
    Otherwise: pass instruction, PC and the BTB prediction that was made for this
    fetch through to ID, so that EX can later tell whether the prediction was correct
*/

package core_tile

import chisel3._

class IFBarrier extends Module {
  val io = IO(new Bundle {
    val inInstr           = Input(UInt(32.W))
    val inPC              = Input(UInt(32.W))
    val inPredictTaken    = Input(Bool())
    val inPredictTarget   = Input(UInt(32.W))
    val flush              = Input(Bool())

    val outInstr           = Output(UInt(32.W))
    val outPC              = Output(UInt(32.W))
    val outPredictTaken    = Output(Bool())
    val outPredictTarget   = Output(UInt(32.W))
  })

  val instrReg         = RegInit(0.U(32.W))
  val pcReg            = RegInit(0.U(32.W))
  val predictTakenReg  = RegInit(false.B)
  val predictTargetReg = RegInit(0.U(32.W))

  when(io.flush) {
    instrReg         := "h00000013".U   // NOP: addi x0, x0, 0
    pcReg            := 0.U
    predictTakenReg  := false.B
    predictTargetReg := 0.U
  }.otherwise {
    instrReg         := io.inInstr
    pcReg            := io.inPC
    predictTakenReg  := io.inPredictTaken
    predictTargetReg := io.inPredictTarget
  }

  io.outInstr         := instrReg
  io.outPC            := pcReg
  io.outPredictTaken  := predictTakenReg
  io.outPredictTarget := predictTargetReg
}
