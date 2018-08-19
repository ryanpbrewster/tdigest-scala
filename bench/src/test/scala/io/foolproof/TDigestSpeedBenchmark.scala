package io.foolproof

import java.util.Random
import java.util.concurrent.TimeUnit

import com.tdunning.math.stats.MergingDigest
import io.foolproof.stats.{SimplifiedDigest, TDigest}
import org.openjdk.jmh.annotations._

class TDigestSpeedBenchmark {
  @Benchmark
  @BenchmarkMode(Array(Mode.AverageTime))
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  def runAdd(scenario: TDigestSpeedBenchmark.AddScenario): Any = {
    val estimator = scenario.newEstimator()
    for (i <- 0 until scenario.size) {
      val x = 5000.0 + 1000.0 * scenario.prng.nextDouble()
      estimator.add(x)
    }
    estimator.quantile(0.99)
  }
}

object TDigestSpeedBenchmark {
  @State(Scope.Benchmark)
  class AddScenario {
    @Param(Array("small", "medium"))
    var scenarioSize: String = _
    var size: Int = _
    var prng: Random = _

    @Param(Array("original", "simplified", "transliterated"))
    var estimatorType: String = _

    def newEstimator(): Estimator = estimatorType match {
      case "original" => new Original(100.0)
      case "simplified" => new Simplified(100.0)
      case "transliterated" => new Transliterated(100.0)
    }


    @Setup
    def setUp(): Unit = {
      prng = new Random(42)
      size = scenarioSize match {
        case "small" => 1e2.toInt
        case "medium" => 1e4.toInt
      }
    }
  }

  trait Estimator {
    def add(x: Double): Unit
    def quantile(q: Double): Double
  }
  class Original(z: Double) extends MergingDigest(z) with Estimator
  class Simplified(z: Double) extends SimplifiedDigest(z) with Estimator {
    override def add(x: Double): Unit = add(x, 1.0)
  }
  class Transliterated(z: Double) extends TDigest(z, (5*z).toInt) with Estimator {
    override def add(x: Double): Unit = add(x, 1.0)
  }
}
