package socless.ui.schematic

import org.scalajs.dom
import socless.schema.{Design, ModuleDef}
import socless.ui.theme.Tokens
import socless.ui.model.RowModel
import socless.js.BrowserLayout.{LaidOut, LaidNodeR}
import socless.layout.LayoutCore

/** The pure geometry/model layer of the schematic (#216): the wire/box value
  * types and the side-effect-free helpers that turn a [[LaidOut]] + [[ModuleDef]]
  * into drawable [[Wire]]s. Split out of [[Schematic]] so it carries no Laminar
  * / `Var` dependency and can be reasoned about (and unit-tested) on its own; the
  * render half in [[Schematic]] imports these via `import SchematicGeom._`.
  *
  * Members are `private[schematic]`: shared with [[Schematic]] in the same
  * package, but not part of any public API. */
private[schematic] object SchematicGeom {

  final case class Wire(
    id: String, fromPath: String, toPath: String, fromInst: String, toInst: String,
    bundle: String, bidir: Boolean, points: Seq[(Double, Double)], dangling: Boolean = false,
  ) {
    def x1: Double = points.head._1; def y1: Double = points.head._2
    def x2: Double = points.last._1; def y2: Double = points.last._2
  }

  final case class BoxShift(shift: Map[String, Double], shiftAt: Double => Double, total: Double)

  final case class Geom(
    layout: LaidOut, mod: ModuleDef, design: Design,
    rows: Map[String, RowModel.BoxRows], wires: Seq[Wire], boxShift: BoxShift,
    canvasH: Double, c: Tokens.Consts, boxById: Map[String, LaidNodeR],
  )

  /** Drawable connections for the current module. Delegates to the shared
    * [[LayoutCore.displayWires]] so the schematic's wire set is folded by the
    * SAME anti-parallel pin-pair rule the layout routes by, and the two cannot
    * drift. The old node-head fold lumped every per-path boundary pin under one
    * `io` node and dropped real connections (issue #258). */
  def displayWires(mod: ModuleDef, expanded: Set[String]): Seq[LayoutCore.DisplayWire] = LayoutCore.displayWires(mod, expanded)

  /** Restore strict orthogonality: any diagonal segment becomes an H-V-H jog
    * through its horizontal midpoint (JS orthogonalizePts). */
  def orthogonalize(pts: Seq[(Double, Double)]): Seq[(Double, Double)] = {
    if (pts.isEmpty) return pts
    val out = scala.collection.mutable.ListBuffer(pts.head)
    for (i <- 1 until pts.length) {
      val (x0, y0) = out.last; val (x1, y1) = pts(i)
      if (x0 == x1 && y0 == y1) {} // drop exact duplicate
      else {
        if (x0 != x1 && y0 != y1) { val mx = (x0 + x1) / 2; out += ((mx, y0)); out += ((mx, y1)) }
        out += ((x1, y1))
      }
    }
    out.toSeq
  }

  def ownerBoxId(boxById: Map[String, LaidNodeR], path: String): String =
    if (boxById.contains(path)) path else path.split('.').headOption.getOrElse(path)

  /** The glyph shown in an op node's box, chosen from its CHIRRTL primop. Unknown
    * ops fall back to the raw op text so nothing renders blank. */
  def opGlyph(op: String): String = op match {
    case "add"  => "+"
    case "sub"  => "-"
    case "mul"  => "×"
    case "div"  => "÷"
    case "rem"  => "%"
    case "neg"  => "-"
    case "lt"   => "<"
    case "leq"  => "≤"
    case "gt"   => ">"
    case "geq"  => "≥"
    case "eq"   => "="
    case "neq"  => "≠"
    case "and"  => "&"
    case "or"   => "|"
    case "xor"  => "^"
    case "not"  => "~"
    case "andr" => "&r"
    case "orr"  => "|r"
    case "xorr" => "^r"
    case "shl" | "dshl" => "<<"
    case "shr" | "dshr" => ">>"
    case "mux"  => "mux"
    case other  => if (other.nonEmpty) other else "?"
  }

  def computeBoxShift(layout: LaidOut, rows: Map[String, RowModel.BoxRows]): BoxShift = {
    val raw = layout.nodes.flatMap { b =>
      rows.get(b.id).filter(_.boxH > b.h).map(br => (b.y + b.h).toDouble -> (br.boxH - b.h))
    }
    val byBottom = raw.groupBy(_._1).map { case (bottom, gs) => bottom -> gs.map(_._2).max }
    val inserts  = byBottom.toSeq
    def shiftAt(y: Double): Double = inserts.collect { case (bottom, delta) if y >= bottom - 1 => delta }.sum
    val shift = layout.nodes.map(b => b.id -> shiftAt(b.y.toDouble)).filter(_._2 > 0).toMap
    BoxShift(shift, shiftAt, inserts.map(_._2).sum)
  }

  /** The box-relative anchor X of `path` on `box`: the pin's own x if the pin is
    * laid out, else the box edge for the side the pin sits on (WEST = left,
    * EAST = right). Used to synthesize a terminal for a wire whose ELK edge is
    * missing (issue #188 follow-up). */
  def anchorX(box: LaidNodeR, path: String): Double =
    box.ports.find(_.path == path).map(_.x.toDouble).getOrElse {
      box.ports.find(p => path.startsWith(p.path)).map(_.x.toDouble).getOrElse(box.x + box.w / 2.0)
    }

  def buildWires(dwires: Seq[LayoutCore.DisplayWire], layout: LaidOut, boxById: Map[String, LaidNodeR],
                 pinYMap: Map[String, Map[String, Double]], boxShift: BoxShift,
                 hiddenIds: Set[String] = Set.empty): Seq[Wire] = {
    val edgeByKey = layout.edges.map(e => (e.from + " " + e.to) -> e).toMap
    // Routed points for a display wire's pin pair, oriented source->sink to match
    // this wire's (from = sink, to = source). The display fold and the layout
    // fold pick the same anti-parallel representative in lockstep, but a vote tie
    // could orient an anti-parallel pair the other way; tolerate that by also
    // matching the reversed edge and flipping its points so terminal anchoring
    // stays correct (issue #258).
    def routeFor(from: String, to: String): Option[Seq[(Int, Int)]] =
      edgeByKey.get(from + " " + to).map(_.points)
        .orElse(edgeByKey.get(to + " " + from).map(_.points.reverse))

    // Box-relative pin Y for `path` on `inst`. When the exact path is not a laid
    // pin (e.g. the wire targets `io.in.bits.a` while the `io` bundle is
    // collapsed), walk UP the path to the nearest ancestor pin that IS laid out
    // (the collapsed bundle parent) instead of letting the caller fall back to
    // the stale routed coordinate (issue #188 follow-up 2b). The exact-path case
    // is unchanged, so already-correct wires keep their pin Y.
    def pinYOf(inst: String, path: String): Option[Double] = {
      val byPath = pinYMap.getOrElse(inst, Map.empty)
      def up(p: String): Option[Double] =
        byPath.get(p).orElse {
          val i = p.lastIndexOf('.'); if (i < 0) None else up(p.substring(0, i))
        }
      up(path)
    }

    // The best-known absolute anchor point (x, y) for an endpoint, used to
    // synthesize a straight segment when the ELK edge is missing. Resolves the
    // owning box, then its pin Y (exact pin) or the box's vertical center.
    def anchorOf(path: String): Option[(Double, Double)] = {
      val inst = path.split('.').headOption.getOrElse("")
      boxById.get(inst).map { box =>
        val shift = boxShift.shift.getOrElse(ownerBoxId(boxById, path), 0.0)
        val ay    = pinYOf(inst, path).map(box.y + shift + _).getOrElse(box.y + shift + box.h / 2.0)
        (anchorX(box, path), ay)
      }
    }

    dwires.zipWithIndex.flatMap { case (w, i) =>
      val fromInst = w.from.split('.').headOption.getOrElse("")
      val toInst   = w.to.split('.').headOption.getOrElse("")
      routeFor(w.from, w.to).filter(_.length >= 2) match {
        case Some(ptsInt) =>
          // Routed path present: keep the ELK polyline, retargeting only the
          // terminal Y values onto the box pins (unchanged behavior).
          val pts     = ptsInt.map { case (x, y) => (x.toDouble, y.toDouble) }
          val fromBox = boxById.get(fromInst); val toBox = boxById.get(toInst)
          val fromShift = boxShift.shift.getOrElse(ownerBoxId(boxById, w.from), 0.0)
          val toShift   = boxShift.shift.getOrElse(ownerBoxId(boxById, w.to), 0.0)
          // pts run source->sink: pts.head = w.to (source), pts.last = w.from (sink).
          val toPinY = pinYOf(toInst, w.to) match {
            case Some(y) => toBox.map(_.y + toShift + y).getOrElse(pts.head._2)
            case None    => pts.head._2 + toShift
          }
          val fromPinY = pinYOf(fromInst, w.from) match {
            case Some(y) => fromBox.map(_.y + fromShift + y).getOrElse(pts.last._2)
            case None    => pts.last._2 + fromShift
          }
          val moved = pts.zipWithIndex.map { case ((x, y), idx) =>
            if (idx == 0) (x, toPinY)
            else if (idx == pts.length - 1) (x, fromPinY)
            else (x, y + boxShift.shiftAt(y))
          }
          Some(Wire(s"w$i", w.from, w.to, fromInst, toInst, w.bundle, w.bidir, orthogonalize(moved)))
        case None =>
          // No routed edge: surface the connection instead of dropping it
          // silently (issue #188 follow-up 2a). Synthesize a straight segment
          // between the best-known anchors, marking it dangling so it renders
          // with a distinct stroke + end marker. If neither anchor resolves,
          // warn (logged, not silently gone) and skip.
          (anchorOf(w.to), anchorOf(w.from)) match {
            case (Some(src), Some(sink)) =>
              Some(Wire(s"w$i", w.from, w.to, fromInst, toInst, w.bundle, w.bidir,
                orthogonalize(Seq(src, sink)), dangling = true))
            case (Some(src), None) =>
              Some(Wire(s"w$i", w.from, w.to, fromInst, toInst, w.bundle, w.bidir,
                Seq(src, (src._1 + 24.0, src._2)), dangling = true))
            case (None, Some(sink)) =>
              Some(Wire(s"w$i", w.from, w.to, fromInst, toInst, w.bundle, w.bidir,
                Seq((sink._1 - 24.0, sink._2), sink), dangling = true))
            case (None, None) =>
              // Both endpoints gone. Under declutter (#223) this is expected when
              // a wire ran between two now-hidden nodes - drop it silently. Only
              // warn for a genuinely-unexpected anchorless wire.
              if (!(hiddenIds.contains(LayoutCore.nodeBaseId(fromInst)) || hiddenIds.contains(LayoutCore.nodeBaseId(toInst))))
                dom.console.warn(s"[socless] wire dropped, no anchor for ${w.from} -> ${w.to}")
              None
          }
      }
    }
  }

  def bakedOf(d: Design, mod: ModuleDef): Option[LaidOut] = {
    val key = d.layoutOf.getOrElse(mod.name, mod.name)
    d.layouts.get(key).map(LaidOut.fromBaked)
  }

  // SVG path data for the wire polyline.
  def routePath(pts: Seq[(Double, Double)]): String =
    if (pts.length >= 2) "M " + pts.map { case (x, y) => s"$x $y" }.mkString(" L ")
    else ""

  /** Subsequence fuzzy test: does `needle` appear in `hay` in order? (JS fuzzyHas) */
  def fuzzyHas(hay: String, needle: String): Boolean = {
    if (needle.isEmpty) return true
    var i = 0
    hay.foreach(ch => if (i < needle.length && ch == needle(i)) i += 1)
    i == needle.length
  }
}
