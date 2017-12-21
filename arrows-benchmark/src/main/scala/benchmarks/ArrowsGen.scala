package benchmarks

import arrows.Arrow
import arrows.Task
import com.twitter.util.Promise

object ArrowsTaskGen extends Gen[Int => Task[Int]] {

  def sync = Task.value _

  def async(schedule: Runnable => Unit) = {
    v =>
      val p = new Promise[Int] with Runnable {
        override def run() = setValue(v)
      }
      schedule(p)
      Task.fromFuture(p)
  }

  def failure(ex: Throwable) = v => Task.exception(ex)

  def map(t: Int => Task[Int], f: Int => Int) =
    t.andThen(_.map(f))

  def flatMap(t: Int => Task[Int], f: Int => Task[Int]) =
    t.andThen(_.flatMap(f))

  def handle(t: Int => Task[Int], i: Int) =
    t.andThen(_.handle { case _ => i })
}

object ArrowsArrowGen extends Gen[Arrow[Int, Int]] {

  def sync = Arrow[Int]

  def async(schedule: Runnable => Unit) =
    Arrow[Int].flatMap { v =>
      val p = new Promise[Int] with Runnable {
        override def run() = setValue(v)
      }
      schedule(p)
      Task.fromFuture(p)
    }

  def failure(ex: Throwable) = Arrow.exception(ex)

  def map(t: Arrow[Int, Int], f: Int => Int) =
    t.map(f)

  def flatMap(t: Arrow[Int, Int], f: Arrow[Int, Int]) =
    t.flatMap(f)

  def handle(t: Arrow[Int, Int], i: Int) =
    t.handle { case _ => i }
}
