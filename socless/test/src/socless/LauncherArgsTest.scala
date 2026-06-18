package socless

import socless.schema._
import socless.parse._
import socless.ir._
import socless.layout._
import socless.cli._

import utest._

/** Unit tests for [[Launcher.parseArgs]].
  *
  * Purely functional - no reflection, no Chisel elaboration, no file I/O.
  * Tests cover all recognised flags (including --fir-input), all error
  * branches, and the --help short-circuit.
  */
object LauncherArgsTest extends TestSuite {

  /** Fail a test unconditionally with a message (avoids shadowing by utest.AssertionError). */
  private def fail(msg: String): Nothing = throw new RuntimeException(s"Test failed: $msg")

  val tests = Tests {

    // -----------------------------------------------------------------------
    // Happy path - Chisel mode (--top)
    // -----------------------------------------------------------------------

    test("all Chisel-mode flags provided returns Right(config)") {
      val result = Launcher.parseArgs(Array(
        "--top", "my.pkg.Top",
        "--output", "/tmp/design.json",
        "--source-root", "/workspace",
        "--chirrtl-out", "/tmp/out.fir",
      ))
      assert(result == Right(Launcher.LauncherConfig(
        top        = Some("my.pkg.Top"),
        firInput   = None,
        output     = Some("/tmp/design.json"),
        sourceRoot = Some("/workspace"),
        chirrtlOut = Some("/tmp/out.fir"),
      )))
    }

    test("only --top provided: optional fields are None") {
      val result = Launcher.parseArgs(Array("--top", "a.b.MyModule"))
      assert(result == Right(Launcher.LauncherConfig(
        top        = Some("a.b.MyModule"),
        firInput   = None,
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
          assert(cfg.top.contains("example.axisystem.AXISystem"))
          assert(cfg.firInput.isEmpty)
          assert(cfg.output.contains("/mill/out/design.json"))
          assert(cfg.sourceRoot.contains("/workspace"))
          assert(cfg.chirrtlOut.isEmpty)
        case Left(err) =>
          fail(s"Expected Right but got Left($err)")
      }
    }

    // -----------------------------------------------------------------------
    // Happy path - CHIRRTL file mode (--fir-input)
    // -----------------------------------------------------------------------

    test("--fir-input only returns Right(config) with top=None") {
      val result = Launcher.parseArgs(Array("--fir-input", "/designs/foo.fir"))
      assert(result == Right(Launcher.LauncherConfig(
        top        = None,
        firInput   = Some("/designs/foo.fir"),
        output     = None,
        sourceRoot = None,
        chirrtlOut = None,
      )))
    }

    test("--fir-input with --output") {
      val result = Launcher.parseArgs(Array(
        "--fir-input", "/designs/foo.fir",
        "--output", "/tmp/design.json",
      ))
      result match {
        case Right(cfg) =>
          assert(cfg.top.isEmpty)
          assert(cfg.firInput.contains("/designs/foo.fir"))
          assert(cfg.output.contains("/tmp/design.json"))
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
    // Missing required flag
    // -----------------------------------------------------------------------

    test("empty args returns Left mentioning --top or --fir-input") {
      Launcher.parseArgs(Array()) match {
        case Left(msg) => assert(msg.contains("--top") || msg.contains("--fir-input"))
        case Right(_)  => fail("Expected Left but got Right")
      }
    }

    test("no --top and no --fir-input but other flags present returns Left") {
      Launcher.parseArgs(Array("--output", "/tmp/out.json")) match {
        case Left(msg) => assert(msg.contains("--top") || msg.contains("--fir-input"))
        case Right(_)  => fail("Expected Left but got Right")
      }
    }

    // -----------------------------------------------------------------------
    // Mutual exclusion: --top and --fir-input together
    // -----------------------------------------------------------------------

    test("--top and --fir-input together returns Left") {
      Launcher.parseArgs(Array("--top", "pkg.Top", "--fir-input", "foo.fir")) match {
        case Left(msg) => assert(msg.contains("mutually exclusive") || msg.contains("--fir-input"))
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

    test("--fir-input at end of args with no value returns Left") {
      Launcher.parseArgs(Array("--fir-input")) match {
        case Left(msg) => assert(msg.contains("--fir-input") && msg.contains("value"))
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

    test("Chisel-mode flags in any order still parse correctly") {
      val result = Launcher.parseArgs(Array(
        "--source-root", "/src",
        "--chirrtl-out", "/fir/out.fir",
        "--output", "/out/design.json",
        "--top", "com.example.Top",
      ))
      assert(result == Right(Launcher.LauncherConfig(
        top        = Some("com.example.Top"),
        firInput   = None,
        output     = Some("/out/design.json"),
        sourceRoot = Some("/src"),
        chirrtlOut = Some("/fir/out.fir"),
      )))
    }

    // -----------------------------------------------------------------------
    // Help text (issue #157)
    // -----------------------------------------------------------------------

    test("--help/-h are recognised and HelpText documents every mode + flag") {
      assert(Launcher.parseArgs(Array("--help")) == Left("help"))
      assert(Launcher.parseArgs(Array("-h")) == Left("help"))
      // Each mode the single main supports must be named, or the help silently
      // drops a feature as the CLI grows.
      for (mode <- Seq("elaborate", "convert", "dump", "serve"))
        assert(Launcher.HelpText.contains(mode))
      // Every flag - export and server - must be documented in --help.
      for (flag <- Seq("--top", "--fir-input", "--output", "--chirrtl-out", "--source-root",
                       "--strict", "--port", "--dir", "--watch", "--no-open"))
        assert(Launcher.HelpText.contains(flag))
      // serve --help documents the server flags too (shared text).
      for (flag <- Seq("--port", "--dir", "--watch", "--source-root", "--no-open"))
        assert(Serve.Help.contains(flag))
    }
  }
}
