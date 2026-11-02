package socle.layout

import socle._

import socle.schema._
import socle.parse._
import socle.ir._
import socle.layout._
import socle.cli._

import utest._
import upickle.default._

import java.util.concurrent.{Callable, Executors, TimeUnit}
import scala.jdk.CollectionConverters._

/** Regression test for issue #55: ELK layout must produce identical output
  * regardless of how many threads call Layout.layout concurrently.
  *
  * Strategy: submit 32 concurrent calls on a thread pool all using the same
  * input (topMod / design from the LayoutTest fixture), collect all resulting
  * JSON strings, and assert they are all equal to the single-threaded result.
  *
  * Without the elkLock.synchronized fix the ELK LayoutMetaDataService
  * singleton can return slightly different node coordinates across threads,
  * causing this test to fail with mismatched JSON strings.
  */
object LayoutDeterminismTest extends TestSuite {

  // Reuse the same synthetic Design as LayoutTest (cpu -> mem with AXI4 wire).
  private val cpuMod = ModuleDef("Cpu", "Cpu", "", SourceRef.unknown,
    Seq(PortDef("io", "master", "AXI4", "", SourceRef.unknown)), Nil, Nil, Metrics(0,0,0))
  private val memMod = ModuleDef("Mem", "Mem", "", SourceRef.unknown,
    Seq(PortDef("io", "slave", "AXI4", "", SourceRef.unknown)), Nil, Nil, Metrics(0,0,0))
  private val topMod = ModuleDef("Top", "Top", "", SourceRef.unknown,
    ports    = Seq(PortDef("done", "out", "UInt<1>", "", SourceRef.unknown)),
    children = Seq(InstanceDef("cpu", "Cpu", "", SourceRef.unknown),
                   InstanceDef("mem", "Mem", "", SourceRef.unknown)),
    wires    = Seq(
      WireDef("mem.io", "cpu.io", "AXI4", "31x ground connects", SourceRef.unknown),
      WireDef("mem.io", "done",   "UInt<1>", "",                 SourceRef.unknown),
    ),
    metrics  = Metrics(0, 0, 0))
  private val design = Design("Top",
    Map("AXI4" -> BundleDef("AXI4", "Bundle")),
    Seq(topMod, cpuMod, memMod))

  // Number of concurrent threads. Large enough to expose races without being
  // so large it makes the test slow on a CI single-core runner.
  private val Concurrency = 32

  val tests = Tests {
    test("layout output is identical across concurrent invocations") {
      val pool = Executors.newFixedThreadPool(Concurrency)
      try {
        // Warm up the singleton (registers LayeredMetaDataProvider) before
        // the concurrent wave so all threads compete on the actual layout phase.
        val warmup: String = write(Layout.layout(topMod, design))

        val tasks: Seq[Callable[String]] =
          Seq.fill(Concurrency)(new Callable[String] {
            def call(): String = write(Layout.layout(topMod, design))
          })

        val futures = pool.invokeAll(tasks.asJava)

        val results: Seq[String] =
          (0 until Concurrency).map { i =>
            futures.get(i).get(30, TimeUnit.SECONDS)
          }

        // All concurrent results must match the warm-up result.
        results.foreach { r =>
          assert(r == warmup)
        }
      } finally {
        pool.shutdown()
        pool.awaitTermination(60, TimeUnit.SECONDS)
      }
    }
  }
}
