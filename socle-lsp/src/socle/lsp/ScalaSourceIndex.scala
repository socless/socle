package socle.lsp

import scala.meta._
import scala.util.control.NonFatal
import socle.capture.{Construct, FsmSource, SourceIndex}
import socle.ir.SourceLines
import socle.schema.SourceRef

/** A [[SourceIndex]] over the Scala sources under `root`, parse-only
  * (scalameta, no type information): a position maps to the outermost
  * generator construct (`for`, or a call to one of [[ScalaSourceIndex.LoopNames]]
  * with a function literal or block argument) that contains it in the body of
  * the module's own class, or of a class or trait it extends (matched by
  * simple name, transitively). Positions in any other body (a helper object,
  * a companion, an unrelated class) give `None`.
  *
  * `file` is resolved against `root` as `POST /open` resolves CHIRRTL paths.
  * `fsm` reads the `switch(register)` state machine of those same bodies
  * (see [[FsmFacts]]), from facts each file's parse keeps. It reads the
  * workspace snapshot, which `refresh()` (once per view) renews, so an edit
  * to the module's own file shows at the next view.
  *
  * Long-lived: each file is parsed once per modification time and only its
  * spans are kept. The workspace (for a class's parents) is listed and
  * indexed by simple name on first need; `refresh()` (once per view) makes
  * the next such need re-list it, re-parsing only changed or new files.
  * Never throws: any failure is `None`. Thread-safe: queries serialize on
  * one lock.
  */
final class ScalaSourceIndex(root: os.Path) extends SourceIndex {
  import ScalaSourceIndex._

  private val lock    = new Object
  private var entries = Map.empty[os.Path, Entry]                   // guarded by lock
  private var byName  = Option.empty[Map[String, Seq[Placed]]]      // guarded by lock: None = re-list
  private var owners  = Map.empty[String, Owners]                   // guarded by lock: by scalaClass
  private var parses  = 0                                           // guarded by lock

  def construct(scalaClass: String, file: String, line: Int, col: Int): Option[Construct] =
    if (scalaClass.isEmpty || line < 1 || col < 1) None
    else safe(lock.synchronized(find(scalaClass, file, line, col))).flatten

  override def fsm(scalaClass: String, register: String, at: Option[SourceRef]): Option[FsmSource] =
    if (scalaClass.isEmpty || register.isEmpty) None
    else safe(lock.synchronized(findFsm(scalaClass, register).orElse(at.flatMap(fsmAt(register, _))))).flatten

  /** The lines of the files under `root`, read through the same per-file
    * cache as the parses (re-read when the file's modification time changes). */
  override val lines: SourceLines = (file: String, line: Int) =>
    if (line < 1) None
    else safe(lock.synchronized(for {
      path <- SocleLanguageServer.openTarget(file, root, Set.empty)
      e    <- entryOf(path)
      text <- e.lines.lift(line - 1)
    } yield text)).flatten

  /** Makes the next workspace lookup re-list the files under `root`. */
  def refresh(): Unit = lock.synchronized { byName = None; owners = Map.empty }

  /** Files parsed so far (cache misses), for tests. */
  private[lsp] def parseCount: Int = lock.synchronized(parses)

  private def find(cls: String, file: String, line: Int, col: Int): Option[Construct] = for {
    path  <- SocleLanguageServer.openTarget(file, root, Set.empty)
    index <- indexOf(path)
    off   <- index.offset(line, col)
    tmpl  <- index.templates.filter(t => t.start <= off && off < t.end).minByOption(t => t.end - t.start)
    if tmpl.fq == cls || ownersOf(cls).counts(tmpl)
    loop  <- index.loops.find(l => l.template == tmpl.id && l.start <= off && off < l.end)
  } yield Construct(s"$file:${loop.line}:${loop.col}", loop.label, SourceRef(file, loop.line, loop.col))

  /** The owned bodies' facts, the class's own first, then its ancestors by
    * file and position; ChiselEnum objects are looked up workspace-wide. */
  private def findFsm(cls: String, register: String): Option[FsmSource] = {
    val o      = ownersOf(cls)
    val ws     = workspace
    val placed = (simpleName(cls) +: o.ancestors.toSeq).distinct.flatMap(ws.getOrElse(_, Nil)).filter(p => o.counts(p.t))
    val (own, inherited) = placed.sortBy(p => (p.path.toString, p.t.start)).partition(p => o.own(p.t.fq))
    def facts(p: Placed) = p.index.fsm.get(p.t.id).map(p.path.relativeTo(root).toString -> _)
    FsmFacts.source((own ++ inherited).flatMap(facts), register, enumValuesOf)
  }

  /** A state machine built outside the class's bodies (a helper method the
    * module calls): the innermost method, else class body, around the
    * register's declaration that switches on it. Chisel names such a register
    * after the val holding the call (`readAddress_readState` for `readState`),
    * so the switch's subject may be the register's `_` suffix. */
  private def fsmAt(register: String, at: SourceRef): Option[FsmSource] = {
    def named(subject: String) = subject.nonEmpty && (register == subject || register.endsWith("_" + subject))
    for {
      path    <- SocleLanguageServer.openTarget(at.file, root, Set.empty)
      index   <- indexOf(path)
      off     <- index.offset(at.line, at.col)
      scope   <- index.scopes.filter(s => s.start <= off && off < s.end && s.facts.switches.exists(w => named(w.subject)))
                   .minByOption(s => s.end - s.start)
      subject <- scope.facts.switches.map(_.subject).filter(named).maxByOption(_.length)
      src     <- FsmFacts.source(Seq(path.relativeTo(root).toString -> scope.facts), subject, enumValuesOf)
    } yield src
  }

  private def enumValuesOf(obj: String): Seq[Seq[String]] =
    workspace.getOrElse(obj, Nil).filter(_.t.isObject).flatMap(p => p.index.fsm.get(p.t.id)).flatMap(_.chiselEnum)

  /** The file's index, re-parsed when its modification time changed. */
  private def indexOf(path: os.Path): Option[FileIndex] = entryOf(path).flatMap(_.index)

  /** The file's cache entry (its lines and its parse), renewed when its
    * modification time changed; `None` when it cannot be read. */
  private def entryOf(path: os.Path): Option[Entry] = safe(os.mtime(path)).flatMap { mtime =>
    entries.get(path).filter(_.mtime == mtime).orElse {
      safe(os.read(path)).map { text =>
        parses += 1
        val e = Entry(mtime, text.split("\n", -1).toIndexedSeq.map(_.stripSuffix("\r")),
          safe(TestDiscovery.parse(text).map(FileIndex(_))).flatten)
        entries += path -> e
        e
      }
    }
  }

  /** Every class, trait and object of the workspace, by simple name. */
  private def workspace: Map[String, Seq[Placed]] = byName.getOrElse {
    val files = SimBaseIndex.scalaFiles(root)
    val known = files.toSet
    entries = entries.filter { case (p, _) => known(p) }
    val m = files.flatMap(p => indexOf(p).toSeq.flatMap(i => i.templates.map(Placed(p, i, _)))).groupBy(_.t.simple)
    byName = Some(m)
    m
  }

  private def ownersOf(cls: String): Owners = owners.getOrElse(cls, {
    val named   = workspace.getOrElse(simpleName(cls), Nil).map(_.t)
    val exact   = named.filter(_.fq == cls)
    val own     = if (exact.nonEmpty) exact else named.filter(_.isObject == cls.endsWith("$"))
    var parents = Set.empty[String]
    // Names with no workspace definition (`Module`, library traits) end the walk.
    var queue   = own.flatMap(_.parents).map(TestDiscovery.simpleName).toList
    while (queue.nonEmpty) {
      val p = queue.head
      queue = queue.tail
      if (!parents(p)) {
        parents += p
        queue ++= workspace.getOrElse(p, Nil).map(_.t).filterNot(_.isObject).flatMap(_.parents).map(TestDiscovery.simpleName)
      }
    }
    val o = Owners(own.map(_.fq).toSet, parents)
    owners += cls -> o
    o
  })
}

object ScalaSourceIndex {
  /** Higher-order calls that generate hardware once per element. `zip...map`,
    * `zipWithIndex.foreach` and `VecInit(xs.map ...)` are `map` and `foreach`. */
  val LoopNames: Set[String] =
    Set("foreach", "map", "flatMap", "tabulate", "fill", "reduce", "reduceTree", "fold", "foldLeft", "scanLeft")

  /** The JVM binary name (`pkg.Outer$Inner`, an object's with a trailing `$`)
    * reduced to the simple name of the class. */
  private[lsp] def simpleName(scalaClass: String): String =
    scalaClass.stripSuffix("$").split('.').last.split('$').last

  /** A class, trait or object body. `start`/`end` are offsets of the whole
    * definition; `fq` is the binary name as `getClass.getName` gives it. */
  private final case class Template0(
      id: Int, simple: String, fq: String, isObject: Boolean, parents: Seq[String], start: Int, end: Int)

  /** An outermost generator construct of template `template`, at 1-based `line`:`col`. */
  private final case class Loop(template: Int, start: Int, end: Int, line: Int, col: Int, label: String)

  /** The bodies whose constructs count for one module class: its own
    * definitions (by binary name) and its ancestors (by simple name). */
  private final case class Owners(own: Set[String], ancestors: Set[String]) {
    def counts(t: Template0): Boolean = own(t.fq) || (!t.isObject && ancestors(t.simple))
  }

  /** A template with the file that defines it and that file's index. */
  private final case class Placed(path: os.Path, index: FileIndex, t: Template0)

  private final case class Entry(mtime: Long, lines: IndexedSeq[String], index: Option[FileIndex])

  /** `None` for any non-fatal failure, and for the StackOverflowError a
    * pathologically deep file gives (fatal to `NonFatal` and `Try`). */
  private def safe[A](a: => A): Option[A] =
    try Some(a) catch {
      case _: StackOverflowError => None
      case NonFatal(_)           => None
    }

  /** A class, trait or object body, or a method body, spanning `start` to `end`, with its facts. */
  private final case class Scope(start: Int, end: Int, facts: FsmFacts.Facts)

  private final case class FileIndex(
      lineStarts: IndexedSeq[Int], templates: Seq[Template0], loops: Seq[Loop], fsm: Map[Int, FsmFacts.Facts],
      scopes: Seq[Scope]) {
    /** The offset of a 1-based line and column, when both lie in the file. */
    def offset(line: Int, col: Int): Option[Int] =
      Option.when(line <= lineStarts.size - 1 && lineStarts(line - 1) + col - 1 < lineStarts(line))(
        lineStarts(line - 1) + col - 1)
  }

  private object FileIndex {
    def apply(source: Source): FileIndex = {
      val templates = Seq.newBuilder[Template0]
      val loops     = Seq.newBuilder[Loop]
      val fsm       = Map.newBuilder[Int, FsmFacts.Facts]
      val scopes    = Seq.newBuilder[Scope]
      var nextId    = 0

      def define(d: Tree, name: String, isObject: Boolean, templ: Template, pkg: Seq[String], outer: Seq[String]): Unit = {
        val binary = (pkg :+ (outer :+ name).mkString("$")).mkString(".") + (if (isObject) "$" else "")
        val id     = nextId
        nextId += 1
        val parents = TestDiscovery.parents(templ)
        templates += Template0(id, name, binary, isObject, parents, d.pos.start, d.pos.end)
        val facts = FsmFacts.collect(templ.body.stats, isObject && parents.exists(TestDiscovery.simpleName(_) == "ChiselEnum"))
        fsm += id -> facts
        scopes += Scope(d.pos.start, d.pos.end, facts)
        templ.body.stats.foreach(walk(_, pkg, outer :+ name, id, inLoop = false))
      }

      def walk(tree: Tree, pkg: Seq[String], outer: Seq[String], tmpl: Int, inLoop: Boolean): Unit = tree match {
        case p: Pkg         => p.body.stats.foreach(walk(_, pkg :+ p.ref.syntax, outer, tmpl, inLoop = false))
        case c: Defn.Class  => define(c, c.name.value, isObject = false, c.templ, pkg, outer)
        case t: Defn.Trait  => define(t, t.name.value, isObject = false, t.templ, pkg, outer)
        case o: Defn.Object => define(o, o.name.value, isObject = true, o.templ, pkg, outer)
        case m: Defn.Def if tmpl >= 0 =>
          // A method body is its own scope too, for a state machine a helper method builds.
          scopes += Scope(m.pos.start, m.pos.end, FsmFacts.collect(List(m.body), isChiselEnum = false))
          m.children.foreach(walk(_, pkg, outer, tmpl, inLoop))
        case t: Term if tmpl >= 0 && !inLoop && headEnd(t).isDefined =>
          val pos = t.pos
          loops += Loop(tmpl, pos.start, pos.end, pos.startLine + 1, pos.startColumn + 1, label(t, headEnd(t).get))
          // Inner loops belong to this one; only nested definitions start anew.
          t.children.foreach(walk(_, pkg, outer, tmpl, inLoop = true))
        case _ => tree.children.foreach(walk(_, pkg, outer, tmpl, inLoop))
      }

      walk(source, Nil, Nil, -1, inLoop = false)
      val text = source.pos.input.text
      // Line starts, plus one past the end so the last line has a bound.
      val starts = (0 +: text.indices.filter(text(_) == '\n').map(_ + 1)) :+ (text.length + 1)
      FileIndex(starts.toIndexedSeq, templates.result(), loops.result(), fsm.result(), scopes.result())
    }

    /** The loop head's text: from the construct's start to its body (or the
      * whole call for a placeholder lambda), on one line. */
    private def label(t: Term, end: Int): String =
      t.pos.input.text.substring(t.pos.start, end).trim.replaceAll("\\s+", " ")

    /** Where the head of a generator construct ends, when `t` is one. */
    private def headEnd(t: Term): Option[Int] = t match {
      case f: Term.For     => Some(f.body.pos.start)
      case f: Term.ForYield => Some(f.body.pos.start)
      case a: Term.Apply if callName(a.fun).exists(LoopNames) =>
        a.argClause.values.lastOption.flatMap(bodyStart(_, a)).filter(_ >= a.pos.start)
      case _ => None
    }

    private def callName(fun: Term): Option[String] = fun match {
      case Term.Name(n)                        => Some(n)
      case Term.Select(_, Term.Name(n))        => Some(n)
      case Term.ApplyType.After_4_6_0(f, _)    => callName(f)
      case a: Term.Apply                       => callName(a.fun) // curried: tabulate(n)(f), foldLeft(z)(f)
      case _                                   => None
    }

    /** The start of the generated body in a function-literal or block argument. */
    private def bodyStart(arg: Term, call: Term.Apply): Option[Int] = arg match {
      case f: Term.Function          => Some(f.body.pos.start)
      case _: Term.AnonymousFunction => Some(call.pos.end)
      case p: Term.PartialFunction   => p.cases.headOption.map(_.body.pos.start)
      case b: Term.Block => b.stats match {
        case List(f: Term.Function)        => Some(f.body.pos.start)
        case List(p: Term.PartialFunction) => p.cases.headOption.map(_.body.pos.start)
        case _                             => Some(b.pos.start)
      }
      case _ => None
    }
  }
}
