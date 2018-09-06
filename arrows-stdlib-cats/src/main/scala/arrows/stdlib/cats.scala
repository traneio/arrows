package arrows.stdlib

import cats.{ Applicative, StackSafeMonad }
import cats.effect._
import cats.arrow.ArrowChoice
import cats.kernel.{ Monoid, Semigroup }
import cats.mtl.ApplicativeAsk

import scala.concurrent.{ ExecutionContext, Promise }
import scala.util.control.NonFatal
import scala.util.{ Failure, Success }

object Cats extends ArrowInstances

sealed abstract class ArrowInstances extends ArrowInstances1 {

  private final val toLeftInstance: Any => Left[Any, Nothing] = Left(_)
  private final def toLeftI[T] = toLeftInstance.asInstanceOf[T => Left[T, Nothing]]

  private final val toRightInstance: Any => Right[Nothing, Any] = Right(_)
  private final def toRightI[T] = toRightInstance.asInstanceOf[T => Right[Nothing, T]]

  private final val toUnit: Any => Unit = _ => ()

  implicit def catsEffectForTask(implicit ec: ExecutionContext): Effect[Task] = new ArrowAsync[Unit] with Effect[Task] {
    def runAsync[A](fa: Task[A])(cb: Either[Throwable, A] => IO[Unit]): SyncIO[Unit] =
      SyncIO(fa.run(())(ec).onComplete(t => cb(t.toEither).unsafeRunAsync(toUnit)))

    override def flatMap[A, B](fa: Task[A])(f: A => Task[B]): Task[B] =
      fa.flatMap(f)
  }

  implicit val catsArrowChoiceForArrow: ArrowChoice[Arrow] = new ArrowChoice[Arrow] {
    def choose[A, B, C, D](f: Arrow[A, C])(g: Arrow[B, D]): Arrow[Either[A, B], Either[C, D]] =
      Arrow[Either[A, B]].flatMap { eab =>
        if (eab.isLeft) f(eab.left.get).map(toLeftI[C])
        else g(eab.right.get).map(toRightI[D])
      }

    def lift[A, B](f: A => B): Arrow[A, B] = Arrow[A].map(f)

    def first[A, B, C](fa: Arrow[A, B]): Arrow[(A, C), (B, C)] =
      Arrow[(A, C)].flatMap { case (a, c) => fa(a).map(_ -> c) }

    def compose[A, B, C](f: Arrow[B, C], g: Arrow[A, B]): Arrow[A, C] = g.andThen(f)
  }
  private final val catsApplicativeAskForArrowInstance: ApplicativeAsk[Arrow[Any, ?], Any] =
    new ApplicativeAsk[Arrow[Any, ?], Any] {
      val applicative: Applicative[Arrow[Any, ?]] = catsAsyncForArrow[Any]

      def ask: Arrow[Any, Any] = Arrow[Any]

      def reader[A](f: Any => A): Arrow[Any, A] = ask.map(f)
    }

  implicit def catsApplicativeAskForArrow[E] =
    catsApplicativeAskForArrowInstance.asInstanceOf[ApplicativeAsk[Arrow[E, ?], E]]

  implicit def catsMonoidForArrow[A, B](implicit B0: Monoid[B]): Monoid[Arrow[A, B]] =
    new Monoid[Arrow[A, B]] with ArrowSemigroup[A, B] {
      implicit def B: Semigroup[B] = B0

      def empty: Arrow[A, B] = Arrow.successful(Monoid[B].empty)
    }
}

sealed abstract class ArrowInstances1 {

  implicit def catsAsyncForArrow[E]: Async[Arrow[E, ?]] = new ArrowAsync[E] {}

  implicit def catsSemigroupForArrow[A, B](implicit B0: Semigroup[B]): Semigroup[Arrow[A, B]] = new ArrowSemigroup[A, B] {
    implicit def B: Semigroup[B] = B0
  }
}

trait ArrowAsync[E] extends StackSafeMonad[Arrow[E, ?]] with Async[Arrow[E, ?]] {
  def flatMap[A, B](fa: Arrow[E, A])(f: A => Arrow[E, B]): Arrow[E, B] =
    Arrow[E].flatMap(e => fa(e).flatMap(a => f(a)(e)))

  def raiseError[A](e: Throwable): Arrow[E, A] = Arrow.failed[A](e)

  def handleErrorWith[A](fa: Arrow[E, A])(f: Throwable => Arrow[E, A]): Arrow[E, A] =
    Arrow[E].flatMap(e => fa.recoverWith { case t: Throwable => f(t)(e) }(e))

  def pure[A](x: A): Arrow[E, A] = Arrow.successful(x)

  def suspend[A](thunk: => Arrow[E, A]): Arrow[E, A] =
    Arrow[E].flatMap(e => try { thunk(e) } catch { case NonFatal(t) => Arrow.failed[A](t)(e) })

  override def delay[A](thunk: => A): Arrow[E, A] =
    Arrow[E].map(_ => thunk)

  def bracketCase[A, B](acquire: Arrow[E, A])(use: A => Arrow[E, B])(release: (A, ExitCase[Throwable]) => Arrow[E, Unit]): Arrow[E, B] = Arrow[E].flatMap(e =>
    acquire.flatMap(a => use(a).transformWith {
      case Success(b) => release(a, ExitCase.complete)(e).map(_ => b)
      case Failure(t) => release(a, ExitCase.error(t))(e).flatMap(_ => Task.failed[B](t))
    }(e))(e))

  def async[A](k: (Either[Throwable, A] => Unit) => Unit): Arrow[E, A] = Arrow[E].flatMap { _ =>
    val promise = Promise[A]

    k {
      case Left(t)  => promise.failure(t)
      case Right(a) => promise.success(a)
    }

    Task.async(promise.future)
  }

  def asyncF[A](k: (Either[Throwable, A] => Unit) => Arrow[E, Unit]): Arrow[E, A] = Arrow[E].flatMap { e =>
    val promise = Promise[A]

    k {
      case Left(t)  => promise.failure(t)
      case Right(a) => promise.success(a)
    }.flatMap(_ => Task.async(promise.future))(e)
  }

  override def map[A, B](fa: Arrow[E, A])(f: A => B): Arrow[E, B] = fa.map(f)
}

trait ArrowSemigroup[A, B] extends Semigroup[Arrow[A, B]] {
  implicit def B: Semigroup[B]

  def combine(x: Arrow[A, B], y: Arrow[A, B]): Arrow[A, B] =
    Arrow[A].flatMap(a => x.flatMap(b => y(a).map(b2 => B.combine(b, b2)))(a))
}