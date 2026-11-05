package socle.ir

import socle.schema._
import socle.parse._
import socle.layout._
import scala.collection.mutable
import socle.parse.ChirrtlParser.{
  Circ, Mod, Port, Inst, Conn, Field, SrcInfo, ModuleDecl,
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

  // Op classification (issue #188 B2). Logic ops are rendered as terminal nodes
  // (their value is a net terminal: input pins `_T~in<i>`, output `_T`).
  // Plumbing ops are contracted: NetTrace traces THROUGH their value so they
  // never appear as logic nodes.
  // Recognized logic ops (rendered): mux, add, sub, mul, div, rem, neg,
  //   lt, leq, gt, geq, eq, neq, and, or, xor, not, andr, orr, xorr,
  //   shl, shr, dshl, dshr - i.e. any op not in PlumbingOps below.
  private val PlumbingOps = Set(
    "bits", "head", "tail", "cat", "pad",
    "asUInt", "asSInt", "asClock", "asAsyncReset", "cvt", "validif")
  // Logic (rendered) vs plumbing (contracted). An empty op (bare alias
  // `node _T = ref`) is plumbing. An unknown op renders generically.
  private def isLogicOp(op: String): Boolean =
    op.nonEmpty && !PlumbingOps.contains(op)

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

  /** AXI4 bundle shape: exactly the five channel fields. The port-role
    * classifier and the bundle-key namer both route through this one predicate
    * (issue #217) so "what counts as AXI4" lives in a single place. */
  private def isAxi4(fields: Set[String]): Boolean =
    fields == Set("ar", "aw", "w", "r", "b")

  /** Decoupled handshake shape: ready + valid + bits payload. */
  private def isDecoupled(fields: Set[String]): Boolean =
    fields == Set("ready", "valid", "bits")

  /** Bare ready/valid handshake (no bits payload). */
  private def isReadyValid(fields: Set[String]): Boolean =
    fields == Set("ready", "valid")

  /** Render up to `max` names inline for a warning message, summarizing the rest
    * as "(+N more)" so a design with many offenders stays readable. */
  private def examples(names: Seq[String], max: Int = 5): String = {
    val shown = names.take(max).mkString(", ")
    if (names.size > max) s"$shown (+${names.size - max} more)" else shown
  }

  /** @param lines    the source lines the `when` markers point at: which `when`s
    *                 are user-written blocks (`CodeBlocks`).
    * @param collapse fold the code blocks into boxes; false keeps their
    *                 terminals in the module (tests of the routing). */
  def design(c: Circ, lines: SourceLines = SourceLines.none, collapse: Boolean = true): Design = {
    val bundles = new BundleReg
    // Cross-module name lookup. The wire bundle inference for `<inst>.<port>`
    // endpoints needs to read port types off the instance's own module,
    // which lives elsewhere in the circuit. Building it once here is cheaper
    // than searching per wire.
    val byName  = c.modules.map(m => m.name -> m).toMap
    val built   = c.modules.map(buildModule(_, byName, bundles, lines, collapse))
    val modules = built.map(_._1)
    // Code-block bodies: each module contributes zero or more (box id -> body)
    // entries, nested boxes included; merge into one regions map for the Design.
    val regions = built.flatMap(_._2).toMap
    // Carry parser warnings through, and add a walk-level one: wires whose
    // endpoint type could not be resolved fall back to bundle "Wire" (issue
    // #118 - the user should know those are best-effort, not real types).
    // Wires between intra-module logic nodes (const -> op, op -> op, reg) are
    // anonymous ground nets by nature: they carry no named bundle, so a "Wire"
    // label is expected rather than a resolution failure. Counting them made the
    // warning noisy after B2 added op nodes (issue #188). Count only wires whose
    // endpoints are instances/module ports - a genuinely unresolvable type - so
    // the warning stays meaningful (issue #118).
    val unresolved = modules.iterator.map { m =>
      val logicIds = m.logic.iterator.map(_.id).toSet
      def isLogicEndpoint(e: String): Boolean = {
        val head = e.split('.').headOption.getOrElse("")
        head.startsWith(Synthetic.ConstPrefix) || logicIds.contains(head) || logicIds.contains(Synthetic.baseOf(head))
      }
      m.wires.count(w => w.bundle == "Wire" && !(isLogicEndpoint(w.from) || isLogicEndpoint(w.to)))
    }.sum
    // Unconnected ports: a data port whose name never appears anywhere in its
    // module body (the same `connected` heuristic the UI badge uses, issue #74).
    // Blackbox ports default to connected=true in buildModule, so only real
    // dangling pins are counted; this also folds in "driverless outputs" since an
    // output that is never referenced is never driven. Clock and reset are excluded
    // on purpose: they are driven by the parent at the instantiation site and being
    // unused inside a combinational leaf is normal, not a defect, so flagging them
    // would bury the genuine data-port findings in noise.
    def isClockOrReset(p: PortDef): Boolean =
      p.name == "clock" || p.name == "reset" ||
      p.bundle == "Clock" || p.bundle == "Reset" || p.bundle == "AsyncReset"
    val unconnectedPorts =
      modules.flatMap(m =>
        m.ports.iterator.filter(p => !p.connected && !isClockOrReset(p)).map(p => s"${m.name}.${p.name}"))
    // Empty modules: a real `module` (not a blackbox extmodule/intmodule) that
    // neither connects nor instantiates anything, so it contributes no dataflow.
    val emptyModules =
      c.modules.filter(m => m.kind == ModuleDecl.Module && m.conns.isEmpty && m.insts.isEmpty).map(_.name)

    val walkWarnings =
      (if (unresolved > 0)
        Seq(Warning("unresolved-endpoint", unresolved,
          s"$unresolved wire(s) had an unresolvable endpoint type and were labeled 'Wire'"))
      else Seq.empty) ++
      (if (unconnectedPorts.nonEmpty)
        Seq(Warning("unconnected-port", unconnectedPorts.size,
          s"${unconnectedPorts.size} unconnected port(s): ${examples(unconnectedPorts)}"))
      else Seq.empty) ++
      (if (emptyModules.nonEmpty)
        Seq(Warning("empty-module", emptyModules.size,
          s"${emptyModules.size} module(s) with no connects or instances: ${examples(emptyModules)}"))
      else Seq.empty)
    Design(top = c.name, bundles = bundles.defs.toMap, modules = modules,
      warnings = c.warnings ++ walkWarnings, regions = regions)
  }

  // ── Module ────────────────────────────────────────────────────────────────
  private def buildModule(
    m:       Mod,
    byName:  Map[String, Mod],
    bundles: BundleReg,
    lines:   SourceLines,
    collapse: Boolean,
  ): (ModuleDef, Map[String, ModuleDef]) = {
    // A port is "connected" if its name is referenced anywhere in the module
    // body (connects, register/memory clocks+resets, node expressions, when
    // conditions, ...). m.refs is collected from every statement except port
    // declarations, so clock/reset and expression-only inputs count as used and
    // only genuinely-dangling ports are flagged unconnected (issue #74).
    // Blackboxes (extmodule/intmodule) have no statements at all, so the
    // heuristic cannot apply: default their ports to connected rather than
    // flagging every blackbox pin as dangling (issue #100).
    val isRealModule = m.kind == ModuleDecl.Module
    val ports = m.ports.map(p => buildPort(p, bundles, !isRealModule || m.refs.contains(p.name)))
    // Clock/reset domain inference (issue #19): the connect that feeds an
    // instance's clock (or reset) port names the domain signal; siblings sharing
    // it share a domain. We find that port on the *child* module's interface
    // (clock by type or name, reset by type or name since reset is often
    // lowered to UInt<1>) and look up `<inst>.<port>` on the LHS of a connect.
    def driverOf(instName: String, portNames: Seq[String]): String =
      portNames.iterator
        .flatMap(pn => m.conns.collectFirst { case Conn(lhs, rhs, _, _, _) if lhs.trim == s"$instName.$pn" => rhs.trim })
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
    // Register logic nodes (issue #188 B1): each reg becomes a node whose name is
    // a net terminal, so wires route THROUGH it (input pin `r`, output pin `r~q`).
    val regNames: Set[String] = m.regs.map(_.name).toSet
    val regLogic: Seq[LogicNodeDef] = m.regs.map { r =>
      val key = registerType(r.tpe, r.name, bundles)
      LogicNodeDef(id = r.name, kind = NodeKind.Reg, label = r.name, bundle = key,
        source = r.src.map(toRef).getOrElse(SourceRef.unknown))
    }
    val regType: Map[String, String] = regLogic.map(n => n.id -> n.bundle).toMap
    // Combinational logic nodes (issue #188 B2). Logic ops render as nodes whose
    // value is a net terminal (input pins `_T~in<i>`, output `_T`); plumbing ops
    // are contracted (NetTrace traces through their value).
    val logicNodes = m.nodes.filter(n => isLogicOp(n.op))
    val opLogic: Seq[LogicNodeDef] = logicNodes.map { n =>
      LogicNodeDef(id = n.name, kind = NodeKind.Op, label = n.op, bundle = "",
        source = n.src.map(toRef).getOrElse(SourceRef.unknown), op = n.op)
    }
    val logicNodeNames: Set[String] = logicNodes.map(_.name).toSet
    // Edges contributed by node declarations, in NetTrace (sink, source) form.
    // Logic ops encode the input-pin index in the sink so the operands do not
    // collapse onto one pin; plumbing nodes feed their bare value (contracted).
    val nodeEdges: Seq[(String, String)] = m.nodes.flatMap { n =>
      if (isLogicOp(n.op))
        n.operands.zipWithIndex.map { case (arg, i) => (Synthetic.opInPin(n.name, i), arg) }
      else
        n.operands.map(arg => (n.name, arg))
    }
    // Memory nodes (issue #205): mem LogicNodeDefs + address edges, and the
    // connect stream rewritten so mport refs route through encoded mem pins.
    val mem = MemNodes.build(m)
    // Code blocks: the user's `when` / `switch` blocks (CodeBlocks). A connect
    // inside one leaves the stream for an assignment terminal of its innermost
    // block (one per block and sink, an input pin per connect, its output drives
    // the sink), and each arm condition feeds the block's condition terminal.
    // The collapse folds the terminals into the block's box.
    val blocks: Seq[CodeBlock] = CodeBlocks.of(m, lines)
    // Built once: CodeBlocks.blockOf scans the blocks per call (big modules).
    val blockOfWhen: Map[Int, String] = blocks.iterator.flatMap(b => b.whenIds.iterator.map(_ -> b.id)).toMap
    val routed: Seq[(Conn, Option[String])] =
      mem.conns.map(c => (c, c.whenIds.reverseIterator.flatMap(blockOfWhen.get).nextOption()))
    val outsideConns: Seq[Conn] = routed.collect { case (c, None) => c }
    // Slice 1 of #210: fold simple depth-1 conditional connects into mux nodes.
    // The folded connects are removed from the stream; the mux nodes are
    // terminals (like other op nodes) and their pin edges join nodeEdges. It
    // sees only the connects outside every block.
    val muxResult  = WhenMux.synthesize(outsideConns, name => regNames.contains(stripIdx(name)))
    val muxIds     = muxResult.nodes.map(_.id).toSet
    // The stream in source order: kept outside connects, and in place of each
    // block connect its terminal edges (as connects, so wires keep the marker).
    val termInputs: mutable.LinkedHashMap[String, Int] = mutable.LinkedHashMap.empty
    def nextPin(term: String): String = {
      val k: Int = termInputs.getOrElse(term, 0)
      termInputs(term) = k + 1
      Synthetic.opInPin(term, k)
    }
    val keptConns: mutable.ArrayBuffer[Conn] = mutable.ArrayBuffer.empty
    val termBlock: mutable.Map[String, String] = mutable.Map.empty // terminal id -> block id
    // One terminal per (block, sink); two sinks that sanitize alike (`io.a.b`,
    // `io.a_b`) get `_2`, `_3`... like WhenMux's ids.
    val asgIds: mutable.Map[(String, String), String] = mutable.Map.empty
    val usedAsg: mutable.Set[String] = mutable.Set.empty
    var outsideAt: Int = 0
    routed.foreach {
      case (c, None) =>
        if (!muxResult.consumed(outsideAt)) keptConns += c
        outsideAt += 1
      case (c, Some(b)) =>
        val asg: String = asgIds.getOrElseUpdate((b, c.lhs), {
          val base: String = Synthetic.assignId(b, c.lhs)
          val id: String = (base #:: LazyList.from(2).map(n => s"${base}_$n")).find(!usedAsg(_)).get
          usedAsg += id
          keptConns += Conn(c.lhs, id, c.src)
          id
        })
        termBlock(asg) = b
        keptConns += Conn(nextPin(asg), c.rhs, c.src)
    }
    // A block's conditions: its arms', and those of the `when`s that are not
    // blocks (a library `when`, or no source line to read) around it up to its
    // parent block or inside it: the block reads them, as it owns the connects
    // they guard. One pin per signal.
    val whenById: Map[Int, ChirrtlParser.WhenDecl] = m.whens.map(w => w.id -> w).toMap
    val armsOf: Map[String, Seq[ChirrtlParser.WhenDecl]] =
      m.whens.filter(w => blockOfWhen.contains(w.id)).groupBy(w => blockOfWhen(w.id))
    val innerConds: Map[String, Seq[ChirrtlParser.WhenDecl]] =
      m.whens.filterNot(w => blockOfWhen.contains(w.id)).flatMap { w =>
        w.parents.reverseIterator.flatMap(p => blockOfWhen.get(p._1)).nextOption().map(b => (b, w))
      }.groupBy(_._1).map { case (b, cs) => b -> cs.map(_._2) }
    def condsOf(b: CodeBlock): Seq[ChirrtlParser.WhenDecl] = {
      val arms: Seq[ChirrtlParser.WhenDecl] = armsOf.getOrElse(b.id, Nil)
      val outer: Seq[ChirrtlParser.WhenDecl] = arms.headOption.toSeq.flatMap { root =>
        root.parents.reverse.takeWhile(p => !blockOfWhen.contains(p._1)).reverse.flatMap(p => whenById.get(p._1))
      }
      (outer ++ arms ++ innerConds.getOrElse(b.id, Nil)).distinctBy(_.cond.trim)
    }
    // No condition terminal for a block without conditions. Each condition pin
    // keeps its condition's CHIRRTL text, and the arm's source text when the
    // block's own arm wrote it (`io.in.fire` for `when _T`): its box pin's label.
    val condShown: mutable.Map[String, String] = mutable.Map.empty
    val condEdges: Seq[(String, String)] = blocks.flatMap { b =>
      val conds: Seq[ChirrtlParser.WhenDecl] = condsOf(b)
      val written: Map[String, String] = armsOf.getOrElse(b.id, Nil)
        .flatMap(a => b.armConds.get(a.id).map(a.cond.trim -> _)).toMap
      if (conds.nonEmpty) termBlock(Synthetic.condId(b.id)) = b.id
      conds.map { w =>
        val pin: String = nextPin(Synthetic.condId(b.id))
        written.get(w.cond.trim).foreach(condShown(pin) = _)
        (pin, w.cond.trim)
      }
    }
    val condTextOf: Map[String, String] = condEdges.toMap
    val termLogic: Seq[LogicNodeDef] = termInputs.keys.toSeq.map { id =>
      LogicNodeDef(id = id, kind = NodeKind.Op, label = "", bundle = "", source = SourceRef.unknown, op = "terminal")
    }
    val (wires, constLogic) =
      coalesceWires(m, keptConns.toSeq, byName, bundles, regNames, regType,
        logicNodeNames ++ muxIds ++ mem.ids ++ termInputs.keySet,
        nodeEdges ++ muxResult.edges ++ mem.addrEdges ++ condEdges)
    val metrics = Metrics(
      registers = m.regs.size,
      wires     = m.conns.size,
      lines     = approxLines(m),
      gates     = None,
      memories  = m.mems.size,
    )
    val allLogic = regLogic ++ constLogic ++ opLogic ++ muxResult.nodes ++ mem.logic ++ termLogic
    // Verification-layer flagging (issue #212): mark logic nodes that are Chisel
    // layer content so the UI can hide them behind "show layers". Seeds: nodes
    // declared inside a `layerblock`, or a module-level condition node the layer
    // reads that drives nothing in the design (its only consumer was the dropped
    // assertion intrinsic). Then fold any node whose every design consumer is
    // already a layer member (a chain of condition nodes feeding the assertion).
    val layerFlagged: Seq[LogicNodeDef] = {
      val interior = m.nodes.filter(_.inLayer).map(_.name).toSet
      // Wires are stored (from = sink, to = source). A node "drives something"
      // when it is the source (to) of some wire; its consumers are the sinks
      // (from) of the wires it sources.
      val drivers  = wires.iterator.map(w => stripIdx(w.to.split('.').headOption.getOrElse(""))).toSet
      def isOpOrConst(n: LogicNodeDef) = n.kind == NodeKind.Op || n.kind == NodeKind.Const
      val members  = mutable.Set.empty[String]
      allLogic.foreach { n =>
        if (isOpOrConst(n) && (interior.contains(n.id) || (m.layerConsumes.contains(n.id) && !drivers.contains(n.id))))
          members += n.id
      }
      // Built once: rescanning every wire per node per pass was quadratic on large modules.
      val consumersOf = mutable.HashMap.empty[String, mutable.ArrayBuffer[String]]
      wires.foreach { w =>
        consumersOf.getOrElseUpdate(stripIdx(w.to.split('.').headOption.getOrElse("")), mutable.ArrayBuffer.empty) +=
          Synthetic.baseOf(stripIdx(w.from.split('.').headOption.getOrElse("")))
      }
      def consumers(id: String): collection.Seq[String] = consumersOf.getOrElse(id, Nil)
      var changed = true
      while (changed) {
        changed = false
        allLogic.foreach { n =>
          if (isOpOrConst(n) && !members.contains(n.id)) {
            val cons = consumers(n.id)
            if (cons.nonEmpty && cons.forall(members.contains)) { members += n.id; changed = true }
          }
        }
      }
      allLogic.map(n => if (members.contains(n.id)) n.copy(layer = true) else n)
    }
    val open = ModuleDef(
      name     = m.name,
      `type`   = m.name,
      doc      = "",
      source   = m.src.map(toRef).getOrElse(SourceRef.unknown),
      ports    = ports,
      children = children,
      wires    = wires,
      metrics  = metrics,
      kind     = moduleKind(m.kind),
      logic    = layerFlagged,
    )
    // Each code block becomes a box, innermost first; its body goes to the regions.
    if (collapse)
      collapseBlocks(m, open, blocks, blockOfWhen, termBlock, asgIds.iterator.map { case ((_, lhs), id) => id -> lhs }.toMap,
        condTextOf, condShown.toMap)
    else (open, Map.empty[String, ModuleDef])
  }

  /** Collapse the code blocks of `m` into boxes (one per block, innermost
    * first, with `LogicBlocks.collapse`) and splice their terminals out of the
    * bodies. A block's members: the op nodes declared in it, its conditions'
    * expression cones (wherever declared, see below), its terminals, the
    * consts that feed only those, and its child blocks' boxes. Registers and
    * memories stay where they are declared. A terminal's out pin is named
    * after its sink (one pin per sink, shared by the block's own and its
    * children's assignments).
    *
    * Conditions: each condition of a block is one pin of its box, `cond`
    * (`cond_2`...), labelled with the condition as written (`io.in.fire`), or
    * else as its expression (`state === 1`), never a CHIRRTL temp. The pin is
    * the condition's marker in the body, and its direction follows the value:
    *  - a signal from outside the box (a port, a register, a node the module
    *    or another box also reads) enters on an in pin `cond`;
    *  - an expression only the block reads moves into the box with its whole
    *    cone (every operand node read only by that cone or the block), and the
    *    cone's root drives an out pin `cond` inside the body (nothing outside
    *    reads it: it shows what the box computes its arms from). */
  private def collapseBlocks(
    m:           Mod,
    open:        ModuleDef,
    blocks:      Seq[CodeBlock],
    blockOfWhen: Map[Int, String],
    termBlock:   collection.Map[String, String],
    asgLhs:      Map[String, String],
    condTextOf:  Map[String, String],
    condShown:   Map[String, String],
  ): (ModuleDef, Map[String, ModuleDef]) = {
    if (blocks.isEmpty) return (open, Map.empty)
    val kindOf: Map[String, String] = open.logic.iterator.map(n => n.id -> n.kind).toMap
    def nodeOf(e: String): Option[String] =
      if (kindOf.contains(e)) Some(e) else Some(fsmHeadBase(e)).filter(kindOf.contains)
    val parentOf: Map[String, String] = blocks.flatMap(b => b.parent.map(p => b.id -> p)).toMap
    // Is block `x` block `b` or nested in it?
    @scala.annotation.tailrec
    def within(b: String, x: String): Boolean = x == b || (parentOf.get(x) match {
      case Some(p) => within(b, p)
      case None    => false
    })
    // Node id -> its block: the innermost block it is declared in, the
    // terminals, the condition cones; then the consts all of whose consumers
    // belong to one block (one pass over the wires, indexed by const).
    val owner: mutable.HashMap[String, String] = mutable.HashMap.empty
    m.nodes.foreach { n =>
      n.whenIds.reverseIterator.flatMap(blockOfWhen.get).nextOption().foreach(b => owner(n.name) = b)
    }
    owner ++= termBlock
    // Each node's consumers (the sinks of the wires it drives) and operand
    // sources, by node id, for the condition cones.
    val sinksOf: mutable.HashMap[String, mutable.ArrayBuffer[String]] = mutable.HashMap.empty
    val sourcesOf: mutable.HashMap[String, mutable.ArrayBuffer[String]] = mutable.HashMap.empty
    open.wires.foreach { w =>
      nodeOf(w.to).foreach(n => sinksOf.getOrElseUpdate(n, mutable.ArrayBuffer.empty) += w.from)
      for (snk <- nodeOf(w.from); src <- nodeOf(w.to))
        sourcesOf.getOrElseUpdate(snk, mutable.ArrayBuffer.empty) += src
    }
    // A block's condition cone, children first (a node a child's condition and
    // its parent both read joins the parent): the op nodes that feed its
    // condition terminal and that only the block (its nested blocks included)
    // or the cone reads, grown to a fixpoint.
    val coneOf: mutable.HashMap[String, Set[String]] = mutable.HashMap.empty
    blocks.reverse.foreach { b =>
      val cone: mutable.Set[String] = mutable.Set.empty
      def readInside(sink: String): Boolean =
        nodeOf(sink).exists(id => cone(id) || owner.get(id).exists(within(b.id, _)))
      def joins(y: String): Boolean =
        !cone(y) && kindOf.get(y).contains(NodeKind.Op) && !Synthetic.isTerminal(y) &&
          owner.get(y).forall(o => o == b.id || within(o, b.id)) &&
          sinksOf.getOrElse(y, Nil).forall(readInside)
      var changed: Boolean = true
      while (changed) {
        val next: Seq[String] = (Seq(Synthetic.condId(b.id)) ++ cone).flatMap(sourcesOf.getOrElse(_, Nil)).distinct.filter(joins)
        changed = next.nonEmpty
        next.foreach { y => cone += y; owner(y) = b.id }
      }
      coneOf(b.id) = cone.toSet
    }
    val constConsumers: mutable.LinkedHashMap[String, mutable.ArrayBuffer[String]] = mutable.LinkedHashMap.empty
    open.wires.foreach { w =>
      if (kindOf.get(w.to).contains(NodeKind.Const))
        constConsumers.getOrElseUpdate(w.to, mutable.ArrayBuffer.empty) += w.from
    }
    def opOwner(id: String): Option[String] =
      if (kindOf.get(id).contains(NodeKind.Op)) owner.get(id) else None
    // A const read only as a condition stays outside: the splice drops the
    // condition terminal's wires, so inside it would be left unwired.
    constConsumers.foreach { case (c, sinks) =>
      val bs: Set[Option[String]] = sinks.iterator.map(s => nodeOf(s).flatMap(opOwner)).toSet
      val condOnly: Boolean = sinks.forall(s => fsmHeadBase(s).startsWith(Synthetic.CondPrefix))
      if (bs.size == 1 && !condOnly) bs.head.foreach(b => owner(c) = b)
    }
    val membersOf: Map[String, Set[String]] =
      open.logic
        .filter(n => n.kind == NodeKind.Op || n.kind == NodeKind.Const)
        .flatMap(n => owner.get(n.id).map(b => (b, n.id)))
        .groupBy(_._1).map { case (b, ms) => b -> ms.map(_._2).toSet }
    val childrenOf: Map[String, Seq[String]] =
      blocks.flatMap(b => b.parent.map(p => (p, b.id))).groupBy(_._1).map { case (p, cs) => p -> cs.map(_._2) }
    // A box is layer content (hidden with the layers) when it assigns nothing
    // and all it holds apart from its condition's cone, its op and const nodes
    // and its child boxes, is: a `when(c) { assert(...) }`. Its cone (the ops
    // and the consts only they read) is then layer content too. Children
    // first, so a parent sees its children.
    val layerNode: Set[String] = open.logic.iterator.filter(_.layer).map(_.id).toSet
    val layerBox: mutable.Set[String] = mutable.Set.empty
    val layerCone: mutable.Set[String] = mutable.Set.empty
    blocks.reverse.foreach { b =>
      val own: Set[String] = membersOf.getOrElse(b.id, Set.empty[String])
      val ops: Set[String] = coneOf.getOrElse(b.id, Set.empty[String])
      val consts: Set[String] = own.filter { c =>
        kindOf.get(c).contains(NodeKind.Const) &&
          constConsumers.get(c).exists(_.forall(s => nodeOf(s).exists(ops)))
      }
      val cone: Set[String] = ops ++ consts
      val held: Seq[String] = own.filterNot(id => Synthetic.isTerminal(id) || cone(id)).toSeq ++ childrenOf.getOrElse(b.id, Nil)
      val assigns: Boolean = own.exists(_.startsWith(Synthetic.AssignPrefix))
      if (!assigns && held.nonEmpty && held.forall(id => layerNode(id) || layerBox(id))) {
        layerBox += b.id
        layerCone ++= cone
      }
    }
    val flagged: ModuleDef =
      if (layerCone.isEmpty) open
      else open.copy(logic = open.logic.map(n => if (layerCone(n.id)) n.copy(layer = true) else n))
    val groups: Seq[socle.capture.Group] = blocks.reverse.map { b =>
      socle.capture.Group(
        id      = b.id,
        kind    = b.kind,
        label   = b.label,
        source  = b.src.map(toRef).getOrElse(SourceRef.unknown),
        layer   = layerBox(b.id),
        members = membersOf.getOrElse(b.id, Set.empty[String]) ++ childrenOf.getOrElse(b.id, Nil),
        state   = b.state,
      )
    }

    // Assignment pins, one per sink the block's connects write (not the wire's
    // far end, which NetTrace moves past a contracted Chisel `Wire`), named
    // after it: its own terminals' and its child boxes' assignment pins for
    // that sink. `asgPins` records each box's final pin names (onNamed).
    val asgPins: mutable.HashMap[(String, String), String] = mutable.HashMap.empty // (box, pin) -> sink
    val AsgKey: String = "asg>"
    val nodeDecls: Map[String, ChirrtlParser.NodeDecl] = m.nodes.iterator.map(n => n.name -> n).toMap
    // A memory write pin (`mem~port~w`) is shown as `mem.port`.
    def sinkLabel(sink: String): String =
      Synthetic.memPinParts(sink).fold(sink) { case (mem, port, _) => s"$mem.$port" }
    // A condition pin shows its arm's condition as written; else its `when`'s
    // condition, where a condition that is a node (`_T`, a cast or compare)
    // shows the user signal it traces to (no synthetic `$` id, no temp), or
    // else its expression.
    def condLabel(pin: String, external: String): String = condShown.getOrElse(pin, {
      val text: String = condTextOf.getOrElse(pin, external)
      val i: Int = external.indexOf('.')
      val traced: String =
        if (i < 0) Synthetic.stripQ(external) else Synthetic.stripQ(external.substring(0, i)) + external.substring(i)
      if (!nodeDecls.contains(text)) text
      else if (!external.contains('$') && !traced.startsWith("_")) traced
      else CodeBlocks.exprText(nodeDecls, text)
    })
    // A pin from or to a CHIRRTL temp op (`_T_1`, `_x_T`) is labelled with
    // its expression (`x =/= y`): its name alone says nothing.
    def tempOp(e: String): Option[String] =
      nodeOf(e).filter(id => id.startsWith("_") && kindOf.get(id).contains(NodeKind.Op) && !Synthetic.isTerminal(id) &&
        nodeDecls.contains(id))
    // The driver-named pin of a temp op, with its expression as the label.
    def tempPin(driver: String): Option[(String, String, String)] = tempOp(driver).map { id =>
      val (key, name) = socle.capture.LogicBlocks.driverPin(driver, Set.empty)
      (key, name, CodeBlocks.exprText(nodeDecls, id))
    }
    def assignPin(sink: String): Option[(String, String, String)] =
      Some((AsgKey + sink, socle.capture.LogicBlocks.driverPin(sink, Set.empty)._2, sinkLabel(sink)))
    val pinOverride: socle.capture.LogicBlocks.PinOverride = (member, external, dir) =>
      if (dir == PortDir.In && fsmHeadBase(member).startsWith(Synthetic.CondPrefix)) {
        val label: String = condLabel(member, external)
        Some((s"cnd>$label", "cond", label))
      } else if (dir == PortDir.In && isAsgOut(external) && asgLhs.contains(external))
        // Read from a block collapsed later (a sibling): named after what it assigns.
        Some((external, socle.capture.LogicBlocks.driverPin(asgLhs(external), Set.empty)._2, sinkLabel(asgLhs(external))))
      else if (dir == PortDir.Out && isAsgOut(member) && asgLhs.contains(member))
        assignPin(asgLhs(member))
      else if (dir == PortDir.Out) {
        val dot: Int = member.indexOf('.')
        val child: String = if (dot < 0) "" else member.substring(0, dot)
        asgPins.get((child, member.substring(dot + 1))) match {
          case Some(sink) if parentOf.contains(child) => assignPin(sink)
          case _                                      => tempPin(member)
        }
      } else tempPin(external)
    val onNamed: (String, String, String) => Unit = (box, key, name) =>
      if (key.startsWith(AsgKey)) asgPins((box, name)) = key.substring(AsgKey.length)

    val (collapsed, bodies) = socle.capture.LogicBlocks.collapse(flagged, groups, pinOverride = Some(pinOverride), onNamed = onNamed)
    // An expression condition computed inside its box: the cone's root drives
    // an out `cond` pin, labelled as the in pin of a signal would be.
    val outCondLabel: String => String = pin =>
      condShown.getOrElse(pin, CodeBlocks.exprText(nodeDecls, condTextOf.getOrElse(pin, pin)))
    (collapsed, bodies.map { case (id, body) => id -> spliceTerminals(body, outCondLabel) })
  }

  // An assignment terminal's output endpoint (`asg$...`, not one of its `~in<k>` pins).
  private def isAsgOut(e: String): Boolean = e.startsWith(Synthetic.AssignPrefix) && !e.contains('~')

  /** Splice the terminals out of a block body: `X <- asg` and `asg~inK <- Y`
    * become `X <- Y` (through chains of terminals: a wire assigned and read in
    * one block). A condition terminal's input from the box's in pin is dropped
    * (that pin is the condition's marker); one from a node of the body (an
    * expression condition computed inside) becomes `<box>.cond <- Y`, on a new
    * out pin labelled `condLabel(the terminal's pin)`. */
  private def spliceTerminals(body: ModuleDef, condLabel: String => String): ModuleDef = {
    def termOf(e: String): Option[String] = Some(fsmHeadBase(e)).filter(Synthetic.isTerminal)
    // Each assignment terminal's input wires, in order.
    val inputs: mutable.HashMap[String, mutable.ArrayBuffer[WireDef]] = mutable.HashMap.empty
    body.wires.foreach { w =>
      termOf(w.from).filter(t => t.startsWith(Synthetic.AssignPrefix) && w.from != t)
        .foreach(t => inputs.getOrElseUpdate(t, mutable.ArrayBuffer.empty) += w)
    }
    // The input wires of terminal `t` whose source is not a terminal (cycle-safe).
    def sources(t: String, seen: Set[String]): Seq[WireDef] =
      inputs.get(t).toSeq.flatten.flatMap { w =>
        if (isAsgOut(w.to)) { if (seen(w.to)) Nil else sources(w.to, seen + w.to) }
        else Seq(w)
      }
    val taken: mutable.Set[String] = body.ports.iterator.map(_.name).to(mutable.Set)
    val condPins: mutable.ArrayBuffer[PortDef] = mutable.ArrayBuffer.empty
    val pinPrefix: String = body.name + "."
    val wires: Seq[WireDef] = body.wires.flatMap { w =>
      termOf(w.from) match {
        case Some(t) if t.startsWith(Synthetic.CondPrefix) && !w.to.startsWith(pinPrefix) && termOf(w.to).isEmpty =>
          val name: String = ("cond" #:: LazyList.from(2).map(n => s"cond_$n")).find(!taken(_)).get
          taken += name
          val label: String = condLabel(w.from)
          condPins += PortDef(name = name, dir = PortDir.Out, bundle = "", doc = "", source = SourceRef.unknown,
            label = if (label == name) "" else label)
          Seq(w.copy(from = pinPrefix + name))
        case Some(_) => Nil // an assignment terminal's input (spliced below), or a condition read from its in pin
        case None if isAsgOut(w.to) =>
          sources(w.to, Set(w.to)).map(in => in.copy(from = w.from, bundle = if (in.bundle == "Wire") w.bundle else in.bundle))
        case None => Seq(w)
      }
    }
    val seen = mutable.LinkedHashSet.empty[(String, String)]
    body.copy(
      ports = body.ports ++ condPins,
      logic = body.logic.filterNot(n => Synthetic.isTerminal(n.id)),
      wires = wires.filter(w => seen.add((w.from, w.to))),
    )
  }

  // Head of a wire endpoint, reduced to the underlying node base id: strip a vec
  // index, then the `~q` (reg Q) / `~in<i>` (op input pin) IrWalk-synthetic
  // suffix off the head segment.
  private[socle] def fsmHeadBase(endpoint: String): String =
    Synthetic.baseOf(stripIdx(endpoint.split('.').headOption.getOrElse("")))

  /** Map the raw CHIRRTL module keyword to the schema's module kind.
    * extmodule (blackbox) -> "external", intmodule -> "intrinsic", else
    * "internal" (issue #20). */
  private def moduleKind(rawKind: String): String = rawKind match {
    case ModuleDecl.ExtModule => ModuleKind.External
    case ModuleDecl.IntModule => ModuleKind.Intrinsic
    case _           => ModuleKind.Internal
  }

  // ── Ports ────────────────────────────────────────────────────────────────
  private def buildPort(
    p:         Port,
    bundles:   BundleReg,
    connected: Boolean,
  ): PortDef = {
    val key = registerType(p.tpe, p.name, bundles)
    PortDef(
      name      = p.name,
      dir       = portRole(p.dir, p.tpe),
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
    case BundleT(fs) if isAxi4(fs.map(_.name).toSet) =>
      if (dir == "output") PortDir.Master else PortDir.Slave
    // The single boundary converting the CHIRRTL input/output keyword into the
    // schema's in/out vocabulary (issue #218); every non-AXI port routes here.
    case _ => if (dir == "output") PortDir.Out else PortDir.In
  }

  // ── Bundles: register + return key ────────────────────────────────────────
  /** Register `tpe` into `bundles` if needed and return its bundle key. The
    * port role/direction is computed separately by [[portRole]] (issue #218). */
  private def registerType(
    tpe:     CType,
    hint:    String,
    bundles: BundleReg,
  ): String = tpe match {
    case b: BundleT =>
      val key = bundleKey(b, hint, bundles)
      if (!bundles.defs.contains(key)) {
        // Insert a placeholder FIRST: it reserves the bundle's slot in the
        // LinkedHashMap (so a parent bundle is emitted before the children it
        // registers during bundleDef) and makes `defs.contains(key)` true,
        // guarding against re-entry for the same key. Overwritten in place
        // (LinkedHashMap keeps the original position on update) with the real def.
        bundles.defs(key) = BundleDef(key, BundleKind.Bundle)
        bundles.defs(key) = bundleDef(b, key, bundles)
      }
      key

    case VecT(elem, n) =>
      s"Vec[${registerType(elem, hint + "_elem", bundles)}, $n]"

    case GroundT("UInt", Some(w)) => s"UInt<$w>"
    case GroundT("SInt", Some(w)) => s"SInt<$w>"
    case GroundT("UInt", None)    => "UInt"
    case GroundT(other, _)        => other
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
        if (isAxi4(names)) "AXI4"
        else if (isDecoupled(names))  s"Decoupled_$hint"
        else if (isReadyValid(names)) s"ReadyValid_$hint"
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
    if (isDecoupled(b.fields.map(_.name).toSet)) {
      // Flatten the `bits` sub-bundle into FieldDefs; valid+ready are implicit.
      val bits = b.fields.find(_.name == "bits").map(_.tpe).getOrElse(GroundT("UInt", None))
      val fields = flattenGround(bits, "")
      BundleDef(name = key, kind = BundleKind.Decoupled, fields = fields)
    } else {
      // Heterogeneous bundle → register channels recursively.
      val channels = b.fields.map { f =>
        val innerKey = registerType(f.tpe, f.name, bundles)
        ChannelDef(name = f.name, `type` = innerKey, flip = f.flip)
      }
      BundleDef(name = key, kind = BundleKind.Bundle, channels = channels)
    }
  }

  private def flattenGround(t: CType, prefix: String): Seq[FieldDef] = t match {
    case BundleT(fs) =>
      fs.flatMap(f => flattenGround(f.tpe, if (prefix.isEmpty) f.name else s"$prefix.${f.name}"))
    case GroundT("UInt", Some(w)) => Seq(FieldDef(prefix, w, PortDir.Out))
    case GroundT("SInt", Some(w)) => Seq(FieldDef(prefix, w, PortDir.Out))
    case GroundT("Clock", _)      => Seq(FieldDef(prefix, 1, PortDir.Out))
    case GroundT("Reset", _)      => Seq(FieldDef(prefix, 1, PortDir.Out))
    case GroundT(_, _)            => Seq(FieldDef(prefix, 0, PortDir.Out))
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
    m:              Mod,
    conns:          Seq[Conn],
    byName:         Map[String, Mod],
    bundles:        BundleReg,
    regNames:       Set[String],
    regType:        Map[String, String],
    logicNodeNames: Set[String],
    nodeEdges:      Seq[(String, String)],
  ): (Seq[WireDef], Seq[LogicNodeDef]) =
    new WireCoalescer(m, byName, bundles, regNames, regType, logicNodeNames)
      .coalesce(conns, nodeEdges)

  /** The wire-coalescing implementation for one module (see [[coalesceWires]]),
    * split into three named passes over a fixed module context:
    *   1. [[resolveEdges]]  - trace nets to real driver->sink terminal edges,
    *      `~q`-suffix register sources, and lift literal sources to const nodes.
    *   2. [[toBundleEdges]] - reduce each endpoint to its bundle endpoint,
    *      descending one segment for split ancestors (whole-and-per-field bundles).
    *   3. [[collapse]]      - group endpoints that pair 1:1 into one counted
    *      bundle wire; scatter the rest as field-level wires.
    * The per-module lookups (instSet/portSet/instMap) are built once as fields
    * so the path helpers below close over them without re-deriving (issue #217). */
  private final class WireCoalescer(
    m:              Mod,
    byName:         Map[String, Mod],
    bundles:        BundleReg,
    regNames:       Set[String],
    regType:        Map[String, String],
    logicNodeNames: Set[String],
  ) {
    private val instSet = m.insts.map(_.name).toSet
    private val portSet = m.ports.map(_.name).toSet
    private val instMap = m.insts.map(i => i.name -> i.ofModule).toMap

    /** True if `t` is itself an aggregate (Bundle or Vec of Bundle) - used to
      * decide whether a path's sub-segment is worth keeping as its own wire,
      * versus collapsing into the parent port.
      */
    private def isAggregate(t: CType): Boolean = t match {
      case BundleT(_) => true
      case VecT(elem, _) => isAggregate(elem)
      case _ => false
    }

    /** Type of the port reached by descending one segment into `path` from the
      * top scope of this module, or None if `path` doesn't start at a known
      * instance port or module-own port.
      */
    private def portTypeAt(path: String): Option[CType] = {
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

    private def trim(path: String): String = {
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
    private def trimDeep(path: String): String = {
      val parts = path.split('.').toIndexedSeq
      val head  = stripIdx(parts.head)
      val depth = if (instSet.contains(head)) 3 else 2
      parts.take(depth).mkString(".")
    }

    // Literal constants (issue #188): a connect whose rhs is a UInt/SInt literal
    // is a terminal SOURCE - the value is lifted into a `const` LogicNodeDef that
    // feeds the sink, so the wire routes from the const node rather than dropping
    // (a literal head is neither inst port, module port, nor register). Both the
    // quoted `UInt<8>("h1f")` form and the bare CHIRRTL `UInt<2>(0h3)` form match.
    private val LitRe = """^(?:UInt|SInt)(?:<\d+>)?\("?([^"\)]*)"?\)$""".r
    private def litValue(path: String): Option[String] = path match {
      case LitRe(v) => Some(v)
      case _        => None
    }
    private def prettyLit(v: String): String = if (v.startsWith("h")) "0x" + v.drop(1) else v

    // Registers are net TERMINALS (issue #188): a path `a -> reg -> b` must not
    // contract to one direct `a -> b` wire (the Part A caveat) - the wire routes
    // THROUGH the reg node instead. So the terminal predicate stops the trace at
    // instance ports, this module's own ports, and registers. Endpoints reaching
    // none of these are internal nets (wire-to-wire `_oHot_WIRE`) and drop out.
    // A net head may be a register Q pin (`r~q`) or a logic-op input pin
    // (`_T~in<i>`); strip either suffix to recover the underlying node name so
    // the terminal check matches the declared reg/logic-node (issue #188).
    // `~` is not valid in CHIRRTL identifiers ([\w$]+), so `~q`/`~in<i>` are
    // IrWalk-synthetic and cannot collide with a real signal name.
    private def isTerminal(path: String): Boolean = {
      if (litValue(path).isDefined) return true
      val head = stripIdx(path.split('.').headOption.getOrElse(""))
      val base = Synthetic.baseOf(head)
      instSet.contains(head) || portSet.contains(head) || regNames.contains(head) ||
        logicNodeNames.contains(base)
    }

    // A register only ever appears as a SOURCE for its Q-output and a SINK for
    // its D-input; CHIRRTL spells both as the plain reg path. `~q`-suffix the
    // HEAD segment of any resolved SOURCE that is a register so the Q pin (`r~q`)
    // is distinct from the D pin (`r`).
    private def qSuffix(path: String): String = {
      val i = path.indexOf('.')
      if (i < 0) path + "~q" else path.substring(0, i) + "~q" + path.substring(i)
    }
    private def isReg(path: String): Boolean = regNames.contains(stripIdx(path.split('.').head))

    /** Pass 1: resolve real driver->sink edges, contracting internal nets so a
      * field's true destination is known even through alias wires (issue #182),
      * but stopping at register and logic-node terminals (issue #188). Returns
      * the resolved edges (register sources `~q`-suffixed, literal sources lifted
      * to const ids) plus the const-node map (insertion-ordered). */
    private def resolveEdges(
      conns: Seq[Conn], nodeEdges: Seq[(String, String)],
    ): (Seq[(String, String)], mutable.LinkedHashMap[String, LogicNodeDef]) = {
      // NetTrace takes (sink, source) = (lhs, rhs). Node-declaration edges (logic
      // input pins, contracted plumbing values) join the connect edges here.
      val allEdges = conns.map(c => (c.lhs, c.rhs)) ++ nodeEdges
      val resolved0: Seq[(String, String)] = NetTrace.resolve(allEdges, isTerminal)
      val resolved: Seq[(String, String)] = resolved0.map { case (src, sink) =>
        (if (isReg(src)) qSuffix(src) else src, sink)
      }
      // Literal sources become deterministic `const` nodes; the edge source is
      // rewritten to a dot-free synthetic id so `trim`/`classify` treat it as a
      // whole atom carrying the value on its label (issue #188).
      val constNodes = mutable.LinkedHashMap.empty[String, LogicNodeDef]
      val resolvedWithConst: Seq[(String, String)] = resolved.map { case (src, sink) =>
        litValue(src) match {
          case Some(v) =>
            val id = Synthetic.constId(sink)
            constNodes.getOrElseUpdate(id, LogicNodeDef(id, NodeKind.Const, prettyLit(v), "", SourceRef.unknown))
            (id, sink)
          case None => (src, sink)
        }
      }
      (resolvedWithConst, constNodes)
    }

    /** Pass 2: trim each resolved endpoint to its bundle endpoint, descending one
      * more segment for split ancestors (bundles connected both whole and
      * per-field). Each element is `((srcEp, sinkEp), (srcOrig, sinkOrig))`. */
    private def toBundleEdges(
      resolvedWithConst: Seq[(String, String)],
    ): Seq[((String, String), (String, String))] = {
      val trimmed: Seq[(String, String, String, String)] =
        resolvedWithConst.map { case (src, sink) => (src, sink, trim(src), trim(sink)) }
      // Every proper dot-prefix of an endpoint: `ep` is a split ancestor iff some
      // endpoint starts with `ep + "."`, i.e. iff `ep` is in this set (one lookup
      // instead of a scan of all endpoints, which was quadratic on large modules).
      val ancestors = mutable.HashSet.empty[String]
      trimmed.foreach { case (_, _, se, ke) =>
        Seq(se, ke).foreach { o =>
          var i = o.indexOf('.')
          while (i >= 0) { ancestors += o.substring(0, i); i = o.indexOf('.', i + 1) }
        }
      }
      def isSplitAncestor(epName: String): Boolean = ancestors.contains(epName)
      def ep(orig: String, t: String): String = if (isSplitAncestor(t)) trimDeep(orig) else t
      trimmed.map { case (src, sink, se, ke) => ((ep(src, se), ep(sink, ke)), (src, sink)) }
    }

    /** Pass 3: group endpoints pairing 1:1 into one counted bundle wire; scatter
      * the rest as field-level wires. `conns` supplies the per-sink source marker. */
    private def collapse(
      bundleEdges: Seq[((String, String), (String, String))], conns: Seq[Conn],
    ): Seq[WireDef] = {
      // Best-effort source marker: the connect landing on each external sink.
      val srcBySink: Map[String, SrcInfo] =
        conns.iterator.flatMap(c => c.src.map(s => c.lhs -> s)).toMap

      // Partner set per bundle endpoint (both directions). A bundle collapses
      // only if it pairs with exactly one partner; otherwise its fields scatter.
      val partners = mutable.LinkedHashMap.empty[String, mutable.LinkedHashSet[String]]
      bundleEdges.foreach { case ((sEp, kEp), _) =>
        partners.getOrElseUpdate(sEp, mutable.LinkedHashSet.empty) += kEp
        partners.getOrElseUpdate(kEp, mutable.LinkedHashSet.empty) += sEp
      }
      def clean(epName: String): Boolean = partners.get(epName).exists(_.size == 1)

      case class G(from: String, to: String, src: Option[SrcInfo], count: Int)
      val collapsed = mutable.LinkedHashMap.empty[(String, String), G]
      val splitWires = mutable.ArrayBuffer.empty[WireDef]

      bundleEdges.foreach { case ((sEp, kEp), (src, sink)) =>
        if (sEp != kEp && clean(sEp) && clean(kEp)) {
          val k = (kEp, sEp) // (from = sink, to = source)
          val g = collapsed.get(k)
          collapsed(k) = G(kEp, sEp, g.flatMap(_.src).orElse(srcBySink.get(sink)),
            g.map(_.count).getOrElse(0) + 1)
        } else if (src != sink) {
          splitWires += WireDef(
            from       = sink,
            to         = src,
            bundle     = inferBundle(m, sink, src, byName, bundles, regType),
            label      = "",
            source     = srcBySink.get(sink).map(toRef).getOrElse(SourceRef.unknown),
            fieldLevel = true,
          )
        }
      }

      val collapsedWires = collapsed.values.toSeq.map { g =>
        WireDef(
          from   = g.from,
          to     = g.to,
          bundle = inferBundle(m, g.from, g.to, byName, bundles, regType),
          label  = if (g.count > 1) s"${g.count}× ground connects" else "",
          source = g.src.map(toRef).getOrElse(SourceRef.unknown),
        )
      }
      collapsedWires ++ splitWires.toSeq.distinct
    }

    /** Run the three passes and return (wires, lifted const nodes). */
    def coalesce(
      conns: Seq[Conn], nodeEdges: Seq[(String, String)],
    ): (Seq[WireDef], Seq[LogicNodeDef]) = {
      val (resolvedWithConst, constNodes) = resolveEdges(conns, nodeEdges)
      val bundleEdges = toBundleEdges(resolvedWithConst)
      val wires       = collapse(bundleEdges, conns)
      (wires, constNodes.values.toSeq)
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
    regType:  Map[String, String],
  ): String = {
    val instMap = m.insts.map(i => i.name -> i.ofModule).toMap

    // A register pin (`r`, `r~q`, possibly `.field`) carries the register's own
    // type, not a synthesized "Wire" (issue #188). Strip a vec index, then a
    // trailing `~q` off the head to recover the reg name.
    def regKeyOf(path: String): Option[String] = {
      val head = stripIdx(path.split('.').head)
      val base = Synthetic.stripQ(head)
      regType.get(base)
    }

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
        registerType(effTpe, hint, bundles)
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
        registerType(effTpe, hint, bundles)
      }
    }

    resolveOnInst(from)
      .orElse(resolveOnInst(to))
      .orElse(resolveOnSelf(from))
      .orElse(resolveOnSelf(to))
      .orElse(regKeyOf(from))
      .orElse(regKeyOf(to))
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
