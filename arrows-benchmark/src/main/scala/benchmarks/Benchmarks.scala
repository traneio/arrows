package benchmarks

import org.openjdk.jmh.annotations._
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

@Warmup(iterations = 6, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 6, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(1)
abstract class Benchmarks(val dist: List[(Gen.Op, Int)]) {

  implicit val scheduler = Executors.newSingleThreadExecutor()

  @Setup
  def checkImpls() = {
    val methods = getClass.getMethods.filter(_.getAnnotation(classOf[Benchmark]) != null)
    val results = methods.map(m => m.getName -> m.invoke(this)).toMap
    if (results.values.toSet.size != 1) {
      tearDown
      val grouped = results.groupBy(_._2).mapValues(_.map(_._1))
      sys.error(s"At least one benchmark returned a different value. Results: $grouped")
    }
  }

  @TearDown
  def tearDown() =
    scheduler.shutdown()
}

class SyncSuccessOnlyBenchmarks
  extends Benchmarks(
    List(
      Gen.Sync -> 30,
      Gen.Map -> 50,
      Gen.FlatMap -> 30
    )
  )
  with ArrowsTwitter
  with ArrowsStdlib
  with CatsEffect
  with MonixSync
  with ScalaFuture
  with TwitterFuture
  with TraneIO
  with Scalaz

class SyncWithFailuresBenchmarks
  extends Benchmarks(
    List(
      Gen.Sync -> 30,
      Gen.Failure -> 5,
      Gen.Map -> 50,
      Gen.FlatMap -> 30,
      Gen.Handle -> 10
    )
  )
  with ArrowsTwitter
  with ArrowsStdlib
  with CatsEffect
  with MonixSync
  with ScalaFuture
  with TwitterFuture
  with TraneIO
  with Scalaz

class AsyncSuccessOnlyBenchmarks
  extends Benchmarks(
    List(
      Gen.Sync -> 30,
      Gen.Async -> 5,
      Gen.Map -> 50,
      Gen.FlatMap -> 30
    )
  )
  with ArrowsTwitter
  with ArrowsStdlib
  with CatsEffect
  with MonixAsync
  with ScalaFuture
  with TwitterFuture
  with TraneIO
// with Scalaz bug https://github.com/scalaz/scalaz/issues/1665

class AsyncWithFailuresBenchmarks
  extends Benchmarks(
    List(
      Gen.Sync -> 30,
      Gen.Async -> 5,
      Gen.Failure -> 5,
      Gen.Map -> 50,
      Gen.FlatMap -> 30,
      Gen.Handle -> 10
    )
  )
  with ArrowsTwitter
  with ArrowsStdlib
  with CatsEffect
  with MonixAsync
  with ScalaFuture
  with TwitterFuture
  with TraneIO
// with Scalaz bug https://github.com/scalaz/scalaz/issues/1665
