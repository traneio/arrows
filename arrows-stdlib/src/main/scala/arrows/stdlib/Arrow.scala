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

  private[arrows] def runSync[B <: T](s: Sync[B], depth: Int)(implicit ec: ExecutionContext): Result[U]
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
  final def run(value: T)(implicit ec: ExecutionContext): Future[U] =
    ArrowRun(value, this)

  /**
   * Executes the `Task` using the provided  execution context
   */
  final def run[V <: T](implicit ec: ExecutionContext, ev: V => Unit): Future[U] =
    ArrowRun((), this.cast)

  /**
   *  When this computation is completed successfully (i.e., with a value),
   *  apply the provided partial function to the value if the partial function
   *  is defined at that value.
   *
   *  Note that the returned value of `pf` will be discarded.
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
   *  When this computation is completed with a failure (i.e., with a throwable),
   *  apply the provided callback to the throwable.
   *
   *  Will not be called in case that the computation is completed with a value.
   *
   *  Note that the returned value of `pf` will be discarded.
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
   *  When this computation is completed, either through an exception, or a value,
   *  apply the provided function.
   *
   *  Note that the returned value of `f` will be discarded.
   *
   * @tparam U    only used to accept any return type of the given callback function
   * @param f     the function to be executed when this `Task` completes
   */
  final def onComplete[V](@deprecatedName('func) f: Try[U] => V): Arrow[T, U] =
    new OnComplete[T, U] {
      override final def a = Arrow.this
      override final def callback(t: Try[U]): Unit = f(t)
    }

  /**
   *  The returned `Task` will be successfully completed with the `Throwable` of the original `Task`
   *  if the original `Task` fails.
   *
   *  If the original `Task` is successful, the returned `Task` is failed with a `NoSuchElementException`.
   */
  final def failed: Arrow[T, Throwable] =
    transform {
      case Failure(t) => Success(t)
      case Success(v) => Failure(new NoSuchElementException("Arrow.failed not completed with a throwable."))
    }

  /**
   *  Processes the value in the computation once the value becomes available.
   *
   *  WARNING: Will not be called if this computation is never completed or if it is completed with a failure.
   *
   * @tparam U     only used to accept any return type of the given callback function
   * @param f      the function which will be executed if this `Task` completes with a result,
   *               the return value of `f` will be discarded.
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
   *  Creates a new computation by applying the 's' function to the successful result of
   *  this computation, or the 'f' function to the failed result. If there is any non-fatal
   *  exception thrown when 's' or 'f' is applied, that exception will be propagated
   *  to the resulting computation.
   *
   * @tparam V  the type of the returned value
   * @param  s  function that transforms a successful result of the receiver into a successful result of the returned computation
   * @param  f  function that transforms a failure of the receiver into a failure of the returned computation
   * @return    a computation that will be completed with the transformed value
   */
  final def transform[V](s: U => V, f: Throwable => Throwable): Arrow[T, V] =
    transform {
      case Success(r) => Try(s(r))
      case Failure(t) => Try(throw f(t))
    }

  /**
   * Creates a new Task by applying the specified function to the result
   * of this Task. If there is any non-fatal exception thrown when 'f'
   * is applied then that exception will be propagated to the resulting computation.
   *
   * @tparam S  the type of the returned `Task`
   * @param  f  function that transforms the result of this computation
   * @return    a `Task` that will be completed with the transformed value
   */
  final def transform[V](f: Try[U] => Try[V]): Arrow[T, V] =
    new TransformTry[T, U, V] {
      override final def a = Arrow.this
      override final def t(t: Try[U]) = f(t)
    }

  /**
   * Creates a new Task by applying the specified function, which produces a Task, to the result
   * of this Task. If there is any non-fatal exception thrown when 'f'
   * is applied then that exception will be propagated to the resulting c.
   *
   * @tparam S  the type of the returned `Task`
   * @param  f  function that transforms the result of this computation
   * @return    a `Task` that will be completed with the transformed value
   */
  final def transformWith[V](f: Try[U] => Task[V]): Arrow[T, V] =
    new TransformWith[T, U, V] {
      override final def a = Arrow.this
      override final def task(t: Try[U]) = f(t)
    }

  /**
   *  Creates a new computation by applying a function to the successful result of
   *  this computation. If this computation is completed with an exception then the new
   *  computation will also contain this exception.
   *
   *  Example:
   *
   *  {{{
   *  val f = Task { "The task" }
   *  val g = f map { x: String => x + " is now!" }
   *  }}}
   *
   *  Note that a for comprehension involving a `Task`
   *  may expand to include a call to `map` and or `flatMap`
   *  and `withFilter`.
   *
   * @tparam S  the type of the returned `Task`
   * @param f   the function which will be applied to the successful result of this `Task`
   * @return    a `Task` which will be completed with the result of the application of the function
   */
  final def map[V](f: U => V): Arrow[T, V] =
    this match {
      case a: Failed[_] => a.cast
      case a            => Map(a, f)
    }

  /**
   *  Creates a new computation by applying a function to the successful result of
   *  this computation, and returns the result of the function as the new computation.
   *  If this computation is completed with an exception then the new computation will
   *  also contain this exception.
   *
   * @tparam S  the type of the returned `Task`
   * @param f   the function which will be applied to the successful result of this `Task`
   * @return    a `Task` which will be completed with the result of the application of the function
   */
  final def flatMap[V](f: U => Task[V]): Arrow[T, V] =
    this match {
      case a: Failed[_] => a.cast
      case a            => FlatMap(a, f)
    }

  /**
   *  Creates a new computation with one level of nesting flattened, this method is equivalent
   *  to `flatMap(identity)`.
   *
   * @tparam S  the type of the returned `Task`
   */
  final def flatten[V](implicit ev: U <:< Task[V]): Arrow[T, V] =
    flatMap[V](ev)

  /**
   *  Creates a new computation by filtering the value of the current computation with a predicate.
   *
   *  If the current computation contains a value which satisfies the predicate, the new computation will also hold that value.
   *  Otherwise, the resulting computation will fail with a `NoSuchElementException`.
   *
   *  If the current computation fails, then the resulting computation also fails.
   *
   *  Example:
   *  {{{
   *  val f = Task { 5 }
   *  val g = f filter { _ % 2 == 1 }
   *  val h = f filter { _ % 2 == 0 }
   *  g foreach println // Eventually prints 5
   *  Await.result(h.run, Duration.Zero) // throw a NoSuchElementException
   *  }}}
   *
   * @param p   the predicate to apply to the successful result of this `Task`
   * @return    a `Task` which will hold the successful result of this `Task` if it matches the predicate or a `NoSuchElementException`
   */
  final def filter(@deprecatedName('pred) p: U => Boolean): Arrow[T, U] =
    map(r => if (p(r)) r else throw new NoSuchElementException("Arrow.filter predicate is not satisfied"))

  /**
   * Used by for-comprehensions.
   */
  final def withFilter(p: U => Boolean): Arrow[T, U] =
    filter(p)

  /**
   *  Creates a new computation by mapping the value of the current computation, if the given partial function is defined at that value.
   *
   *  If the current computation contains a value for which the partial function is defined, the new computation will also hold that value.
   *  Otherwise, the resulting computation will fail with a `NoSuchElementException`.
   *
   *  If the current computation fails, then the resulting computation also fails.
   *
   *  Example:
   *  {{{
   *  val f = Task { -5 }
   *  val g = f collect {
   *    case x if x < 0 => -x
   *  }
   *  val h = f collect {
   *    case x if x > 0 => x * 2
   *  }
   *  g foreach println // Eventually prints 5
   *  Await.result(h.run, Duration.Zero) // throw a NoSuchElementException
   *  }}}
   *
   * @tparam S    the type of the returned `Task`
   * @param pf    the `PartialFunction` to apply to the successful result of this `Task`
   * @return      a `Task` holding the result of application of the `PartialFunction` or a `NoSuchElementException`
   */
  final def collect[V](pf: PartialFunction[U, V]): Arrow[T, V] =
    map {
      r => pf.applyOrElse(r, (t: U) => throw new NoSuchElementException("Arrow.collect partial function is not defined at: " + t))
    }

  /**
   *  Creates a new computation that will handle any matching throwable that this
   *  computation might contain. If there is no match, or if this computation contains
   *  a valid result then the new computation will contain the same.
   *
   *  Example:
   *
   *  {{{
   *  Task (6 / 0) recover { case e: ArithmeticException => 0 } // result: 0
   *  Task (6 / 0) recover { case e: NotFoundException   => 0 } // result: exception
   *  Task (6 / 2) recover { case e: ArithmeticException => 0 } // result: 3
   *  }}}
   *
   * @tparam U    the type of the returned `Task`
   * @param pf    the `PartialFunction` to apply if this `Task` fails
   * @return      a `Task` with the successful value of this `Task` or the result of the `PartialFunction`
   */
  final def recover[V >: U](pf: PartialFunction[Throwable, V]): Arrow[T, V] =
    this match {
      case a: Successful[_] => a.cast
      case a                => Recover(a, pf)
    }

  /**
   *  Creates a new computation that will handle any matching throwable that this
   *  computation might contain by assigning it a value of another computation.
   *
   *  If there is no match, or if this computation contains
   *  a valid result then the new computation will contain the same result.
   *
   *  Example:
   *
   *  {{{
   *  val f = Task { Int.MaxValue }
   *  Task (6 / 0) recoverWith { case e: ArithmeticException => f } // result: Int.MaxValue
   *  }}}
   *
   * @tparam U    the type of the returned `Task`
   * @param pf    the `PartialFunction` to apply if this `Task` fails
   * @return      a `Task` with the successful value of this `Task` or the outcome of the `Task` returned by the `PartialFunction`
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
   *  Zips the values of `this` and `that` computation, and creates
   *  a new computation holding the tuple of their results.
   *
   *  If `this` computation fails, the resulting computation is failed
   *  with the throwable stored in `this`.
   *  Otherwise, if `that` computation fails, the resulting computation is failed
   *  with the throwable stored in `that`.
   *
   * @tparam U      the type of the other `Task`
   * @param that    the other `Task`
   * @return        a `Task` with the results of both computations or the failure of the first of them that failed
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
   *  Zips the values of `this` and `that` computation using a function `f`,
   *  and creates a new computation holding the result.
   *
   *  If `this` computation fails, the resulting computation is failed
   *  with the throwable stored in `this`.
   *  Otherwise, if `that` computation fails, the resulting computation is failed
   *  with the throwable stored in `that`.
   *  If the application of `f` throws a throwable, the resulting computation
   *  is failed with that throwable if it is non-fatal.
   *
   * @tparam U      the type of the other `Task`
   * @tparam R      the type of the resulting `Task`
   * @param that    the other `Task`
   * @param f       the function to apply to the results of `this` and `that`
   * @return        a `Task` with the result of the application of `f` to the results of `this` and `that`
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
   *  Creates a new computation which holds the result of this computation if it was completed successfully, or, if not,
   *  the result of the `that` computation if `that` is completed successfully.
   *  If both computations are failed, the resulting computation holds the throwable object of the first computation.
   *
   *  Using this method will not cause concurrent programs to become nondeterministic.
   *
   *  Example:
   *  {{{
   *  val f = Task { sys.error("failed") }
   *  val g = Task { 5 }
   *  val h = f fallbackTo g
   *  h.foreach(println).run // Eventually prints 5
   *  }}}
   *
   * @tparam U     the type of the other `Task` and the resulting `Task`
   * @param that   the `Task` whose result we want to use if this `Task` fails.
   * @return       a `Task` with the successful result of this or that `Task` or the failure of this `Task` if both fail
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
   *  Creates a new `Task[S]` which is completed with this `Task`'s result if
   *  that conforms to `S`'s erased type or a `ClassCastException` otherwise.
   *
   * @tparam S     the type of the returned `Task`
   * @param tag    the `ClassTag` which will be used to cast the result of this `Task`
   * @return       a `Task` holding the casted result of this `Task` or a `ClassCastException` otherwise
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
   *  Applies the side-effecting function to the result of this computation, and returns
   *  a new computation with the result of this computation.
   *
   *  This method allows one to enforce that the callbacks are executed in a
   *  specified order.
   *
   *  Note that if one of the chained `andThen` callbacks throws
   *  an exception, that exception is not propagated to the subsequent `andThen`
   *  callbacks. Instead, the subsequent `andThen` callbacks are given the original
   *  value of this computation.
   *
   *  The following example prints out `5`:
   *
   *  {{{
   *  val f = Task { 5 }
   *  f andThen {
   *    case r => sys.error("runtime exception")
   *  } andThen {
   *    case Failure(t) => println(t)
   *    case Success(v) => println(v)
   *  }
   *  }}}
   *
   * @tparam U     only used to accept any return type of the given `PartialFunction`
   * @param pf     a `PartialFunction` which will be conditionally applied to the outcome of this `Task`
   * @return       a `Task` which will be completed with the exact same outcome as this `Task` but after the `PartialFunction` has been executed.
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

  /**
   * Creates an identity arrow.
   */
  final def apply[T]: Arrow[T, T] = Identity

  /**
   * Implicit conversion from `Task` to `Future`. This method is provided to
   * make the migration easier and is not meant for production usage.
   */
  @deprecated("Use task.run", "")
  implicit final def toFuture[T](t: Task[T])(implicit ec: ExecutionContext): Future[T] = t.run

  /**
   * Implicit conversion from `Future` to `Task`. This method is provided to
   * make the migration easier and is not meant for production usage.
   */
  @deprecated("Use Task.async", "")
  implicit final def fromFuture[T](f: => Future[T]): Task[T] = Task.async(f)

  /**
   * Returns an arrow that produces a constant value regardless of the input value.
   */
  final def successful[T](v: T): Arrow[Any, T] = Successful(v)

  /**
   * Returns an arrow that produces a failure regardless of the input value.
   */
  final def failed[T](exception: Throwable): Arrow[Any, T] = Failed(exception)

  /**
   * Applies multiple arrows in parallel to an input value.
   */
  final def sequence[T, U, M[X] <: TraversableOnce[X]](in: M[Arrow[T, U]])(
    implicit
    cbf: CanBuildFrom[M[Arrow[T, U]], U, M[U]]
  ): Arrow[T, M[U]] =
    Sequence[T, U, M](in)

  /**
   * Forks the execution of a `Task` using the provided
   * implicit execution context.
   */
  final def fork[T, U](t: Arrow[T, U])(implicit ec: ExecutionContext): Arrow[T, U] =
    new Fork(t)

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
    classOf[Unit] -> classOf[scala.runtime.BoxedUnit]
  )

  private[arrows] final object NotApplied
  private[arrows] final val AlwaysNotApplied: Any => AnyRef = _ => NotApplied
}
