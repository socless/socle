package socless.parse

import socless.schema._
import scala.collection.mutable

/** A minimal CHIRRTL text parser.
  *
  * We use this instead of `chisel3.internal.firrtl.ir.*` because the latter
  * is `private[chisel3]` and has churn between Chisel patch versions. CHIRRTL
  * text - the output of `circt.stage.ChiselStage.emitCHIRRTL` - is public,
  * version-stable, and still has Bundle types intact.
  *
  * Scope (what we extract):
  *   - module / extmodule names + their source-info markers
  *   - ports (name, direction, type tree, source-info)
  *   - submodule instances (name, of-module, source-info)
  *   - connect statements (lhs, rhs, source-info)
  *   - register declarations (reg, regreset) counted per module
  *   - memory declarations (mem, cmem, smem) counted per module
  *
  * Out of scope (ignored without error): when blocks, wires, ops.
  * Node declarations (node <name> = <expr>) ARE parsed into NodeDecl and
  * accumulated in Mod.nodes; when blocks and raw wires are still skipped.
  */
object ChirrtlParser {

  // ── AST ────────────────────────────────────────────────────────────────────
  sealed trait Type
  case class GroundT(name: String, width: Option[Int]) extends Type     // UInt<8>, Clock, Reset, ...
  case class BundleT(fields: Seq[Field])                  extends Type
  case class VecT(elem: Type, length: Int)                extends Type
  case class Field(name: String, flip: Boolean, tpe: Type)

  case class SrcInfo(file: String, line: Int, col: Int)

  case class Port(name: String, dir: String /* "input" | "output" */, tpe: Type, src: Option[SrcInfo])
  case class Inst(name: String, ofModule: String, src: Option[SrcInfo])
  /** A condition frame on the path to a connect: the `when` condition reference
    * and whether this connect sits in that when's `else` branch. */
  case class Guard(cond: String, negated: Boolean)
  case class Conn(lhs: String, rhs: String, src: Option[SrcInfo], guard: Seq[Guard] = Seq.empty)

  /** A register declaration inside a module body.
    *
    * CHIRRTL forms captured:
    *   reg <name> : <type>, <clock>                        (plain reg, no reset)
    *   regreset <name> : <type>, <clock>, <reset>, <init>  (reg with synchronous reset)
    */
  case class RegDecl(name: String, tpe: Type, src: Option[SrcInfo], inLayer: Boolean = false,
      // the `regreset` init operand as written (`UInt<2>(0h0)`); None for a plain reg
      init: Option[String] = None)

  /** A wire declaration inside a module body: `wire <name> : <type>`. Kept for
    * the probe view's signal list; the classic IrWalk path does not read it. */
  case class WireDecl(name: String, tpe: Type, src: Option[SrcInfo], inLayer: Boolean = false,
    // Declaration order among the module's wires and nodes (a statement counter).
    idx: Int = 0)

  /** A memory declaration inside a module body.
    *
    * CHIRRTL forms captured (all flavours produce a MemDecl):
    *   cmem <name> : <type>[<depth>]    (combinational memory port)
    *   smem <name> : <type>[<depth>]    (sequential memory port)
    *   mem <name> :                     (structured-memory block; multi-line in old FIRRTL)
    */
  case class MemDecl(name: String, elem: String, depth: Int, sync: Boolean, src: Option[SrcInfo])

  /** A memory access port (`mport`) inside a module body (issue #205):
    *   <infer|read|write|rdwr> mport <name> = <mem>[<addr>], <clock>
    * The clock operand is dropped (not a rendered pin). `mode` "infer" means the
    * direction is resolved later from how `name` is used in connects. */
  case class MPortDecl(name: String, mem: String, addr: String, mode: String, src: Option[SrcInfo])

  /** A node declaration inside a module body: `node <name> = <expr>`.
    *
    * `op` is the leading primop keyword (e.g. "add", "mux", "bits"); it is ""
    * when the rhs is a bare reference or literal (a simple alias node).
    * `operands` are the top-level argument tokens - a reference, a literal, or
    * a nested expression rendered as text.
    */
  case class NodeDecl(name: String, op: String, operands: Seq[String], src: Option[SrcInfo], guard: Seq[Guard] = Seq.empty,
    // True when this node was declared inside a `layerblock` (Chisel verification
    // layer content, issue #212), so it is verification logic, not design dataflow.
    inLayer: Boolean = false,
    // Declaration order among the module's wires and nodes (a statement counter).
    idx: Int = 0)

  /** CHIRRTL module-declaration keyword (`Mod.kind`). */
  object ModuleDecl {
    val Module    = "module"
    val ExtModule = "extmodule"
    val IntModule = "intmodule"
  }

  case class Mod(
    name: String,
    kind: String,    // ModuleDecl: Module | ExtModule | IntModule
    ports: Seq[Port],
    insts: Seq[Inst],
    conns: Seq[Conn],
    src: Option[SrcInfo],
    regs: Seq[RegDecl] = Seq.empty,
    mems: Seq[MemDecl] = Seq.empty,
    // Identifier heads referenced anywhere in the module body EXCEPT port
    // declaration lines: connects, register/memory clocks+resets, node
    // expressions, when conditions, etc. A port is "used/connected" iff its name
    // appears here. Collected across all statement kinds (not just connects)
    // because clock/reset and expression-only inputs never appear in a connect
    // yet are clearly used (issue #74).
    refs: Set[String] = Set.empty,
    nodes: Seq[NodeDecl] = Seq.empty,
    mports: Seq[MPortDecl] = Seq.empty,
    // Identifier heads referenced from inside `layerblock` content (issue #212):
    // the signals a verification layer reads. A module-level condition node whose
    // only consumer is a layer (so it appears here but drives no design sink) is
    // itself verification logic and is collapsed into the layer.
    layerConsumes: Set[String] = Set.empty,
    wires: Seq[WireDecl] = Seq.empty,
  )
  case class Circ(name: String, modules: Seq[Mod], warnings: Seq[Warning] = Seq.empty)

  // CHIRRTL statement keywords, type names, and primops that must never count
  // as a port reference (issue #100). Field selections are already excluded
  // syntactically (a head is never preceded by '.'), so this list only needs
  // the bare-word vocabulary of the language itself.
  private val RefStopwords: Set[String] = Set(
    "circuit", "module", "extmodule", "intmodule", "public", "defname", "parameter",
    "input", "output", "flip", "inst", "of", "node", "wire", "reg", "regreset",
    "mem", "cmem", "smem", "when", "else", "skip", "connect", "invalidate",
    "attach", "define", "force", "release", "printf", "assert", "assume",
    "cover", "stop", "read", "write", "infer", "rdwr", "mport", "with", "is",
    "invalid", "old", "new", "undefined",
    "UInt", "SInt", "Clock", "Reset", "AsyncReset", "Analog", "Probe", "RWProbe",
    "const", "Fixed", "Interval",
    "mux", "validif", "add", "sub", "mul", "div", "rem", "lt", "leq", "gt",
    "geq", "eq", "neq", "pad", "shl", "shr", "dshl", "dshr", "cvt", "neg",
    "not", "and", "or", "xor", "andr", "orr", "xorr", "cat", "bits", "head",
    "tail", "asUInt", "asSInt", "asClock", "asAsyncReset",
  )

  // ── Top-level parse ────────────────────────────────────────────────────────
  def parse(chirrtl: String): Circ = {
    val lines = chirrtl.split('\n').toIndexedSeq
    var circuitName = "Unknown"
    val modules = mutable.Buffer.empty[Mod]

    // State for the current module being accumulated.
    var curMod: Option[String]    = None
    var curKind: String           = ModuleDecl.Module
    var curSrc: Option[SrcInfo]   = None
    val curPorts                  = mutable.Buffer.empty[Port]
    val curInsts                  = mutable.Buffer.empty[Inst]
    val curConns                  = mutable.Buffer.empty[Conn]
    val curRegs                   = mutable.Buffer.empty[RegDecl]
    val curMems                   = mutable.Buffer.empty[MemDecl]
    val curWires                  = mutable.Buffer.empty[WireDecl]
    var curDeclIdx                = 0
    val curRefs                   = mutable.Set.empty[String]
    val curNodes                  = mutable.Buffer.empty[NodeDecl]
    val curMports                 = mutable.Buffer.empty[MPortDecl]
    val curLayerConsumes          = mutable.Set.empty[String]

    // When-scope stack: (indent, cond, negated). A `when c :` pushes a frame at
    // its indent; `else :` flips the matching frame's polarity; a dedent pops it.
    // Each Conn is tagged with the active stack (outermost-first) as its guard.
    val whenStack = mutable.ArrayBuffer.empty[(Int, String, Boolean)]
    // Layerblock-scope stack: (indent, name). Mirrors whenStack; `layerblock X :`
    // pushes, a dedent pops. Non-empty => the line is verification-layer content
    // (issue #212).
    val layerStack = mutable.ArrayBuffer.empty[(Int, String)]
    def indentOf(s: String): Int = s.takeWhile(_ == ' ').length

    def flush(): Unit = curMod.foreach { name =>
      modules += Mod(
        name, curKind,
        curPorts.toSeq, curInsts.toSeq, curConns.toSeq, curSrc,
        curRegs.toSeq, curMems.toSeq, curRefs.toSet, curNodes.toSeq,
        curMports.toSeq, curLayerConsumes.toSet, curWires.toSeq,
      )
      curMod = None; curSrc = None
      curPorts.clear(); curInsts.clear(); curConns.clear()
      curRegs.clear(); curMems.clear(); curWires.clear(); curRefs.clear(); curNodes.clear()
      curMports.clear(); curLayerConsumes.clear(); curDeclIdx = 0
      whenStack.clear(); layerStack.clear()
    }

    // Collect referenced identifiers (used to detect which ports are actually
    // used). Only the HEAD of each dotted reference counts: `io.valid`
    // references the port `io`, never a port named `valid` (issue #100: the
    // old bag-of-tokens split marked a dangling port `valid` as connected
    // whenever any other port's `.valid` field was used, and statement
    // keywords / module names landed in refs too). Source markers and string
    // literals are stripped first; field selections are excluded by the
    // lookbehind; statement keywords and primops by the stopword set.
    val SrcMarker = """@\[[^\]]*\]""".r
    val StrLit    = "\"(?:[^\"\\\\]|\\\\.)*\"".r
    val RefHead   = """(?<![\w$.])[A-Za-z_][A-Za-z0-9_$]*""".r
    def collectRefs(line: String): Unit = {
      val stripped = StrLit.replaceAllIn(SrcMarker.replaceAllIn(line, " "), " ")
      RefHead.findAllIn(stripped).foreach { id =>
        if (!RefStopwords.contains(id)) curRefs += id
      }
    }
    // Same harvesting, but into the layer-consumed set (issue #212): records the
    // signals a `layerblock` line reads, so IrWalk can fold a module-level
    // condition node that feeds only the layer into it.
    def collectLayerRefs(line: String): Unit = {
      val stripped = StrLit.replaceAllIn(SrcMarker.replaceAllIn(line, " "), " ")
      RefHead.findAllIn(stripped).foreach { id =>
        if (!RefStopwords.contains(id)) curLayerConsumes += id
      }
    }

    val Circuit  = """^\s*circuit\s+([\w$]+)\s*:.*$""".r
    // Chisel 7+ emits `public module Foo:` for the entry point (layer support).
    // Older Chisel just emits `module Foo:`. We accept both, plus extmodule /
    // intmodule. Names may contain `$` (anonymous classes) and extra tokens
    // may sit between the name and the colon (`module Foo enablelayer A :`):
    // rejecting the line silently merged the module's whole body into the
    // PREVIOUS module (issue #101). Multiple `@[file line:col]` markers may
    // appear on one line (Chisel attaches a marker per parent macro
    // expansion); we capture them as a single trailing block and only parse
    // the first one - coalescing them all into one source ref would be lossy
    // without buying anything.
    val ModLine  = """^(\s+)(?:public\s+)?(module|extmodule|intmodule)\s+([\w$]+)([^:]*)\s*:\s*((?:@\[[^\]]*\]\s*)*)\s*$""".r

    // `when <cond> :` and `else :` lines (optional trailing source markers).
    // Captured here (not via parseStmt) so the leading indentation - the only
    // scope signal CHIRRTL gives us - drives the when-stack.
    val WhenLine = """^(\s*)when\s+(.+?)\s*:\s*(?:@\[[^\]]*\]\s*)*$""".r
    val ElseLine = """^(\s*)else\s*:\s*(?:@\[[^\]]*\]\s*)*$""".r
    val LayerBlockLine = """^(\s*)layerblock\s+([\w$]+)\s*:\s*(?:@\[[^\]]*\]\s*)*$""".r

    // Constructs we recognize but do not represent (no topology for them):
    // counted and surfaced as structured warnings so users know what is
    // missing instead of wires silently vanishing (issues #101, #118).
    var skippedPartial = 0   // old-FIRRTL partial connect `lhs <- rhs`
    var skippedAttach  = 0   // Analog `attach(a, b)`

    for (raw <- lines) {
      raw match {
        case Circuit(n) =>
          circuitName = n
        case ModLine(_indent, kind, name, _extra, srcOpt) =>
          flush()
          curMod  = Some(name)
          curKind = kind
          curSrc  = parseSrc(Option(srcOpt))
        case other if curMod.isDefined && other.trim.startsWith(";") =>
          () // comment line: never a statement, never a reference
        case WhenLine(_, cond) if curMod.isDefined =>
          val ind = indentOf(raw)
          while (whenStack.nonEmpty && whenStack.last._1 >= ind) whenStack.remove(whenStack.length - 1)
          while (layerStack.nonEmpty && layerStack.last._1 >= ind) layerStack.remove(layerStack.length - 1)
          whenStack += ((ind, cond.trim, false))
          collectRefs(raw) // harvest the condition ref(s)
          if (layerStack.nonEmpty) collectLayerRefs(raw)
        case ElseLine(_) if curMod.isDefined =>
          val ind = indentOf(raw)
          while (whenStack.nonEmpty && whenStack.last._1 > ind) whenStack.remove(whenStack.length - 1)
          while (layerStack.nonEmpty && layerStack.last._1 >= ind) layerStack.remove(layerStack.length - 1)
          if (whenStack.nonEmpty && whenStack.last._1 == ind) {
            val (i, c, _) = whenStack.last
            whenStack(whenStack.length - 1) = ((i, c, true))
          }
        case LayerBlockLine(_, _layerName) if curMod.isDefined =>
          val ind = indentOf(raw)
          while (whenStack.nonEmpty && whenStack.last._1 >= ind) whenStack.remove(whenStack.length - 1)
          while (layerStack.nonEmpty && layerStack.last._1 >= ind) layerStack.remove(layerStack.length - 1)
          layerStack += ((ind, _layerName))
        case other if curMod.isDefined =>
          val ind = indentOf(other)
          while (whenStack.nonEmpty && whenStack.last._1 >= ind) whenStack.remove(whenStack.length - 1)
          while (layerStack.nonEmpty && layerStack.last._1 >= ind) layerStack.remove(layerStack.length - 1)
          val inLayer = layerStack.nonEmpty
          val guard = whenStack.iterator.map { case (_, c, n) => Guard(c, n) }.toSeq
          // Wrap per-line parsing so a malformed statement never aborts the
          // whole parse; we skip unrecognised / unparseable lines gracefully.
          try {
            parseStmt(other) match {
              // Port declarations name the port itself; excluded from refs so a
              // port is not counted as "used" merely by being declared.
              case Some(p: Port)    => curPorts += p
              // An instance line (`inst foo of Bar`) references no ports:
              // collecting it would add the instance and module names to refs.
              case Some(i: Inst)    => curInsts += i
              // Connects reference exactly their two endpoint expressions.
              case Some(c: Conn)    => curConns += c.copy(guard = guard); collectRefs(c.lhs); collectRefs(c.rhs)
              case Some(r: RegDecl)  => curRegs += r.copy(inLayer = inLayer); collectRefs(other)
              case Some(w: WireDecl)  => curWires += w.copy(inLayer = inLayer, idx = curDeclIdx); curDeclIdx += 1; collectRefs(other)
              case Some(m: MemDecl)   => curMems += m; collectRefs(other)
              case Some(mp: MPortDecl) => curMports += mp; collectRefs(other)
              case Some(n: NodeDecl)  => curNodes += n.copy(guard = guard, inLayer = inLayer, idx = curDeclIdx); curDeclIdx += 1; collectRefs(other)
              case _ =>
                val t = other.trim
                if (PartialConnRe.findFirstIn(t).isDefined) skippedPartial += 1
                else if (t.startsWith("attach")) skippedAttach += 1
                collectRefs(other) // whens, ops, etc.
            }
            // Inside a layerblock, record the signals this line reads so IrWalk
            // can fold module-level condition nodes that feed only it (#212).
            if (inLayer) collectLayerRefs(other)
          } catch {
            case _: Exception => () // skip malformed line, keep going
          }
        case _ => () // pre-module fluff
      }
    }
    flush()
    val warnings = Seq(
      if (skippedPartial > 0)
        Some(Warning("skipped-partial-connect", skippedPartial,
          s"$skippedPartial partial connect(s) ('<-') were skipped; those wires are not represented"))
      else None,
      if (skippedAttach > 0)
        Some(Warning("skipped-attach", skippedAttach,
          s"$skippedAttach attach statement(s) were skipped; those Analog nets are not represented"))
      else None,
    ).flatten
    // Warnings are not printed here: they ride in Circ.warnings -> Design.warnings
    // and SoclessExporter.logWarnings echoes every kind to stderr once, so the
    // console, design.json, and UI banner all agree (and nothing double-prints).
    Circ(circuitName, modules.toSeq, warnings)
  }

  // ── Statement-level parsing ────────────────────────────────────────────────
  // Each statement may carry one or more `@[...]` source markers - Chisel 7
  // attaches them per parent macro frame. We accept zero-or-more and parse
  // only the first (innermost). Stmt lines we don't recognize are dropped
  // without error; the exporter doesn't need raw wires, when blocks, or ops.
  private val PortRe = """^\s+(input|output)\s+([\w$]+)\s*:\s*(.+?)\s*((?:@\[[^\]]*\]\s*)*)$""".r
  private val InstRe = """^\s+inst\s+([\w$]+)\s+of\s+([\w$]+)\s*((?:@\[[^\]]*\]\s*)*)$""".r
  // Chisel 7 emits `connect lhs, rhs` (was `lhs <= rhs` in older versions).
  // We accept both for portability.
  private val ConnRe1 = """^\s+connect\s+(.+?)\s*,\s*(.+?)\s*((?:@\[[^\]]*\]\s*)*)$""".r
  private val ConnRe2 = """^\s+(.+?)\s*<=\s*(.+?)\s*((?:@\[[^\]]*\]\s*)*)$""".r
  // Old-FIRRTL partial connect; recognized only to COUNT it (no topology).
  private val PartialConnRe = """^[\w$][\w$.\[\]]*\s*<-\s*""".r

  /** The LHS of a legacy `<=` connect must look like a reference: bare `<=`
    * also appears inside printf format strings and expression lines, which
    * used to parse into bogus Conns (inflating wire metrics, issue #101). */
  private def isRefExpr(s: String): Boolean =
    s.nonEmpty && (s.head.isLetter || s.head == '_') &&
      !s.exists(c => c == '(' || c == '"' || c == ' ')

  // Register declarations.
  // Plain reg:      reg <name> : <type>, <clock>  @[...]
  // Reset reg:  regreset <name> : <type>, <clock>, <reset>, <init>  @[...]
  // The type is NOT split at the first comma (aggregate reg types contain
  // commas, issue #101): the full remainder is captured and parseType
  // consumes exactly the type prefix, leaving the clock/reset operands.
  private val RegRe =
    """^\s+(?:regreset|reg)\s+([\w$]+)\s*:\s*(.+?)\s*((?:@\[[^\]]*\]\s*)*)$""".r

  // Wire declarations: wire <name> : <type>  @[...]
  private val WireRe =
    """^\s+wire\s+([\w$]+)\s*:\s*(.+?)\s*((?:@\[[^\]]*\]\s*)*)$""".r

  // Memory access port: <mode> mport <name> = <mem>[<addr>], <clock>.
  // The address may itself be indexed (`vec[i]`), so `[(.+)\]` matches to the
  // LAST ']'; the clock operand is captured but discarded.
  private val MPortRe =
    """^\s+(infer|read|write|rdwr)\s+mport\s+([\w$]+)\s*=\s*([\w$]+)\[(.+)\]\s*,\s*(.+?)\s*((?:@\[[^\]]*\]\s*)*)$""".r

  // Memory declarations: cmem/smem carry an inline `<type>[<depth>]`; the bare
  // old-FIRRTL structured `mem <name> :` block has no inline type (depth 0).
  private val MemRe =
    """^\s+(cmem|smem|mem)\s+([\w$]+)\s*[:\s](.*?)((?:@\[[^\]]*\]\s*)*)$""".r

  // Node declarations: node <name> = <expr>  @[...]
  private val NodeRe =
    """^\s+node\s+([\w$]+)\s*=\s*(.+?)\s*((?:@\[[^\]]*\]\s*)*)$""".r
  // Matches a primop call: <op>(<args>)
  private val CallRe = """^([\w$]+)\((.*)\)$""".r

  /** Split a primop argument list at top-level commas only (ignores commas
    * inside nested parens, angle brackets, or square brackets). Trims each
    * token and drops empties. */
  private def splitTopLevel(args: String): Seq[String] = {
    val out   = mutable.ListBuffer.empty[String]
    val sb    = new StringBuilder
    var depth = 0
    args.foreach {
      case c @ ('(' | '<' | '[') => depth += 1; sb += c
      case c @ (')' | '>' | ']') => depth -= 1; sb += c
      case ',' if depth == 0     => out += sb.toString.trim; sb.clear()
      case c                     => sb += c
    }
    if (sb.nonEmpty) out += sb.toString.trim
    out.toList.filter(_.nonEmpty)
  }

  /** Parse a node rhs expression into (op, operands). A bare reference or
    * literal yields ("", Seq(expr)). */
  private def parseNodeExpr(expr: String): (String, Seq[String]) = expr.trim match {
    case CallRe(op, args) => (op, splitTopLevel(args))
    case bare             => ("", Seq(bare))
  }

  /** Render a parsed type to a short human string for memory element labels. */
  private def renderType(t: Type): String = t match {
    case GroundT(n, Some(w)) => s"$n<$w>"
    case GroundT(n, None)    => n
    case VecT(e, n)          => s"${renderType(e)}[$n]"
    case BundleT(_)          => "{...}"
  }

  /** Parse a cmem/smem inline type `UInt<32>[16]` into (elem, depth). The bare
    * structured `mem <name> :` form passes an empty string and yields ("", 0). */
  private def parseMemType(s: String): (String, Int) = {
    val t = s.trim
    if (t.isEmpty) ("", 0)
    else parseType(t)._1 match {
      case VecT(elem, n) => (renderType(elem), n)
      case other         => (renderType(other), 0)
    }
  }

  private def parseStmt(line: String): Option[AnyRef] = line match {
    case PortRe(dir, name, typeStr, srcOpt) =>
      val (tpe, _) = parseType(typeStr)
      Some(Port(name, dir, tpe, parseSrc(Option(srcOpt))))
    case InstRe(name, ofMod, srcOpt) =>
      Some(Inst(name, ofMod, parseSrc(Option(srcOpt))))
    case ConnRe1(lhs, rhs, srcOpt) =>
      Some(Conn(lhs.trim, rhs.trim, parseSrc(Option(srcOpt))))
    case ConnRe2(lhs, rhs, srcOpt) if !line.contains("connect ") && isRefExpr(lhs.trim) =>
      Some(Conn(lhs.trim, rhs.trim, parseSrc(Option(srcOpt))))
    case RegRe(name, typeStr, srcOpt) =>
      // Parse the type prefix; of the operands after it, keep a regreset's init.
      val (tpe, rest) = parseType(typeStr.trim)
      val ops  = rest.trim.stripPrefix(",").split(",", 3).map(_.trim)
      val init = Option.when(line.trim.startsWith("regreset") && ops.length == 3 && ops(2).nonEmpty)(ops(2))
      Some(RegDecl(name, tpe, parseSrc(Option(srcOpt)), init = init))
    case WireRe(name, typeStr, srcOpt) =>
      val (tpe, _) = parseType(typeStr.trim)
      Some(WireDecl(name, tpe, parseSrc(Option(srcOpt))))
    case MPortRe(mode, name, mem, addr, _clk, srcOpt) =>
      Some(MPortDecl(name, mem, addr.trim, mode, parseSrc(Option(srcOpt))))
    case MemRe(kw, name, typeStr, srcOpt) =>
      val (elem, depth) = parseMemType(typeStr)
      Some(MemDecl(name, elem, depth, sync = kw == "smem", parseSrc(Option(srcOpt))))
    case NodeRe(name, expr, srcOpt) =>
      val (op, operands) = parseNodeExpr(expr)
      Some(NodeDecl(name, op, operands, parseSrc(Option(srcOpt))))
    case _ => None
  }

  // ── @[file line:col] markers ───────────────────────────────────────────────
  // Multi-marker lines (Chisel macro expansions) get the FIRST one - that's
  // the innermost call site, which is typically the user's own Scala source.
  private val SrcRe = """@\[([^\s\]]+)\s+(\d+):(\d+)\]""".r
  private def parseSrc(opt: Option[String]): Option[SrcInfo] = opt.flatMap { s =>
    SrcRe.findFirstMatchIn(s).flatMap { m =>
      for {
        line <- m.group(2).toIntOption
        col  <- m.group(3).toIntOption
      } yield SrcInfo(m.group(1), line, col)
    }
  }

  // ── Type parser ────────────────────────────────────────────────────────────
  /** Parse a CHIRRTL type prefix; returns (type, rest-of-string).
    *
    *   UInt<8>                                     -> GroundT("UInt", Some(8))
    *   { flip ready : UInt<1>, valid : UInt<1> }   -> BundleT(Seq(Field("ready", true, UInt<1>), ...))
    *   UInt<32>[8]                                 -> VecT(GroundT("UInt", 32), 8)
    *
    * The recursion handles arbitrarily nested bundles + vectors of bundles.
    */
  def parseType(src: String): (Type, String) = {
    val s0 = src.trim
    // `const T` is T for our purposes (issue #101: it used to parse as a
    // ground type literally named "const", discarding the real type).
    val s = if (s0.startsWith("const ")) s0.substring(6).trim else s0
    val (base, after) =
      if (s.startsWith("{")) parseBundle(s)
      else                    parseGround(s)
    // Vector suffix: any number of "[N]" appended after a type
    var t = base; var rest = after.trim
    while (rest.startsWith("[")) {
      val close = rest.indexOf(']')
      if (close <= 0) return (t, rest)
      val n = rest.substring(1, close).trim.toIntOption.getOrElse(0)
      t = VecT(t, n)
      rest = rest.substring(close + 1).trim
    }
    (t, rest)
  }

  private def parseBundle(src: String): (Type, String) = {
    if (!src.startsWith("{")) return (BundleT(Seq.empty), src)
    var rest = src.substring(1).trim
    val fields = mutable.Buffer.empty[Field]
    while (rest.nonEmpty && !rest.startsWith("}")) {
      // `flip` is only the keyword when an identifier follows: a field
      // literally NAMED flip (`flip : UInt<1>`) must keep its name (issue
      // #101: it used to parse as Field(name = "", flip = true)).
      val (flip, afterFlip) =
        if (rest.startsWith("flip") && rest.length > 4 && rest(4).isWhitespace
            && !rest.substring(4).trim.startsWith(":"))
                                        (true,  rest.substring(4).trim)
        else                            (false, rest)
      // field name up to ':'
      val colon = afterFlip.indexOf(':')
      if (colon < 0) return (BundleT(fields.toSeq), rest)
      val fname  = afterFlip.substring(0, colon).trim
      val (ftpe, afterT) = parseType(afterFlip.substring(colon + 1))
      fields += Field(fname, flip, ftpe)
      rest = afterT.trim
      if (rest.startsWith(",")) rest = rest.substring(1).trim
    }
    if (rest.startsWith("}")) rest = rest.substring(1)
    (BundleT(fields.toSeq), rest.trim)
  }

  private def parseGround(src: String): (Type, String) = {
    val s = src.trim
    if (s.isEmpty) return (GroundT("", None), "")
    // Pull the first identifier
    val nameEnd = s.indexWhere(c => !(c.isLetterOrDigit || c == '_'), 1)
    val (name, after0) =
      if (nameEnd < 0) (s, "")
      else             (s.substring(0, nameEnd), s.substring(nameEnd))
    // Probe<T> / RWProbe<T>: the payload may itself contain '<...>'
    // (Probe<UInt<8>>), so the bracket must be matched by depth, not by the
    // first '>' (issue #101: the width parsed as "UInt<8" and a stray '>'
    // leaked into the remainder).
    if ((name == "Probe" || name == "RWProbe") && after0.startsWith("<")) {
      var depth = 0; var i = 0; var end = -1
      while (i < after0.length && end < 0) {
        after0(i) match {
          case '<' => depth += 1
          case '>' => depth -= 1; if (depth == 0) end = i
          case _   =>
        }
        i += 1
      }
      if (end > 0) return (GroundT(name, None), after0.substring(end + 1).trim)
    }
    // Optional <Width>
    val (width, after) =
      if (after0.startsWith("<")) {
        val gt = after0.indexOf('>')
        if (gt < 0) (None, after0)
        else (Some(after0.substring(1, gt).trim.toIntOption.getOrElse(0)), after0.substring(gt + 1))
      } else (None, after0)
    (GroundT(name, width), after.trim)
  }
}
