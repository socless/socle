package socless.schema

/** Pure bundle-structure walking, shared by the JVM backend and the Scala.js
  * browser engine (issue #170). This is the Scala home of the logic the UI used
  * to re-derive in JS (`isAggregateType` / `portTree` / `resolvePinDir`): given
  * a [[Design]]'s bundle definitions, expand a bundle key into its nested
  * channel/Vec/Decoupled tree, and resolve the true direction of a pin by
  * walking that tree honouring channel flips and Vec indexing.
  *
  * Operating on the typed [[Schema]] (`BundleDef.channels`/`fields`) means there
  * is one implementation of bundle structure across the parser, the layout, and
  * the UI - no JS/Scala drift.
  */
object BundleTree {

  private val VecRe    = """^Vec\[(.+),\s*(\d+)\]$""".r
  private val GroundRe = """^(?:UInt|SInt)<(\d+)>$""".r

  /** A type key is aggregate (expandable) when it is a known bundle OR a Vec.
    * Vec keys ("Vec[AXI4, 2]", "Vec[UInt<8>, 4]") are not entries in
    * `design.bundles` but expand into indexed elements all the same. */
  def isAggregateType(typeKey: String, design: Design): Boolean =
    typeKey != null && typeKey.nonEmpty &&
      (design.bundles.contains(typeKey) || VecRe.findFirstIn(typeKey).isDefined)

  /** One node of a port's expansion tree. `dir` is None when unresolvable, so
    * the UI renders no direction arrow rather than a wrong one (issue #85). */
  case class PortNode(
    name: String, path: String, bundleKey: String, isExpandable: Boolean,
    dir: Option[String], width: Int, children: Seq[PortNode],
  )

  /** Build the recursive port tree for `bundleKey`. `baseDir` ("out"/"in", or
    * None) is the pin's direction from the instance port's perspective;
    * `parentPath` is the dotted path prefix (e.g. "xbar.io.in[0]"). */
  def portTree(bundleKey: String, baseDir: Option[String], parentPath: String, design: Design): PortNode =
    design.bundles.get(bundleKey) match {
      case None =>
        bundleKey match {
          case VecRe(inner0, nStr) =>
            // Vec[Inner, n]: n indexed elements of type Inner (which may itself
            // be a Vec/bundle, so match greedily and recurse).
            val inner = inner0.trim
            val n     = nStr.toInt
            val vchildren = (0 until n).map { i =>
              val childPath = parentPath + "[" + i + "]"
              val sub       = portTree(inner, baseDir, childPath, design)
              PortNode("[" + i + "]", childPath, inner, isAggregateType(inner, design), baseDir, sub.width, sub.children)
            }
            PortNode("", parentPath, bundleKey, isExpandable = true, baseDir, 0, vchildren)
          case _ =>
            // Ground/leaf: extract a width from "UInt<8>" -> 8.
            val w = bundleKey match { case GroundRe(ws) => ws.toInt; case _ => 0 }
            PortNode("", parentPath, bundleKey, isExpandable = false, baseDir, w, Nil)
        }
      case Some(b) =>
        val children =
          if (b.kind == BundleKind.Bundle)
            b.channels.map { ch =>
              // Only flip against a KNOWN base direction; with baseDir None the
              // child stays None so no wrong arrow is rendered (issue #85).
              val childDir  = baseDir.map(bd => if (ch.flip) flip(bd) else bd)
              val childPath = if (parentPath.nonEmpty) parentPath + "." + ch.name else ch.name
              val sub       = portTree(ch.`type`, childDir, childPath, design)
              PortNode(ch.name, childPath, ch.`type`, isAggregateType(ch.`type`, design), childDir, sub.width, sub.children)
            }
          else if (b.kind == BundleKind.Decoupled) {
            // valid goes the port's way, ready the other way (#313), then each bits field.
            val valid = PortNode("valid", parentPath + ".valid", "Bool", isExpandable = false, baseDir, 1, Nil)
            val ready = PortNode("ready", parentPath + ".ready", "Bool", isExpandable = false, baseDir.map(flip), 1, Nil)
            val bits  = b.fields.map { f =>
              // Flip field directions only for a consumer-side port (baseDir "in").
              val d = if (baseDir.contains(PortDir.In)) flip(f.dir) else f.dir
              // A Decoupled of a ground type (Queue[UInt]) has one unnamed field: `bits` itself.
              val name = if (f.name.isEmpty) "bits" else "bits." + f.name
              PortNode(name, parentPath + "." + name, "UInt<" + f.width + ">",
                isExpandable = false, Some(d), f.width, Nil)
            }
            // Two or more fields: one `bits` row until opened (#334). Its key is
            // synthetic (`<key>#bits`), never a design bundle; paths do not change.
            if (bits.size < 2) valid +: ready +: bits
            else {
              val fields = bits.map(n => n.copy(name = n.name.stripPrefix("bits.")))
              Seq(valid, ready, PortNode("bits", parentPath + ".bits", bundleKey + "#bits", isExpandable = true,
                fields.headOption.flatMap(_.dir), 0, fields))
            }
          }
          else Nil
        PortNode("", parentPath, bundleKey, isExpandable = true, baseDir, 0, children)
    }

  /** Resolve the true direction ("out"/"in") of an instance-prefixed pin path
    * ("xbar.io.in[0]") by walking the instance type's port through the bundle
    * tree. None when unresolvable. */
  def resolvePinDir(instType: String, pinPath: String, design: Design): Option[String] = {
    val modOpt = design.modules.find(_.name == instType)
    if (modOpt.isEmpty) return None
    val rel  = pinPath.replaceFirst("""^[^.\[]+\.""", "")
    val port = modOpt.get.ports.find(p => rel == p.name || rel.startsWith(p.name + ".") || rel.startsWith(p.name + "["))
    if (port.isEmpty) return None
    var dir  = if (port.get.dir == PortDir.Out || port.get.dir == PortDir.Master) PortDir.Out else PortDir.In
    var key  = port.get.bundle
    var rest = rel.substring(port.get.name.length)
    val idxRe = """^\[(\d+)\]""".r
    val segRe = """^\.([^.\[]+)""".r
    while (rest.nonEmpty) {
      idxRe.findPrefixMatchOf(rest) match {
        case Some(im) =>
          key match {
            case VecRe(inner, _) => key = inner.trim; rest = rest.substring(im.matched.length)
            case _               => return Some(dir)
          }
        case None =>
          segRe.findPrefixMatchOf(rest) match {
            case None => return Some(dir)
            case Some(sm) =>
              val seg = sm.group(1)
              design.bundles.get(key) match {
                case Some(b) if b.kind == BundleKind.Bundle =>
                  b.channels.find(_.name == seg) match {
                    case None     => return Some(dir)
                    case Some(ch) => if (ch.flip) dir = flip(dir); key = ch.`type`
                  }
                case _ => return Some(dir)
              }
              rest = rest.substring(sm.matched.length)
          }
      }
    }
    Some(dir)
  }

  /** The name a type key is shown by, at Chisel's level (#333): a captured
    * bundle's Scala class (`DecoupledIO`, `GCDInput`), a shape the CHIRRTL walk
    * recognises (`AXI4`, `Decoupled`, `ReadyValid`), a ground type as is, a Vec
    * with its element's name. "" for an anonymous bundle (`new Bundle {...}`,
    * or a CHIRRTL key named after its port, `Io_284312`): the port's own name
    * says it. Never a structural hash. */
  def displayName(typeKey: String, design: Design): String = if (typeKey.contains('#')) "" else typeKey match {
    case VecRe(inner, n) =>
      val e = displayName(inner.trim, design)
      if (e.isEmpty) s"Vec[$n]" else s"Vec[$e, $n]"
    case _ =>
      design.bundles.get(typeKey) match {
        case None => typeKey
        case Some(b) if b.scalaClass.nonEmpty =>
          val simple = b.scalaClass.substring(b.scalaClass.lastIndexOf('.') + 1)
          if (simple.contains("$anon")) "" else simple.substring(simple.lastIndexOf('$') + 1)
        case Some(_) =>
          if (typeKey == "AXI4") "AXI4"
          else if (typeKey.startsWith("Decoupled_")) "Decoupled"
          else if (typeKey.startsWith("ReadyValid_")) "ReadyValid"
          else ""
      }
  }

  /** A closed `bits` row's fields, `name width` joined (`pc 32b, inst 32b`). */
  def bitsSummary(node: PortNode): String =
    node.children.map(c => s"${c.name} ${c.width}b").mkString(", ")

  private def flip(d: String): String = if (d == PortDir.Out) PortDir.In else PortDir.Out
}
