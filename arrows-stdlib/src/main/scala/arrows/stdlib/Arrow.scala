package arrows.stdlib

import ArrowRun._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try
import scala.reflect.ClassTag
import scala.util.Success
import scala.util.Failure

abstract class Arrow[-T, +U] extends (T => Task[U]) {

  import ArrowAst._

  private[arrows] def runSync[B <: T](s: Sync[B], depth: Int)(implicit ec: ExecutionContext): Result[U]
  private[arrows] final def cast[A, B] = this.asInstanceOf[Arrow[A, B]]

  final def apply(v: T): Task[U] =
    Apply(v, this)

  final def andThen[V](b: Arrow[U, V]): Arrow[T, V] =
    if (this eq Identity)
      b.cast
    else
      AndThen(this, b)

  final def run(value: T)(implicit ec: ExecutionContext): Future[U] =
    ArrowRun(value, this)

  final def run[V <: T](implicit ec: ExecutionContext, ev: V => Unit): Future[U] =
    ArrowRun((), this.cast)

  @deprecated("use `foreach` or `onComplete` instead (keep in mind that they take total rather than partial functions)", "2.12.0")
  def onSuccess[V](pf: PartialFunction[U, V]): Arrow[T, U] =
    this match {
      case a: Failed[_] => a.cast
      case _ =>
        new OnComplete[T, U] {
          override final def a = Arrow.this
          override final def callback(t: Try[U]): Unit =
            t match {
              case t: Success[_] =>
                pf.applyOrElse[U, Any](t.value, Predef.identity[U]) // Exploiting the cached function to avoid MatchError
              case _ =>
            }
        }
    }

  @deprecated("use `onComplete` or `failed.foreach` instead (keep in mind that they take total rather than partial functions)", "2.12.0")
  def onFailure[V](@deprecatedName('callback) pf: PartialFunction[Throwable, V]): Arrow[T, U] =
    this match {
      case a: Successful[_] => a.cast
      case _ =>
        new OnComplete[T, U] {
          override final def a = Arrow.this
          override final def callback(t: Try[U]): Unit =
            t match {
              case t: Failure[_] =>
                pf.applyOrElse[Throwable, Any](t.exception, Predef.identity[Throwable]) // Exploiting the cached function to avoid MatchError
              case _ =>
            }
        }
    }

  def onComplete[V](@deprecatedName('func) f: Try[U] => V): Arrow[T, U] =
    new OnComplete[T, U] {
      override final def a = Arrow.this
      override final def callback(t: Try[U]): Unit = f(t)
    }

  def failed: Arrow[T, Throwable] =
    transform {
      case Failure(t) => Success(t)
      case Success(v) => Failure(new NoSuchElementException("Arrow.failed not completed with a throwable."))
    }

  def foreach[V](f: U => V): Arrow[T, U] =
    this match {
      case a: Failed[_] => a.cast
      case _ =>
        new OnComplete[T, U] {
          override final def a = Arrow.this
          override final def callback(t: Try[U]): Unit =
            t match {
              case t: Success[_] => f(t.value)
              case _             =>
            }
        }
    }

  def transform[V](s: U => V, f: Throwable => Throwable): Arrow[T, V] =
    transform {
      case Success(r) => Try(s(r))
      case Failure(t) => Try(throw f(t))
    }

  def transform[V](f: Try[U] => Try[V]): Arrow[T, V] =
    new TransformTry[T, U, V] {
      override final def a = Arrow.this
      override final def t(t: Try[U]) = f(t)
    }

  def transformWith[V](f: Try[U] => Task[V]): Arrow[T, V] =
    new TransformWith[T, U, V] {
      override final def a = Arrow.this
      override final def task(t: Try[U]) = f(t)
    }

  def map[V](f: U => V): Arrow[T, V] =
    this match {
      case a: Failed[_] => a.cast
      case a            => Map(a, f)
    }

  def flatMap[V](f: U => Task[V]): Arrow[T, V] =
    this match {
      case a: Failed[_] => a.cast
      case a            => FlatMap(a, f)
    }

  def flatten[V](implicit ev: U <:< Task[V]): Arrow[T, V] =
    flatMap[V](ev)

  def filter(@deprecatedName('pred) p: U => Boolean): Arrow[T, U] =
    map(r => if (p(r)) r else throw new NoSuchElementException("Arrow.filter predicate is not satisfied"))

  final def withFilter(p: U => Boolean): Arrow[T, U] =
    filter(p)

  def collect[V](pf: PartialFunction[U, V]): Arrow[T, V] =
    map {
      r => pf.applyOrElse(r, (t: U) => throw new NoSuchElementException("Arrow.collect partial function is not defined at: " + t))
    }

  def recover[V >: U](pf: PartialFunction[Throwable, V]): Arrow[T, V] =
    this match {
      case a: Successful[_] => a.cast
      case a                => Recover(a, pf)
    }

  def recoverWith[V >: U](pf: PartialFunction[Throwable, Task[V]]): Arrow[T, V] =
    this match {
      case a: Successful[_] => a.cast
      case a =>
        new TransformWith[T, U, V] {
          override final def a = Arrow.this
          override final def task(t: Try[U]) =
            t match {
              case t: Failure[_] =>
                val ex = t.exception
                val result = pf.applyOrElse(ex, Arrow.AlwaysNotApplied)
                if (result eq Arrow.NotApplied) Task.failed(ex) else result.asInstanceOf[Task[V]]
              case t: Success[_] =>
                Task.successful(t.value)
            }
        }
    }

  def zip[V](that: Task[V]): Arrow[T, (U, V)] =
    this match {
      case a: Failed[_] => a.cast
      case _ =>
        new Zip[T, U, V, (U, V)] {
          override final def a = Arrow.this
          override final def p = that
          override final def future(u: Future[U], v: Future[V])(implicit ec: ExecutionContext) =
            u.zip(v)
        }
    }

  def zipWith[V, X](that: Task[V])(f: (U, V) => X): Arrow[T, X] =
    this match {
      case a: Failed[_] => a.cast
      case _ =>
        new Zip[T, U, V, X] {
          override final def a = Arrow.this
          override final def p = that
          override final def future(u: Future[U], v: Future[V])(implicit ec: ExecutionContext) =
            u.zipWith(v)(f)
        }
    }

  // TODO optimize
  def fallbackTo[V >: U](that: Task[V]): Arrow[T, V] =
    if (this eq that) this
    else {
      transformWith {
        case r: Success[_] => Task.successful(r.value)
        case r: Failure[_] => that.recoverWith { case _ => Task.failed(r.exception) }
      }
    }

  def mapTo[V](implicit tag: ClassTag[V]): Arrow[T, V] =
    this match {
      case _: Failed[_] => this.cast
      case _ =>
        val boxedClass = {
          val c = tag.runtimeClass
          if (c.isPrimitive) Arrow.toBoxed(c) else c
        }
        require(boxedClass ne null)
        map(s => boxedClass.cast(s).asInstanceOf[V])
    }

  def andThen[V](pf: PartialFunction[Try[U], V]): Arrow[T, U] =
    new OnComplete[T, U] {
      override final def a = Arrow.this
      override final def callback(t: Try[U]): Unit =
        pf.applyOrElse[Try[U], Any](t, Predef.identity[Try[U]])
    }
}

final object Arrow {

  import ArrowAst._

  final def apply[T]: Arrow[T, T] = Identity

  final def failed[T](exception: Throwable): Arrow[Any, T] = Failed(exception)

  private[stdlib] val toBoxed = scala.collection.Map[Class[_], Class[_]](
    classOf[Boolean] -> classOf[java.lang.Boolean],
    classOf[Byte] -> classOf[java.lang.Byte],
    classOf[Char] -> classOf[java.lang.Character],
    classOf[Short] -> classOf[java.lang.Short],
    classOf[Int] -> classOf[java.lang.Integer],
    classOf[Long] -> classOf[java.lang.Long],
    classOf[Float] -> classOf[java.lang.Float],
    classOf[Double] -> classOf[java.lang.Double],
    classOf[Unit] -> classOf[scala.runtime.BoxedUnit]
  )

  final object NotApplied
  private[arrows] final val AlwaysNotApplied: Any => AnyRef = _ => NotApplied
}
