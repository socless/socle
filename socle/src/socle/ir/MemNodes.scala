package socle.ir

import socle.schema._
import socle.parse.ChirrtlParser.{Mod, Conn, MemDecl, MPortDecl, SrcInfo}
import socle.layout.LayoutCore.stripIdx

/** Memory rendering (issue #205): turn cmem/smem decls + their mport access
  * ports into mem `LogicNodeDef`s, rewrite mport references in the connect
  * stream to encoded mem pins, and emit the address edges. The encoded pins
  * (`<mem>~<port>~{a,w,r}`) are NetTrace terminals, exactly like register and
  * op-node pins, so the wire pipeline routes dataflow through the memory. */
object MemNodes {

  /** @param logic     mem LogicNodeDefs (kind="mem", with memInfo).
    * @param ids       mem node ids (added to the NetTrace terminal set).
    * @param addrEdges NetTrace (sink, source) edges feeding each address pin.
    * @param conns     the connect stream with mport refs rewritten to mem pins. */
  final case class Result(
    logic:     Seq[LogicNodeDef],
    ids:       Set[String],
    addrEdges: Seq[(String, String)],
    conns:     Seq[Conn],
  )

  private def headOf(path: String): String = stripIdx(path.split('.').head)
  private def toRef(s: SrcInfo): SourceRef = SourceRef(s.file, s.line, s.col)

  def build(m: Mod): Result = {
    // Only cmem/smem (which carry an inline element type) render as nodes; the
    // bare structured `mem` block (elem == "") is out of scope.
    val memDecls: Seq[MemDecl] = m.mems.filter(_.elem.nonEmpty)
    if (memDecls.isEmpty) return Result(Nil, Set.empty, Nil, m.conns)
    val memNames = memDecls.map(_.name).toSet
    val mports   = m.mports.filter(p => memNames.contains(p.mem))

    // Direction per mport: explicit read/write/rdwr honored; "infer" resolved
    // from connect usage (rhs source => read, lhs sink => write, both => rdwr).
    def dirOf(p: MPortDecl): String = p.mode match {
      case "read" | "write" | "rdwr" => p.mode
      case _ =>
        val asSource = m.conns.exists(c => headOf(c.rhs) == p.name)
        val asSink   = m.conns.exists(c => headOf(c.lhs) == p.name)
        if (asSource && asSink) "rdwr" else if (asSink) "write" else "read"
    }

    // Compute direction once per mport into a map, then reuse below.
    val dirByName: Map[String, String] = mports.map(p => p.name -> dirOf(p)).toMap

    // Stable per-mem role tags: R0,R1,... then W0,... then RW0,... in source order.
    val tagOf = scala.collection.mutable.HashMap.empty[String, String]
    memDecls.foreach { md =>
      val ps = mports.filter(_.mem == md.name)
      var ri = 0; var wi = 0; var rwi = 0
      ps.foreach { p =>
        val t = dirByName(p.name) match {
          case "read"  => val t = s"R$ri";  ri  += 1; t
          case "write" => val t = s"W$wi";  wi  += 1; t
          case _       => val t = s"RW$rwi"; rwi += 1; t
        }
        tagOf(p.name) = t
      }
    }

    val portByName = mports.map(p => p.name -> p).toMap

    // Build the mem LogicNodeDefs with memInfo (port list in source order).
    val logic: Seq[LogicNodeDef] = memDecls.map { md =>
      val ports = mports.filter(_.mem == md.name).map { p =>
        MemPortDef(tag = tagOf(p.name), dir = dirByName(p.name), name = p.name,
          source = p.src.map(toRef).getOrElse(SourceRef.unknown))
      }
      LogicNodeDef(id = md.name, kind = NodeKind.Mem, label = md.name, bundle = "",
        source = md.src.map(toRef).getOrElse(SourceRef.unknown),
        memInfo = Some(MemInfo(md.depth, md.elem, md.sync, ports)))
    }

    // Address edges (sink = address pin, source = address expr).
    val addrEdges: Seq[(String, String)] = mports.map { p =>
      (Synthetic.memPin(p.mem, p.name, "a"), p.addr)
    }

    // Rewrite mport refs in connects: a head equal to an mport name becomes that
    // port's read pin on the RHS (source) or write pin on the LHS (sink); any
    // `.field` tail (bundle memory) is preserved.
    def rewrite(path: String, asSink: Boolean): String = {
      val parts = path.split('.')
      portByName.get(parts.head) match {
        case Some(p) =>
          val role = if (asSink) "w" else "r"
          (Synthetic.memPin(p.mem, p.name, role) +: parts.tail).mkString(".")
        case None => path
      }
    }
    val conns = m.conns.map(c => c.copy(lhs = rewrite(c.lhs, asSink = true),
                                        rhs = rewrite(c.rhs, asSink = false)))

    Result(logic, memNames, addrEdges, conns)
  }
}
