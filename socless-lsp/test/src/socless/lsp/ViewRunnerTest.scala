package socless.lsp

import utest._

/** Forks real ChiselSim test JVMs with the embedded probe agent in view mode
  * (the plan-1 run contract), on the example and probe-fixture classpaths. */
object ViewRunnerTest extends TestSuite {
  private def prop(k: String) = sys.props.getOrElse(k, sys.error(s"missing -D$k (set by build.mill forkArgs)"))
  private def cp(k: String)   = prop(k).split(java.io.File.pathSeparator).toSeq.filter(_.nonEmpty).map(os.Path(_))

  private val root   = os.Path(prop("socless.root"))
  private val javaBin = (os.Path(sys.props("java.home")) / "bin" / "java").toString
  private lazy val runner = new ViewRunner(ViewRunner.extractProbeJar(), root, javaBin)

  val tests = Tests {
    test("default java is the server's own") {
      assert(ViewRunner.defaultJava == javaBin)
      assert(os.isFile(os.Path(ViewRunner.defaultJava)))
    }
    test("probe jar goes to a content-hashed path, reused across starts") {
      val dir   = os.temp.dir(prefix = "socless-probe-extract-")
      val bytes = "fake probe jar".getBytes("UTF-8")
      def open() = new java.io.ByteArrayInputStream(bytes)
      try {
        val first = ViewRunner.extractTo(dir, () => open())
        assert(first.last.matches("socless-probe-[0-9a-f]{16}\\.jar"))
        assert(os.read.bytes(first).sameElements(bytes))
        os.mtime.set(first, 1000000000000L)
        val second = ViewRunner.extractTo(dir, () => open())
        assert(second == first, os.mtime(second) == 1000000000000L) // not rewritten
        assert(os.list(dir) == Seq(first))
        val other = ViewRunner.extractTo(dir, () => new java.io.ByteArrayInputStream("v2".getBytes("UTF-8")))
        assert(other != first)
        val missing = scala.util.Try(ViewRunner.extractTo(dir, () => null))
        assert(missing.failed.get.getMessage.contains("socless-probe.jar resource missing"))
      } finally os.remove.all(dir)
    }
    test("probe cache dir follows XDG, falling back to ~/.cache") {
      val home = os.Path("/home/u")
      assert(ViewRunner.cacheDir(Some("/xdg/cache"), home) == os.Path("/xdg/cache/socless"))
      assert(ViewRunner.cacheDir(None, home) == home / ".cache" / "socless")
      assert(ViewRunner.cacheDir(Some(""), home) == home / ".cache" / "socless")
      assert(ViewRunner.cacheDir(Some("relative/dir"), home) == home / ".cache" / "socless") // spec: ignore non-absolute
    }
    test("probe cache dir is private to the user") {
      import java.nio.file.attribute.PosixFilePermissions.{fromString, toString => perms}
      val parent = os.temp.dir(prefix = "socless-probe-perm-")
      val bytes  = "probe".getBytes("UTF-8")
      try {
        val fresh = parent / "fresh"
        ViewRunner.extractTo(fresh, () => new java.io.ByteArrayInputStream(bytes))
        assert(perms(java.nio.file.Files.getPosixFilePermissions(fresh.toNIO)) == "rwx------")
        val loose = parent / "loose"
        os.makeDir(loose)
        java.nio.file.Files.setPosixFilePermissions(loose.toNIO, fromString("rwxrwxrwx"))
        ViewRunner.extractTo(loose, () => new java.io.ByteArrayInputStream(bytes))
        assert(perms(java.nio.file.Files.getPosixFilePermissions(loose.toNIO)) == "rwx------")
      } finally os.remove.all(parent)
    }
    test("a tampered jar of the same size is replaced, not reused") {
      val dir   = os.temp.dir(prefix = "socless-probe-tamper-")
      val bytes = "genuine probe!".getBytes("UTF-8")
      try {
        val target = ViewRunner.extractTo(dir, () => new java.io.ByteArrayInputStream(bytes))
        os.write.over(target, "planted  probe".getBytes("UTF-8")) // same length
        val again = ViewRunner.extractTo(dir, () => new java.io.ByteArrayInputStream(bytes))
        assert(again == target)
        assert(os.read.bytes(again).sameElements(bytes))
      } finally os.remove.all(dir)
    }
    test("view gcd") {
      val t0  = System.nanoTime()
      val res = runner.run(TestFork(cp("example.cp")), "gcd.GcdSpec", "GCD computes gcd(48, 18)")
      println(s"[ViewRunnerTest] view gcd took ${(System.nanoTime() - t0) / 1000000} ms")
      res match {
        case Viewed(d) =>
          assert(d.top == "GCD")
          assert(d.showUnwiredPorts)
          assert(d.layouts(d.layoutOf.getOrElse("GCD", "GCD")).nodes.nonEmpty)
          assert(!d.warnings.exists(_.kind == "probe-problem"))
        case other => sys.error(s"expected Viewed, got $other")
      }
    }
    test("elaboration error") {
      runner.run(TestFork(cp("fixtures.cp")), "probefix.BrokenSpec", "broken module") match {
        case Failed(status, msg) =>
          assert(status == "elaboration-error")
          assert(msg.contains("broken on purpose"))
        case other => sys.error(s"expected Failed, got $other")
      }
    }
    test("fork settings: config from forkArgs and MILL_WORKSPACE_ROOT") {
      val ws = os.temp.dir(prefix = "socless-fork-ws-")
      try {
        os.write(ws / "config" / "fixture.json", "{}", createFolders = true)
        val fork = TestFork(cp("fixtures.cp"), Seq("-Dfixture.config=abc"), Map("MILL_WORKSPACE_ROOT" -> ws.toString))
        runner.run(fork, "probefix.ConfigSpec", "configured module") match {
          case Viewed(d) => assert(d.top == "Configured")
          case other     => sys.error(s"expected Viewed, got $other")
        }
        // without the settings the same test cannot elaborate: they matter
        runner.run(TestFork(cp("fixtures.cp")), "probefix.ConfigSpec", "configured module") match {
          case Failed(status, msg) =>
            assert(status == "elaboration-error")
            assert(msg.contains("missing -Dfixture.config"))
          case other => sys.error(s"expected Failed, got $other")
        }
        runner.run(TestFork(cp("fixtures.cp"), Seq("-Dfixture.config=abc")), "probefix.ConfigSpec",
          "configured module") match {
          case Failed(status, msg) =>
            assert(status == "elaboration-error")
            assert(msg.contains("missing config file"))
          case other => sys.error(s"expected Failed, got $other")
        }
      } finally os.remove.all(ws)
    }
    test("fork settings: user jvmArgs cannot override the probe's") {
      val ws = os.temp.dir(prefix = "socless-fork-ws-")
      try {
        val fork = TestFork(cp("example.cp"),
          Seq("-Dsocless.probe.mode=off", s"-Dsocless.probe.out=${ws / "elsewhere.json"}"))
        runner.run(fork, "gcd.GcdSpec", "GCD computes gcd(48, 18)") match {
          case Viewed(d) => assert(d.top == "GCD")
          case other     => sys.error(s"expected Viewed, got $other")
        }
        assert(!os.exists(ws / "elsewhere.json"))
      } finally os.remove.all(ws)
    }
    test("fork settings: working dir") {
      // the run's cwd is the fork's working dir when given: with an empty
      // MILL_WORKSPACE_ROOT (this JVM inherits Mill's) the fixture reads config/ from the cwd
      val ws = os.temp.dir(prefix = "socless-fork-cwd-")
      try {
        os.write(ws / "config" / "fixture.json", "{}", createFolders = true)
        val fork = TestFork(cp("fixtures.cp"), Seq("-Dfixture.config=abc"), Map("MILL_WORKSPACE_ROOT" -> ""), Some(ws))
        runner.run(fork, "probefix.ConfigSpec", "configured module") match {
          case Viewed(d) => assert(d.top == "Configured")
          case other     => sys.error(s"expected Viewed, got $other")
        }
      } finally os.remove.all(ws)
    }
    test("no design") {
      val res = runner.run(TestFork(cp("fixtures.cp")), "probefix.NoSimulateSpec", "plain arithmetic")
      assert(res.isInstanceOf[Failed])
      assert(res.asInstanceOf[Failed].status == "no-design")
    }
    test("missing output") {
      val dir = os.temp.dir()
      try {
        val broken = new ViewRunner(dir / "no-such-probe.jar", root, javaBin)
        val res    = broken.run(TestFork(cp("fixtures.cp")), "probefix.NoSimulateSpec", "plain arithmetic")
        assert(res.isInstanceOf[Failed])
        val Failed(status, msg) = res
        assert(status == "probe-failure")
        assert(msg.nonEmpty)
      } finally os.remove.all(dir)
    }
    test("timeout kills the process tree") {
      // A "java" that hangs, as a stuck test JVM would, with a child that hangs
      // too; it records both pids.
      val dir  = os.temp.dir(prefix = "hang-")
      val pids = dir / "pids"
      val hang = dir / "java.sh"
      os.write(hang, s"#!/bin/sh\nsleep 60 &\necho $$! > $pids\necho $$$$ >> $pids\nwait\n",
        perms = os.PermSet.fromString("rwx------"))
      try {
        val quick  = new ViewRunner(ViewRunner.extractProbeJar(), root, hang.toString, timeoutMs = 1000)
        val stdout = new java.io.ByteArrayOutputStream
        val ps     = new java.io.PrintStream(stdout, true)
        val saved  = System.out
        System.setOut(ps)
        val res =
          try Console.withOut(ps)(quick.run(TestFork(cp("example.cp")), "gcd.GcdSpec", "GCD computes gcd(48, 18)"))
          finally System.setOut(saved)
        assert(res == Failed("probe-failure", "timed out after 1 s"))
        val ids = os.read.lines(pids).map(_.trim.toLong)
        assert(ids.size == 2)
        val alive = ids.filter(id => ProcessHandle.of(id).map[Boolean](_.isAlive).orElse(false))
        assert(alive.isEmpty)
        assert(stdout.size == 0)
      } finally os.remove.all(dir)
    }
  }
}
