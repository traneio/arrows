package benchmarks

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Promise

object ScalaFutureGen extends Gen[Int => Future[Int]] {

  def sync = Future.successful _

  def async(schedule: Runnable => Unit) = {
    v =>
      val p = Promise[Int]()
      schedule(() => p.success(v))
      p.future
  }

  def failure(ex: Throwable) = _ => Future.failed(ex)

  def map(t: Int => Future[Int], f: Int => Int) =
    t.andThen(_.map(f))

  def flatMap(t: Int => Future[Int], f: Int => Future[Int]) =
    t.andThen(_.flatMap(f))

  def handle(t: Int => Future[Int], i: Int) =
    t.andThen(_.recover { case _ => i })
}
