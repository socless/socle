package socless.lsp

import com.google.gson.JsonObject
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import utest._

/** The Simulate runner: fake `java` and `surfer` scripts for the run contract,
  * and one real ChiselSim run with Verilator (skipped when it is not on PATH). */
object SimRunnerTest extends TestSuite {
  private def prop(k: String) = sys.props.getOrElse(k, sys.error(s"missing -D$k (set by build.mill forkArgs)"))
  private def cp(k: String)   = prop(k).split(java.io.File.pathSeparator).toSeq.filter(_.nonEmpty).map(os.Path(_))
  private val javaBin = (os.Path(sys.props("java.home")) / "bin" / "java").toString

  private val suite = "gcd.GcdSpec"
  private val name  = "GCD computes gcd(48, 18)"
  private val req   = SimRequest(os.root / "nowhere" / "GcdSpec.scala", suite, name)

  private def script(p: os.Path, body: String): os.Path = {
    os.write.over(p, "#!/bin/sh\n" + body, perms = os.PermSet.fromString("rwx------"))
    p
  }

  /** A temp workspace root and a bin dir with a fake surfer recording its argv. */
  private final class Env {
    val root       = os.temp.dir(prefix = "socless-sim-root-")
    val bin        = os.temp.dir(prefix = "socless-sim-bin-")
    val argv       = bin / "argv"
    val pwd        = bin / "pwd"
    val surferArgv = bin / "surfer-argv"
    val surfer     = script(bin / "surfer.sh", s"""printf '%s\\n' "$$@" > $surferArgv\n""")
    /** A java recording argv, cwd and $$FIXTURE_ENV, writing a trace unless `trace` is false, exiting `exit`. */
    def java(exit: Int, trace: Boolean = true): os.Path = script(bin / s"java-$exit-$trace.sh",
      s"""printf '%s\\n' "$$@" > $argv
         |pwd > $pwd
         |echo "$$FIXTURE_ENV" >> $pwd
         |${if (trace) "mkdir -p build/chiselsim/x && echo '$timescale 1ns $end' > build/chiselsim/x/trace.vcd" else ""}
         |exit $exit
         |""".stripMargin)
    def settings(flag: String = "emitVcd") = SimSettings(flag, surfer.toString)
    def close(): Unit = { os.remove.all(root); os.remove.all(bin) }
  }

  private val fork = TestFork(Seq(os.root / "a.jar", os.root / "b.jar"), Seq("-Duser.flag=1"), Map("FIXTURE_ENV" -> "from-fork"))

  private def eventually[T](what: String, timeoutMs: Long = 10000)(f: => Option[T]): T = {
    val end = System.currentTimeMillis() + timeoutMs
    var r   = Option.empty[T]
    while (r.isEmpty && System.currentTimeMillis() < end) {
      r = try f catch { case _: Exception => None }
      if (r.isEmpty) Thread.sleep(50)
    }
    r.getOrElse(sys.error(s"timed out waiting for $what"))
  }

  private def onPath(exe: String): Boolean =
    sys.env.getOrElse("PATH", "").split(java.io.File.pathSeparator).exists(d => d.nonEmpty && os.isFile(os.Path(d, os.pwd) / exe))

  val tests = Tests {
    test("passing run: waves found and opened in surfer") {
      val e = new Env
      try {
        val runner = new SimRunner(e.root, e.java(0).toString, log = _ => ())
        val dir    = runner.dirFor(suite, name)
        val out    = runner.run(req, e.settings(), Right(fork))
        val vcd    = dir / "build" / "chiselsim" / "x" / "trace.vcd"
        assert(out.passed, out.waves == Some(vcd))
        assert(out.message == s"Simulation passed: $suite $name. Waves: $vcd")
        val args = os.read.lines(e.argv)
        // user jvm args first, then ours (last -D wins), then the Runner with the flag as a config-map entry
        assert(args.take(2) == Seq("-Duser.flag=1", s"-Dchisel.project.root=$dir"))
        assert(args.drop(2) == Seq("-cp", "/a.jar:/b.jar", "org.scalatest.tools.Runner", "-oW", "-DemitVcd=1",
          "-s", suite, "-t", name))
        assert(os.read.lines(e.pwd) == Seq(dir.toString, "from-fork")) // cwd is the kept dir, env from the fork
        assert(dir.startsWith(e.root / "out" / "socless" / "sim"))
        eventually("surfer argv")(Some(e.surferArgv).filter(os.exists).map(os.read.lines(_)).filter(_.nonEmpty)) ==>
          Seq(vcd.toString)
        assert(os.exists(dir / "surfer.log"), os.exists(dir / "sim.log"))
      } finally e.close()
    }
    test("failing run still reports and opens its waves") {
      val e = new Env
      try {
        val runner = new SimRunner(e.root, e.java(1).toString, log = _ => ())
        val out    = runner.run(req, e.settings(), Right(fork))
        val vcd    = runner.dirFor(suite, name) / "build" / "chiselsim" / "x" / "trace.vcd"
        assert(!out.passed, out.message == s"Simulation failed: $suite $name. Waves: $vcd")
        eventually("surfer argv")(Some(e.surferArgv).filter(os.exists).map(os.read.lines(_)).filter(_.nonEmpty)) ==>
          Seq(vcd.toString)
      } finally e.close()
    }
    test("no trace: the message says how to enable waves, surfer is not started") {
      val e = new Env
      try {
        val runner = new SimRunner(e.root, e.java(0, trace = false).toString, log = _ => ())
        val out    = runner.run(req, e.settings("emitFst"), Right(fork))
        assert(out.passed, out.waves.isEmpty)
        assert(out.message == s"Simulation passed: $suite $name. No waveform found (is waves support enabled? flag -DemitFst=1)")
        Thread.sleep(200)
        assert(!os.exists(e.surferArgv))
      } finally e.close()
    }
    test("the newest trace wins, .fst too") {
      val e = new Env
      try {
        val java = script(e.bin / "java-fst.sh",
          """mkdir -p a b && echo old > a/old.vcd && touch -d '2000-01-01' a/old.vcd && echo new > b/trace.fst
            |""".stripMargin)
        val runner = new SimRunner(e.root, java.toString, log = _ => ())
        val out    = runner.run(req, e.settings(), Right(fork))
        assert(out.waves == Some(runner.dirFor(suite, name) / "b" / "trace.fst"))
      } finally e.close()
    }
    test("surfer missing: the waves path is still reported") {
      val e = new Env
      try {
        val runner = new SimRunner(e.root, e.java(0).toString, log = _ => ())
        val out    = runner.run(req, SimSettings("emitVcd", (e.bin / "no-such-surfer").toString), Right(fork))
        val vcd    = runner.dirFor(suite, name) / "build" / "chiselsim" / "x" / "trace.vcd"
        assert(out.passed, out.waves == Some(vcd))
        assert(out.message.startsWith(s"Simulation passed: $suite $name. Waves: $vcd (cannot start "))
      } finally e.close()
    }
    test("a test name matching no test is a failure, not a pass") {
      val e = new Env
      try {
        // ScalaTest 3.2.19's summary for a -t matching nothing (verified on GcdSpec), exit 0
        val java = script(e.bin / "java-none.sh",
          """echo 'Run starting. Expected test count is: 0'
            |echo 'Total number of tests run: 0'
            |echo 'No tests were executed.'
            |""".stripMargin)
        val runner = new SimRunner(e.root, java.toString, log = _ => ())
        val out    = runner.run(req, e.settings(), Right(fork))
        assert(!out.passed, out.waves.isEmpty)
        assert(out.message == s"Simulation failed: $suite $name. No test named \"$name\" ran in $suite.")
      } finally e.close()
    }
    test("real ScalaTest run of a test name matching nothing is a failure") {
      val e = new Env
      try {
        val runner = new SimRunner(e.root, javaBin, log = _ => ())
        val out = runner.run(req.copy(test = "no such test"), e.settings(), Right(TestFork(cp("example.cp"))))
        assert(!out.passed, out.message == s"Simulation failed: $suite no such test. No test named \"no such test\" ran in $suite.")
      } finally e.close()
    }
    test("classpath failure is reported, java never runs") {
      val e = new Env
      try {
        val runner = new SimRunner(e.root, e.java(0).toString, log = _ => ())
        val out    = runner.run(req, e.settings(), Left("no Mill test module"))
        assert(!out.passed, out.waves.isEmpty, out.message == s"Simulation not run: $suite $name. no Mill test module")
        assert(!os.exists(e.argv))
      } finally e.close()
    }
    test("command line and log path go to the log") {
      val e = new Env
      try {
        val logged = new java.util.concurrent.ConcurrentLinkedQueue[String]
        val runner = new SimRunner(e.root, e.java(0).toString, log = m => { logged.add(m); () })
        runner.run(req, e.settings(), Right(fork))
        val all = logged.toArray.mkString("\n")
        assert(all.contains(s"${e.java(0)} -Duser.flag=1"), all.contains("org.scalatest.tools.Runner"))
        assert(all.contains((runner.dirFor(suite, name) / "sim.log").toString))
      } finally e.close()
    }
    test("one simulation at a time: a second one is refused, the first goes on") {
      val e = new Env
      try {
        val release = e.bin / "release"
        val java = script(e.bin / "java-wait.sh", s"while [ ! -e $release ]; do sleep 0.05; done\n")
        val runner  = new SimRunner(e.root, java.toString, log = _ => ())
        val started = new CountDownLatch(1)
        val outcome = new AtomicReference[SimOutcome]
        val done    = new CountDownLatch(1)
        val first = runner.start(req, e.settings(), () => Right(fork), () => started.countDown(),
          o => { outcome.set(o); done.countDown() })
        assert(first.isEmpty, started.await(1, TimeUnit.SECONDS))
        val other  = req.copy(test = "another")
        val second = runner.start(other, e.settings(), () => Right(fork), () => sys.error("must not start"), _ => ())
        assert(second == Some(req))
        os.write(release, "")
        assert(done.await(10, TimeUnit.SECONDS), outcome.get.passed)
        // done: the next one is accepted
        val again = new CountDownLatch(1)
        assert(runner.start(other, e.settings(), () => Right(fork), () => (), _ => again.countDown()).isEmpty)
        assert(again.await(10, TimeUnit.SECONDS))
      } finally e.close()
    }
    test("timeout kills the process tree") {
      val e = new Env
      try {
        val pids = e.bin / "pids"
        val hang = script(e.bin / "java-hang.sh", s"sleep 60 &\necho $$! > $pids\necho $$$$ >> $pids\nwait\n")
        val runner = new SimRunner(e.root, hang.toString, timeoutMs = 1000, log = _ => ())
        val out    = runner.run(req, e.settings(), Right(fork))
        assert(!out.passed)
        assert(out.message == s"Simulation failed: $suite $name. Timed out after 1 s. " +
          "No waveform found (is waves support enabled? flag -DemitVcd=1)")
        val ids = os.read.lines(pids).map(_.trim.toLong)
        assert(ids.size == 2)
        assert(ids.forall(id => !ProcessHandle.of(id).map[Boolean](_.isAlive).orElse(false)))
      } finally e.close()
    }
    test("output dir is emptied at the start of each run") {
      val e = new Env
      try {
        val runner = new SimRunner(e.root, e.java(0, trace = false).toString, log = _ => ())
        val dir    = runner.dirFor(suite, name)
        os.write(dir / "stale.vcd", "old", createFolders = true)
        val out = runner.run(req, e.settings(), Right(fork))
        assert(!os.exists(dir / "stale.vcd"), out.waves.isEmpty)
        assert(os.exists(dir / "sim.log"))
      } finally e.close()
    }
    test("dir names are safe, bounded and collision-safe") {
      val root = os.Path("/ws")
      val base = root / "out" / "socless" / "sim"
      val d = SimRunner.dirFor(root, suite, name)
      assert(d.startsWith(base), d.segmentCount == base.segmentCount + 2)
      assert(d / os.up == base / "gcd.GcdSpec") // an already safe name is kept as is
      assert(d.last.matches("GCD-computes-gcd-48--18--[0-9a-f]{8}"))
      for (n <- Seq("..", ".", "../../etc", "a/b", "x" * 500, "", " ", "été → ok")) {
        val s = SimRunner.segment(n)
        assert(s.matches("[-_.A-Za-z0-9]+"), s != "." && s != "..", !s.startsWith("."), s.length <= 80)
        assert(SimRunner.dirFor(root, n, n).startsWith(base), SimRunner.dirFor(root, n, n).segmentCount == base.segmentCount + 2)
      }
      // names that sanitize the same stay apart
      assert(SimRunner.segment("a b") != SimRunner.segment("a/b"))
      assert(SimRunner.segment("x" * 200 + "1") != SimRunner.segment("x" * 200 + "2"))
    }
    test("settings") {
      def obj(kv: (String, String)*) = { val o = new JsonObject; kv.foreach { case (k, v) => o.addProperty(k, v) }; o }
      assert(SimSettings() == SimSettings("emitVcd", "surfer"))
      assert(SimSettings.fromInit(null) == SimSettings())
      assert(SimSettings.fromInit(obj("panel" -> "client")) == SimSettings())
      assert(SimSettings.fromInit(obj("waveFlag" -> "emitFst", "surferCommand" -> "/opt/surfer")) ==
        SimSettings("emitFst", "/opt/surfer"))
      assert(SimSettings.fromInit(obj("waveFlag" -> " ", "surferCommand" -> "")) == SimSettings())
      assert(SimSettings.fromInit(obj("waveFlag" -> "emit Fst=2")) == SimSettings()) // not a flag name
      assert(SimSettings.invalidFlag(obj("waveFlag" -> "emit Fst=2")) == Some("emit Fst=2"))
      assert(SimSettings.invalidFlag(obj("waveFlag" -> "emit-fst.v_2")).isEmpty)
      assert(SimSettings.invalidFlag(obj("waveFlag" -> " ")).isEmpty, SimSettings.invalidFlag(null).isEmpty)
      assert(SimSettings.fromInit(obj("waveFlag" -> "emit-fst")) == SimSettings("emit-fst", "surfer"))
      val nested = new JsonObject
      nested.add("socless", obj("waveFlag" -> "emitFst"))
      assert(SimSettings.fromConfig(nested) == Some(SimSettings("emitFst", "surfer")))
      assert(SimSettings.fromConfig(new JsonObject).isEmpty)
      assert(SimSettings.fromConfig(null).isEmpty)
      assert(SimSettings.section(nested).map(_.get("waveFlag").getAsString) == Some("emitFst"))
    }
    test("real gcd run with Verilator writes a VCD under out/socless/sim") {
      if (!onPath("verilator")) {
        println("[SimRunnerTest] SKIPPED real gcd run: verilator is not on PATH")
      } else {
        val e = new Env
        try {
          val runner = new SimRunner(e.root, javaBin, log = m => println(s"[SimRunnerTest] $m"))
          val t0     = System.nanoTime()
          val out    = runner.run(req, e.settings("emitVcd"), Right(TestFork(cp("example.cp"))))
          println(s"[SimRunnerTest] real gcd run took ${(System.nanoTime() - t0) / 1000000} ms: ${out.message}")
          assert(out.passed)
          val vcd = out.waves.getOrElse(sys.error(s"no waves: ${out.message}"))
          assert(vcd.ext == "vcd", vcd.startsWith(e.root / "out" / "socless" / "sim"), os.size(vcd) > 0)
          println(s"[SimRunnerTest] trace $vcd: ${os.size(vcd)} bytes")
          eventually("surfer argv")(Some(e.surferArgv).filter(os.exists).map(os.read.lines(_)).filter(_.nonEmpty)) ==>
            Seq(vcd.toString)
        } finally e.close()
      }
    }
  }
}
