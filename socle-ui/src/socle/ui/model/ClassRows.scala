package socle.ui.model

import socle.layout.ClassGroups.{ClassGroup, Parameterization}
import socle.schema.ParamDef

/** The rows of the Hierarchy's Classes tab: the design's modules by Scala
  * class, then by parameterization (modules built with the same constructor
  * arguments), then their instances. Pure: the pane renders these rows. */
object ClassRows {

  /** One row. `kind` is "class", "params" or "instance"; `count` the class's
    * modules or the parameterization's instances (0 on an instance row);
    * `unknown` marks a parameterization some value of which could not be read;
    * `path` the instance's path (top first) to open, `module` its module name;
    * `expandable`/`expanded` the chevron of a class or parameterization row. */
  final case class Row(
    id: String, kind: String, depth: Int, label: String, title: String, count: Int,
    unknown: Boolean = false, path: List[String] = Nil, module: String = "",
    expandable: Boolean = false, expanded: Boolean = false,
  )

  /** The `?` marker's tooltip on a parameterization not fully read. */
  val Unknown = "some values could not be read: not compared"

  /** `(nCores = 2)`, or `(no parameters)`. */
  def paramsLabel(params: Seq[ParamDef]): String =
    if (params.isEmpty) "(no parameters)" else params.map(p => s"${p.name} = ${p.value}").mkString("(", ", ", ")")

  /** An instance path as a row shows it: below the top (`cluster0/cores_0`),
    * the top module by its name. */
  def pathLabel(path: List[String]): String =
    if (path.tail.isEmpty) path.head else path.tail.mkString("/")

  /** The rows shown: the user's classes first, then the library's. A class row
    * is closed by default, a parameterization row open; an id in `toggled`
    * flips that. A class with a single parameterization without
    * parameters lists its instances directly. */
  def build(groups: Seq[ClassGroup], toggled: Set[String]): List[Row] = {
    val out = List.newBuilder[Row]
    def instances(p: Parameterization, depth: Int): Unit =
      for (m <- p.modules; path <- m.paths)
        out += Row(path.mkString("/"), "instance", depth, pathLabel(path), path.mkString(" > "), 0,
          path = path, module = m.name)
    ordered(groups).foreach { g =>
      val id   = s"class:${g.scalaClass}"
      val open = toggled(id)
      out += Row(id, "class", 0, ScalaClassLabel.simpleName(g.scalaClass, g.scalaClass), g.scalaClass,
        g.parameterizations.map(_.modules.size).sum, expandable = true, expanded = open)
      if (open) g.parameterizations match {
        case Seq(only) if only.params.isEmpty => instances(only, 1)
        case ps => ps.zipWithIndex.foreach { case (p, i) =>
          val pid   = s"$id#$i"
          val pOpen = !toggled(pid)
          val label = paramsLabel(p.params)
          val note  = if (p.unknown) Unknown else "modules built with the same constructor arguments"
          out += Row(pid, "params", 1, label, s"$label: $note",
            p.modules.map(_.paths.size).sum, unknown = p.unknown, expandable = true, expanded = pOpen)
          if (pOpen) instances(p, 2)
        }
      }
    }
    out.result()
  }

  /** The user's classes first, then the library's, each in `groups` order. */
  private def ordered(groups: Seq[ClassGroup]): Seq[ClassGroup] = {
    val (user, library) = groups.partition(!_.library)
    user ++ library
  }

  /** The class view as `socle-state` gives it to an editor (`classes`): every
    * class with every parameterization (a lone one without parameters too)
    * and its instances, in the order the rows show them. */
  def toJson(groups: Seq[ClassGroup]): ujson.Arr =
    ujson.Arr.from(ordered(groups).map { g =>
      ujson.Obj(
        "scalaClass" -> g.scalaClass,
        "name"       -> ScalaClassLabel.simpleName(g.scalaClass, g.scalaClass),
        "library"    -> g.library,
        "modules"    -> g.parameterizations.map(_.modules.size).sum,
        "parameterizations" -> g.parameterizations.map { p =>
          ujson.Obj(
            "label"   -> paramsLabel(p.params),
            "unknown" -> p.unknown,
            "params"  -> p.params.map(d => ujson.Obj("name" -> d.name, "type" -> d.tpe, "value" -> d.value)),
            "instances" -> (for (m <- p.modules; path <- m.paths)
              yield ujson.Obj("path" -> path, "label" -> pathLabel(path), "module" -> m.name)))
        })
    })
}
