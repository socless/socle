package socless.lsp

import socless.capture.Construct
import utest._

object ScalaSourceIndexTest extends TestSuite {
  private def tree(files: (String, String)*): os.Path = {
    val dir = os.temp.dir(prefix = "socless-srcindex-")
    files.foreach { case (rel, text) => os.write(dir / os.RelPath(rel), text, createFolders = true) }
    dir
  }

  /** The 1-based (line, col) of the only occurrence of `token` in `text`. */
  private def at(text: String, token: String): (Int, Int) = {
    val i = text.indexOf(token)
    assert(i >= 0, text.indexOf(token, i + 1) < 0)
    val before = text.substring(0, i)
    (before.count(_ == '\n') + 1, i - before.lastIndexOf('\n'))
  }

  private def query(root: os.Path, cls: String, file: String, token: String): Option[Construct] = {
    val (line, col) = at(os.read(root / os.RelPath(file)), token)
    new ScalaSourceIndex(root).construct(cls, file, line, col)
  }

  private val ModFile = "src/main/scala/pkg/Mod.scala"
  private val mod =
    """package pkg
      |
      |import chisel3._
      |
      |class Mod(n: Int) extends Module {
      |  val a = Wire(UInt(8.W))
      |  val kOutside = a + 1.U
      |  for (b <- 0 until n) {
      |    val kFirst = a + b.U
      |    for (i <- 0 until n) {
      |      for (j <- 0 until n) {
      |        val kDeep = a + i.U + j.U
      |      }
      |    }
      |    val kLast = a - b.U
      |    when(a === 0.U) { val kInWhen = a + 1.U }
      |  }
      |  Helpers.tie(a)
      |}
      |
      |object ModUtil {
      |  def gen(a: UInt) = for (i <- 0 until 2) { val kSameFileObject = a + i.U }
      |}
      |""".stripMargin

  private val helpers =
    """package pkg
      |
      |object Helpers {
      |  def tie(a: UInt): Unit = for (i <- 0 until 4) { val kHelper = a + i.U }
      |}
      |""".stripMargin

  private val NamesFile = "src/main/scala/pkg/Names.scala"
  private val names =
    """package pkg
      |
      |class Names extends Module {
      |  xs.foreach { e => kForeach }
      |  xs.map(e => kMap)
      |  xs.flatMap { e => kFlatMap }
      |  xs.zip(ys).map { case (p, q) => kZipMap }
      |  xs.zipWithIndex.foreach { case (e, i) => kZipWithIndex }
      |  Seq.tabulate(4)(i => kTabulate)
      |  Seq.fill(4) { kFill }
      |  xs.reduce(_ max kReduceOne)
      |  xs.reduceTree((p, q) => kReduceTree)
      |  xs.fold(z)((p, q) => kFoldAll)
      |  xs.foldLeft(z) { (p, q) => kFoldLeft }
      |  xs.scanLeft(z)((p, q) => kScanLeft)
      |  VecInit(xs.map(e => kVecInitMap))
      |  when(c) { kWhen }
      |  doIt(e => kPlainLambda)
      |  block(DebugLayer) { xs.foreach { e => kInLayerBlock } }
      |}
      |""".stripMargin

  val tests = Tests {
    test("a position inside a for is that construct") {
      val root  = tree(ModFile -> mod)
      val first = query(root, "pkg.Mod", ModFile, "kFirst").get
      val last  = query(root, "pkg.Mod", ModFile, "kLast").get
      assert(first.label == "for (b <- 0 until n)", first.id == last.id, first == last)
      assert(first.source == socless.schema.SourceRef(ModFile, 8, 3))
    }

    test("nested loops resolve to the outermost") {
      val root = tree(ModFile -> mod)
      val deep = query(root, "pkg.Mod", ModFile, "kDeep").get
      assert(deep == query(root, "pkg.Mod", ModFile, "kFirst").get)
    }

    test("foreach, map, zip...map, zipWithIndex.foreach, tabulate, fill, reduce, reduceTree, foldLeft count; a plain call with a lambda does not") {
      val root = tree(NamesFile -> names)
      def label(token: String) = query(root, "pkg.Names", NamesFile, token).map(_.label)
      val counted = Seq(
        "kForeach"      -> "xs.foreach",
        "kMap"          -> "xs.map",
        "kFlatMap"      -> "xs.flatMap",
        "kZipMap"       -> "xs.zip(ys).map",
        "kZipWithIndex" -> "xs.zipWithIndex.foreach",
        "kTabulate"     -> "Seq.tabulate(4)",
        "kFill"         -> "Seq.fill(4)",
        "kReduceOne"    -> "xs.reduce",
        "kReduceTree"   -> "xs.reduceTree",
        "kFoldAll"      -> "xs.fold(z)",
        "kFoldLeft"     -> "xs.foldLeft(z)",
        "kScanLeft"     -> "xs.scanLeft(z)",
        "kVecInitMap"   -> "xs.map",
        "kInLayerBlock" -> "xs.foreach",
      )
      counted.foreach { case (token, prefix) =>
        val l = label(token)
        assert(l.exists(_.startsWith(prefix)))
      }
      assert(label("kWhen").isEmpty, label("kPlainLambda").isEmpty)
      // Distinct calls are distinct constructs.
      val ids = counted.map(c => query(root, "pkg.Names", NamesFile, c._1).get.id)
      assert(ids.distinct.size == ids.size)
    }

    test("a when inside a for resolves to the for") {
      val root = tree(ModFile -> mod)
      assert(query(root, "pkg.Mod", ModFile, "kInWhen") == query(root, "pkg.Mod", ModFile, "kFirst"))
      assert(query(root, "pkg.Mod", ModFile, "kInWhen").isDefined)
    }

    test("columns are 1-based and spans are exact") {
      // Literal positions, not derived through `at`. Line 1, columns:
      // `for` at 28, `kOne` at 55, the for's closing `}` at 64.
      val one  = "class One extends Module { for (i <- 0 until 2) { val kOne = i } }\n"
      val root = tree("One.scala" -> one)
      val idx  = new ScalaSourceIndex(root)
      def at(col: Int) = idx.construct("One", "One.scala", 1, col)
      val c = at(55)
      assert(c.map(_.label) == Some("for (i <- 0 until 2)"), c.map(_.source) == Some(socless.schema.SourceRef("One.scala", 1, 28)))
      assert(at(28) == c, at(27).isEmpty, at(64) == c, at(65).isEmpty)
    }

    test("a pathologically deep file gives None, never throws") {
      val deep = "class Deep { val x = " + "(" * 100000 + "1" + ")" * 100000 + " }\n"
      val root = tree("Deep.scala" -> deep)
      assert(new ScalaSourceIndex(root).construct("Deep", "Deep.scala", 1, 30).isEmpty)
    }

    test("a deep file in the workspace is cached as unparsable and does not hide inherited bodies") {
      val deep = "class Deep { val x = " + "(" * 100000 + "1" + ")" * 100000 + " }\n"
      val root = tree(
        "Deep.scala" -> deep,
        "src/main/scala/pkg/Base.scala" ->
          "package pkg\n\ntrait Base extends Module {\n  for (k <- 0 until 3) { val kInherited = k }\n}\n",
        "src/main/scala/pkg/Leaf.scala" -> "package pkg\n\nclass Leaf extends Base\n",
      )
      val idx = new ScalaSourceIndex(root)
      val (l, c) = at(os.read(root / "src/main/scala/pkg/Base.scala"), "kInherited")
      assert(idx.construct("pkg.Leaf", "src/main/scala/pkg/Base.scala", l, c).isDefined, idx.parseCount == 3)
      idx.refresh()
      assert(idx.construct("pkg.Leaf", "src/main/scala/pkg/Base.scala", l, c).isDefined, idx.parseCount == 3)
    }

    test("a long-lived index re-parses only edited files and sees new ones after refresh") {
      val base = "package pkg\n\ntrait Base extends Module {\n  for (k <- 0 until 3) { val kInherited = k }\n}\n"
      val root = tree(ModFile -> mod, "src/main/scala/pkg/Helpers.scala" -> helpers, "src/main/scala/pkg/Base.scala" -> base)
      val idx  = new ScalaSourceIndex(root)
      def q(cls: String, file: String, token: String) = {
        val (l, c) = at(os.read(root / os.RelPath(file)), token)
        idx.construct(cls, file, l, c)
      }
      def batch() = Seq(
        q("pkg.Mod", ModFile, "kFirst"),
        q("pkg.Mod", "src/main/scala/pkg/Helpers.scala", "kHelper"), // needs the workspace
      )
      val first = batch()
      assert(first(0).isDefined, first(1).isEmpty, idx.parseCount == 3)
      batch()
      idx.refresh()
      batch()
      assert(idx.parseCount == 3) // nothing changed: nothing re-parsed

      // Edit Mod: the next query sees it, and only Mod is re-parsed.
      val modPath = root / os.RelPath(ModFile)
      os.write.over(modPath, mod.replace("for (b <- 0 until n) {", "for (bb <- 0 until n) {"))
      os.mtime.set(modPath, os.mtime(modPath) + 2000)
      assert(q("pkg.Mod", ModFile, "kFirst").map(_.label) == Some("for (bb <- 0 until n)"), idx.parseCount == 4)

      // A new file is found after refresh.
      os.write(root / "src/main/scala/pkg/Leaf.scala", "package pkg\n\nclass Leaf extends Base\n")
      idx.refresh()
      assert(q("pkg.Leaf", "src/main/scala/pkg/Base.scala", "kInherited").isDefined, idx.parseCount == 5)
    }

    test("a position outside any loop is None") {
      val root = tree(ModFile -> mod)
      assert(query(root, "pkg.Mod", ModFile, "kOutside").isEmpty)
    }

    test("only the module's own class body counts") {
      val root = tree(ModFile -> mod, "src/main/scala/pkg/Helpers.scala" -> helpers)
      assert(query(root, "pkg.Mod", "src/main/scala/pkg/Helpers.scala", "kHelper").isEmpty)
      assert(query(root, "pkg.Mod", ModFile, "kSameFileObject").isEmpty)
      // The helper object's own loop is not a construct of an unrelated class either.
      assert(query(root, "pkg.Names", ModFile, "kFirst").isEmpty)
    }

    test("an inherited body counts") {
      val root = tree(
        "src/main/scala/pkg/Base.scala" ->
          """package pkg
            |
            |trait Base extends Module {
            |  for (k <- 0 until 3) { val kInherited = k }
            |}
            |""".stripMargin,
        "src/main/scala/pkg/Mid.scala"  -> "package pkg\n\nabstract class Mid extends Base\n",
        "src/main/scala/pkg/Leaf.scala" -> "package pkg\n\nclass Leaf extends Mid\n",
      )
      val c = query(root, "pkg.Leaf", "src/main/scala/pkg/Base.scala", "kInherited")
      assert(c.map(_.label) == Some("for (k <- 0 until 3)"))
      assert(query(root, "pkg.Other", "src/main/scala/pkg/Base.scala", "kInherited").isEmpty)
    }

    test("unreadable file gives None, never throws") {
      val root = tree(ModFile -> mod, "src/main/scala/pkg/Broken.scala" -> "class Broken { for (i <- ")
      val idx  = new ScalaSourceIndex(root)
      assert(
        idx.construct("pkg.Mod", "src/main/scala/pkg/Missing.scala", 3, 1).isEmpty,
        idx.construct("Broken", "src/main/scala/pkg/Broken.scala", 1, 20).isEmpty,
        idx.construct("pkg.Mod", ModFile, 9999, 1).isEmpty,
        idx.construct("pkg.Mod", ModFile, 9, 9999).isEmpty,
        idx.construct("pkg.Mod", "../../etc/passwd", 1, 1).isEmpty,
        idx.construct("", ModFile, 9, 9).isEmpty,
      )
    }

    test("a changed file is re-read") {
      val root  = tree(ModFile -> mod)
      val idx   = new ScalaSourceIndex(root)
      val (l, c) = at(mod, "kFirst")
      assert(idx.construct("pkg.Mod", ModFile, l, c).isDefined)
      val moved = mod.replace("for (b <- 0 until n) {", "if (true) {")
      os.write.over(root / os.RelPath(ModFile), moved)
      os.mtime.set(root / os.RelPath(ModFile), os.mtime(root / os.RelPath(ModFile)) + 2000)
      assert(idx.construct("pkg.Mod", ModFile, l, c).isEmpty)
    }
  }
}
