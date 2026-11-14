package probefix

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funsuite.AnyFunSuite

// Modules whose constructor parameters are plain (not vals): the probe reads
// their values only from the constructor arguments it records.

/** One output, so each fixture module is a real module of the circuit. */
abstract class Leaf extends Module {
  val out = IO(Output(UInt(8.W)))
  out := 0.U
}

case class LaneCfg(width: Int, label: String)
case class Wrap(inner: Wrap2)
case class Wrap2(inner: Wrap3)
case class Wrap3(inner: Wrap4)
case class Wrap4(n: Int)

/** No toString of its own. */
class Opaque
/** A toString that throws once armed (Chisel itself calls it to name the
  * module, so it is armed after elaboration builds the module). */
class Bomb {
  @volatile var armed = false
  override def toString: String = if (armed) throw new IllegalStateException("bomb") else "Bomb"
}

class Plain(nCores: Int) extends Leaf
class WithCfg(cfg: LaneCfg) extends Leaf
class WithSeq(taps: Seq[Int]) extends Leaf
class WithFn(f: Int => Int) extends Leaf
class WithType(gen: UInt) extends Leaf
class WithLit(init: UInt) extends Leaf
class WithHw(sig: UInt) extends Leaf
class WithOpaque(o: Opaque) extends Leaf
class WithBomb(b: Bomb) extends Leaf
class WithLong(s: String) extends Leaf
class WithDeep(w: Wrap) extends Leaf
class Lists(a: Int)(b: String)(implicit cfg: LaneCfg) extends Leaf
class Secondary(depth: Int, width: Int) extends Leaf {
  def this(depth: Int) = this(depth, 8)
}
class Fifo(depth: Int) extends Leaf
abstract class Lanes(lanes: Int) extends Leaf
class Striped(stripes: Int) extends Lanes(stripes * 2)

class Holder(val tag: Int) {
  class InnerMod(depth: Int) extends Leaf
}

class ParamsTop extends Module {
  val out = IO(Output(UInt(8.W)))
  out := 0.U
  implicit val cfg: LaneCfg = LaneCfg(4, "imp")
  val holder = new Holder(7)

  /** A module class declared in a module: its outer instance is a module. */
  class Nested(k: Int) extends Leaf

  val plain     = Module(new Plain(2))
  val cfgA      = Module(new WithCfg(LaneCfg(8, "a")))
  val cfgB      = Module(new WithCfg(LaneCfg(8, "a")))
  val cfgC      = Module(new WithCfg(LaneCfg(16, "a")))
  val seq       = Module(new WithSeq(1 to 20))
  val fn        = Module(new WithFn(_ + 1))
  val tpe       = Module(new WithType(UInt(8.W)))
  val opaque    = Module(new WithOpaque(new Opaque))
  private val armed = new Bomb
  val bomb      = Module(new WithBomb(armed))
  val longA     = Module(new WithLong("x" * 300 + "a"))
  val longB     = Module(new WithLong("x" * 300 + "b"))
  val deep      = Module(new WithDeep(Wrap(Wrap2(Wrap3(Wrap4(5))))))
  val lists     = Module(new Lists(1)("two"))
  val secondary = Module(new Secondary(3))
  val anon      = Module(new Fifo(4) { override def desiredName = "FifoAnon" })
  val striped   = Module(new Striped(3))
  val inner     = Module(new holder.InnerMod(5))
  val nested    = Module(new Nested(9))
  val lit5      = Module(new WithLit(5.U(3.W)))
  val lit7      = Module(new WithLit(7.U(3.W)))
  val hw        = Module(new WithHw(out))
  armed.armed = true
}

class ParamsSpec extends AnyFunSuite with ChiselSim {
  test("params top") {
    simulate(new ParamsTop) { dut => dut.clock.step() }
  }
}
