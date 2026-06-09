package socless

import utest._

/** Unit tests for [[Launcher.parseArgs]].
  *
  * Purely functional - no reflection, no Chisel elaboration, no file I/O.
  * Tests cover the four happy-path arguments, all error branches, and the
  * --help short-circuit.
  */
object LauncherArgsTest extends TestSuite {

  /** Fail a test unconditionally with a message (avoids shadowing by utest.AssertionError). */
  private def fail(msg: String): Nothing = throw new RuntimeException(s"Test failed: $msg")

  val tests = Tests {

    // -----------------------------------------------------------------------
    // Happy path
    // -----------------------------------------------------------------------

    test("all flags provided returns Right(config)") {
      val result = Launcher.parseArgs(Array(
        "--top", "my.pkg.Top",
        "--output", "/tmp/design.json",
        "--source-root", "/workspace",
        "--chirrtl-out", "/tmp/out.fir",
      ))
      assert(result == Right(Launcher.LauncherConfig(
        top        = "my.pkg.Top",
        output     = Some("/tmp/design.json"),
        sourceRoot = Some("/workspace"),
        chirrtlOut = Some("/tmp/out.fir"),
      )))
    }

    test("only --top provided: optional fields are None") {
      val result = Launcher.parseArgs(Array("--top", "a.b.MyModule"))
      assert(result == Right(Launcher.LauncherConfig(
        top        = "a.b.MyModule",
        output     = None,
        sourceRoot = None,
        chirrtlOut = None,
      )))
    }

    test("Mill plugin style: --top --output --source-root") {
      // Mirrors what SoclessModule.socless passes
      val result = Launcher.parseArgs(Array(
        "--top", "example.axisystem.AXISystem",
        "--output", "/mill/out/design.json",
        "--source-root", "/workspace",
      ))
      result match {
        case Right(cfg) =>
          assert(cfg.top == "example.axisystem.AXISystem")
          assert(cfg.output.contains("/mill/out/design.json"))
          assert(cfg.sourceRoot.contains("/workspace"))
          assert(cfg.chirrtlOut.isEmpty)
        case Left(err) =>
          fail(s"Expected Right but got Left($err)")
      }
    }

    // -----------------------------------------------------------------------
    // --help / -h
    // -----------------------------------------------------------------------

    test("--help returns Left(\"help\")") {
      assert(Launcher.parseArgs(Array("--help")) == Left("help"))
    }

    test("-h returns Left(\"help\")") {
      assert(Launcher.parseArgs(Array("-h")) == Left("help"))
    }

    test("--help short-circuits even when valid flags follow") {
      assert(Launcher.parseArgs(Array("--help", "--top", "foo")) == Left("help"))
    }

    // -----------------------------------------------------------------------
    // Missing required --top
    // -----------------------------------------------------------------------

    test("empty args returns Left with --top message") {
      Launcher.parseArgs(Array()) match {
        case Left(msg) => assert(msg.contains("--top"))
        case Right(_)  => fail("Expected Left but got Right")
      }
    }

    test("no --top but other flags present returns Left") {
      Launcher.parseArgs(Array("--output", "/tmp/out.json")) match {
        case Left(msg) => assert(msg.contains("--top"))
        case Right(_)  => fail("Expected Left but got Right")
      }
    }

    // -----------------------------------------------------------------------
    // Unknown flags
    // -----------------------------------------------------------------------

    test("unknown flag returns Left naming the bad flag") {
      Launcher.parseArgs(Array("--top", "pkg.Top", "--bogus", "val")) match {
        case Left(msg) => assert(msg.contains("--bogus"))
        case Right(_)  => fail("Expected Left but got Right")
      }
    }

    test("bare positional argument returns Left") {
      Launcher.parseArgs(Array("pkg.Top")) match {
        case Left(msg) => assert(msg.contains("pkg.Top") || msg.contains("unknown flag"))
        case Right(_)  => fail("Expected Left but got Right")
      }
    }

    // -----------------------------------------------------------------------
    // Missing value after flag
    // -----------------------------------------------------------------------

    test("--top at end of args with no value returns Left") {
      Launcher.parseArgs(Array("--top")) match {
        case Left(msg) => assert(msg.contains("--top") && msg.contains("value"))
        case Right(_)  => fail("Expected Left but got Right")
      }
    }

    test("--output at end of args with no value returns Left") {
      Launcher.parseArgs(Array("--top", "pkg.Top", "--output")) match {
        case Left(msg) => assert(msg.contains("--output") && msg.contains("value"))
        case Right(_)  => fail("Expected Left but got Right")
      }
    }

    test("--source-root followed immediately by another flag returns Left") {
      // Another flag where a path is expected - should be treated as missing value
      Launcher.parseArgs(Array("--top", "pkg.Top", "--source-root", "--output")) match {
        case Left(msg) => assert(msg.contains("--source-root"))
        case Right(_)  => fail("Expected Left but got Right")
      }
    }

    test("--chirrtl-out at end of args returns Left") {
      Launcher.parseArgs(Array("--top", "pkg.Top", "--chirrtl-out")) match {
        case Left(msg) => assert(msg.contains("--chirrtl-out") && msg.contains("value"))
        case Right(_)  => fail("Expected Left but got Right")
      }
    }

    // -----------------------------------------------------------------------
    // Ordering independence
    // -----------------------------------------------------------------------

    test("flags in any order still parse correctly") {
      val result = Launcher.parseArgs(Array(
        "--source-root", "/src",
        "--chirrtl-out", "/fir/out.fir",
        "--output", "/out/design.json",
        "--top", "com.example.Top",
      ))
      assert(result == Right(Launcher.LauncherConfig(
        top        = "com.example.Top",
        output     = Some("/out/design.json"),
        sourceRoot = Some("/src"),
        chirrtlOut = Some("/fir/out.fir"),
      )))
    }
  }
}
