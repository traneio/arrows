package benchmarks.gen

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Promise
import org.openjdk.jmh.annotations.Benchmark
import scala.util.Try

trait ScalaFuture {
  this: Benchmarks =>

  private[this] final val sFut = ScalaFutureGen(dist)

  @Benchmark
  def scalaFuture = {
    import scala.concurrent._
    import scala.concurrent.duration._
    Try(Await.result(sFut(1), Duration.Inf))
  }
}

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
