package benchmarks

import monix.eval.Task
import monix.execution.Cancelable

object MonixTaskGen extends Gen[Int => Task[Int]] {

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
