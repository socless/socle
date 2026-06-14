package socless.js

import socless.Design
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
}
