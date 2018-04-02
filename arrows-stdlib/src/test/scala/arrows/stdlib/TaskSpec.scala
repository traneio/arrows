package arrows.stdlib

import language.postfixOps
import scala.concurrent._
import scala.concurrent.duration._
import scala.util._
import scala.concurrent.ExecutionContext.Implicits.global
import java.util.concurrent.Executors

class TaskSpec extends Spec {

  object ex extends Exception

  def eval[T](t: Task[T]) = Await.result(t.run, Duration.Inf)
  def evalTry[T](t: Task[T]) = Try(eval(t))

  "fromFuture" - {
    "success" in {
      eval(Task.fromFuture(Future.successful(1))) mustEqual 1
    }
    "failure" in {
      evalTry(Task.fromFuture(Future.failed(ex))) mustEqual Failure(ex)
    }
  }

  "fromTry" - {
    "success" in {
      eval(Task.fromTry(Success(1))) mustEqual 1
    }
    "failure" in {
      evalTry(Task.fromTry(Failure(ex))) mustEqual Failure(ex)
    }
  }

  "value" in {
    eval(Task.successful(1)) mustEqual 1
  }

  "exception" in {
    evalTry(Task.failed(ex)) mustEqual Failure(ex)
  }

  "fork" in {
    val ex = Executors.newSingleThreadExecutor()
    try {
      implicit val ec = ExecutionContext.fromExecutor(ex)
      var thread: Thread = null
      eval(Task.fork(Task {
        thread = Thread.currentThread
      })(ec))
      thread != null && thread != Thread.currentThread() mustEqual true
    } finally {
      ex.shutdown()
    }
  }

  "never" in {
    Try(Await.result(Task.never.run, 5 millis)) match {
      case Failure(_: TimeoutException) =>
      case _                            => fail
    }
  }

  "apply" in {
    var c = false
    val t = Task(c = true)
    c mustEqual false
    eval(t)
    c mustEqual true
  }

  "sequence" - {
    "empty" in {
      eval(Task.sequence(List.empty[Task[Int]])).toList mustEqual Nil
    }
    "non-empty" in {
      val a1 = Task.successful(1)
      val a2 = Task.successful(2)
      val a = Task.sequence[Int, List](List(a1, a2))
      eval(a).toList mustEqual List(1, 2)
    }
  }
}