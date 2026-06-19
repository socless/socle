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

    test("resolvePinDir returns None for unknown module/port") {
      assert(BundleTree.resolvePinDir("Ghost", "g.x", design).isEmpty)
      assert(BundleTree.resolvePinDir("Top", "top.nope", design).isEmpty)
    }
  }
}
