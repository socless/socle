package socle.probe

import chisel3.Data
import chisel3.experimental.BaseModule
import chisel3.reflect.DataMirror

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.util.control.NonFatal

/** Renders a recorded constructor argument for display, and digests it.
  *
  * The display text is short and lossy (nesting, element counts and leaf
  * lengths are capped; ModelWalk caps the whole at 200 characters). The digest
  * is computed over a full, uncapped traversal of the value, so two values
  * that differ past the display cap still differ. It is empty ("unknown, never
  * equal to anything") when the traversal exceeds its work budget, or when any
  * part of the value, at any depth, is only shown opaquely: a function, a
  * module, hardware that is not a literal, an object without a toString of
  * its own (one with its own is digested from that text), an
  * identity-looking toString, a lazy collection, or anything that could not
  * be read. A Chisel literal shows (and digests) its value, a Chisel type its
  * label with a ground type's width (`UInt<8>`); its digest covers the whole type. */
object ParamValues {

  val MaxDepth = 3  // nesting levels shown: Name(f = Inner(g = ...)) stops below
  val MaxElems = 8  // collection elements shown before "... (n total)"

  // The digest's work budget: values visited, characters hashed, nesting depth.
  val DigestNodes = 100000
  val DigestChars = 1000000
  val DigestDepth = 64

  /** Display text of `v`; `name` labels an anonymous Chisel bundle. */
  def render(v: Any, name: String): String =
    try show(v, name, 0)
    catch { case NonFatal(_) | _: StackOverflowError => ModelWalk.Unreadable }

  /** Hex digest of `v`'s full structure, or "" when unknown. */
  def digest(v: Any, name: String): String =
    try {
      val d = new Digester(name)
      val md = MessageDigest.getInstance("SHA-256")
      d.feed(md, v, 0)
      md.digest().take(16).map(b => f"${b & 0xff}%02x").mkString
    } catch { case NoDigest | NonFatal(_) | _: StackOverflowError => "" }

  // ── Display ───────────────────────────────────────────────────────────────

  private def show(v: Any, name: String, depth: Int): String = v match {
    case null                => "null"
    case a: CtorArgs.ModuleArg => moduleText(a.get)
    case m: BaseModule       => moduleText(m)
    case d: Data             => if (d.isLit) leaf(d.toString) else dataLabel(d, name)
    case s: String           => leaf(s)
    case _: java.lang.Number | _: java.lang.Boolean | _: java.lang.Character | _: BigInt | _: BigDecimal =>
      leaf(v.toString)
    case _ if lazyLike(v)    => s"<${simpleName(v.getClass)}>"
    case a: Array[_]         => elems("Array", a.iterator, a.length, name, depth)
    case m: scala.collection.Map[_, _] =>
      elems("Map", m.iterator.map { case (k, x) => Entry(k, x) }, m.size, name, depth)
    case s: scala.collection.Iterable[_] => elems(collName(s), s.iterator, s.size, name, depth)
    case e: Entry            => show(e.k, name, depth) + " -> " + show(e.v, name, depth)
    case p: Product if isTuple(p) =>
      if (depth >= MaxDepth) "(...)"
      else p.productIterator.map(show(_, name, depth + 1)).mkString("(", ", ", ")")
    case p: Product =>
      val prefix = p.productPrefix
      if (p.productArity == 0) prefix
      else if (depth >= MaxDepth) s"$prefix(...)"
      else
        p.productElementNames.zip(p.productIterator)
          .map { case (n, x) => s"$n = ${show(x, name, depth + 1)}" }
          .mkString(s"$prefix(", ", ", ")")
    case _ if isFunction(v)  => "<function>"
    case _ if ownToString(v) => leaf(v.toString)
    case _                   => s"<${simpleName(v.getClass)}>"
  }

  private final case class Entry(k: Any, v: Any)

  private def elems(prefix: String, it: Iterator[Any], total: Int, name: String, depth: Int): String =
    if (depth >= MaxDepth) s"$prefix(...)"
    else {
      val shown = it.take(MaxElems).map(show(_, name, depth + 1)).toList
      val more  = if (total > MaxElems) Seq(s"... ($total total)") else Nil
      (shown ++ more).mkString(s"$prefix(", ", ", ")")
    }

  /** A leaf longer than the display cap is cut here already. */
  private def leaf(s: String): String =
    if (s.length <= ModelWalk.MaxValueChars) s else s.take(ModelWalk.MaxValueChars + 1)

  private def moduleText(m: AnyRef): String =
    if (m == null) "<module>" else s"<module ${simpleName(m.getClass)}>"

  /** The port type label; a ground type also shows its known width (`UInt<8>`). */
  private def dataLabel(d: Data, name: String): String = {
    val t = ModelWalk.typeOf(d, name)
    if (t.fields.isEmpty && t.vecLen < 0 && t.width >= 0) s"${t.label}<${t.width}>" else t.label
  }

  private def collName(s: scala.collection.Iterable[_]): String = s match {
    case _: List[_]                    => "List"
    case _: Vector[_]                  => "Vector"
    case _: scala.collection.Seq[_]    => "Seq"
    case _: scala.collection.Set[_]    => "Set"
    case _                             => "Iterable"
  }

  // ── Classification ────────────────────────────────────────────────────────

  /** Never iterated: a lazy or one-shot collection may be infinite or consumed. */
  private def lazyLike(v: Any): Boolean = v match {
    case _: LazyList[_] | _: scala.collection.View[_] | _: Iterator[_] => true
    case _: scala.collection.Iterable[_]                              => false
    case _: scala.collection.IterableOnce[_]                          => !v.isInstanceOf[Option[_]]
    case _                                                            => false
  }

  private def isTuple(p: Product): Boolean = p.getClass.getName.startsWith("scala.Tuple")

  private def isFunction(v: Any): Boolean = v match {
    case _: Function0[_] | _: Function1[_, _] | _: Function2[_, _, _] | _: Function3[_, _, _, _] => true
    case _ =>
      val c = v.getClass
      c.isSynthetic || c.getName.contains("$$Lambda") ||
        c.getInterfaces.exists(i => i.getName.startsWith("scala.Function") || i.getName.startsWith("java.util.function."))
  }

  /** Whether the class overrides Object.toString. */
  private def ownToString(v: Any): Boolean =
    v.getClass.getMethod("toString").getDeclaringClass != classOf[Object]

  /** `Object.toString`'s shape (`Name@1b6d3586`), left by a toString that defers to it. */
  private val IdentityLike = ".*@[0-9a-f]{4,}.*".r

  private[probe] def simpleName(c: Class[_]): String = {
    val n    = c.getName
    val last = n.substring(n.lastIndexOf('.') + 1)
    last.split('$').filter(s => s.nonEmpty && !s.forall(_.isDigit) && s != "anon").lastOption.getOrElse(last)
  }

  // ── Digest ────────────────────────────────────────────────────────────────

  private case object NoDigest extends RuntimeException(null, null, false, false)

  /** Feeds a tagged, length-prefixed encoding of the value into a MessageDigest. */
  private final class Digester(name: String) {
    private var nodes = 0
    private var chars = 0L

    def feed(md: MessageDigest, v: Any, depth: Int): Unit = {
      nodes += 1
      if (nodes > DigestNodes || depth > DigestDepth) throw NoDigest
      v match {
        case null                  => tag(md, 'N')
        case _: CtorArgs.ModuleArg => throw NoDigest
        case _: BaseModule         => throw NoDigest
        case d: Data if d.isLit    =>
          tag(md, 'L'); str(md, ModelWalk.typeOf(d, name).toString); str(md, d.litValue.toString(16))
        // Hardware (a port, wire or register of the parent) is a reference, not a value.
        case d: Data if DataMirror.internal.isSynthesizable(d) => throw NoDigest
        case d: Data               => tag(md, 'D'); str(md, ModelWalk.typeOf(d, name).toString)
        case s: String             => tag(md, 'S'); str(md, s)
        case _: java.lang.Number | _: java.lang.Boolean | _: java.lang.Character | _: BigInt | _: BigDecimal =>
          tag(md, 'V'); str(md, v.getClass.getName); str(md, v.toString)
        case _ if lazyLike(v)      => throw NoDigest
        case a: Array[_]           => ordered(md, 'Q', a.iterator, a.length, depth)
        case m: scala.collection.Map[_, _] =>
          unordered(md, 'M', m.iterator.map { case (k, x) => Entry(k, x) }, m.size, depth)
        case s: scala.collection.Set[_]      => unordered(md, 'U', s.iterator, s.size, depth)
        case s: scala.collection.Iterable[_] => ordered(md, 'Q', s.iterator, s.size, depth)
        case e: Entry              => tag(md, 'E'); feed(md, e.k, depth + 1); feed(md, e.v, depth + 1)
        case p: Product =>
          tag(md, 'P'); str(md, p.getClass.getName); int(md, p.productArity)
          p.productElementNames.zip(p.productIterator).foreach { case (n, x) => str(md, n); feed(md, x, depth + 1) }
        case _ if isFunction(v)    => throw NoDigest
        case _ if ownToString(v) =>
          val text = v.toString
          if (text == null || IdentityLike.matches(text)) throw NoDigest
          tag(md, 'T'); str(md, v.getClass.getName); str(md, text)
        case _ => throw NoDigest
      }
    }

    private def ordered(md: MessageDigest, t: Char, it: Iterator[Any], n: Int, depth: Int): Unit = {
      tag(md, t); int(md, n)
      it.foreach(feed(md, _, depth + 1))
    }

    /** Each element digested on its own, then sorted: equal maps and sets
      * hash alike whatever their iteration order. */
    private def unordered(md: MessageDigest, t: Char, it: Iterator[Any], n: Int, depth: Int): Unit = {
      tag(md, t); int(md, n)
      val parts = it.map { x =>
        val sub = MessageDigest.getInstance("SHA-256")
        feed(sub, x, depth + 1)
        sub.digest().map(b => f"${b & 0xff}%02x").mkString
      }.toVector.sorted
      parts.foreach(str(md, _))
    }

    private def tag(md: MessageDigest, t: Char): Unit = md.update(t.toByte)
    private def int(md: MessageDigest, i: Int): Unit = md.update(java.nio.ByteBuffer.allocate(4).putInt(i).array())
    private def str(md: MessageDigest, s: String): Unit = {
      chars += s.length
      if (chars > DigestChars) throw NoDigest
      val b = s.getBytes(StandardCharsets.UTF_8)
      int(md, b.length); md.update(b)
    }
  }
}
