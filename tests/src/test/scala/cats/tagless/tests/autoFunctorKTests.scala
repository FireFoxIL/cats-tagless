/*
 * Copyright 2019 cats-tagless maintainers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cats.tagless
package tests

import cats.{Monad, Show, ~>}
import cats.arrow.FunctionK
import cats.data.Cokleisli
import cats.free.Free
import cats.laws.discipline.SerializableTests
import cats.tagless.laws.discipline.FunctorKTests

import scala.annotation.nowarn
import scala.util.Try

class autoFunctorKTests extends CatsTaglessTestSuite {
  import autoFunctorKTests.*

  checkAll("FunctorK[SafeAlg]", FunctorKTests[SafeAlg].functorK[Try, Option, List, Int])
  checkAll("FunctorK is Serializable", SerializableTests.serializable(FunctorK[SafeAlg]))

  test("simple mapK") {
    val optionParse: SafeAlg[Option] = Interpreters.tryInterpreter.mapK(fk)
    assertEquals(optionParse.parseInt("3"), Some(3))
    assertEquals(optionParse.parseInt("sd"), None)
    assertEquals(optionParse.divide(3f, 3f), Some(1f))
  }

  test("simple instance summon with autoDeriveFromFunctorK on") {
    implicit val listParse: SafeAlg[List] = Interpreters.tryInterpreter.mapK(λ[Try ~> List](_.toList))
    assertEquals(SafeAlg[List].parseInt("3"), List(3))
  }

  test("auto derive from functor k") {
    import SafeAlg.autoDerive.*
    import Interpreters.tryInterpreter
    SafeAlg[Option]
  }

  test("Alg with non effect method") {
    val tryInt = new AlgWithNonEffectMethod[Try] {
      def a(i: Int): Try[Int] = Try(i)
      def b(i: Int): Int = i
    }

    assertEquals(tryInt.mapK(fk).a(3), Some(3))
    assertEquals(tryInt.mapK(fk).b(2), 2)
  }

  test("Alg with non effect method with default Impl") {
    val tryInt = new AlgWithDefaultImpl[Try] {
      def plusOne(i: Int): Try[Int] = Try(i + 1)
    }

    assertEquals(tryInt.mapK(fk).plusOne(3), Some(4))
    assertEquals(tryInt.mapK(fk).minusOne(2), 1)
  }

  test("Alg with extra type parameters") {
    implicit val foo: AlgWithExtraTP[Try, String] = new AlgWithExtraTP[Try, String] {
      def a(i: Int) = Try(i.toString)
    }

    import AlgWithExtraTP.autoDerive.*
    assertEquals(AlgWithExtraTP[Option, String].a(5), Some("5"))
  }

  test("Alg with extra type parameters before effect type") {
    implicit val algWithExtraTP: AlgWithExtraTP2[String, Try] = new AlgWithExtraTP2[String, Try] {
      def a(i: Int) = Try(i.toString)
    }

    assertEquals(algWithExtraTP.mapK(fk).a(5), Some("5"))
  }

  test("Alg with type member") {
    implicit val tryInt: AlgWithTypeMember.Aux[Try, String] = new AlgWithTypeMember[Try] {
      type T = String
      def a(i: Int): Try[String] = Try(i.toString)
    }

    assertEquals[Option[Any], Option[Any]](tryInt.mapK(fk).a(3), Some("3"))
    import AlgWithTypeMember.fullyRefined.autoDerive.*
    val op: AlgWithTypeMember.Aux[Option, String] = implicitly
    assertEquals(op.a(3), Option("3"))
  }

  test("Alg with type bound") {
    import AlgWithTypeBound.*
    implicit val tryB: AlgWithTypeBound.Aux[Try, B.type] = new AlgWithTypeBound[Try] {
      type T = B.type
      override def t = Try(B)
    }

    assertEquals[Option[A], Option[A]](tryB.mapK(fk).t, Option(B))
    import AlgWithTypeBound.fullyRefined.autoDerive.*
    val op: AlgWithTypeBound.Aux[Option, B.type] = implicitly
    assertEquals(op.t, Option(B))
  }

  test("Stack safety with Free") {
    val incTry: Increment[Try] = new Increment[Try] {
      def plusOne(i: Int) = Try(i + 1)
    }

    val incFree = incTry.mapK(λ[Try ~> Free[Try, *]](t => Free.liftF(t)))

    def a(i: Int): Free[Try, Int] = for {
      j <- incFree.plusOne(i)
      z <- if (j < 10000) a(j) else Free.pure[Try, Int](j)
    } yield z
    assertEquals(a(0).foldMap(FunctionK.id), util.Success(10000))
  }

  test("turn off auto derivation") {
    @nowarn("cat=unused")
    implicit object foo extends AlgWithoutAutoDerivation[Try] {
      def a(i: Int): Try[Int] = util.Success(i)
    }

    assertNoDiff(
      compileErrors("AlgWithoutAutoDerivation.autoDerive"),
      """|error: value autoDerive is not a member of object cats.tagless.tests.autoFunctorKTests.AlgWithoutAutoDerivation
         |AlgWithoutAutoDerivation.autoDerive
         |                         ^
         |""".stripMargin
    )
  }

  test("defs with no params") {
    implicit object foo extends AlgWithDef[Try] {
      def a = Try(1)
    }

    assertEquals(foo.mapK(fk).a, Some(1))
  }

  test("method with type params") {
    implicit object foo extends AlgWithTParamInMethod[Try] {
      def a[T](t: T): Try[String] = Try(t.toString)
    }

    assertEquals(foo.mapK(fk).a(32), Some("32"))
  }

  test("auto deriviation with existing derivation") {
    AlgWithOwnDerivation[Option]
  }

  test("alg with abstract type class fully refined resolve instance") {
    implicit object foo extends AlgWithAbstractTypeClass[Try] {
      type TC[T] = Show[T]
      def a[T: TC](t: T): Try[String] = Try(t.show)
    }

    import AlgWithAbstractTypeClass.fullyRefined.*
    // Scalac needs help when abstract type is high order.
    implicit val fShow: FunctorK[AlgWithAbstractTypeClass.Aux[*[_], Show]] =
      functorKForFullyRefinedAlgWithAbstractTypeClass[Show]
    assertEquals(fShow.mapK(foo)(fk).a(true), Some("true"))
  }

  test("alg with abstract type class") {
    implicit object foo extends AlgWithAbstractTypeClass[Try] {
      type TC[T] = Show[T]
      def a[T: TC](t: T): Try[String] = Try(t.show)
    }

    assertEquals(AlgWithAbstractTypeClass.mapK(foo)(fk).a(true), Some("true"))
  }

  test("alg with default parameter") {
    implicit object foo extends AlgWithDefaultParameter[Try] {
      def greet(name: String) = Try(s"Hello $name")
    }

    val bar = AlgWithDefaultParameter.mapK(foo)(fk)
    assertEquals(bar.greet(), Some("Hello World"))
    assertEquals(bar.greet("John Doe"), Some("Hello John Doe"))
  }

  test("alg with final method") {
    implicit object foo extends AlgWithFinalMethod[Try] {
      def log(msg: String) = Try(msg)
    }

    val bar = AlgWithFinalMethod.mapK(foo)(fk)
    assertEquals(bar.info("green"), Some("[info] green"))
    assertEquals(bar.warn("yellow"), Some("[warn] yellow"))
  }

  test("alg with by-name parameter") {
    implicit object foo extends AlgWithByNameParameter[Try] {
      def log(msg: => String) = Try(msg)
    }

    val bar = AlgWithByNameParameter.mapK(foo)(fk)
    assertEquals(bar.log("level".reverse), Some("level"))
  }

  test("builder-style algebra") {
    val listBuilder: BuilderAlgebra[List] = BuilderAlgebra.Named("foo")
    val optionBuilder = listBuilder.mapK[Option](λ[List ~> Option](_.headOption))
    assertEquals(optionBuilder.withFoo("bar").unit, Some(()))
  }
}

object autoFunctorKTests {
  implicit val fk: Try ~> Option = λ[Try ~> Option](_.toOption)

  @autoFunctorK
  trait AlgWithNonEffectMethod[F[_]] {
    def a(i: Int): F[Int]
    def b(i: Int): Int
  }

  @autoFunctorK @finalAlg
  trait AlgWithTypeMember[F[_]] {
    type T
    def a(i: Int): F[T]
  }

  object AlgWithTypeMember {
    type Aux[F[_], T0] = AlgWithTypeMember[F] { type T = T0 }
    Derive.functorK[AlgWithTypeMember { type T = Int }]
  }

  @autoFunctorK
  trait AlgWithTypeBound[F[_]] {
    type T <: AlgWithTypeBound.A
    def t: F[T]
  }

  object AlgWithTypeBound {
    sealed abstract class A
    case object B extends A
    case object C extends A
    type Aux[F[_], T0 <: A] = AlgWithTypeBound[F] { type T = T0 }
  }

  @autoFunctorK @finalAlg
  trait AlgWithExtraTP[F[_], T] {
    def a(i: Int): F[T]
  }

  @autoFunctorK @finalAlg
  trait AlgWithExtraTP2[T, F[_]] {
    def a(i: Int): F[T]
  }

  @autoFunctorK
  trait Increment[F[_]] {
    def plusOne(i: Int): F[Int]
  }

  @autoFunctorK(autoDerivation = false)
  trait AlgWithoutAutoDerivation[F[_]] {
    def a(i: Int): F[Int]
  }

  @autoFunctorK
  trait AlgWithDefaultImpl[F[_]] {
    def plusOne(i: Int): F[Int]
    def minusOne(i: Int): Int = i - 1
  }

  @autoFunctorK @finalAlg
  trait AlgWithDef[F[_]] {
    def a: F[Int]
  }

  @autoFunctorK @finalAlg
  trait AlgWithTParamInMethod[F[_]] {
    def a[T](t: T): F[String]
  }

  @autoFunctorK @finalAlg
  trait AlgWithContextBounds[F[_]] {
    def a[T: Show](t: Int): F[String]
  }

  @autoFunctorK @finalAlg
  trait AlgWithAbstractTypeClass[F[_]] {
    type TC[T]
    def a[T: TC](t: T): F[String]
  }

  object AlgWithAbstractTypeClass {
    type Aux[F[_], TC0[_]] = AlgWithAbstractTypeClass[F] { type TC[T] = TC0[T] }
    Derive.functorK[AlgWithAbstractTypeClass { type TC[T] = List[T] }]
  }

  @autoFunctorK @finalAlg
  trait AlgWithCurryMethod[F[_]] {
    def a(t: Int)(b: String): F[String]
  }

  @autoFunctorK @finalAlg
  trait AlgWithOwnDerivation[F[_]] {
    def a(b: Int): F[String]
  }

  object AlgWithOwnDerivation {
    implicit def fromMonad[F[_]: Monad]: AlgWithOwnDerivation[F] = new AlgWithOwnDerivation[F] {
      def a(b: Int): F[String] = Monad[F].pure(b.toString)
    }
  }

  @autoFunctorK
  trait AlgWithDefaultParameter[F[_]] {
    def greet(name: String = "World"): F[String]
  }

  @autoFunctorK
  trait AlgWithFinalMethod[F[_]] {
    def log(msg: String): F[String]
    final def info(msg: String): F[String] = log(s"[info] $msg")
    final def warn(msg: String): F[String] = log(s"[warn] $msg")
  }

  @autoFunctorK
  trait AlgWithByNameParameter[F[_]] {
    def log(msg: => String): F[String]
  }

  @autoFunctorK
  trait AlgWithVarArgsParameter[F[_]] {
    def sum(xs: Int*): Int
    def fSum(xs: Int*): F[Int]
  }

  trait BuilderAlgebra[F[_]] {
    def unit: F[Unit]
    def withFoo(foo: String): BuilderAlgebra[F]
  }

  object BuilderAlgebra {
    implicit val functorK: FunctorK[BuilderAlgebra] = Derive.functorK

    final case class Named(name: String) extends BuilderAlgebra[List] {
      val unit: List[Unit] = List.fill(5)(())
      def withFoo(foo: String): BuilderAlgebra[List] = copy(name = foo)
    }
  }

  @autoFunctorK
  trait AlgWithContravariantK[F[_]] {
    def app(f: Cokleisli[F, String, Int])(x: String): F[Int]
  }
}
