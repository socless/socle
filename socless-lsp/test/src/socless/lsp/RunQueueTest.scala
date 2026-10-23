package socless.lsp

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import scala.jdk.CollectionConverters._
import utest._

/** The single-worker, latest-wins queue, with a fake `exec` (no JVM forked). */
object RunQueueTest extends TestSuite {
  private def req(name: String) = ViewRequest(os.root / s"$name.scala", "S", name)
  private val dummy = Failed("no-design", "fake")

  val tests = Tests {
    test("latest wins") {
      val started  = new CountDownLatch(1)
      val release  = new CountDownLatch(1)
      val gotD     = new CountDownLatch(1)
      val executed = new ConcurrentLinkedQueue[String]
      val results  = new ConcurrentLinkedQueue[String]
      val q = new RunQueue(
        r => {
          executed.add(r.test)
          if (r.test == "A") { started.countDown(); release.await(10, TimeUnit.SECONDS) }
          dummy
        },
        (r, _) => { results.add(r.test); if (r.test == "D") gotD.countDown() },
      )
      try {
        q.submit(req("A"))
        assert(started.await(10, TimeUnit.SECONDS))
        q.submit(req("B")); q.submit(req("C")); q.submit(req("D"))
        release.countDown()
        assert(gotD.await(10, TimeUnit.SECONDS))
        Thread.sleep(100) // nothing else may run after D
        assert(executed.asScala.toSeq == Seq("A", "D"))
        assert(results.asScala.toSeq == Seq("A", "D"))
      } finally q.close()
    }
    test("exec throwing keeps the worker alive") {
      val done    = new CountDownLatch(2)
      val results = new ConcurrentLinkedQueue[(String, RunResult)]
      val q = new RunQueue(
        r => if (r.test == "X") throw new RuntimeException("boom") else dummy,
        (r, res) => { results.add(r.test -> res); done.countDown() },
      )
      try {
        q.submit(req("X"))
        // wait for X's result before Y, so Y cannot replace X in the slot
        while (results.isEmpty) Thread.sleep(10)
        q.submit(req("Y"))
        assert(done.await(10, TimeUnit.SECONDS))
        val seen = results.asScala.toSeq
        assert(seen.map(_._1) == Seq("X", "Y"))
        assert(seen(0)._2 == Failed("probe-failure", "boom"))
        assert(seen(1)._2 == dummy)
      } finally q.close()
    }
  }
}
