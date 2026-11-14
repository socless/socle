package socle.probe

import socle.capture.{CParam, CapturedModel}
import utest._

import scala.collection.mutable

case class TCfg(width: Int, tag: String)
case class THolder(f: Int => Int)
class TNoString
class TIdentity { override def toString: String = s"TIdentity@${Integer.toHexString(System.identityHashCode(this))}" }
class TNamed(n: Int) { override def toString: String = s"TNamed($n)" }

/** Records its own constructor arguments as the agent's advice would, so the
  * walk's ctor path runs in-process (TestElab elaborates without the agent). */
class SelfRecorded(width: Int, cfg: TCfg) extends chisel3.Module {
  CtorArgs.record(this, classOf[SelfRecorded], Array[AnyRef](Int.box(width), cfg))
  val io = chisel3.IO(chisel3.Input(chisel3.Bool()))
}

object ParamValuesTest extends TestSuite {
  import ParamValues.{digest, render}

  private def u(w: Int) = chisel3.UInt(chisel3.fromIntToWidth(w).W)
  private val noString     = new TNoString
  private val identityLike = new TIdentity
  private val named3       = new TNamed(3)

  val tests = Tests {
    test("render") {
      assert(render(42, "p") == "42")
      assert(render("s", "p") == "s")
      assert(render(BigInt(7), "p") == "7")
      assert(render(null, "p") == "null")
      assert(render((1, "a"), "p") == "(1, a)")
      assert(render(List(1, 2), "p") == "List(1, 2)")
      assert(render(Array(1, 2), "p") == "Array(1, 2)")
      assert(render(Map("a" -> 1), "p") == "Map(a -> 1)")
      assert(render(Vector.tabulate(10)(identity), "p") == "Vector(0, 1, 2, 3, 4, 5, 6, 7, ... (10 total))")
      assert(render(Some(TCfg(8, "x")), "p") == "Some(value = TCfg(width = 8, tag = x))")
      assert(render(None, "p") == "None")
      assert(render((x: Int) => x, "p") == "<function>")
      assert(render(noString, "p") == "<TNoString>")
      assert(render(named3, "p") == "TNamed(3)")
      assert(render(LazyList.from(0), "p") == "<LazyList>")
      assert(render(u(8), "p") == "UInt<8>")
    }

    test("digest: equal values, equal digests") {
      assert(digest(TCfg(8, "x"), "p") == digest(TCfg(8, "x"), "p"))
      assert(digest(List(1, 2, 3), "p") == digest(Vector(1, 2, 3), "p"))
      // unordered collections: iteration order does not matter
      val m1 = (1 to 40).map(i => i.toString -> i).toMap
      val m2 = scala.collection.immutable.ListMap((1 to 40).reverse.map(i => i.toString -> i): _*)
      assert(digest(m1, "p") == digest(m2, "p"), digest(m1, "p").nonEmpty)
      assert(digest(u(8), "p") == digest(u(8), "p"))
    }

    test("digest: different values, different digests") {
      assert(digest(TCfg(8, "x"), "p") != digest(TCfg(16, "x"), "p"))
      assert(digest(1, "p") != digest("1", "p"))
      assert(digest(1, "p") != digest(1L, "p"))
      assert(digest(List("a,b"), "p") != digest(List("a", "b"), "p"))
      // past the display cap: same text, different digests
      val a = "y" * 1000 + "a"
      val b = "y" * 1000 + "b"
      assert(render(a, "p").take(200) == render(b, "p").take(200))
      assert(digest(a, "p") != digest(b, "p"))
      // a Seq longer than the 8 shown elements
      assert(digest((1 to 20).toList, "p") != digest((1 to 19).toList :+ 99, "p"))
      // widths the label alone would merge
      assert(digest(u(8), "p") != digest(u(16), "p"))
    }

    test("digest: opaque values, at any depth, have none") {
      assert(digest((x: Int) => x, "p") == "")
      assert(digest(THolder(_ + 1), "p") == "")
      assert(digest(List(noString), "p") == "")
      assert(digest(identityLike, "p") == "")
      assert(digest(LazyList.from(0), "p") == "")
      assert(digest(named3, "p").nonEmpty)
    }

    test("digest: over budget, none") {
      assert(digest(1 to (ParamValues.DigestNodes + 1), "p") == "")
      assert(digest("z" * (ParamValues.DigestChars + 1), "p") == "")
    }

    test("module params capped at 4 KB") {
      val problems = mutable.ArrayBuffer.empty[String]
      val ps = (1 to 30).map(i => CParam(s"p$i", "String", "v" * 200, "ctor", "d"))
      val capped = ModelWalk.capParams("Wide", ps, problems)
      assert(capped.take(20) == ps.take(20))
      assert(capped.drop(20).forall(p => p.value == "...(truncated)" && p.digest == "d"))
      assert(problems.size == 1, problems.head.startsWith("Wide: "))
    }

    test("old captures without origin or digest still parse") {
      val p = upickle.default.read[CParam]("""{"name":"n","tpe":"Int","value":"4"}""")
      assert(p == CParam("n", "Int", "4", "", ""))
      assert(!CapturedModel.toJson(CapturedModel("ok", "T", Nil)).contains("origin"))
      val q = CParam("n", "Int", "4", "ctor", "ab")
      assert(upickle.default.read[CParam](upickle.default.write(q)) == q)
    }

    test("walk reads recorded constructor arguments") {
      CtorArgs.resetForTest()
      val m = TestElab.captureOf(new SelfRecorded(8, TCfg(4, "t")))
      val ps = m.modules.head.params
      assert(ps.map(p => (p.name, p.value, p.origin)) == Seq(("width", "8", "ctor"), ("cfg", "TCfg(width = 4, tag = t)", "ctor")))
      assert(ps.forall(_.digest.nonEmpty))
      CtorArgs.stop()
      CtorArgs.resetForTest()
    }
  }
}
