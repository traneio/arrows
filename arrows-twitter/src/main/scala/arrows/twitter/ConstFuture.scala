package arrows.twitter

import com.twitter.util.Future
import com.twitter.util.Try
import com.twitter.util.Awaitable
import com.twitter.util.Duration
import com.twitter.util.Return
import scala.runtime.NonLocalReturnControl
import com.twitter.concurrent.Scheduler
import com.twitter.util.FutureNonLocalReturnControl
import com.twitter.util.Local
import com.twitter.util.Monitor
import scala.util.control.NonFatal
import com.twitter.util.Promise
import com.twitter.util.Throw

trait ConstFuture[T] extends Future[T] {
  def isReady(implicit permit: Awaitable.CanAwait): Boolean = true

  override def ready(timeout: Duration)(implicit permit: Awaitable.CanAwait) = this
  def poll: Option[com.twitter.util.Try[T]] = Some(toTry)

  protected def toTry: Try[T]

  def respond(k: Try[T] => Unit): Future[T] = {
    val saved = Local.save()
    Scheduler.submit(new Runnable {
      def run(): Unit = {
        val current = Local.save()
        Local.restore(saved)
        try k(toTry)
        catch Monitor.catcher
        finally Local.restore(current)
      }
    })
    this
  }

  def raise(interrupt: Throwable): Unit = ()

  def transform[B](f: Try[T] => Future[B]): Future[B] = {
    val p = new Promise[B]
    // see the note on `respond` for an explanation of why `Scheduler` is used.
    val saved = Local.save()
    Scheduler.submit(new Runnable {
      def run(): Unit = {
        val current = Local.save()
        Local.restore(saved)
        val computed = try f(toTry)
        catch {
          case e: NonLocalReturnControl[_] => Future.exception(new FutureNonLocalReturnControl(e))
          case NonFatal(e)                 => Future.exception(e)
          case t: Throwable =>
            Monitor.handle(t)
            throw t
        } finally Local.restore(current)
        p.become(computed)
      }
    })
    p
  }
}

class ReturnFuture[T](r: T) extends ConstFuture[T] {
  override def result(timeout: Duration)(implicit permit: Awaitable.CanAwait): T = r
  override final def toTry = Return(r)
}

class ThrowFuture[T](ex: Throwable) extends ConstFuture[T] {
  override def result(timeout: Duration)(implicit permit: Awaitable.CanAwait): T = throw ex
  override final def toTry = Throw(ex)
}