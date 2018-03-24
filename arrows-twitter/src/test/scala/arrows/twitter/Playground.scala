package arrows.twitter

import com.twitter.util.Await

object Playground extends App {

  var t = Task.value(1).liftToTry
  for (_ <- (0 until 10000))
    t = t.map(_.map(_ + 1))

  Await.result(t.run)
}