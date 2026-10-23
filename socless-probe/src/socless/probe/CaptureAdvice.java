package socless.probe;

import net.bytebuddy.asm.Advice;

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
}
