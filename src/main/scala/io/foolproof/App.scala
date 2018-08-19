package io.foolproof

import java.util

import com.tdunning.math.stats.MergingDigest
import io.foolproof.stats.{Oracle, TDigest, TransliterationImpl}

import scala.util.Random

object App {
  final val N = 1000000
  def main(args: Array[String]): Unit = {
    println("Hello, World!")

    val prng = new Random(42)

    val buf = new Array[Double](N)
    val reference = new MergingDigest(100.0)
    val simplified = new TransliterationImpl(100.0)
    val transliterated = new TDigest(100.0, maxSize = 500)
    for (i <- 0 until N) {
      val x = 5000.0 + 1000.0 * prng.nextGaussian()
      buf(i) = x
      reference.add(x)
      simplified.add(x, 1.0)
      transliterated.add(x, 1.0)
    }

    util.Arrays.sort(buf)
    val oracle = new Oracle(buf)
    val quantiles = Array.range(0, 10001).map(x => x.toDouble / 10000.0)
    for (q <- quantiles) {
      print(f"$q%.4f")
      print(f" ${oracle.rank(reference.quantile(q)) - q}%12.9f")
      print(f" ${oracle.rank(simplified.quantile(q)) - q}%12.9f")
      print(f" ${oracle.rank(transliterated.quantile(q)) - q}%12.9f")
      println()
    }
  }
}
