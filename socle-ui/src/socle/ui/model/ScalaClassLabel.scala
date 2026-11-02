package socle.ui.model

import socle.schema.ModuleDef

/** Text for the Scala generator of a captured module (`ModuleDef.scalaClass`
  * non-empty): the compact signature a box header shows, and the parameter
  * lines the inspector lists. Designs from CHIRRTL carry no class and use none
  * of this.
  */
object ScalaClassLabel {

  /** Header signatures are cut to this many code points (full text in a tooltip). */
  val HeaderMax = 40

  /** One parameter: name, declared type when known, value. */
  case class Param(name: String, tpe: Option[String], value: String)

  /** Last segment of a binary class name: `deephier.Cluster` -> `Cluster`,
    * `a.Outer$Inner` -> `Inner`, `a.Obj$` -> `Obj`. A numeric segment (an
    * anonymous class, `$anon$1`) falls back to `fallback`. */
  def simpleName(scalaClass: String, fallback: String): String = {
    val s    = scalaClass.stripSuffix("$")
    val last = s.substring(math.max(s.lastIndexOf('.'), s.lastIndexOf('$')) + 1)
    if (last.isEmpty || last.forall(_.isDigit)) fallback else last
  }

  /** The module's parameters in declaration order with their types; a design
    * that only carries the unordered `parameters` map gives them typeless. */
  def params(m: ModuleDef): Seq[Param] =
    if (m.parameterDecls.nonEmpty) m.parameterDecls.map(p => Param(p.name, Some(p.tpe).filter(_.nonEmpty), p.value))
    else m.parameters.toSeq.map { case (k, v) => Param(k, None, v) }

  /** `Cluster(nCores = <unreadable>)`, or just `Cluster` without parameters. */
  def signature(m: ModuleDef): String = {
    val name = simpleName(m.scalaClass, m.name)
    val ps   = params(m)
    if (ps.isEmpty) name else ps.map(p => s"${p.name} = ${p.value}").mkString(s"$name(", ", ", ")")
  }

  /** Inspector line: `name: type = value`, or `name = value` without a type. */
  def line(p: Param): String = p.tpe.fold(s"${p.name} = ${p.value}")(t => s"${p.name}: $t = ${p.value}")

  /** `text` cut to at most `max` code points plus an ellipsis; a surrogate
    * pair is never split. */
  def truncate(text: String, max: Int): String = {
    var i = 0
    var n = 0
    while (i < text.length && n < max) {
      val pair = Character.isHighSurrogate(text.charAt(i)) && i + 1 < text.length &&
        Character.isLowSurrogate(text.charAt(i + 1))
      i += (if (pair) 2 else 1)
      n += 1
    }
    if (i < text.length) text.substring(0, i) + "…" else text
  }
}
