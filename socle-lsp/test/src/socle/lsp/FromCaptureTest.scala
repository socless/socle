package socle.lsp

import utest._

object FromCaptureTest extends TestSuite {
  private val repo = os.Path(sys.props("socle.root"))

  val tests = Tests {
    test("from-capture attaches the source diagram") {
      val out = os.temp.dir(prefix = "socle-fromcapture-") / "design.json"
      val rc  = FromCapture.run(Array(
        (repo / "socle" / "test" / "resources" / "captured" / "fsm.json").toString,
        "--output", out.toString,
        "--source-root", repo.toString,
      ))
      assert(rc == 0)
      val design = upickle.default.read[socle.schema.Design](os.read(out))
      val fsm = design.modules.flatMap(_.logic).find(_.id == "fsm$TrafficFsm$15_17").flatMap(_.fsm).get
      assert(fsm.states.map(s => (s.name, s.initial)) == Seq("sRed" -> true, "sGreen" -> false, "sYellow" -> false))
      // each state's encoding in the register, for the waveform viewer's names
      assert(fsm.states.map(_.value) == Seq("0", "1", "2"))
      assert(fsm.transitions.map(t => (t.from, t.to, t.label)) ==
        Seq(("sRed", "sGreen", "io.tick"), ("sGreen", "sYellow", "io.tick"), ("sYellow", "sRed", "io.tick")))
    }
  }
}
