package socle.ir

import socle._

import socle.schema._
import socle.parse._
import socle.ir._
import socle.layout._
import socle.cli._

import utest._

/** Tests that IrWalk.portRole (via the full parse->design pipeline) correctly
  * classifies AXI4 ports as "master" or "slave" based on the port-level
  * `input`/`output` keyword in CHIRRTL, not on inner field-flip heuristics.
  *
  * Regression for GitHub issue #30: the old code looked at `flip r` to infer
  * direction, but that field is flipped in both master and slave bundles
  * (identical inner structure), so slaves were incorrectly labelled "master".
  */
object PortRoleTest extends TestSuite {

  // ── Helpers ───────────────────────────────────────────────────────────────

  private def readFixture(name: String): String = {
    val s = getClass.getResourceAsStream(s"/chirrtl/$name")
    require(s != null, s"missing classpath resource /chirrtl/$name")
    try scala.io.Source.fromInputStream(s, "UTF-8").mkString
    finally s.close()
  }

  /** Return a map from (moduleName -> portName) -> dir string for all ports
    * in the parsed design.
    */
  private def portDirs(chirrtlFile: String): Map[(String, String), String] = {
    val circ   = ChirrtlParser.parse(readFixture(chirrtlFile))
    val design = IrWalk.design(circ)
    (for {
      mod  <- design.modules
      port <- mod.ports
    } yield (mod.name, port.name) -> port.dir).toMap
  }

  // ── Inline synthetic CHIRRTL for focused unit tests ───────────────────────

  /** Build a minimal CHIRRTL circuit string with one master module and one
    * slave module, each with a single AXI4-shaped port.  The inner bundle
    * type is intentionally identical for both (same field-flip pattern), so
    * only the outer `input`/`output` keyword distinguishes them.
    */
  private val syntheticCHIRRTL: String = {
    val axi = "{ ar : { flip ready : UInt<1>, valid : UInt<1>, bits : UInt<32>}," +
              " aw : { flip ready : UInt<1>, valid : UInt<1>, bits : UInt<32>}," +
              " w  : { flip ready : UInt<1>, valid : UInt<1>, bits : UInt<32>}," +
              " flip r : { flip ready : UInt<1>, valid : UInt<1>, bits : UInt<32>}," +
              " flip b : { flip ready : UInt<1>, valid : UInt<1>, bits : UInt<32>}}"
    s"""FIRRTL version 4.0.0
circuit Top :
  module Master :
    output io : $axi

  module Slave :
    input io : $axi

  public module Top :
    inst m of Master
    inst s of Slave
"""
  }

  // ── Tests ─────────────────────────────────────────────────────────────────

  val tests = Tests {

    test("synthetic master io is 'master', slave io is 'slave'") {
      val circ   = ChirrtlParser.parse(syntheticCHIRRTL)
      val design = IrWalk.design(circ)
      val dirs   = (for {
        mod  <- design.modules
        port <- mod.ports
      } yield (mod.name, port.name) -> port.dir).toMap

      assert(dirs(("Master", "io")) == "master")
      assert(dirs(("Slave",  "io")) == "slave")
    }

    test("axisystem fixture: AXIMaster io is 'master'") {
      val dirs = portDirs("axisystem.fir")
      assert(dirs(("AXIMaster",   "io")) == "master")
      assert(dirs(("AXIMaster_1", "io")) == "master")
    }

    test("axisystem fixture: AXISlave io is 'slave'") {
      val dirs = portDirs("axisystem.fir")
      assert(dirs(("AXISlave",   "io")) == "slave")
      assert(dirs(("AXISlave_1", "io")) == "slave")
    }

    test("non-AXI ports retain plain in/out direction") {
      val dirs = portDirs("gcd.fir")
      // GCD has a plain Decoupled IO - all ports should be "in" or "out",
      // never "master" or "slave".
      val axiRoles = dirs.values.filter(v => v == "master" || v == "slave")
      assert(axiRoles.isEmpty)
    }

  }
}
