package socle.probe

import chisel3.RawModule
import chisel3.stage.{ChiselCircuitAnnotation, ChiselGeneratorAnnotation, DesignAnnotation}
import socle.capture.CapturedModel

import scala.annotation.nowarn

/** Shared test helper: elaborate a generator in-process and walk it, as the
  * agent will in a test JVM. */
object TestElab {

  /** The elaborated DUT and its CHIRRTL serialization. The annotations are
    * typed as firrtl `Annotation`, deprecated since Chisel 7 with no public
    * replacement for this in-process elaboration. */
  @nowarn("cat=deprecation")
  def elaborate(gen: => RawModule): (RawModule, String) = {
    val annos   = ChiselGeneratorAnnotation(() => gen).elaborate.toSeq
    val dut     = annos.collectFirst { case d: DesignAnnotation[_] => d.design }.get
    val chirrtl = annos.collectFirst { case c: ChiselCircuitAnnotation => c.elaboratedCircuit.serialize }.get
    (dut, chirrtl)
  }

  def captureOf(gen: => RawModule): CapturedModel = {
    val (dut, chirrtl) = elaborate(gen)
    ModelWalk.capture(dut, chirrtl)
  }
}
