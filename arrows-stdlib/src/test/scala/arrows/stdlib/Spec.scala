package arrows.stdlib

import org.scalatest.MustMatchers
import org.scalatest.FreeSpec
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global

class Spec extends FreeSpec with MustMatchers {
  final def eval[T, U](a: Arrow[T, U], value: T): U =
    Await.result(a.run(value), Duration.Inf)

  final def eval[T, U](a: Arrow[T, U])(implicit ev: T => Unit): U =
    Await.result(a.run(global, ev), Duration.Inf)
}