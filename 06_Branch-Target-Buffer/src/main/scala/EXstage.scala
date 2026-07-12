// ADS I Class Project
// Pipelined RISC-V Core - EX Stage
//
// Chair of Electronic Design Automation, RPTU in Kaiserslautern
// File created on 01/09/2026 by Tobias Jauch (@tojauch)

/*
Instruction Execute (EX) Stage: ALU operations and exception detection

Instantiated Modules:
    ALU: Integrate your module from Assignment02 for arithmetic/logical operations

ALU Interface:
    alu.io.operandA: first operand input
    alu.io.operandB: second operand input
    alu.io.operation: operation code controlling ALU function
    alu.io.aluResult: computation result output

Internal Signals:
    Map uopc codes to ALUOp values

Functionality:
    Map instruction uop to ALU operation code
    Pass operands to ALU
    Output results to pipeline
    Resolve the actual outcome/target of branches and jumps and compare it against
    the prediction that was made for them in IF (predictTaken/predictTarget, piped
    in through the IF/ID barriers) to determine whether the pipeline must be flushed
    and the BTB must be updated (cf. Figure 1 of Assignment 6: EX drives
    update/updatePC/updateTarget/mispredicted into the BTB)

Branch/Jump handling:
    Conditional branches (beq, bne, blt, bge, bltu, bgeu): only flush when the
    prediction made in IF turns out to be wrong (wrong direction, or taken with
    a stale/wrong predicted target); the BTB is updated on every conditional
    branch so it always reflects the latest observed behaviour of that branch.
    Unconditional jumps (jal, jalr): always taken and never looked up in / written
    to the BTB (cf. spec: "handled as taken branches without consulting the BTB"),
    so they always flush exactly like in the Assignment 5 static scheme.

Outputs:
    aluResult: computation result from ALU
    exception: pass exception flag
    branchTarget: corrected PC to redirect IF to on a flush (taken-target if taken, else fall-through)
    flush: control signal to flush pipeline on mispredicted branches/taken jumps
    btbUpdate/btbUpdatePC/btbUpdateTarget/btbMispredicted: update interface towards the BTB
*/

package core_tile

import chisel3._
import chisel3.util._
import Assignment02.{ALU, ALUOp}
import uopc._

// -----------------------------------------
// Execute Stage
// -----------------------------------------

class EX extends Module {
  val io = IO(new Bundle {
    val uop         = Input(uopc())
    val operandA    = Input(UInt(32.W))
    val operandB    = Input(UInt(32.W))
    val rd          = Input(UInt(5.W))
    val rs1         = Input(UInt(5.W))
    val rs2         = Input(UInt(5.W))
    val pc          = Input(UInt(32.W))
    val XcptInvalid = Input(Bool())

    // Prediction that was made for this instruction back in IF (piped through IF/ID barriers)
    val predictTaken  = Input(Bool())
    val predictTarget = Input(UInt(32.W))

    val aluResult_MEM = Input(UInt(32.W))
    val rd_MEM        = Input(UInt(5.W))
    val wrEn_MEM      = Input(Bool())
    val aluResult_WB  = Input(UInt(32.W))
    val rd_WB         = Input(UInt(5.W))
    val wrEn_WB       = Input(Bool())

    val aluResult      = Output(UInt(32.W))
    val rdOut          = Output(UInt(5.W))
    val exception      = Output(Bool())
    val flush          = Output(Bool())
    val branchTarget   = Output(UInt(32.W))

    // BTB update interface (cf. Figure 1 of Assignment 6)
    val btbUpdate       = Output(Bool())
    val btbUpdatePC      = Output(UInt(32.W))
    val btbUpdateTarget  = Output(UInt(32.W))
    val btbMispredicted  = Output(Bool())
  })

  val alu = Module(new ALU)

  val forwardA = Wire(UInt(2.W))
  val forwardB = Wire(UInt(2.W))

  forwardA := Mux(io.wrEn_MEM && (io.rs1 === io.rd_MEM) && (io.rs1 =/= 0.U), 1.U,
                  Mux(io.wrEn_WB && (io.rs1 === io.rd_WB) && (io.rs1 =/= 0.U), 2.U, 0.U))

  forwardB := Mux(io.wrEn_MEM && (io.rs2 === io.rd_MEM) && (io.rs2 =/= 0.U), 1.U,
                  Mux(io.wrEn_WB && (io.rs2 === io.rd_WB) && (io.rs2 =/= 0.U), 2.U, 0.U))

  val aluOperandA = Mux(forwardA === 1.U, io.aluResult_MEM,
                         Mux(forwardA === 2.U, io.aluResult_WB,
                             io.operandA))

  val aluOperandB = Mux(forwardB === 1.U, io.aluResult_MEM,
                         Mux(forwardB === 2.U, io.aluResult_WB,
                             io.operandB))

  alu.io.operandA  := aluOperandA
  alu.io.operandB  := aluOperandB
  alu.io.operation := ALUOp.ADD

  switch(io.uop) {
    is(uopc.isADD)   { alu.io.operation := ALUOp.ADD  }
    is(uopc.isSUB)   { alu.io.operation := ALUOp.SUB  }
    is(uopc.isAND)   { alu.io.operation := ALUOp.AND  }
    is(uopc.isOR)    { alu.io.operation := ALUOp.OR   }
    is(uopc.isXOR)   { alu.io.operation := ALUOp.XOR  }
    is(uopc.isSLL)   { alu.io.operation := ALUOp.SLL  }
    is(uopc.isSRL)   { alu.io.operation := ALUOp.SRL  }
    is(uopc.isSRA)   { alu.io.operation := ALUOp.SRA  }
    is(uopc.isSLT)   { alu.io.operation := ALUOp.SLT  }
    is(uopc.isSLTU)  { alu.io.operation := ALUOp.SLTU }
    is(uopc.isADDI)  { alu.io.operation := ALUOp.ADD  }
    is(uopc.isANDI)  { alu.io.operation := ALUOp.AND  }
    is(uopc.isORI)   { alu.io.operation := ALUOp.OR   }
    is(uopc.isXORI)  { alu.io.operation := ALUOp.XOR  }
    is(uopc.isSLLI)  { alu.io.operation := ALUOp.SLL  }
    is(uopc.isSRLI)  { alu.io.operation := ALUOp.SRL  }
    is(uopc.isSRAI)  { alu.io.operation := ALUOp.SRA  }
    is(uopc.isSLTI)  { alu.io.operation := ALUOp.SLT  }
    is(uopc.isSLTIU) { alu.io.operation := ALUOp.SLTU }
    is(uopc.isNOP)   { alu.io.operation := ALUOp.ADD  }
  }

  val isEqual   = (aluOperandA === aluOperandB)
  val isLess    = aluOperandA.asSInt < aluOperandB.asSInt
  val isLessU   = aluOperandA < aluOperandB

  val beqTaken  = isEqual
  val bneTaken  = !isEqual
  val bltTaken  = isLess
  val bgeTaken  = !isLess
  val bltuTaken = isLessU
  val bgeuTaken = !isLessU

  val branchTaken = Wire(Bool())
  val branchTarget = Wire(UInt(32.W))

  branchTaken := false.B
  branchTarget := io.pc + 4.U

  val isCondBranch = io.uop === uopc.isBEQ  || io.uop === uopc.isBNE || io.uop === uopc.isBLT ||
                      io.uop === uopc.isBGE || io.uop === uopc.isBLTU || io.uop === uopc.isBGEU
  val isJump       = io.uop === uopc.isJAL || io.uop === uopc.isJALR

  when(io.uop === uopc.isBEQ) {
    branchTaken := beqTaken
    branchTarget := io.pc + io.operandB
  }.elsewhen(io.uop === uopc.isBNE) {
    branchTaken := bneTaken
    branchTarget := io.pc + io.operandB
  }.elsewhen(io.uop === uopc.isBLT) {
    branchTaken := bltTaken
    branchTarget := io.pc + io.operandB
  }.elsewhen(io.uop === uopc.isBGE) {
    branchTaken := bgeTaken
    branchTarget := io.pc + io.operandB
  }.elsewhen(io.uop === uopc.isBLTU) {
    branchTaken := bltuTaken
    branchTarget := io.pc + io.operandB
  }.elsewhen(io.uop === uopc.isBGEU) {
    branchTaken := bgeuTaken
    branchTarget := io.pc + io.operandB
  }.elsewhen(io.uop === uopc.isJAL) {
    branchTaken := true.B
    branchTarget := io.pc + io.operandB
  }.elsewhen(io.uop === uopc.isJALR) {
    branchTaken := true.B
    branchTarget := (aluOperandA + io.operandB) & ~1.U
  }


  // PC contains the current instruction address, not the predicted one.
  val resolvedPC = Mux(branchTaken, branchTarget, io.pc + 4.U)

  // Here we already know whether the branch is taken or not, - based on ALU result.
  // 1. Preduction was wrong
  // 2. Prediction was right, but the predicted target was wrong (stale BTB entry)
  val condMispredicted = (
      io.predictTaken =/= branchTaken) ||
      (branchTaken && io.predictTaken && (io.predictTarget =/= branchTarget))

  // For unconditional jumps, we always flush, since the BTB is never updated
  // For conditional branches, we only flush if the prediction was wrong
  val mispredictedOrJump = Mux(isCondBranch, condMispredicted, isJump)


  io.aluResult := Mux(io.uop === uopc.isJAL || io.uop === uopc.isJALR, io.pc + 4.U, alu.io.aluResult)
  io.rdOut       := io.rd
  io.exception   := io.XcptInvalid
  io.flush       := mispredictedOrJump
  io.branchTarget := resolvedPC

  io.btbUpdate      := isCondBranch
  io.btbUpdatePC     := io.pc
  io.btbUpdateTarget := branchTarget
  io.btbMispredicted := condMispredicted
}
