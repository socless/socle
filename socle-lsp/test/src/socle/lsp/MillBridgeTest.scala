package socle.lsp

import utest._

/** Drives a real Mill on a throwaway project under the system temp dir (never
  * this repo: the outer `./mill socle-lsp.test` holds its lock). One project
  * and one bridge are shared by every case to keep Mill invocations few.
  */
object MillBridgeTest extends TestSuite {
  private val repoRoot = os.Path(sys.props("socle.root"))

  private val buildMill =
    """//| mill-version: 1.1.6
      |import mill.*, scalalib.*
      |
      |object lib extends ScalaModule {
      |  def scalaVersion = "2.13.16"
      |  object test extends ScalaTests with TestModule.Utest {
      |    def mvnDeps = Seq(mvn"com.lihaoyi::utest:0.8.5")
      |    def forkArgs = Seq("-Dfixture.config=abc")
      |    def forkEnv = Map("FIXTURE_ENV" -> "1")
      |  }
      |}
      |""".stripMargin

  private val proj: os.Path = {
    val dir = os.temp.dir(prefix = "socle-millbridge-")
    os.copy(repoRoot / "mill", dir / "mill")
    os.write(dir / "build.mill", buildMill)
    os.write(dir / "lib" / "src" / "Lib.scala", "object Lib { def one = 1 }\n", createFolders = true)
    os.write(dir / "lib" / "test" / "src" / "LibTest.scala",
      """import utest._
        |object LibTest extends TestSuite { val tests = Tests { test("one") { assert(Lib.one == 1) } } }
        |""".stripMargin, createFolders = true)
    dir
  }
  private lazy val bridge = MillBridge.default(proj)
  private def testFile = proj / "lib" / "test" / "src" / "LibTest.scala"

  override def utestAfterAll(): Unit = os.remove.all(proj)

  private def timed[T](label: String)(body: => T): T = {
    val t0 = System.nanoTime()
    try body
    finally println(f"[MillBridgeTest] $label: ${(System.nanoTime() - t0) / 1e9}%.1fs")
  }

  /** An executable shell script standing in for `mill`. */
  private def fakeMill(dir: os.Path, body: String): Seq[String] = {
    val f = dir / "fake-mill.sh"
    os.write.over(f, "#!/bin/sh\n" + body, perms = os.PermSet.fromString("rwx------"))
    Seq(f.toString)
  }

  /** A fake `mill` for a project whose only test module is `t` with sources
    * in `src`: `resolve`, `show t.sources` and `show t.runClasspath` answer as
    * Mill 1.1.6 does; the combined fork query prints `fork.json` (exit 0) or,
    * when it is empty, fails with Mill's "Cannot resolve" line. */
  private def fakeTestMill(dir: os.Path, forkQuery: Option[String]): Seq[String] = {
    os.write.over(dir / "build.mill", "// fake\n")
    os.write.over(dir / "fork.json", forkQuery.getOrElse[String](""))
    fakeMill(dir,
      s"""case "$$*" in
         |  *"resolve __.test.sources") echo t.sources ;;
         |  *"show t.sources") echo '["ref:v0:00000000:$dir/src"]' ;;
         |  *"show t.runClasspath") echo '["qref:v1:0000abcd:$dir/a.jar"]' ;;
         |  *"show t.{"*)
         |    if [ -s $dir/fork.json ]; then cat $dir/fork.json
         |    else echo "[error] show Cannot resolve t.testSandboxWorkingDir. Try mill resolve t._ to see what is available." >&2; exit 1
         |    fi ;;
         |  *) echo "unexpected: $$*" >&2; exit 2 ;;
         |esac
         |""".stripMargin)
  }

  val tests = Tests {
    test("no build.mill: no Mill is spawned") {
      val dir = os.temp.dir(prefix = "socle-nobuild-")
      try {
        val file   = dir / "src" / "ASpec.scala"
        val marker = dir / "spawned"
        assert(MillBridge.findRoot(file).isEmpty) // precondition: nothing above the temp dir
        val b = new MillBridge(dir, fakeMill(dir, s"touch $marker\n"))
        assert(b.testClasspath(file) == Left(s"no build.mill found for $file"))
        assert(!os.exists(marker))
      } finally os.remove.all(dir)
    }
    test("no test module is a clear message") {
      val dir = os.temp.dir(prefix = "socle-notest-")
      try {
        os.write(dir / "build.mill", "// fake\n")
        // the exact line real Mill 1.1.6 prints for a build without a `test` module
        val b = new MillBridge(dir, fakeMill(dir,
          "echo '[error] resolve Cannot resolve __.test.sources. Try `mill resolve _`, `mill resolve __.sources` " +
            "to see what is available, or `mill __.sources` to run all `sources` tasks' >&2\nexit 1\n"))
        assert(b.testClasspath(dir / "src" / "ASpec.scala") == Left(s"no Mill test module found in $dir"))
      } finally os.remove.all(dir)
    }
    test("a hung Mill times out and its process tree is killed") {
      val dir = os.temp.dir(prefix = "socle-millhang-")
      try {
        os.write(dir / "build.mill", "// fake\n")
        val pids = dir / "pids"
        val b = new MillBridge(dir, fakeMill(dir, s"sleep 60 &\necho $$! > $pids\necho $$$$ >> $pids\nwait\n"),
          timeoutMs = 1000)
        val t0 = System.nanoTime()
        val r  = b.testClasspath(dir / "src" / "ASpec.scala")
        assert(r == Left("Mill did not finish in 1 s"))
        assert((System.nanoTime() - t0) / 1e9 < 20)
        val ids = os.read.lines(pids).map(_.trim.toLong)
        assert(ids.size == 2)
        assert(ids.forall(id => !ProcessHandle.of(id).map[Boolean](_.isAlive).orElse(false)))
      } finally os.remove.all(dir)
    }
    test("findRoot") {
      assert(MillBridge.findRoot(testFile) == Some(proj))
      assert(MillBridge.findRoot(os.root / "nonexistent-dir" / "x.scala") == None)
    }
    test("module for a test file") {
      timed("module discovery") {
        assert(bridge.testModuleFor(testFile) == Some("lib.test"))
      }
      assert(bridge.testModuleFor(proj / "lib" / "src" / "Lib.scala") == None)
      assert(bridge.testModuleFor(os.root / "nonexistent-socle" / "Elsewhere.scala") == None)
    }
    test("classpath") {
      val cp = timed("classpath") { bridge.testClasspath(testFile) }
      val paths = cp.getOrElse(throw new java.lang.AssertionError(s"expected Right, got $cp"))
      assert(paths.contains(proj / "out" / "lib" / "test" / "compile.dest" / "classes"))
      assert(paths.exists(_.last.startsWith("utest_2.13-0.8.5")))
    }
    test("fork settings") {
      val f = timed("fork settings") { bridge.testFork(testFile) }
      val fork = f.getOrElse(throw new java.lang.AssertionError(s"expected Right, got $f"))
      assert(fork.classpath.contains(proj / "out" / "lib" / "test" / "compile.dest" / "classes"))
      assert(fork.jvmArgs == Seq("-Dfixture.config=abc"))
      // Mill's allForkEnv: the user's forkEnv plus what Mill sets for tests
      assert(fork.env.get("FIXTURE_ENV") == Some("1"))
      assert(fork.env.get("MILL_WORKSPACE_ROOT") == Some(proj.toString))
      assert(fork.env.get("MILL_TEST_RESOURCE_DIR") == Some((proj / "lib" / "test" / "resources").toString))
      assert(fork.workingDir.isEmpty) // testSandboxWorkingDir (default true): a fresh dir, like Mill's sandbox
    }
    test("fork settings: no sandbox runs in forkWorkingDir, allForkEnv as is") {
      val dir = os.temp.dir(prefix = "socle-fakefork-")
      try {
        val json =
          s"""{"t.runClasspath": ["qref:v1:0000abcd:$dir/a.jar"], "t.forkArgs": ["-Xss8m"],
             | "t.allForkEnv": {"MILL_WORKSPACE_ROOT": "/ws", "X": "1"}, "t.forkWorkingDir": "$dir/wd",
             | "t.testSandboxWorkingDir": false}""".stripMargin
        val b = new MillBridge(dir, fakeTestMill(dir, Some(json)))
        assert(b.testFork(dir / "src" / "ASpec.scala") ==
          Right(TestFork(Seq(dir / "a.jar"), Seq("-Xss8m"), Map("MILL_WORKSPACE_ROOT" -> "/ws", "X" -> "1"), Some(dir / "wd"))))
      } finally os.remove.all(dir)
    }
    test("fork settings unavailable: classpath only, logged") {
      val dir = os.temp.dir(prefix = "socle-fakefork-")
      try {
        val logs = new java.util.concurrent.ConcurrentLinkedQueue[String]
        val b = new MillBridge(dir, fakeTestMill(dir, None), log = m => { logs.add(m); () })
        assert(b.testFork(dir / "src" / "ASpec.scala") ==
          Right(TestFork(Seq(dir / "a.jar"), env = Map("MILL_WORKSPACE_ROOT" -> dir.toString))))
        import scala.jdk.CollectionConverters._
        assert(logs.asScala.exists(l => l.contains("classpath only") && l.contains("Cannot resolve")))
      } finally os.remove.all(dir)
    }
    test("not a test module") {
      val r = bridge.testClasspath(proj / "lib" / "src" / "Lib.scala")
      assert(r == Left(s"not in a Mill test module: ${proj / "lib" / "src" / "Lib.scala"}"))
    }
    test("compile error") {
      val broken = proj / "lib" / "test" / "src" / "Broken.scala"
      os.write(broken, "object Broken { val x: Int = \"nope\" }\n")
      try {
        val r = timed("classpath with compile error") { bridge.testClasspath(testFile) }
        r match {
          case Left(msg) => assert(msg.contains("Broken.scala"))
          case Right(cp) => throw new java.lang.AssertionError(s"expected Left, got $cp")
        }
        // the fork query reports it too (a compile error is not a fallback case)
        timed("fork settings with compile error") { bridge.testFork(testFile) } match {
          case Left(msg)   => assert(msg.contains("Broken.scala"))
          case Right(fork) => throw new java.lang.AssertionError(s"expected Left, got $fork")
        }
      } finally os.remove(broken)
    }
    test("broken build is reported, then rediscovered") {
      os.write.append(proj / "build.mill", "object oops {\n")
      Thread.sleep(1100) // past the bridge's one-second stamp throttle
      try {
        val r = timed("classpath with broken build") { bridge.testClasspath(testFile) }
        r match {
          case Left(msg) =>
            assert(msg.contains("build.mill"), !msg.contains("not in a Mill test module"))
          case Right(cp) => throw new java.lang.AssertionError(s"expected Left, got $cp")
        }
      } finally os.write.over(proj / "build.mill", buildMill)
      Thread.sleep(1100)
      timed("rediscovery") { assert(bridge.testModuleFor(testFile) == Some("lib.test")) }
    }
  }
}
