package benchmarks

import monix.eval.Task
import monix.execution.Cancelable
import org.openjdk.jmh.annotations.Benchmark
import scala.util.Try
import scala.util.Success

trait MonixAsync {
  this: Benchmarks =>

  private[this] final val gen = MonixGen(dist)

  @Benchmark
  def monixTask = {
    import scala.concurrent._
    import scala.concurrent.duration._
    import monix.execution.Scheduler.Implicits.global
    Try(Await.result(gen(1).runAsync, Duration.Inf))
  }

}

trait MonixSync {
  this: Benchmarks =>

  private[this] final val gen = MonixGen(dist)

  @Benchmark
  def monixTask = {
    import monix.execution.Scheduler.Implicits.global
    Success(gen(1).runSyncMaybe.right.get)
  }
}

object MonixGen extends Gen[Int => Task[Int]] {

  def sync = Task.now _

  def async(schedule: Runnable => Unit) = {
    v =>
      Task.async[Int] {
        case (s, cb) =>
          schedule(() => cb.onSuccess(v))
          Cancelable.empty
      }
  }

  def failure(ex: Throwable) = _ => Task.raiseError(ex)

  def map(t: Int => Task[Int], f: Int => Int) =
    t.andThen(_.map(f))

  def flatMap(t: Int => Task[Int], f: Int => Task[Int]) =
    t.andThen(_.flatMap(f))

  def handle(t: Int => Task[Int], i: Int) =
    t.andThen(_.onErrorHandle(_ => i))
}
