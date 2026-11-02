package socle.lsp

import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters._
import scala.util.Try

/** Bounded waits on forked processes (the probe's test JVM, Mill). os-lib's own
  * timeout/destroy path is avoided: it prints to Console.out (the LSP channel)
  * and kills only the direct child. */
private[lsp] object ProcessTree {

  /** Waits up to `timeoutMs` for `proc`; on timeout kills it and all its
    * descendants and returns false. */
  def waitOrKill(proc: Process, timeoutMs: Long): Boolean =
    proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS) || { kill(proc.toHandle); false }

  /** Forcibly kills a process and all its descendants, and waits for them to exit. */
  def kill(root: ProcessHandle): Unit = {
    // descendants first, collected while the root is alive (they reparent once it dies)
    val all = root.descendants().iterator().asScala.toSeq :+ root
    all.foreach(_.destroyForcibly())
    all.foreach(p => Try(p.onExit().get(10, TimeUnit.SECONDS)))
  }
}
