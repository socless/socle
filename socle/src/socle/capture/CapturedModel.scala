package socle.capture

import upickle.default._

// The design as captured from live, elaborated Chisel objects by the probe
// (socle-probe's ModelWalk). Pure data: no Chisel import, so it compiles
// both in `socle` (which adapts it to the design.json schema) and, via
// sharedSources, in `socle-probe` (which produces it inside a user's JVM).
// Anything the probe could not read is reported as such ("<unreadable>", a
// `problems` entry), never guessed.

/** One capture. `status` is "ok" | "no-design" | "elaboration-error". */
case class CapturedModel(
  status:   String,
  top:      String,              // module name of the DUT ("GCD"), "" unless ok
  modules:  Seq[CModule],        // one per distinct module name, top first
  chirrtl:  String = "",         // circuit serialization, for the wiring fallback
  problems: Seq[String] = Nil,
  error:    String = "",         // exception message when elaboration-error
  chiselVersion: String = "",    // Chisel version of the test JVM, "" when unreadable
)

/** One distinct module: `name` is the elaborated module name ("Queue4_Token"),
  * `scalaClass` the generator class ("chisel3.util.Queue"). */
case class CModule(
  name:       String,
  scalaClass: String,
  params:     Seq[CParam],
  source:     Option[CSource],
  ports:      Seq[CPort],
  instances:  Seq[CInstance],
  wires:      Seq[CWire] = Nil,  // empty with non-empty chirrtl: consumer derives them (WiresFromChirrtl)
)

/** A primary-constructor parameter; `value` is "<unreadable>" when it is not a val. */
case class CParam(name: String, tpe: String, value: String)

case class CSource(file: String, line: Int, col: Int)

case class CPort(name: String, tpe: CType)

/** A port's type. `dir` is "in" | "out" | "flip" | "unspecified" (the specified
  * direction); `width` is -1 when unknown; `fields` keep declaration order;
  * `vecLen` is -1 unless this is a Vec (then `fields` holds its element type
  * once, under the name "0"). `label` is the simple class name, or the field
  * name for an anonymous bundle. */
case class CType(
  scalaClass: String,
  label:      String,
  dir:        String,
  width:      Int,
  fields:     Seq[(String, CType)] = Nil,
  vecLen:     Int = -1,
)

case class CInstance(name: String, module: String)

/** Dotted endpoint paths, as in WireDef ("cpu.io", "io.out"). */
case class CWire(from: String, to: String)

object CSource   { implicit val rw: ReadWriter[CSource]   = macroRW }
object CParam    { implicit val rw: ReadWriter[CParam]    = macroRW }
object CType     { implicit lazy val rw: ReadWriter[CType] = macroRW }
object CPort     { implicit val rw: ReadWriter[CPort]     = macroRW }
object CInstance { implicit val rw: ReadWriter[CInstance] = macroRW }
object CWire     { implicit val rw: ReadWriter[CWire]     = macroRW }
object CModule   { implicit val rw: ReadWriter[CModule]   = macroRW }

object CapturedModel {
  implicit val rw: ReadWriter[CapturedModel] = macroRW

  def toJson(m: CapturedModel): String = write(m)
  def fromJson(s: String): CapturedModel = read[CapturedModel](s)

  /** The Chisel version Socle is built and tested with: build.mill's
    * `V.chisel` (a socle test pins the two together). The probe hooks Chisel
    * internals by name, so another major.minor line may capture oddly or not at all. */
  val TestedChisel = "7.16.0"

  /** Whether `version` is on the tested major.minor line (a snapshot or release
    * candidate of it is); an unknown ("") version is not flagged. */
  def onTestedLine(version: String): Boolean = {
    def line(v: String) = v.split("[.+-]").take(2).toSeq
    version.isEmpty || line(version) == line(TestedChisel)
  }

  /** "Chisel 8.1.0; Socle is tested with Chisel 7.16.0" when `version` is off the line. */
  def untestedNote(version: String): Option[String] =
    Option.when(!onTestedLine(version))(s"Chisel $version; Socle is tested with Chisel $TestedChisel")
}
