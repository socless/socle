package socle.ui.model

import socle.schema._
import utest._

/** The UI's pure labelling helpers: module glyphs, logic pin labels, and the
  * generator class header text. */
object LabelsTest extends TestSuite {

  private def mod(name: String, children: Int = 0, regs: Int = 0, kind: String = ModuleKind.Internal) =
    ModuleDef(name, name, "", SourceRef.unknown, Nil,
      (0 until children).map(i => InstanceDef(s"c$i", "Leaf", "", SourceRef.unknown)),
      Nil, Metrics(regs, 0, 0), kind = kind)

  val tests = Tests {
    test("Glyphs") {
      test("hub wins over registers and blackbox") {
        assert(Glyphs.of(Some(mod("H", children = 3, regs = 5, kind = ModuleKind.External))) == "⬢")
      }
      test("two children is not a hub") {
        assert(Glyphs.of(Some(mod("T", children = 2))) == "·")
      }
      test("registers win over blackbox") {
        assert(Glyphs.of(Some(mod("R", regs = 1, kind = ModuleKind.Intrinsic))) == "◆")
      }
      test("external and intrinsic are blackboxes") {
        assert(Glyphs.of(Some(mod("E", kind = ModuleKind.External))) == "■")
        assert(Glyphs.of(Some(mod("I", kind = ModuleKind.Intrinsic))) == "■")
      }
      test("unknown module and plain module") {
        assert(Glyphs.of(None) == "·")
        assert(Glyphs.of(Some(mod("P"))) == "·")
      }
      test("by type name in a design, missing type") {
        val d = Design("Top", Map.empty, Seq(mod("Top", children = 4), mod("R", regs = 2)))
        assert(Glyphs.of(d, "Top") == "⬢")
        assert(Glyphs.of(d, "R") == "◆")
        assert(Glyphs.of(d, "Nope") == "·")
      }
    }

    test("LogicLabels.stripQ") {
      test("register Q pin reads as the register") {
        assert(LogicLabels.stripQ("r~q") == "r")
        assert(LogicLabels.stripQ("r~q.bits.a") == "r.bits.a")
      }
      test("Vec register element keeps its index") {
        assert(LogicLabels.stripQ("v~q[0]") == "v[0]")
        assert(LogicLabels.stripQ("v~q[3].x") == "v[3].x")
      }
      test("op input pin reads in<i>, keeping a field") {
        assert(LogicLabels.stripQ("sum~in0") == "in0")
        assert(LogicLabels.stripQ("sum~in12.x") == "in12.x")
      }
      test("only the head segment is cleaned") {
        assert(LogicLabels.stripQ("a.b~q") == "a.b~q")
        assert(LogicLabels.stripQ("a.s~in0") == "a.s~in0")
      }
      test("non-matching labels are untouched") {
        assert(LogicLabels.stripQ("sum~in") == "sum~in")
        assert(LogicLabels.stripQ("io.out") == "io.out")
        assert(LogicLabels.stripQ("q") == "q")
      }
      test("null and empty pass through") {
        assert(LogicLabels.stripQ(null) == null)
        assert(LogicLabels.stripQ("") == "")
      }
    }

    test("LogicLabels.inRole") {
      test("mux operands by the sel value that picks them") {
        assert((0 to 2).map(LogicLabels.inRole("mux", _)) == Seq("sel", "1", "0"))
      }
      test("binary, shift and unary ops") {
        assert(LogicLabels.inRole("sub", 1) == "b")
        assert(LogicLabels.inRole("add", 0) == "a")
        assert(LogicLabels.inRole("dshr", 1) == "amt")
        assert(LogicLabels.inRole("not", 0) == "in")
      }
      test("out of range and unknown ops fall back to the index") {
        assert(LogicLabels.inRole("mux", 3) == "3")
        assert(LogicLabels.inRole("not", 1) == "1")
        assert(LogicLabels.inRole("cat", 0) == "0")
        assert(LogicLabels.inRole("add", -1) == "-1")
      }
    }

    test("ScalaClassLabel") {
      test("simpleName") {
        assert(ScalaClassLabel.simpleName("deephier.Cluster", "x") == "Cluster")
        assert(ScalaClassLabel.simpleName("a.Outer$Inner", "x") == "Inner")
        assert(ScalaClassLabel.simpleName("a.Obj$", "x") == "Obj")
        assert(ScalaClassLabel.simpleName("Top", "x") == "Top")
        // An anonymous class, an empty class name: the fallback.
        assert(ScalaClassLabel.simpleName("a.B$$anon$1", "Fallback") == "Fallback")
        assert(ScalaClassLabel.simpleName("", "Fallback") == "Fallback")
        assert(ScalaClassLabel.simpleName("a.B.", "Fallback") == "Fallback")
      }
      val base = mod("Cluster_1").copy(scalaClass = "deephier.Cluster")
      test("signature without parameters is the simple name") {
        assert(ScalaClassLabel.signature(base) == "Cluster")
        assert(ScalaClassLabel.signature(mod("Plain")) == "Plain")
      }
      test("ordered typed parameters win over the unordered map") {
        val m = base.copy(
          parameters = Map("ignored" -> "9"),
          parameterDecls = Seq(ParamDef("nCores", "Int", "4"), ParamDef("w", "", "<unreadable>")))
        assert(ScalaClassLabel.params(m) == Seq(
          ScalaClassLabel.Param("nCores", Some("Int"), "4"),
          ScalaClassLabel.Param("w", None, "<unreadable>")))
        assert(ScalaClassLabel.signature(m) == "Cluster(nCores = 4, w = <unreadable>)")
        assert(ScalaClassLabel.params(m).map(ScalaClassLabel.line) == Seq("nCores: Int = 4", "w = <unreadable>"))
      }
      test("the parameter map gives typeless parameters") {
        val m = base.copy(parameters = Map("depth" -> "16"))
        assert(ScalaClassLabel.params(m) == Seq(ScalaClassLabel.Param("depth", None, "16")))
        assert(ScalaClassLabel.signature(m) == "Cluster(depth = 16)")
      }
      test("truncate") {
        assert(ScalaClassLabel.truncate("abc", 3) == "abc")
        assert(ScalaClassLabel.truncate("abcd", 3) == "abc…")
        assert(ScalaClassLabel.truncate("", 3) == "")
        assert(ScalaClassLabel.truncate("abc", 0) == "…")
        // Code points, not chars: a surrogate pair counts once and is never split.
        val face = "😀"
        assert(ScalaClassLabel.truncate(face * 3, 2) == face * 2 + "…")
        assert(ScalaClassLabel.truncate(face * 2, 2) == face * 2)
        assert(ScalaClassLabel.truncate("a" + face, 1) == "a…")
        // A lone high surrogate is one code point.
        assert(ScalaClassLabel.truncate("\uD83Dx", 1) == "\uD83D…")
      }
      test("a long signature cut to the header width") {
        val m = base.copy(parameterDecls = (1 to 6).map(i => ParamDef(s"param$i", "Int", s"$i")))
        val cut = ScalaClassLabel.truncate(ScalaClassLabel.signature(m), ScalaClassLabel.HeaderMax)
        assert(cut.length == ScalaClassLabel.HeaderMax + 1, cut.endsWith("…"), cut.startsWith("Cluster(param1 = 1"))
      }
    }
  }
}
