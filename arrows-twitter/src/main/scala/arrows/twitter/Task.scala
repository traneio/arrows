package arrows.twitter

import com.twitter.util._
import scala.annotation.switch
import scala.collection.mutable.ArrayBuffer
import scala.util.control.{ NonFatal => ScalaNonFatal }

import com.twitter.util.Future.NextThrewException

// type Task[+T] = Arrow[Unit, T] (see package object)
final object Task {

  import ArrowImpl._

  final val DEFAULT_TIMEOUT: Duration = Duration.Top

  final val Unit: Task[Unit] = value(())

  final val Done: Task[Unit] = Unit

  final val Void: Task[Void] = value[Void](null: Void)

  final val None: Task[Option[Nothing]] = value(scala.None)

  final val Nil: Task[Seq[Nothing]] = value(scala.Nil)

  final val True: Task[Boolean] = value(true)

  final val False: Task[Boolean] = value(false)

  final val ??? : Task[Nothing] =
    exception(new NotImplementedError("an implementation is missing"))

  final def fromFuture[T](f: => Future[T]): Task[T] =
    new FromFuture(f)

  final def const[T](result: Try[T]): Task[T] =
    result match {
      case r: Return[_] => value(r.r)
      case r: Throw[_]  => exception(r.e)
    }

  final def value[T](v: T): Task[T] = Value(v)

  final def exception[T](ex: Throwable): Task[T] = Exception(ex)

  final def fork[T](pool: FuturePool)(t: Task[T]): Task[T] = Arrow.fork(pool)(t)

  // TODO optimize
  final val never: Task[Nothing] = fromFuture(Future.never)

  final def sleep(howlong: Duration)(implicit timer: Timer): Task[Unit] =
    new TransformFuture[Unit, Unit, Unit] {
      override final def a = Identity
      override final def future(fut: Future[Unit]) = Future.sleep(howlong)
    }

  final def apply[A](a: => A): Task[A] =
    Arrow[Unit].map(_ => a)

  final def monitored[T, U](a: Arrow[T, U]): Arrow[T, U] =
    new Wrap[T, U] {
      override def arrow = a
      override def wrap(f: => Future[U]): Future[U] =
        Future.monitored(f)
    }

  final def collect[A](fs: Seq[Task[A]]): Task[Seq[A]] = Arrow.collect(fs)

  final def join[A](fs: Seq[Task[A]]): Task[Unit] =
    collect(fs).unit

  private final val toTupleInstance =
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

  private final def toTuple[A, B] = toTupleInstance.asInstanceOf[Seq[A] => B]

  final def join[A, B](a: Task[A], b: Task[B]): Task[(A, B)] =
    collect(Seq(a, b)).map(toTuple)

  final def join[A, B, C](a: Task[A], b: Task[B], c: Task[C]): Task[(A, B, C)] =
    collect(Seq(a, b, c)).map(toTuple)

  final def join[A, B, C, D](
    a: Task[A],
    b: Task[B],
    c: Task[C],
    d: Task[D]
  ): Task[(A, B, C, D)] =
    collect(Seq(a, b, c, d)).map(toTuple)

  final def join[A, B, C, D, E](
    a: Task[A],
    b: Task[B],
    c: Task[C],
    d: Task[D],
    e: Task[E]
  ): Task[(A, B, C, D, E)] =
    collect(Seq(a, b, c, d, e)).map(toTuple)

  final def join[A, B, C, D, E, F](
    a: Task[A],
    b: Task[B],
    c: Task[C],
    d: Task[D],
    e: Task[E],
    f: Task[F]
  ): Task[(A, B, C, D, E, F)] =
    collect(Seq(a, b, c, d, e, f)).map(toTuple)

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

  final def traverseSequentially[A, B](as: Seq[A])(f: A => Task[B]): Task[Seq[B]] =
    as.foldLeft(Nil: Task[Seq[B]]) {
      case (p, v) => p.flatMap(seq => f(v).map(seq :+ _))
    }

  // TODO
  //  final def collect[A, B](fs: Map[A, Future[B]]): Future[Map[A, B]] =

  // TODO optimize
  final def collectToTry[A](fs: Seq[Task[A]]): Task[Seq[Try[A]]] =
    collect(fs.map(_.liftToTry))

  // TODO optimize
  final def select[A](fs: Seq[Task[A]]): Task[(Try[A], Seq[Task[A]])] =
    Task.fromFuture {
      Future.select(fs.map(_.run)).map {
        case (t, futs) => (t, futs.map(fromFuture(_)))
      }
    }

  // TODO optimize
  final def selectIndex[A](fs: IndexedSeq[Task[A]]): Task[Int] =
    Task.fromFuture(Future.selectIndex(fs.map(_.run)))

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

  final def when[A](p: Boolean)(f: Task[A]): Task[Unit] =
    if (p) f.unit else Unit

  final def whileDo[A](p: => Boolean)(f: Task[A]): Task[Unit] = {
    def loop(): Task[Unit] = {
      if (p) f.flatMap { _ =>
        loop()
      }
      else Task.Unit
    }
    loop()
  }

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
