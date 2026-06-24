// ADS I Class Project
// Pipelined RISC-V Core - Forwarding Unit
//
// Chair of Electronic Design Automation, RPTU in Kaiserslautern
// File created on 05/09/2026 by Tobias Jauch (@tojauch)

/*
Forwarding Unit: resolves data hazards by forwarding results from later pipeline stages to the EX stage

Functionality (cf. slide 6-24ff of the lecture slides):
    Detects data hazards by comparing source registers (rs1, rs2) with destination registers in EX/MEM and MEM/WB barriers.
    Generates control signals for the multiplexers in the EX stage to select the correct data source for the ALU inputs
    Handles cases where multiple hazards occur simultaneously (e.g., forwarding from both EX and MEM stages)
    Distinguishes between R-type instructions (2 operands) and I-type instructions (1 operand)

Inputs:
    rs1: first source register from ID/EX barrier
    rs2: second source register from ID/EX barrier
    uop: micro-operation code to distinguish R-type from I-type instructions
    exBarrier_rd: destination register in EX/MEM barrier (1-cycle latency)
    memBarrier_rd: destination register in MEM/WB barrier (2-cycle latency)

Outputs:
    fwd_A_sel: control signal for selecting source of operand A (0=original, 1=EX, 2=MEM)
    fwd_B_sel: control signal for selecting source of operand B (0=original, 1=EX, 2=MEM)

*/

package core_tile

import chisel3._
import chisel3.util._
import uopc._

// -----------------------------------------
// Forwarding Unit
// -----------------------------------------

class ForwardingUnit extends Module {
  val io = IO(new Bundle {
    val rs1           = Input(UInt(5.W))
    val rs2           = Input(UInt(5.W))
    val uop           = Input(uopc())
    val exBarrier_rd  = Input(UInt(5.W))
    val memBarrier_rd = Input(UInt(5.W))
    // 0 = no forwarding, 1 = EX hazard (EXBarrier), 2 = MEM hazard (MEMBarrier)
    val fwd_A_sel     = Output(UInt(2.W))
    val fwd_B_sel     = Output(UInt(2.W))
  })

  // R-type instructions use both rs1 and rs2; I-type instructions use only rs1
  val isRType = (io.uop === uopc.isADD)  || (io.uop === uopc.isSUB)  ||
                (io.uop === uopc.isAND)  || (io.uop === uopc.isOR)   ||
                (io.uop === uopc.isXOR)  || (io.uop === uopc.isSLL)  ||
                (io.uop === uopc.isSRL)  || (io.uop === uopc.isSRA)  ||
                (io.uop === uopc.isSLT)  || (io.uop === uopc.isSLTU)

  // EX hazard: producer one cycle ahead (in EXBarrier) matches consumer rs1/rs2
  val exHazard_A  = (io.exBarrier_rd =/= 0.U) && (io.exBarrier_rd === io.rs1)
  val exHazard_B  = isRType && (io.exBarrier_rd =/= 0.U) && (io.exBarrier_rd === io.rs2)

  // MEM hazard: producer two cycles ahead (in MEMBarrier), only if no EX hazard
  val memHazard_A = !exHazard_A && (io.memBarrier_rd =/= 0.U) && (io.memBarrier_rd === io.rs1)
  val memHazard_B = isRType && !exHazard_B && (io.memBarrier_rd =/= 0.U) && (io.memBarrier_rd === io.rs2)

  io.fwd_A_sel := Mux(exHazard_A, 1.U, Mux(memHazard_A, 2.U, 0.U))
  io.fwd_B_sel := Mux(exHazard_B, 1.U, Mux(memHazard_B, 2.U, 0.U))
}
