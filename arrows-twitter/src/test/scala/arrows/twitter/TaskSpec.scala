package arrows.twitter

import com.twitter.util.Duration
import com.twitter.util.Await
import com.twitter.util.Try
import com.twitter.util.Throw
import com.twitter.util.Future
import com.twitter.util.Return
import com.twitter.util.FuturePool
import com.twitter.util.TimeConversions._
import com.twitter.util.TimeoutException
import com.twitter.util.Stopwatch
import com.twitter.util.JavaTimer
import language.postfixOps

class TaskSpec extends Spec {

  object ex extends Exception
  implicit val timer = new JavaTimer(true)

  def eval[T](t: Task[T]) = Await.result(t.run)
  def evalTry[T](t: Task[T]) = Try(eval(t))

  "default timeout" in {
    Task.DEFAULT_TIMEOUT mustEqual Duration.Top
  }

  "Unit" in {
    eval(Task.Unit) mustEqual (())
  }

  "Done" in {
    eval(Task.Done) mustEqual (())
  }

  "Void" in {
    eval(Task.Void) mustEqual null
  }

  "None" in {
    eval(Task.None) mustEqual None
  }

  "Nil" in {
    eval(Task.Nil) mustEqual Nil
  }

  "True" in {
    eval(Task.True) mustEqual true
  }

  "False" in {
    eval(Task.False) mustEqual false
  }

  "???" in {
    evalTry(Task.???) match {
      case Throw(ex: NotImplementedError) =>
      case _                              => fail
    }
  }

  "fromFuture" - {
    "success" in {
      eval(Task.fromFuture(Future.value(1))) mustEqual 1
    }
    "failure" in {
      evalTry(Task.fromFuture(Future.exception(ex))) mustEqual Throw(ex)
    }
  }

  "const" - {
    "success" in {
      eval(Task.const(Return(1))) mustEqual 1
    }
    "failure" in {
      evalTry(Task.const(Throw(ex))) mustEqual Throw(ex)
    }
  }

  "value" in {
    eval(Task.value(1)) mustEqual 1
  }

  "exception" in {
    evalTry(Task.exception(ex)) mustEqual Throw(ex)
  }

  "fork" in {
    var thread: Thread = null
    eval(Task.fork(FuturePool.unboundedPool)(Task(thread = Thread.currentThread)))
    thread != null && thread != Thread.currentThread() mustEqual true
  }

  "never" in {
    Try(Await.result(Task.never.run, 5 millis)) match {
      case Throw(_: TimeoutException) =>
      case _                          => fail
    }
  }

  "sleep" in {
    val s = Stopwatch.systemMillis
    eval(Task.sleep(50 millis))
    s() >= 50 mustEqual true
  }

  "apply" in {
    var c = false
    val t = Task(c = true)
    c mustEqual false
    eval(t)
    c mustEqual true
  }

  "monitored" in {
    val inner = Task.value(123)

    val t = Task.monitored {
      inner ensure { throw ex }
    }

    evalTry(t) mustEqual Throw(ex)
  }

  "collect" - {
    "empty" in {
      Await.result(Task.collect(Nil).run).toList mustEqual Nil
    }
    "non-empty" in {
      val a1 = Task.value(1)
      val a2 = Task.value(2)
      val a = Task.collect(List(a1, a2))
      Await.result(a.run).toList mustEqual List(1, 2)
    }
  }

  "join" in {
    var c1 = false
    var c2 = false
    val a1 = Task {
      c1 = true
      1
    }
    val a2 = Task {
      c2 = true
      2
    }
    val a = Arrow.collect(List(a1, a2))
    Await.result(a.run).toList mustEqual List(1, 2)
    c1 mustEqual true
    c2 mustEqual true
  }

  "join tuple" in {
    def t(i: Int) = Task.value(i)
    eval(Task.join(t(1), t(2))) mustEqual
      ((1, 2))
    eval(Task.join(t(1), t(2), t(3))) mustEqual
      ((1, 2, 3))
    eval(Task.join(t(1), t(2), t(3), t(4))) mustEqual
      ((1, 2, 3, 4))
    eval(Task.join(t(1), t(2), t(3), t(4), t(5))) mustEqual
      ((1, 2, 3, 4, 5))
    eval(Task.join(t(1), t(2), t(3), t(4), t(5), t(6))) mustEqual
      ((1, 2, 3, 4, 5, 6))
    eval(Task.join(t(1), t(2), t(3), t(4), t(5), t(6), t(7))) mustEqual
      ((1, 2, 3, 4, 5, 6, 7))
    eval(Task.join(t(1), t(2), t(3), t(4), t(5), t(6), t(7), t(8))) mustEqual
      ((1, 2, 3, 4, 5, 6, 7, 8))
    eval(Task.join(t(1), t(2), t(3), t(4), t(5), t(6), t(7), t(8), t(9))) mustEqual
      ((1, 2, 3, 4, 5, 6, 7, 8, 9))
    eval(Task.join(t(1), t(2), t(3), t(4), t(5), t(6), t(7), t(8), t(9), t(10), t(11))) mustEqual
      ((1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11))
    eval(Task.join(t(1), t(2), t(3), t(4), t(5), t(6), t(7), t(8), t(9), t(10), t(11), t(12))) mustEqual
      ((1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12))
    eval(Task.join(t(1), t(2), t(3), t(4), t(5), t(6), t(7), t(8), t(9), t(10), t(11), t(12), t(13))) mustEqual
      ((1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13))
    eval(Task.join(t(1), t(2), t(3), t(4), t(5), t(6), t(7), t(8), t(9), t(10), t(11), t(12), t(13), t(14))) mustEqual
      ((1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14))
    eval(Task.join(t(1), t(2), t(3), t(4), t(5), t(6), t(7), t(8), t(9), t(10), t(11), t(12), t(13), t(14), t(15))) mustEqual
      ((1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15))
    eval(Task.join(t(1), t(2), t(3), t(4), t(5), t(6), t(7), t(8), t(9), t(10), t(11), t(12), t(13), t(14), t(15), t(16))) mustEqual
      ((1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16))
    eval(Task.join(t(1), t(2), t(3), t(4), t(5), t(6), t(7), t(8), t(9), t(10), t(11), t(12), t(13), t(14), t(15), t(16), t(17))) mustEqual
      ((1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17))
    eval(Task.join(t(1), t(2), t(3), t(4), t(5), t(6), t(7), t(8), t(9), t(10), t(11), t(12), t(13), t(14), t(15), t(16), t(17), t(18))) mustEqual
      ((1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18))
    eval(Task.join(t(1), t(2), t(3), t(4), t(5), t(6), t(7), t(8), t(9), t(10), t(11), t(12), t(13), t(14), t(15), t(16), t(17), t(18), t(19))) mustEqual
      ((1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19))
    eval(Task.join(t(1), t(2), t(3), t(4), t(5), t(6), t(7), t(8), t(9), t(10), t(11), t(12), t(13), t(14), t(15), t(16), t(17), t(18), t(19), t(20))) mustEqual
      ((1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20))
    eval(Task.join(t(1), t(2), t(3), t(4), t(5), t(6), t(7), t(8), t(9), t(10), t(11), t(12), t(13), t(14), t(15), t(16), t(17), t(18), t(19), t(20), t(21))) mustEqual
      ((1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21))
    eval(Task.join(t(1), t(2), t(3), t(4), t(5), t(6), t(7), t(8), t(9), t(10), t(11), t(12), t(13), t(14), t(15), t(16), t(17), t(18), t(19), t(20), t(21), t(22))) mustEqual
      ((1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22))
  }

  "traverseSequentially" - {
    "empty" in {
      eval(Task.traverseSequentially(Nil)(_ => ???)).toList mustEqual Nil
    }
    "non-empty" in {
      eval(Task.traverseSequentially(List(1, 2))(Task.value)).toList mustEqual List(1, 2)
    }
  }

  "collectToTry" - {
    "success" in {
      eval(Task.collectToTry(List(Task.value(1), Task.value(2)))).toList mustEqual
        List(Return(1), Return(2))
    }
    "failure" in {
      eval(Task.collectToTry(List(Task.value(1), Task.exception(ex)))).toList mustEqual
        List(Return(1), Throw(ex))
    }
  }
}