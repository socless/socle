package socless.schema

import utest._

/** The scaladoc of a module and of its port fields, read from the embedded
  * sources (the Inspector and the pin cards show it). */
object ScaladocTest extends TestSuite {

  private val file = "src/Top.scala"
  private val text =
    """package demo
      |
      |/** A request payload. */
      |class Req extends Bundle {
      |  /** The byte address. */
      |  val addr = UInt(32.W)
      |  val data = UInt(32.W)
      |}
      |
      |/** Decodes one instruction.
      |  *
      |  * Second paragraph.
      |  */
      |@instantiable
      |class Decode extends Module {
      |  val io = IO(new Bundle {
      |    /** The fetched instruction. */
      |    val in  = Flipped(Decoupled(new Req))
      |    // a plain comment is not a doc
      |    val out = Output(UInt(8.W))
      |  })
      |}
      |
      |class Bare extends Module {
      |  val io = IO(new Bundle { val x = Input(Bool()) })
      |}
      |""".stripMargin

  private def lineOf(s: String) = text.linesIterator.indexWhere(_.contains(s)) + 1

  private val decode = ModuleDef("Decode", "Decode", "", SourceRef(file, lineOf("class Decode"), 7),
    Seq(PortDef("io", "out", "Io", "", SourceRef(file, lineOf("val io = IO(new Bundle {"), 14))), Nil, Nil, Metrics(0, 0, 0))
  private val bare = ModuleDef("Bare", "Bare", "", SourceRef(file, lineOf("class Bare"), 7),
    Seq(PortDef("io", "out", "Io2", "", SourceRef.unknown)), Nil, Nil, Metrics(0, 0, 0))
  private val design = Design("Decode", Map.empty, Seq(decode, bare), sources = Map(file -> text))

  val tests = Tests {
    test("a module's doc, past an annotation") {
      assert(Scaladoc.ofModule(decode, design) == "Decodes one instruction.\n\nSecond paragraph.")
    }
    test("a module with no doc, or no source, has none") {
      assert(Scaladoc.ofModule(bare, design) == "")
      assert(Scaladoc.ofModule(decode, design.copy(sources = Map.empty)) == "")
    }
    test("a declared doc wins") {
      assert(Scaladoc.ofModule(decode.copy(doc = "Given."), design) == "Given.")
    }
    test("a field of the io bundle") {
      assert(Scaladoc.ofField(decode, Seq("io", "in"), design) == "The fetched instruction.")
      assert(Scaladoc.ofField(decode, Seq("io", "out"), design) == "")
    }
    test("a field of a named bundle, through a Decoupled's bits") {
      assert(Scaladoc.ofField(decode, Seq("io", "in", "bits", "addr"), design) == "The byte address.")
      assert(Scaladoc.ofField(decode, Seq("io", "in", "bits", "data"), design) == "")
    }
    test("a port with no source ref is found in its module's body") {
      assert(Scaladoc.ofField(bare, Seq("io", "x"), design) == "")
      assert(Scaladoc.ofField(decode.copy(ports = decode.ports.map(_.copy(source = SourceRef.unknown))), Seq("io", "in"), design) ==
        "The fetched instruction.")
    }
    test("an unknown field is no doc, never an error") {
      assert(Scaladoc.ofField(decode, Seq("io", "nope"), design) == "")
      assert(Scaladoc.ofField(decode, Seq("zz"), design) == "")
    }
  }
}
