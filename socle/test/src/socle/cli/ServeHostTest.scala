package socle.cli

import socle._

import socle.schema._
import socle.parse._
import socle.ir._
import socle.layout._
import socle.cli._

import utest._

/** Unit tests for [[Serve.resolveBindHost]] - the loopback-vs-WSL bind address
  * decision (issue: blank UI on WSL).
  *
  * Background: the standalone UI server bound `127.0.0.1` unconditionally.
  * WSL2's localhost-forwarding relay only forwards Windows -> WSL traffic to
  * services bound to `0.0.0.0` (or the VM's eth0); a service bound strictly to
  * `127.0.0.1` inside the VM is invisible to it, so the Windows-side browser
  * gets ERR_CONNECTION_REFUSED and shows a blank page. Under WSL we therefore
  * bind `0.0.0.0`, while a normal machine keeps the loopback-only default.
  *
  * Purely functional - no env reads, no sockets.
  */
object ServeHostTest extends TestSuite {

  val tests = Tests {

    test("no override, not WSL: loopback only (unchanged default)") {
      assert(Serve.resolveBindHost(None, wsl = false) == "127.0.0.1")
    }

    test("no override, under WSL: bind all interfaces so the Windows relay reaches it") {
      assert(Serve.resolveBindHost(None, wsl = true) == "0.0.0.0")
    }

    test("explicit --host wins over WSL auto-detection") {
      assert(Serve.resolveBindHost(Some("192.168.1.5"), wsl = true) == "192.168.1.5")
    }

    test("explicit --host can force loopback even under WSL") {
      assert(Serve.resolveBindHost(Some("127.0.0.1"), wsl = true) == "127.0.0.1")
    }

    test("explicit --host respected when not WSL") {
      assert(Serve.resolveBindHost(Some("0.0.0.0"), wsl = false) == "0.0.0.0")
    }
  }
}
