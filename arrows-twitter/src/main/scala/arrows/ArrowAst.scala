package arrows

import scala.util.control.{ NonFatal => ScalaNonFatal }

import com.twitter.util.Future
import com.twitter.util.Try
import ArrowRun._
import com.twitter.util.Monitor

private[arrows] final object ArrowAst {

  type Point[T] = Arrow[Any, T]

  trait Transform[T, U, V] extends Arrow[T, V] {
    def a: Arrow[T, U]

    def runSync[B <: T](r: Sync[B], depth: Int): Result[V] =
      if (depth > MaxDepth)
        new Defer(r, this)
      else
        a.runSync(r, depth + 1) match {
          case r: Sync[U] => runCont(r, depth)
          case r          => r.cont(this, depth)
        }

    def runCont(r: Sync[U], depth: Int): Result[V]
  }

  final case object Identity extends Point[Nothing] {
    override final def runSync[B <: Any](r: Sync[B], depth: Int): Result[Nothing] =
      r.as[Nothing]
  }
  final case class Value[T](v: T) extends Point[T] {
    override final def runSync[B <: Any](r: Sync[B], depth: Int): Result[T] =
      r.success(v)
  }
  final case class Exception[T](ex: Throwable) extends Point[T] {
    override final def runSync[B <: Any](r: Sync[B], depth: Int): Result[T] =
      r.failure(ex)
  }
  final class FromFuture[T](fut: => Future[T]) extends Point[T] {
    override final def runSync[B <: Any](r: Sync[B], depth: Int): Result[T] =
      Async(r, fut)
  }
  final case class Apply[T, U](v: T, arrow: Arrow[T, U]) extends Point[U] {
    override final def runSync[B <: Any](r: Sync[B], depth: Int): Result[U] =
      arrow.runSync(r.success(v), depth)
  }

  final case class AndThen[T, U, V](a: Arrow[T, U], b: Arrow[U, V]) extends Transform[T, U, V] {
    override final def runCont(r: Sync[U], depth: Int): Result[V] =
      b.runSync(r, depth)
  }

  final case class Recursive[T, U](r: Arrow[T, U] => Arrow[T, U]) extends Transform[T, U, U] {
    override final def runCont(r: Sync[U], depth: Int): Result[U] = r
    final val a =
      try r(this)
      catch {
        case ex: Throwable =>
          if (ScalaNonFatal(ex))
            Exception(ex)
          else
            throw ex
      }
  }

  final case class Map[T, U, V](a: Arrow[T, U], f: U => V) extends Transform[T, U, V] {
    override final def runCont(r: Sync[U], depth: Int): Result[V] =
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
    override final def runCont(r: Sync[U], depth: Int): Result[V] =
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
  final case class Handle[T, U, V](a: Arrow[T, U], pf: PartialFunction[Throwable, V]) extends Transform[T, U, V] {
    override final def runCont(r: Sync[U], depth: Int): Result[V] =
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

  final case class Collect[T, U](seq: Seq[Arrow[T, U]]) extends Arrow[T, Seq[U]] {
    override final def runSync[B <: T](s: Sync[B], depth: Int): Result[Seq[U]] =
      if (!s.success)
        s.as[Seq[U]]
      else {
        val v = s.value
        val arr = new Array[AnyRef](seq.length)
        val it = seq.iterator
        var i = 0
        var success = true
        while (it.hasNext) {
          val a = it.next()
          val s = a.runSync(new Sync(true, v), depth).simplifyGraph
          s match {
            case s: Sync[_] =>
              if (!s.success)
                return s.as[Seq[U]]
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
          val values = arr.asInstanceOf[Array[U]]
          while (i < arr.length) {
            values(i) = arr(i).asInstanceOf[Sync[U]].value
            i += 1
          }
          new Sync(true, values.toSeq)
        } else {
          var i = 0
          while (i < arr.length) {
            arr(i) = arr(i).asInstanceOf[Result[U]].toFuture
            i += 1
          }
          Async(null, Future.collect(arr.toSeq.asInstanceOf[Seq[Future[U]]]))
        }
      }
  }

  trait Wrap[T, U] extends Arrow[T, U] {
    override final def runSync[B <: T](r: Sync[B], depth: Int): Result[U] =
      new Async(wrap(arrow.runSync(r, 0).toFuture))
    def arrow: Arrow[T, U]
    def wrap(f: => Future[U]): Future[U]
  }

  trait TransformFuture[T, U, V] extends Arrow[T, V] {
    def a: Arrow[T, U]
    def runSync[B <: T](r: Sync[B], depth: Int): Result[V] =
      if (depth > MaxDepth)
        new Defer(r, this)
      else {
        val u = a.runSync(r, depth + 1)
        try Async(u, future(u.toFuture)) // TODO Avoid allocating Future.Unit
        catch {
          case ex: Throwable =>
            if (ScalaNonFatal(ex)) r.failure(ex)
            else throw ex
        }
      }
    def future(f: Future[U]): Future[V]
  }

  trait TransformTry[T, U, V] extends Transform[T, U, V] {
    override final def runCont(r: Sync[U], depth: Int): Result[V] =
      try task(r.toTry).runSync(r.unit, depth)
      catch {
        case ex: Throwable =>
          if (ScalaNonFatal(ex)) r.failure(ex)
          else throw ex
      }
    def task(f: Try[U]): Task[V]
  }

  trait Respond[T, U] extends Transform[T, U, U] {
    override final def runCont(r: Sync[U], depth: Int): Result[U] = {
      try resp(r.toTry)
      catch {
        case ex: Throwable => Monitor.handle(ex)
      }
      r
    }
    def resp(t: Try[U]): Unit
  }

  trait JoinTask[T, U, V, X] extends Transform[T, U, X] {
    override final def runCont(r: Sync[U], depth: Int): Result[X] =
      if (r.success) {
        val f1 = ReturnFuture(r.value)
        val f2 = p.runSync(r.unit, depth).toFuture
        Async(r, future(f1, f2))
      } else
        r.as[X]

    def p: Task[V]
    def future(u: Future[U], v: Future[V]): Future[X]
  }
}

