package arrows.twitter

import java.util.Arrays

import com.twitter.util.Future
import com.twitter.util.Return
import com.twitter.util.Throw
import com.twitter.util.Try

private[arrows] final object ArrowRun {

  import ArrowImpl._

  final val MaxDepth = 512

  sealed abstract class Result[+T] {
    def simplify: Result[T]
    def cont[B >: T, U](a: Transform[_, B, U], depth: Int): Result[U]

    final def as[U] = this.asInstanceOf[Result[U]]

    final def simplifyGraph: Result[T] = {
      var c = this.simplify
      while (c.isInstanceOf[Defer[_, _]])
        c = c.simplify
      c
    }

    def toFuture: Future[T]
  }

  final class Sync[+T](
    private[this] var _success: Boolean,
    private[this] var curr:     Any
  )
    extends Result[T] {

    def success = _success

    final def unit: Sync[Unit] =
      success(())

    final def success[U](v: U): Sync[U] = {
      _success = true
      curr = v
      this.asInstanceOf[Sync[U]]
    }

    final def failure[U](ex: Throwable): Sync[U] = {
      _success = false
      curr = ex
      this.asInstanceOf[Sync[U]]
    }

    final def value = curr.asInstanceOf[T]

    final def exception = curr.asInstanceOf[Throwable]

    override final def toFuture =
      if (_success) new ReturnFuture(value)
      else new ThrowFuture(exception)

    final def toTry: Try[T] =
      if (success)
        Return(value)
      else
        Throw(exception)

    override final def simplify = this

    override final def cont[B >: T, U](a: Transform[_, B, U], depth: Int) =
      a.runCont(this, depth)
  }

  final class Async[T](
    private[this] var fut: Future[T]
  )
    extends Result[T] with (Try[T] => Future[T]) {

    private[this] var stack = new Array[Transform[Any, Any, Any]](10)
    private[this] var pos = 0

    override final def toFuture = {
      val r = simplify
      if (r eq this)
        fut
      else
        r.toFuture
    }

    private[this] final def runCont(t: Try[T]) = {
      var res: Result[_] =
        t match {
          case t: Throw[_]  => new Sync(false, t.throwable)
          case t: Return[_] => new Sync(true, t.r)
        }
      var i = 0
      while (i < pos) {
        res = res.cont(stack(i), 0)
        i += 1
      }
      res.as[T]
    }

    override final def apply(t: Try[T]) =
      runCont(t).toFuture

    override final def simplify =
      fut.poll match {
        case r: Some[_] =>
          runCont(r.get)
        case _ =>
          fut = fut.transform(this)
          this
      }

    override final def cont[B >: T, U](a: Transform[_, B, U], depth: Int) = {
      if (pos == stack.length)
        stack = Arrays.copyOf(stack, pos + pos / 2)
      stack(pos) = a.asInstanceOf[Transform[Any, Any, Any]]
      pos += 1
      this.as[U]
    }
  }

  final object Async {
    final def apply[T](owner: Result[_], fut: Future[T]): Result[T] =
      fut.poll match {
        case r: Some[_] =>
          owner match {
            case owner: Sync[_] =>
              r.get match {
                case r: Throw[_]  => owner.failure(r.e)
                case r: Return[_] => owner.success(r.r)
              }
            case _ =>
              r.get match {
                case r: Throw[_]  => new Sync(false, r.e)
                case r: Return[_] => new Sync(true, r.r)
              }
          }
        case _ =>
          new Async(fut)
      }
  }

  final class Defer[T, U](r: Sync[T], a: Arrow[T, U]) extends Result[U] {
    private var stacks = Array(new Array[Transform[Any, Any, Any]](MaxDepth + 1))
    private[this] var pos = 0

    override def toFuture =
      simplifyGraph.toFuture

    override final def simplify =
      a.runSync(r, 0) match {
        case d: Defer[_, _] =>
          val l = d.stacks.length
          d.stacks = Arrays.copyOf(d.stacks, l + stacks.length)
          System.arraycopy(stacks, 0, d.stacks, l, stacks.length)
          d
        case other =>
          var res: Result[Any] = other
          var i = 0
          while (i < stacks.length) {
            val a = stacks(i)
            var j = 0
            while (j < a.length) {
              val c = a(j)
              if (c != null)
                res = res.cont(c, 0)
              j += 1
            }
            i += 1
          }
          res.asInstanceOf[Result[U]]
      }

    override final def cont[B >: U, V](a: Transform[_, B, V], depth: Int) = {
      stacks(stacks.length - 1)(pos) = a.asInstanceOf[Transform[Any, Any, Any]]
      pos += 1
      this.as[V]
    }
  }

  final def apply[T, U](value: T, a: Arrow[T, U]): Future[U] =
    a.runSync(new Sync(true, value), 0).toFuture
}
