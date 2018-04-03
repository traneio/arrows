package arrows.twitter

import com.twitter.util._
import scala.annotation.switch
import scala.collection.mutable.ArrayBuffer
import scala.util.control.{ NonFatal => ScalaNonFatal }

import com.twitter.util.Future.NextThrewException

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
 * the `Task` companion object.
 */
final object Task {

  import ArrowImpl._

  final val DEFAULT_TIMEOUT: Duration = Duration.Top

  /** A successfully satisfied constant `Unit`-typed `Task` of `()` */
  final val Unit: Task[Unit] = value(())

  /** A successfully satisfied constant `Unit`-typed `Task` of `()` */
  final val Done: Task[Unit] = Unit

  /**
   * A successfully satisfied constant `Task` of `Void`-type.
   * Can be useful for Java programmers.
   */
  final val Void: Task[Void] = value[Void](null: Void)

  /** A successfully satisfied constant `Task` of `None` */
  final val None: Task[Option[Nothing]] = value(scala.None)

  /** A successfully satisfied constant `Task` of `Nil` */
  final val Nil: Task[Seq[Nothing]] = value(scala.Nil)

  /** A successfully satisfied constant `Task` of `true` */
  final val True: Task[Boolean] = value(true)

  /** A successfully satisfied constant `Task` of `false` */
  final val False: Task[Boolean] = value(false)

  /**
   * A failed `Task` analogous to `Predef.???`.
   */
  final val ??? : Task[Nothing] =
    exception(new NotImplementedError("an implementation is missing"))

  /**
   * Creates a `Task` from a `Task`. This method takes a
   * by-name parameter so the `Task` creation can happen
   * lazily during execution.
   *
   * Avoid creating the `Task` eagerly since it won't
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
    new FromFuture(f)

  /**
   * Creates a satisfied `Task` from a [[Try]].
   *
   * @see [[value]] for creation from a constant value.
   * @see [[apply]] for creation from a `Function`.
   * @see [[exception]] for creation from a `Throwable`.
   */
  final def const[T](result: Try[T]): Task[T] =
    result match {
      case r: Return[_] => value(r.r)
      case r: Throw[_]  => exception(r.e)
    }

  /**
   * Creates a successful satisfied `Task` from the value `a`.
   *
   * @see [[const]] for creation from a [[Try]]
   * @see [[apply]] for creation from a `Function`.
   * @see [[exception]] for creation from a `Throwable`.
   */
  final def value[T](v: T): Task[T] = Value(v)

  /**
   * Creates a failed satisfied `Task`.
   *
   * For example, {{{Task.exception(new Exception("boo"))}}}.
   *
   * @see [[apply]] for creation from a `Function`.
   */
  final def exception[T](ex: Throwable): Task[T] = Exception(ex)

  /**
   * Forks the execution of a `Task` using the provided
   * task pool.
   */
  final def fork[T](pool: FuturePool)(t: Task[T]): Task[T] = Arrow.fork(pool)(t)

  /**
   * A `Task` that can never be satisfied.
   */
  // TODO optimize
  final val never: Task[Nothing] = async(Future.never)

  /**
   * A `Unit`-typed `Task` that is satisfied after `howlong`.
   */
  final def sleep(howlong: Duration)(implicit timer: Timer): Task[Unit] =
    new TransformFuture[Unit, Unit, Unit] {
      override final def a = Identity
      override final def future(fut: Future[Unit]) = Future.sleep(howlong)
    }

  /**
   * Creates a satisfied `Task` from the result of running `a`.
   *
   * If the result of `a` is a [[NonFatal non-fatal exception]],
   * this will result in a failed `Task`. Otherwise, the result
   * is a successfully satisfied `Task`.
   *
   * @note that `a` is executed in the execution thread and as such
   *       some care must be taken with blocking code.
   */
  final def apply[A](a: => A): Task[A] =
    Arrow[Unit].map(_ => a)

  /**
   * Run the arrow while installing a [[Monitor]] that
   * translates any exception thrown into an encoded one.  If an
   * exception is thrown anywhere, the underlying computation is
   * interrupted with that exception.
   *
   * This function is usually called to wrap a computation that
   * returns a Task (f0) whose value is satisfied by the invocation
   * of an onSuccess/onFailure/ensure callbacks of another task
   * (f1).  If an exception happens in the callbacks on f1, f0 is
   * never satisfied.  In this example, `Task.monitored { f1
   * onSuccess g; f0 }` will cancel f0 so that f0 never hangs.
   */
  final def monitored[T, U](a: Arrow[T, U]): Arrow[T, U] =
    new Wrap[T, U] {
      override final def arrow = a
      override final def wrap(f: => Future[U]): Future[U] =
        Future.monitored(f)
    }

  /**
   * Collect the results from the given tasks into a new task of
   * `Seq[A]`. If one or more of the given Tasks is exceptional, the resulting
   * Task result will be the first exception encountered.
   *
   * @param fs a sequence of Tasks
   * @return a `Task[Seq[A]]` containing the collected values from fs.
   *
   * @see [[collectToTry]] if you want to be able to see the results of each
   *     `Task` regardless of if they succeed or fail.
   * @see [[join]] if you are not interested in the results of the individual
   *     `Tasks`, only when they are complete.
   */
  final def collect[A](fs: Seq[Task[A]]): Task[Seq[A]] = Arrow.collect(fs)

  /**
   * Creates a `Task` that is satisfied when all tasks in `fs`
   * are successfully satisfied. If any of the tasks in `fs` fail,
   * the returned `Task` is immediately satisfied by that failure.
   *
   * @param fs a sequence of Tasks
   *
   * @see [[collect]] if you want to be able to see the results of each `Task`.
   * @see [[collectToTry]] if you want to be able to see the results of each
   *     `Task` regardless of if they succeed or fail.
   */
  final def join[A](fs: Seq[Task[A]]): Task[Unit] =
    collect(fs).unit

  private[this] final val toTupleInstance =
    (seq: Seq[Any]) => {
      val size = seq.size
      val it = seq.iterator
      def v = it.next
      (size: @switch) match {
        case 2  => (v, v)
        case 3  => (v, v, v)
        case 4  => (v, v, v, v)
        case 5  => (v, v, v, v, v)
        case 6  => (v, v, v, v, v, v)
        case 7  => (v, v, v, v, v, v, v)
        case 8  => (v, v, v, v, v, v, v, v)
        case 9  => (v, v, v, v, v, v, v, v, v)
        case 10 => (v, v, v, v, v, v, v, v, v, v)
        case 11 => (v, v, v, v, v, v, v, v, v, v, v)
        case 12 => (v, v, v, v, v, v, v, v, v, v, v, v)
        case 13 => (v, v, v, v, v, v, v, v, v, v, v, v, v)
        case 14 => (v, v, v, v, v, v, v, v, v, v, v, v, v, v)
        case 15 => (v, v, v, v, v, v, v, v, v, v, v, v, v, v, v)
        case 16 => (v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v)
        case 17 => (v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v)
        case 18 => (v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v)
        case 19 => (v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v)
        case 20 => (v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v)
        case 21 => (v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v)
        case 22 => (v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v)
      }
    }

  private[this] final def toTuple[A, B] = toTupleInstance.asInstanceOf[Seq[A] => B]

  /**
   * Joins 2 tasks. The returned task is complete when all
   * underlying tasks complete. It fails immediately if any of them
   * do.
   */
  final def join[A, B](a: Task[A], b: Task[B]): Task[(A, B)] =
    collect(Seq(a, b)).map(toTuple)

  /**
   * Joins 3 tasks. The returned task is complete when all
   * underlying tasks complete. It fails immediately if any of them
   * do.
   */
  final def join[A, B, C](a: Task[A], b: Task[B], c: Task[C]): Task[(A, B, C)] =
    collect(Seq(a, b, c)).map(toTuple)

  /**
   * Joins 4 tasks. The returned task is complete when all
   * underlying tasks complete. It fails immediately if any of them
   * do.
   */
  final def join[A, B, C, D](
    a: Task[A],
    b: Task[B],
    c: Task[C],
    d: Task[D]
  ): Task[(A, B, C, D)] =
    collect(Seq(a, b, c, d)).map(toTuple)

  /**
   * Joins 5 tasks. The returned task is complete when all
   * underlying tasks complete. It fails immediately if any of them
   * do.
   */
  final def join[A, B, C, D, E](
    a: Task[A],
    b: Task[B],
    c: Task[C],
    d: Task[D],
    e: Task[E]
  ): Task[(A, B, C, D, E)] =
    collect(Seq(a, b, c, d, e)).map(toTuple)

  /**
   * Joins 6 tasks. The returned task is complete when all
   * underlying tasks complete. It fails immediately if any of them
   * do.
   */
  final def join[A, B, C, D, E, F](
    a: Task[A],
    b: Task[B],
    c: Task[C],
    d: Task[D],
    e: Task[E],
    f: Task[F]
  ): Task[(A, B, C, D, E, F)] =
    collect(Seq(a, b, c, d, e, f)).map(toTuple)

  /**
   * Joins 7 tasks. The returned task is complete when all
   * underlying tasks complete. It fails immediately if any of them
   * do.
   */
  final def join[A, B, C, D, E, F, G](
    a: Task[A],
    b: Task[B],
    c: Task[C],
    d: Task[D],
    e: Task[E],
    f: Task[F],
    g: Task[G]
  ): Task[(A, B, C, D, E, F, G)] =
    collect(Seq(a, b, c, d, e, f, g)).map(toTuple)

  /**
   * Joins 8 tasks. The returned task is complete when all
   * underlying tasks complete. It fails immediately if any of them
   * do.
   */
  final def join[A, B, C, D, E, F, G, H](
    a: Task[A],
    b: Task[B],
    c: Task[C],
    d: Task[D],
    e: Task[E],
    f: Task[F],
    g: Task[G],
    h: Task[H]
  ): Task[(A, B, C, D, E, F, G, H)] =
    collect(Seq(a, b, c, d, e, f, g, h)).map(toTuple)

  /**
   * Joins 9 tasks. The returned task is complete when all
   * underlying tasks complete. It fails immediately if any of them
   * do.
   */
  final def join[A, B, C, D, E, F, G, H, I](
    a: Task[A],
    b: Task[B],
    c: Task[C],
    d: Task[D],
    e: Task[E],
    f: Task[F],
    g: Task[G],
    h: Task[H],
    i: Task[I]
  ): Task[(A, B, C, D, E, F, G, H, I)] =
    collect(Seq(a, b, c, d, e, f, g, h, i)).map(toTuple)

  /**
   * Joins 10 tasks. The returned task is complete when all
   * underlying tasks complete. It fails immediately if any of them
   * do.
   */
  final def join[A, B, C, D, E, F, G, H, I, J](
    a: Task[A],
    b: Task[B],
    c: Task[C],
    d: Task[D],
    e: Task[E],
    f: Task[F],
    g: Task[G],
    h: Task[H],
    i: Task[I],
    j: Task[J]
  ): Task[(A, B, C, D, E, F, G, H, I, J)] =
    collect(Seq(a, b, c, d, e, f, g, h, i, j)).map(toTuple)

  /**
   * Joins 11 tasks. The returned task is complete when all
   * underlying tasks complete. It fails immediately if any of them
   * do.
   */
  final def join[A, B, C, D, E, F, G, H, I, J, K](
    a: Task[A],
    b: Task[B],
    c: Task[C],
    d: Task[D],
    e: Task[E],
    f: Task[F],
    g: Task[G],
    h: Task[H],
    i: Task[I],
    j: Task[J],
    k: Task[K]
  ): Task[(A, B, C, D, E, F, G, H, I, J, K)] =
    collect(Seq(a, b, c, d, e, f, g, h, i, j, k)).map(toTuple)

  /**
   * Joins 12 tasks. The returned task is complete when all
   * underlying tasks complete. It fails immediately if any of them
   * do.
   */
  final def join[A, B, C, D, E, F, G, H, I, J, K, L](
    a: Task[A],
    b: Task[B],
    c: Task[C],
    d: Task[D],
    e: Task[E],
    f: Task[F],
    g: Task[G],
    h: Task[H],
    i: Task[I],
    j: Task[J],
    k: Task[K],
    l: Task[L]
  ): Task[(A, B, C, D, E, F, G, H, I, J, K, L)] =
    collect(Seq(a, b, c, d, e, f, g, h, i, j, k, l)).map(toTuple)

  /**
   * Joins 13 tasks. The returned task is complete when all
   * underlying tasks complete. It fails immediately if any of them
   * do.
   */
  final def join[A, B, C, D, E, F, G, H, I, J, K, L, M](
    a: Task[A],
    b: Task[B],
    c: Task[C],
    d: Task[D],
    e: Task[E],
    f: Task[F],
    g: Task[G],
    h: Task[H],
    i: Task[I],
    j: Task[J],
    k: Task[K],
    l: Task[L],
    m: Task[M]
  ): Task[(A, B, C, D, E, F, G, H, I, J, K, L, M)] =
    collect(Seq(a, b, c, d, e, f, g, h, i, j, k, l, m)).map(toTuple)

  /**
   * Joins 14 tasks. The returned task is complete when all
   * underlying tasks complete. It fails immediately if any of them
   * do.
   */
  final def join[A, B, C, D, E, F, G, H, I, J, K, L, M, N](
    a: Task[A],
    b: Task[B],
    c: Task[C],
    d: Task[D],
    e: Task[E],
    f: Task[F],
    g: Task[G],
    h: Task[H],
    i: Task[I],
    j: Task[J],
    k: Task[K],
    l: Task[L],
    m: Task[M],
    n: Task[N]
  ): Task[(A, B, C, D, E, F, G, H, I, J, K, L, M, N)] =
    collect(Seq(a, b, c, d, e, f, g, h, i, j, k, l, m, n)).map(toTuple)

  /**
   * Joins 15 tasks. The returned task is complete when all
   * underlying tasks complete. It fails immediately if any of them
   * do.
   */
  final def join[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O](
    a: Task[A],
    b: Task[B],
    c: Task[C],
    d: Task[D],
    e: Task[E],
    f: Task[F],
    g: Task[G],
    h: Task[H],
    i: Task[I],
    j: Task[J],
    k: Task[K],
    l: Task[L],
    m: Task[M],
    n: Task[N],
    o: Task[O]
  ): Task[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O)] =
    collect(Seq(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o)).map(toTuple)

  /**
   * Joins 16 tasks. The returned task is complete when all
   * underlying tasks complete. It fails immediately if any of them
   * do.
   */
  final def join[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P](
    a: Task[A],
    b: Task[B],
    c: Task[C],
    d: Task[D],
    e: Task[E],
    f: Task[F],
    g: Task[G],
    h: Task[H],
    i: Task[I],
    j: Task[J],
    k: Task[K],
    l: Task[L],
    m: Task[M],
    n: Task[N],
    o: Task[O],
    p: Task[P]
  ): Task[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P)] =
    collect(Seq(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p)).map(toTuple)

  /**
   * Joins 17 tasks. The returned task is complete when all
   * underlying tasks complete. It fails immediately if any of them
   * do.
   */
  final def join[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q](
    a: Task[A],
    b: Task[B],
    c: Task[C],
    d: Task[D],
    e: Task[E],
    f: Task[F],
    g: Task[G],
    h: Task[H],
    i: Task[I],
    j: Task[J],
    k: Task[K],
    l: Task[L],
    m: Task[M],
    n: Task[N],
    o: Task[O],
    p: Task[P],
    q: Task[Q]
  ): Task[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q)] =
    collect(Seq(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q)).map(toTuple)

  /**
   * Joins 18 tasks. The returned task is complete when all
   * underlying tasks complete. It fails immediately if any of them
   * do.
   */
  final def join[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R](
    a: Task[A],
    b: Task[B],
    c: Task[C],
    d: Task[D],
    e: Task[E],
    f: Task[F],
    g: Task[G],
    h: Task[H],
    i: Task[I],
    j: Task[J],
    k: Task[K],
    l: Task[L],
    m: Task[M],
    n: Task[N],
    o: Task[O],
    p: Task[P],
    q: Task[Q],
    r: Task[R]
  ): Task[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R)] =
    collect(Seq(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r)).map(toTuple)

  /**
   * Joins 19 tasks. The returned task is complete when all
   * underlying tasks complete. It fails immediately if any of them
   * do.
   */
  final def join[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S](
    a: Task[A],
    b: Task[B],
    c: Task[C],
    d: Task[D],
    e: Task[E],
    f: Task[F],
    g: Task[G],
    h: Task[H],
    i: Task[I],
    j: Task[J],
    k: Task[K],
    l: Task[L],
    m: Task[M],
    n: Task[N],
    o: Task[O],
    p: Task[P],
    q: Task[Q],
    r: Task[R],
    s: Task[S]
  ): Task[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S)] =
    collect(Seq(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s)).map(toTuple)

  /**
   * Joins 20 tasks. The returned task is complete when all
   * underlying tasks complete. It fails immediately if any of them
   * do.
   */
  final def join[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T](
    a: Task[A],
    b: Task[B],
    c: Task[C],
    d: Task[D],
    e: Task[E],
    f: Task[F],
    g: Task[G],
    h: Task[H],
    i: Task[I],
    j: Task[J],
    k: Task[K],
    l: Task[L],
    m: Task[M],
    n: Task[N],
    o: Task[O],
    p: Task[P],
    q: Task[Q],
    r: Task[R],
    s: Task[S],
    t: Task[T]
  ): Task[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T)] =
    collect(Seq(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t)).map(toTuple)

  /**
   * Joins 21 tasks. The returned task is complete when all
   * underlying tasks complete. It fails immediately if any of them
   * do.
   */
  final def join[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U](
    a: Task[A],
    b: Task[B],
    c: Task[C],
    d: Task[D],
    e: Task[E],
    f: Task[F],
    g: Task[G],
    h: Task[H],
    i: Task[I],
    j: Task[J],
    k: Task[K],
    l: Task[L],
    m: Task[M],
    n: Task[N],
    o: Task[O],
    p: Task[P],
    q: Task[Q],
    r: Task[R],
    s: Task[S],
    t: Task[T],
    u: Task[U]
  ): Task[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U)] =
    collect(Seq(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u)).map(toTuple)

  /**
   * Joins 22 tasks. The returned task is complete when all
   * underlying tasks complete. It fails immediately if any of them
   * do.
   */
  final def join[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V](
    a: Task[A],
    b: Task[B],
    c: Task[C],
    d: Task[D],
    e: Task[E],
    f: Task[F],
    g: Task[G],
    h: Task[H],
    i: Task[I],
    j: Task[J],
    k: Task[K],
    l: Task[L],
    m: Task[M],
    n: Task[N],
    o: Task[O],
    p: Task[P],
    q: Task[Q],
    r: Task[R],
    s: Task[S],
    t: Task[T],
    u: Task[U],
    v: Task[V]
  ): Task[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V)] =
    collect(Seq(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v)).map(toTuple)

  /**
   * Take a sequence and sequentially apply a function `f` to each item.
   * Then return all task results `as` as a single `Task[Seq[_]]`.
   *
   * If during execution any `f` is satisfied `as` as a failure ([[Task.exception]])
   * then that failed Task will be returned and the remaining elements of `as`
   * will not be processed.
   *
   * usage:
   *  {{{
   *    // will return a Task of `Seq(2, 3, 4)`
   *    Task.traverseSequentially(Seq(1, 2, 3)) { i =>
   *      Task(i + 1)
   *    }
   *  }}}
   *
   * @param `as` a sequence of `A` that will have `f` applied to each item sequentially
   * @return a `Task[Seq[B]]` containing the results of `f` being applied to every item in `as`
   */
  final def traverseSequentially[A, B](as: Seq[A])(f: A => Task[B]): Task[Seq[B]] =
    as.foldLeft(Nil: Task[Seq[B]]) {
      case (p, v) => p.flatMap(seq => f(v).map(seq :+ _))
    }

  // TODO
  //  final def collect[A, B](fs: Map[A, Task[B]]): Task[Map[A, B]] =

  /**
   * Collect the results from the given tasks into a new task of `Seq[Try[A]]`.
   *
   * The returned `Task` returns when all of the given `Task`s
   * return.
   *
   * @param fs a sequence of Tasks
   * @return a `Task[Seq[Try[A]]]` containing the collected values from fs.
   */
  // TODO optimize
  final def collectToTry[A](fs: Seq[Task[A]]): Task[Seq[Try[A]]] =
    collect(fs.map(_.liftToTry))

  /**
   * "Select" off the first task to be satisfied.  Return this as a
   * result, with the remainder of the Tasks as a sequence.
   *
   * @param fs the tasks to select from. Must not be empty.
   *
   * @see [[selectIndex]] which can be more performant in some situations.
   */
  // TODO optimize
  final def select[A](fs: Seq[Task[A]]): Task[(Try[A], Seq[Task[A]])] =
    Task.async {
      Future.select(fs.map(_.run)).map {
        case (t, futs) => (t, futs.map(async(_)))
      }
    }

  /**
   * Select the index into `fs` of the first task to be satisfied.
   *
   * @param fs cannot be empty
   *
   * @see [[select]] which can be an easier API to use.
   */
  // TODO optimize
  final def selectIndex[A](fs: IndexedSeq[Task[A]]): Task[Int] =
    Task.async(Future.selectIndex(fs.map(_.run)))

  /**
   * Repeat a computation that returns a Task some number of times, after each
   * computation completes.
   */
  final def times[A](n: Int)(f: Task[A]): Task[Unit] = {
    var i = 0
    def loop(): Task[Unit] = {
      if (i < n) {
        i += 1
        f.flatMap { _ =>
          loop()
        }
      } else Task.Unit
    }
    loop()
  }

  /**
   * Perform the effects of the supplied Task only when the provided
   * flag is true.
   */
  final def when[A](p: Boolean)(f: Task[A]): Task[Unit] =
    if (p) f.unit else Unit

  /**
   * Repeat a computation that returns a Task while some predicate obtains,
   * after each computation completes.
   */
  final def whileDo[A](p: => Boolean)(f: Task[A]): Task[Unit] = {
    def loop(): Task[Unit] = {
      if (p) f.flatMap { _ =>
        loop()
      }
      else Task.Unit
    }
    loop()
  }

  /**
   * Produce values from `next` until it fails synchronously
   * applying `body` to each iteration. The returned `Task`
   * indicates completion via a failed `Task`.
   */
  final def each[A](next: Task[A])(body: A => Unit): Task[Nothing] = {
    def go(): Task[Nothing] =
      try next.flatMap { a =>
        body(a)
        go()
      } catch {
        case ScalaNonFatal(exc) =>
          Task.exception(NextThrewException(exc))
      }

    go()
  }

  /**
   * Runs tasks in parallel.
   */
  final def parallel[A](n: Int)(f: Task[A]): Seq[Task[A]] = {
    var i = 0
    val result = new ArrayBuffer[Task[A]](n)
    while (i < n) {
      result += f
      i += 1
    }
    result
  }
}
