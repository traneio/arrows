package benchmarks.gen

import com.twitter.util.Promise
import com.twitter.util.Future
import org.openjdk.jmh.annotations.Benchmark
import scala.util.Try

trait TwitterFuture {
  this: Benchmarks =>

  private[this] final val gen = TwitterFutureGen(dist)

  @Benchmark
  def twitterFuture = {
    import com.twitter.util.Await
    Try(Await.result(gen(1)))
  }
}

object TwitterFutureGen extends Gen[Int => Future[Int]] {

  def sync = Future.value _

  def async(schedule: Runnable => Unit) = {
    v =>
      val p = Promise.apply[Int]()
      schedule(() => p.setValue(v))
      p
  }

  def failure(ex: Throwable) = _ => Future.exception(ex)

  def map(t: Int => Future[Int], f: Int => Int) =
    t.andThen(_.map(f(_)))

  def flatMap(t: Int => Future[Int], f: Int => Future[Int]) =
    t.andThen(_.flatMap(f))

  def handle(t: Int => Future[Int], i: Int) =
    t.andThen(_.handle { case _ => i })
}
