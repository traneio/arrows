package arrows.twitter

import com.twitter.util._
import ArrowRun.Sync
import ArrowRun._
import java.util.concurrent.atomic.AtomicReference

/**
 *  An `Arrow` represents a computation that can be reused by multiple executions.
 *  It's a function that can be composed using methods similar to `Future`. For example:
 *
 *  {{{
 *  // regular future composition
 *  def callAService(i: Int): Future[Int] = ???
 *  def myTransformation(i: Int): Future[Int] = callServiceA(i).map(_ + 1)
 *  val result: Future[Int] = myTransformation(1)
 *
 *  // using arrows
 *  val callServiceA: Arrow[Int, Int] = ???
 *  val myTransformation: Arrow[Int, Int] = callServiceA.map(_ + 1)
 *  val result: Future[Int] = myTransformation.run(1)
 *  }}}
 *
 *  Note that the arrows can be created once and reused for multiple `run` invocations
 *
 *  Arrow also has a specialization called `Task` that provides an interface that allows
 *  users to express computations that don't take inputs:
 *
 *  {{{
 *  // task composition
 *  def callAService(i: Int): Task[Int] = ???
 *  def myTransformation(i: Int): Task[Int] = callServiceA(i).map(_ + 1)
 *  val result: Future[Int] = myTransformation(1).run
 *  }}}
 *
 *  It's similar to `Future` compositions, but the execution is lazy. The `Task` creation
 *  only creates a computation that is executed once `run` is called.
 *
 *  `Arrow.apply` returns an identity arrow that is a convenient way to create arrows:
 *
 *  {{{
 *  val myArrow: Arrow[Int, Int] = Arrow[Int].map(_ + 1)
 *  }}}
 *
 *  `arrow.apply` can be used to produce `Task`s. It's useful to express computations that vary
 *  their structure based on input values:
 *
 *  {{{
 *  val someArrow: Arrow[Int, Int] = ???
 *  Arrow[Int].flatMap {
 *  	case 0 => 0
 *  	case i => someArrow(a)
 *  }
 *  }}}
 */
abstract class Arrow[-T, +U] extends (T => Task[U]) {

  import ArrowImpl._

  private[arrows] def runSync[B <: T](s: Sync[B], depth: Int): Result[U]
  private[arrows] final def cast[A, B] = this.asInstanceOf[Arrow[A, B]]

  /**
   * Creates a `Task` that produces the result of
   * this arrow when applied to `v`.
   */
  final def apply(v: T): Task[U] =
    Apply(v, this)

  /**
   * Transforms the result of the arrow by
   * applying `a`.
   */
  final def andThen[V](b: Arrow[U, V]): Arrow[T, V] =
    if (this eq Identity)
      b.cast
    else
      AndThen(this, b)

  /**
   * Executes the `Arrow` using the provided input and
   * execution context
   */
  final def run(value: T): Future[U] =
    ArrowRun(value, this)

  /**
   * Executes the `Task` using the provided  execution context
   */
  final def run[V <: T](implicit ev: V => Unit): Future[U] =
    ArrowRun((), this.cast)

  /**
   * Memoizes a Task
   */
  final def memoize[V <: T](implicit ev: V => Unit): Task[U] = {
    val ref = new AtomicReference[Task[U]]
    Task.Unit.flatMap { _ =>
      val curr = ref.get
      if (curr != null)
        curr
      else
        this.asInstanceOf[Task[U]].flatMap { result =>
          val t = Task.value(result)
          ref.compareAndSet(null, t)
          t
        }
    }
  }

  /**
   * If this, the original computation, succeeds, run `f` on the result.
   *
   * The returned result is a Task that is satisfied when the original computation
   * and the callback, `f`, are done.
   * If the original computation fails, this one will also fail, without executing `f`
   * and preserving the failed computation of `this`.
   *
   * @see [[flatMap]] for computations that return `Task`s.
   * @see [[onSuccess]] for side-effecting chained computations.
   */
  final def map[V](f: U => V): Arrow[T, V] =
    this match {
      case a: Exception[_] => a.cast
      case a               => Map(a, f)
    }

  /**
   * If this, the original computation, succeeds, run `f` on the result.
   *
   * The returned result is a Task that is satisfied when the original computation
   * and the callback, `f`, are done.
   * If the original computation fails, this one will also fail, without executing `f`
   * and preserving the failed computation of `this`.
   *
   * @see [[map]]
   */
  final def flatMap[V](f: U => Task[V]): Arrow[T, V] =
    this match {
      case a: Exception[_] => a.cast
      case a               => FlatMap(a, f)
    }

  /**
   * If this, the original computation, results in an exceptional computation,
   * `rescueException` may convert the failure into a new result.
   *
   * The returned result is a `Task` that is satisfied when the original
   * computation and the callback, `rescueException`, are done.
   *
   * This is the equivalent of [[map]] for failed computations.
   *
   * @see [[rescue]]
   */
  final def handle[V >: U](rescueException: PartialFunction[Throwable, V]): Arrow[T, V] =
    this match {
      case a: Value[_] => a.cast
      case a           => Handle(a, rescueException)
    }

  /**
   * Convert this `Task[A]` to a `Task[Unit]` by discarding the result.
   *
   * @note failed computations will remain as is.
   */
  final def unit: Arrow[T, Unit] = map(Arrow.ToUnit)

  /**
   * When the computation completes, invoke the given callback
   * function.
   *
   * The returned `Task` will be satisfied when this,
   * the original computation, is done.
   *
   * This method is most useful for very generic code (like
   * libraries). Otherwise, it is a best practice to use one of the
   * alternatives ([[onSuccess]], [[onFailure]], etc.).
   *
   * @note this should be used for side-effects.
   *
   * @param k the side-effect to apply when the computation completes.
   *          The value of the input to `k` will be the result of the
   *          computation to this computation.
   * @return a chained Task[A]
   * @see [[transform]] to produce a new `Task` from the result of
   *     the computation.
   * @see [[ensure]] if you are not interested in the result of
   *     the computation.
   * @see [[addEventListener]] for a Java friendly API.
   */
  final def respond(k: Try[U] => Unit): Arrow[T, U] =
    new Respond[T, U] {
      override final def a = Arrow.this
      override final def resp(t: Try[U]): Unit = k(t)
    }

  /**
   * Invoked regardless of whether the computation completed successfully or unsuccessfully.
   * Implemented in terms of [[respond]] so that subclasses control evaluation order. Returns a
   * chained Task.
   *
   * The returned `Task` will be satisfied when this,
   * the original computation, is done.
   *
   * @note this should be used for side-effects.
   *
   * @param f the side-effect to apply when the computation completes.
   * @see [[respond]] if you need the result of the computation for
   *     usage in the side-effect.
   */
  final def ensure(f: => Unit): Arrow[T, U] =
    new Respond[T, U] {
      override final def a = Arrow.this
      override final def resp(t: Try[U]): Unit = f
    }

  /**
   * Returns a new Task that fails if this Task does not return in time.
   *
   * Same as the other `raiseWithin`, but with an implicit timer. Sometimes this is more convenient.
   *
   * ''Note'': On timeout, the underlying computation is interrupted.
   */
  final def raiseWithin(timeout: Duration)(implicit timer: Timer): Arrow[T, U] =
    raiseWithin(timer, timeout, new TimeoutException(timeout.toString))

  /**
   * Returns a new Task that fails if this Task does not return in time.
   *
   * Same as the other `raiseWithin`, but with an implicit timer. Sometimes this is more convenient.
   *
   * ''Note'': On timeout, the underlying computation is interrupted.
   */
  final def raiseWithin(timeout: Duration, exc: Throwable)(implicit timer: Timer): Arrow[T, U] =
    raiseWithin(timer, timeout, exc)

  /**
   * Returns a new Task that fails if this Task does not return in time.
   *
   * ''Note'': On timeout, the underlying computation is interrupted.
   */
  final def raiseWithin(timer: Timer, timeout: Duration, exc: Throwable): Arrow[T, U] =
    new TransformFuture[T, U, U] {
      override final def a = Arrow.this
      override final def future(fut: Future[U]) = fut.raiseWithin(timer, timeout, exc)
    }

  /**
   * Returns a new Task that fails if it is not satisfied in time.
   *
   * Same as the other `within`, but with an implicit timer. Sometimes this is more convenient.
   *
   * ''Note'': On timeout, the underlying computation is not interrupted.
   */
  final def within(timeout: Duration)(implicit timer: Timer): Arrow[T, U] =
    within(timer, timeout)

  /**
   * Returns a new Task that fails if it is not satisfied in time.
   *
   * ''Note'': On timeout, the underlying computation is not interrupted.
   */
  final def within(timer: Timer, timeout: Duration): Arrow[T, U] =
    within(timer, timeout, new TimeoutException(timeout.toString))

  /**
   * Returns a new Task that fails if it is not satisfied in time.
   *
   * ''Note'': On timeout, the underlying computation is not interrupted.
   *
   * @param timer to run timeout on.
   * @param timeout indicates how long you are willing to wait for the result to be available.
   * @param exc exception to throw.
   */
  final def within(timer: Timer, timeout: Duration, exc: => Throwable): Arrow[T, U] =
    by(timer, timeout.fromNow, exc)

  /**
   * Returns a new Task that fails if it is not satisfied before the given time.
   *
   * Same as the other `by`, but with an implicit timer. Sometimes this is more convenient.
   *
   * ''Note'': On timeout, the underlying computation is not interrupted.
   */
  final def by(when: Time)(implicit timer: Timer): Arrow[T, U] =
    by(timer, when)

  /**
   * Returns a new Task that fails if it is not satisfied before the given time.
   *
   * ''Note'': On timeout, the underlying computation is not interrupted.
   */
  final def by(timer: Timer, when: Time): Arrow[T, U] =
    by(timer, when, new TimeoutException(when.toString))

  /**
   * Returns a new Task that fails if it is not satisfied before the given time.
   *
   * ''Note'': On timeout, the underlying computation is not interrupted.
   *
   * ''Note'': Interrupting a returned computation would not prevent it from being satisfied
   *           with a given exception (when the time comes).
   *
   * @param timer to run timeout on.
   * @param when indicates when to stop waiting for the result to be available.
   * @param exc exception to throw.
   */
  final def by(timer: Timer, when: Time, exc: => Throwable): Arrow[T, U] =
    new TransformFuture[T, U, U] {
      override final def a = Arrow.this
      override final def future(fut: Future[U]) = fut.by(timer, when, exc)
    }

  /**
   * Delay the completion of this Task for at least `howlong` from now.
   *
   * ''Note'': Interrupting a returned computation would not prevent it from becoming this computation
   *           (when the time comes).
   */
  final def delayed(howlong: Duration)(implicit timer: Timer): Arrow[T, U] =
    new TransformFuture[T, U, U] {
      override final def a = Arrow.this
      override final def future(fut: Future[U]) = fut.delayed(howlong)
    }

  /**
   * When this computation completes, run `f` on that completed result
   * whether or not this computation was successful.
   *
   * The returned `Task` will be satisfied when this,
   * the original computation, and `f` are done.
   *
   * @see [[respond]] for purely side-effecting callbacks.
   * @see [[map]] and [[flatMap]] for dealing strictly with successful
   *     computations.
   * @see [[handle]] and [[rescue]] for dealing strictly with exceptional
   *     computations.
   * @see [[transformedBy]] for a Java friendly API.
   */
  final def transform[V](f: Try[U] => Task[V]): Arrow[T, V] =
    new TransformTry[T, U, V] {
      override final def a = Arrow.this
      override final def task(t: Try[U]) = f(t)
    }

  /**
   * Sequentially compose `this` with `f`. This is as [[flatMap]], but
   * discards the result of `this`. Note that this applies only
   * `Unit`-valued  Futures — i.e. side-effects.
   */
  final def before[V](f: Task[V])(implicit ev: U <:< Unit): Arrow[T, V] =
    this match {
      case a: Exception[_] => a.cast
      case a               => a.flatMap(_ => f) // TODO avoid function
    }

  /**
   * If this, the original computation, results in an exceptional computation,
   * `rescueException` may convert the failure into a new result.
   *
   * The returned result is a `Task` that is satisfied when the original
   * computation and the callback, `rescueException`, are done.
   *
   * This is the equivalent of [[flatMap]] for failed computations.
   *
   * @see [[handle]]
   */
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

  /**
   * Invoke the callback only if the Task returns successfully. Useful for Scala `for`
   * comprehensions. Use [[onSuccess]] instead of this method for more readable code.
   *
   * @see [[onSuccess]]
   */
  final def foreach(k: U => Unit): Arrow[T, U] = onSuccess(k)

  /**
   * Invoke the function on the result, if the computation was
   * successful.  Returns a chained Task as in `respond`.
   *
   * @note this should be used for side-effects.
   *
   * @return chained Task
   * @see [[flatMap]] and [[map]] to produce a new `Task` from the result of
   *     the computation.
   */
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

  /**
   * Invoke the function on the error, if the computation was
   * unsuccessful.  Returns a chained Task as in `respond`.
   *
   * @note this should be used for side-effects.
   *
   * @note if `fn` is a `PartialFunction` and the input is not defined for a given
   *       Throwable, the resulting `MatchError` will propagate to the current
   *       `Monitor`. This will happen if you use a construct such as
   *       `computation.onFailure { case NonFatal(e) => ... }` when the Throwable
   *       is "fatal".
   *
   * @return chained Task
   * @see [[handle]] and [[rescue]] to produce a new `Task` from the result of
   *     the computation.
   */
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

  /**
   * Choose the first Task to be satisfied.
   *
   * @param other another Task
   * @return a new Task whose result is that of the first of this and other to return
   */
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

  /**
   * A synonym for [[select]]: Choose the first `Task` to be satisfied.
   */
  final def or[V >: U](other: Task[V]): Arrow[T, V] = select(other)

  /**
   * Joins this computation with a given `other` computation into a `Task[(A, B)]`
   * (computation of a `Tuple2`). If this or `other` computation fails, the returned `Task`
   * is immediately satisfied by that failure.
   */
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

  /**
   * Joins this computation with a given `other` computation and applies `fn` to its result.
   * If this or `other` computation fails, the returned `Task` is immediately satisfied
   * by that failure.
   *
   * Before (using [[join]]):
   * {{{
   *   val ab = a.join(b).map { case (a, b) => Foo(a, b) }
   * }}}
   *
   * After (using [[joinWith]]):
   * {{{
   *   val ab = a.joinWith(b)(Foo.apply)
   * }}}
   */
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

  /**
   * Convert this `Task[A]` to a `Task[Void]` by discarding the result.
   *
   * @note failed computations will remain as is.
   */
  final def voided: Arrow[T, Void] = map(Arrow.ToVoid)

  /**
   * Converts a `Task[Task[B]]` into a `Task[B]`.
   */
  final def flatten[V](implicit ev: U <:< Task[V]): Arrow[T, V] =
    flatMap[V](ev)

  /**
   * Returns an identical computation except that it ignores interrupts which match a predicate
   */
  final def mask(pred: PartialFunction[Throwable, Boolean]): Arrow[T, U] =
    new TransformFuture[T, U, U] {
      override final def a = Arrow.this
      override final def future(fut: Future[U]) = fut.mask(pred)
    }

  /**
   * Returns an identical computation that ignores all interrupts
   */
  final def masked: Arrow[T, U] =
    new TransformFuture[T, U, U] {
      override final def a = Arrow.this
      override final def future(fut: Future[U]) = fut.mask(Arrow.AlwaysMasked)
    }

  /**
   * Returns a `Task[Boolean]` indicating whether two Futures are equivalent.
   *
   * Note that {{{Task.exception(e).willEqual(Task.exception(e)) == Task.value(true)}}}.
   */
  final def willEqual[V](that: Task[V]): Arrow[T, Boolean] =
    flatMap { v =>
      that.map(_ == v)
    }

  /**
   * Returns the result of the computation as a `Task[Try[A]]`.
   */
  final def liftToTry: Arrow[T, Try[U]] =
    new TransformTry[T, U, Try[U]] {
      override final def a = Arrow.this
      override final def task(t: Try[U]) = Task.value(t)
    }

  /**
   * Lowers a `Task[Try[T]]` into a `Task[T]`.
   */
  final def lowerFromTry[V](implicit ev: U <:< Try[V]): Arrow[T, V] =
    flatMap { r =>
      Task.const(r.asInstanceOf[Try[V]])
    }

  /**
   * Makes a derivative `Task` which will be satisfied with the result
   * of the parent.  However, if it's interrupted, it will detach from
   * the parent `Task`, satisfy itself with the exception raised to
   * it, and won't propagate the interruption back to the parent
   * `Task`.
   *
   * This is useful for when a `Task` is shared between many contexts,
   * where it may not be useful to discard the underlying computation
   * if just one context is no longer interested in the result.  In
   * particular, this is different from [[masked Task.masked]] in that it will
   * prevent memory leaks if the parent Task will never be
   * satisfied, because closures that are attached to this derivative
   * `Task` will not be held onto by the killer `Task`.
   */
  final def interruptible(): Arrow[T, U] =
    new TransformFuture[T, U, U] {
      override final def a = Arrow.this
      override final def future(fut: Future[U]) = fut.interruptible()
    }
}

final object Arrow {

  import ArrowImpl._
  import scala.language.implicitConversions

  /**
   * Implicit conversion from `Task` to `Task`. This method is provided to
   * make the migration easier and is not meant for production usage.
   */
  @deprecated("Use task.run", "")
  implicit final def toFuture[T](t: Task[T]): Future[T] = t.run

  /**
   * Implicit conversion from `Task` to `Task`. This method is provided to
   * make the migration easier and is not meant for production usage.
   */
  @deprecated("Use Task.async", "")
  implicit final def fromFuture[T](f: => Future[T]): Task[T] = Task.async(f)

  /**
   * Creates an identity arrow.
   */
  final def apply[T]: Arrow[T, T] = Identity

  final def let[T, U, V](l: Local[T], v: T)(wrapped: Arrow[U, V]): Arrow[U, V] =
    new Wrap[U, V] {
      override def arrow = wrapped
      override def wrap(f: => Future[V]): Future[V] =
        l.let(v)(f)
    }

  /**
   * Forks the execution of a `Task` using the provided
   * implicit execution context.
   */
  final def fork[T, U](pool: FuturePool)(t: Arrow[T, U]): Arrow[T, U] =
    new Wrap[T, U] {
      override def arrow = t
      override def wrap(f: => Future[U]) =
        pool(f).flatten
    }

  /**
   * Produces a recursive arrow. Example:
   *
   * {{{
   * val sum: Arrow[List[Int], Int] =
   * 		Arrow.recursive { self =>
   * 			Arrow[List[Int]].flatMap {
   * 				case Nil => 0
   * 				case head :: tail =>
   * 					self(head).map(_ + head)
   * 			}
   * 		}
   * }}}
   */
  final def recursive[T, U](r: Arrow[T, U] => Arrow[T, U]): Arrow[T, U] =
    Recursive(r)

  /**
   * Returns an arrow that produces a constant value regardless of the input value.
   */
  final def value[T](v: T): Arrow[Any, T] = Value(v)

  /**
   * Returns an arrow that produces a failure regardless of the input value.
   */
  final def exception[T](ex: Throwable): Arrow[Any, T] = Exception(ex)

  /**
   * Applies multiple arrows in parallel to an input value.
   */
  final def collect[T, U](seq: Seq[Arrow[T, U]]): Arrow[T, Seq[U]] = Collect(seq)

  private final val ToUnit: Any => Unit = _ => ()
  private final val ToVoid: Any => Void = _ => (null: Void)

  private[arrows] final object NotApplied
  private[arrows] final val AlwaysNotApplied: Any => AnyRef = _ => NotApplied

  private final val AlwaysMasked: PartialFunction[Throwable, Boolean] = { case _ => true }
}
