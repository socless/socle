package socle.probe;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

// Byte Buddy inlines these advice bodies into Chisel's own classes, so each
// body is a single static call into Capture and references nothing else.
public final class CaptureAdvice {
  private CaptureAdvice() {}

  /** On exit of the ChiselCircuitAnnotation and DesignAnnotation constructors. */
  public static final class OnAnnotation {
    private OnAnnotation() {}

    @Advice.OnMethodExit
    public static void exit(@Advice.This Object anno) {
      Capture.onAnnotation(anno);
    }
  }

  /** On exit of chisel3.stage.phases.Elaborate.transform, normal or thrown. */
  public static final class OnElaborate {
    private OnElaborate() {}

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Thrown Throwable thrown) {
      Capture.onElaborateExit(thrown);
    }
  }

  /** On normal exit of every constructor of a chisel3.experimental.BaseModule
    * subclass (the user's modules included): stores references to the module,
    * its arguments and the constructor's declaring class, nothing more. A
    * throwable from the store is suppressed, so elaboration never sees it. */
  public static final class OnModuleConstructed {
    private OnModuleConstructed() {}

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(
        @Advice.This Object module,
        @Advice.AllArguments Object[] args,
        @Advice.Origin Class<?> declaring) {
      Capture.onModuleConstructed(module, args, declaring);
    }
  }

  /** On exit of svsim.verilator.Backend$.initializeFromProcessEnvironment,
    * installed in view mode only: a failed Verilator lookup is replaced by a
    * placeholder backend, so the run reaches elaboration without Verilator. */
  public static final class OnVerilatorLookup {
    private OnVerilatorLookup() {}

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Thrown(readOnly = false) Throwable thrown,
        @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returned) {
      returned = Capture.onVerilatorLookupExit(thrown, returned);
      if (returned != null) thrown = null;
    }
  }
}
