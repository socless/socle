package probefix

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

/** A module that reads its config the way std-vta-dev does: a system property
  * from the test module's `forkArgs` and a file under `$MILL_WORKSPACE_ROOT/config`
  * (relative to the working directory when that variable is unset or empty).
  * Elaboration fails, naming what is missing, without either. */
class Configured extends Module {
  private val prop = System.getProperty("fixture.config")
  require(prop != null, "missing -Dfixture.config")
  private val file = sys.env.get("MILL_WORKSPACE_ROOT").filter(_.nonEmpty)
    .fold(new java.io.File("config/fixture.json"))(new java.io.File(_, "config/fixture.json")) // else cwd
  require(file.isFile, s"missing config file $file")
  val io = IO(new Bundle {
    val in  = Input(UInt(8.W))
    val out = Output(UInt(8.W))
  })
  io.out := io.in
}

class ConfigSpec extends AnyFunSuite with ChiselSim {
  test("configured module") {
    simulate(new Configured) { dut => dut.clock.step() }
  }
}
