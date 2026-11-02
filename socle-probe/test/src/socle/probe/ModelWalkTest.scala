package socle.probe

import socle.capture._
import utest._

/** A plain (non-val) `name` parameter: must not be read back through the
  * inherited BaseModule.name. */
class NamedParam(name: String) extends chisel3.Module {
  val io = chisel3.IO(chisel3.Input(chisel3.Bool()))
}

/** A val parameter with a very long string value. */
class LongParam(val blob: String) extends chisel3.Module {
  val io = chisel3.IO(chisel3.Input(chisel3.Bool()))
}

object ModelWalkTest extends TestSuite {

  import TestElab.captureOf

  private def field(t: CType, name: String): CType =
    t.fields.collectFirst { case (`name`, f) => f }
      .getOrElse(throw new java.util.NoSuchElementException(s"no field $name in ${t.fields.map(_._1)}"))

  private lazy val gcdModel     = captureOf(new gcd.GCD)
  private lazy val deepsocModel = captureOf(new deephier.DeepSoc)

  val tests = Tests {
    test("gcd") {
      val m = gcdModel
      assert(m.status == "ok", m.problems.isEmpty)
      assert(m.top == "GCD")
      val top = m.modules.head
      assert(top.name == "GCD")
      val io = top.ports.find(_.name == "io").get.tpe
      assert(io.label == "io")
      assert(io.scalaClass.contains("$anon"))
      val in = field(io, "in")
      assert(in.scalaClass == "chisel3.util.DecoupledIO")
      assert(in.dir == "flip")
      val bits = field(in, "bits")
      assert(bits.scalaClass == "gcd.GCDInput")
      assert(field(bits, "a").width == 16)
      val src = top.source.get
      assert(src.file.endsWith("gcd/GCD.scala"))
      assert(src.line == 24)
    }

    test("deepsoc") {
      val m = deepsocModel
      assert(m.status == "ok", m.problems.isEmpty)
      val names = m.modules.map(_.name).toSet
      for (n <- Seq("DeepSoc", "Cluster", "Core", "ALU", "RegFile")) assert(names.contains(n))
      val cluster = m.modules.find(_.name == "Cluster").get
      assert(cluster.params.contains(CParam("nCores", "Int", "<unreadable>")))
    }

    test("queue params") {
      val queues = deepsocModel.modules.filter(_.scalaClass == "chisel3.util.Queue")
      assert(queues.nonEmpty)
      for (q <- queues) {
        val entries = q.params.find(_.name == "entries").get
        assert(entries.tpe == "Int", entries.value.matches("\\d+"))
      }
    }

    test("inherited member is not a param value") {
      val m = captureOf(new NamedParam("hello"))
      assert(m.modules.head.params == Seq(CParam("name", "String", "<unreadable>")))
    }

    test("long param values are truncated") {
      val m = captureOf(new LongParam("x" * 500))
      val v = m.modules.head.params.find(_.name == "blob").get.value
      assert(v == "x" * 200 + "...(truncated)")
    }

    test("round trip") {
      val m = deepsocModel
      assert(CapturedModel.fromJson(CapturedModel.toJson(m)) == m)
    }
  }
}
