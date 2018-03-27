package benchmarks

object Main {

  def main(args: Array[String]): Unit = {

    val b = new AsyncSuccessOnlyBenchmarks
    b.checkImpls()
    println(b.arrowsStdlibArrow.get)

    b.tearDown()
  }

}