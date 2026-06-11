package socless

import scala.collection.mutable
import ChirrtlParser.{
  Circ, Mod, Port, Inst, Conn, Field, SrcInfo,
  Type => CType, GroundT, BundleT, VecT,
}

/** Translates a [[ChirrtlParser.Circ]] into the [[Design]] shape the UI consumes.
  *
  * Bundle types are de-duplicated by structural identity (two ports with the
  * same shape share one BundleDef entry). Decoupled handshakes are detected
  * by structural match - `{ ready, valid, bits }` triggers the Decoupled
  * rendering in the UI.
  */
object IrWalk {

  def design(c: Circ): Design = {
    val bundles = mutable.LinkedHashMap.empty[String, BundleDef]
    // Cross-module name lookup. The wire bundle inference for `<inst>.<port>`
    // endpoints needs to read port types off the instance's own module,
    // which lives elsewhere in the circuit. Building it once here is cheaper
    // than searching per wire.
    val byName  = c.modules.map(m => m.name -> m).toMap
    val modules = c.modules.map(buildModule(_, byName, bundles))
    Design(top = c.name, bundles = bundles.toMap, modules = modules)
  }

  // ── Module ────────────────────────────────────────────────────────────────
  private def buildModule(
    m:       Mod,
    byName:  Map[String, Mod],
    bundles: mutable.LinkedHashMap[String, BundleDef],
  ): ModuleDef = {
    // A port is "connected" if its name is referenced anywhere in the module
    // body (connects, register/memory clocks+resets, node expressions, when
    // conditions, ...). m.refs is collected from every statement except port
    // declarations, so clock/reset and expression-only inputs count as used and
    // only genuinely-dangling ports are flagged unconnected (issue #74).
    val ports = m.ports.map(p => buildPort(p, bundles, m.refs.contains(p.name)))
    val children = m.insts.map { i =>
      InstanceDef(
        name   = i.name,
        `type` = i.ofModule,
        doc    = "",
        source = i.src.map(toRef).getOrElse(SourceRef.unknown),
      )
    }
    val wires = coalesceWires(m, byName, bundles)
    val metrics = Metrics(
      registers = m.regs.size,
      wires     = m.conns.size,
      lines     = approxLines(m),
      gates     = None,
      memories  = m.mems.size,
    )
    ModuleDef(
      name     = m.name,
      `type`   = m.name,
      doc      = "",
      source   = m.src.map(toRef).getOrElse(SourceRef.unknown),
      ports    = ports,
      children = children,
      wires    = wires,
      metrics  = metrics,
      kind     = moduleKind(m.kind),
    )
  }

  /** Map the raw CHIRRTL module keyword to the schema's module kind.
    * extmodule (blackbox) -> "external", intmodule -> "intrinsic", else
    * "internal" (issue #20). */
  private def moduleKind(rawKind: String): String = rawKind match {
    case "extmodule" => "external"
    case "intmodule" => "intrinsic"
    case _           => "internal"
  }

  // ── Ports ────────────────────────────────────────────────────────────────
  private def buildPort(
    p:         Port,
    bundles:   mutable.LinkedHashMap[String, BundleDef],
    connected: Boolean,
  ): PortDef = {
    val (key, role) = registerType(p.tpe, p.dir, p.name, bundles)
    PortDef(
      name      = p.name,
      dir       = role,
      bundle    = key,
      doc       = "",
      source    = p.src.map(toRef).getOrElse(SourceRef.unknown),
      connected = connected,
    )
  }

  /** Direction at a port boundary, AXI-aware. Default to in/out from the
    * `input`/`output` keyword; if the type is a Bundle whose field-name set
    * matches an AXI4 shape (ar + aw + w + r + b), surface "master"/"slave"
    * based on the port-level direction keyword:
    *
    *   output io  =>  master  (module drives the request channels)
    *   input  io  =>  slave   (module receives the request channels)
    *
    * This is the correct mapping because CHIRRTL (and Chisel Flipped) applies
    * the outer flip to the entire bundle: an AXI slave uses `Flipped(AXI4())`,
    * which in CHIRRTL is emitted as `input io : { ... }`.  The inner field
    * flip pattern (e.g. `flip r`) is identical across both sides, so relying
    * on inner flips (the old approach) cannot distinguish master from slave.
    */
  private def portRole(dir: String, tpe: CType): String = tpe match {
    case BundleT(fs)
        if fs.exists(_.name == "ar") && fs.exists(_.name == "r") =>
      if (dir == "output") "master" else "slave"
    case _ => dir // "input" or "output"
  }

  // ── Bundles: register + return key ────────────────────────────────────────
  /** Register `tpe` into `bundles` if needed and return (key, port-role). */
  private def registerType(
    tpe:     CType,
    dir:     String,
    hint:    String,
    bundles: mutable.LinkedHashMap[String, BundleDef],
  ): (String, String) = tpe match {
    case b: BundleT =>
      val key = bundleKey(b, hint)
      bundles.getOrElseUpdate(key, BundleDef(key, "Bundle")) // placeholder for cycles
      bundles(key) = bundleDef(b, key, bundles)
      (key, portRole(dir, b))

    case VecT(elem, n) =>
      val (inner, _) = registerType(elem, dir, hint + "_elem", bundles)
      (s"Vec[$inner, $n]", dir)

    case GroundT("UInt", Some(w)) => (s"UInt<$w>", dir)
    case GroundT("SInt", Some(w)) => (s"SInt<$w>", dir)
    case GroundT("UInt", None)    => ("UInt", dir)
    case GroundT(other, _)        => (other, dir)
  }

  /** Stable key for a bundle: based on its shape (so equivalent bundles
    * collapse) but biased toward a readable hint when possible.
    */
  private def bundleKey(b: BundleT, hint: String): String = {
    val fingerprint = b.fields.map(f => s"${if (f.flip) "f" else ""}${f.name}").mkString("_")
    // Recognized AXI shapes - give them friendly names.
    val names = b.fields.map(_.name).toSet
    if (names == Set("ar", "aw", "w", "r", "b")) return "AXI4"
    if (names == Set("ready", "valid", "bits"))  return s"Decoupled_$hint"
    if (names == Set("ready", "valid"))           return s"ReadyValid_$hint"
    // Otherwise, hint-based with a deterministic fingerprint suffix.
    // We use SHA-1 over the UTF-8 fingerprint bytes so the key is a pure
    // function of the bundle's structural shape - stable across JVM versions
    // and implementations (unlike String.hashCode, which is not guaranteed
    // stable outside the same JVM run).
    val digest = java.security.MessageDigest.getInstance("SHA-1")
      .digest(fingerprint.getBytes("UTF-8"))
    val hex = digest.map("%02x".format(_)).mkString.take(6)
    s"${capitalize(hint)}_$hex"
  }

  private def bundleDef(
    b:       BundleT,
    key:     String,
    bundles: mutable.LinkedHashMap[String, BundleDef],
  ): BundleDef = {
    val isDecoupled = {
      val ns = b.fields.map(_.name).toSet
      ns == Set("ready", "valid", "bits")
    }
    if (isDecoupled) {
      // Flatten the `bits` sub-bundle into FieldDefs; valid+ready are implicit.
      val bits = b.fields.find(_.name == "bits").map(_.tpe).getOrElse(GroundT("UInt", None))
      val fields = flattenGround(bits, "")
      BundleDef(name = key, kind = "Decoupled", fields = fields)
    } else {
      // Heterogeneous bundle → register channels recursively.
      val channels = b.fields.map { f =>
        val (innerKey, _) = registerType(f.tpe, "input", f.name, bundles)
        ChannelDef(name = f.name, `type` = innerKey, flip = f.flip)
      }
      BundleDef(name = key, kind = "Bundle", channels = channels)
    }
  }

  private def flattenGround(t: CType, prefix: String): Seq[FieldDef] = t match {
    case BundleT(fs) =>
      fs.flatMap(f => flattenGround(f.tpe, if (prefix.isEmpty) f.name else s"$prefix.${f.name}"))
    case GroundT("UInt", Some(w)) => Seq(FieldDef(prefix, w, "out"))
    case GroundT("SInt", Some(w)) => Seq(FieldDef(prefix, w, "out"))
    case GroundT("Clock", _)      => Seq(FieldDef(prefix, 1, "out"))
    case GroundT("Reset", _)      => Seq(FieldDef(prefix, 1, "out"))
    case GroundT(_, _)            => Seq(FieldDef(prefix, 0, "out"))
    case VecT(elem, n)            => (0 until n).flatMap(i => flattenGround(elem, s"$prefix[$i]"))
  }

  // ── Wires: coalesce connect statements back into bundle-level wires ───────
  /** CHIRRTL emits one `connect` per ground signal - connecting two AXI ports
    * unfolds to ~30 connects. We coalesce by reducing each endpoint to its
    * `<inst>.<port>` prefix and deduping. The label embeds counts so the UI
    * shows "31× ground connects coalesced".
    *
    * Sub-bundle granularity: when a port's type carries *named* sub-bundles
    * (e.g. `io.a: Decoupled[...]`, `io.b: Decoupled[...]`), we drop into the
    * third segment so each named child of `io` becomes its own coalesced
    * wire instead of collapsing into one opaque `<inst>.io` blob. Ports
    * that bottom out at ground signals stay at two-segment depth as before.
    *
    * Internal-net connects (`_T_42 <= ...` between synthesized wires) are
    * filtered out at this stage: only edges with at least one endpoint on an
    * instance port or this module's own port are kept.
    */
  private def coalesceWires(
    m:       Mod,
    byName:  Map[String, Mod],
    bundles: mutable.LinkedHashMap[String, BundleDef],
  ): Seq[WireDef] = {
    case class Group(from: String, to: String, src: Option[SrcInfo], count: Int)
    val groups = mutable.LinkedHashMap.empty[(String, String), Group]
    val instSet = m.insts.map(_.name).toSet
    val portSet = m.ports.map(_.name).toSet
    val instMap = m.insts.map(i => i.name -> i.ofModule).toMap

    def stripIdx(s: String): String = {
      val i = s.indexOf('[')
      if (i < 0) s else s.substring(0, i)
    }

    /** True if `t` is itself an aggregate (Bundle or Vec of Bundle) - used to
      * decide whether a path's sub-segment is worth keeping as its own wire,
      * versus collapsing into the parent port.
      */
    def isAggregate(t: CType): Boolean = t match {
      case BundleT(_) => true
      case VecT(elem, _) => isAggregate(elem)
      case _ => false
    }

    /** Type of the port reached by descending one segment into `path` from the
      * top scope of this module, or None if `path` doesn't start at a known
      * instance port or module-own port.
      */
    def portTypeAt(path: String): Option[CType] = {
      val parts = path.split('.').toIndexedSeq.map(stripIdx)
      if (parts.isEmpty) return None
      val head = parts.head
      if (instSet.contains(head) && parts.length >= 2) {
        for {
          ofMod <- instMap.get(head)
          mod   <- byName.get(ofMod)
          port  <- mod.ports.find(_.name == parts(1))
        } yield port.tpe
      } else if (portSet.contains(head)) {
        m.ports.find(_.name == head).map(_.tpe)
      } else None
    }

    /** Endpoint reaches the IO boundary somewhere - either it's an instance
      * port or it's one of this module's own ports. Used to drop
      * internal-net connects (wire-to-wire signals like `_oHot_WIRE`).
      */
    def isExternalEndpoint(path: String): Boolean = {
      val head = stripIdx(path.split('.').headOption.getOrElse(""))
      instSet.contains(head) || portSet.contains(head)
    }

    def trim(path: String): String = {
      val rawParts = path.split('.').toIndexedSeq
      if (rawParts.isEmpty) return path
      val head = stripIdx(rawParts.head)
      if (instSet.contains(head) && rawParts.length >= 2) {
        // <inst>.<port>[.<sub>] - descend into named sub-bundle when present.
        val descend = rawParts.length >= 3 &&
          portTypeAt(path).flatMap(t => descendField(t, stripIdx(rawParts(2)))).exists(isAggregate)
        if (descend) s"${rawParts(0)}.${rawParts(1)}.${rawParts(2)}"
        else         s"${rawParts(0)}.${rawParts(1)}"
      } else if (portSet.contains(head)) {
        // bare <port>[.<sub>] on this module - same descent rule.
        val descend = rawParts.length >= 2 &&
          portTypeAt(path).flatMap(t => descendField(t, stripIdx(rawParts(1)))).exists(isAggregate)
        if (descend) s"${rawParts(0)}.${rawParts(1)}"
        else         rawParts(0)
      } else rawParts.head
    }

    for (c <- m.conns) {
      // Drop pure internal-net connects; the schematic only renders port-to-port wires.
      if (isExternalEndpoint(c.lhs) || isExternalEndpoint(c.rhs)) {
        val from = trim(c.lhs)
        val to   = trim(c.rhs)
        // Skip self-loops on the same port (these are the in-bundle subfield connects)
        if (from != to) {
          val k = (from, to)
          val g = groups.get(k)
          groups(k) = Group(from, to, g.flatMap(_.src).orElse(c.src), g.map(_.count).getOrElse(0) + 1)
        }
      }
    }
    groups.values.toSeq.map { g =>
      val bundle = inferBundle(m, g.from, g.to, byName, bundles)
      WireDef(
        from   = g.from,
        to     = g.to,
        bundle = bundle,
        label  = if (g.count > 1) s"${g.count}× ground connects" else "",
        source = g.src.map(toRef).getOrElse(SourceRef.unknown),
      )
    }
  }

  /** Best-effort bundle type for a coalesced wire endpoint.
    *
    * Resolution order, trying each in turn:
    *   1. `<inst>.<port>` on either side - look up `inst.ofModule` in the
    *      circuit and read its port type. Common case for inter-module
    *      wires (e.g. `cpu.io → xbar.in[0]`).
    *   2. Bare `<port>` on either side - read this module's port type.
    *   3. Fallback to `"Wire"` so the UI still draws something.
    *
    * Vec subscripts (`xbar.in[0]`) are stripped so the port lookup works
    * on the underlying bundle type. The first match wins - Chisel
    * structurally typechecks at connect time, so both sides agree.
    */
  private def inferBundle(
    m:        Mod,
    from:     String,
    to:       String,
    byName:   Map[String, Mod],
    bundles:  mutable.LinkedHashMap[String, BundleDef],
  ): String = {
    val instMap = m.insts.map(i => i.name -> i.ofModule).toMap

    def stripIdx(s: String): String = {
      val i = s.indexOf('[')
      if (i < 0) s else s.substring(0, i)
    }

    def resolveOnInst(path: String): Option[String] = {
      val parts = path.split('.').toIndexedSeq.map(stripIdx)
      if (parts.length < 2) return None
      for {
        ofMod <- instMap.get(parts(0))
        mod   <- byName.get(ofMod)
        port  <- mod.ports.find(_.name == parts(1))
      } yield {
        // Descend into the named sub-bundle when the path carries a third segment.
        val (effTpe, hint) =
          if (parts.length >= 3) descendField(port.tpe, parts(2)).map(t => (t, s"${port.name}_${parts(2)}")).getOrElse((port.tpe, port.name))
          else (port.tpe, port.name)
        val (k, _) = registerType(effTpe, port.dir, hint, bundles)
        k
      }
    }

    def resolveOnSelf(path: String): Option[String] = {
      val parts = path.split('.').toIndexedSeq.map(stripIdx)
      if (parts.isEmpty) return None
      m.ports.find(_.name == parts(0)).map { p =>
        val (effTpe, hint) =
          if (parts.length >= 2) descendField(p.tpe, parts(1)).map(t => (t, s"${p.name}_${parts(1)}")).getOrElse((p.tpe, p.name))
          else (p.tpe, p.name)
        val (k, _) = registerType(effTpe, p.dir, hint, bundles)
        k
      }
    }

    resolveOnInst(from)
      .orElse(resolveOnInst(to))
      .orElse(resolveOnSelf(from))
      .orElse(resolveOnSelf(to))
      .getOrElse("Wire")
  }

  // ── Helpers ───────────────────────────────────────────────────────────────
  private def approxLines(m: Mod): Int = {
    val all = (m.src ++ m.ports.flatMap(_.src) ++ m.insts.flatMap(_.src) ++ m.conns.flatMap(_.src))
      .map(_.line).toSeq
    if (all.isEmpty) 0 else (all.max - all.min + 1)
  }

  /** Look up a named field inside an aggregate type. None if the type isn't a
    * bundle (or Vec of bundle) or the field name doesn't exist. Used to
    * descend `<inst>.<port>.<sub>` paths into the named sub-bundle of a port.
    */
  private def descendField(t: CType, fieldName: String): Option[CType] = t match {
    case BundleT(fs)   => fs.find(_.name == fieldName).map(_.tpe)
    case VecT(elem, _) => descendField(elem, fieldName)
    case _             => None
  }

  private def toRef(s: SrcInfo): SourceRef = SourceRef(s.file, s.line, s.col)

  private def capitalize(s: String): String =
    if (s.isEmpty) s else s.head.toUpper +: s.tail
}
