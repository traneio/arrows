package arrows.stdlib

import scala.util.control.{ NonFatal => ScalaNonFatal }

import ArrowRun._
import scala.concurrent.ExecutionContext
import scala.util.Try
import scala.concurrent.Future
import scala.util.Success
import scala.util.Failure

private[arrows] final object ArrowAst {

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

  final class FromFuture[T](fut: => Future[T]) extends Point[T] {
    override final def runSync[B <: Any](r: Sync[B], depth: Int)(implicit ec: ExecutionContext): Result[T] =
      Async(r, fut)
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

    def runSync[B <: T](r: Sync[B], depth: Int)(implicit ec: ExecutionContext): Result[V] =
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

}

