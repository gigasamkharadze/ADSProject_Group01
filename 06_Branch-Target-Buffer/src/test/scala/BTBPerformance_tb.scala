// ADS I Class Project
// BTB Performance Evaluation (Task 6.4)
//
// Chair of Electronic Design Automation, RPTU in Kaiserslautern
// File created on 07/12/2026

/*
End-to-end confirmation that the BTB-enabled core (useBTB = true) completes a
loop with a repeated backward branch in fewer clock cycles than the static
"assume not taken" core (useBTB = false), by directly instantiating
core_tile.PipelinedRV32Icore with each configuration and counting cycles
until the loop's exit marker instruction (addi x5, x0, 224) commits.

Uses the same BinaryFile_btb_loop benchmark analyzed in BTB_tb.scala's
prediction-accuracy test: a 5-iteration countdown loop whose closing branch
is taken 4 times and not-taken once (loop exit). The static core mispredicts
(and flushes) on every one of the 4 taken iterations; the BTB-enabled core
only mispredicts on the first (cold-miss) and last (loop-exit) iterations,
so it must reach the same final state in fewer cycles.
*/

package core_tile

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class BTBPerformanceTest extends AnyFlatSpec with ChiselScalatestTester {

  def cyclesUntilLoopExit(useBTB: Boolean): Int = {
    var cycles = 0
    test(new PipelinedRV32Icore("src/test/programs/BinaryFile_btb_loop", useBTB)) { dut =>
      dut.clock.setTimeout(0)
      // The marker instruction (addi x5, x0, 224) only commits once the loop
      // has exited; cap the search so a broken flush/redirect can't hang the test.
      while (dut.io.check_res.peek().litValue != 224 && cycles < 200) {
        dut.clock.step(1)
        cycles += 1
      }
    }
    cycles
  }

  "the BTB-enabled core" should "complete a repeated-branch loop in fewer cycles than the static core" in {
    val cyclesWithBTB    = cyclesUntilLoopExit(useBTB = true)
    val cyclesStatic     = cyclesUntilLoopExit(useBTB = false)

    assert(cyclesWithBTB < 200, "BTB-enabled core never reached the loop exit marker")
    assert(cyclesStatic  < 200, "Static core never reached the loop exit marker")
    assert(cyclesWithBTB < cyclesStatic)
  }
}
