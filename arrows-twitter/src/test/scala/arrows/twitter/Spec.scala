package arrows.twitter

import org.scalatest.MustMatchers
import org.scalatest.FreeSpec
import com.twitter.util.Await

class Spec extends FreeSpec with MustMatchers {
  final def eval[T, U](a: Arrow[T, U], value: T): U =
    Await.result(a.run(value))

  final def eval[T, U](a: Arrow[T, U])(implicit ev: T => Unit): U =
    Await.result(a.run(ev))
}