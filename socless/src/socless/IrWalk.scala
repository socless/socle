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
  * by structural match — `{ ready, valid, bits }` triggers the Decoupled
  * rendering in the UI.
  */
object IrWalk {

  def design(c: Circ): Design = {
    val bundles = mutable.LinkedHashMap.empty[String, BundleDef]
    val modules = c.modules.map(buildModule(_, bundles))
    Design(top = c.name, bundles = bundles.toMap, modules = modules)
  }

  // ── Module ────────────────────────────────────────────────────────────────
  private def buildModule(
    m:       Mod,
    bundles: mutable.LinkedHashMap[String, BundleDef],
  ): ModuleDef = {
    val ports = m.ports.map(p => buildPort(p, bundles))
    val children = m.insts.map { i =>
      InstanceDef(
        name   = i.name,
        `type` = i.ofModule,
        doc    = "",
        source = i.src.map(toRef).getOrElse(SourceRef.unknown),
      )
    }
    val wires = coalesceWires(m, bundles)
    val metrics = Metrics(
      registers = 0,
      wires     = m.conns.size,
      lines     = approxLines(m),
      gates     = None,
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
    )
  }

  // ── Ports ────────────────────────────────────────────────────────────────
  private def buildPort(
    p:       Port,
    bundles: mutable.LinkedHashMap[String, BundleDef],
  ): PortDef = {
    val (key, role) = registerType(p.tpe, p.dir, p.name, bundles)
    PortDef(
      name   = p.name,
      dir    = role,
      bundle = key,
      doc    = "",
      source = p.src.map(toRef).getOrElse(SourceRef.unknown),
    )
  }

  /** Direction at a port boundary, AXI-aware. Default to in/out from the
    * `input`/`output` keyword; if the type is a Bundle whose outer flips
    * match an AXI master pattern (ar+aw+r), surface "master"/"slave".
    */
  private def portRole(dir: String, tpe: CType): String = tpe match {
    case BundleT(fs) if fs.exists(_.name == "ar") && fs.exists(_.name == "r") =>
      // R is flipped on a master, so if `r` is `flip` we are a master.
      if (fs.find(_.name == "r").exists(_.flip)) "master" else "slave"
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
    // Recognized AXI shapes — give them friendly names.
    val names = b.fields.map(_.name).toSet
    if (names == Set("ar", "aw", "w", "r", "b")) return "AXI4"
    if (names == Set("ready", "valid", "bits"))  return s"Decoupled_$hint"
    if (names == Set("ready", "valid"))           return s"ReadyValid_$hint"
    // Otherwise, hint-based with fingerprint suffix
    s"${capitalize(hint)}_${fingerprint.hashCode.toHexString.take(6)}"
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
  /** CHIRRTL emits one `connect` per ground signal — connecting two AXI ports
    * unfolds to ~30 connects. We coalesce by reducing each endpoint to its
    * `<inst>.<port>` prefix and deduping. The label embeds counts so the UI
    * shows "31× ground connects coalesced".
    */
  private def coalesceWires(
    m:       Mod,
    bundles: mutable.LinkedHashMap[String, BundleDef],
  ): Seq[WireDef] = {
    case class Group(from: String, to: String, src: Option[SrcInfo], count: Int)
    val groups = mutable.LinkedHashMap.empty[(String, String), Group]
    val instSet = m.insts.map(_.name).toSet
    val portSet = m.ports.map(_.name).toSet

    def trim(path: String): String = {
      val parts = path.split('.')
      if (parts.isEmpty) return path
      val head = parts.head
      // <instance>.<port>   → keep first two segments
      if (instSet.contains(head) && parts.length >= 2) s"${parts(0)}.${parts(1)}"
      // <port>.<...>        → port name is at top level, keep first segment
      else if (portSet.contains(head)) head
      else parts.head
    }

    for (c <- m.conns) {
      val from = trim(c.lhs)
      val to   = trim(c.rhs)
      // Skip self-loops on the same port (these are the in-bundle subfield connects)
      if (from != to) {
        val k = (from, to)
        val g = groups.get(k)
        groups(k) = Group(from, to, g.flatMap(_.src).orElse(c.src), g.map(_.count).getOrElse(0) + 1)
      }
    }
    groups.values.toSeq.map { g =>
      val bundle = inferBundle(m, g.from, g.to, bundles)
      WireDef(
        from   = g.from,
        to     = g.to,
        bundle = bundle,
        label  = if (g.count > 1) s"${g.count}× ground connects" else "",
        source = g.src.map(toRef).getOrElse(SourceRef.unknown),
      )
    }
  }

  /** Best-effort bundle type for a coalesced wire: look up the port's type
    * on either endpoint's instance / on the current module.
    */
  private def inferBundle(
    m:        Mod,
    from:     String,
    to:       String,
    bundles:  mutable.LinkedHashMap[String, BundleDef],
  ): String = {
    // Try the module's own ports first (matches `from` if from is unqualified)
    m.ports.find(p => p.name == from || p.name == to).foreach { p =>
      val (k, _) = registerType(p.tpe, p.dir, p.name, bundles)
      return k
    }
    // Otherwise: we don't have the instance's port type here (it's defined
    // in another module). Fall back to a generic "Wire" — the UI tolerates it.
    "Wire"
  }

  // ── Helpers ───────────────────────────────────────────────────────────────
  private def approxLines(m: Mod): Int = {
    val all = (m.src ++ m.ports.flatMap(_.src) ++ m.insts.flatMap(_.src) ++ m.conns.flatMap(_.src))
      .map(_.line).toSeq
    if (all.isEmpty) 0 else (all.max - all.min + 1)
  }

  private def toRef(s: SrcInfo): SourceRef = SourceRef(s.file, s.line, s.col)

  private def capitalize(s: String): String =
    if (s.isEmpty) s else s.head.toUpper +: s.tail
}
