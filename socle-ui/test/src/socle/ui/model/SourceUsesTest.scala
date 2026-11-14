package socle.ui.model

import utest._

/** The Source tab's lit lines: a name in a comment or a string is not a use. */
object SourceUsesTest extends TestSuite {
  private def lit(src: String, names: String*): Seq[Int] =
    SourceUses.lines(SourceUses.codeOnly(src.split("\n", -1).toIndexedSeq), names)

  val tests = Tests {
    test("the instantiation line and real uses are lit") {
      val src =
        """  val cluster1 = Module(new Cluster(nCores = 2))
          |  cluster1.io.in  <> pipe.io.deq
          |  io.out          <> cluster1.io.out""".stripMargin
      assert(lit(src, "cluster1") == Seq(1, 2, 3))
    }
    test("a line comment is not a use") {
      val src =
        """  val cluster1 = Module(new Cluster(nCores = 2))
          |  // Pipe: top-in -> cluster0 -> FIFO -> cluster1 -> top-out
          |  cluster1.io.in  <> pipe.io.deq""".stripMargin
      assert(lit(src, "cluster1") == Seq(1, 3))
    }
    test("a trailing comment does not light the line, its code does") {
      val src =
        """  x := y // cluster1 feeds x
          |  x := cluster1.io.out // cluster1 feeds x""".stripMargin
      assert(lit(src, "cluster1") == Seq(2))
    }
    test("a block comment over several lines is not a use") {
      val src =
        """  /* cluster1 is
          |     cluster1 again */ val a = cluster0
          |  val b = cluster1 /* cluster1 */
          |  val c = 1 /* cluster1 */ + 2""".stripMargin
      assert(lit(src, "cluster1") == Seq(3))
      assert(lit(src, "cluster0") == Seq(2))
    }
    test("block comments nest") {
      val src =
        """  /* outer /* inner */ still cluster1 */
          |  val b = cluster1""".stripMargin
      assert(lit(src, "cluster1") == Seq(2))
    }
    test("a Scaladoc is not a use") {
      val src =
        """/** Top-level SoC: data flows in -> cluster0 -> cluster1
          |  * -> out; cluster1 is the second.
          |  */
          |class DeepSoc extends Module {
          |  val cluster1 = Module(new Cluster(nCores = 2))""".stripMargin
      assert(lit(src, "cluster1") == Seq(5))
    }
    test("a // inside a string literal does not start a comment") {
      val src =
        """  val url = "http://x"; val a = cluster1.io.out
          |  val q = '"'; val e = '\''; val b = cluster1
          |  val s = "a \" // b"; val c = cluster1""".stripMargin
      assert(lit(src, "cluster1") == Seq(1, 2, 3))
    }
    test("a name inside a string literal is not a use") {
      val q3 = "\"\"\""
      val src = Seq(
        """  printf("cluster1 done\n")""",
        s"  val t = s${q3}cluster1",
        s"  cluster1 again$q3; val d = cluster1").mkString("\n")
      assert(lit(src, "cluster1") == Seq(3))
    }
    test("a port path is matched as a substring of the code") {
      val src =
        """  cluster1.io.in  <> pipe.io.deq
          |  // cluster1.io.in is fed by the FIFO""".stripMargin
      assert(lit(src, "cluster1.io") == Seq(1))
    }
  }
}
