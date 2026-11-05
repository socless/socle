package socle.ir

import socle.schema._
import socle.parse._
import socle.layout._

import utest._

/** A read of one element of a Vec register comes out of the register's Q pin:
  * IrWalk names it `v~q[i]` (the `~q` right after the register name, before
  * any index or field), and the layout puts that end on the register's EAST
  * side. It used to be `v[i]~q`, which the layout read as the D pin `v`. */
object VecRegReadTest extends TestSuite {
  private def designOf(s: String): Design = IrWalk.design(ChirrtlParser.parse(s))

  private val vecOfBool =
    """circuit M :
      |  module M :
      |    input clock : Clock
      |    input io_in : UInt<1>
      |    output io_out : UInt<1>
      |    reg v : UInt<1>[8], clock
      |    connect v[1], io_in
      |    connect io_out, v[0]
      |""".stripMargin

  private val vecOfBundle =
    """circuit M :
      |  module M :
      |    input clock : Clock
      |    input io_in : UInt<8>
      |    output io_out : UInt<8>
      |    reg w : { a : UInt<8>, b : UInt<4>}[2], clock
      |    connect w[1].a, io_in
      |    connect io_out, w[0].a
      |""".stripMargin

  /** The (node, side) the source end of the wire into `sink` lands on, in the
    * prepared layout graph. */
  private def sourcePin(d: Design, sink: String): (String, String) = {
    val m = d.modules.head
    val p = LayoutCore.prepare(m, d)
    val e = p.edges.find(_.fromPath.startsWith(sink)).getOrElse(sys.error(s"no edge into $sink: ${p.edges}"))
    val pins = p.nodes.flatMap(n => n.pins.map(pin => pin.path -> (n.id, pin.side))).toMap
    pins.getOrElse(e.toPath, sys.error(s"no pin ${e.toPath}: $pins"))
  }

  val tests = Tests {
    test("a Vec register element read is the Q pin v~q[i]") {
      val d = designOf(vecOfBool)
      assert(sourcePin(d, "io_out") == ("v" -> PinSide.East))
      val ws = d.modules.head.wires.map(w => w.from -> w.to).toSet
      assert(ws.contains("io_out" -> "v~q[0]"))
      assert(ws.contains("v[1]" -> "io_in"))
      val m = d.modules.head
      val q = LayoutCore.classify("v~q[0]", m, d).get
      assert(q.nodeId == "v", q.kind == NodeKind.Reg, q.side == PinSide.East)
    }
    test("a Vec-of-bundle register field read is the Q pin v~q[i].a") {
      val d = designOf(vecOfBundle)
      assert(sourcePin(d, "io_out") == ("w" -> PinSide.East))
      // A register end coalesces to its element, as before (`w[0]~q` then).
      val ws = d.modules.head.wires.map(w => w.from -> w.to).toSet
      assert(ws.contains("io_out" -> "w~q[0]"))
      val m = d.modules.head
      val q = LayoutCore.classify("w~q[0].a", m, d).get
      assert(q.nodeId == "w", q.kind == NodeKind.Reg, q.side == PinSide.East)
      // A field end `w~q[0].a` lands on the deepest open Q row, on EAST.
      val fieldRead = m.copy(wires = m.wires.map(w => if (w.from == "io_out") w.copy(to = "w~q[0].a") else w))
      val open = LayoutCore.prepare(fieldRead, d, Set("w~q", "w~q[0]"))
      val e = open.edges.find(_.fromPath == "io_out").get
      assert(e.toPath == "w~q[0].a")
      val pin = open.nodes.find(_.id == "w").get.pins.find(_.path == "w~q[0].a").get
      assert(pin.side == PinSide.East)
    }
    test("stripQ and baseOf read the Q pin of a Vec element") {
      assert(Synthetic.stripQ("v~q[0]") == "v[0]")
      assert(Synthetic.stripQ("r~q") == "r")
      assert(Synthetic.baseOf("v~q[0]") == "v")
      assert(IrWalk.fsmHeadBase("v~q[0].a") == "v")
    }
  }
}
