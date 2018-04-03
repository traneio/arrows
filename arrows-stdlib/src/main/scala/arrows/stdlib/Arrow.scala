package arrows.stdlib

import ArrowRun._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try
import scala.reflect.ClassTag
import scala.util.Success
import scala.util.Failure
import scala.collection.generic.CanBuildFrom
import language.implicitConversions

/**
 * An `Arrow` represents a computation that can be reused by multiple executions.
 *
 *  Asynchronous computations that yield futures are created with the `Future.apply` call and are computed using a supplied `ExecutionContext`,
 * which can be backed by a Thread pool.
 *
 *  {{{
 *  import ExecutionContext.Implicits.global
 *  val s = "Hello"
 *  val f: Future[String] = Future {
 *    s + " future!"
 *  }
 *  f foreach {
 *    msg => println(msg)
 *  }
 *  }}}
 *
 *  @author  Philipp Haller, Heather Miller, Aleksandar Prokopec, Viktor Klang
 *
 *  @see [[http://docs.scala-lang.org/overviews/core/futures.html Futures and Promises]]
 *
 *  @define multipleCallbacks
 *  Multiple callbacks may be registered; there is no guarantee that they will be
 *  executed in a particular order.
 *
 *  @define caughtThrowables
 *  The future may contain a throwable object and this means that the future failed.
 *  Futures obtained through combinators have the same exception as the future they were obtained from.
 *  The following throwable objects are not contained in the future:
 *  - `Error` - errors are not contained within futures
 *  - `InterruptedException` - not contained within futures
 *  - all `scala.util.control.ControlThrowable` except `NonLocalReturnControl` - not contained within futures
 *
 *  Instead, the future is completed with a ExecutionException with one of the exceptions above
 *  as the cause.
 *  If a future is failed with a `scala.runtime.NonLocalReturnControl`,
 *  it is completed with a value from that throwable instead.
 *
 *  @define swallowsExceptions
 *  Since this method executes asynchronously and does not produce a return value,
 *  any non-fatal exceptions thrown will be reported to the `ExecutionContext`.
 *
 *  @define nonDeterministic
 *  Note: using this method yields nondeterministic dataflow programs.
 *
 *  @define forComprehensionExamples
 *  Example:
 *
 *  {{{
 *  val f = Future { 5 }
 *  val g = Future { 3 }
 *  val h = for {
 *    x: Int <- f // returns Future(5)
 *    y: Int <- g // returns Future(3)
 *  } yield x + y
 *  }}}
 *
 *  is translated to:
 *
 *  {{{
 *  f flatMap { (x: Int) => g map { (y: Int) => x + y } }
 *  }}}
 *
 */
abstract class Arrow[-T, +U] extends (T => Task[U]) {

  import ArrowImpl._

  private[arrows] def runSync[B <: T](s: Sync[B], depth: Int)(implicit ec: ExecutionContext): Result[U]
  private[arrows] final def cast[A, B] = this.asInstanceOf[Arrow[A, B]]

  final def apply(v: T): Task[U] =
    Apply(v, this)

  final def andThen[V](b: Arrow[U, V]): Arrow[T, V] =
    if (this eq Identity)
      b.cast
    else
      AndThen(this, b)

  final def run(value: T)(implicit ec: ExecutionContext): Future[U] =
    ArrowRun(value, this)

  final def run[V <: T](implicit ec: ExecutionContext, ev: V => Unit): Future[U] =
    ArrowRun((), this.cast)

  /**
   *  When this task is completed successfully (i.e., with a value),
   *  apply the provided partial function to the value if the partial function
   *  is defined at that value.
   *
   *  Note that the returned value of `pf` will be discarded.
   *
   *  $swallowsExceptions
   *  $multipleCallbacks
   *
   * @group Callbacks
   */
  @deprecated("use `foreach` or `onComplete` instead (keep in mind that they take total rather than partial functions)", "2.12.0")
  final def onSuccess[V](pf: PartialFunction[U, V]): Arrow[T, U] =
    this match {
      case a: Failed[_] => a.cast
      case _ =>
        new OnComplete[T, U] {
          override final def a = Arrow.this
          override final def callback(t: Try[U]): Unit =
            t match {
              case t: Success[_] =>
                pf.applyOrElse[U, Any](t.value, Predef.identity[U]) // Exploiting the cached function to avoid MatchError
              case _ =>
            }
        }
    }

  /**
   *  When this future is completed with a failure (i.e., with a throwable),
   *  apply the provided callback to the throwable.
   *
   *  $caughtThrowables
   *
   *  Will not be called in case that the future is completed with a value.
   *
   *  Note that the returned value of `pf` will be discarded.
   *
   *  $swallowsExceptions
   *  $multipleCallbacks
   *
   * @group Callbacks
   */
  @deprecated("use `onComplete` or `failed.foreach` instead (keep in mind that they take total rather than partial functions)", "2.12.0")
  final def onFailure[V](@deprecatedName('callback) pf: PartialFunction[Throwable, V]): Arrow[T, U] =
    this match {
      case a: Successful[_] => a.cast
      case _ =>
        new OnComplete[T, U] {
          override final def a = Arrow.this
          override final def callback(t: Try[U]): Unit =
            t match {
              case t: Failure[_] =>
                pf.applyOrElse[Throwable, Any](t.exception, Predef.identity[Throwable]) // Exploiting the cached function to avoid MatchError
              case _ =>
            }
        }
    }

  /**
   *  When this future is completed, either through an exception, or a value,
   *  apply the provided function.
   *
   *  Note that the returned value of `f` will be discarded.
   *
   *  $swallowsExceptions
   *  $multipleCallbacks
   *
   * @tparam U    only used to accept any return type of the given callback function
   * @param f     the function to be executed when this `Future` completes
   * @group Callbacks
   */
  final def onComplete[V](@deprecatedName('func) f: Try[U] => V): Arrow[T, U] =
    new OnComplete[T, U] {
      override final def a = Arrow.this
      override final def callback(t: Try[U]): Unit = f(t)
    }

  /**
   *  The returned `Future` will be successfully completed with the `Throwable` of the original `Future`
   *  if the original `Future` fails.
   *
   *  If the original `Future` is successful, the returned `Future` is failed with a `NoSuchElementException`.
   *
   * @return a failed projection of this `Future`.
   * @group Transformations
   */
  final def failed: Arrow[T, Throwable] =
    transform {
      case Failure(t) => Success(t)
      case Success(v) => Failure(new NoSuchElementException("Arrow.failed not completed with a throwable."))
    }

  /**
   *  Asynchronously processes the value in the future once the value becomes available.
   *
   *  WARNING: Will not be called if this future is never completed or if it is completed with a failure.
   *
   *  $swallowsExceptions
   *
   * @tparam U     only used to accept any return type of the given callback function
   * @param f      the function which will be executed if this `Future` completes with a result,
   *               the return value of `f` will be discarded.
   * @group Callbacks
   */
  final def foreach[V](f: U => V): Arrow[T, U] =
    this match {
      case a: Failed[_] => a.cast
      case _ =>
        new OnComplete[T, U] {
          override final def a = Arrow.this
          override final def callback(t: Try[U]): Unit =
            t match {
              case t: Success[_] => f(t.value)
              case _             =>
            }
        }
    }

  /**
   *  Creates a new future by applying the 's' function to the successful result of
   *  this future, or the 'f' function to the failed result. If there is any non-fatal
   *  exception thrown when 's' or 'f' is applied, that exception will be propagated
   *  to the resulting future.
   *
   * @tparam S  the type of the returned `Future`
   * @param  s  function that transforms a successful result of the receiver into a successful result of the returned future
   * @param  f  function that transforms a failure of the receiver into a failure of the returned future
   * @return    a `Future` that will be completed with the transformed value
   * @group Transformations
   */
  final def transform[V](s: U => V, f: Throwable => Throwable): Arrow[T, V] =
    transform {
      case Success(r) => Try(s(r))
      case Failure(t) => Try(throw f(t))
    }

  /**
   * Creates a new Future by applying the specified function to the result
   * of this Future. If there is any non-fatal exception thrown when 'f'
   * is applied then that exception will be propagated to the resulting future.
   *
   * @tparam S  the type of the returned `Future`
   * @param  f  function that transforms the result of this future
   * @return    a `Future` that will be completed with the transformed value
   * @group Transformations
   */
  final def transform[V](f: Try[U] => Try[V]): Arrow[T, V] =
    new TransformTry[T, U, V] {
      override final def a = Arrow.this
      override final def t(t: Try[U]) = f(t)
    }

  /**
   * Creates a new Future by applying the specified function, which produces a Future, to the result
   * of this Future. If there is any non-fatal exception thrown when 'f'
   * is applied then that exception will be propagated to the resulting future.
   *
   * @tparam S  the type of the returned `Future`
   * @param  f  function that transforms the result of this future
   * @return    a `Future` that will be completed with the transformed value
   * @group Transformations
   */
  final def transformWith[V](f: Try[U] => Task[V]): Arrow[T, V] =
    new TransformWith[T, U, V] {
      override final def a = Arrow.this
      override final def task(t: Try[U]) = f(t)
    }

  /**
   *  Creates a new future by applying a function to the successful result of
   *  this future. If this future is completed with an exception then the new
   *  future will also contain this exception.
   *
   *  Example:
   *
   *  {{{
   *  val f = Future { "The future" }
   *  val g = f map { x: String => x + " is now!" }
   *  }}}
   *
   *  Note that a for comprehension involving a `Future`
   *  may expand to include a call to `map` and or `flatMap`
   *  and `withFilter`.  See [[scala.concurrent.Future#flatMap]] for an example of such a comprehension.
   *
   *
   * @tparam S  the type of the returned `Future`
   * @param f   the function which will be applied to the successful result of this `Future`
   * @return    a `Future` which will be completed with the result of the application of the function
   * @group Transformations
   */
  final def map[V](f: U => V): Arrow[T, V] =
    this match {
      case a: Failed[_] => a.cast
      case a            => Map(a, f)
    }

  /**
   *  Creates a new future by applying a function to the successful result of
   *  this future, and returns the result of the function as the new future.
   *  If this future is completed with an exception then the new future will
   *  also contain this exception.
   *
   *  $forComprehensionExamples
   *
   * @tparam S  the type of the returned `Future`
   * @param f   the function which will be applied to the successful result of this `Future`
   * @return    a `Future` which will be completed with the result of the application of the function
   * @group Transformations
   */
  final def flatMap[V](f: U => Task[V]): Arrow[T, V] =
    this match {
      case a: Failed[_] => a.cast
      case a            => FlatMap(a, f)
    }

  /**
   *  Creates a new future with one level of nesting flattened, this method is equivalent
   *  to `flatMap(identity)`.
   *
   * @tparam S  the type of the returned `Future`
   * @group Transformations
   */
  final def flatten[V](implicit ev: U <:< Task[V]): Arrow[T, V] =
    flatMap[V](ev)

  /**
   *  Creates a new future by filtering the value of the current future with a predicate.
   *
   *  If the current future contains a value which satisfies the predicate, the new future will also hold that value.
   *  Otherwise, the resulting future will fail with a `NoSuchElementException`.
   *
   *  If the current future fails, then the resulting future also fails.
   *
   *  Example:
   *  {{{
   *  val f = Future { 5 }
   *  val g = f filter { _ % 2 == 1 }
   *  val h = f filter { _ % 2 == 0 }
   *  g foreach println // Eventually prints 5
   *  Await.result(h, Duration.Zero) // throw a NoSuchElementException
   *  }}}
   *
   * @param p   the predicate to apply to the successful result of this `Future`
   * @return    a `Future` which will hold the successful result of this `Future` if it matches the predicate or a `NoSuchElementException`
   * @group Transformations
   */
  final def filter(@deprecatedName('pred) p: U => Boolean): Arrow[T, U] =
    map(r => if (p(r)) r else throw new NoSuchElementException("Arrow.filter predicate is not satisfied"))

  /**
   * Used by for-comprehensions.
   * @group Transformations
   */
  final def withFilter(p: U => Boolean): Arrow[T, U] =
    filter(p)

  /**
   *  Creates a new future by mapping the value of the current future, if the given partial function is defined at that value.
   *
   *  If the current future contains a value for which the partial function is defined, the new future will also hold that value.
   *  Otherwise, the resulting future will fail with a `NoSuchElementException`.
   *
   *  If the current future fails, then the resulting future also fails.
   *
   *  Example:
   *  {{{
   *  val f = Future { -5 }
   *  val g = f collect {
   *    case x if x < 0 => -x
   *  }
   *  val h = f collect {
   *    case x if x > 0 => x * 2
   *  }
   *  g foreach println // Eventually prints 5
   *  Await.result(h, Duration.Zero) // throw a NoSuchElementException
   *  }}}
   *
   * @tparam S    the type of the returned `Future`
   * @param pf    the `PartialFunction` to apply to the successful result of this `Future`
   * @return      a `Future` holding the result of application of the `PartialFunction` or a `NoSuchElementException`
   * @group Transformations
   */
  final def collect[V](pf: PartialFunction[U, V]): Arrow[T, V] =
    map {
      r => pf.applyOrElse(r, (t: U) => throw new NoSuchElementException("Arrow.collect partial function is not defined at: " + t))
    }

  /**
   *  Creates a new future that will handle any matching throwable that this
   *  future might contain. If there is no match, or if this future contains
   *  a valid result then the new future will contain the same.
   *
   *  Example:
   *
   *  {{{
   *  Future (6 / 0) recover { case e: ArithmeticException => 0 } // result: 0
   *  Future (6 / 0) recover { case e: NotFoundException   => 0 } // result: exception
   *  Future (6 / 2) recover { case e: ArithmeticException => 0 } // result: 3
   *  }}}
   *
   * @tparam U    the type of the returned `Future`
   * @param pf    the `PartialFunction` to apply if this `Future` fails
   * @return      a `Future` with the successful value of this `Future` or the result of the `PartialFunction`
   * @group Transformations
   */
  final def recover[V >: U](pf: PartialFunction[Throwable, V]): Arrow[T, V] =
    this match {
      case a: Successful[_] => a.cast
      case a                => Recover(a, pf)
    }

  /**
   *  Creates a new future that will handle any matching throwable that this
   *  future might contain by assigning it a value of another future.
   *
   *  If there is no match, or if this future contains
   *  a valid result then the new future will contain the same result.
   *
   *  Example:
   *
   *  {{{
   *  val f = Future { Int.MaxValue }
   *  Future (6 / 0) recoverWith { case e: ArithmeticException => f } // result: Int.MaxValue
   *  }}}
   *
   * @tparam U    the type of the returned `Future`
   * @param pf    the `PartialFunction` to apply if this `Future` fails
   * @return      a `Future` with the successful value of this `Future` or the outcome of the `Future` returned by the `PartialFunction`
   * @group Transformations
   */
  final def recoverWith[V >: U](pf: PartialFunction[Throwable, Task[V]]): Arrow[T, V] =
    this match {
      case a: Successful[_] => a.cast
      case a =>
        new TransformWith[T, U, V] {
          override final def a = Arrow.this
          override final def task(t: Try[U]) =
            t match {
              case t: Failure[_] =>
                val ex = t.exception
                val result = pf.applyOrElse(ex, Arrow.AlwaysNotApplied)
                if (result eq Arrow.NotApplied) Task.failed(ex) else result.asInstanceOf[Task[V]]
              case t: Success[_] =>
                Task.successful(t.value)
            }
        }
    }

  /**
   *  Zips the values of `this` and `that` future, and creates
   *  a new future holding the tuple of their results.
   *
   *  If `this` future fails, the resulting future is failed
   *  with the throwable stored in `this`.
   *  Otherwise, if `that` future fails, the resulting future is failed
   *  with the throwable stored in `that`.
   *
   * @tparam U      the type of the other `Future`
   * @param that    the other `Future`
   * @return        a `Future` with the results of both futures or the failure of the first of them that failed
   * @group Transformations
   */
  final def zip[V](that: Task[V]): Arrow[T, (U, V)] =
    this match {
      case a: Failed[_] => a.cast
      case _ =>
        new Zip[T, U, V, (U, V)] {
          override final def a = Arrow.this
          override final def p = that
          override final def future(u: Future[U], v: Future[V])(implicit ec: ExecutionContext) =
            u.zip(v)
        }
    }

  /**
   *  Zips the values of `this` and `that` future using a function `f`,
   *  and creates a new future holding the result.
   *
   *  If `this` future fails, the resulting future is failed
   *  with the throwable stored in `this`.
   *  Otherwise, if `that` future fails, the resulting future is failed
   *  with the throwable stored in `that`.
   *  If the application of `f` throws a throwable, the resulting future
   *  is failed with that throwable if it is non-fatal.
   *
   * @tparam U      the type of the other `Future`
   * @tparam R      the type of the resulting `Future`
   * @param that    the other `Future`
   * @param f       the function to apply to the results of `this` and `that`
   * @return        a `Future` with the result of the application of `f` to the results of `this` and `that`
   * @group Transformations
   */
  final def zipWith[V, X](that: Task[V])(f: (U, V) => X): Arrow[T, X] =
    this match {
      case a: Failed[_] => a.cast
      case _ =>
        new Zip[T, U, V, X] {
          override final def a = Arrow.this
          override final def p = that
          override final def future(u: Future[U], v: Future[V])(implicit ec: ExecutionContext) =
            u.zipWith(v)(f)
        }
    }

  /**
   *  Creates a new future which holds the result of this future if it was completed successfully, or, if not,
   *  the result of the `that` future if `that` is completed successfully.
   *  If both futures are failed, the resulting future holds the throwable object of the first future.
   *
   *  Using this method will not cause concurrent programs to become nondeterministic.
   *
   *  Example:
   *  {{{
   *  val f = Future { sys.error("failed") }
   *  val g = Future { 5 }
   *  val h = f fallbackTo g
   *  h foreach println // Eventually prints 5
   *  }}}
   *
   * @tparam U     the type of the other `Future` and the resulting `Future`
   * @param that   the `Future` whose result we want to use if this `Future` fails.
   * @return       a `Future` with the successful result of this or that `Future` or the failure of this `Future` if both fail
   * @group Transformations
   */
  // TODO optimize
  final def fallbackTo[V >: U](that: Task[V]): Arrow[T, V] =
    if (this eq that) this
    else {
      transformWith {
        case r: Success[_] => Task.successful(r.value)
        case r: Failure[_] => that.recoverWith { case _ => Task.failed(r.exception) }
      }
    }

  /**
   *  Creates a new `Future[S]` which is completed with this `Future`'s result if
   *  that conforms to `S`'s erased type or a `ClassCastException` otherwise.
   *
   * @tparam S     the type of the returned `Future`
   * @param tag    the `ClassTag` which will be used to cast the result of this `Future`
   * @return       a `Future` holding the casted result of this `Future` or a `ClassCastException` otherwise
   * @group Transformations
   */
  final def mapTo[V](implicit tag: ClassTag[V]): Arrow[T, V] =
    this match {
      case _: Failed[_] => this.cast
      case _ =>
        val boxedClass = {
          val c = tag.runtimeClass
          if (c.isPrimitive) Arrow.toBoxed(c) else c
        }
        require(boxedClass ne null)
        map(s => boxedClass.cast(s).asInstanceOf[V])
    }

  /**
   *  Applies the side-effecting function to the result of this future, and returns
   *  a new future with the result of this future.
   *
   *  This method allows one to enforce that the callbacks are executed in a
   *  specified order.
   *
   *  Note that if one of the chained `andThen` callbacks throws
   *  an exception, that exception is not propagated to the subsequent `andThen`
   *  callbacks. Instead, the subsequent `andThen` callbacks are given the original
   *  value of this future.
   *
   *  The following example prints out `5`:
   *
   *  {{{
   *  val f = Future { 5 }
   *  f andThen {
   *    case r => sys.error("runtime exception")
   *  } andThen {
   *    case Failure(t) => println(t)
   *    case Success(v) => println(v)
   *  }
   *  }}}
   *
   * $swallowsExceptions
   *
   * @tparam U     only used to accept any return type of the given `PartialFunction`
   * @param pf     a `PartialFunction` which will be conditionally applied to the outcome of this `Future`
   * @return       a `Future` which will be completed with the exact same outcome as this `Future` but after the `PartialFunction` has been executed.
   * @group Callbacks
   */
  final def andThen[V](pf: PartialFunction[Try[U], V]): Arrow[T, U] =
    new OnComplete[T, U] {
      override final def a = Arrow.this
      override final def callback(t: Try[U]): Unit =
        pf.applyOrElse[Try[U], Any](t, Predef.identity[Try[U]])
    }
}

final object Arrow {

  import ArrowImpl._
  import language.higherKinds

  final def apply[T]: Arrow[T, T] = Identity

  @deprecated("Use task.run", "")
  implicit final def toFuture[T](t: Task[T])(implicit ec: ExecutionContext): Future[T] = t.run

  @deprecated("Use Task.async", "")
  implicit final def fromFuture[T](f: => Future[T]): Task[T] = Task.async(f)

  final def successful[T](v: T): Arrow[Any, T] = Successful(v)

  final def failed[T](exception: Throwable): Arrow[Any, T] = Failed(exception)

  final def sequence[T, U, M[X] <: TraversableOnce[X]](in: M[Arrow[T, U]])(
    implicit cbf: CanBuildFrom[M[Arrow[T, U]], U, M[U]]): Arrow[T, M[U]] =
    Sequence[T, U, M](in)

  final def fork[T, U](t: Arrow[T, U])(implicit ec: ExecutionContext): Arrow[T, U] =
    new Fork(t)

  final def recursive[T, U](r: Arrow[T, U] => Arrow[T, U]): Arrow[T, U] = Recursive(r)

  private[arrows] final val toBoxed = scala.collection.Map[Class[_], Class[_]](
    classOf[Boolean] -> classOf[java.lang.Boolean],
    classOf[Byte] -> classOf[java.lang.Byte],
    classOf[Char] -> classOf[java.lang.Character],
    classOf[Short] -> classOf[java.lang.Short],
    classOf[Int] -> classOf[java.lang.Integer],
    classOf[Long] -> classOf[java.lang.Long],
    classOf[Float] -> classOf[java.lang.Float],
    classOf[Double] -> classOf[java.lang.Double],
    classOf[Unit] -> classOf[scala.runtime.BoxedUnit])

  private[arrows] final object NotApplied
  private[arrows] final val AlwaysNotApplied: Any => AnyRef = _ => NotApplied
}
