package socle.layout

import socle.schema._

import utest._

/** A routed wire ends exactly on its pins: the router's port and the laid pin
  * are the same point, so the UI never has to bend a wire's last segment onto
  * the pin (the small bumps of #334). */
object WireEndsTest extends TestSuite {

  private def golden(name: String): Design = {
    val s = getClass.getResourceAsStream(s"/golden/$name.json")
    require(s != null, s"missing classpath resource /golden/$name.json")
    try upickle.default.read[Design](scala.io.Source.fromInputStream(s, "UTF-8").mkString)
    finally s.close()
  }

  val tests = Tests {
    test("every routed end is on its pin's row") {
      for (name <- Seq("rvpipeline", "axisystem", "gcd", "deepsoc")) {
        val d = golden(name)
        for (m <- d.modules) {
          val l    = Layout.layout(m, d)
          val pinY = l.nodes.flatMap(_.ports.map(p => p.path -> p.y)).toMap
          for (e <- l.edges if e.points.length >= 2) {
            // points run source (to) -> sink (from)
            for ((path, pt) <- Seq(e.to -> e.points.head, e.from -> e.points.last); y <- pinY.get(path)) {
              val off = math.abs(pt._2 - y)
              if (off > 1) throw new java.lang.AssertionError(s"$name/${m.name}: wire ${e.to} -> ${e.from} ends ${off}px off its pin $path")
            }
          }
        }
      }
    }
  }
}
