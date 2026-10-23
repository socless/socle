package socless.probe

import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.asm.Advice
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.`type`.TypeDescription
import net.bytebuddy.matcher.ElementMatchers._

import java.lang.instrument.Instrumentation
import java.nio.file.Paths

/** Java agent (`-javaagent:<assembly>`) attached to an unmodified ChiselSim
  * test JVM. Writes the captured design to `-Dsocless.probe.out=<file.json>`;
  * with `-Dsocless.probe.mode=view` it then stops the test before firtool.
  * Chisel classes are matched by name only, so none is loaded here. */
object Agent {

  def premain(args: String, inst: Instrumentation): Unit =
    sys.props.get(Capture.OutProp) match {
      case None =>
        System.err.println(s"[socless-probe] -D${Capture.OutProp} is not set: the probe is disabled")
      case Some(outFile) =>
        Capture.configure(Paths.get(outFile), sys.props.get(Capture.ModeProp).contains("view"))
        Runtime.getRuntime.addShutdownHook(new Thread(() => Capture.onShutdown(), "socless-probe-shutdown"))
        new AgentBuilder.Default()
          .disableClassFormatChanges()
          .`with`(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
          .`with`(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
          .`type`(
            named[TypeDescription]("chisel3.stage.ChiselCircuitAnnotation")
              .or(named[TypeDescription]("chisel3.stage.DesignAnnotation"))
          )
          .transform((b, _, _, _, _) =>
            b.visit(Advice.to(classOf[CaptureAdvice.OnAnnotation]).on(isConstructor[MethodDescription]()))
          )
          .`type`(named[TypeDescription]("chisel3.stage.phases.Elaborate"))
          .transform((b, _, _, _, _) =>
            b.visit(
              Advice
                .to(classOf[CaptureAdvice.OnElaborate])
                .on(named[MethodDescription]("transform").and(not(isBridge[MethodDescription]())))
            )
          )
          .installOn(inst)
        ()
    }
}
