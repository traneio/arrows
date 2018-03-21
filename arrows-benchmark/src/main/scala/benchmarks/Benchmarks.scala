package benchmarks

import org.openjdk.jmh.annotations._
import java.util.concurrent.TimeUnit
import scalaz.effect.RTS
import scala.util.Try
import java.util.concurrent.Executors

@Warmup(iterations = 4, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 4, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(1)
abstract class Benchmarks {

  //  @Param(Array("10", "100", "1000"))
  var depth: Int = 100

  def dist: List[(Gen.Op, Int)]

  implicit val scheduler = Executors.newSingleThreadExecutor()
  object rts extends RTS

  lazy val arrow = ArrowsArrowGen(depth, dist)
  lazy val task = ArrowsTaskGen(depth, dist)

    @Setup
    def checkImpls() = {
      val results = Set(
        arrowsArrow,
        arrowsTask,
//        scalazIO,
        monixTask,
        traneIOFuture,
        scalaFuture,
        twitterFuture,
        catsEffectIO
      )
      require(results.size == 1)
    }

  @TearDown
  def tearDown() = {
    scheduler.shutdown()
    rts.threadPool.shutdown()
  }

  @Benchmark
  def arrowsArrow = {
    import com.twitter.util.Await
    Try(Await.result(arrow.run(1)))
  }

  @Benchmark
  def arrowsTask = {
    import com.twitter.util.Await
    Try(Await.result(task(1).run))
  }

  lazy val io = ScalazIOGen(depth, dist)

//  @Benchmark
//  def scalazIO = {
//    Try(rts.unsafePerformIO(io(1)))
//  }

  lazy val t = MonixTaskGen(depth, dist)

  @Benchmark
  def monixTask = {
    import scala.concurrent._
    import scala.concurrent.duration._
    import monix.execution.Scheduler.Implicits.global
    Try(Await.result(t(1).runAsync, Duration.Inf))
  }

  lazy val fut = TraneIOFutureGen(depth, dist)
  val inf = java.time.Duration.ofMillis(Int.MaxValue)

  @Benchmark
  def traneIOFuture = {
    Try(fut(1).get(inf))
  }

  lazy val sFut = ScalaFutureGen(depth, dist)

  @Benchmark
  def scalaFuture = {
    import scala.concurrent._
    import scala.concurrent.duration._
    Try(Await.result(sFut(1), Duration.Inf))
  }
  
   lazy val tFut = TwitterFutureGen(depth, dist)

  @Benchmark
  def twitterFuture = {
     import com.twitter.util.Await
    Try(Await.result(tFut(1)))
  }
   
      lazy val catsIO = CatsEffectIOGen(depth, dist)

  @Benchmark
  def catsEffectIO = {
    Try(catsIO(1).unsafeRunSync())
  }
}

class SyncSuccessOnlyBenchmarks extends Benchmarks {
  override def dist: List[(Gen.Op, Int)] = List(
    Gen.Sync -> 30,
    Gen.Map -> 50,
    Gen.FlatMap -> 30,
  )
}

class SyncWithFailuresBenchmarks extends Benchmarks {
  override def dist: List[(Gen.Op, Int)] = List(
    Gen.Sync -> 30,
    Gen.Failure -> 5,
    Gen.Map -> 50,
    Gen.FlatMap -> 30,
    Gen.Handle -> 10
  )
}

class AsyncSuccessOnlyBenchmarks extends Benchmarks {
  override def dist: List[(Gen.Op, Int)] = List(
    Gen.Sync -> 30,
    Gen.Async -> 5,
    Gen.Map -> 50,
    Gen.FlatMap -> 30,
  )
}

//object Test extends App {
//  val b = new AsyncSuccessOnlyBenchmarks
//  val e = b.arrowsTask
//  for(_ <- (0 until 1000000).par) println(b.arrowsTask)
//  println(b.arrowsTask)
//  println(b.traneIOFuture)
//  b.tearDown()
//}

class AsyncWithFailuresBenchmarks extends Benchmarks {
  override def dist: List[(Gen.Op, Int)] = List(
    Gen.Sync -> 30,
    Gen.Async -> 5,
    Gen.Failure -> 5,
    Gen.Map -> 50,
    Gen.FlatMap -> 30,
    Gen.Handle -> 10
  )
}
