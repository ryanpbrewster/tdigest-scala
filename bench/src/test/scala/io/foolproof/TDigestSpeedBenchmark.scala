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
  @Fork(1)
  @Warmup(iterations = 5, time = 3)
  @Measurement(iterations = 5, time = 3)
  def runAdd(scenario: TDigestSpeedBenchmark.AddScenario): Any = {
    val x = 5000.0 + 1000.0 * scenario.prng.nextDouble()
    scenario.estimator.add(x)
  }
}

object TDigestSpeedBenchmark {
  @State(Scope.Benchmark)
  class AddScenario {
    var prng: Random = _
    var estimator: Estimator = _

    @Param(Array("simplified", "transliterated", "original"))
    var estimatorType: String = _


    @Setup
    def setUp(): Unit = {
      prng = new Random(42)
      estimator = estimatorType match {
        case "original" => new Original(100.0)
        case "simplified" => new Simplified(100.0)
        case "transliterated" => new Transliterated(100.0)
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
  class Transliterated(z: Double) extends TDigest(z, (10*z).toInt) with Estimator {
    override def add(x: Double): Unit = add(x, 1.0)
  }
}
