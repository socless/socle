package socless.lsp

import utest._

object FromCaptureTest extends TestSuite {
  private val repo = os.Path(sys.props("socless.root"))

  val tests = Tests {
    test("from-capture attaches the source diagram") {
      val out = os.temp.dir(prefix = "socless-fromcapture-") / "design.json"
      val rc  = FromCapture.run(Array(
        (repo / "socless" / "test" / "resources" / "captured" / "fsm.json").toString,
        "--output", out.toString,
        "--source-root", repo.toString,
      ))
      assert(rc == 0)
      val design = upickle.default.read[socless.schema.Design](os.read(out))
      val fsm = design.modules.flatMap(_.logic).find(_.id == "blk$TrafficFsm$fsm$state").flatMap(_.fsm).get
      assert(fsm.states.map(s => (s.name, s.initial)) == Seq("sRed" -> true, "sGreen" -> false, "sYellow" -> false))
      // each state's encoding in the register, for the waveform viewer's names
      assert(fsm.states.map(_.value) == Seq("0", "1", "2"))
      assert(fsm.transitions.map(t => (t.from, t.to, t.label)) ==
        Seq(("sRed", "sGreen", "io.tick"), ("sGreen", "sYellow", "io.tick"), ("sYellow", "sRed", "io.tick")))
    }
  }
}
