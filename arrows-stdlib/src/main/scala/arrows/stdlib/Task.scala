package arrows.stdlib

import scala.util.Try
import scala.collection.generic.CanBuildFrom
import language.higherKinds
import scala.util.Success
import scala.util.Failure
import scala.concurrent.Future
import scala.concurrent.ExecutionContext

/**
 * A `Task` is a specific case of an Arrow without
 * inputs. The package object defines it as:
 * 
 * {{{
 * type Task[+T] = Arrow[Unit, T]
 * }}}
 * 
 * It's the equivalent of `Task`/`IO` in libraries
 * like Scalaz 8, Cats Effect, and Monix.
 * 
 * This object provides operations to manipulate `Task`
 * instances similarly to how they'd be manipulated using
 * the `Future` companion object.
 */
final object Task {

  import ArrowImpl._

  /**
   * A Task which is always completed with the Unit value.
   */
  val unit: Arrow[Any, Unit] = successful(())

  /**
   * A Task which is never completed.
   */
  // TODO optimize
  final val never: Task[Nothing] = async(Future.never)

  /**
   * Creates a `Task` from a `Future`. This method takes a
   * by-name parameter so the `Future` creation can happen
   * lazily during execution.
   *
   * Avoid creating the `Future` eagerly since it won't
   * defer side effects.
   *
   * {{{
   * // Bad practice
   * val fut = performSomeAsyncSideEffect()
   * Task.async(fut)
   * 
   * // Ok
   * Task.async(performSomeAsyncSideEffect())
   * }}}
   */
  final def async[T](f: => Future[T]): Task[T] =
    new FromFuture(_ => f)

  /**
   * Creates a `Task` from a `Future`. This method exposes
   * the execution context so the user can perform regular
   * future compositions using the run loop executor.
   */
  final def async[T](f: ExecutionContext => Future[T]): Task[T] =
    new FromFuture(f)

  /**
   * Forks the execution of a `Task` using the provided
   * implicit execution context.
   */
  final def fork[T](t: Task[T])(implicit ec: ExecutionContext): Task[T] = Arrow.fork(t)

  /**
   *  Creates failed Task with the specified exception.
   *
   *  @tparam T        the type of the value in the task
   *  @param exception the non-null instance of `Throwable`
   *  @return          the newly created `Task` instance
   */
  final def failed[T](exception: Throwable): Arrow[Any, T] = Failed(exception)

  /**
   *  Creates a Task with the specified result.
   *
   *  @tparam T       the type of the value in the task
   *  @param result   the given successful value
   *  @return         the newly created `Task` instance
   */
  final def successful[T](result: T): Arrow[Any, T] = Successful(result)

  /**
   *  Creates a Task with the specified result or exception.
   *
   *  @tparam T       the type of the value in the `Task`
   *  @param result   the result of the returned `Task` instance
   *  @return         the newly created `Task` instance
   */
  final def fromTry[T](result: Try[T]): Task[T] =
    result match {
      case r: Success[_] => successful(r.value)
      case r: Failure[_] => failed(r.exception)
    }

  /**
   *  Delays the execution of `body` until the `Task` is run.
   *
   *  The following expressions are equivalent:
   *
   *  {{{
   *  val f1 = Task(expr)
   *  val f2 = Task.unit.map(_ => expr)
   *  }}}
   *
   *  @tparam T        the type of the result
   *  @param body      the computation
   *  @return          the `Task` holding the result of the computation
   */
  final def apply[T](body: => T): Task[T] =
    Arrow[Unit].map(_ => body)

  /**
   *  Simple version of `Task.traverse`. Useful for reducing many `Task`s 
   *  into a single `Task`.
   *
   * @tparam A        the type of the value inside the Tasks
   * @tparam M        the type of the `TraversableOnce` of Tasks
   * @param in        the `TraversableOnce` of Tasks which will be sequenced
   * @return          the `Task` of the `TraversableOnce` of results
   */
  final def sequence[A, M[X] <: TraversableOnce[X]](in: M[Task[A]])(
    implicit cbf: CanBuildFrom[M[Task[A]], A, M[A]]): Task[M[A]] =
    Arrow.sequence(in)

  // TODO optimize all methods below

  /**
   *  Returns a new `Task` to the result of the first task
   *  in the list that is completed. This means no matter if it is
   *  completed as a success or as a failure.
   *
   * @tparam T        the type of the value in the task
   * @param tasks   	the `TraversableOnce` of Tasks in which to find the first completed
   * @return          the `Task` holding the result of the task that is first to be completed
   */
  final def firstCompletedOf[T](@deprecatedName('futures) tasks: TraversableOnce[Task[T]]): Task[T] =
    Task.async {
      implicit ec: ExecutionContext =>
        Future.firstCompletedOf(tasks.map(_.run))
    }

  /**
   *  Returns a `Task` that will hold the optional result
   *  of the first `Task` with a result that matches the predicate.
   *
   * @tparam T        the type of the value in the task
   * @param tasks     the `TraversableOnce` of Tasks to search
   * @param p         the predicate which indicates if it's a match
   * @return          the `Task` holding the optional result of the search
   */
  @deprecated("use the overloaded version of this method that takes a scala.collection.immutable.Iterable instead", "2.12.0")
  final def find[T](@deprecatedName('futures) tasks: TraversableOnce[Task[T]])(@deprecatedName('predicate) p: T => Boolean): Task[Option[T]] =
    Task.async {
      implicit ec: ExecutionContext =>
        Future.find(tasks.map(_.run))(p)
    }

  /**
   *  Returns a `Task` that will hold the optional result
   *  of the first `Task` with a result that matches the predicate,
   *  failed `Task`s will be ignored.
   *
   * @tparam T        the type of the value in the task
   * @param tasks     the `scala.collection.immutable.Iterable` of Tasks to search
   * @param p         the predicate which indicates if it's a match
   * @return          the `Task` holding the optional result of the search
   */
  final def find[T](@deprecatedName('futures) tasks: scala.collection.immutable.Iterable[Task[T]])(p: T => Boolean): Task[Option[T]] = {
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

  /**
   *  A left fold over the specified tasks, with the start value of the given zero.
   *  The fold is performed in left-to-right order as the tasks become completed.
   *  The result will be the first failure of any of the tasks, or any failure
   *  in the actual fold, or the result of the fold.
   *
   *  Example:
   *  {{{
   *    val taskSum = Task.foldLeft(tasks)(0)(_ + _)
   *  }}}
   *
   * @tparam T       the type of the value of the input Tasks
   * @tparam R       the type of the value of the returned `Task`
   * @param tasks  	 the `scala.collection.immutable.Iterable` of Tasks to be folded
   * @param zero     the start value of the fold
   * @param op       the fold operation to be applied to the zero and tasks
   * @return         the `Task` holding the result of the fold
   */
  final def foldLeft[T, R](@deprecatedName('futures) tasks: scala.collection.immutable.Iterable[Task[T]])(zero: R)(op: (R, T) => R): Task[R] =
    foldNext(tasks.iterator, zero, op)

  private[this] final def foldNext[T, R](i: Iterator[Task[T]], prevValue: R, op: (R, T) => R): Task[R] =
    if (!i.hasNext) successful(prevValue)
    else i.next().flatMap { value => foldNext(i, op(prevValue, value), op) }

  /**
   *  A fold over the specified tasks, with the start value of the given zero.
   *  The fold is performed on the thread where the last task is completed,
   *  the result will be the first failure of any of the tasks, or any failure 
   *  in the actual fold, or the result of the fold.
   *
   *  Example:
   *  {{{
   *    val taskSum = Task.fold(tasks)(0)(_ + _)
   *  }}}
   *
   * @tparam T       the type of the value of the input Tasks
   * @tparam R       the type of the value of the returned `Task`
   * @param tasks  	 the `TraversableOnce` of Tasks to be folded
   * @param zero     the start value of the fold
   * @param op       the fold operation to be applied to the zero and tasks
   * @return         the `Task` holding the result of the fold
   */
  @deprecated("use Task.foldLeft instead", "2.12.0")
  final def fold[T, R](@deprecatedName('futures) tasks: TraversableOnce[Task[T]])(zero: R)(@deprecatedName('foldFun) op: (R, T) => R): Task[R] =
    if (tasks.isEmpty) successful(zero)
    else sequence(tasks).map(_.foldLeft(zero)(op))

  /**
   *  Fold over the supplied tasks where the fold-zero is the result value of the
   *  `Task` that's completed first.
   *
   *  Example:
   *  {{{
   *    val taskSum = Task.reduce(tasks)(_ + _)
   *  }}}
   * @tparam T       the type of the value of the input Tasks
   * @tparam R       the type of the value of the returned `Task`
   * @param tasks    the `TraversableOnce` of Tasks to be reduced
   * @param op       the reduce operation which is applied to the results of the tasks
   * @return         the `Task` holding the result of the reduce
   */
  @deprecated("use Task.reduceLeft instead", "2.12.0")
  final def reduce[T, R >: T](@deprecatedName('futures) tasks: TraversableOnce[Task[T]])(op: (R, T) => R): Task[R] =
    if (tasks.isEmpty) failed(new NoSuchElementException("reduce attempted on empty collection"))
    else sequence(tasks).map(_ reduceLeft op)

  /**
   *  Left reduction over the supplied tasks where the zero is the result
   *  value of the first `Task`.
   *
   *  Example:
   *  {{{
   *    val taskSum = Task.reduceLeft(tasks)(_ + _)
   *  }}}
   * @tparam T       the type of the value of the input Tasks
   * @tparam R       the type of the value of the returned `Task`
   * @param tasks    the `scala.collection.immutable.Iterable` of Tasks to be reduced
   * @param op       the reduce operation which is applied to the results of the tasks
   * @return         the `Task` holding the result of the reduce
   */
  final def reduceLeft[T, R >: T](@deprecatedName('futures) tasks: scala.collection.immutable.Iterable[Task[T]])(op: (R, T) => R): Task[R] = {
    val i = tasks.iterator
    if (!i.hasNext) failed(new NoSuchElementException("reduceLeft attempted on empty collection"))
    else i.next() flatMap { v => foldNext(i, v, op) }
  }

  /**
   *  Transforms a `TraversableOnce[A]` into a `Task[TraversableOnce[B]]` using the
   *  provided function `A => Task[B]`. This is useful for performing a parallel map.
   *  For example, to apply a function to all items of a list
   *  in parallel:
   *
   *  {{{
   *    val myTaskList = Task.traverse(myList)(x => Task(myFunc(x)))
   *  }}}
   * @tparam A        the type of the value inside the Tasks in the `TraversableOnce`
   * @tparam B        the type of the value of the returned `Task`
   * @tparam M        the type of the `TraversableOnce` of Tasks
   * @param in        the `TraversableOnce` of Tasks which will be sequenced
   * @param fn        the function to apply to the `TraversableOnce` of Tasks to produce the results
   * @return          the `Task` of the `TraversableOnce` of results
   */
  final def traverse[A, B, M[X] <: TraversableOnce[X]](in: M[A])(fn: A => Task[B])(implicit cbf: CanBuildFrom[M[A], B, M[B]]): Task[M[B]] =
    in.foldLeft(successful(cbf(in))) {
      (fr, a) => fr.zipWith(fn(a))(_ += _)
    }.map(_.result())
}
