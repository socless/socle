package socle.probe

import chisel3.experimental.BaseModule

import java.lang.ref.{ReferenceQueue, WeakReference}

/** The constructor arguments of each module, as the agent's constructor
  * advice hands them over: one array per declaring class of the module's
  * constructor chain, the first one recorded for that class (the primary
  * constructor, which a secondary one delegates to and so exits first).
  *
  * Only references are stored, nothing is rendered here. Modules are weak
  * keys compared by identity (a module's own equals or hashCode is never
  * called, it is user code on a half-built object); an argument that is itself
  * a module is held weakly too. Recording stops, and everything is dropped,
  * once the capture is written ([[stop]]), so the arguments never outlive the
  * elaboration they come from. */
object CtorArgs {

  /** An argument that was a module, held weakly. */
  final class ModuleArg(m: AnyRef) extends WeakReference[AnyRef](m)

  private final class Key(m: AnyRef, q: ReferenceQueue[AnyRef]) extends WeakReference[AnyRef](m, q) {
    private val hash = System.identityHashCode(m)
    override def hashCode: Int = hash
    override def equals(o: Any): Boolean = o match {
      case k: Key => (k eq this) || { val a = get; a != null && (a eq k.get) }
      case _      => false
    }
  }

  private val queue  = new ReferenceQueue[AnyRef]
  private val byKey  = new java.util.HashMap[Key, java.util.LinkedHashMap[Class[_], Array[AnyRef]]]
  @volatile private var recording = true

  /** Constructor exit of `module` in `declaring`; `args` is the advice's own copy. */
  def record(module: AnyRef, declaring: Class[_], args: Array[AnyRef]): Unit =
    if (recording && module != null && declaring != null && args != null && args.length > 0) {
      var i = 0
      while (i < args.length) {
        if (args(i).isInstanceOf[BaseModule]) args(i) = new ModuleArg(args(i))
        i += 1
      }
      synchronized {
        expunge()
        var levels = byKey.get(new Key(module, null))
        if (levels == null) {
          levels = new java.util.LinkedHashMap[Class[_], Array[AnyRef]]
          byKey.put(new Key(module, queue), levels)
        }
        levels.putIfAbsent(declaring, args)
      }
    }

  /** The arguments recorded for `module`'s constructor in `declaring`, if any. */
  def of(module: AnyRef, declaring: Class[_]): Option[Array[AnyRef]] = synchronized {
    Option(byKey.get(new Key(module, null))).flatMap(l => Option(l.get(declaring)))
  }

  /** The capture is written: record nothing more, drop what was recorded. */
  def stop(): Unit = synchronized { recording = false; byKey.clear() }

  /** Test seam: record again, from an empty map. */
  private[probe] def resetForTest(): Unit = synchronized { byKey.clear(); recording = true }

  private def expunge(): Unit = {
    var r = queue.poll()
    while (r != null) { byKey.remove(r); r = queue.poll() }
  }
}
