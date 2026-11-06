package socle.capture

import socle.ir.IrWalk
import socle.schema._
import scala.collection.mutable

/** Decides which logic nodes of a module belong together as one box. Pure.
  * The module's code-block boxes (IrWalk's `when` / `switch` boxes, kinds
  * block and fsm) only ever join a generator group, and a register a box
  * writes stays a plain node at module level, as the code declares it, unless
  * rule 5 places it in a generator group. Rules, in order: (2) layer nodes
  * never join non-layer groups; (3) an op, a mem or a code-block box whose
  * source the index maps to a construct joins that construct (a `when` in a
  * `for` loop: the loop's box holds the copies); (4) an unknown-position node
  * joins the one group it drives; (5) a register joins the one generator group
  * that holds all its D drivers (a box among them: a register made in a loop
  * and written by the loop's `when`s); (6) a const joins the one group that
  * holds all its consumers, else stays a plain node; (7) everything else but a
  * box or a register a box writes goes to `logic`. Rules 4 and 5 run to a
  * fixpoint before rule 7, then rules 4 and 6 run again over the completed
  * assignment so they can reach `logic`. (Rule 1, an FSM group around the
  * state register, is gone: the FSM box is IrWalk's.) */
object LogicGroups {

  private val MaxLabel = 40

  private sealed trait Key
  private case class GenKey(layer: Boolean, c: Construct)  extends Key
  private case class LogicKey(layer: Boolean)              extends Key

  private def layerOf(k: Key): Boolean = k match {
    case GenKey(l, _)  => l
    case LogicKey(l)   => l
  }

  def assign(module: ModuleDef, index: Option[SourceIndex]): Seq[Group] = {
    val nodes = module.logic
    val ids   = nodes.iterator.map(_.id).toSet

    // Exact id first (a const id carries its `~in<i>` suffix), then the head base.
    def nodeOf(endpoint: String): Option[String] =
      if (ids.contains(endpoint)) Some(endpoint)
      else Some(IrWalk.fsmHeadBase(endpoint)).filter(ids.contains)

    val assigned = mutable.LinkedHashMap.empty[String, Key]

    // Rule 3: op/mem/code-block box with a source the index maps to a construct.
    index.foreach { idx =>
      nodes.foreach { n =>
        if ((n.kind == NodeKind.Op || n.kind == NodeKind.Mem || NodeKind.isBox(n.kind)) && !assigned.contains(n.id) &&
            n.source.file.nonEmpty)
          idx.construct(module.scalaClass, n.source.file, n.source.line, n.source.col)
            .foreach(c => assigned(n.id) = GenKey(n.layer, c))
      }
    }

    // Sinks (consumers) and sources (drivers) of every node, by node id.
    val consumers = mutable.Map.empty[String, mutable.Set[Option[String]]]
    val drivers   = mutable.Map.empty[String, mutable.Set[Option[String]]]
    module.wires.foreach { w =>
      val sink   = nodeOf(w.from)
      val source = nodeOf(w.to)
      source.foreach(s => consumers.getOrElseUpdate(s, mutable.Set.empty) += sink.filterNot(_ == s))
      // A sink that resolves to a register is a D write (`r`, `r[0]`, `r.a`);
      // reads carry `~q` and appear on the source side.
      sink.foreach(s => drivers.getOrElseUpdate(s, mutable.Set.empty) += source.filterNot(_ == s))
    }

    // The one group holding all of `others` (all must be grouped, same layer).
    def single(others: Iterable[Option[String]], layer: Boolean): Option[Key] = {
      val keys = others.map(_.flatMap(assigned.get)).toSet
      if (keys.size == 1) keys.head.filter(layerOf(_) == layer) else None
    }
    def unknownPos(n: LogicNodeDef) =
      (n.kind == NodeKind.Op || n.kind == NodeKind.Mem) && n.source.file.isEmpty

    def fixpoint(rule: LogicNodeDef => Option[Key]): Unit = {
      var changed = true
      while (changed) {
        changed = false
        nodes.foreach { n =>
          if (!assigned.contains(n.id)) rule(n).foreach { k => assigned(n.id) = k; changed = true }
        }
      }
    }
    def follow(n: LogicNodeDef): Option[Key] = single(consumers.getOrElse(n.id, Nil), n.layer)

    // A register a code-block box writes stays plain (unless rule 5 places it).
    val boxes = nodes.iterator.filter(n => NodeKind.isBox(n.kind)).map(_.id).toSet
    def boxWritten(n: LogicNodeDef) =
      n.kind == NodeKind.Reg && drivers.get(n.id).exists(_.exists(_.exists(boxes)))

    // Rules 4 and 5 (only generator groups exist so far).
    fixpoint { n =>
      if (unknownPos(n)) follow(n)
      else if (n.kind == NodeKind.Reg)
        single(drivers.getOrElse(n.id, Nil), n.layer).filter(_.isInstanceOf[GenKey])
      else None
    }

    // Rule 7 for registers and known-position ops/mems; unknown-position ops
    // stay open so rule 4 can still reach the logic group.
    nodes.foreach { n =>
      if (!assigned.contains(n.id) && ((n.kind == NodeKind.Reg && !boxWritten(n)) || ((n.kind == NodeKind.Op || n.kind == NodeKind.Mem) && !unknownPos(n))))
        assigned(n.id) = LogicKey(n.layer)
    }
    // Rules 4 and 6 again, now that `logic` exists; then leftover unknown-position
    // ops go to `logic` and consts get a last chance to follow them.
    def late(n: LogicNodeDef): Option[Key] =
      if (unknownPos(n) || n.kind == NodeKind.Const) follow(n) else None
    fixpoint(late)
    nodes.foreach { n => if (unknownPos(n) && !assigned.contains(n.id)) assigned(n.id) = LogicKey(n.layer) }
    fixpoint(late)

    val genKeys = assigned.values.collect { case k: GenKey => k }.toSeq.distinct
      .sortBy(k => (k.c.source.file, k.c.source.line, k.c.source.col, k.c.id))
    def membersOf(k: Key): Set[String] = assigned.collect { case (id, `k`) => id }.toSet

    def shorten(s: String): String = if (s.length <= MaxLabel) s else s.take(MaxLabel - 1) + "…"

    def genGroups(layer: Boolean, prefix: String) =
      genKeys.filter(_.layer == layer).zipWithIndex.map { case (k, i) =>
        Group(s"blk$$${module.name}$$$prefix$$${i + 1}", NodeKind.Block, shorten(k.c.label), k.c.source, layer, membersOf(k))
      }
    def logicGroup(layer: Boolean, id: String): Seq[Group] = {
      val ms = membersOf(LogicKey(layer))
      if (ms.isEmpty) Nil else Seq(Group(id, NodeKind.Block, if (layer) "debug logic" else "logic", module.source, layer, ms))
    }

    val layerGens = genGroups(layer = true, "layer")
    (genGroups(layer = false, "gen") ++
      logicGroup(layer = false, s"blk$$${module.name}$$logic") ++
      layerGens ++
      logicGroup(layer = true, s"blk$$${module.name}$$layer$$${layerGens.size + 1}")
    ).filter(_.members.nonEmpty)
  }
}
