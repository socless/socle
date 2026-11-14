package socle.probe

import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.asm.Advice
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.`type`.TypeDescription
import net.bytebuddy.matcher.ElementMatchers._

import java.lang.instrument.Instrumentation
import java.nio.file.Paths

/** Java agent (`-javaagent:<assembly>`) attached to an unmodified ChiselSim
  * test JVM. Writes the captured design to `-Dsocle.probe.out=<file.json>`;
  * with `-Dsocle.probe.mode=view` it then stops the test before firtool,
  * and a missing Verilator does not stop the run before elaboration.
  * Chisel classes are matched by name only, so none is loaded here. */
object Agent {

  def premain(args: String, inst: Instrumentation): Unit =
    sys.props.get(Capture.OutProp) match {
      case None =>
        System.err.println(s"[socle-probe] -D${Capture.OutProp} is not set: the probe is disabled")
      case Some(outFile) =>
        val view = sys.props.get(Capture.ModeProp).contains("view")
        Capture.configure(Paths.get(outFile), view)
        Runtime.getRuntime.addShutdownHook(new Thread(() => Capture.onShutdown(), "socle-probe-shutdown"))
        val builder = new AgentBuilder.Default()
          .disableClassFormatChanges()
          .`with`(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
          .`with`(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
          // The JDK, Scala and the probe itself define no Chisel module: skip
          // them before the module matcher below resolves their hierarchy.
          .ignore(
            isSynthetic[TypeDescription]()
              .or(nameStartsWith[TypeDescription]("java."))
              .or(nameStartsWith[TypeDescription]("javax."))
              .or(nameStartsWith[TypeDescription]("jdk."))
              .or(nameStartsWith[TypeDescription]("sun."))
              .or(nameStartsWith[TypeDescription]("com.sun."))
              .or(nameStartsWith[TypeDescription]("scala."))
              .or(nameStartsWith[TypeDescription]("net.bytebuddy."))
              .or(nameStartsWith[TypeDescription]("socle.probe."))
              .or(nameStartsWith[TypeDescription]("socle.shaded."))
          )
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
        // View mode only: ChiselSim looks Verilator up before elaborating; a
        // failed lookup must not stop a run that never reaches Verilator.
        // The user's module constructors are instrumented in view mode only
        // too, so a run that goes on to simulate runs their code unchanged.
        val viewBuilder =
          if (!view) builder
          else
            builder
              .`type`(named[TypeDescription]("svsim.verilator.Backend$"))
              .transform((b, _, _, _, _) =>
                b.visit(
                  Advice
                    .to(classOf[CaptureAdvice.OnVerilatorLookup])
                    .on(named[MethodDescription]("initializeFromProcessEnvironment").and(takesNoArguments[MethodDescription]()))
                )
              )
              // Constructor arguments of every module (CtorArgs): only
              // references are stored, rendered after elaboration by ModelWalk.
              // BaseModule is a class, so its subclasses are matched by
              // superclass alone, without resolving every loaded class's interfaces.
              .`type`(hasSuperClass[TypeDescription](named[TypeDescription]("chisel3.experimental.BaseModule")))
              .transform((b, _, _, _, _) =>
                b.visit(Advice.to(classOf[CaptureAdvice.OnModuleConstructed]).on(isConstructor[MethodDescription]()))
              )
        viewBuilder.installOn(inst)
        ()
    }
}
