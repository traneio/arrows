package com.twitter.arrows

import com.twitter.util.Await
import com.twitter.util.Duration
import com.twitter.util.Future

object Testt extends App {

  def zero = 0

  val a =
    Arrow[Int].flatMap {
      case 1 => Task.value(0)
      case i => Task.value(i + 1).map(_ + 2)
    }

  println(Await.result(a.run(10)))
}

//object Laws extends App {
//
//  val a = 11
//  val m = Arrow.value(a)
//
//  def f(x: Int) = Arrow.value(x * 2)
//  def g(x: Int) = Arrow.value(x - 10)
//
//  val run = IdInterpreter
//
//  // Left
//  require(run(Arrow[Int].flatMap(f), a) == run(f(a), {}))
//
//  // Right
//  require(run(Arrow[Int].flatMap(Arrow.value), a) == a)
//
//  // Associativity
//  require(run(Arrow[Int].flatMap(f).flatMap(g), a) == run(Arrow[Int].flatMap(v => f(v).flatMap(g)), a))
//}
//
object Bench extends App {
  def bench(name: String)(f: => Unit) =
    for (i <- 0 until 10) {
      var i = 1000000
      val start = System.nanoTime()
      while (i > 0) {
        f
        i -= 1
      }
      println(s"$name: ${System.nanoTime() - start}")
    }

  import scala.concurrent.ExecutionContext.Implicits.global

  val a1 = Arrow[Int].map(_ + 1).map(_ + 2).flatMap(i => Task.value(i + 1).map(_ + 2).flatMap(_ => Task.value(10))).flatMap(i => Task.value(i + 3))

  def u: Unit = Arrow[Int]

  println(Await.result(a1.run(1)))

  bench("Arrow.map ") {
    a1.run(1)
  }

  bench("Future.map") {
    Future.value(1).map(_ + 1).map(_ + 2).flatMap(i => Future.value(i + 1).map(_ + 2).flatMap(_ => Future.value(10)))
  }
}
//
//object Test extends App {
//  import scala.concurrent.ExecutionContext.Implicits.global
//
//  val ar = Arrow[Int].map(_ + 1).map(_ + 2).flatMap(_ => Arrow.value(33)).map(_ + 3).flatMap(_ => Arrow.value(22))
//
//  Optimize(ar)
//
//  Arrow[Int].flatMap {
//    case 1 => Arrow.value(2)
//    case _ => Arrow.value(3)
//  }
//
//  //  val sumR: Arrow[List[Int], Int] =
//  //    Arrow.foldLeft[Int, Int](0) {
//  //      case (a, b) => a + b
//  //    }
//  //
//  //  test(sumR, List(10, 20))
//
//  //  println(ListInterpreter(sumR, List(List(10, 1), List(10, 2))))
//
//  val sumA =
//    Arrow.recursive[List[Int], Int] { self =>
//      Arrow.flatMap {
//        case Nil => Arrow.value(0)
//        case head :: tail => self(tail).map(_ + head)
//      }
//    }
//
//  def sumF(l: List[Int]): Future[Int] =
//    l match {
//      case Nil => Future.successful(0)
//      case head :: tail => sumF(tail).map(_ + head)
//    }
//
//  test(sumA, List(1, 1, 1, 2))
//
//  def test[T, U](a: Arrow[T, U], value: T) =
//    println(Await.result(FutureInterpreter(value, a), Duration.Inf))
//
//  val a = Arrow.map[Int, Int](_ + 1)
//
//  val f = FutureInterpreter((), Arrow.value(1).map(_ + 1).flatMap(a))
//
//  test(Arrow.value(1).map(_ + 1).flatMap(a), {})
//}