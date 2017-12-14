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

object Task {

  import Ast._

  val DEFAULT_TIMEOUT: Duration = Duration.Top

  val Unit: Task[Unit] = value(())

  val Done: Task[Unit] = Unit

  val Void: Task[Void] = value[Void](null: Void)

  val None: Task[Option[Nothing]] = value(scala.None)

  val Nil: Task[Seq[Nothing]] = value(scala.Nil)

  val True: Task[Boolean] = value(true)

  val False: Task[Boolean] = value(false)

  val ??? : Task[Nothing] =
    exception(new NotImplementedError("an implementation is missing"))

  def fromFuture[T](f: => Future[T]): Task[T] =
    new Transform[Unit, Unit, T] {
      override def a = Identity
      override def future(fut: Future[Unit]) = f
    }

  // TODO review
  def const[T](result: Try[T]): Task[T] =
    if (result.isReturn)
      value(result.asInstanceOf[Return[T]]())
    else
      exception(result.asInstanceOf[Throw[T]].throwable)

  def value[T](v: T): Task[T] = Value(v)

  def exception[T](ex: Throwable): Task[T] = Exception(ex)

  val never: Task[Nothing] = Never

  def sleep(howlong: Duration)(implicit timer: Timer): Task[Unit] =
    new Transform[Unit, Unit, Unit] {
      override def a = Identity
      override def future(fut: Future[Unit]) = Future.sleep(howlong)
    }

  def apply[A](a: => A): Task[A] =
    try value(a)
    catch {
      case ex if NonFatal(ex) => exception(ex)
    }

  // TODO
  //  def monitored[T, U](a: Arrow[T, U]): Arrow[T, U] =

  def collect[A](fs: Seq[Task[A]]): Task[Seq[A]] =
    new TransformSeq[Seq, A, Seq[A]] {
      override def seq = fs
      override def f(seq: Seq[Future[A]]) = Future.collect(seq)
    }

  def join[A](fs: Seq[Task[A]]): Task[Unit] =
    new TransformSeq[Seq, A, Unit] {
      override def seq = fs
      override def f(seq: Seq[Future[A]]) = Future.join(seq)
    }

  private def toTuple[A, T](seq: Seq[A]): T = {
    val size = seq.size
    val it = seq.iterator
    def v = it.next
    val tuple =
      if (size == 2) (v, v)
      else if (size == 3) (v, v, v)
      else if (size == 4) (v, v, v, v)
      else if (size == 5) (v, v, v, v, v)
      else if (size == 6) (v, v, v, v, v, v)
      else if (size == 7) (v, v, v, v, v, v, v)
      else if (size == 8) (v, v, v, v, v, v, v, v)
      else if (size == 9) (v, v, v, v, v, v, v, v, v)
      else if (size == 10) (v, v, v, v, v, v, v, v, v, v)
      else if (size == 11) (v, v, v, v, v, v, v, v, v, v, v)
      else if (size == 12) (v, v, v, v, v, v, v, v, v, v, v, v)
      else if (size == 13) (v, v, v, v, v, v, v, v, v, v, v, v, v)
      else if (size == 14) (v, v, v, v, v, v, v, v, v, v, v, v, v, v)
      else if (size == 15) (v, v, v, v, v, v, v, v, v, v, v, v, v, v, v)
      else if (size == 16) (v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v)
      else if (size == 17) (v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v)
      else if (size == 18) (v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v)
      else if (size == 19) (v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v)
      else if (size == 20) (v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v)
      else if (size == 21) (v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v)
      else if (size == 22) (v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v, v)
      else throw new MatchError(seq)
    tuple.asInstanceOf[T]
  }

  def join[A, B](a: Task[A], b: Task[B]): Task[(A, B)] =
    collect(Seq(a, b)).map(toTuple)

  def join[A, B, C](a: Task[A], b: Task[B], c: Task[C]): Task[(A, B, C)] =
    collect(Seq(a, b, c)).map(toTuple)

  def join[A, B, C, D](
    a: Task[A],
    b: Task[B],
    c: Task[C],
    d: Task[D]
  ): Task[(A, B, C, D)] =
    collect(Seq(a, b, c, c)).map(toTuple)

  def join[A, B, C, D, E](
    a: Task[A],
    b: Task[B],
    c: Task[C],
    d: Task[D],
    e: Task[E]
  ): Task[(A, B, C, D, E)] =
    collect(Seq(a, b, c, c, e)).map(toTuple)

  def join[A, B, C, D, E, F](
    a: Task[A],
    b: Task[B],
    c: Task[C],
    d: Task[D],
    e: Task[E],
    f: Task[F]
  ): Task[(A, B, C, D, E, F)] =
    collect(Seq(a, b, c, c, e, f)).map(toTuple)

  def join[A, B, C, D, E, F, G](
    a: Task[A],
    b: Task[B],
    c: Task[C],
    d: Task[D],
    e: Task[E],
    f: Task[F],
    g: Task[G]
  ): Task[(A, B, C, D, E, F, G)] =
    collect(Seq(a, b, c, c, e, f, g)).map(toTuple)

  def join[A, B, C, D, E, F, G, H](
    a: Task[A],
    b: Task[B],
    c: Task[C],
    d: Task[D],
    e: Task[E],
    f: Task[F],
    g: Task[G],
    h: Task[H]
  ): Task[(A, B, C, D, E, F, G, H)] =
    collect(Seq(a, b, c, c, e, f, g, h)).map(toTuple)

  def join[A, B, C, D, E, F, G, H, I](
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
    collect(Seq(a, b, c, c, e, f, g, h, i)).map(toTuple)

  def join[A, B, C, D, E, F, G, H, I, J](
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
    collect(Seq(a, b, c, c, e, f, g, h, i, j)).map(toTuple)

  def join[A, B, C, D, E, F, G, H, I, J, K](
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
    collect(Seq(a, b, c, c, e, f, g, h, i, j, k)).map(toTuple)

  def join[A, B, C, D, E, F, G, H, I, J, K, L](
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
    collect(Seq(a, b, c, c, e, f, g, h, i, j, k, l)).map(toTuple)

  def join[A, B, C, D, E, F, G, H, I, J, K, L, M](
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
    collect(Seq(a, b, c, c, e, f, g, h, i, j, k, l, m)).map(toTuple)

  def join[A, B, C, D, E, F, G, H, I, J, K, L, M, N](
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
    collect(Seq(a, b, c, c, e, f, g, h, i, j, k, l, m, n)).map(toTuple)

  def join[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O](
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
    collect(Seq(a, b, c, c, e, f, g, h, i, j, k, l, m, n, o)).map(toTuple)

  def join[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P](
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
    collect(Seq(a, b, c, c, e, f, g, h, i, j, k, l, m, n, o, p)).map(toTuple)

  def join[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q](
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
    collect(Seq(a, b, c, c, e, f, g, h, i, j, k, l, m, n, o, p, q)).map(toTuple)

  def join[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R](
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
    collect(Seq(a, b, c, c, e, f, g, h, i, j, k, l, m, n, o, p, q, r)).map(toTuple)

  def join[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S](
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
    collect(Seq(a, b, c, c, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s)).map(toTuple)

  def join[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T](
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
    collect(Seq(a, b, c, c, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t)).map(toTuple)

  def join[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U](
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
    collect(Seq(a, b, c, c, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u)).map(toTuple)

  def join[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V](
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
    collect(Seq(a, b, c, c, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v)).map(toTuple)

  def traverseSequentially[A, B](as: Seq[A])(f: A => Task[B]): Task[Seq[B]] =
    as.foldLeft(Nil: Task[Seq[B]]) {
      case (p, v) => p.flatMap(seq => f(v).map(seq :+ _))
    }

  // TODO
  //  def collect[A, B](fs: Map[A, Future[B]]): Future[Map[A, B]] =

  def collectToTry[A](fs: Seq[Task[A]]): Task[Seq[Try[A]]] =
    new TransformSeq[Seq, A, Seq[Try[A]]] {
      override def seq = fs
      override def f(seq: Seq[Future[A]]) = Future.collectToTry(seq)
    }

  // TODO review performance
  def select[A](fs: Seq[Task[A]]): Task[(Try[A], Seq[Task[A]])] =
    new TransformSeq[Seq, A, (Try[A], Seq[Task[A]])] {
      override def seq = fs
      override def f(seq: Seq[Future[A]]) =
        Future.select(seq).map {
          case (t, futs) => (t, futs.map(fromFuture(_)))
        }
    }

  def selectIndex[A](fs: IndexedSeq[Task[A]]): Task[Int] =
    new TransformSeq[IndexedSeq, A, Int] {
      override def seq = fs
      override def f(seq: IndexedSeq[Future[A]]) = Future.selectIndex(seq)
    }

  def times[A](n: Int)(f: Task[A]): Task[Unit] = {
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

  def when[A](p: Boolean)(f: Task[A]): Task[Unit] =
    if (p) f.unit else Unit

  def whileDo[A](p: => Boolean)(f: Task[A]): Task[Unit] = {
    def loop(): Task[Unit] = {
      if (p) f.flatMap { _ =>
        loop()
      }
      else Task.Unit
    }
    loop()
  }

  def each[A](next: Task[A])(body: A => Unit): Task[Nothing] = {
    def go(): Task[Nothing] =
      try next.flatMap { a =>
        body(a); go()
      } catch {
        case NonFatal(exc) =>
          Task.exception(NextThrewException(exc))
      }

    go()
  }

  def parallel[A](n: Int)(f: Task[A]): Seq[Task[A]] = {
    var i = 0
    val result = new ArrayBuffer[Task[A]](n)
    while (i < n) {
      result += f
      i += 1
    }
    result
  }
}