package benchmarks

import scalaz.effect._
import org.openjdk.jmh.annotations._
import scala.util.Try
import scalaz.effect.RTS

trait Scalaz {
  this: Benchmarks =>

  private[this] final object rts extends RTS
  private[this] final val gen = ScalazGen(dist)

  @Benchmark
  def scalazIO = {
    Try(rts.unsafePerformIO(gen(1)))
  }

  @TearDown
  def shutdown() =
    rts.threadPool.shutdown()
}

object ScalazGen extends Gen[Int => IO[Throwable, Int]] {

  def sync = IO.now _

  def async(schedule: Runnable => Unit) =
    v => IO.async(cb => schedule(() => cb(ExitResult.Completed(v))))

  def failure(ex: Throwable) = _ => IO.fail(ex)

  def map(t: Int => IO[Throwable, Int], f: Int => Int) =
    t.andThen(_.map(f))

  def flatMap(t: Int => IO[Throwable, Int], f: Int => IO[Throwable, Int]) =
    t.andThen(_.flatMap(f))

  def handle(t: Int => IO[Throwable, Int], i: Int) =
    t.andThen(_.catchAll(_ => IO.now(i)))
}
