package benchmarks

import org.scalatest.FreeSpec

class BenchmarksCheck extends FreeSpec {

  "all benchmarks should return the same result" - {
    "SyncSuccessOnlyBenchmarks" in {
      (new SyncSuccessOnlyBenchmarks).checkImpls()
    }
    "SyncWithFailuresBenchmarks" in {
      (new SyncWithFailuresBenchmarks).checkImpls()
    }
    "AsyncSuccessOnlyBenchmarks" in {
      (new SyncWithFailuresBenchmarks).checkImpls()
    }
    "AsyncWithFailuresBenchmarks" in {
      (new SyncWithFailuresBenchmarks).checkImpls()
    }
  }
}