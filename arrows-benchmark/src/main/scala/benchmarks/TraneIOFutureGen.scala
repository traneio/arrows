package benchmarks

import java.util.function.Function

import io.trane.future.Future
import io.trane.future.Promise

object TraneIOFutureGen extends Gen[Function[Int, Future[Int]]] {

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
