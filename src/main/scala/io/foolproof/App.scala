package io.foolproof

import java.util

import com.tdunning.math.stats.MergingDigest
import io.foolproof.stats.Oracle

import scala.util.Random

object App {
  final val N = 1000000
  def main(args: Array[String]): Unit = {
    println("Hello, World!")

    val prng = new Random(42)

    val buf = new Array[Double](N)
    val refs = Array(10, 25, 100, 500).map(z => new MergingDigest(z))
    for (i <- 0 until N) {
      val x = 5000.0 + 1000.0 * prng.nextGaussian()
      buf(i) = x
      for (ref <- refs) {
        ref.add(x)
      }
    }

    util.Arrays.sort(buf)
    val oracle = new Oracle(buf)
    val quantiles = Array.range(0, 10001).map(x => x.toDouble / 10000.0)
    for (q <- quantiles) {
      print(f"$q%.4f")
      for (ref <- refs) {
        print(f" ${oracle.rank(ref.quantile(q)) - q}%12.9f")
      }
      println()
    }
  }
}
