package com.twitter.arrows

import scala.language.experimental.macros
import scala.util.control.NonFatal
import scala.annotation.tailrec
import com.twitter.util.ConstFuture
import com.twitter.util.Return
import com.twitter.util.Duration
import com.twitter.util.Try
import com.twitter.util.Throw
import com.twitter.util.Timer
import com.twitter.util.Future
import com.twitter.util.TimeoutException
import com.twitter.util.Time
import scala.collection.mutable.ArrayBuffer
import com.twitter.util.Future.NextThrewException
import com.twitter.util.Monitor

abstract class Arrow[-T, +U] {

  import Ast._
  import Arrow._

  def run(value: T): Future[U] = Interpreter(value, this)

  def run[V <: T](implicit ev: V => Unit) = Interpreter((), this.asInstanceOf[Arrow[Unit, U]])

  def apply(value: T): Task[U] = Apply(value, this)

  def andThen[V](b: Arrow[U, V]): Arrow[T, V] =
    if (this eq Identity)
      b.asInstanceOf[Arrow[T, V]]
    else if (this.isInstanceOf[Exception[_]])
      this.asInstanceOf[Arrow[T, V]]
    else if (b eq Identity)
      this.asInstanceOf[Arrow[T, V]]
    else if (b.isInstanceOf[Map[_, _, _]]) {
      val map = b.asInstanceOf[Map[U, Any, V]]
      Map(this.andThen(map.a), map.f)
    } else if (b.isInstanceOf[FlatMap[_, _, _]]) {
      val flatMap = b.asInstanceOf[FlatMap[U, Any, V]]
      FlatMap(this.andThen(flatMap.a), flatMap.f)
    } else
      AndThen(this, b)

  def map[V](f: U => V): Arrow[T, V] =
    if (this.isInstanceOf[Exception[_]])
      this.asInstanceOf[Arrow[T, V]]
    else if (this.isInstanceOf[Map[_, _, _]]) {
      val map = this.asInstanceOf[Map[T, Any, U]]
      Map(map.a, map.f.andThen(f))
    } else
      Map(this, f)

  def flatMap[V](f: U => Task[V]): Arrow[T, V] =
    if (this.isInstanceOf[Exception[_]])
      this.asInstanceOf[Arrow[T, V]]
    else if (this.isInstanceOf[Map[_, _, _]]) {
      val map = this.asInstanceOf[Map[T, Any, U]]
      FlatMap(map.a, map.f.andThen(f))
    } else if (this.isInstanceOf[FlatMap[_, _, _]]) {
      val flatMap = this.asInstanceOf[FlatMap[T, Any, U]]
      flatMap.a.flatMap { v =>
        flatMap.f(v).flatMap(f)
      }
    } else
      FlatMap(this, f)

  def unit: Arrow[T, Unit] = map(Arrow.ToUnit)

  def respond(k: Try[U] => Unit): Arrow[T, U] =
    new Transform[T, U, U] {
      override def a = Arrow.this
      override def future(fut: Future[U]) = fut.respond(k)
    }

  def ensure(f: => Unit): Arrow[T, U] =
    new Transform[T, U, U] {
      override def a = Arrow.this
      override def future(fut: Future[U]) = fut.ensure(f)
    }

  def raiseWithin(timeout: Duration)(implicit timer: Timer): Arrow[T, U] =
    raiseWithin(timer, timeout, new TimeoutException(timeout.toString))

  def raiseWithin(timeout: Duration, exc: Throwable)(implicit timer: Timer): Arrow[T, U] =
    raiseWithin(timer, timeout, exc)

  def raiseWithin(timer: Timer, timeout: Duration, exc: Throwable): Arrow[T, U] =
    new Transform[T, U, U] {
      override def a = Arrow.this
      override def future(fut: Future[U]) = fut.raiseWithin(timer, timeout, exc)
    }

  def within(timeout: Duration)(implicit timer: Timer): Arrow[T, U] =
    within(timer, timeout)

  def within(timer: Timer, timeout: Duration): Arrow[T, U] =
    within(timer, timeout, new TimeoutException(timeout.toString))

  def within(timer: Timer, timeout: Duration, exc: => Throwable): Arrow[T, U] =
    by(timer, timeout.fromNow, exc)

  def by(when: Time)(implicit timer: Timer): Arrow[T, U] =
    by(timer, when)

  def by(timer: Timer, when: Time): Arrow[T, U] =
    by(timer, when, new TimeoutException(when.toString))

  def by(timer: Timer, when: Time, exc: => Throwable): Arrow[T, U] =
    new Transform[T, U, U] {
      override def a = Arrow.this
      override def future(fut: Future[U]) = fut.by(timer, when, exc)
    }

  def delayed(howlong: Duration)(implicit timer: Timer): Arrow[T, U] =
    new Transform[T, U, U] {
      override def a = Arrow.this
      override def future(fut: Future[U]) = fut.delayed(howlong)
    }

  def transform[V](f: Try[U] => Task[V]): Arrow[T, V] =
    liftToTry.flatMap(f)

  def before[V](f: Task[V])(implicit ev: U <:< Task[Unit]): Arrow[T, V] =
    flatMap(_ => f)

  def rescue[V >: U](
    rescueException: PartialFunction[Throwable, Task[V]]
  ): Arrow[T, V] =
    transform { t =>
      if (t.isThrow) {
        val ex = t.asInstanceOf[Throw[V]].throwable
        val result = rescueException.applyOrElse(ex, Arrow.AlwaysNotApplied)
        if (result eq Never) Task.exception(ex) else result
      } else Task.value(t.asInstanceOf[Return[V]].r)
    }

  def foreach(k: U => Unit): Arrow[T, U] =
    new Transform[T, U, U] {
      override def a = Arrow.this
      override def future(fut: Future[U]) = fut.foreach(k)
    }

  def filter(p: U => Boolean): Arrow[T, U] =
    map(r => if (p(r)) r else throw new Try.PredicateDoesNotObtain)

  def withFilter(p: U => Boolean): Arrow[T, U] = filter(p)

  def onSuccess(f: U => Unit): Arrow[T, U] =
    map { r =>
      try f(r)
      catch {
        case ex: Throwable => Monitor.handle(ex)
      }
      r
    }

  def onFailure(fn: Throwable => Unit): Arrow[T, U] =
    new Transform[T, U, U] {
      override def a = Arrow.this
      override def future(fut: Future[U]) = fut.onFailure(fn)
    }

  def handle[V >: U](rescueException: PartialFunction[Throwable, V]): Arrow[T, V] =
    new Transform[T, U, V] {
      override def a = Arrow.this
      override def future(fut: Future[U]) = fut.handle(rescueException)
    }

  def select[V >: U](other: Task[V]): Arrow[T, V] =
    new JoinTask[T, U, V, V] {
      override def a = Arrow.this
      override def p = other
      override def future(u: Future[U], v: Future[V]) =
        u.select(v)
    }

  def or[V >: U](other: Task[V]): Arrow[T, V] = select(other)

  def join[V](other: Task[V]): Arrow[T, (U, V)] =
    new JoinTask[T, U, V, (U, V)] {
      override def a = Arrow.this
      override def p = other
      override def future(u: Future[U], v: Future[V]) =
        u.join(v)
    }

  def joinWith[V, X](other: Task[V])(fn: (U, V) => X): Arrow[T, X] =
    new JoinTask[T, U, V, X] {
      override def a = Arrow.this
      override def p = other
      override def future(u: Future[U], v: Future[V]) =
        u.joinWith(v)(fn)
    }

  def voided: Arrow[T, Void] = map(Arrow.ToVoid)

  def flatten[V](implicit ev: U <:< Task[V]): Arrow[T, V] =
    flatMap[V](ev)

  def mask(pred: PartialFunction[Throwable, Boolean]): Arrow[T, U] =
    new Transform[T, U, U] {
      override def a = Arrow.this
      override def future(fut: Future[U]) = fut.mask(pred)
    }

  def masked: Arrow[T, U] = mask(Arrow.AlwaysMasked)

  def willEqual[V](that: Task[V]): Arrow[T, Boolean] =
    flatMap { v =>
      that.map(_ == v)
    }

  def liftToTry: Arrow[T, Try[U]] =
    new Transform[T, U, Try[U]] {
      override def a = Arrow.this
      override def future(fut: Future[U]) = fut.liftToTry
    }

  def lowerFromTry[V](implicit ev: U <:< Try[V]): Arrow[T, V] =
    new Transform[T, U, V] {
      override def a = Arrow.this
      override def future(fut: Future[U]) = fut.lowerFromTry
    }

  def interruptible(): Arrow[T, U] =
    new Transform[T, U, U] {
      override def a = Arrow.this
      override def future(fut: Future[U]) = fut.interruptible()
    }
}

object Arrow {

  import language.implicitConversions

  @deprecated("Use task.run", "")
  implicit def toFuture[T](t: Task[T]): Future[T] = t.run

  @deprecated("Use Task.fromFuture", "")
  implicit def fromFuture[T](f: => Future[T]): Task[T] = Task.fromFuture(f)
  
  import Ast._

  def recursive[T, U](rec: Arrow[T, U] => Arrow[T, U]): Arrow[T, U] =
    Recursive(rec).f

  def apply[T]: Arrow[T, T] = Identity

  private val ToUnit: Any => Unit = _ => ()
  private val ToVoid: Any => Void = _ => (null: Void)
  private val AlwaysNotApplied: Any => Task[Nothing] = scala.Function.const(Never)
  private val AlwaysMasked: PartialFunction[Throwable, Boolean] = { case _ => true }
}
