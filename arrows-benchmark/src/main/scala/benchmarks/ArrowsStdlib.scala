package benchmarks

import scala.concurrent.ExecutionContext.Implicits.global
import arrows.stdlib.Arrow
import arrows.stdlib.Task
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Benchmark
import scala.util.Try
import scala.concurrent.Promise
import scala.concurrent.Await
import scala.concurrent.duration.Duration

trait ArrowsStdlib {
  this: Benchmarks =>

  private[this] final val arrowGen = ArrowsStdlibArrowGen(dist)
  private[this] final val taskGen = ArrowsStdlibTaskGen(dist)

  @Benchmark
  def arrowsStdlibArrow = {
    Try(Await.result(arrowGen.run(1), Duration.Inf))
  }

  @Benchmark
  def arrowsStdlibTask = {
    Try(Await.result(taskGen(1).run, Duration.Inf))
  }
}

object ArrowsStdlibTaskGen extends Gen[Int => Task[Int]] {

  def sync = Task.successful _

  def async(schedule: Runnable => Unit) = {
    v =>
      val p = Promise[Int]()
      schedule(() => p.success(v))
      Task.async(p.future)
  }

  def failure(ex: Throwable) = v => Task.failed(ex)

  def map(t: Int => Task[Int], f: Int => Int) =
    t.andThen(_.map(f))

  def flatMap(t: Int => Task[Int], f: Int => Task[Int]) =
    t.andThen(_.flatMap(f))

  def handle(t: Int => Task[Int], i: Int) =
    t.andThen(_.recover { case _ => i })
}

object ArrowsStdlibArrowGen extends Gen[Arrow[Int, Int]] {

  def sync = Arrow[Int]

  def async(schedule: Runnable => Unit) =
    Arrow[Int].flatMap { v =>
      val p = Promise[Int]()
      schedule(() => p.success(v))
      Task.async(p.future)
    }

  def failure(ex: Throwable) = Arrow.failed(ex)

  def map(t: Arrow[Int, Int], f: Int => Int) =
    t.map(f)

  def flatMap(t: Arrow[Int, Int], f: Arrow[Int, Int]) =
    t.flatMap(f)

  def handle(t: Arrow[Int, Int], i: Int) =
    t.recover { case _ => i }
}
