package socle.ui.model

import socle.schema._

/** A bus drawn into a logic block's typed pin (view mode): the module keeps one
  * wire per field (`io.in[0].valid` to `<box>.io_in[0].valid`), the schematic
  * draws them as the one declared port (`io.in: Vec[DecoupledIO, 3]`). What
  * a click on that bus inspects and follows: the port, not its field wires. */
object TypedBus {

  /** The bus: its end away from the block (the port as the Scala code names
    * it: `io.in`), the block's pin, the pin's type. */
  case class Bus(end: String, pin: String, bundle: String)

  private def under(p: String, root: String) = p.startsWith(root + ".") || p.startsWith(root + "[")

  /** The typed pin of a block an end is on, `<box>.<pin>`, and its type. */
  private def pinOf(e: String, d: Design): Option[(String, String)] = {
    val box = e.takeWhile(_ != '.')
    val pin = e.drop(box.length + 1).takeWhile(c => c != '.' && c != '[')
    d.regions.get(box).filter(_ => pin.nonEmpty)
      .flatMap(_.ports.find(p => p.name == pin && p.bundle.nonEmpty))
      .map(p => (s"$box.$pin", p.bundle))
  }

  /** The bus `sel` is either end of: the wires under `sel` that are a field of
    * a typed pin all carry it between one pin and one far end (the same field
    * under each). The port's other wires (to a `when` box) are drawn apart. */
  def of(mod: ModuleDef, d: Design, sel: String): Option[Bus] = {
    val ws = mod.wires.filter(w => under(w.from, sel) || under(w.to, sel))
    val buses = ws.map { w =>
      Seq(w.from -> w.to, w.to -> w.from).iterator.flatMap { case (e, other) =>
        pinOf(e, d).flatMap { case (pin, ty) =>
          val field = e.drop(pin.length)
          if (field.nonEmpty && other.endsWith(field)) {
            val far = other.dropRight(field.length)
            if (sel == pin || sel == far) Some(Bus(far, pin, ty)) else None
          } else None
        }
      }.nextOption()
    }
    buses.flatten.distinct match {
      case Seq(b) => Some(b)
      case _      => None
    }
  }
}
