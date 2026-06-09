package socless

import utest._
import ChirrtlParser.{BundleT, Field, GroundT}

/** Verifies that bundle key generation is deterministic and stable across JVMs.
  *
  * The hash suffix is derived from SHA-1 over the UTF-8 structural fingerprint,
  * not from String.hashCode (which is not guaranteed stable across JVM versions
  * or implementations). These tests pin the exact expected hex values so that a
  * future accidental regression is immediately caught.
  */
object BundleKeyTest extends TestSuite {

  // Helpers to build small BundleT fixtures
  private def field(name: String, flip: Boolean = false): Field =
    Field(name, flip, GroundT("UInt", Some(8)))

  // Drive bundleKey through the public API: build a minimal Design and check
  // what key ends up in the bundles map.
  private def keyFor(fields: Seq[Field], hint: String): String = {
    import ChirrtlParser.{Circ, Mod, Port}
    val bundle = BundleT(fields)
    val port   = Port(hint, "output", bundle, src = None)
    val mod    = Mod("Top", "module", Seq(port), Nil, Nil, src = None)
    val circ   = Circ("Top", Seq(mod))
    val design = IrWalk.design(circ)
    // Return the first key that isn't a plain ground-type string.
    design.bundles.keys
      .find(k => !k.startsWith("UInt") && !k.startsWith("SInt") && !k.startsWith("Vec"))
      .getOrElse(throw new RuntimeException(
        s"no bundle key found; bundles=${design.bundles.keys.toSeq}"))
  }

  // Known SHA-1 hex values (verified with Python's hashlib.sha1):
  //   SHA-1("fa_b_fc")[0..5] = 390d65
  //   SHA-1("data")[0..5]    = a17c9a
  private val fpFlipABFlipC = Seq(field("a", flip = true), field("b"), field("c", flip = true))
  private val fpData         = Seq(field("data"))

  val tests = Tests {

    test("key has expected shape: Hint_<6hexchars>") {
      val k      = keyFor(fpFlipABFlipC, "myport")
      val prefix = "Myport_"
      assert(k.startsWith(prefix))
      val suffix = k.stripPrefix(prefix)
      assert(suffix.length == 6)
      assert(suffix.forall(c => "0123456789abcdef".contains(c)))
    }

    test("same-shaped bundle always produces the same key (determinism)") {
      val k1 = keyFor(fpFlipABFlipC, "myport")
      val k2 = keyFor(fpFlipABFlipC, "myport")
      assert(k1 == k2)
    }

    test("same shape different hint: identical hex suffix, different full key") {
      val k1      = keyFor(fpFlipABFlipC, "portA")
      val k2      = keyFor(fpFlipABFlipC, "portB")
      val suffix1 = k1.dropWhile(_ != '_').drop(1)
      val suffix2 = k2.dropWhile(_ != '_').drop(1)
      assert(suffix1 == suffix2)
      assert(k1 != k2)
    }

    test("different shapes produce different hex suffixes") {
      val k1      = keyFor(fpFlipABFlipC, "p")
      val k2      = keyFor(fpData, "p")
      val suffix1 = k1.dropWhile(_ != '_').drop(1)
      val suffix2 = k2.dropWhile(_ != '_').drop(1)
      assert(suffix1 != suffix2)
    }

    // Pinned golden values: if the algorithm, encoding, or take-length ever
    // changes accidentally these assertions catch it immediately.
    test("pinned hex for fingerprint 'fa_b_fc' is 390d65") {
      val k      = keyFor(fpFlipABFlipC, "myport")
      val suffix = k.stripPrefix("Myport_")
      assert(suffix == "390d65")
    }

    test("pinned hex for fingerprint 'data' is a17c9a") {
      val k      = keyFor(fpData, "p")
      val suffix = k.stripPrefix("P_")
      assert(suffix == "a17c9a")
    }

    test("AXI4 friendly-name fast-path is unchanged") {
      import ChirrtlParser.{Circ, Mod, Port}
      val bundle = BundleT(Seq("ar", "aw", "w", "r", "b").map(n => field(n)))
      val mod    = Mod("Top", "module", Seq(Port("io", "output", bundle, None)), Nil, Nil, None)
      val design = IrWalk.design(Circ("Top", Seq(mod)))
      assert(design.bundles.contains("AXI4"))
    }

    test("Decoupled friendly-name fast-path is unchanged") {
      import ChirrtlParser.{Circ, Mod, Port}
      val bundle = BundleT(Seq(field("ready"), field("valid"), field("bits")))
      val mod    = Mod("Top", "module", Seq(Port("io", "output", bundle, None)), Nil, Nil, None)
      val design = IrWalk.design(Circ("Top", Seq(mod)))
      assert(design.bundles.keys.exists(_.startsWith("Decoupled_")))
    }

    test("ReadyValid friendly-name fast-path is unchanged") {
      import ChirrtlParser.{Circ, Mod, Port}
      val bundle = BundleT(Seq(field("ready"), field("valid")))
      val mod    = Mod("Top", "module", Seq(Port("io", "output", bundle, None)), Nil, Nil, None)
      val design = IrWalk.design(Circ("Top", Seq(mod)))
      assert(design.bundles.keys.exists(_.startsWith("ReadyValid_")))
    }
  }
}
