package socle.ui.schematic

import utest._

/** The highlight's path matching: a path lights the paths under it and over it,
  * on a `.`, `[` or `~` boundary, never a sibling that merely shares its text. */
object PathIndexTest extends TestSuite {

  private val paths = Seq(
    "c", "c.io", "c.io.a", "c.io.a.bits", "c.io.ab", "c.io.ab.bits",
    "c.in", "c.in[1]", "c.in[1].x", "c.in[10]", "c.in[10].x",
    "core1", "core1.io.x", "core10", "core10.io.x",
    "r", "r~q", "r~in0", "r~in1", "r~in10",
    "_T~in0", "_T_1~in0",
    "when$m$x$1.cond", "when$m$x$10.cond",
  )
  private val idx = new PathIndex(paths.map(p => p -> p))

  private def under(k: String) = idx.under(k).toSet
  private def over(k: String)  = idx.over(k).toSet
  // The reference: the same questions answered by a scan with the predicate.
  private def underScan(k: String) = paths.filter(p => PathIndex.within(p, k)).toSet
  private def overScan(k: String)  = paths.filter(p => PathIndex.within(k, p)).toSet

  val tests = Tests {
    test("an exact path matches itself") {
      assert(PathIndex.within("c.io.a", "c.io.a"))
      assert(under("c.io.ab.bits") == Set("c.io.ab.bits"))
    }
    test("a parent lights its fields, elements and pins") {
      assert(under("c.io.a") == Set("c.io.a", "c.io.a.bits"))
      assert(under("c.in") == Set("c.in", "c.in[1]", "c.in[1].x", "c.in[10]", "c.in[10].x"))
      assert(under("core1") == Set("core1", "core1.io.x"))
      assert(under("r") == Set("r", "r~q", "r~in0", "r~in1", "r~in10"))
    }
    test("a field lights the paths over it") {
      assert(over("c.io.a.bits") == Set("c", "c.io", "c.io.a", "c.io.a.bits"))
      assert(over("c.in[10].x") == Set("c", "c.in", "c.in[10]", "c.in[10].x"))
      assert(over("r~in10") == Set("r", "r~in10"))
    }
    test("a sibling field is not lit") {
      assert(!PathIndex.within("c.io.ab", "c.io.a"))
      assert(!under("c.io.a").contains("c.io.ab"))
      assert(!over("c.io.ab").contains("c.io.a"))
    }
    test("a sibling element is not lit") {
      assert(!under("c.in[1]").contains("c.in[10]"))
      assert(!under("c.in[1]").contains("c.in[10].x"))
      assert(!over("c.in[10].x").contains("c.in[1]"))
    }
    test("a sibling box is not lit") {
      assert(under("core1") == Set("core1", "core1.io.x"))
      assert(!over("core10.io.x").contains("core1"))
      assert(under("_T") == Set("_T~in0"))
      assert(!under("when$m$x$1").contains("when$m$x$10.cond"))
    }
    test("a sibling pin is not lit") {
      assert(under("r~in1") == Set("r~in1"))
      assert(!over("r~in10").contains("r~in1"))
    }
    test("the index agrees with the predicate's scan") {
      val keys = paths ++ Seq("c.io.a.b", "c.in[", "core", "r~in", "x", "_T_1")
      keys.foreach { k =>
        assert(under(k) == underScan(k))
        assert(over(k) == overScan(k))
      }
    }
  }
}
