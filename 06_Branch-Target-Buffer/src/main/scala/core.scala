// ADS I Class Project
// Pipelined RISC-V Core
//
// Chair of Electronic Design Automation, RPTU in Kaiserslautern
// File created on 01/15/2023 by Tobias Jauch (@tojauch)

/*
Assignment 6 extends the Assignment 5 core with a Branch Target Buffer (BTB).

useBTB is a module-construction parameter (not a runtime signal) that makes it
possible to switch between the two branch resolution schemes without touching
IF/EX at all (cf. Task 6.3, "structure your core in a modular way"):

    useBTB = true  (default): the BTB is instantiated and wired up. IF queries
        it every cycle and, on a valid taken prediction, redirects fetch to the
        predicted target immediately; EX drives the update interface once the
        real outcome is known. Conditional branches only cost a pipeline flush
        on a misprediction.
    useBTB = false: the BTB is left unconnected and IF's prediction inputs are
        tied to "no prediction available" (btbValid = false), so IF always
        falls back to the Assignment 5 static "assume not taken" behaviour and
        EX always flushes on every taken branch/jump, exactly like Assignment 5.

Because a branch that is executed for the very first time has no BTB entry yet,
useBTB = true behaves identically to useBTB = false for any branch that never
repeats -- the BTB can only improve behaviour (never change it) for programs
without loops/repeated branches, which is why all Assignment 5 regression tests
keep passing unmodified under the default useBTB = true configuration.
*/

package core_tile

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFile
import Assignment02.{ALU, ALUOp}
import uopc._


class PipelinedRV32Icore (BinaryFile: String, useBTB: Boolean = true) extends Module {
  val io = IO(new Bundle {
    val check_res = Output(UInt(32.W))
    val exception = Output(Bool())
  })

  val ifStage    = Module(new IF(BinaryFile))
  val ifBarrier  = Module(new IFBarrier)
  val idStage    = Module(new ID)
  val idBarrier  = Module(new IDBarrier)
  val exStage    = Module(new EX)
  val exBarrier  = Module(new EXBarrier)
  val memStage   = Module(new MEM)
  val memBarrier = Module(new MEMBarrier)
  val wbStage    = Module(new WB)
  val wbBarrier  = Module(new WBBarrier)
  val regfile    = Module(new regFile)

  // ── Branch Target Buffer ────────────────────────────────────────────────────
  if (useBTB) {
    val btb = Module(new BTB)

    // Lookup, driven by the current fetch PC every cycle
    btb.io.PC := ifStage.io.pc

    ifStage.io.btbValid        := btb.io.valid
    ifStage.io.btbTarget       := btb.io.target
    ifStage.io.btbPredictTaken := btb.io.predictTaken

    // Update, driven by EX once the branch has actually been resolved
    btb.io.update       := exStage.io.btbUpdate
    btb.io.updatePC      := exStage.io.btbUpdatePC
    btb.io.updateTarget  := exStage.io.btbUpdateTarget
    btb.io.mispredicted  := exStage.io.btbMispredicted
  } else {
    // Static scheme (Assignment 5): no BTB, IF never gets a prediction
    ifStage.io.btbValid        := false.B
    ifStage.io.btbTarget       := 0.U
    ifStage.io.btbPredictTaken := false.B
  }

  // ── IF Stage ──────────────────────────────────────────────────────────────
  ifStage.io.flush      := exStage.io.flush
  ifStage.io.resolvedPC := exStage.io.branchTarget

  // IF → IFBarrier (flush injects NOP to squash WP2)
  ifBarrier.io.inInstr         := ifStage.io.instr
  ifBarrier.io.inPC            := ifStage.io.pc
  ifBarrier.io.inPredictTaken  := ifStage.io.btbValid && ifStage.io.btbPredictTaken
  ifBarrier.io.inPredictTarget := ifStage.io.btbTarget
  ifBarrier.io.flush           := exStage.io.flush

  // ── ID Stage ──────────────────────────────────────────────────────────────
  idStage.io.instr         := ifBarrier.io.outInstr
  idStage.io.pc            := ifBarrier.io.outPC
  idStage.io.predictTaken  := ifBarrier.io.outPredictTaken
  idStage.io.predictTarget := ifBarrier.io.outPredictTarget

  regfile.io.req_1         := idStage.io.regFileReq_A
  idStage.io.regFileResp_A := regfile.io.resp_1
  regfile.io.req_2         := idStage.io.regFileReq_B
  idStage.io.regFileResp_B := regfile.io.resp_2

  // ID → IDBarrier (flush zeroes out registers to squash WP1)
  idBarrier.io.inUOP           := idStage.io.uop
  idBarrier.io.inRD            := idStage.io.rd
  idBarrier.io.inRS1           := idStage.io.rs1
  idBarrier.io.inRS2           := idStage.io.rs2
  idBarrier.io.inOperandA      := idStage.io.operandA
  idBarrier.io.inOperandB      := idStage.io.operandB
  idBarrier.io.inPC            := idStage.io.pcOut
  idBarrier.io.inPredictTaken  := idStage.io.predictTakenOut
  idBarrier.io.inPredictTarget := idStage.io.predictTargetOut
  idBarrier.io.inXcptInvalid   := idStage.io.XcptInvalid
  idBarrier.io.inwr_en         := idStage.io.wr_en
  idBarrier.io.flush           := exStage.io.flush

  // ── EX Stage ──────────────────────────────────────────────────────────────
  exStage.io.uop           := idBarrier.io.outUOP
  exStage.io.operandA      := idBarrier.io.outOperandA
  exStage.io.operandB      := idBarrier.io.outOperandB
  exStage.io.rd            := idBarrier.io.outRD
  exStage.io.rs1           := idBarrier.io.outRS1
  exStage.io.rs2           := idBarrier.io.outRS2
  exStage.io.pc            := idBarrier.io.outPC
  exStage.io.predictTaken  := idBarrier.io.outPredictTaken
  exStage.io.predictTarget := idBarrier.io.outPredictTarget
  exStage.io.XcptInvalid   := idBarrier.io.outXcptInvalid

  // Forwarding from EX-MEM and MEM-WB barriers
  exStage.io.aluResult_MEM := exBarrier.io.outAluResult
  exStage.io.rd_MEM        := exBarrier.io.outRD
  exStage.io.wrEn_MEM      := exBarrier.io.outWriteEn
  exStage.io.aluResult_WB  := memBarrier.io.outAluResult
  exStage.io.rd_WB         := memBarrier.io.outRD
  exStage.io.wrEn_WB       := memBarrier.io.outWriteEn

  // EX → EXBarrier
  exBarrier.io.inAluResult   := exStage.io.aluResult
  exBarrier.io.inRD          := exStage.io.rdOut
  exBarrier.io.inXcptInvalid := exStage.io.exception
  exBarrier.io.inwr_en       := idBarrier.io.outwr_en

  // ── MEM Stage ─────────────────────────────────────────────────────────────
  memBarrier.io.inAluResult := exBarrier.io.outAluResult
  memBarrier.io.inRD        := exBarrier.io.outRD
  memBarrier.io.inException := exBarrier.io.outXcptInvalid
  memBarrier.io.inwr_en     := exBarrier.io.outWriteEn

  // ── WB Stage ──────────────────────────────────────────────────────────────
  wbStage.io.aluResult := memBarrier.io.outAluResult
  wbStage.io.rd        := memBarrier.io.outRD
  wbStage.io.writeEn   := memBarrier.io.outWriteEn

  regfile.io.req_3 := wbStage.io.regFileReq

  wbBarrier.io.inCheckRes    := wbStage.io.check_res
  wbBarrier.io.inXcptInvalid := memBarrier.io.outException

  io.check_res := wbBarrier.io.outCheckRes
  io.exception := wbBarrier.io.outXcptInvalid
}
