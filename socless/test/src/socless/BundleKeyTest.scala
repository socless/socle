package socless

import utest._
import ChirrtlParser.{BundleT, Field, GroundT, Circ, Mod, Port}

/** Verifies bundle key generation: identity is the FULL structural
  * fingerprint (names, flips, ground kinds, widths, vec arity), so
  * structurally identical bundles collapse to one key regardless of hint,
  * and structurally different bundles never share a key even when they want
  * the same friendly name (issue #88). Hash suffixes come from SHA-1 over
  * the UTF-8 fingerprint, never String.hashCode, so keys are stable across
  * JVM versions; the pinned hex values guard that.
  */
object BundleKeyTest extends TestSuite {

  private def field(name: String, flip: Boolean = false, width: Int = 8): Field =
    Field(name, flip, GroundT("UInt", Some(width)))

  /** One module per (portName, bundle), all in one circuit, sharing a registry. */
  private def designFor(ports: (String, BundleT)*): Design = {
    val mods = ports.zipWithIndex.map { case ((hint, bundle), i) =>
      Mod(s"M$i", "module", Seq(Port(hint, "output", bundle, src = None)), Nil, Nil, src = None)
    }
    IrWalk.design(Circ("M0", mods))
  }

  private def bundleKeys(d: Design): Seq[String] =
    d.bundles.keys.toSeq.filterNot(k => k.startsWith("UInt") || k.startsWith("SInt") || k.startsWith("Vec"))

  private val abFlip = BundleT(Seq(field("a", flip = true), field("b"), field("c", flip = true)))
  private val data8  = BundleT(Seq(field("data")))
  private val data32 = BundleT(Seq(field("data", width = 32)))

  val tests = Tests {

    test("first registration claims the bare friendly name") {
      val d = designFor("myport" -> abFlip)
      assert(d.bundles.contains("Myport"))
    }

    test("identical structure under different hints collapses to ONE key") {
      val d    = designFor("portA" -> abFlip, "portB" -> abFlip)
      val keys = bundleKeys(d)
      assert(keys.length == 1)
      assert(keys.head == "Porta" || keys.head == "PortA" || keys.head.startsWith("Port"))
    }

    test("same hint, different structure: distinct keys, both definitions kept") {
      val d    = designFor("io" -> data8, "io" -> data32)
      val keys = bundleKeys(d)
      assert(keys.length == 2)
      assert(keys.contains("Io"))
      val other = keys.find(_ != "Io").get
      assert(other.startsWith("Io_"))
      // Both field widths survive (the old fingerprint silently overwrote one).
      val widths = keys.map(k => d.bundles(k)).flatMap(_.channels.map(_.`type`)).toSet
      assert(widths == Set("UInt<8>", "UInt<32>"))
    }

    test("hash suffix is deterministic SHA-1 hex over the structural fingerprint") {
      // SHA-1("{!a:UInt<8>,b:UInt<8>,!c:UInt<8>}").take(6) = 689197
      // SHA-1("{data:UInt<8>}").take(6)                    = 3bc851
      val d = designFor("p" -> data32, "p" -> data8, "p" -> abFlip)
      // data32 claims "P"; the other two collide on the base and get suffixes.
      assert(d.bundles.contains("P"))
      assert(d.bundles.contains("P_3bc851"))
      assert(d.bundles.contains("P_689197"))
    }

    test("Decoupled bundles with different payloads stay distinct") {
      val deq32 = BundleT(Seq(field("ready", flip = true, width = 1), field("valid", width = 1), field("bits", width = 32)))
      val deq64 = BundleT(Seq(field("ready", flip = true, width = 1), field("valid", width = 1), field("bits", width = 64)))
      val d     = designFor("deq" -> deq32, "deq" -> deq64)
      val keys  = bundleKeys(d)
      assert(keys.length == 2)
      assert(keys.contains("Decoupled_deq"))
      assert(keys.exists(_.startsWith("Decoupled_deq_")))
      val fieldWidths = keys.map(k => d.bundles(k)).flatMap(_.fields.map(_.width)).toSet
      assert(fieldWidths == Set(32, 64))
    }

    test("AXI4 friendly name goes to the first shape; a different AXI shape gets a suffix") {
      def axi(width: Int) = BundleT(Seq("ar", "aw", "w", "r", "b").map(n => field(n, width = width)))
      val d    = designFor("io" -> axi(8), "io" -> axi(16))
      assert(d.bundles.contains("AXI4"))
      assert(d.bundles.keys.exists(k => k.startsWith("AXI4_") && k != "AXI4"))
    }

    test("ReadyValid friendly-name fast-path is unchanged") {
      val rv = BundleT(Seq(field("ready"), field("valid")))
      val d  = designFor("io" -> rv)
      assert(d.bundles.keys.exists(_.startsWith("ReadyValid_")))
    }

    test("keys are deterministic across runs") {
      val d1 = designFor("io" -> data8, "io" -> data32, "q" -> abFlip)
      val d2 = designFor("io" -> data8, "io" -> data32, "q" -> abFlip)
      assert(d1.bundles.keys.toSet == d2.bundles.keys.toSet)
    }
  }
}
