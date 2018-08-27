package arrows.stdlib

import java.util.concurrent.TimeUnit

import cats.Eq
import cats.data.EitherT
import cats.effect.Async
import cats.instances.all._
import cats.tests.CatsSuite
import org.scalacheck.{ Arbitrary, Cogen, Gen }
import org.scalacheck.Arbitrary.{ arbitrary => getArbitrary }

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ Await, Future }
import arrows.stdlib.Cats._
import cats.effect.laws.discipline.{ AsyncTests, EffectTests }
import cats.kernel.laws.discipline.MonoidTests
import cats.laws.discipline.SemigroupalTests.Isomorphisms
import cats.laws.discipline.{ ArrowChoiceTests, MonadTests }
import cats.effect.laws.discipline.arbitrary._
import cats.effect.laws.util.TestContext
import cats.effect.laws.util.TestInstances._
import cats.mtl.laws.discipline.ApplicativeAskTests

class ArrowLawTests extends CatsSuite {

  implicit val context: TestContext = TestContext()

  implicit def eqArrow[A, B](implicit A: Arbitrary[A], B: Eq[B]): Eq[Arrow[A, B]] = new Eq[Arrow[A, B]] {
    val sampleCnt: Int = 10

    def eqv(f: A Arrow B, g: A Arrow B): Boolean = {
      val samples = List.fill(sampleCnt)(A.arbitrary.sample).collect {
        case Some(a) => a
        case None    => sys.error("Could not generate arbitrary values to compare two Arrows")
      }
      samples.forall(s => eqFuture[B].eqv(f.run(s), g.run(s)))
    }
  }

  implicit def arrowArbitrary[A: Arbitrary, B: Arbitrary: Cogen]: Arbitrary[Arrow[A, B]] =
    Arbitrary(Gen.delay(genArrow[A, B]))

  implicit val nonFatalArbitrary: Arbitrary[Throwable] =
    Arbitrary(Gen.const(new Exception()))

  def genArrow[A: Arbitrary, B: Arbitrary: Cogen]: Gen[Arrow[A, B]] = {
    Gen.frequency(
      5 -> genPure[A, B],
      5 -> genApply[A, B],
      1 -> genFail[A, B],
      5 -> genAsync[A, B],
      5 -> getMapOne[A, B],
      5 -> getMapTwo[A, B],
      10 -> genFlatMap[A, B]
    )
  }

  def genPure[A: Arbitrary, B: Arbitrary]: Gen[Arrow[A, B]] =
    getArbitrary[B].map(Arrow.successful)

  def genApply[A: Arbitrary, B: Arbitrary]: Gen[Arrow[A, B]] =
    getArbitrary[B].map(a => Arrow[A].map(_ => a))

  def genFail[A: Arbitrary, B]: Gen[Arrow[A, B]] =
    getArbitrary[Throwable].map(Arrow.failed)

  def genAsync[A: Arbitrary, B: Arbitrary]: Gen[Arrow[A, B]] =
    getArbitrary[(Either[Throwable, B] => Unit) => Unit].map(Async[Arrow[A, ?]].async)

  def genFlatMap[A: Arbitrary, B: Arbitrary: Cogen]: Gen[Arrow[A, B]] =
    for {
      arr <- getArbitrary[Arrow[A, B]]
      f <- getArbitrary[B => Task[B]]
    } yield arr.flatMap(f)

  def getMapOne[A: Arbitrary, B: Arbitrary: Cogen]: Gen[Arrow[A, B]] =
    for {
      arr <- getArbitrary[Arrow[A, B]]
      f <- getArbitrary[B => B]
    } yield arr.map(f)

  def getMapTwo[A: Arbitrary, B: Arbitrary: Cogen]: Gen[Arrow[A, B]] =
    for {
      arr <- getArbitrary[Arrow[A, B]]
      f1 <- getArbitrary[B => B]
      f2 <- getArbitrary[B => B]
    } yield arr.map(f1).map(f2)

  implicit def arrowCogen[A, B]: Cogen[Arrow[A, B]] =
    Cogen[Unit].contramap(_ => ())

  implicit def arrowIso[A]: Isomorphisms[Arrow[A, ?]] = Isomorphisms.invariant[Arrow[A, ?]]

  implicit def eitherTEq[R: Eq: Arbitrary, A: Eq]: Eq[EitherT[Arrow[R, ?], Throwable, A]] =
    EitherT.catsDataEqForEitherT[Arrow[R, ?], Throwable, A]

  checkAll("ApplicativeAsk[Arrow, String]", ApplicativeAskTests[Arrow[String, ?], String].applicativeAsk[Int])
  checkAll("ArrowChoice[Arrow]", ArrowChoiceTests[Arrow].arrowChoice[Int, Int, Int, String, String, String])
  checkAll("Async[Arrow]", AsyncTests[Arrow[String, ?]].async[Int, Int, Int])
  checkAll("Effect[Task]", EffectTests[Task].effect[Int, Int, Int])
  checkAll("Monoid[Arrow[Int, String]", MonoidTests[Arrow[Int, String]].monoid)
}
