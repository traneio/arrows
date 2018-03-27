package arrows.stdlib

import scala.util.Try
import scala.collection.generic.CanBuildFrom
import language.higherKinds
import scala.util.Success
import scala.util.Failure
import scala.concurrent.Future
import scala.concurrent.ExecutionContext

// type Task[+T] = Arrow[Unit, T] (see package object)
final object Task {

  import ArrowImpl._

  val unit: Arrow[Any, Unit] = successful(())

  // TODO optimize
  final val never: Task[Nothing] = fromFuture(Future.never)

  final def fromFuture[T](f: => Future[T]): Task[T] =
    new FromFuture(_ => f)

  final def fromFuture[T](f: ExecutionContext => Future[T]): Task[T] =
    new FromFuture(f)

  final def fork[T](t: Task[T])(implicit ec: ExecutionContext): Task[T] = Arrow.fork(t)

  def failed[T](exception: Throwable): Arrow[Any, T] = Failed(exception)

  def successful[T](result: T): Arrow[Any, T] = Successful(result)

  def fromTry[T](result: Try[T]): Task[T] =
    result match {
      case r: Success[_] => successful(r.value)
      case r: Failure[_] => failed(r.exception)
    }

  def apply[T](body: => T): Task[T] =
    Arrow[Unit].map(_ => body)

  def sequence[A, M[X] <: TraversableOnce[X]](in: M[Task[A]])(
    implicit
    cbf: CanBuildFrom[M[Task[A]], A, M[A]]
  ): Task[M[A]] =
    Arrow.sequence(in)

  // TODO optimize all methods below

  def firstCompletedOf[T](@deprecatedName('futures) tasks: TraversableOnce[Task[T]]): Task[T] =
    Task.fromFuture {
      implicit ec: ExecutionContext =>
        Future.firstCompletedOf(tasks.map(_.run))
    }

  @deprecated("use the overloaded version of this method that takes a scala.collection.immutable.Iterable instead", "2.12.0")
  def find[T](@deprecatedName('futures) tasks: TraversableOnce[Task[T]])(@deprecatedName('predicate) p: T => Boolean): Task[Option[T]] =
    Task.fromFuture {
      implicit ec: ExecutionContext =>
        Future.find(tasks.map(_.run))(p)
    }

  def find[T](@deprecatedName('futures) tasks: scala.collection.immutable.Iterable[Task[T]])(p: T => Boolean): Task[Option[T]] = {
    def searchNext(i: Iterator[Task[T]]): Task[Option[T]] =
      if (!i.hasNext) successful[Option[T]](None)
      else {
        i.next().transformWith {
          case Success(r) if p(r) => successful(Some(r))
          case other              => searchNext(i)
        }
      }
    searchNext(tasks.iterator)
  }

  def foldLeft[T, R](@deprecatedName('futures) tasks: scala.collection.immutable.Iterable[Task[T]])(zero: R)(op: (R, T) => R): Task[R] =
    foldNext(tasks.iterator, zero, op)

  private[this] def foldNext[T, R](i: Iterator[Task[T]], prevValue: R, op: (R, T) => R): Task[R] =
    if (!i.hasNext) successful(prevValue)
    else i.next().flatMap { value => foldNext(i, op(prevValue, value), op) }

  @deprecated("use Task.foldLeft instead", "2.12.0")
  def fold[T, R](@deprecatedName('futures) tasks: TraversableOnce[Task[T]])(zero: R)(@deprecatedName('foldFun) op: (R, T) => R): Task[R] =
    if (tasks.isEmpty) successful(zero)
    else sequence(tasks).map(_.foldLeft(zero)(op))

  @deprecated("use Task.reduceLeft instead", "2.12.0")
  def reduce[T, R >: T](@deprecatedName('futures) tasks: TraversableOnce[Task[T]])(op: (R, T) => R): Task[R] =
    if (tasks.isEmpty) failed(new NoSuchElementException("reduce attempted on empty collection"))
    else sequence(tasks).map(_ reduceLeft op)

  def reduceLeft[T, R >: T](@deprecatedName('futures) tasks: scala.collection.immutable.Iterable[Task[T]])(op: (R, T) => R): Task[R] = {
    val i = tasks.iterator
    if (!i.hasNext) failed(new NoSuchElementException("reduceLeft attempted on empty collection"))
    else i.next() flatMap { v => foldNext(i, v, op) }
  }

  def traverse[A, B, M[X] <: TraversableOnce[X]](in: M[A])(fn: A => Task[B])(implicit cbf: CanBuildFrom[M[A], B, M[B]]): Task[M[B]] =
    in.foldLeft(successful(cbf(in))) {
      (fr, a) => fr.zipWith(fn(a))(_ += _)
    }.map(_.result())
}
