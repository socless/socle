package socless.schema

import socless._

import socless.schema._
import socless.parse._
import socless.ir._
import socless.layout._
import socless.cli._

import utest._

/** Pins the shared bundle-tree walk (issue #170): the Scala port of the UI's
  * former portTree / isAggregateType / resolvePinDir. */
object BundleTreeTest extends TestSuite {

  // A small design: a Producer with a Decoupled output `io` (bits = data:8),
  // and a Top with a Vec-of-AXI4 port and a plain bundle.
  private val design = Design(
    top = "Top",
    bundles = Map(
      "Decoupled_X" -> BundleDef("Decoupled_X", "Decoupled",
        fields = Seq(FieldDef("data", 8, "out"))),
      "AXI4" -> BundleDef("AXI4", "Bundle", channels = Seq(
        ChannelDef("aw", "AW", flip = false),
        ChannelDef("b",  "B",  flip = true),   // response channel flips
      )),
      "AW" -> BundleDef("AW", "Bundle", channels = Seq(ChannelDef("addr", "UInt<32>", flip = false))),
      "B"  -> BundleDef("B",  "Bundle", channels = Seq(ChannelDef("resp", "UInt<2>",  flip = false))),
    ),
    modules = Seq(
      ModuleDef("Top", "Top", "", SourceRef.unknown,
        ports = Seq(PortDef("bus", "master", "Vec[AXI4, 2]", "", SourceRef.unknown)),
        children = Nil, wires = Nil, metrics = Metrics(0, 0, 0)),
    ),
  )

  val tests = Tests {
    test("isAggregateType: bundles and Vecs expand, grounds do not") {
      assert(BundleTree.isAggregateType("AXI4", design))
      assert(BundleTree.isAggregateType("Vec[AXI4, 2]", design))
      assert(BundleTree.isAggregateType("Vec[UInt<8>, 4]", design))
      assert(!BundleTree.isAggregateType("UInt<8>", design))
      assert(!BundleTree.isAggregateType("", design))
    }

    test("portTree expands a Vec into indexed elements") {
      val t = BundleTree.portTree("Vec[AXI4, 2]", Some("out"), "bus", design)
      assert(t.isExpandable)
      assert(t.children.length == 2)
      assert(t.children.map(_.name) == Seq("[0]", "[1]"))
      assert(t.children.head.path == "bus[0]")
      assert(t.children.head.bundleKey == "AXI4")
    }

    test("portTree flips a flipped channel's direction") {
      val axi = BundleTree.portTree("AXI4", Some("out"), "x.io", design)
      val aw  = axi.children.find(_.name == "aw").get
      val b   = axi.children.find(_.name == "b").get
      assert(aw.dir.contains("out"))   // not flipped
      assert(b.dir.contains("in"))     // flipped against base "out"
    }

    test("portTree Decoupled yields valid/ready/bits") {
      val d = BundleTree.portTree("Decoupled_X", Some("out"), "p.io", design)
      val names = d.children.map(_.name)
      assert(names.contains("valid") && names.contains("ready") && names.contains("bits.data"))
      assert(d.children.find(_.name == "valid").get.dir.contains("out"))
      assert(d.children.find(_.name == "ready").get.dir.contains("in"))
    }

    // #313: a consumer (Flipped(Decoupled)) port receives valid and drives ready.
    test("consumer Decoupled: valid in, ready out") {
      def dirs(base: Option[String]) = {
        val c = BundleTree.portTree("Decoupled_X", base, "x.io.in", design).children
        (c.find(_.name == "valid").get.dir, c.find(_.name == "ready").get.dir)
      }
      assert(dirs(Some("in")) == ((Some("in"), Some("out"))))
      assert(dirs(Some("out")) == ((Some("out"), Some("in"))))
      assert(dirs(None) == ((None, None)))
    }

    // Regression guard for issue 1 ("weird recursive thing"): a Decoupled port
    // must expand exactly ONE level into valid / ready / bits.* leaves, with NO
    // child re-expanding into the same valid/ready set. The trigger would be a
    // bits/valid/ready leaf whose bundleKey is (wrongly) a registered aggregate,
    // making isExpandable true and re-splitting. We gate isExpandable strictly on
    // registered-bundle membership so flattened ground leaves never re-expand.
    test("Decoupled expands one level: valid/ready/bits.* leaves, none re-expandable into the same set") {
      val children = BundleTree.portTree("Decoupled_X", Some("out"), "p.io", design).children
      val names    = children.map(_.name).toSet
      assert(names.contains("valid") && names.contains("ready"))
      assert(children.exists(_.name.startsWith("bits")))
      children.foreach { c =>
        if (c.isExpandable) {
          val sub = BundleTree.portTree(c.bundleKey, c.dir, c.path, design).children.map(_.name).toSet
          assert(!(sub.contains("valid") && sub.contains("ready")))
        }
      }
    }

    test("portTree with no base direction leaves child dirs unresolved") {
      val axi = BundleTree.portTree("AXI4", None, "x.io", design)
      assert(axi.children.forall(_.dir.isEmpty))
    }

    test("resolvePinDir walks Vec index + channel flip") {
      // bus is a master (out) Vec[AXI4,2]; bus[0].b is the flipped response channel.
      assert(BundleTree.resolvePinDir("Top", "top.bus[0].aw", design).contains("out"))
      assert(BundleTree.resolvePinDir("Top", "top.bus[0].b",  design).contains("in"))
      assert(BundleTree.resolvePinDir("Top", "top.bus",       design).contains("out"))
    }

    test("displayName: the Chisel-level name of a type, never a structural hash") {
      def dn(key: String, bundles: Map[String, BundleDef]) =
        BundleTree.displayName(key, Design("T", bundles, Nil))
      val classic = Map(
        "AXI4" -> BundleDef("AXI4", "Bundle"),
        "Decoupled_out_0112bf" -> BundleDef("Decoupled_out_0112bf", "Decoupled"),
        "ReadyValid_x" -> BundleDef("ReadyValid_x", "Bundle"),
        "Io_284312" -> BundleDef("Io_284312", "Bundle"),
        "Rf" -> BundleDef("Rf", "Bundle"),
      )
      assert(dn("AXI4", classic) == "AXI4")
      assert(dn("Decoupled_out_0112bf", classic) == "Decoupled")
      assert(dn("ReadyValid_x", classic) == "ReadyValid")
      // A classic key named after its port is anonymous: no type shown.
      assert(dn("Io_284312", classic) == "")
      assert(dn("Rf", classic) == "")
      assert(dn("Vec[AXI4, 2]", classic) == "Vec[AXI4, 2]")
      assert(dn("Vec[Io_284312, 2]", classic) == "Vec[2]")
      assert(dn("UInt<8>", classic) == "UInt<8>")
      assert(dn("Vec[UInt<1>, 3]", classic) == "Vec[UInt<1>, 3]")
      // A captured design names the Scala class; an anonymous class is no name.
      val captured = Map(
        "chisel3.util.DecoupledIO_d565d6" -> BundleDef("chisel3.util.DecoupledIO_d565d6", "Decoupled", scalaClass = "chisel3.util.DecoupledIO"),
        "gcd.GCDInput" -> BundleDef("gcd.GCDInput", "Bundle", scalaClass = "gcd.GCDInput"),
        "GCD.io" -> BundleDef("GCD.io", "Bundle", scalaClass = "gcd.GCD$$anon$1"),
        "x.Outer$Inner" -> BundleDef("x.Outer$Inner", "Bundle", scalaClass = "x.Outer$Inner"),
      )
      assert(dn("chisel3.util.DecoupledIO_d565d6", captured) == "DecoupledIO")
      assert(dn("gcd.GCDInput", captured) == "GCDInput")
      assert(dn("GCD.io", captured) == "")
      assert(dn("x.Outer$Inner", captured) == "Inner")
    }

    test("Decoupled with two fields has a bits node") {
      val d2 = design.copy(bundles = design.bundles + ("Decoupled_X2" -> BundleDef("Decoupled_X2", "Decoupled",
        fields = Seq(FieldDef("a", 8, "out"), FieldDef("b", 4, "out")))))
      val c = BundleTree.portTree("Decoupled_X2", Some("out"), "p.io", d2).children
      assert(c.map(_.name) == Seq("valid", "ready", "bits"))
      val bits = c.last
      assert(bits.isExpandable && bits.path == "p.io.bits")
      assert(bits.children.map(_.path) == Seq("p.io.bits.a", "p.io.bits.b"))
      assert(bits.children.map(_.name) == Seq("a", "b"))
      assert(BundleTree.bitsSummary(bits) == "a 8b, b 4b")
      assert(BundleTree.displayName(bits.bundleKey, d2) == "")
      assert(!BundleTree.isAggregateType(bits.bundleKey, d2))
    }

    test("nested bits fields") {
      val d3 = design.copy(bundles = design.bundles + ("Decoupled_req" -> BundleDef("Decoupled_req", "Decoupled",
        fields = Seq(FieldDef("ctrl.addr", 32, "out"), FieldDef("ctrl.opcode", 4, "out"), FieldDef("data.mask", 8, "out")))))
      val bits = BundleTree.portTree("Decoupled_req", Some("in"), "q.io.enq", d3).children.last
      assert(bits.children.map(_.name) == Seq("ctrl.addr", "ctrl.opcode", "data.mask"))
      assert(bits.children.map(_.path).head == "q.io.enq.bits.ctrl.addr")
    }

    test("resolvePinDir returns None for unknown module/port") {
      assert(BundleTree.resolvePinDir("Ghost", "g.x", design).isEmpty)
      assert(BundleTree.resolvePinDir("Top", "top.nope", design).isEmpty)
    }
  }
}
