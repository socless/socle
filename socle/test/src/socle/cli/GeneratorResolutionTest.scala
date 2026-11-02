package socle.cli

import socle._

import socle.schema._
import socle.parse._
import socle.ir._
import socle.layout._
import socle.cli._

import utest._

// Top-level fixtures so their binary names are plain FQCNs (resolveTop takes
// the same strings users would pass to --top). Resolution never instantiates,
// so these do not need to be Chisel modules.
class GenFixNoArg
class GenFixArgsOnly(val width: Int)
object GenFixApply  { def apply(): AnyRef = new Object }
object GenFixBuild  { def build(): AnyRef = new Object }
object GenFixBoth   { def apply(): AnyRef = new Object; def build(): AnyRef = new Object }
object GenFixFactories {
  def smallSoC(): AnyRef = new Object
  def withArgs(x: Int): AnyRef = new Object
}
class GenFixClassWithCompanion
object GenFixClassWithCompanion { def apply(): AnyRef = new Object }
class GenFixParamWithCompanion(val x: Int)
object GenFixParamWithCompanion { def apply(): AnyRef = new Object }

/** resolveTop accepts classes, generator objects, and factory methods
  * (issue #116); these tests pin the resolution rules and precedence.
  */
object GeneratorResolutionTest extends TestSuite {

  // The GenFix* fixtures below live in this test's package, so resolveTop must
  // be handed their fully-qualified name including the subpackage.
  private val pkg = "socle.cli"
  import Launcher.{resolveTop, GenSpec}

  val tests = Tests {

    test("class with a no-arg constructor resolves via the constructor") {
      val Right(spec) = resolveTop(s"$pkg.GenFixNoArg")
      assert(spec.isInstanceOf[GenSpec.Ctor])
    }

    test("object with apply() resolves to the object method") {
      val Right(spec: GenSpec.ObjectMethod) = resolveTop(s"$pkg.GenFixApply")
      assert(spec.method.getName == "apply")
      assert(spec.instantiate() != null)
    }

    test("object with build() resolves when apply() is absent") {
      val Right(spec: GenSpec.ObjectMethod) = resolveTop(s"$pkg.GenFixBuild")
      assert(spec.method.getName == "build")
    }

    test("apply() wins over build() when both exist") {
      val Right(spec: GenSpec.ObjectMethod) = resolveTop(s"$pkg.GenFixBoth")
      assert(spec.method.getName == "apply")
    }

    test("zero-arg factory method on an object resolves") {
      val Right(spec: GenSpec.ObjectMethod) = resolveTop(s"$pkg.GenFixFactories.smallSoC")
      assert(spec.method.getName == "smallSoC")
      assert(spec.instantiate() != null)
    }

    test("factory methods that need arguments do not resolve") {
      val Left(msg) = resolveTop(s"$pkg.GenFixFactories.withArgs")
      assert(msg.contains("not found"))
      assert(msg.contains("generator object"))
    }

    test("a no-arg constructor takes precedence over the companion apply()") {
      val Right(spec) = resolveTop(s"$pkg.GenFixClassWithCompanion")
      assert(spec.isInstanceOf[GenSpec.Ctor])
    }

    test("class with only parameterized ctors falls back to its companion apply()") {
      val Right(spec: GenSpec.ObjectMethod) = resolveTop(s"$pkg.GenFixParamWithCompanion")
      assert(spec.method.getName == "apply")
    }

    test("class with only parameterized constructors and no companion gives the forms hint") {
      val Left(msg) = resolveTop(s"$pkg.GenFixArgsOnly")
      assert(msg.contains("no accessible no-argument constructor"))
      assert(msg.contains("apply() or build()"))
      assert(msg.contains("object MyTopForSocle"))
    }

    test("unknown name reports not-found with the accepted forms") {
      val Left(msg) = resolveTop(s"$pkg.DoesNotExist")
      assert(msg.contains("not found on the classpath"))
      assert(msg.contains("Gens.smallSoC"))
    }
  }
}
