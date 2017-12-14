package com.twitter.arrows

import language.higherKinds
import com.twitter.util.Future
import com.twitter.util.Try

private[arrows] object Ast {

  case object Identity extends Arrow[Any, Nothing]
  case object Never extends Task[Nothing]
  case class Value[T](v: T) extends Task[T]
  case class Exception[T](ex: Throwable) extends Task[T]
  case class AndThen[-T, U, +V](a: Arrow[T, U], b: Arrow[U, V]) extends Arrow[T, V]
  case class Map[-T, U, +V](a: Arrow[T, U], f: U => V) extends Arrow[T, V]
  case class FlatMap[-T, U, +V](a: Arrow[T, U], f: U => Task[V]) extends Arrow[T, V]
  case class Recursive[T, U](rec: Arrow[T, U] => Arrow[T, U]) extends Arrow[T, U] {
    val f = rec(this)
  }

  case class Apply[T, U](value: T, a: Arrow[T, U]) extends Task[U]

  trait Transform[T, U, V] extends Arrow[T, V] {
    def a: Arrow[T, U]
    def future(f: Future[U]): Future[V]
  }

  trait JoinTask[T, U, V, X] extends Arrow[T, X] {
    def a: Arrow[T, U]
    def p: Task[V]
    def future(u: Future[U], v: Future[V]): Future[X]
  }

  trait TransformSeq[S[_] <: Seq[_], T, U] extends Task[U] {
    def seq: S[Task[T]]
    def f(seq: S[Future[T]]): Future[U]
  }
}
