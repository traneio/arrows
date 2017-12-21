package benchmarks

import cats.effect.IO

object CatsEffectIOGen extends Gen[Int => IO[Int]] {

  def sync = IO.pure _

  def async(schedule: Runnable => Unit) =
    v => IO.async(cb => schedule(() => cb(Right(v))))

  def failure(ex: Throwable) = _ => IO.raiseError(ex)

  def map(t: Int => IO[Int], f: Int => Int) =
    t.andThen(_.map(f))

  def flatMap(t: Int => IO[Int], f: Int => IO[Int]) =
    t.andThen(_.flatMap(f))

  def handle(t: Int => IO[Int], i: Int) =
    t.andThen(_.attempt.map(_.getOrElse(i)))
}
