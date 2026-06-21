package socless.layout

import socless.schema._
import utest._

object LayoutDebugTest extends TestSuite {

  // A tiny hand-built layout: two boxes, pins on both sides, one routed edge.
  val layout: ModuleLayout = ModuleLayout(
    nodes = Seq(
      LaidNode("a", "inst", 10, 20, 80, 50, Seq(
        LaidPort("a.in", 10, 40, "WEST", interface = false, label = "in"),
        LaidPort("a.out", 90, 40, "EAST", interface = true, label = "out"),
      )),
      LaidNode("b", "reg", 200, 20, 80, 50, Seq(
        LaidPort("b", 200, 40, "WEST", interface = false),
        LaidPort("b~q", 280, 40, "EAST", interface = false),
      )),
    ),
    edges = Seq(RoutedEdge("a.out", "b", Seq((90, 40), (200, 40)), label = "1x")),
    w = 320, h = 90,
  )

  val tests = Tests {
    test("textFor lists every node with coords and every edge, deterministically") {
      val t = LayoutDebug.textFor("M", layout)
      assert(t.contains("layout M  canvas=320x90  nodes=2 edges=1"))
      assert(t.contains("node a  kind=inst  pos=(10,20) size=80x50"))
      assert(t.contains("pin a.out  EAST (90,40) iface"))
      assert(t.contains("edge a.out -> b  pts=(90,40)(200,40)  1x"))
      assert(t.indexOf("node a") < t.indexOf("node b")) // sorted by id
    }
    test("svgFor is well-formed XML and contains every node, pin, edge, canvas") {
      val s = LayoutDebug.svgFor("M", layout)
      // Parses as XML (well-formed).
      javax.xml.parsers.SAXParserFactory.newInstance().newSAXParser()
        .parse(new java.io.ByteArrayInputStream(s.getBytes("UTF-8")),
               new org.xml.sax.helpers.DefaultHandler())
      assert(s.contains("<svg"))
      assert(s.contains("""width="320""""))                                // canvas width
      assert(s.contains("""x="10" y="20" width="80" height="50""""))       // node a rect
      assert(s.contains("""points="90,40 200,40""""))                      // edge polyline
      assert(s.contains("""cx="90""""))                                    // a.out pin dot
    }
    test("svgFor arrowhead coords are locale-independent (no comma-decimal corruption)") {
      // Float formatting via `%.1f` is locale-sensitive: under a comma-decimal
      // locale (fr_FR) it emits `194,0` for 194.0, which silently corrupts the
      // SVG `points` attribute (the comma reads as a coordinate separator) and
      // turns a 6px arrowhead into a giant polygon. Force such a locale and
      // assert every polygon point token is a clean `int,int` pair.
      val saved = java.util.Locale.getDefault
      try {
        java.util.Locale.setDefault(java.util.Locale.FRANCE)
        val s = LayoutDebug.svgFor("M", layout)
        val polys = "<polygon points=\"([^\"]+)\"".r.findAllMatchIn(s).map(_.group(1)).toList
        assert(polys.nonEmpty)
        polys.foreach(p => p.split(" ").foreach(tok => assert(tok.split(",").length == 2)))
      } finally java.util.Locale.setDefault(saved)
    }
    test("svgFor box titles omit coordinate annotations (coords live in the txt)") {
      val s = LayoutDebug.svgFor("M", layout)
      assert(!s.contains("(10,20"))        // node 'a' coord annotation gone from the SVG
      assert(s.contains(">a</text>"))      // the node id is still drawn as the title
    }
    test("svgFor draws boundary stubs with the pin label only, not a redundant id title") {
      val bl = ModuleLayout(
        nodes = Seq(LaidNode("io.foo", "boundary", 0, 0, 118, 28,
          Seq(LaidPort("io.foo", 0, 14, "EAST", interface = false, label = "foo")))),
        edges = Nil, w = 130, h = 40)
      val s = LayoutDebug.svgFor("B", bl)
      assert(s.contains(">foo</text>"))    // the pin label is drawn
      assert(!s.contains(">io.foo</text>"))// no separate full-path id title on the box
    }
    test("svgFor caps rendered width/height with maxPx but keeps a full-resolution viewBox") {
      val capped = LayoutDebug.svgFor("M", layout, maxPx = 100)
      assert(capped.contains("""viewBox="0 0 320 110""""))  // full coords preserved
      assert(capped.contains("""width="100""""))            // 320 scaled to maxPx
      assert(capped.contains("""height="34""""))            // 110 * 100/320 = 34.4 -> 34
      val uncapped = LayoutDebug.svgFor("M", layout)        // default: no cap
      assert(uncapped.contains("""width="320""""))
      assert(uncapped.contains("""height="110""""))
    }
    test("CLI debug-layout reads a design.json and writes a dump dir") {
      def mod(n: String) =
        ModuleDef(n, n, "", SourceRef.unknown, Nil, Nil, Nil, Metrics(0, 0, 0))
      val design = Design("M", Map.empty, Seq(mod("M")),
        layouts = Map("M" -> layout), layoutOf = Map("M" -> "M"))
      val dir  = os.temp.dir()
      val json = dir / "design.json"
      os.write(json, upickle.default.write(design))
      val out  = dir / "dump"
      val code = socless.cli.Launcher.runDebugLayout(Array(json.toString, "--out", out.toString))
      assert(code == 0)
      assert(os.exists(out / "M.svg"))
      assert(os.exists(out / "M.txt"))

      // No layouts -> nonzero exit, no crash.
      val empty = dir / "empty.json"
      os.write(empty, upickle.default.write(Design("E", Map.empty, Seq(mod("E")))))
      assert(socless.cli.Launcher.runDebugLayout(Array(empty.toString)) != 0)

      // Usage errors (bad module / bad format) -> exit 2, no crash.
      assert(socless.cli.Launcher.runDebugLayout(Array(json.toString, "--module", "BOGUS")) == 2)
      assert(socless.cli.Launcher.runDebugLayout(Array(json.toString, "--format", "pdf")) == 2)
      // --max-px: non-integer is a usage error; a valid value still succeeds.
      assert(socless.cli.Launcher.runDebugLayout(Array(json.toString, "--max-px", "abc")) == 2)
      assert(socless.cli.Launcher.runDebugLayout(Array(json.toString, "--out", out.toString, "--max-px", "200")) == 0)

      // A real module with no baked layout -> nonzero, no silent empty success.
      val noLay = dir / "nolay.json"
      os.write(noLay, upickle.default.write(
        Design("M", Map.empty, Seq(mod("M"), mod("N")),
          layouts = Map("M" -> layout), layoutOf = Map("M" -> "M"))))
      assert(socless.cli.Launcher.runDebugLayout(Array(noLay.toString, "--module", "N")) != 0)
    }
    test("dump writes per-module files and resolves deduped layouts") {
      def mod(n: String) =
        ModuleDef(n, n, "", SourceRef.unknown, Nil, Nil, Nil, Metrics(0, 0, 0))
      val design = Design(
        top      = "M",
        bundles  = Map.empty,
        modules  = Seq(mod("M"), mod("M_1")),   // M_1 shares M's layout via layoutOf
        layouts  = Map("M" -> layout),
        layoutOf = Map("M" -> "M", "M_1" -> "M"),
      )
      val dir = os.temp.dir()
      val written = LayoutDebug.dump(design, dir, moduleFilter = None, format = "both")
      assert(written.size == 4)                        // M + M_1, each .svg + .txt
      assert(os.exists(dir / "M.svg"))
      assert(os.exists(dir / "M.txt"))
      assert(os.exists(dir / "M_1.svg"))               // resolved through layoutOf
      assert(os.read(dir / "M_1.txt").contains("layout M_1"))

      val onlyM = os.temp.dir()
      val w2 = LayoutDebug.dump(design, onlyM, moduleFilter = Some("M"), format = "svg")
      assert(w2.size == 1)
      assert(os.exists(onlyM / "M.svg"))
      assert(!os.exists(onlyM / "M.txt"))
    }
  }
}
