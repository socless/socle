package socless.probe

import chisel3.{ElaboratedCircuit, RawModule}
import chisel3.stage.{ChiselCircuitAnnotation, DesignAnnotation}
import socless.capture.CapturedModel

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable

/** Thrown after the capture in view mode, to stop the test before firtool and
  * Verilator. Scalatest reports the test as failed; callers trust only the
  * captured `status`. */
final class ViewOnly extends RuntimeException("socless: view-only run, stopping before firtool/Verilator")

/** Targets of the advice the agent inlines into Chisel's classes. The first
  * capture written wins; a shutdown hook writes "no-design" when nothing was.
  *
  * A failure of the probe itself (serializing, walking, writing) is never
  * blamed on the user: it becomes a `problems` entry, or a missing output file
  * when even the write fails, and it never escapes into the user's run (only
  * `ViewOnly` does, in view mode). */
object Capture {

  val ModeProp = "socless.probe.mode"
  val OutProp  = "socless.probe.out"

  @volatile private var out: Path       = null
  @volatile private var viewMode        = false
  private val written                   = new AtomicBoolean(false)
  // Set when a DesignAnnotation is built: the user's elaboration succeeded.
  @volatile private var designReached   = false
  // Elaborate builds ChiselCircuitAnnotation just before DesignAnnotation, on the same thread.
  private val circuit                   = new ThreadLocal[ElaboratedCircuit]

  def configure(outFile: Path, view: Boolean): Unit = { out = outFile; viewMode = view }

  /** Test seam: the design walk (ModelWalk.capture in the agent). */
  @volatile private[probe] var walk: (RawModule, String) => CapturedModel = ModelWalk.capture

  /** Test seam: forget any earlier capture and restore the real walk. */
  private[probe] def resetForTest(): Unit = synchronized {
    out = null; viewMode = false; written.set(false); designReached = false
    walk = ModelWalk.capture; circuit.remove()
  }

  /** Exit of the ChiselCircuitAnnotation or DesignAnnotation constructor. */
  def onAnnotation(anno: Any): Unit = anno match {
    case c: ChiselCircuitAnnotation => circuit.set(c.elaboratedCircuit)
    case d: DesignAnnotation[_]     => onDesign(d.design.asInstanceOf[RawModule])
    case _                          => ()
  }

  /** Exit of Elaborate.transform; `thrown` is null when it returned normally.
    * Once the design was reached, whatever is thrown comes from the probe (or
    * is `ViewOnly`), not from the user's elaboration, so it is ignored. */
  def onElaborateExit(thrown: Throwable): Unit = thrown match {
    case null | _: ViewOnly => ()
    case _ if designReached => ()
    case t => writeOrLog(CapturedModel(status = "elaboration-error", top = "", modules = Nil, error = rootMessage(t)))
  }

  /** Exit of `svsim.verilator.Backend$.initializeFromProcessEnvironment`, in
    * view mode only. ChiselSim looks Verilator up before elaborating, and view
    * mode stops before Verilator would run, so a failed lookup (no `verilator`,
    * or no `which`, on the PATH) gets a placeholder backend that is never used.
    * Returns the backend the method returns: its own when the lookup worked,
    * null (the throwable is kept) on a fatal error. */
  def onVerilatorLookupExit(thrown: Throwable, returned: Any): Any = thrown match {
    case null                           => returned
    case scala.util.control.NonFatal(_) => new svsim.verilator.Backend("verilator")
    case _                              => null
  }

  /** JVM shutdown: the test never elaborated a design. When it did and nothing
    * was written, the probe failed: the file stays missing rather than claim
    * "no-design". */
  def onShutdown(): Unit =
    if (!designReached) writeOrLog(CapturedModel(status = "no-design", top = "", modules = Nil))

  private def onDesign(dut: RawModule): Unit = {
    designReached = true
    if (!written.get) {
      try {
        val problems = mutable.ArrayBuffer.empty[String]
        val chirrtl = attempt("cannot read chirrtl", problems, "") {
          val elaborated = Option(circuit.get)
          circuit.remove()
          elaborated match {
            case None    => problems += "cannot read chirrtl: no ChiselCircuitAnnotation before the design"; ""
            case Some(c) => c.serialize
          }
        }
        val version = attempt("cannot read the Chisel version", problems, "")(chiselVersion())
        val walked  = attempt("cannot walk the design", problems, Option.empty[CapturedModel])(Some(walk(dut, chirrtl)))
        val model = walked.getOrElse {
          val top = attempt("cannot read the design name", problems, "")(dut.name)
          CapturedModel(status = "ok", top = top, modules = Nil, chirrtl = chirrtl)
        }
        writeOrLog(model.copy(problems = problems.toSeq ++ model.problems, chiselVersion = version))
      } catch {
        case t: Throwable => logProbeFailure(t)
      }
    }
    if (viewMode) throw new ViewOnly
  }

  /** One read of the design; a failure of any kind becomes a problem. */
  private def attempt[A](what: String, problems: mutable.Buffer[String], fallback: A)(body: => A): A =
    try body
    catch { case t: Throwable => problems += s"$what: $t"; fallback }

  /** The test JVM's Chisel version: `chisel3.BuildInfo.version` (a runtime
    * read of a non-final val), else the jar's Implementation-Version. */
  private def chiselVersion(): String =
    try chisel3.BuildInfo.version
    catch {
      case _: Throwable =>
        Option(classOf[chisel3.Module].getPackage).flatMap(p => Option(p.getImplementationVersion)).getOrElse("")
    }

  private def writeOrLog(model: CapturedModel): Unit =
    try write(model)
    catch { case t: Throwable => logProbeFailure(t) }

  /** The probe itself failed: report it on stderr, never fail the user's run. */
  private def logProbeFailure(t: Throwable): Unit =
    try { System.err.println(s"socless probe: capture failed: $t"); t.printStackTrace() }
    catch { case _: Throwable => () }

  private def write(model: CapturedModel): Unit = synchronized {
    if (out != null && !written.get) {
      Option(out.toAbsolutePath.getParent).foreach(Files.createDirectories(_))
      val tmp = out.resolveSibling(out.getFileName.toString + ".tmp")
      Files.write(tmp, CapturedModel.toJson(model).getBytes(StandardCharsets.UTF_8))
      Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      written.set(true)
    }
  }

  private def rootMessage(t: Throwable): String = {
    var root = t
    while (root.getCause != null && root.getCause != root) root = root.getCause
    Option(root.getMessage).getOrElse(root.toString)
  }
}
