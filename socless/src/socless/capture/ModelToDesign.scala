package socless.capture

import scala.collection.mutable

import socless.schema._

/** Adapts a [[CapturedModel]] (the probe's view of an elaborated design) to the
  * design.json schema the UI renders: ports and submodules only, no internal
  * logic. No layouts: callers bake them (`Layout.withLayouts`).
  *
  * Port types follow exactly how Chisel emits them to CHIRRTL
  * (`chisel3.internal.firrtl.Converter`): the captured `CType.dir` is each
  * node's specified direction, so a port's direction and every channel flip are
  * composed down the tree here, which makes the result line up with what the
  * CHIRRTL pipeline shows for the same design.
  *
  * Wiring keeps only wires between this module's ports and its child
  * instances' ports; a wire touching internal logic (register, constant, mux,
  * op, memory pins) is dropped, never bridged.
  */
object ModelToDesign {

  def design(m: CapturedModel): Design = {
    require(m.status == "ok",
      s"cannot adapt a capture with status '${m.status}'" + (if (m.error.nonEmpty) s": ${m.error}" else ""))
    val reg      = new Registry
    val byName   = m.modules.map(c => c.name -> c).toMap
    // Per module: (port name, schema direction, keyed type), in port order.
    val ports: Map[String, Seq[(String, String, Keyed)]] = m.modules.map { c =>
      c.name -> c.ports.map { p =>
        val user = userDir(p.tpe)
        val dir  = if (user == Unspecified || user == Out) PortDir.Out else PortDir.In
        (p.name, dir, reg.keyed(p.tpe, clear = user == In || user == Out, s"${c.name}.${p.name}"))
      }
    }.toMap
    lazy val chirrtlWires = WiresFromChirrtl.wireDefs(m.chirrtl)
    def params(module: String): Map[String, String] =
      byName.get(module).map(_.params.map(p => p.name -> p.value).toMap).getOrElse(Map.empty)

    val modules = m.modules.map { c =>
      val own       = ports(c.name).map { case (n, _, k) => n -> k }.toMap
      val instances = c.instances.map(i => i.name -> i.module).toMap
      // The key of the sub-type `path` names, when its head is a port of this
      // module or a port of one of its instances; None for anything else.
      def resolve(path: String): Option[String] = path.split('.').toList match {
        case h :: p :: rest if instances.contains(stripIdx(h)) =>
          val child = ports.getOrElse(instances(stripIdx(h)), Nil).map { case (n, _, k) => n -> k }.toMap
          child.get(stripIdx(p)).map(k => descend(k, p :: rest).key)
        case h :: rest if own.contains(stripIdx(h)) =>
          Some(descend(own(stripIdx(h)), h :: rest).key)
        case _ => None
      }
      val raw =
        if (c.wires.nonEmpty) c.wires.map(w => WireDef(w.from, w.to, "", "", SourceRef.unknown))
        else if (m.chirrtl.nonEmpty) chirrtlWires.getOrElse(c.name, Nil)
        else Nil
      // The bundle is the source's sub-type, except for clock/reset: IrWalk
      // labels those wires Clock/Reset/AsyncReset (a top-level reset is a Bool,
      // a child's is Reset), and the layout hides wires by that label.
      val wires = raw.flatMap { w =>
        for (src <- resolve(w.to); snk <- resolve(w.from)) yield {
          val bundle = Seq(w.bundle, snk, src).find(ClockResetTypes).getOrElse(src)
          w.copy(bundle = bundle)
        }
      }
      ModuleDef(
        name       = c.name,
        `type`     = c.name,
        doc        = "",
        source     = c.source.map(s => SourceRef(s.file, s.line, s.col)).getOrElse(SourceRef.unknown),
        ports      = ports(c.name).map { case (n, d, k) => PortDef(n, d, k.key, "", SourceRef.unknown) },
        children   = c.instances.map(i => InstanceDef(i.name, i.module, "", SourceRef.unknown, params = params(i.module))),
        wires      = wires,
        metrics    = Metrics(0, 0, 0),
        parameters = params(c.name),
        scalaClass = c.scalaClass,
        parameterDecls = c.params.map(p => ParamDef(p.name, p.tpe, p.value)),
      )
    }
    Design(
      top      = m.top,
      bundles  = reg.defs.toMap,
      modules  = modules,
      warnings = m.problems.map(p => Warning("probe-problem", 1, p)) ++
        reg.unknown.map { case (cls, n) =>
          Warning("probe-unknown-type", n, s"$n port leaf(s) of type $cls shown by class name: no schema type for it")
        },
      // Wires through internal logic are dropped, so draw unwired ports too.
      showUnwiredPorts = true,
    )
  }

  private val ClockResetTypes = Set("Clock", "Reset", "AsyncReset")

  // ── Directions (chisel3 SpecifiedDirection, as captured) ──────────────────
  private val Unspecified = "unspecified"
  private val Out         = "out"
  private val In          = "in"
  private val Flip        = "flip"

  private def flip(d: String): String = d match {
    case Unspecified => Flip
    case Flip        => Unspecified
    case Out         => In
    case In          => Out
    case other       => other
  }

  /** SpecifiedDirection.fromParent. */
  private def fromParent(parent: String, child: String): String = parent match {
    case Out | In => parent
    case Flip     => flip(child)
    case _        => child
  }

  /** Converter.firrtlUserDirOf: a Vec takes its element's direction. */
  private def userDir(t: CType): String =
    if (t.vecLen >= 0 && t.fields.nonEmpty) fromParent(t.dir, userDir(t.fields.head._2)) else t.dir

  // ── Types ─────────────────────────────────────────────────────────────────
  /** A type with its schema key, kept as a tree so a wire endpoint path
    * resolves to the key of the sub-type it names. */
  private case class Keyed(key: String, fields: Map[String, Keyed] = Map.empty, elem: Option[Keyed] = None)

  private val DecoupledClasses = Set("chisel3.util.DecoupledIO", "chisel3.util.IrrevocableIO")

  private def stripIdx(s: String): String = s.takeWhile(_ != '[')

  /** Walk `segs` (the first one names `k` itself, with any Vec indices)
    * down fields and Vec elements, stopping at the deepest node reached. */
  private def descend(k: Keyed, segs: List[String]): Keyed = segs match {
    case Nil => k
    case seg :: rest =>
      val at = (1 to seg.count(_ == '[')).foldLeft(k)((n, _) => n.elem.getOrElse(n))
      rest match {
        case next :: _ => at.fields.get(stripIdx(next)).fold(at)(descend(_, rest))
        case Nil       => at
      }
  }

  /** The schema name of a ground type, or None for a class this adapter does
    * not know (a ChiselEnum, a Property, ...). */
  private def ground(t: CType): Option[String] = {
    def sized(base: String) = if (t.width >= 0) s"$base<${t.width}>" else base
    t.scalaClass match {
      case "chisel3.Clock"               => Some("Clock")
      case "chisel3.ResetType"           => Some("Reset")
      case "chisel3.AsyncReset"          => Some("AsyncReset")
      case "chisel3.experimental.Analog" => Some("Analog")
      case "chisel3.SInt"                => Some(sized("SInt"))
      case "chisel3.UInt" | "chisel3.Bool" => Some(sized("UInt"))
      case _                             => None
    }
  }

  /** Ground leaves of a Decoupled payload, as IrWalk flattens `bits`. */
  private def flatten(t: CType, prefix: String): Seq[FieldDef] =
    if (t.vecLen >= 0) t.fields.headOption.toSeq.flatMap { case (_, e) =>
      (0 until t.vecLen).flatMap(i => flatten(e, s"$prefix[$i]"))
    }
    else if (t.fields.isEmpty) Seq(FieldDef(prefix, t.width.max(0), PortDir.Out))
    else t.fields.flatMap { case (n, f) => flatten(f, if (prefix.isEmpty) n else s"$prefix.$n") }

  /** Bundle definitions, keyed by Chisel class (or `<module>.<path>` for an
    * anonymous bundle); a class seen with a different structure (a generic
    * like DecoupledIO[T]) gets a structural hash suffix. */
  private final class Registry {
    val defs = mutable.LinkedHashMap.empty[String, BundleDef]
    /** Ground classes with no schema name, with how many leaves use each. */
    val unknown = mutable.LinkedHashMap.empty[String, Int]

    /** `clear`: an Input/Output above this node, so no flips below it. */
    def keyed(t: CType, clear: Boolean, path: String): Keyed = {
      val childClear = clear || t.dir == In || t.dir == Out
      if (t.vecLen >= 0) {
        val elem = t.fields.headOption.map { case (_, e) => keyed(e, childClear, s"$path[]") }
        Keyed(s"Vec[${elem.map(_.key).getOrElse("UInt")}, ${t.vecLen}]", elem = elem)
      } else if (t.fields.isEmpty) Keyed(ground(t).getOrElse {
        // Never invent a type: show the class's simple name and say so.
        unknown(t.scalaClass) = unknown.getOrElse(t.scalaClass, 0) + 1
        t.scalaClass.substring(t.scalaClass.lastIndexOf('.') + 1)
      })
      else {
        val kids = t.fields.map { case (n, f) => (n, keyed(f, childClear, s"$path.$n"), !childClear && (userDir(f) == Flip || userDir(f) == In)) }
        val body =
          if (DecoupledClasses(t.scalaClass))
            BundleDef("", BundleKind.Decoupled, fields = t.fields.find(_._1 == "bits").toSeq.flatMap(b => flatten(b._2, "")))
          else
            BundleDef("", BundleKind.Bundle, channels = kids.map { case (n, k, fl) => ChannelDef(n, k.key, fl) })
        val base = if (t.scalaClass.isEmpty || t.scalaClass.contains("$anon")) path else t.scalaClass
        Keyed(intern(base, body.copy(scalaClass = t.scalaClass)), kids.map { case (n, k, _) => n -> k }.toMap)
      }
    }

    private def intern(base: String, body: BundleDef): String = {
      val hex = java.security.MessageDigest.getInstance("SHA-1")
        .digest(body.toString.getBytes("UTF-8")).map("%02x".format(_)).mkString
      val candidates = base +: (6 to hex.length).map(n => s"${base}_${hex.take(n)}")
      val key = candidates.find(k => defs.get(k).forall(_.copy(name = "") == body))
        .getOrElse(sys.error(s"bundle key clash for $base"))
      if (!defs.contains(key)) defs(key) = body.copy(name = key)
      key
    }
  }
}
