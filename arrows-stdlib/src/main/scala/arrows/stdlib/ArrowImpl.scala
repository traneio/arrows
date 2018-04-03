package arrows.stdlib

import language.higherKinds
import scala.util.control.{ NonFatal => ScalaNonFatal }

import ArrowRun._
import scala.concurrent.ExecutionContext
import scala.util.Try
import scala.concurrent.Future
import scala.util.Success
import scala.util.Failure
import scala.collection.generic.CanBuildFrom

private[arrows] final object ArrowImpl {

  type Point[T] = Arrow[Any, T]

  final case object Identity extends Point[Nothing] {
    override final def runSync[B <: Any](r: Sync[B], depth: Int)(implicit ec: ExecutionContext): Result[Nothing] =
      r.as[Nothing]
  }

  final case class Apply[T, U](v: T, arrow: Arrow[T, U]) extends Point[U] {
    override final def runSync[B <: Any](r: Sync[B], depth: Int)(implicit ec: ExecutionContext): Result[U] =
      arrow.runSync(r.success(v), depth)
  }

  final case class AndThen[T, U, V](a: Arrow[T, U], b: Arrow[U, V]) extends Transform[T, U, V] {
    override final def runCont(r: Sync[U], depth: Int)(implicit ec: ExecutionContext): Result[V] =
      b.runSync(r, depth)
  }

  final class FromFuture[T](fut: ExecutionContext => Future[T]) extends Point[T] {
    override final def runSync[B <: Any](r: Sync[B], depth: Int)(implicit ec: ExecutionContext): Result[T] =
      Async(r, fut(ec))
  }

  final case class Recursive[T, U](r: Arrow[T, U] => Arrow[T, U]) extends Transform[T, U, U] {
    override final def runCont(r: Sync[U], depth: Int)(implicit ec: ExecutionContext): Result[U] = r
    final val a =
      try r(this)
      catch {
        case ex: Throwable =>
          if (ScalaNonFatal(ex))
            Failed(ex)
          else
            throw ex
      }
  }

  trait Transform[T, U, V] extends Arrow[T, V] {
    def a: Arrow[T, U]

    final def runSync[B <: T](r: Sync[B], depth: Int)(implicit ec: ExecutionContext): Result[V] =
      if (depth > MaxDepth)
        new Defer(r, this)
      else
        a.runSync(r, depth + 1) match {
          case r: Sync[U] => runCont(r, depth)
          case r          => r.cont(this, depth)
        }

    def runCont(r: Sync[U], depth: Int)(implicit ec: ExecutionContext): Result[V]
  }

  final case class Successful[T](v: T) extends Point[T] {
    override final def runSync[B <: Any](r: Sync[B], depth: Int)(implicit ec: ExecutionContext): Result[T] =
      r.success(v)
  }
  final case class Failed[T](ex: Throwable) extends Point[T] {
    override final def runSync[B <: Any](r: Sync[B], depth: Int)(implicit ec: ExecutionContext): Result[T] =
      r.failure(ex)
  }

  final case class Map[T, U, V](a: Arrow[T, U], f: U => V) extends Transform[T, U, V] {
    override final def runCont(r: Sync[U], depth: Int)(implicit ec: ExecutionContext): Result[V] =
      if (r.success)
        try r.success(f(r.value))
        catch {
          case ex: Throwable =>
            if (ScalaNonFatal(ex)) r.failure(ex)
            else throw ex
        }
      else
        r.as[V]
  }
  final case class FlatMap[T, U, V](a: Arrow[T, U], f: U => Task[V]) extends Transform[T, U, V] {
    override final def runCont(r: Sync[U], depth: Int)(implicit ec: ExecutionContext): Result[V] =
      if (r.success)
        if (f.isInstanceOf[Arrow[_, _]])
          f.asInstanceOf[Arrow[U, V]].runSync(r, depth)
        else
          try f(r.value).runSync(r.unit, depth + 1)
          catch {
            case ex: Throwable =>
              if (ScalaNonFatal(ex)) r.failure(ex)
              else throw ex
          }
      else
        r.as[V]
  }

  trait OnComplete[T, U] extends Transform[T, U, U] {
    override final def runCont(r: Sync[U], depth: Int)(implicit ec: ExecutionContext): Result[U] = {
      try callback(r.toTry)
      catch {
        case ex: Throwable =>
          if (ScalaNonFatal(ex)) ec.reportFailure(ex)
          else throw ex
      }
      r
    }
    def callback(t: Try[U]): Unit
  }

  final case class Recover[T, U, V](a: Arrow[T, U], pf: PartialFunction[Throwable, V]) extends Transform[T, U, V] {
    override final def runCont(r: Sync[U], depth: Int)(implicit ec: ExecutionContext): Result[V] =
      if (!r.success) {
        try {
          val v = pf.applyOrElse(r.exception, Arrow.AlwaysNotApplied)
          if (v.isInstanceOf[Arrow.NotApplied.type]) r.as[V]
          else r.success(v).as[V]
        } catch {
          case ex: Throwable =>
            if (ScalaNonFatal(ex)) r.failure(ex)
            else throw ex
        }
      } else
        r.as[V]
  }

  final case class RecoverWith[T, U, V](a: Arrow[T, U], pf: PartialFunction[Throwable, V]) extends Transform[T, U, V] {
    override final def runCont(r: Sync[U], depth: Int)(implicit ec: ExecutionContext): Result[V] =
      if (!r.success) {
        try {
          val v = pf.applyOrElse(r.exception, Arrow.AlwaysNotApplied)
          if (v.isInstanceOf[Arrow.NotApplied.type]) r.as[V]
          else r.success(v).as[V]
        } catch {
          case ex: Throwable =>
            if (ScalaNonFatal(ex)) r.failure(ex)
            else throw ex
        }
      } else
        r.as[V]
  }

  trait TransformWith[T, U, V] extends Transform[T, U, V] {
    override final def runCont(r: Sync[U], depth: Int)(implicit ec: ExecutionContext): Result[V] =
      try task(r.toTry).runSync(r.unit, depth)
      catch {
        case ex: Throwable =>
          if (ScalaNonFatal(ex)) r.failure(ex)
          else throw ex
      }
    def task(f: Try[U]): Task[V]
  }

  trait TransformTry[T, U, V] extends Transform[T, U, V] {
    override final def runCont(r: Sync[U], depth: Int)(implicit ec: ExecutionContext): Result[V] =
      try {
        t(r.toTry) match {
          case v: Success[_] => r.success(v.value)
          case v: Failure[_] => r.failure(v.exception)
        }
      } catch {
        case ex: Throwable =>
          if (ScalaNonFatal(ex)) r.failure(ex)
          else throw ex
      }
    def t(f: Try[U]): Try[V]
  }

  trait Zip[T, U, V, X] extends Transform[T, U, X] {
    override final def runCont(r: Sync[U], depth: Int)(implicit ec: ExecutionContext): Result[X] =
      if (r.success) {
        val f1 = Future.successful(r.value)
        val f2 = p.runSync(r.unit, depth).toFuture
        Async(r, future(f1, f2))
      } else
        r.as[X]

    def p: Task[V]
    def future(u: Future[U], v: Future[V])(implicit ec: ExecutionContext): Future[X]
  }

  final case class Sequence[T, U, M[X] <: TraversableOnce[X]](in: M[Arrow[T, U]])(
    implicit
    cbf: CanBuildFrom[M[Arrow[T, U]], U, M[U]]
  ) extends Arrow[T, M[U]] {
    override final def runSync[B <: T](s: Sync[B], depth: Int)(implicit ec: ExecutionContext): Result[M[U]] =
      if (!s.success)
        s.as[M[U]]
      else {
        val v = s.value
        val arr = new Array[AnyRef](in.size)
        val it = in.toIterator
        var i = 0
        var success = true
        while (it.hasNext) {
          val a = it.next()
          val s = a.runSync(new Sync(true, v), depth).simplifyGraph
          s match {
            case s: Sync[_] =>
              if (!s.success)
                return s.as[M[U]]
              else
                success &&= s.success
            case s =>
              success = false
          }
          arr(i) = s
          i += 1
        }

        if (success) {
          var i = 0
          val builder = cbf()
          while (i < arr.length) {
            builder += arr(i).asInstanceOf[Sync[U]].value
            i += 1
          }
          new Sync(true, builder.result())
        } else {
          var i = 0
          val futures = arr.asInstanceOf[Array[Future[U]]]
          while (i < arr.length) {
            futures(i) = arr(i).asInstanceOf[Result[U]].toFuture
            i += 1
          }
          Async(null, Future.sequence(futures.toSeq).map(cbf().++=(_).result()))
        }
      }
  }

  class Fork[T, U](t: Arrow[T, U])(implicit fec: ExecutionContext) extends Arrow[T, U] {
    override final def runSync[B <: T](r: Sync[B], depth: Int)(implicit ec: ExecutionContext): Result[U] =
      Async(r, Future(t.runSync(r, 0)(fec).toFuture)(fec).flatten)(ec)
  }
}

