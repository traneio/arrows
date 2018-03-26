package arrows.stdlib

import scala.util.Try
import scala.collection.generic.CanBuildFrom
import language.higherKinds
import scala.util.Success
import scala.util.Failure
import scala.concurrent.Future

// type Task[+T] = Arrow[Unit, T] (see package object)
final object Task {

  import ArrowAst._

  val unit: Arrow[Any, Unit] = successful(())

  final def fromFuture[T](f: => Future[T]): Task[T] =
    new FromFuture(f)

  def failed[T](exception: Throwable): Arrow[Any, T] = Failed(exception)

  def successful[T](result: T): Arrow[Any, T] = Successful(result)

  def fromTry[T](result: Try[T]): Task[T] =
    result match {
      case r: Success[_] => successful(r.value)
      case r: Failure[_] => failed(r.exception)
    }

  def apply[T](body: => T): Task[T] =
    Arrow[Unit].map(_ => body)

  def sequence[A, M[X] <: TraversableOnce[X]](in: M[Task[A]])(implicit cbf: CanBuildFrom[M[Task[A]], A, M[A]]): Task[M[A]] = ???

  def firstCompletedOf[T](futures: TraversableOnce[Task[T]]): Task[T] = ???

  @deprecated("use the overloaded version of this method that takes a scala.collection.immutable.Iterable instead", "2.12.0")
  def find[T](@deprecatedName('futurestravonce) futures: TraversableOnce[Task[T]])(@deprecatedName('predicate) p: T => Boolean): Task[Option[T]] = ???

  def find[T](futures: scala.collection.immutable.Iterable[Task[T]])(p: T => Boolean): Task[Option[T]] = ???

  def foldLeft[T, R](futures: scala.collection.immutable.Iterable[Task[T]])(zero: R)(op: (R, T) => R): Task[R] = ???

  @deprecated("use Task.foldLeft instead", "2.12.0")
  def fold[T, R](futures: TraversableOnce[Task[T]])(zero: R)(@deprecatedName('foldFun) op: (R, T) => R): Task[R] = ???

  @deprecated("use Task.reduceLeft instead", "2.12.0")
  def reduce[T, R >: T](futures: TraversableOnce[Task[T]])(op: (R, T) => R): Task[R] = ???

  def reduceLeft[T, R >: T](futures: scala.collection.immutable.Iterable[Task[T]])(op: (R, T) => R): Task[R] = ???

  def traverse[A, B, M[X] <: TraversableOnce[X]](in: M[A])(fn: A => Task[B])(implicit cbf: CanBuildFrom[M[A], B, M[B]]): Task[M[B]] = ???
}
