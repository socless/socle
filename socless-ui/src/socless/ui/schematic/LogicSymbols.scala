package socless.ui.schematic

import socless.ui.theme._
import com.raquo.laminar.api.L._
import com.raquo.laminar.nodes.ReactiveElement
import com.raquo.laminar.codecs.StringAsIsCodec

/** Conventional schematic symbols for combinational ops and registers
  * (issue #203). Pure `familyOf` classification plus Laminar-SVG shape builders.
  *
  * The node keeps its laid footprint (Approach A: pins never move), but the
  * symbol is drawn at a COMPACT size centered in the node, with short stubs
  * running out to the WEST input pins and the EAST output pin - the way a real
  * schematic has wires running to a small gate, rather than a shape stretched to
  * fill the box. */
object LogicSymbols {
  private def sa(name: String) = svg.svgAttr(name, StringAsIsCodec, scala.None)

  sealed trait Family
  case object Mux   extends Family
  case object Gate  extends Family
  case object Block extends Family

  /** Symbol family for a combinational op kind. Registers are not ops; they use
    * `ff` directly. Unknown ops fall back to `Block` (labelled operator block). */
  def familyOf(op: String): Family = op match {
    case "mux"                        => Mux
    case "and" | "or" | "xor" | "not" => Gate
    case _                            => Block
  }

  /** Short DOM tag for assertions/tooling: "mux" | "gate" | "block". */
  def tag(op: String): String = familyOf(op) match {
    case Mux => "mux"; case Gate => "gate"; case Block => "block"
  }

  private val MinBodyH = 22.0

  /** Compact body geometry centered in the node, with body height at least
    * `MinBodyH` so a single-row node does not collapse to a thin bar. */
  private final case class Body(xL: Double, xR: Double, top: Double, bot: Double, cy: Double)
  private def bodyOf(w: Double, bodyW: Double, inYs: Seq[Double], outY: Double): Body = {
    // The output pin is laid at the vertical center of the inputs (LayoutCore),
    // so centering on all pins keeps inputs equidistant and the output straight.
    val ys  = if (inYs.nonEmpty) inYs :+ outY else Seq(outY)
    val lo  = ys.min; val hi = ys.max
    val cy  = (lo + hi) / 2
    val bh  = math.max(hi - lo, MinBodyH)
    val xL  = math.max(2.0, (w - bodyW) / 2)
    Body(xL, xL + bodyW, cy - bh / 2, cy + bh / 2, cy)
  }

  /** Short orthogonal stubs from the WEST pins (x=0) to the body left edge and
    * from the body right edge to the EAST pin (x=w). */
  private def stubs(w: Double, inYs: Seq[Double], outY: Double, b: Body, stroke: String): Seq[ReactiveElement.Base] = {
    val st  = s"stroke:$stroke;stroke-width:1.2;fill:none"
    val ins = inYs.map(y => svg.path(sa("d") := s"M 0 $y L ${b.xL} $y", sa("style") := st))
    // The body is centered on the output (bodyOf), so the output leaves the
    // symbol straight to the EAST pin - only a horizontal line (#230).
    val out = svg.path(sa("d") := s"M ${b.xR} $outY L $w $outY", sa("style") := st)
    ins :+ out
  }

  private def frame(w: Double, h: Double, children: Seq[ReactiveElement.Base]): ReactiveElement.Base =
    svg.svg(
      sa("style") := s"position:absolute;left:0;top:0;width:${w}px;height:${h}px;overflow:visible;pointer-events:none",
      children,
    )

  private def fillStroke(stroke: String) = s"fill:${Tokens.panel};stroke:$stroke;stroke-width:1.4;stroke-linejoin:round"

  /** Mux trapezoid: data inputs on the tall left edge, the select entering the
    * TOP of the symbol (issue #225). in0 is the select (see LogicLabels.inRole),
    * so it is split off and routed over the top instead of sitting as another
    * left input; the data inputs (in1, in2) shape the body. */
  def mux(w: Double, h: Double, inYs: Seq[Double], outY: Double, stroke: String): ReactiveElement.Base = {
    val (selY, dataYs) = inYs match {
      case sel +: rest if rest.nonEmpty => (Some(sel), rest)
      case _                            => (None, inYs)
    }
    val b = bodyOf(w, 30.0, dataYs, outY)
    val k = math.min((b.bot - b.top) / 2 - 2, 9.0)
    val d = s"M ${b.xL} ${b.top} L ${b.xR} ${b.cy - k} L ${b.xR} ${b.cy + k} L ${b.xL} ${b.bot} Z"
    val st = s"stroke:$stroke;stroke-width:1.2;fill:none"
    // Select: from its west pin, over to the symbol's horizontal center, then
    // straight down into the mid of the slanted top edge.
    val selStub = selY.toSeq.map { y =>
      val ex = (b.xL + b.xR) / 2
      val ey = (b.top + (b.cy - k)) / 2
      svg.path(sa("d") := s"M 0 $y L $ex $y L $ex $ey", sa("style") := st)
    }
    frame(w, h, (svg.path(sa("d") := d, sa("style") := fillStroke(stroke)) +: stubs(w, dataYs, outY, b, stroke)) ++ selStub)
  }

  /** Logic gate. `kind` in {and,or,xor,not}; `negated` adds an output bubble. */
  def gate(kind: String, negated: Boolean, w: Double, h: Double, inYs: Seq[Double], outY: Double, stroke: String): ReactiveElement.Base = {
    val b = bodyOf(w, 30.0, inYs, outY)
    val xL = b.xL; val xR = b.xR; val top = b.top; val bot = b.bot; val midY = b.cy
    val body = kind match {
      case "and" =>
        s"M $xL $top L ${(xL + xR) / 2} $top A ${(xR - xL) / 2} ${(bot - top) / 2} 0 0 1 ${(xL + xR) / 2} $bot L $xL $bot Z"
      case "or" | "xor" =>
        s"M $xL $top Q ${xL + 9} $midY $xL $bot Q ${(xL + xR) / 2} $bot $xR $midY Q ${(xL + xR) / 2} $top $xL $top Z"
      case _ /* not */ =>
        s"M $xL $top L $xR $midY L $xL $bot Z"
    }
    val bodyEl    = svg.path(sa("d") := body, sa("style") := fillStroke(stroke))
    val extraBack = if (kind == "xor") Seq(svg.path(sa("d") := s"M ${xL - 5} $top Q ${xL + 4} $midY ${xL - 5} $bot",
      sa("style") := s"fill:none;stroke:$stroke;stroke-width:1.4")) else Nil
    val bubble    = if (kind == "not" || negated) Seq(svg.circle(sa("cx") := (xR + 3).toString, sa("cy") := midY.toString,
      sa("r") := "3", sa("style") := fillStroke(stroke))) else Nil
    frame(w, h, (bodyEl +: extraBack) ++ bubble ++ stubs(w, inYs, outY, b, stroke))
  }

  /** Labelled operator block: rounded rect carrying the op glyph. */
  def opBlock(glyph: String, w: Double, h: Double, inYs: Seq[Double], outY: Double, stroke: String): ReactiveElement.Base = {
    val bw = math.max(30.0, glyph.length * 7.0 + 14)
    val b  = bodyOf(w, bw, inYs, outY)
    val rect = svg.rect(sa("x") := b.xL.toString, sa("y") := b.top.toString,
      sa("width") := (b.xR - b.xL).toString, sa("height") := (b.bot - b.top).toString,
      sa("rx") := "4", sa("style") := fillStroke(stroke))
    val text = svg.text(sa("x") := ((b.xL + b.xR) / 2).toString, sa("y") := (b.cy + 4).toString,
      sa("text-anchor") := "middle",
      sa("style") := s"fill:$stroke;font-family:${Tokens.mono};font-size:12px;font-weight:600", glyph)
    frame(w, h, Seq(rect, text) ++ stubs(w, inYs, outY, b, stroke))
  }

  /** Register flip-flop: a tall (higher-than-wide) rectangle with the clock mark
    * as an upward chevron ("/\") at the bottom-center edge (issue #203). */
  def ff(w: Double, h: Double, inYs: Seq[Double], outY: Double, stroke: String): ReactiveElement.Base = {
    // Tall body centered on the terminal Ys; expands vertically to encapsulate
    // every D/Q pin so a register bank (a bundle stored in a register) is drawn
    // as one box around all its signals rather than a fixed-height symbol.
    val bw = 22.0
    val ys = if (inYs.nonEmpty) inYs :+ outY else Seq(outY)
    val bh = math.max(34.0, (ys.max - ys.min) + 16.0)
    val cy = (ys.min + ys.max) / 2
    val xL = math.max(2.0, (w - bw) / 2); val xR = xL + bw
    val top = cy - bh / 2; val bot = cy + bh / 2
    val b = Body(xL, xR, top, bot, cy)
    val rect = svg.rect(sa("x") := xL.toString, sa("y") := top.toString,
      sa("width") := bw.toString, sa("height") := bh.toString,
      sa("rx") := "2", sa("style") := fillStroke(stroke))
    // Clock mark: an upward chevron centered on the bottom edge.
    val cx = (xL + xR) / 2
    val clk = svg.path(sa("d") := s"M ${cx - 5} ${bot} L $cx ${bot - 6} L ${cx + 5} ${bot}",
      sa("style") := s"fill:none;stroke:$stroke;stroke-width:1.3;stroke-linejoin:round")
    frame(w, h, Seq(rect, clk) ++ stubs(w, inYs, outY, b, stroke))
  }
}
