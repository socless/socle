package socless.js

import socless.{Design, BundleTree}
import scala.scalajs.js
import scala.scalajs.js.annotation._

/** The `window.SoclessLayout` global (issue #167): the page's entry point into
  * the in-browser elkjs layout. Parses the design once per key (designs can be
  * large), then lays out a named module on demand.
  *
  * NoModule + @JSExportTopLevel emits a top-level `let SoclessLayout`, so the
  * page must bridge it onto window with `window.SoclessLayout = SoclessLayout;`
  * right after the inlined script. */
@JSExportTopLevel("SoclessLayout")
object LayoutExport {

  /** Toolchain smoke (kept from the Phase-1 probe). */
  @JSExport
  def ping(): String = "socless-scalajs-ok"

  // Parse cache: the UI passes a stable key (e.g. "<top>:<version>") so the same
  // design is not re-parsed on every interactive re-layout.
  private var cachedKey: String    = null
  private var cachedDesign: Design = null

  private def designOf(json: String, key: String): Design = {
    if (cachedDesign == null || key == null || key != cachedKey) {
      cachedDesign = upickle.default.read[Design](json)
      cachedKey = key
    }
    cachedDesign
  }

  /** Lay out `moduleName` from the given design.
    *
    * @param designJson the serialized Design (window.DESIGN.RAW stringified)
    * @param designKey  a stable cache key for that design
    * @param moduleName the module to lay out
    * @param mode       "optimize" (full layered) or "reroute" (interactive)
    * @return a Promise of `{nodes, edges, w, h}` in the UI's layout shape
    */
  @JSExport
  def layout(designJson: String, designKey: String, moduleName: String,
             mode: String, overrides: js.Dynamic): js.Promise[js.Dynamic] = {
    try {
      val design = designOf(designJson, designKey)
      design.modules.find(_.name == moduleName) match {
        case Some(module) => BrowserLayout.run(module, design, if (mode == null) "optimize" else mode, overrides)
        case None         => js.Promise.reject(new js.Error(s"module '$moduleName' not found"))
      }
    } catch {
      case t: Throwable => js.Promise.reject(new js.Error(s"layout failed: ${t.getMessage}"))
    }
  }

  // ── Bundle-tree walk for in-box port expansion (issue #170) ────────────────
  // Synchronous (no elkjs): the UI calls these instead of its former JS
  // portTree / resolvePinDir. The design is parse-cached by key like layout().

  /** Expand `bundleKey` into its nested channel/Vec/Decoupled tree. `baseDir`
    * is "out"/"in" or null. Returns `{name, path, bundleKey, isExpandable,
    * dir, width, children:[...]}`. */
  @JSExport
  def portTree(designJson: String, designKey: String, bundleKey: String,
               baseDir: String, parentPath: String): js.Dynamic =
    nodeToJs(BundleTree.portTree(
      bundleKey, Option(baseDir).filter(_.nonEmpty), parentPath, designOf(designJson, designKey)))

  /** Resolve a pin's true direction ("out"/"in"), or "" when unresolvable. */
  @JSExport
  def resolvePinDir(designJson: String, designKey: String, instType: String, pinPath: String): String =
    BundleTree.resolvePinDir(instType, pinPath, designOf(designJson, designKey)).getOrElse("")

  private def nodeToJs(n: BundleTree.PortNode): js.Dynamic = {
    val dirJs: js.Any = n.dir match { case Some(d) => d; case None => null }
    js.Dynamic.literal(
      name = n.name, path = n.path, bundleKey = n.bundleKey,
      isExpandable = n.isExpandable,
      dir = dirJs,
      width = n.width,
      children = js.Array(n.children.map(nodeToJs): _*),
    )
  }
}
