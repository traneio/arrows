package benchmarks

import scala.annotation.tailrec
import scala.util.Random
import java.util.concurrent.ExecutorService

trait Gen[T] {

  def apply(depth: Int, dist: List[(Gen.Op, Int)])(implicit s: ExecutorService): T = {
    val rnd = new Random(1)
    import rnd._

    val values = dist.collect { case (g: Gen.Value, i) => (g, i) }
    val transforms = dist.collect { case (g: Gen.Transform, i) => (g, i) }

    require(values.nonEmpty)

    def choose[O <: Gen.Op](l: List[(O, Int)]): O = {
      @tailrec
      def find(n: Int, prev: Int, l: List[(O, Int)]): O = {
        l match {
          case Nil => ???
          case (o, i) :: tail =>
            if (prev + i > n) o
            else find(n, prev + i, tail)
        }
      }

      val max = l.map(_._2).sum
      find(nextInt(max), 0, l)
    }

    val ex = new Exception

    def genValue: T =
      choose(values) match {
        case Gen.Async =>
          async(s.submit(_))
        case Gen.Sync =>
          sync
        case Gen.Failure =>
          failure(ex)
      }

    def genTransform(depth: Int, t: T): T =
      depth match {
        case 0 => t
        case _ =>
          choose(transforms) match {
            case Gen.Map =>
              val i = nextInt
              genTransform(depth - 1, map(t, _ + i))
            case Gen.FlatMap =>
              val d = nextInt(depth)
              val n = genTransform(depth - d, genValue)
              genTransform(d, flatMap(t, n))
            case Gen.Handle =>
              val i = nextInt
              genTransform(depth - 1, handle(t, i))
          }
      }

    genTransform(depth, genValue)
  }

  def sync: T
  def async(schedule: Runnable => Unit): T
  def failure(ex: Throwable): T
  def map(t: T, f: Int => Int): T
  def flatMap(t: T, f: T): T
  def handle(t: T, i: Int): T
}

object Gen {
  sealed trait Op

  sealed trait Value extends Op
  case object Async extends Value
  case object Sync extends Value
  case object Failure extends Value

  sealed trait Transform extends Op
  case object Map extends Transform
  case object FlatMap extends Transform
  case object Handle extends Transform
}
