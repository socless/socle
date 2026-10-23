package socless.lsp

import scala.util.control.NonFatal

/** One view run at a time, latest wins: a single daemon worker runs `exec`,
  * and a single pending slot holds the next request. Submitting while a run is
  * in progress replaces whatever was pending; the running one finishes and its
  * result is still reported. `exec` or `onResult` throwing never stops the
  * worker: a throwing `exec` is reported as `Failed("probe-failure", msg)`.
  */
final class RunQueue(exec: ViewRequest => RunResult, onResult: (ViewRequest, RunResult) => Unit) {
  private val lock    = new Object
  private var pending = Option.empty[ViewRequest]
  private var closed  = false

  private val worker = new Thread(() => loop(), "socless-view-runner")
  worker.setDaemon(true)
  worker.start()

  def submit(r: ViewRequest): Unit = lock.synchronized {
    if (!closed) { pending = Some(r); lock.notifyAll() }
  }

  /** Drops the pending request and stops the worker after the current run. */
  def close(): Unit = lock.synchronized { closed = true; pending = None; lock.notifyAll() }

  private def next(): Option[ViewRequest] = lock.synchronized {
    while (pending.isEmpty && !closed) lock.wait()
    val r = pending
    pending = None
    r
  }

  private def loop(): Unit = {
    var r = next()
    while (r.isDefined) {
      val req = r.get
      val res =
        try exec(req)
        catch { case NonFatal(e) => Failed("probe-failure", String.valueOf(e.getMessage)) }
      try onResult(req, res)
      catch { case NonFatal(e) => Console.err.println(s"[socless-lsp] view result handler failed: $e") }
      r = next()
    }
  }
}
