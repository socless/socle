package socle.capture

import socle.ir.{IrWalk, Synthetic}
import socle.schema._
import scala.collection.mutable

/** A set of logic nodes of one module shown as a single box. `kind` is
  * `NodeKind.Fsm` or `NodeKind.Block`; `members` are ids from `ModuleDef.logic`;
  * `state` is an FSM box's state register (`LogicNodeDef.state`). */
case class Group(
  id:      String,
  kind:    String,
  label:   String,
  source:  SourceRef,
  layer:   Boolean,
  members: Set[String],
  state:   String = "",
)

/** Pure pass: collapse each group's member nodes into one box node with pins,
  * rewiring the module's wires, and return the group bodies keyed by group id.
  * A pin is named after the endpoint that DRIVES its wire (see [[driverPin]]),
  * unless a [[PinOverride]] names it. */
object LogicBlocks {

  /** Names a box pin from its wire's driver endpoint and its external
    * endpoint (the end outside the group: the driver for an in pin, the sink
    * for an out pin), given the ids of the boxes (fsm, block) the module holds
    * so far: `(key, name)`, where wires with the same key share one pin. */
  type PinNamer = (String, String, Set[String]) => (String, String)

  /** Names a pin the caller recognises from its wire's member end, external end
    * and direction (`PortDir.In` / `Out`): `Some((key, name, label))`. These
    * pins claim their names before the others; `None` leaves the pin to the
    * [[PinNamer]]. The name it gives is a base: a name the box already uses
    * gets `_2`, `_3`..., and `onNamed(group id, key, final name)` reports it. */
  type PinOverride = (String, String, String) => Option[(String, String, String)]

  /** The driver-side pin: a register read `X~q` names `X`, an op output `X`
    * names `X`, a box pin `<box>.<pin>` names `<pin>`, a port or an instance
    * keeps its path. A trailing `[i]` is dropped, except on a register
    * element read (`v~q[0]` names `v_0`, as before the `~q` moved ahead of
    * the index); `head` renames the head of a
    * non-box driver (the key carries the renamed path). The name maps every
    * non-[A-Za-z0-9_] char to `_`, collapses `__` and trims a trailing `_`. */
  def driverPin(driver: String, boxes: Set[String], head: String => String = identity): (String, String) = {
    // A register element read `v~q[0]` keeps its index (pin `v_0`, one per element).
    val qRead = stripIdx(driver.takeWhile(_ != '.')).endsWith(Synthetic.QSuffix)
    val path  = if (qRead) driver else driver.replaceAll("""\[[^\]]*\]$""", "")
    val parts = path.split('.').toList
    val (key, shown) = parts match {
      case h :: pin if pin.nonEmpty && boxes.contains(stripIdx(h)) => (path, pin.mkString("."))
      case h :: rest =>
        val renamed = (head(Synthetic.stripQ(h)) :: rest).mkString(".")
        (renamed, renamed)
      case Nil => (path, path)
    }
    val name = shown.replaceAll("[^A-Za-z0-9_]", "_").replaceAll("_{2,}", "_").replaceAll("_+$", "")
    (key, if (name.isEmpty) "pin" else name)
  }

  private def stripIdx(s: String): String = s.takeWhile(_ != '[')

  def collapse(module: ModuleDef, groups: Seq[Group],
               pinName: PinNamer = (driver, _, boxes) => driverPin(driver, boxes),
               pinOverride: Option[PinOverride] = None,
               onNamed: (String, String, String) => Unit = (_, _, _) => ()): (ModuleDef, Map[String, ModuleDef]) = {
    var logic  = module.logic
    var wires  = module.wires
    val bodies = mutable.LinkedHashMap.empty[String, ModuleDef]

    groups.foreach { g =>
      val members = g.members.intersect(logic.iterator.map(_.id).toSet)
      if (members.nonEmpty) {
        val boxes = logic.iterator.filter(n => NodeKind.isBox(n.kind)).map(_.id).toSet
        val (body, nextLogic, nextWires) = rewire(g, members, logic, wires, pinName(_, _, boxes), pinOverride, onNamed, boxes)
        bodies(g.id) = body
        logic = nextLogic
        wires = nextWires
      }
    }
    (module.copy(logic = logic, wires = wires), bodies.toMap)
  }

  private def rewire(
    g:       Group,
    members: Set[String],
    logic:   Seq[LogicNodeDef],
    wires:   Seq[WireDef],
    pinOf:   (String, String) => (String, String),
    pinOverride: Option[PinOverride],
    onNamed: (String, String, String) => Unit,
    boxes:   Set[String],
  ): (ModuleDef, Seq[LogicNodeDef], Seq[WireDef]) = {
    // Exact id first (a const id carries its `~in<i>` suffix), then the head base.
    def inMember(endpoint: String): Boolean =
      members.contains(endpoint) || members.contains(IrWalk.fsmHeadBase(endpoint))

    // Pins by key; a name another key already holds gets `_2`, `_3`... A key
    // used in both directions (a namer keying on the external end: a port the
    // group reads back and writes) is one inout pin.
    val pins  = mutable.LinkedHashMap.empty[String, PortDef]
    val taken = mutable.Set.empty[String]
    def putPin(key: String, base: String, label: String, dir: String): String = {
      pins.get(key) match {
        case None =>
          val name = (base #:: LazyList.from(2).map(n => s"${base}_$n")).find(!taken(_)).get
          taken += name
          pins(key) = PortDef(name = name, dir = dir, bundle = "", doc = "", source = SourceRef.unknown,
            label = if (label == name) "" else label)
        case Some(p) if p.dir != dir => pins(key) = p.copy(dir = PortDir.Inout)
        case Some(_) =>
      }
      pins(key).name
    }
    def addPin(driver: String, external: String, dir: String): String = {
      val (key, base) = pinOf(driver, external)
      // Shown by its source path (#333): the key, below a box's id for a box pin.
      val h     = key.takeWhile(c => c != '.' && c != '[')
      val label = if (boxes(h) && key.length > h.length + 1) key.drop(h.length + 1) else key
      putPin(key, base, label, dir)
    }
    // The pins the caller names itself, claimed first, in wire order.
    val overridden: Map[Int, (String, String, String)] = pinOverride.fold(Map.empty[Int, (String, String, String)]) { f =>
      wires.iterator.zipWithIndex.flatMap { case (w, i) =>
        val fromMem = inMember(w.from)
        val toMem   = inMember(w.to)
        if (fromMem == toMem) None
        else {
          val dir = if (toMem) PortDir.Out else PortDir.In
          f(if (fromMem) w.from else w.to, if (fromMem) w.to else w.from, dir).map { case (key, name, label) =>
            onNamed(g.id, key, putPin(key, name, label, dir))
            i -> ((key, name, label))
          }
        }
      }.toMap
    }

    val internal = mutable.ArrayBuffer.empty[WireDef]
    val parent   = mutable.ArrayBuffer.empty[WireDef]
    // WireDef(from = sink, to = source): a member sink is fed from outside (in
    // pin), a member source drives outside (out pin). The driver is `w.to`.
    // The body keeps the crossing wire too, from its member end to the pin,
    // written `<box>.<pin>` as the parent writes it: a pin name alone may be a
    // member's id (an out pin named after the register that drives it).
    wires.iterator.zipWithIndex.foreach { case (w, i) =>
      val fromMem = inMember(w.from)
      val toMem   = inMember(w.to)
      if (fromMem && toMem) internal += w
      else if (fromMem || toMem) {
        val dir     = if (toMem) PortDir.Out else PortDir.In
        val pin     = overridden.get(i) match {
          case Some((key, _, _)) => pins(key).name
          case None              => addPin(w.to, if (fromMem) w.to else w.from, dir)
        }
        val pinPath = s"${g.id}.$pin"
        parent += (if (fromMem) w.copy(from = pinPath) else w.copy(to = pinPath))
        internal += (if (fromMem) w.copy(to = pinPath) else w.copy(from = pinPath))
      } else parent += w
    }

    def dedup(ws: Seq[WireDef]): Seq[WireDef] = {
      val seen = mutable.LinkedHashSet.empty[(String, String)]
      ws.filter(w => seen.add((w.from, w.to)))
    }

    val body = ModuleDef(
      name = g.id, `type` = g.id, doc = "", source = g.source,
      ports = pins.values.toSeq, children = Nil, wires = dedup(internal.toSeq),
      metrics = Metrics(0, 0, 0), kind = ModuleKind.Internal,
      logic = logic.filter(n => members.contains(n.id)),
    )
    val node = LogicNodeDef(g.id, g.kind, g.label, "", g.source, layer = g.layer, state = g.state)
    (body, logic.filterNot(n => members.contains(n.id)) :+ node, dedup(parent.toSeq))
  }
}
