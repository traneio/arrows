package arrows

import com.twitter.util.Duration
import com.twitter.util.Future
import com.twitter.util.FuturePool
import com.twitter.util.Local
import com.twitter.util.Return
import com.twitter.util.Throw
import com.twitter.util.Time
import com.twitter.util.TimeoutException
import com.twitter.util.Timer
import com.twitter.util.Try
import arrows.ArrowRun.Sync
import arrows.ArrowRun._

abstract class Arrow[-T, +U] extends (T => Task[U]) {

  import ArrowAst._

  private[arrows] def runSync[B <: T](s: Sync[B], depth: Int): Result[U]
  private[arrows] final def cast[A, B] = this.asInstanceOf[Arrow[A, B]]

  final def apply(v: T): Task[U] =
    Apply(v, this)

  final def andThen[V](b: Arrow[U, V]): Arrow[T, V] =
    if (this eq Identity)
      b.cast
    else
      AndThen(this, b)

  final def run(value: T): Future[U] =
    ArrowRun(value, this)

  final def run[V <: T](implicit ev: V => Unit): Future[U] =
    ArrowRun((), this.cast)

  final def map[V](f: U => V): Arrow[T, V] =
    this match {
      case a: Exception[_] => a.cast
      case a               => Map(a, f)
    }

  final def flatMap[V](f: U => Task[V]): Arrow[T, V] =
    this match {
      case a: Exception[_] => a.cast
      case a               => FlatMap(a, f)
    }

  final def handle[V >: U](rescueException: PartialFunction[Throwable, V]): Arrow[T, V] =
    this match {
      case a: Value[_] => a.cast
      case a           => Handle(a, rescueException)
    }

  final def unit: Arrow[T, Unit] = map(Arrow.ToUnit)

  final def respond(k: Try[U] => Unit): Arrow[T, U] =
    new Respond[T, U] {
      override final def a = Arrow.this
      override final def resp(t: Try[U]): Unit = k(t)
    }

  final def ensure(f: => Unit): Arrow[T, U] =
    new Respond[T, U] {
      override final def a = Arrow.this
      override final def resp(t: Try[U]): Unit = f
    }

  final def raiseWithin(timeout: Duration)(implicit timer: Timer): Arrow[T, U] =
    raiseWithin(timer, timeout, new TimeoutException(timeout.toString))

  final def raiseWithin(timeout: Duration, exc: Throwable)(implicit timer: Timer): Arrow[T, U] =
    raiseWithin(timer, timeout, exc)

  final def raiseWithin(timer: Timer, timeout: Duration, exc: Throwable): Arrow[T, U] =
    new TransformFuture[T, U, U] {
      override final def a = Arrow.this
      override final def future(fut: Future[U]) = fut.raiseWithin(timer, timeout, exc)
    }

  final def within(timeout: Duration)(implicit timer: Timer): Arrow[T, U] =
    within(timer, timeout)

  final def within(timer: Timer, timeout: Duration): Arrow[T, U] =
    within(timer, timeout, new TimeoutException(timeout.toString))

  final def within(timer: Timer, timeout: Duration, exc: => Throwable): Arrow[T, U] =
    by(timer, timeout.fromNow, exc)

  final def by(when: Time)(implicit timer: Timer): Arrow[T, U] =
    by(timer, when)

  final def by(timer: Timer, when: Time): Arrow[T, U] =
    by(timer, when, new TimeoutException(when.toString))

  final def by(timer: Timer, when: Time, exc: => Throwable): Arrow[T, U] =
    new TransformFuture[T, U, U] {
      override final def a = Arrow.this
      override final def future(fut: Future[U]) = fut.by(timer, when, exc)
    }

  final def delayed(howlong: Duration)(implicit timer: Timer): Arrow[T, U] =
    new TransformFuture[T, U, U] {
      override final def a = Arrow.this
      override final def future(fut: Future[U]) = fut.delayed(howlong)
    }

  final def transform[V](f: Try[U] => Task[V]): Arrow[T, V] =
    new TransformTry[T, U, V] {
      override final def a = Arrow.this
      override final def task(t: Try[U]) = f(t)
    }

  final def before[V](f: Task[V])(implicit ev: U <:< Unit): Arrow[T, V] =
    this match {
      case a: Exception[_] => a.cast
      case a               => a.flatMap(_ => f) // TODO avoid function
    }

  final def rescue[V >: U](
    rescueException: PartialFunction[Throwable, Task[V]]
  ): Arrow[T, V] =
    this match {
      case a: Value[_] => a.cast
      case a =>
        new TransformTry[T, U, V] {
          override final def a = Arrow.this
          override final def task(t: Try[U]) =
            t match {
              case t: Throw[_] =>
                val ex = t.throwable
                val result = rescueException.applyOrElse(ex, Arrow.AlwaysNotApplied)
                if (result eq Arrow.NotApplied) Task.exception(ex) else result.asInstanceOf[Task[V]]
              case t: Return[_] =>
                Task.value(t.r)
            }
        }
    }

  final def foreach(k: U => Unit): Arrow[T, U] = onSuccess(k)

  final def onSuccess(f: U => Unit): Arrow[T, U] =
    this match {
      case a: Exception[_] => a.cast
      case _ =>
        new Respond[T, U] {
          override final def a = Arrow.this
          override final def resp(t: Try[U]): Unit =
            t match {
              case t: Return[_] => f(t())
              case _            =>
            }
        }
    }

  final def filter(p: U => Boolean): Arrow[T, U] =
    map(r => if (p(r)) r else throw new Try.PredicateDoesNotObtain)

  final def withFilter(p: U => Boolean): Arrow[T, U] = filter(p)

  final def onFailure(fn: Throwable => Unit): Arrow[T, U] =
    this match {
      case a: Value[_] => a.cast
      case _ =>
        new Respond[T, U] {
          override final def a = Arrow.this
          override final def resp(t: Try[U]): Unit =
            t match {
              case t: Throw[_] => fn(t.throwable)
              case _           =>
            }
        }
    }

  final def select[V >: U](other: Task[V]): Arrow[T, V] =
    this match {
      case a: Exception[_] => a.cast
      case _ =>
        new JoinTask[T, U, V, V] {
          override final def a = Arrow.this
          override final def p = other
          override final def future(u: Future[U], v: Future[V]) =
            u.select(v)
        }
    }

  final def or[V >: U](other: Task[V]): Arrow[T, V] = select(other)

  final def join[V](other: Task[V]): Arrow[T, (U, V)] =
    this match {
      case a: Exception[_] => a.cast
      case _ =>
        new JoinTask[T, U, V, (U, V)] {
          override final def a = Arrow.this
          override final def p = other
          override final def future(u: Future[U], v: Future[V]) =
            u.join(v)
        }
    }

  final def joinWith[V, X](other: Task[V])(fn: (U, V) => X): Arrow[T, X] =
    this match {
      case a: Exception[_] => a.cast
      case _ =>
        new JoinTask[T, U, V, X] {
          override final def a = Arrow.this
          override final def p = other
          override final def future(u: Future[U], v: Future[V]) =
            u.joinWith(v)(fn)
        }
    }

  final def voided: Arrow[T, Void] = map(Arrow.ToVoid)

  final def flatten[V](implicit ev: U <:< Task[V]): Arrow[T, V] =
    flatMap[V](ev)

  final def mask(pred: PartialFunction[Throwable, Boolean]): Arrow[T, U] =
    new TransformFuture[T, U, U] {
      override final def a = Arrow.this
      override final def future(fut: Future[U]) = fut.mask(pred)
    }

  final def masked: Arrow[T, U] =
    new TransformFuture[T, U, U] {
      override final def a = Arrow.this
      override final def future(fut: Future[U]) = fut.mask(Arrow.AlwaysMasked)
    }

  final def willEqual[V](that: Task[V]): Arrow[T, Boolean] =
    flatMap { v =>
      that.map(_ == v)
    }

  final def liftToTry: Arrow[T, Try[U]] =
    new TransformTry[T, U, Try[U]] {
      override final def a = Arrow.this
      override final def task(t: Try[U]) = Task.value(t)
    }

  final def lowerFromTry[V](implicit ev: U <:< Try[V]): Arrow[T, V] =
    flatMap { r =>
      Task.const(r.asInstanceOf[Try[V]])
    }

  final def interruptible(): Arrow[T, U] =
    new TransformFuture[T, U, U] {
      override final def a = Arrow.this
      override final def future(fut: Future[U]) = fut.interruptible()
    }
}

final object Arrow {

  import ArrowAst._
  import scala.language.implicitConversions

  @deprecated("Use task.run", "")
  implicit final def toFuture[T](t: Task[T]): Future[T] = t.run

  @deprecated("Use Task.fromFuture", "")
  implicit final def fromFuture[T](f: => Future[T]): Task[T] = Task.fromFuture(f)

  final def apply[T]: Arrow[T, T] = Identity

  final def let[T, U, V](l: Local[T], v: T)(wrapped: Arrow[U, V]): Arrow[U, V] =
    new Wrap[U, V] {
      override def arrow = wrapped
      override def wrap(f: => Future[V]): Future[V] =
        l.let(v)(f)
    }

  final def fork[T, U](pool: FuturePool)(t: Arrow[T, U]): Arrow[T, U] =
    new Wrap[T, U] {
      override def arrow = t
      override def wrap(f: => Future[U]) =
        pool(f).flatten
    }

  final def recursive[T, U](r: Arrow[T, U] => Arrow[T, U]): Arrow[T, U] =
    Recursive(r)

  final def value[T](v: T): Arrow[Any, T] = Value(v)

  final def exception[T](ex: Throwable): Arrow[Any, T] = Exception(ex)

  final def collect[T, U](seq: Seq[Arrow[T, U]]): Arrow[T, Seq[U]] = Collect(seq)

  private final val ToUnit: Any => Unit = _ => ()
  private final val ToVoid: Any => Void = _ => (null: Void)

  final object NotApplied
  private[arrows] final val AlwaysNotApplied: Any => AnyRef = _ => NotApplied

  private final val AlwaysMasked: PartialFunction[Throwable, Boolean] = { case _ => true }
}
