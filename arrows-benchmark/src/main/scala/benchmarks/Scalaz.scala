package benchmarks

import scalaz._
import scalaz.effect.IO
import org.openjdk.jmh.annotations.Benchmark
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

  override def tearDown() =
    rts.threadPool.shutdown()
}

object ScalazGen extends Gen[Int => IO[Int]] {

  def sync = IO.now _

  def async(schedule: Runnable => Unit) =
    v => IO.async(cb => schedule(() => cb(\/-(v))))

  def failure(ex: Throwable) = _ => IO.fail(ex)

  def map(t: Int => IO[Int], f: Int => Int) =
    t.andThen(_.map(f))

  def flatMap(t: Int => IO[Int], f: Int => IO[Int]) =
    t.andThen(_.flatMap(f))

  def handle(t: Int => IO[Int], i: Int) =
    t.andThen(_.catchAll(_ => IO.now(i)))
}
