package socless.vscode

import scala.scalajs.js

/** The side panel's pure model: the `socless/tests` and `socless-state`
  * payloads, per-test run states and the rows of the three trees (Tests,
  * Hierarchy, Details). No `vscode` import, so it runs under plain Node in tests;
  * [[Sidebar]] turns rows into VS Code tree items.
  */
object SidebarModel {

  // ── payload reading (tolerant: a malformed part is skipped, never thrown) ──

  private def obj(v: js.Any): Option[js.Dynamic] =
    if (v == null || js.isUndefined(v) || js.typeOf(v) != "object" || js.Array.isArray(v)) None
    else Some(v.asInstanceOf[js.Dynamic])

  private def str(v: js.Any): Option[String] =
    if (js.typeOf(v) == "string") Some(v.asInstanceOf[String]) else None

  private def int(v: js.Any): Option[Int] =
    if (js.typeOf(v) == "number" && v.asInstanceOf[Double].isWhole) Some(v.asInstanceOf[Double].toInt) else None

  private def arr(v: js.Any): Option[Seq[js.Any]] =
    if (js.Array.isArray(v)) Some(v.asInstanceOf[js.Array[js.Any]].toSeq) else None

  /** An optional string: absent or null is `Some(None)`, a string `Some(Some(s))`, anything else None. */
  private def optStr(v: js.Any): Option[Option[String]] =
    if (v == null || js.isUndefined(v)) Some(None) else str(v).map(Some(_))

  // ── Tests ──────────────────────────────────────────────────────────────────

  final case class TestInfo(name: String, line: Int)
  final case class SuiteInfo(suite: String, file: String, tests: Seq[TestInfo])

  /** The `socless/tests` result: `{suites: [{suite, file, tests: [{name, line}]}]}`. */
  def parseTests(result: js.Any): Seq[SuiteInfo] =
    obj(result).flatMap(o => arr(o.suites)).getOrElse(Nil).flatMap { s =>
      for {
        o     <- obj(s)
        suite <- str(o.suite)
        file  <- str(o.file)
        ts    <- arr(o.tests)
        tests  = ts.flatMap(t => obj(t).flatMap(x => for (n <- str(x.name); l <- int(x.line)) yield TestInfo(n, l)))
        if tests.nonEmpty
      } yield SuiteInfo(suite, file, tests)
    }

  /** `fileUri` relative to the workspace folder `rootUri`, else its decoded path. */
  def relativePath(fileUri: String, rootUri: Option[String]): String = {
    val root = rootUri.map(r => if (r.endsWith("/")) r else r + "/")
    root.filter(fileUri.startsWith).map(r => decode(fileUri.substring(r.length)))
      .getOrElse(decode(fileUri.stripPrefix("file://")))
  }

  private def decode(s: String): String =
    try js.URIUtils.decodeURIComponent(s) catch { case _: Throwable => s }

  final case class TestKey(suite: String, test: String)

  sealed trait RunState
  case object Idle       extends RunState
  case object Shown      extends RunState
  case object Running    extends RunState
  case object Simulating extends RunState
  case object Failed     extends RunState

  /** Per-test run state from the server's `socless/status` (status, suite,
    * test) and the extension's own View design commands. */
  final class RunTracker {
    private var shown      = Option.empty[TestKey] // whose design the panel shows
    private var running    = Option.empty[TestKey]
    private var simulating = Option.empty[TestKey]
    private var failed     = Set.empty[TestKey]

    def onStatus(status: String, suite: String, test: String): Unit = {
      val key = Option.when(suite.nonEmpty && test.nonEmpty)(TestKey(suite, test))
      status match {
        case "running"    => running = key; simulating = None
        case "simulating" => simulating = key; running = None
        case "ok" =>
          running = None; simulating = None
          key.foreach { k => shown = Some(k); failed -= k }
        case "error" =>
          running = None; simulating = None
          key.foreach(failed += _)
        case _ => running = None; simulating = None
      }
    }

    /** A view started from the Tests view: running until the server says otherwise. */
    def viewStarted(key: TestKey): Unit = running = Some(key)

    /** The View design command for `key` was refused (no server, or it rejected): not running. */
    def viewNotStarted(key: TestKey): Unit = if (running.contains(key)) running = None

    /** The panel closed: no test is shown any more. */
    def panelClosed(): Unit = shown = None

    /** A (re)started server knows of no run: forget every state. */
    def reset(): Unit = { shown = None; running = None; simulating = None; failed = Set.empty }

    def state(key: TestKey): RunState =
      if (running.contains(key)) Running
      else if (simulating.contains(key)) Simulating
      else if (failed(key)) Failed
      else if (shown.contains(key)) Shown
      else Idle
  }

  /** One tree row, independent of VS Code: `icon` is a codicon id. */
  final case class Row(
      id: String,
      label: String,
      description: String = "",
      tooltip: String = "",
      icon: String = "",
      contextValue: String = "",
      expandable: Boolean = false,
      children: Seq[Row] = Nil,
  )

  def suiteRow(s: SuiteInfo, rootUri: Option[String]): Row = {
    val rel = relativePath(s.file, rootUri)
    Row(s"suite:${s.file}#${s.suite}", s.suite.split('.').last, rel, s"${s.suite}\n$rel", "symbol-class", "suite", expandable = true)
  }

  def testRow(suite: String, file: String, t: TestInfo, st: RunState): Row = {
    val (icon, desc) = st match {
      case Idle       => ("beaker", "")
      case Shown      => ("eye", "shown")
      case Running    => ("sync~spin", "running…")
      case Simulating => ("sync~spin", "simulating…")
      case Failed     => ("error", "failed")
    }
    Row(s"test:$file#$suite#${t.name}", t.name, desc, s"$suite: ${t.name}", icon, "test")
  }

  /** The Tests view's message: why it is empty, else None. */
  def testsMessage(loaded: Boolean, suites: Seq[SuiteInfo], problem: Option[String]): Option[String] =
    problem.orElse(Option.when(loaded && suites.isEmpty)("No ChiselSim tests in this workspace."))

  /** The Tests view's problem for a language client state (vscode-languageclient: 1 Stopped,
    * 2 Running, 3 Starting): None unless the server stopped. */
  def serverProblem(clientState: Int): Option[String] =
    Option.when(clientState == 1)("The SoCLess language server is not running. Reload the window to restart it.")

  // ── socless-state (from the UI in the panel) ───────────────────────────────

  final case class SourceInfo(file: String, line: Int)
  final case class ModuleInfo(name: String, scalaClass: String, kind: String, source: Option[SourceInfo],
      children: Seq[(String, String)])
  final case class DesignInfo(top: String, modules: Map[String, ModuleInfo])
  final case class DetailsInfo(name: String, module: String, scalaClass: String, kind: String,
      params: Seq[(String, String, String)], ports: Seq[(String, String, String)])
  /** `design`: None when the message leaves the design as it was (the UI sends the
    * hierarchy only when it changes), Some(None) when there is none. */
  final case class UiState(design: Option[Option[DesignInfo]], path: List[String], selection: Option[String],
      details: Option[DetailsInfo])

  private def parseModule(v: js.Any): Option[ModuleInfo] =
    for {
      o    <- obj(v)
      name <- str(o.name)
      kids <- arr(o.children)
    } yield ModuleInfo(name, str(o.scalaClass).getOrElse(""), str(o.kind).getOrElse(""),
      obj(o.source).flatMap(s => for (f <- str(s.file); l <- int(s.line)) yield SourceInfo(f, l)),
      kids.flatMap(c => obj(c).flatMap(x => for (n <- str(x.name); t <- str(x.`type`)) yield (n, t))))

  private def triples(v: js.Any, a: String, b: String, c: String): Seq[(String, String, String)] =
    arr(v).getOrElse(Nil).flatMap(e => obj(e).flatMap { x =>
      for (p <- str(x.selectDynamic(a)); q <- str(x.selectDynamic(b)); r <- str(x.selectDynamic(c))) yield (p, q, r)
    })

  /** A `{type: "socless-state", design?, path, selection, details}` message (no
    * `design` key: unchanged since the last message), or
    * None when it is not one (wrong type, path not an array of strings, a
    * selection neither null nor a string, a design or details of the wrong shape). */
  def parseUiState(msg: js.Any): Option[UiState] =
    obj(msg).filter(o => str(o.`type`).contains("socless-state")).flatMap { o =>
      val path = arr(o.path).filter(_.forall(p => js.typeOf(p) == "string")).map(_.map(_.asInstanceOf[String]).toList)
      val design: Option[Option[Option[DesignInfo]]] =
        if (js.isUndefined(o.design)) Some(None)
        else if (o.design == null) Some(Some(None))
        else for {
          d    <- obj(o.design)
          top  <- str(d.top)
          mods <- arr(d.modules)
        } yield Some(Some(DesignInfo(top, mods.flatMap(parseModule).map(m => m.name -> m).toMap)))
      val details: Option[Option[DetailsInfo]] =
        if (o.details == null || js.isUndefined(o.details)) Some(None)
        else obj(o.details).flatMap { x =>
          for (n <- str(x.name); m <- str(x.module)) yield Some(DetailsInfo(n, m,
            str(x.scalaClass).getOrElse(""), str(x.kind).getOrElse(""),
            triples(x.params, "name", "type", "value"), triples(x.ports, "name", "dir", "type")))
        }
      for (p <- path; s <- optStr(o.selection); d <- design; det <- details) yield UiState(d, p, s, det)
    }

  // ── Hierarchy ──────────────────────────────────────────────────────────────

  /** The module at an instance path (top module name, then instance names). */
  def moduleAt(d: DesignInfo, path: List[String]): Option[ModuleInfo] = path match {
    case Nil => None
    case top :: rest =>
      rest.foldLeft(d.modules.get(top)) { (m, inst) =>
        m.flatMap(_.children.find(_._1 == inst)).flatMap(c => d.modules.get(c._2))
      }
  }

  def hierRoots(d: DesignInfo): Seq[List[String]] =
    if (d.modules.contains(d.top)) Seq(List(d.top)) else Nil

  def hierChildren(d: DesignInfo, path: List[String]): Seq[List[String]] =
    moduleAt(d, path).toSeq.flatMap(_.children.map(c => path :+ c._1))

  def hierId(path: List[String]): String = "hier:" + path.mkString("/")

  /** Instance name, module (or Scala class) simple name; expandable when it has children. */
  def hierRow(d: DesignInfo, path: List[String]): Option[Row] =
    moduleAt(d, path).map { m =>
      val cls  = m.scalaClass.split('.').last
      val desc = if (cls.nonEmpty) cls else m.name
      val tip  = if (m.scalaClass.nonEmpty) s"${m.scalaClass} (module ${m.name})" else s"module ${m.name}"
      val icon = if (m.kind == "external" || m.kind == "intrinsic") "package" else "circuit-board"
      Row(hierId(path), path.last, desc, tip, icon, "module", expandable = m.children.nonEmpty)
    }

  /** Where a Hierarchy click leads: a module with children is drilled into; a
    * leaf (or blackbox) is selected in its parent's view. */
  def navigateTarget(d: DesignInfo, path: List[String]): Option[(List[String], Option[String])] =
    moduleAt(d, path).map { m =>
      if (m.children.isEmpty && path.size > 1) (path.init, Some(path.last)) else (path, None)
    }

  def moduleSource(d: DesignInfo, path: List[String]): Option[SourceInfo] = moduleAt(d, path).flatMap(_.source)

  /** The node to reveal in the Hierarchy: the drill path (only from the top, when it
    * resolves), or the selected instance under it. */
  def revealPath(d: DesignInfo, path: List[String], selection: Option[String] = None): Option[List[String]] =
    Some(path).filter(p => p.headOption.contains(d.top) && moduleAt(d, p).isDefined).map { p =>
      selection.filter(s => moduleAt(d, p).exists(_.children.exists(_._1 == s))).fold(p)(p :+ _)
    }

  // ── Details ────────────────────────────────────────────────────────────────

  def detailRows(det: DetailsInfo): Seq[Row] = {
    val head = Row("detail:name", det.name, det.module, s"${det.name}: module ${det.module}", "symbol-module")
    val cls  = Option.when(det.scalaClass.nonEmpty)(Row("detail:class", det.scalaClass, "Scala class", det.scalaClass, "symbol-class"))
    val params = Option.when(det.params.nonEmpty)(Row("detail:params", "Parameters", det.params.size.toString,
      icon = "symbol-parameter", expandable = true,
      children = det.params.zipWithIndex.map { case ((n, t, v), i) =>
        val label = if (t.nonEmpty) s"$n: $t = $v" else s"$n = $v"
        Row(s"detail:param:$i", label, tooltip = label)
      }))
    val ports = Row("detail:ports", "Ports", det.ports.size.toString, icon = "plug", expandable = true,
      children = det.ports.zipWithIndex.map { case ((n, dir, t), i) =>
        val icon = dir.toLowerCase match {
          case "in" | "input" | "slave"   => "arrow-right"
          case "out" | "output" | "master" => "arrow-left"
          case _                           => "arrow-both"
        }
        Row(s"detail:port:$i", n, s"$dir $t", s"$n: $dir $t", icon)
      })
    Seq(head) ++ cls ++ params :+ ports
  }

  // ── editor side ────────────────────────────────────────────────────────────

  /** The file to open for a module source: absolute as is, else under the workspace folder. */
  def sourcePath(file: String, rootPath: Option[String]): Option[String] =
    if (file.startsWith("/") || file.matches("^[A-Za-z]:[\\\\/].*")) Some(file)
    else rootPath.map(r => r.stripSuffix("/") + "/" + file)

  /** The message the extension posts to the panel page, relayed to the UI. */
  def navigateMessage(path: List[String], select: Option[String]): js.Any =
    js.Dynamic.literal(`type` = "socless-navigate", path = js.Array(path: _*),
      select = select.fold[js.Any](null)(s => s))
}
