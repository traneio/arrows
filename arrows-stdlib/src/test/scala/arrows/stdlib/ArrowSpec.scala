package arrows.stdlib

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import scala.util._
import scala.concurrent._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import java.util.concurrent.Executors

@RunWith(classOf[JUnitRunner])
class ArrowSpec extends Spec {

  object ex extends Exception

  def test[T](
    f:     Arrow[Int, Int] => Arrow[Int, T],
    cases: List[(Try[Int], Try[T])],
    check: Try[Int] => Unit = _ => ()
  ) = {
    cases.map {
      case (input, result) =>
        s"$input => $result" - {
          input match {
            case Success(v) =>
              "value" in {
                Try(Await.result(f(Arrow.successful(v)).run, Duration.Inf)) mustEqual result
                check(input)
              }
              "identity" in {
                Try(Await.result(f(Arrow[Int]).run(v), Duration.Inf)) mustEqual result
                check(input)
              }
              //              "async" in {
              //                Try(Await.result(f(Arrow[Int].delayed(5 millis)).run(v))) mustEqual result
              //                check(input)
              //              }
              "defer" in {
                var a = f(Arrow[Int])
                for (_ <- 0 until ArrowRun.MaxDepth) a = a.map(identity)
                Try(Await.result(a.run(v), Duration.Inf)) mustEqual result
                check(input)
              }
              "double defer" in {
                var a = f(Arrow[Int])
                for (_ <- 0 until ArrowRun.MaxDepth * 2 + 1) a = a.map(identity)
                Try(Await.result(a.run(v), Duration.Inf)) mustEqual result
                check(input)
              }
            case Failure(ex) =>
              "exception" in {
                Try(Await.result(f(Arrow.failed(ex)).run, Duration.Inf)) mustEqual result
                check(input)
              }
            //              "async" in {
            //                Try(Await.result(f(Arrow.failed(ex).delayed(5 millis)).run, Duration.Inf)) mustEqual result
            //                check(input)
            //              }
          }
        }
    }
  }

  "instance" - {

    "apply" - {
      test(
        _.flatMap(Arrow[Int]),
        List(
          Success(1) -> Success(1),
          Failure(ex) -> Failure(ex)
        )
      )
    }
    "andThen" - {
      "identity" - {
        test(
          _.andThen(Arrow[Int]),
          List(
            Success(1) -> Success(1),
            Failure(ex) -> Failure(ex)
          )
        )
      }
      "with map" - {
        test(
          _.andThen(Arrow[Int].map(_ + 1)),
          List(
            Success(1) -> Success(2),
            Failure(ex) -> Failure(ex)
          )
        )
      }
      "with handle" - {
        test(
          _.andThen(Arrow[Int].recover { case _ => 2 }),
          List(
            Success(1) -> Success(1),
            Failure(ex) -> Success(2)
          )
        )
      }
    }

    "map" - {
      "success" - {
        test(
          _.map(_ + 1),
          List(
            Success(1) -> Success(2),
            Failure(ex) -> Failure(ex)
          )
        )
      }
      "failure" - {
        test(
          _.map(_ => throw ex),
          List(
            Success(1) -> Failure(ex),
            Failure(ex) -> Failure(ex)
          )
        )
      }
    }

    "flatMap" - {
      "success" - {
        test(
          _.flatMap(i => Task.successful(i + 1)),
          List(
            Success(1) -> Success(2),
            Failure(ex) -> Failure(ex)
          )
        )
      }
      "failure" - {
        test(
          _.flatMap(_ => throw ex),
          List(
            Success(1) -> Failure(ex),
            Failure(ex) -> Failure(ex)
          )
        )
      }
    }

    "recover" - {
      "success" - {
        test(
          _.recover { case _ => 2 },
          List(
            Success(1) -> Success(1),
            Failure(ex) -> Success(2)
          )
        )
      }
      "failure" - {
        test(
          _.recover { case _ => throw ex },
          List(
            Success(1) -> Success(1),
            Failure(ex) -> Failure(ex)
          )
        )
      }
    }

    "onComplete" - {
      "success" - {
        var r: Try[Int] = null
        test(
          _.onComplete(r = _),
          List(
            Success(1) -> Success(1),
            Failure(ex) -> Failure(ex)
          ),
          r mustEqual _
        )
      }
      "failure" - {
        var r: Try[Int] = null
        test(
          _.onComplete { i =>
            r = i
            throw ex
          },
          List(
            Success(1) -> Success(1),
            Failure(ex) -> Failure(ex)
          ),
          r mustEqual _
        )
      }
    }

    "transform" - {
      "success" - {
        test(
          _.transform(identity),
          List(
            Success(1) -> Success(1),
            Failure(ex) -> Failure(ex)
          )
        )
      }
      "failure" - {
        test(
          _.transform(_ => Failure(ex)),
          List(
            Success(1) -> Failure(ex),
            Failure(ex) -> Failure(ex)
          )
        )
      }
    }

    "transformWith" - {
      "success" - {
        test(
          _.transformWith(Task.successful),
          List(
            Success(1) -> Success(Success(1)),
            Failure(ex) -> Success(Failure(ex))
          )
        )
      }
      "failure" - {
        test(
          _.transformWith(_ => throw ex),
          List(
            Success(1) -> Failure(ex),
            Failure(ex) -> Failure(ex)
          )
        )
      }
    }

    "recoverWith" - {
      object other extends Exception
      "success" - {
        test(
          _.recoverWith {
            case `ex`                    => Task.successful(2)
            case _: NullPointerException => Task.successful(3)
          },
          List(
            Success(1) -> Success(1),
            Failure(ex) -> Success(2),
            Failure(new NullPointerException) -> Success(3),
            Failure(other) -> Failure(other)
          )
        )
      }
      "failure" - {
        test(
          _.recoverWith {
            case _ => throw ex
          },
          List(Failure(new NullPointerException) -> Failure(ex))
        )
      }
    }

    "foreach" - {
      "success" - {
        var c1 = 0
        var c2 = 0
        test(
          _.foreach(c1 += _),
          List(
            Success(1) -> Success(1)
          ),
          _ => {
            c2 += 1
            c1 mustEqual c2
          }
        )
      }

      "failure" - {
        var c = false
        test(
          _.foreach(_ => c = true),
          List(
            Failure(ex) -> Failure(ex)
          ),
          _ => {
            c mustEqual false
          }
        )
      }
    }

    "onSuccess" - {
      "success" - {
        var c1 = 0
        var c2 = 0
        test(
          _.onSuccess { case i => c1 += i },
          List(
            Success(1) -> Success(1)
          ),
          _ => {
            c2 += 1
            c1 mustEqual c2
          }
        )
      }

      "failure" - {
        var c = false
        test(
          _.onSuccess { case _ => c = true },
          List(
            Failure(ex) -> Failure(ex)
          ),
          _ => {
            c mustEqual false
          }
        )
      }
    }

    "filter" - {
      "success" - {
        test(
          _.filter(_ == 1),
          List(
            Success(1) -> Success(1),
            Failure(ex) -> Failure(ex)
          )
        )
      }
      "failure" - {
        test(
          _.filter(_ => throw ex),
          List(
            Success(1) -> Failure(ex),
            Failure(ex) -> Failure(ex)
          )
        )
      }
    }

    "withFilter" - {
      "success" - {
        test(
          _.withFilter(_ == 1),
          List(
            Success(1) -> Success(1),
            Failure(ex) -> Failure(ex)
          )
        )
      }
      "failure" - {
        test(
          _.withFilter(_ => throw ex),
          List(
            Success(1) -> Failure(ex),
            Failure(ex) -> Failure(ex)
          )
        )
      }
    }

    "onFailure" - {
      "failure" - {
        var c1 = 0
        var c2 = 0
        test(
          _.onFailure { case _ => c1 += 1 },
          List(
            Failure(ex) -> Failure(ex)
          ),
          _ => {
            c2 += 1
            c1 mustEqual c2
          }
        )
      }
      "success" - {
        var c = false
        test(
          _.onFailure { case _ => c = true },
          List(
            Success(1) -> Success(1)
          ),
          _ => {
            c mustEqual false
          }
        )
      }
    }

    "zip" - {
      test(
        _.zip(Task.successful(2)),
        List(
          Success(1) -> Success((1, 2)),
          Failure(ex) -> Failure(ex)
        )
      )
    }

    "zipWith" - {
      "success" - {
        test(
          _.zipWith(Task.successful(2))(_ + _),
          List(
            Success(1) -> Success(3),
            Failure(ex) -> Failure(ex)
          )
        )
      }
      //      "failure" - {
      //        test(
      //          _.joinWith(Task.successful(2))((_, _) => throw ex),
      //          List(
      //            Success(1) -> Failure(ex),
      //            Failure(ex) -> Failure(ex)))
      //      }
    }

    "flatten" - {
      test(
        _.map(Task.successful).flatten,
        List(
          Success(1) -> Success(1),
          Failure(ex) -> Failure(ex)
        )
      )
    }
  }

  "companion" - {
    "toFuture" in {
      val f: Future[Int] = Arrow.successful(1)
      eval(f) mustEqual 1
    }
    "fromFuture" in {
      val f: Arrow[Unit, Int] = Future.successful(1)
      eval(f.run) mustEqual 1
    }
    "apply" in {
      eval(Arrow[Int].run(1)) mustEqual 1
    }
    "fork" in {
      val ex = Executors.newSingleThreadExecutor()
      try {
        implicit val ec = ExecutionContext.fromExecutor(ex)
        var thread: Thread = null
        eval(Arrow.fork(Task {
          thread = Thread.currentThread
        }))
        thread != null && thread != Thread.currentThread() mustEqual true
      } finally {
        ex.shutdown()
      }
    }
    "recursive" in {
      val sum =
        Arrow.recursive[List[Int], Int] { self =>
          Arrow[List[Int]].flatMap {
            case Nil          => Task.successful(0)
            case head :: tail => self(tail).map(_ + head)
          }
        }
      eval(sum, List(1, 2)) mustEqual 3
    }
    "successful" in {
      eval(Arrow.successful(1), ()) mustEqual 1
    }
    "failed" in {
      Try(eval(Arrow.failed(ex).run)) mustEqual Failure(ex)
    }
    "collect" in {
      val a1 = Arrow[Int].map(_ + 1)
      val a2 = Arrow[Int].map(_ + 2)
      val a = Arrow.sequence(List(a1, a2))
      eval(a, 1).toList mustEqual List(2, 3)
    }
  }
}