package benchmarks

import java.util.function.Function

import io.trane.future.Future
import io.trane.future.Promise
import org.openjdk.jmh.annotations.Benchmark
import scala.util.Try

trait TraneIO {
  this: Benchmarks =>

  private[this] final val gen = TraneIOGen(dist)
  private[this] final val inf = java.time.Duration.ofMillis(Int.MaxValue)

  @Benchmark
  def traneIOFuture = {
    Try(gen(1).get(inf))
  }

}

object TraneIOGen extends Gen[Function[Int, Future[Int]]] {

  def sync = Future.value _

  def async(schedule: Runnable => Unit) = {
    v =>
      val p = Promise.apply[Int]()
      schedule(() => p.setValue(v))
      p
  }

  def failure(ex: Throwable) = _ => Future.exception(ex)

  def map(t: Function[Int, Future[Int]], f: Int => Int) =
    t.andThen(_.map(f(_)))

  def flatMap(t: Function[Int, Future[Int]], f: Function[Int, Future[Int]]) =
    t.andThen(_.flatMap(f))

  def handle(t: Function[Int, Future[Int]], i: Int) =
    t.andThen(_.rescue(_ => Future.value(i)))
}
