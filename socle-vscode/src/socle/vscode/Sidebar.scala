package socle.vscode

import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import socle.vscode.facade._
import SidebarModel._

/** The Socle side panel: the Tests, Hierarchy and Details tree views of the
  * activity bar container. Decisions live in [[SidebarModel]]; this only wires
  * vscode. Tests come from the language server (`socle/tests`, refreshed on
  * `socle/testsChanged`, run states from `socle/status`); Hierarchy and
  * Details from the UI in the panel (`socle-state`, through [[Panel]]).
  *
  * Tree elements are plain objects holding their tree item (`item`), so the
  * e2e's `socle._views` snapshot and the inline actions see real fields.
  */
object Sidebar {
  private var suites      = Seq.empty[SuiteInfo]
  private var testsLoaded = false
  private val tracker     = new RunTracker
  private var ui          = Option.empty[UiState]    // the last path, selection and details
  private var design      = Option.empty[DesignInfo] // the last hierarchy (sent only when it changes)
  private var client      = Option.empty[LanguageClient]
  private var problem     = Option.empty[String]     // why there are no tests (Java, server stopped)

  private val testsEvents   = new EventEmitter()
  private val hierEvents    = new EventEmitter()
  private val detailsEvents = new EventEmitter()
  private var testsView     = Option.empty[TreeView]
  private var hierView      = Option.empty[TreeView]
  private var detailsView   = Option.empty[TreeView]

  // Hierarchy elements by path id, one object per node for the current design
  // (reveal and getParent need the same object back).
  private var hierElements = Map.empty[String, js.Dynamic]

  private val Expanded  = 2
  private val Collapsed = 1
  private val Flat      = 0

  private def rootUri: Option[String] =
    workspace.workspaceFolders.toOption.flatMap(_.headOption).map(_.uri.toString())
  private def rootPath: Option[String] =
    workspace.workspaceFolders.toOption.flatMap(_.headOption).map(_.uri.fsPath)

  private def item(r: Row, collapsible: Int, command: Option[js.Object] = None): js.Dynamic = {
    val o = js.Dynamic.literal(id = r.id, label = r.label, description = r.description,
      contextValue = r.contextValue, collapsibleState = collapsible)
    if (r.tooltip.nonEmpty) o.updateDynamic("tooltip")(r.tooltip)
    if (r.icon.nonEmpty) o.updateDynamic("iconPath")(new ThemeIcon(r.icon))
    command.foreach(c => o.updateDynamic("command")(c))
    o
  }

  private def command(id: String, title: String, args: js.Any*): js.Object =
    js.Dynamic.literal(command = id, title = title, arguments = js.Array(args: _*))

  private def provider(events: EventEmitter, children: js.UndefOr[js.Dynamic] => js.Array[js.Dynamic],
      parent: js.Dynamic => js.UndefOr[js.Dynamic] = _ => js.undefined): js.Object =
    js.Dynamic.literal(
      onDidChangeTreeData = events.event,
      getChildren = ((el: js.UndefOr[js.Dynamic]) => children(el)): js.Function1[js.UndefOr[js.Dynamic], js.Array[js.Dynamic]],
      getTreeItem = ((el: js.Dynamic) => el.item): js.Function1[js.Dynamic, js.Dynamic],
      getParent = ((el: js.Dynamic) => parent(el)): js.Function1[js.Dynamic, js.UndefOr[js.Dynamic]],
    )

  // ── Tests ──────────────────────────────────────────────────────────────────

  private def testsChildren(el: js.UndefOr[js.Dynamic]): js.Array[js.Dynamic] = el.toOption match {
    case None =>
      suites.map { s =>
        js.Dynamic.literal(kind = "suite", suite = s.suite, file = s.file,
          item = item(suiteRow(s, rootUri), Expanded))
      }.map(e => e: js.Dynamic).toJSArray
    case Some(e) if e.kind.asInstanceOf[Any] == "suite" =>
      suites.find(s => s.suite == e.suite.asInstanceOf[String] && s.file == e.file.asInstanceOf[String]).toSeq.flatMap { s =>
        s.tests.map { t =>
          val row = testRow(s.suite, s.file, t, tracker.state(TestKey(s.suite, t.name)))
          js.Dynamic.literal(kind = "test", suite = s.suite, file = s.file, test = t.name, line = t.line,
            item = item(row, Flat, Some(command("socle._openTest", "Open test", s.file, t.line))))
        }
      }.map(e => e: js.Dynamic).toJSArray
    case _ => js.Array()
  }

  private def refreshTests(): Unit = {
    val msg = testsMessage(testsLoaded, suites, problem).orUndefined
    testsView.foreach(_.message = msg)
    testsEvents.fire(js.undefined)
  }

  /** Asks the server for the tests (`socle/tests`); a failure keeps the last list. */
  private def fetchTests(): Unit = client.foreach { c =>
    c.sendRequest("socle/tests").`then`[Unit] { (r: js.Any) =>
      suites = parseTests(r)
      testsLoaded = true
      refreshTests()
    }.asInstanceOf[js.Dynamic].`catch`((_: js.Any) => ())
  }

  /** `(file, suite, test)` of a Tests view element (or any object with those fields). */
  private def testArgs(el: js.Any): Option[(String, String, String)] =
    if (el == null || js.isUndefined(el) || js.typeOf(el) != "object") None
    else {
      val d = el.asInstanceOf[js.Dynamic]
      for {
        f <- Option(d.file.asInstanceOf[js.Any]).filter(js.typeOf(_) == "string")
        s <- Option(d.suite.asInstanceOf[js.Any]).filter(js.typeOf(_) == "string")
        t <- Option(d.test.asInstanceOf[js.Any]).filter(js.typeOf(_) == "string")
      } yield (f.asInstanceOf[String], s.asInstanceOf[String], t.asInstanceOf[String])
    }

  /** Runs a server command on a test. Without a running server the command does not exist: say
    * so instead of marking the test running; a rejected View design is not running either. */
  private def runOnItem(serverCommand: String, el: js.Any): Unit = testArgs(el).foreach { case (file, suite, test) =>
    if (!client.exists(_.isRunning()))
      window.showErrorMessage(problem.getOrElse("Socle: the language server is not running."))
    else {
      val key  = TestKey(suite, test)
      val view = serverCommand == "socle.viewDesign"
      if (view) { tracker.viewStarted(key); refreshTests() }
      commands.executeCommand(serverCommand, file, suite, test).asInstanceOf[js.Dynamic].`catch` { (_: js.Any) =>
        if (view) { tracker.viewNotStarted(key); refreshTests() }
      }
    }
  }

  private def openAt(uri: Uri, line: Int, preserveFocus: Boolean): Unit = {
    val at = new Range(line, 0, line, 0)
    ignore(window.showTextDocument(uri, js.Dynamic.literal(selection = at, preserveFocus = preserveFocus, preview = true,
      viewColumn = Panel.sourceColumn)))
  }

  // ── Hierarchy ──────────────────────────────────────────────────────────────

  private def hierElement(d: DesignInfo, path: List[String]): Option[js.Dynamic] = {
    val id = hierId(path)
    hierElements.get(id).orElse(hierRow(d, path).map { r =>
      val e = js.Dynamic.literal(kind = "hier", path = path.toJSArray,
        item = item(r, if (!r.expandable) Flat else if (path.size == 1) Expanded else Collapsed,
          Some(command("socle._openHierarchy", "Show in the schematic", path.toJSArray))))
      hierElements += id -> e
      e
    })
  }

  private def pathOf(el: js.Dynamic): List[String] = el.path.asInstanceOf[js.Array[String]].toList

  private def hierChildrenOf(el: js.UndefOr[js.Dynamic]): js.Array[js.Dynamic] =
    design.fold(js.Array[js.Dynamic]()) { d =>
      val paths = el.toOption.fold(hierRoots(d))(e => hierChildren(d, pathOf(e)))
      paths.flatMap(hierElement(d, _)).toJSArray
    }

  private def hierParent(el: js.Dynamic): js.UndefOr[js.Dynamic] = {
    val p = pathOf(el)
    design.filter(_ => p.size > 1).flatMap(hierElement(_, p.init)).orUndefined
  }

  /** Selects the drill path, or the selected instance under it, in the Hierarchy (only while the
    * view is visible: reveal would open it). */
  private def revealDrillPath(): Unit =
    for {
      v <- hierView if v.visible
      s <- ui
      d <- design
      p <- revealPath(d, s.path, s.selection)
      e <- hierElement(d, p)
    } v.reveal(e, js.Dynamic.literal(select = true, focus = false)).asInstanceOf[js.Dynamic].`catch`((_: js.Any) => ())

  /** A Hierarchy click: the schematic drills there (or selects a leaf in its parent), and the
    * module's source opens beside, keeping the focus in the tree. */
  private def openHierarchy(pathArg: js.Any): Unit =
    if (js.Array.isArray(pathArg)) {
      val path = pathArg.asInstanceOf[js.Array[Any]].toList.collect { case s: String => s }
      for (d <- design) {
        navigateTarget(d, path).foreach { case (p, sel) => Panel.navigate(navigateMessage(p, sel)) }
        for {
          src  <- moduleSource(d, path)
          file <- sourcePath(src.file, rootPath)
        } openAt(Uri.file(file), math.max(src.line - 1, 0), preserveFocus = true)
      }
    }

  // ── Details ────────────────────────────────────────────────────────────────

  private def detailElements(rows: Seq[Row]): js.Array[js.Dynamic] =
    rows.map { r =>
      js.Dynamic.literal(kind = "detail", item = item(r, if (r.expandable) Expanded else Flat),
        children = detailElements(r.children))
    }.map(e => e: js.Dynamic).toJSArray

  private def detailsChildren(el: js.UndefOr[js.Dynamic]): js.Array[js.Dynamic] = el.toOption match {
    case None    => ui.flatMap(_.details).fold(js.Array[js.Dynamic]())(det => detailElements(detailRows(det)))
    case Some(e) => e.children.asInstanceOf[js.Array[js.Dynamic]]
  }

  // ── wiring ─────────────────────────────────────────────────────────────────

  private val NoDesign = "Run View design on a test to see its design here."

  /** Creates the three views and the side panel commands. */
  def register(ctx: ExtensionContext): Unit = {
    def view(id: String, p: js.Object): TreeView = {
      val v = window.createTreeView(id, js.Dynamic.literal(treeDataProvider = p, showCollapseAll = true))
      ctx.subscriptions.push(v)
      v
    }
    testsView = Some(view("socle.tests", provider(testsEvents, testsChildren)))
    hierView = Some(view("socle.hierarchy", provider(hierEvents, hierChildrenOf, hierParent)))
    detailsView = Some(view("socle.details", provider(detailsEvents, detailsChildren)))
    hierView.foreach(_.message = NoDesign)
    detailsView.foreach(_.message = NoDesign)
    hierView.foreach(v => ctx.subscriptions.push(v.onDidChangeVisibility((_: js.Any) => revealDrillPath())))
    Seq(testsEvents, hierEvents, detailsEvents).foreach(e => ctx.subscriptions.push(e))

    def register(id: String, f: js.Function): Unit = ctx.subscriptions.push(commands.registerCommand(id, f))
    register("socle.viewDesignItem", ((el: js.Any) => runOnItem("socle.viewDesign", el)): js.Function1[js.Any, Unit])
    register("socle.simulateItem", ((el: js.Any) => runOnItem("socle.simulate", el)): js.Function1[js.Any, Unit])
    register("socle.refreshTests", (() => fetchTests()): js.Function0[Unit])
    register("socle.openSettings",
      (() => ignore(commands.executeCommand("workbench.action.openSettings", "@ext:socle.socle"))): js.Function0[Unit])
    register("socle._openTest",
      ((file: String, line: Int) => openAt(Uri.parse(file), line, preserveFocus = false)): js.Function2[String, Int, Unit])
    register("socle._openHierarchy", ((path: js.Any) => openHierarchy(path)): js.Function1[js.Any, Unit])
    register("socle._views", (() => snapshot()): js.Function0[js.Any])
  }

  /** Hooks the language client: tests and fresh run states on every (re)start, tests on
    * `socle/testsChanged`, run states on `socle/status`, a message while the server is stopped. */
  def attach(c: LanguageClient, ctx: ExtensionContext): Unit = {
    client = Some(c)
    ctx.subscriptions.push(c.onDidChangeState { (e: js.Dynamic) =>
      val state = e.newState.asInstanceOf[Int]
      problem = serverProblem(state)
      if (state == 2) { tracker.reset(); fetchTests() } // Running: a new server knows of no run
      refreshTests()
    })
    ctx.subscriptions.push(c.onNotification("socle/testsChanged", (_: js.Dynamic) => fetchTests()))
    ctx.subscriptions.push(c.onNotification("socle/status", (p: js.Dynamic) => {
      def s(v: js.Dynamic) = if (js.typeOf(v) == "string") v.asInstanceOf[String] else ""
      tracker.onStatus(s(p.status), s(p.suite), s(p.test))
      refreshTests()
    }))
  }

  /** The server cannot start (Java missing or too old): the Tests view says why. */
  def unavailable(why: String): Unit = { problem = Some(why); refreshTests() }

  /** A `socle-state` from the UI in the panel. */
  def onUiState(msg: js.Any): Unit = parseUiState(msg).foreach { s =>
    ui = Some(s)
    s.design.filter(_ != design).foreach { d =>
      design = d
      hierElements = Map.empty
      val hm: js.UndefOr[String] = if (d.isEmpty) NoDesign else js.undefined
      hierView.foreach(_.message = hm)
      hierEvents.fire(js.undefined)
    }
    val dm: js.UndefOr[String] = if (s.details.isEmpty) NoDesign else js.undefined
    detailsView.foreach(_.message = dm)
    detailsEvents.fire(js.undefined)
    revealDrillPath()
  }

  /** The panel closed: no design to show. */
  def onPanelClosed(): Unit = {
    ui = None
    design = None
    tracker.panelClosed()
    refreshTests()
    hierElements = Map.empty
    hierView.foreach(_.message = NoDesign)
    detailsView.foreach(_.message = NoDesign)
    hierEvents.fire(js.undefined)
    detailsEvents.fire(js.undefined)
  }

  /** For the e2e (`socle._views`): every row of the three trees, depth first, with its element. */
  private def snapshot(): js.Any = {
    def walk(children: js.UndefOr[js.Dynamic] => js.Array[js.Dynamic]): js.Array[js.Dynamic] = {
      val out = js.Array[js.Dynamic]()
      def go(el: js.UndefOr[js.Dynamic], depth: Int): Unit = children(el).foreach { e =>
        val it = e.item
        out.push(js.Dynamic.literal(depth = depth, id = it.id, label = it.label, description = it.description,
          icon = if (js.isUndefined(it.iconPath)) "" else it.iconPath.id, command = it.command, element = e))
        go(e, depth + 1)
      }
      go(js.undefined, 0)
      out
    }
    js.Dynamic.literal(tests = walk(testsChildren), hierarchy = walk(hierChildrenOf), details = walk(detailsChildren))
  }

  private def ignore(p: js.Promise[_]): Unit = p.asInstanceOf[js.Dynamic].`catch`((_: js.Any) => ())
}
