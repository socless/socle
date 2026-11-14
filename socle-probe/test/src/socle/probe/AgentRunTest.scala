package socle.probe

import socle.capture.{CParam, CapturedModel, WiresFromChirrtl}
import utest._

/** Runs unmodified ChiselSim scalatest tests in a forked JVM with the probe
  * agent attached in view mode, as the tool will. Each run gets a fresh
  * working directory, so `build/chiselsim` checks are isolated. Every run gets
  * a PATH without any `verilator`, so view mode is proven to work on machines
  * (CI, many users) that do not have Verilator installed. */
object AgentRunTest extends TestSuite {

  case class Run(model: CapturedModel, output: String, cwd: os.Path)

  private def prop(k: String) = sys.props.getOrElse(k, sys.error(s"missing -D$k (set by build.mill forkArgs)"))

  /** The current PATH with no `verilator` on it. A directory holding one is
    * replaced by a mirror of symlinks to everything else in it, since it may
    * also hold `which` and the rest of the system (`/usr/bin`). */
  private lazy val pathWithoutVerilator: String = {
    def hasVerilator(dir: os.Path) = {
      val f = (dir / "verilator").toIO
      f.isFile && f.canExecute
    }
    val mirrors = os.temp.dir(prefix = "path-no-verilator-")
    val dirs = sys.env.getOrElse("PATH", "").split(java.io.File.pathSeparator).toSeq
      .filter(d => d.nonEmpty && new java.io.File(d).isAbsolute).map(os.Path(_))
    val kept = dirs.zipWithIndex.map { case (d, i) =>
      if (!os.isDir(d) || !hasVerilator(d)) d
      else {
        val m = mirrors / i.toString
        os.makeDir(m)
        os.list(d).filter(_.last != "verilator").foreach(f => os.symlink(m / f.last, f))
        m
      }
    }
    Predef.assert(!kept.exists(d => os.isDir(d) && hasVerilator(d)))
    kept.mkString(java.io.File.pathSeparator)
  }

  private def viewRun(cpProp: String, suite: String, test: Option[String], view: Boolean = true): Run = {
    val cwd  = os.temp.dir(prefix = "probe-run-")
    val out  = cwd / "captured.json"
    val java = os.Path(sys.props("java.home")) / "bin" / "java"
    val cmd = Seq(
      java.toString,
      s"-javaagent:${prop("probe.jar")}",
    ) ++ (if (view) Seq("-Dsocle.probe.mode=view") else Nil) ++ Seq(
      s"-Dsocle.probe.out=$out",
      "-cp", prop(cpProp),
      "org.scalatest.tools.Runner", "-oW", "-s", suite,
    ) ++ test.toSeq.flatMap(t => Seq("-t", t))
    val res = os.proc(cmd).call(cwd = cwd, env = Map("PATH" -> pathWithoutVerilator), timeout = 120000, check = false, mergeErrIntoOut = true)
    val output = res.out.text()
    Predef.assert(os.exists(out), s"no $out after the run; JVM output:\n$output")
    Run(CapturedModel.fromJson(os.read(out)), output, cwd)
  }

  // The Task 4 contract: no problems, and the captured CHIRRTL is the one of
  // this elaboration (the consumer derives the wiring from it, per module).
  private def assertConsumable(m: CapturedModel): Unit = {
    assert(m.problems.isEmpty)
    assert(m.chirrtl.nonEmpty)
    assert(WiresFromChirrtl.wires(m.chirrtl).keySet == m.modules.map(_.name).toSet)
  }

  val tests = Tests {
    test("the agent loads on Java 8+: no class file newer than 52, outside multi-release dirs") {
      // A test module may pin an older JDK (its `javaHome`), which the view run forks.
      val jar = new java.util.zip.ZipFile(prop("probe.jar"))
      try {
        import scala.jdk.CollectionConverters._
        val newer = jar.entries().asScala.filter { e =>
          e.getName.endsWith(".class") && !e.getName.startsWith("META-INF/versions/") && {
            val in = new java.io.DataInputStream(jar.getInputStream(e))
            try { in.readInt(); in.readUnsignedShort(); in.readUnsignedShort() > 52 } finally in.close()
          }
        }.map(_.getName).toList
        assert(newer.isEmpty)
      } finally jar.close()
    }
    test("gcd view") {
      val r = viewRun("example.cp", "gcd.GcdSpec", Some("GCD computes gcd(48, 18)"))
      assert(r.model.status == "ok")
      assert(r.model.top == "GCD")
      assert(r.model.chiselVersion == "7.16.0")
      assertConsumable(r.model)
      assert(r.output.contains("socle.probe.ViewOnly"))
      // Neither firtool nor Verilator ran. Without the agent this run creates
      // build/chiselsim/GcdSpec/GCD/computes-gcd-48--18-/workdir-verilator.
      val chiselsim = r.cwd / "build" / "chiselsim" / "GcdSpec"
      val produced  = if (os.exists(chiselsim)) os.walk(chiselsim) else Nil
      assert(!produced.exists(_.last == "workdir-verilator"))
      assert(!produced.exists(_.ext == "sv"))
    }
    test("deepsoc parameterized") {
      val r = viewRun("example.cp", "deephier.DeepSocSpec", Some("Cluster with 3 cores steps"))
      assert(r.model.status == "ok")
      assert(r.model.top == "Cluster")
      assertConsumable(r.model)
      val cluster = r.model.modules.find(_.name == "Cluster").get
      val coreModules = r.model.modules.filter(_.scalaClass == "deephier.Core").map(_.name).toSet
      assert(cluster.instances.count(i => coreModules(i.module)) == 3)
      // nCores is a plain parameter: read from the recorded constructor arguments.
      val nCores = cluster.params.find(_.name == "nCores").get
      assert(nCores.value == "3", nCores.origin == "ctor", nCores.digest.nonEmpty)
    }
    test("constructor arguments") {
      val r = viewRun("fixtures.cp", "probefix.ParamsSpec", None)
      assert(r.model.status == "ok")
      assertConsumable(r.model)
      val byName = r.model.modules.map(m => m.name -> m).toMap
      val top    = byName("ParamsTop")
      /** The params of the module instance `inst` of ParamsTop, by name. */
      def params(inst: String): Map[String, CParam] =
        byName(top.instances.find(_.name == inst).get.module).params.map(p => p.name -> p).toMap
      def value(inst: String, p: String) = params(inst)(p).value
      def digest(inst: String, p: String) = params(inst)(p).digest

      assert(params("plain")("nCores") == params("plain")("nCores").copy(value = "2", origin = "ctor"))
      assert(digest("plain", "nCores").nonEmpty)
      assert(value("cfgA", "cfg") == "LaneCfg(width = 8, label = a)")
      assert(value("seq", "taps") == "Seq(1, 2, 3, 4, 5, 6, 7, 8, ... (20 total))")
      assert(value("fn", "f") == "<function>")
      assert(value("tpe", "gen") == "UInt<8>")
      assert(value("opaque", "o") == "<Opaque>")
      assert(value("bomb", "b") == "<unreadable>")
      assert(value("deep", "w") == "Wrap(inner = Wrap2(inner = Wrap3(inner = Wrap4(...))))")
      // multiple and implicit parameter lists, flattened in declaration order
      assert(params("lists").keys.toSet == Set("a", "b", "cfg"))
      assert(value("lists", "a") == "1", value("lists", "b") == "two", value("lists", "cfg") == "LaneCfg(width = 4, label = imp)")
      // a secondary constructor: the primary's arguments, its default filled in
      assert(value("secondary", "depth") == "3", value("secondary", "width") == "8")
      // an anonymous subclass: the named superclass's parameters
      assert(byName(top.instances.find(_.name == "anon").get.module).scalaClass.contains("$anon"))
      assert(params("anon").keys.toSet == Set("depth"), value("anon", "depth") == "4")
      // an inherited chain: the module's own level, not the superclass's (6)
      assert(params("striped").keys.toSet == Set("stripes"), value("striped", "stripes") == "3")
      // an inner class: its outer instance is not a parameter
      assert(params("inner").keys.toSet == Set("depth"), value("inner", "depth") == "5")
      // a module class declared in a module: its outer instance is a module
      assert(params("nested").keys.toSet == Set("k"), value("nested", "k") == "9")
      // Chisel literals show their value; same type, different values, different digests
      assert(value("lit5", "init") == "UInt<3>(5)", value("lit7", "init") == "UInt<3>(7)")
      assert(digest("lit5", "init").nonEmpty, digest("lit5", "init") != digest("lit7", "init"))
      // hardware is a reference, not a value: no digest
      assert(value("hw", "sig") == "UInt<8>", digest("hw", "sig") == "")
      // long values: the same display, different digests
      assert(value("longA", "s") == "x" * 200 + "...(truncated)", value("longA", "s") == value("longB", "s"))
      assert(digest("longA", "s").nonEmpty, digest("longB", "s").nonEmpty, digest("longA", "s") != digest("longB", "s"))
      // equal values, equal digests; different values, different digests
      assert(digest("cfgA", "cfg") == digest("cfgB", "cfg"), digest("cfgA", "cfg") != digest("cfgC", "cfg"))
      // opaque values have no digest
      for ((i, p) <- Seq("fn" -> "f", "opaque" -> "o", "bomb" -> "b")) assert(digest(i, p) == "")
    }
    test("no simulate") {
      val r = viewRun("fixtures.cp", "probefix.NoSimulateSpec", None)
      assert(r.model.status == "no-design")
      assert(r.model.modules.isEmpty)
      // the Chisel on the test classpath, so a no-design can say which one it found
      assert(r.model.chiselVersion == chisel3.BuildInfo.version)
      assert(r.output.contains("Tests: succeeded 1, failed 0"))
    }
    test("elaboration error") {
      val r = viewRun("fixtures.cp", "probefix.BrokenSpec", None)
      assert(r.model.status == "elaboration-error")
      assert(r.model.error.contains("broken on purpose"))
    }
    test("without view mode a missing Verilator still fails the run") {
      val r = viewRun("example.cp", "gcd.GcdSpec", Some("GCD computes gcd(48, 18)"), view = false)
      assert(r.output.contains("verilator not found on the PATH"))
      assert(r.model.status == "no-design")
    }
  }
}
