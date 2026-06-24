// ADS I Class Project
// Pipelined RISC-V Core
//
// Chair of Electronic Design Automation, RPTU in Kaiserslautern
// File created on 01/15/2023 by Tobias Jauch (@tojauch)

package PipelinedRV32I_Tester

import chisel3._
import chiseltest._
import PipelinedRV32I._
import org.scalatest.flatspec.AnyFlatSpec

class PipelinedRISCV32ITest extends AnyFlatSpec with ChiselScalatestTester {

"RV32I_BasicTester" should "work" in {
    test(new PipelinedRV32I("src/test/programs/BinaryFile_pipelined")).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>

      dut.clock.setTimeout(0)
      dut.clock.step(5)
      dut.io.result.expect(0.U)     // ADDI x0, x0, 0
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(4.U)     // ADDI x1, x0, 4
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(5.U)     // ADDI x2, x0, 5
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(9.U)     // ADD x3, x1, x2
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(2047.U)  // ADDI x4, x0, 2047
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(16.U)    // ADDI x5, x0, 16
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(2031.U)  // SUB x6, x4, x5
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(2022.U)  // XOR x7, x6, x3
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(2047.U)  // OR x8, x6, x5
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(0.U)     // AND x9, x6, x5
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(64704.U) // SLL x10, x7, x2
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(63.U)    // SRL x11, x7, x2
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(63.U)    // SRA x12, x7, x2
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(0.U)     // SLT x13, x4, x4
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(0.U)     // SLT x13, x4, x5
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(1.U)     // SLT x13, x5, x4
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(0.U)     // SLTU x13, x4, x4
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(0.U)     // SLTU x13, x4, x5
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(1.U)     // SLTU x13, x5, x4
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
    }
  }
}

class ForwardingTest extends AnyFlatSpec with ChiselScalatestTester {

  "Forwarding_Tester" should "work" in {
    test(new PipelinedRV32I("src/test/programs/BinaryFile_forwarding")).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      dut.clock.setTimeout(0)

      // Pipeline fill: 5 cycles before first result appears
      dut.clock.step(5)
      dut.io.result.expect(0.U)             // NOP
      dut.io.exception.expect(false.B)

      // Register setup (all source from x0, no hazards)
      dut.clock.step(1)
      dut.io.result.expect(4.U)             // ADDI x1, x0, 4
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(5.U)             // ADDI x2, x0, 5
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(10.U)            // ADDI x3, x0, 10
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(20.U)            // ADDI x4, x0, 20
      dut.io.exception.expect(false.B)

      // EX hazard (distance=1): x5 in EXBarrier when ADD x6 executes
      dut.clock.step(1)
      dut.io.result.expect(100.U)           // ADDI x5, x0, 100
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(104.U)           // ADD x6, x5, x1  (EX fwd: x5=100)
      dut.io.exception.expect(false.B)

      // MEM hazard (distance=2): x7 in MEMBarrier when ADD x8 executes
      dut.clock.step(1)
      dut.io.result.expect(50.U)            // ADDI x7, x0, 50
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(1.U)             // ADDI x9, x0, 1  (filler)
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(55.U)            // ADD x8, x7, x2  (MEM fwd: x7=50)
      dut.io.exception.expect(false.B)

      // Regfile forwarding (distance=3): WB writes x10 in same cycle ID reads it
      dut.clock.step(1)
      dut.io.result.expect(200.U)           // ADDI x10, x0, 200
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(0.U)             // NOP
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(0.U)             // NOP
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(210.U)           // ADD x11, x10, x3  (regfile fwd: x10=200)
      dut.io.exception.expect(false.B)

      // Double hazard: rs1=x12 from MEMBarrier (dist=2), rs2=x13 from EXBarrier (dist=1)
      dut.clock.step(1)
      dut.io.result.expect(30.U)            // ADDI x12, x0, 30
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(40.U)            // ADDI x13, x0, 40
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(70.U)            // ADD x14, x12, x13  (MEM fwd A, EX fwd B)
      dut.io.exception.expect(false.B)

      // I-type with EX hazard on rs1 (opB must remain immediate, not forwarded)
      dut.clock.step(1)
      dut.io.result.expect(7.U)             // ADDI x15, x0, 7
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(10.U)            // ADDI x16, x15, 3  (EX fwd: rs1=x15=7, imm=3)
      dut.io.exception.expect(false.B)

      // Chain: three consecutive dependent instructions
      dut.clock.step(1)
      dut.io.result.expect(5.U)             // ADDI x17, x0, 5
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(9.U)             // ADD x18, x17, x1  (EX fwd: x17=5)
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect(14.U)            // ADD x19, x18, x2  (EX fwd: x18=9)
      dut.io.exception.expect(false.B)

      // SUB with EX hazard
      dut.clock.step(1)
      dut.io.result.expect(9.U)             // ADD x20, x1, x2   (no hazard: x1, x2 old)
      dut.io.exception.expect(false.B)
      dut.clock.step(1)
      dut.io.result.expect("hFFFFFFFF".U)   // SUB x21, x20, x3  (EX fwd: x20=9), 9-10=-1
      dut.io.exception.expect(false.B)

      dut.clock.step(1)
    }
  }
}
