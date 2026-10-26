package socless.lsp

import com.google.gson.JsonObject
import socless.wave.WaveSettings
import utest._

object SimSettingsTest extends TestSuite {
  private def obj(kvs: (String, Any)*): JsonObject = {
    val o = new JsonObject
    kvs.foreach {
      case (k, v: Boolean) => o.addProperty(k, v)
      case (k, v: Int)     => o.addProperty(k, v)
      case (k, v: String)  => o.addProperty(k, v)
      case (k, v)          => sys.error(s"unsupported $k=$v")
    }
    o
  }

  val tests = Tests {
    test("defaults") {
      val d = SimSettings()
      assert(d.waveViewer == "surfer", d.waveViewerCommand == "", d.wave == WaveSettings())
      assert(WaveSettings() == WaveSettings(true, false, false, true, true, 150))
      assert(SimSettings.fromInit(null) == d)
      assert(SimSettings.fromInit(obj("panel" -> "client")) == d)
    }
    test("fromInit reads every key") {
      val s = SimSettings.fromInit(obj(
        "waveViewer" -> "custom", "waveViewerCommand" -> "gtkwave {file}",
        "wavesFollowSelection" -> false, "wavesShowClockReset" -> true, "wavesFoldBundles" -> true,
        "wavesShowWires" -> false, "wavesColorByDirection" -> false, "wavesFollowDelayMs" -> 40))
      assert(s.waveViewer == "custom", s.waveViewerCommand == "gtkwave {file}")
      assert(s.wave == WaveSettings(false, true, true, false, false, 40))
    }
    test("fromConfig reads the socless section") {
      val sec = obj("waveViewer" -> "surfer-panel", "wavesFollowDelayMs" -> 0)
      val top = new JsonObject
      top.add("socless", sec)
      val s = SimSettings.fromConfig(top).get
      assert(s.waveViewer == "surfer-panel", s.wave.followDelayMs == 0, s.wave.followSelection)
      assert(SimSettings.fromConfig(new JsonObject).isEmpty)
    }
    test("bad values fall back") {
      assert(SimSettings.fromInit(obj("waveViewer" -> "gtkwave")).waveViewer == "surfer")
      assert(SimSettings.fromInit(obj("wavesFollowDelayMs" -> -5)).wave.followDelayMs == 150)
      assert(SimSettings.fromInit(obj("wavesFollowDelayMs" -> "soon")).wave.followDelayMs == 150)
      assert(SimSettings.fromInit(obj("wavesShowWires" -> "maybe")).wave.showWires)
      assert(SimSettings.fromInit(obj("waveViewerCommand" -> "  ")).waveViewerCommand == "")
    }
  }
}
