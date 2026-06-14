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

  // Single source of truth for path-index stripping, shared with the layout
  // engine (also used by the Scala.js build): `in[0]` -> `in`.
  import LayoutCore.stripIdx

  /** Bundle registry: deduplication is by FULL structural identity (names,
    * flips, ground kinds, widths, vec arity), tracked in `keyByFp`. Friendly
    * keys (AXI4, Decoupled_<hint>, <Hint>) go to the first structure that
    * claims them; a structurally different bundle wanting the same friendly
    * name gets a deterministic hash suffix instead of silently overwriting
    * the registered definition (issue #88).
    */
  private final class BundleReg {
    val defs    = mutable.LinkedHashMap.empty[String, BundleDef]
    val keyByFp = mutable.HashMap.empty[String, String]
  }

  def design(c: Circ): Design = {
    val bundles = new BundleReg
    // Cross-module name lookup. The wire bundle inference for `<inst>.<port>`
    // endpoints needs to read port types off the instance's own module,
    // which lives elsewhere in the circuit. Building it once here is cheaper
    // than searching per wire.
    val byName  = c.modules.map(m => m.name -> m).toMap
    val modules = c.modules.map(buildModule(_, byName, bundles))
    // Carry parser warnings through, and add a walk-level one: wires whose
    // endpoint type could not be resolved fall back to bundle "Wire" (issue
    // #118 - the user should know those are best-effort, not real types).
    val unresolved = modules.flatMap(_.wires).count(_.bundle == "Wire")
    val walkWarnings =
      if (unresolved > 0)
        Seq(Warning("unresolved-endpoint", unresolved,
          s"$unresolved wire(s) had an unresolvable endpoint type and were labeled 'Wire'"))
      else Seq.empty
    Design(top = c.name, bundles = bundles.defs.toMap, modules = modules,
      warnings = c.warnings ++ walkWarnings)
  }

  // ── Module ────────────────────────────────────────────────────────────────
  private def buildModule(
    m:       Mod,
    byName:  Map[String, Mod],
    bundles: BundleReg,
  ): ModuleDef = {
    // A port is "connected" if its name is referenced anywhere in the module
    // body (connects, register/memory clocks+resets, node expressions, when
    // conditions, ...). m.refs is collected from every statement except port
    // declarations, so clock/reset and expression-only inputs count as used and
    // only genuinely-dangling ports are flagged unconnected (issue #74).
    // Blackboxes (extmodule/intmodule) have no statements at all, so the
    // heuristic cannot apply: default their ports to connected rather than
    // flagging every blackbox pin as dangling (issue #100).
    val isRealModule = m.kind == "module"
    val ports = m.ports.map(p => buildPort(p, bundles, !isRealModule || m.refs.contains(p.name)))
    // Clock/reset domain inference (issue #19): the connect that feeds an
    // instance's clock (or reset) port names the domain signal; siblings sharing
    // it share a domain. We find that port on the *child* module's interface
    // (clock by type or name, reset by type or name since reset is often
    // lowered to UInt<1>) and look up `<inst>.<port>` on the LHS of a connect.
    def driverOf(instName: String, portNames: Seq[String]): String =
      portNames.iterator
        .flatMap(pn => m.conns.collectFirst { case Conn(lhs, rhs, _) if lhs.trim == s"$instName.$pn" => rhs.trim })
        .find(_.nonEmpty)
        .getOrElse("")

    val children = m.insts.map { i =>
      val childPorts = byName.get(i.ofModule).map(_.ports).getOrElse(Seq.empty)
      val clockPorts = childPorts.filter(isClockPort).map(_.name)
      val resetPorts = childPorts.filter(isResetPort).map(_.name)
      InstanceDef(
        name   = i.name,
        `type` = i.ofModule,
        doc    = "",
        source = i.src.map(toRef).getOrElse(SourceRef.unknown),
        clock  = driverOf(i.name, clockPorts),
        reset  = driverOf(i.name, resetPorts),
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
    bundles:   BundleReg,
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
    bundles: BundleReg,
  ): (String, String) = tpe match {
    case b: BundleT =>
      val key = bundleKey(b, hint, bundles)
      if (!bundles.defs.contains(key)) {
        bundles.defs(key) = BundleDef(key, "Bundle") // placeholder while children register
        bundles.defs(key) = bundleDef(b, key, bundles)
      }
      (key, portRole(dir, b))

    case VecT(elem, n) =>
      val (inner, _) = registerType(elem, dir, hint + "_elem", bundles)
      (s"Vec[$inner, $n]", dir)

    case GroundT("UInt", Some(w)) => (s"UInt<$w>", dir)
    case GroundT("SInt", Some(w)) => (s"SInt<$w>", dir)
    case GroundT("UInt", None)    => ("UInt", dir)
    case GroundT(other, _)        => (other, dir)
  }

  /** Canonical structural rendering of a CHIRRTL type: field names, flips,
    * ground kinds, widths, and vec arity all participate, so two bundles get
    * the same fingerprint iff they are structurally identical. The old
    * names-and-flips-only fingerprint collapsed e.g. Decoupled[UInt<32>] and
    * Decoupled[R] into one definition (issue #88).
    */
  private def typeFp(t: CType): String = t match {
    case GroundT(name, w) => s"$name<${w.getOrElse(-1)}>"
    case VecT(elem, n)    => s"vec[${typeFp(elem)};$n]"
    case BundleT(fs)      =>
      fs.map(f => s"${if (f.flip) "!" else ""}${f.name}:${typeFp(f.tpe)}").mkString("{", ",", "}")
  }

  /** SHA-1 over the UTF-8 fingerprint, hex prefix. SHA-1 (not String.hashCode)
    * so the key is stable across JVM versions and implementations.
    */
  private def fpHex(fp: String): String =
    java.security.MessageDigest.getInstance("SHA-1")
      .digest(fp.getBytes("UTF-8"))
      .map("%02x".format(_)).mkString

  /** Key for a bundle. Identity is the structural fingerprint: the same
    * structure always returns the same key regardless of hint (so identical
    * bundles reached via different ports collapse), and different structures
    * never share a key (a friendly-name clash gets a hash suffix instead of
    * overwriting, issue #88).
    */
  private def bundleKey(b: BundleT, hint: String, bundles: BundleReg): String = {
    val fp = typeFp(b)
    bundles.keyByFp.getOrElseUpdate(fp, {
      val names = b.fields.map(_.name).toSet
      val base =
        if (names == Set("ar", "aw", "w", "r", "b")) "AXI4"
        else if (names == Set("ready", "valid", "bits")) s"Decoupled_$hint"
        else if (names == Set("ready", "valid"))         s"ReadyValid_$hint"
        else capitalize(hint)
      if (!bundles.defs.contains(base)) base
      else {
        val hex = fpHex(fp)
        var n   = 6
        var key = s"${base}_${hex.take(n)}"
        while (bundles.defs.contains(key) && n < hex.length) { n += 1; key = s"${base}_${hex.take(n)}" }
        key
      }
    })
  }

  private def bundleDef(
    b:       BundleT,
    key:     String,
    bundles: BundleReg,
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

  // Clock/reset port recognition for domain inference (issue #19). Type is the
  // strong signal; a name fallback catches reset lowered to UInt<1> (common
  // after some passes) and clocks named clk. Mirrors the UI's clock/reset
  // de-emphasis heuristic so the two stay consistent.
  private def isClockPort(p: ChirrtlParser.Port): Boolean = p.tpe match {
    case GroundT("Clock", _) => true
    case _                   => { val n = p.name.toLowerCase; n == "clock" || n == "clk" }
  }
  private def isResetPort(p: ChirrtlParser.Port): Boolean = p.tpe match {
    case GroundT("Reset", _) | GroundT("AsyncReset", _) => true
    case _ => { val n = p.name.toLowerCase; n == "reset" || n == "rst" || n == "rst_n" || n == "resetn" }
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
    bundles: BundleReg,
  ): Seq[WireDef] = {
    case class Group(from: String, to: String, src: Option[SrcInfo], count: Int)
    val groups = mutable.LinkedHashMap.empty[(String, String), Group]
    val instSet = m.insts.map(_.name).toSet
    val portSet = m.ports.map(_.name).toSet
    val instMap = m.insts.map(i => i.name -> i.ofModule).toMap

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

    // Deeper trim: keep one extra path segment (`inst.port.field` for instance
    // ports, `port.field` for this module's own ports). Used to push scalar
    // field connects below a "split" bundle port so they don't collapse onto
    // the whole-port path (issue #82).
    def trimDeep(path: String): String = {
      val parts = path.split('.').toIndexedSeq
      val head  = stripIdx(parts.head)
      val depth = if (instSet.contains(head)) 3 else 2
      parts.take(depth).mkString(".")
    }

    // Pass 1: trim every external connect to its coalesced endpoints.
    val conns = m.conns.filter(c => isExternalEndpoint(c.lhs) || isExternalEndpoint(c.rhs))
    val pre   = conns.map(c => (c, trim(c.lhs), trim(c.rhs)))

    // A port is "split" when it is connected both as a whole (`inst.port`) and at
    // sub-field granularity (`inst.port.x`) - e.g. RegStage's io, whose `in`/`out`
    // sub-bundles descend while its scalar enable/flush/stall collapse onto `io`.
    // Collapsing the scalars onto `io` makes a redundant pin that overlaps the
    // `io.in`/`io.out` pins (issue #82). Detect such ancestors and, for the
    // connects that landed on them, descend one more segment instead.
    val endpoints = pre.flatMap { case (_, f, t) => Seq(f, t) }.toSet
    def isSplitAncestor(ep: String): Boolean = endpoints.exists(o => o != ep && o.startsWith(ep + "."))
    def resolve(orig: String, trimmed: String): String =
      if (isSplitAncestor(trimmed)) trimDeep(orig) else trimmed

    for ((c, f0, t0) <- pre) {
      val from = resolve(c.lhs, f0)
      val to   = resolve(c.rhs, t0)
      // Skip self-loops on the same port (these are the in-bundle subfield connects)
      if (from != to) {
        val k = (from, to)
        val g = groups.get(k)
        groups(k) = Group(from, to, g.flatMap(_.src).orElse(c.src), g.map(_.count).getOrElse(0) + 1)
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
    bundles:  BundleReg,
  ): String = {
    val instMap = m.insts.map(i => i.name -> i.ofModule).toMap

    // An indexed path segment selects ONE vec element: `in[0]` against a
    // Vec[AXI4, 2] is an AXI4, not the whole vec. One unwrap per subscript
    // (issue #89: the index was stripped and the wire got the vec type, which
    // the UI then displayed on both endpoint pins).
    def unwrapIdx(t: CType, rawSeg: String): CType = {
      var cur    = t
      var subs   = rawSeg.count(_ == '[')
      while (subs > 0) {
        cur match {
          case VecT(elem, _) => cur = elem; subs -= 1
          case _             => return cur
        }
      }
      cur
    }

    def resolveOnInst(path: String): Option[String] = {
      val raw   = path.split('.').toIndexedSeq
      val parts = raw.map(stripIdx)
      if (parts.length < 2) return None
      for {
        ofMod <- instMap.get(parts(0))
        mod   <- byName.get(ofMod)
        port  <- mod.ports.find(_.name == parts(1))
      } yield {
        val portTpe = unwrapIdx(port.tpe, raw(1))
        // Descend into the named sub-bundle when the path carries a third segment.
        val (effTpe, hint) =
          if (parts.length >= 3)
            descendField(portTpe, parts(2)).map(t => (unwrapIdx(t, raw(2)), s"${port.name}_${parts(2)}")).getOrElse((portTpe, port.name))
          else (portTpe, port.name)
        val (k, _) = registerType(effTpe, port.dir, hint, bundles)
        k
      }
    }

    def resolveOnSelf(path: String): Option[String] = {
      val raw   = path.split('.').toIndexedSeq
      val parts = raw.map(stripIdx)
      if (parts.isEmpty) return None
      m.ports.find(_.name == parts(0)).map { p =>
        val portTpe = unwrapIdx(p.tpe, raw(0))
        val (effTpe, hint) =
          if (parts.length >= 2)
            descendField(portTpe, parts(1)).map(t => (unwrapIdx(t, raw(1)), s"${p.name}_${parts(1)}")).getOrElse((portTpe, p.name))
          else (portTpe, p.name)
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
