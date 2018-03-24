package arrows.twitter

import language.postfixOps
import com.twitter.util._
import com.twitter.util.TimeConversions._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ArrowSpec extends Spec {

  implicit val timer = new JavaTimer(true)
  object ex extends Exception

  def test[T](
    f: Arrow[Int, Int] => Arrow[Int, T],
    cases: List[(Try[Int], Try[T])],
    check: Try[Int] => Unit = _ => ()) = {
    cases.map {
      case (input, result) =>
        s"$input => $result" - {
          input match {
            case Return(v) =>
              "value" in {
                Try(Await.result(f(Arrow.value(v)).run)) mustEqual result
                check(input)
              }
              "identity" in {
                Try(Await.result(f(Arrow[Int]).run(v))) mustEqual result
                check(input)
              }
              "async" in {
                Try(Await.result(f(Arrow[Int].delayed(5 millis)).run(v))) mustEqual result
                check(input)
              }
              "defer" in {
                var a = f(Arrow[Int])
                for (_ <- 0 until ArrowRun.MaxDepth) a = a.map(identity)
                Try(Await.result(a.run(v))) mustEqual result
                check(input)
              }
              "double defer" in {
                var a = f(Arrow[Int])
                for (_ <- 0 until ArrowRun.MaxDepth * 2 + 1) a = a.map(identity)
                Try(Await.result(a.run(v))) mustEqual result
                check(input)
              }
            case Throw(ex) =>
              "exception" in {
                Try(Await.result(f(Arrow.exception(ex)).run)) mustEqual result
                check(input)
              }
              "async" in {
                Try(Await.result(f(Arrow.exception(ex).delayed(5 millis)).run)) mustEqual result
                check(input)
              }
          }
        }
    }
  }

  "instance" - {

    "apply" - {
      test(
        _.flatMap(Arrow[Int]),
        List(
          Return(1) -> Return(1),
          Throw(ex) -> Throw(ex)))
    }
    "andThen" - {
      "identity" - {
        test(
          _.andThen(Arrow[Int]),
          List(
            Return(1) -> Return(1),
            Throw(ex) -> Throw(ex)))
      }
      "with map" - {
        test(
          _.andThen(Arrow[Int].map(_ + 1)),
          List(
            Return(1) -> Return(2),
            Throw(ex) -> Throw(ex)))
      }
      "with handle" - {
        test(
          _.andThen(Arrow[Int].handle { case _ => 2 }),
          List(
            Return(1) -> Return(1),
            Throw(ex) -> Return(2)))
      }
    }

    "map" - {
      "success" - {
        test(
          _.map(_ + 1),
          List(
            Return(1) -> Return(2),
            Throw(ex) -> Throw(ex)))
      }
      "failure" - {
        test(
          _.map(_ => throw ex),
          List(
            Return(1) -> Throw(ex),
            Throw(ex) -> Throw(ex)))
      }
    }

    "flatMap" - {
      "success" - {
        test(
          _.flatMap(i => Task.value(i + 1)),
          List(
            Return(1) -> Return(2),
            Throw(ex) -> Throw(ex)))
      }
      "failure" - {
        test(
          _.flatMap(_ => throw ex),
          List(
            Return(1) -> Throw(ex),
            Throw(ex) -> Throw(ex)))
      }
    }

    "handle" - {
      "success" - {
        test(
          _.handle { case _ => 2 },
          List(
            Return(1) -> Return(1),
            Throw(ex) -> Return(2)))
      }
      "failure" - {
        test(
          _.handle { case _ => throw ex },
          List(
            Return(1) -> Return(1),
            Throw(ex) -> Throw(ex)))
      }
    }

    "unit" - {
      test(
        _.unit,
        List(
          Return(1) -> Return(()),
          Throw(ex) -> Throw(ex)))
    }

    "respond" - {
      "success" - {
        var r: Try[Int] = null
        test(
          _.respond(r = _),
          List(
            Return(1) -> Return(1),
            Throw(ex) -> Throw(ex)),
          r mustEqual _)
      }
      "failure" - {
        var r: Try[Int] = null
        test(
          _.respond { i =>
            r = i
            throw ex
          },
          List(
            Return(1) -> Return(1),
            Throw(ex) -> Throw(ex)),
          r mustEqual _)
      }
    }

    "ensure" - {
      "success" - {
        var c1 = 0
        var c2 = 0
        test(
          _.ensure(c1 += 1),
          List(
            Return(1) -> Return(1),
            Throw(ex) -> Throw(ex)),
          _ => {
            c2 += 1
            c1 mustEqual c2
          })
      }
      "failure" - {
        var c1 = 0
        var c2 = 0
        test(
          _.ensure {
            c1 += 1
            throw ex
          },
          List(
            Return(1) -> Return(1),
            Throw(ex) -> Throw(ex)),
          _ => {
            c2 += 1
            c1 mustEqual c2
          })
      }
    }

    "raiseWithin" - {
      "no timeout" - {
        test(
          _.raiseWithin(10 millis),
          List(
            Return(1) -> Return(1),
            Throw(ex) -> Throw(ex)))
      }

      "timeout" - {
        test(
          _.delayed(10 seconds).raiseWithin(10 millis, ex),
          List(
            Return(1) -> Throw(ex),
            Throw(ex) -> Throw(ex)))
      }
    }

    "within" - {
      "no timeout" - {
        "duration" - {
          test(
            _.within(10 millis),
            List(
              Return(1) -> Return(1),
              Throw(ex) -> Throw(ex)))
        }
        "timer + duration" - {
          test(
            _.within(timer, 10 millis),
            List(
              Return(1) -> Return(1),
              Throw(ex) -> Throw(ex)))
        }
        "timer + duration + exception" - {
          test(
            _.within(timer, 10 millis, ex),
            List(
              Return(1) -> Return(1),
              Throw(ex) -> Throw(ex)))
        }
      }

      "timeout" - {
        test(
          _.delayed(10 seconds).within(timer, 1 millis, ex),
          List(
            Return(1) -> Throw(ex),
            Throw(ex) -> Throw(ex)))
      }
    }

    "by" - {
      "no timeout" - {
        "duration" - {
          test(
            _.by(10.millis.fromNow),
            List(
              Return(1) -> Return(1),
              Throw(ex) -> Throw(ex)))
        }
        "timer + duration" - {
          test(
            _.by(timer, 10.millis.fromNow),
            List(
              Return(1) -> Return(1),
              Throw(ex) -> Throw(ex)))
        }
        "timer + duration + exception" - {
          test(
            _.by(timer, 10.millis.fromNow, ex),
            List(
              Return(1) -> Return(1),
              Throw(ex) -> Throw(ex)))
        }
      }

      "timeout" - {
        test(
          _.delayed(10 seconds).by(timer, 10.millis.fromNow, ex),
          List(
            Return(1) -> Throw(ex),
            Throw(ex) -> Throw(ex)))
      }
    }

    "delayed" - {
      var s: () => Long = null
      test(
        _.ensure(s = Stopwatch.systemMillis).delayed(50 millis),
        List(
          Return(1) -> Return(1),
          Throw(ex) -> Throw(ex)),
        _ => s() > 50)
    }

    "transform" - {
      "success" - {
        test(
          _.transform(Task.value),
          List(
            Return(1) -> Return(Return(1)),
            Throw(ex) -> Return(Throw(ex))))
      }
      "failure" - {
        test(
          _.transform(_ => throw ex),
          List(
            Return(1) -> Throw(ex),
            Throw(ex) -> Throw(ex)))
      }
    }

    "before" - {
      "success" - {
        var c1 = 0
        var c2 = 0
        test(
          _.unit.before(Task(c1 += 1)),
          List(
            Return(1) -> Return(())),
          _ => {
            c2 += 1
            c1 mustEqual c2
          })
      }
      "failure" - {
        var c1 = 0
        test(
          _.unit.before(Task(c1 += 1)),
          List(
            Throw(ex) -> Throw(ex)),
          _ => {
            c1 mustEqual 0
          })
      }
    }

    "rescue" - {
      object other extends Exception
      "success" - {
        test(
          _.rescue {
            case `ex`                    => Task.value(2)
            case _: NullPointerException => Task.value(3)
          },
          List(
            Return(1) -> Return(1),
            Throw(ex) -> Return(2),
            Throw(new NullPointerException) -> Return(3),
            Throw(other) -> Throw(other)))
      }
      "failure" - {
        test(
          _.rescue {
            case _ => throw ex
          },
          List(Throw(new NullPointerException) -> Throw(ex)))
      }
    }

    "foreach" - {
      "success" - {
        var c1 = 0
        var c2 = 0
        test(
          _.foreach(c1 += _),
          List(
            Return(1) -> Return(1)),
          _ => {
            c2 += 1
            c1 mustEqual c2
          })
      }

      "failure" - {
        var c = false
        test(
          _.foreach(_ => c = true),
          List(
            Throw(ex) -> Throw(ex)),
          _ => {
            c mustEqual false
          })
      }
    }

    "onSuccess" - {
      "success" - {
        var c1 = 0
        var c2 = 0
        test(
          _.onSuccess(c1 += _),
          List(
            Return(1) -> Return(1)),
          _ => {
            c2 += 1
            c1 mustEqual c2
          })
      }

      "failure" - {
        var c = false
        test(
          _.onSuccess(_ => c = true),
          List(
            Throw(ex) -> Throw(ex)),
          _ => {
            c mustEqual false
          })
      }
    }

    "filter" - {
      "success" - {
        test(
          _.filter(_ == 1),
          List(
            Return(1) -> Return(1),
            Return(2) -> Throw(new Try.PredicateDoesNotObtain),
            Throw(ex) -> Throw(ex)))
      }
      "failure" - {
        test(
          _.filter(_ => throw ex),
          List(
            Return(1) -> Throw(ex),
            Throw(ex) -> Throw(ex)))
      }
    }

    "withFilter" - {
      "success" - {
        test(
          _.withFilter(_ == 1),
          List(
            Return(1) -> Return(1),
            Return(2) -> Throw(new Try.PredicateDoesNotObtain),
            Throw(ex) -> Throw(ex)))
      }
      "failure" - {
        test(
          _.withFilter(_ => throw ex),
          List(
            Return(1) -> Throw(ex),
            Throw(ex) -> Throw(ex)))
      }
    }

    "onFailure" - {
      "failure" - {
        var c1 = 0
        var c2 = 0
        test(
          _.onFailure(_ => c1 += 1),
          List(
            Throw(ex) -> Throw(ex)),
          _ => {
            c2 += 1
            c1 mustEqual c2
          })
      }
      "success" - {
        var c = false
        test(
          _.onFailure(_ => c = true),
          List(
            Return(1) -> Return(1)),
          _ => {
            c mustEqual false
          })
      }
    }

    "select" - {
      test(
        _.select(Task.never),
        List(
          Return(1) -> Return(1),
          Throw(ex) -> Throw(ex)))
    }

    "or" - {
      test(
        _.or(Task.never),
        List(
          Return(1) -> Return(1),
          Throw(ex) -> Throw(ex)))
    }

    "join" - {
      test(
        _.join(Task.value(2)),
        List(
          Return(1) -> Return((1, 2)),
          Throw(ex) -> Throw(ex)))
    }

    "joinWith" - {
      "success" - {
        test(
          _.joinWith(Task.value(2))(_ + _),
          List(
            Return(1) -> Return(3),
            Throw(ex) -> Throw(ex)))
      }
//      "failure" - {
//        test(
//          _.joinWith(Task.value(2))((_, _) => throw ex),
//          List(
//            Return(1) -> Throw(ex),
//            Throw(ex) -> Throw(ex)))
//      }
    }

    "voided" - {
      test(
        _.voided,
        List(
          Return(1) -> Return(null),
          Throw(ex) -> Throw(ex)))
    }

    "flatten" - {
      test(
        _.map(Task.value).flatten,
        List(
          Return(1) -> Return(1),
          Throw(ex) -> Throw(ex)))
    }

    "mask" in {
      var c = false
      val p = Promise[Int]
      p.setInterruptHandler {
        case `ex` => c = true
      }
      val f = Task.fromFuture(p).mask {
        case `ex` => true
      }.run
      f.raise(ex)
      c mustEqual false
    }

    "masked" in {
      var c = false
      val p = Promise[Int]
      p.setInterruptHandler {
        case `ex` => c = true
      }
      val f = Task.fromFuture(p).masked.run
      f.raise(ex)
      c mustEqual false
    }

    "willEqual" - {
      test(
        _.willEqual(Task.value(1)),
        List(
          Return(1) -> Return(true),
          Return(2) -> Return(false),
          Throw(ex) -> Throw(ex)))
    }

    "liftToTry" - {
      test(
        _.liftToTry,
        List(
          Return(1) -> Return(Return(1)),
          Throw(ex) -> Return(Throw(ex))))
    }

    "lowerFromTry" - {
      test(
        _.liftToTry.lowerFromTry,
        List(
          Return(1) -> Return(1),
          Throw(ex) -> Throw(ex)))
    }

    "interruptible" in {
      val p = Promise[Int]
      val f = Task.fromFuture(p).interruptible().run
      f.raise(ex)
      Try(Await.result(f)) mustEqual Throw(ex)
    }
  }

  "companion" - {
    "toFuture" in {
      val f: Future[Int] = Arrow.value(1)
      Await.result(f) mustEqual 1
    }
    "fromFuture" in {
      val f: Arrow[Unit, Int] = Future.value(1)
      Await.result(f.run) mustEqual 1
    }
    "apply" in {
      Await.result(Arrow[Int].run(1)) mustEqual 1
    }
    "let" in {
      val l = new Local[Int]
      l.set(Some(1))
      val a = l.let(2)(Task.value(l()))
      l() mustEqual Some(1)
      Await.result(a.run) mustEqual Some(2)
    }
    "fork" in {
      var thread: Thread = null
      Arrow.fork(FuturePool.unboundedPool)(Task(thread = Thread.currentThread))
      thread != Thread.currentThread() mustEqual true
    }
    "recursive" in {
      val sum =
        Arrow.recursive[List[Int], Int] { self =>
          Arrow[List[Int]].flatMap {
            case Nil          => Task.value(0)
            case head :: tail => self(tail).map(_ + head)
          }
        }
      Await.result(sum.run(List(1, 2))) mustEqual 3
    }
    "value" in {
      Await.result(Arrow.value(1).run) mustEqual 1
    }
    "exception" in {
      Try(Await.result(Arrow.exception(ex).run)) mustEqual Throw(ex)
    }
    "collect" in {
      val a1 = Arrow[Int].map(_ + 1)
      val a2 = Arrow[Int].map(_ + 2)
      val a = Arrow.collect(List(a1, a2))
      Await.result(a.run(1)).toList mustEqual List(2, 3)
    }
  }
}