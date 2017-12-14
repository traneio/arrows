package com.twitter.arrows

import com.twitter.util.Future
import scala.util.control.NonFatal

private[arrows] object Interpreter {
  import Ast._

  def apply[T, U](value: T, a: Arrow[T, U]): Future[U] = {

    def fail[T](ex: Throwable) =
      if (NonFatal(ex))
        Future.exception(ex)
      else
        throw ex

    def map[T, U, V](value: T, a: Map[T, U, V]): Future[V] =
      if (a.a eq Identity)
        try Future.value(a.f(value.asInstanceOf[U]))
        catch {
          case e: Throwable => fail(e)
        }
      else if (a.a.isInstanceOf[Value[_]]) {
        val value = a.a.asInstanceOf[Value[U]].v
        try Future.value(a.f(value))
        catch {
          case e: Throwable => fail(e)
        }
      } else
        apply(value, a.a).map(a.f)

    def flatMap[T, U, V](value: T, a: FlatMap[T, U, V]): Future[V] =
      if (a.a eq Identity)
        try apply((), a.f(value.asInstanceOf[U]))
        catch {
          case e: Throwable => fail(e)
        }
      else if (a.a.isInstanceOf[Value[_]]) {
        val value = a.a.asInstanceOf[Value[U]].v
        try apply((), a.f(value))
        catch {
          case e: Throwable => fail(e)
        }
      } else
        apply(value, a.a).flatMap(v => apply((), a.f(v)))

    def andThen[T, U, V](value: T, a: AndThen[T, U, V]): Future[V] =
      if (a.a eq Identity)
        apply(value, a.b.asInstanceOf[Arrow[T, V]])
      else if (a.a.isInstanceOf[Value[_]]) {
        val value = a.a.asInstanceOf[Value[U]].v
        try apply(value, a.b)
        catch {
          case e: Throwable => fail(e)
        }
      } else
        apply(value, a.a).flatMap(apply(_, a.b))

    if (a eq Identity)
      Future.value(value.asInstanceOf[U])
    else if (a.isInstanceOf[Value[_]])
      Future.value(a.asInstanceOf[Value[U]].v)
    else if (a.isInstanceOf[Exception[_]])
      Future.exception(a.asInstanceOf[Exception[_]].ex)
    else if (a.isInstanceOf[Map[_, _, _]])
      map(value, a.asInstanceOf[Map[T, Any, U]])
    else if (a.isInstanceOf[FlatMap[_, _, _]])
      flatMap(value, a.asInstanceOf[FlatMap[T, Any, U]])
    else if (a.isInstanceOf[AndThen[_, _, _]])
      andThen(value, a.asInstanceOf[AndThen[T, Any, U]])
    else if (a.isInstanceOf[Recursive[_, _]])
      apply(value, a.asInstanceOf[Recursive[T, U]].f)
    else if (a.isInstanceOf[Apply[_, _]]) {
      val ap = a.asInstanceOf[Apply[Any, U]]
      apply(ap.value, ap.a)
    } else if (a.isInstanceOf[Transform[_, _, _]]) {
      val t = a.asInstanceOf[Transform[T, Any, U]]
      t.future(apply(value, t.a)) // safe since we control the function
    } else if (a.isInstanceOf[TransformSeq[Seq, _, _]]) {
      val t = a.asInstanceOf[TransformSeq[Seq, Any, U]]
      t.f(t.seq.map(apply((), _)))
    } else if (a.isInstanceOf[JoinTask[_, _, _, _]]) {
      val j = a.asInstanceOf[JoinTask[T, Any, Any, U]]
      val v = apply(value, j.a)
      val u = apply((), j.p)
      j.future(u, v)
    }
    else
      throw new MatchError(a)
  }
}

