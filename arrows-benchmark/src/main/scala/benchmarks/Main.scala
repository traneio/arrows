package benchmarks

object Main {

  def main(args: Array[String]): Unit = {

    val b = new AsyncSuccessOnlyBenchmarks

    println(b.arrowsStdlibArrow.get)

    b.tearDown()
  }

}