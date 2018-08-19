package io.foolproof.stats

import java.util
import java.util.Comparator

class TDigest(compression: Double, maxSize: Int) {
  import TDigest._

  private var min = 0.0
  private var max = 0.0

  private var totalWeight = 0.0

  private final val centroids = new util.ArrayList[Centroid]()
  private final val temp = new util.ArrayList[Centroid]()


  def add(x: Double, w: Double): Unit = {
    if (temp.size >= maxSize - centroids.size - 1) {
      flushBuffer()
    }
    temp.add(Centroid(x, w))
  }


  def quantile(q: Double): Double = {
    if (q < 0 || q > 1) {
      throw new IllegalArgumentException("q should be in [0,1], got " + q)
    }
    flushBuffer()

    if (centroids.isEmpty) {
      // no centroids means no data, no way to get a quantile
      return Double.NaN
    } else if (centroids.size == 1) {
      // with one data point, all quantiles lead to Rome
      return centroids.get(0).mean
    }

    // we know that there are at least two centroids now
    val n = centroids.size

    // if values were stored in a sorted array, index would be the offset we are interested in
    val index = q * totalWeight

    // at the boundaries, we return min or max
    if (index < centroids.get(0).weight / 2) {
      assert(centroids.get(0).weight > 0)
      return min + 2 * index / centroids.get(0).weight * (centroids.get(0).mean - min)
    }

    // in between we interpolate between centroids
    var weightSoFar = centroids.get(0).weight / 2
    for (i <- 0 until n - 1) {
      val dw = (centroids.get(i).weight + centroids.get(i + 1).weight) / 2
      if (weightSoFar + dw > index) {
        // centroids i and i+1 bracket our current point
        val z1 = index - weightSoFar
        val z2 = weightSoFar + dw - index
        return weightedAverage(centroids.get(i).mean, z2, centroids.get(i + 1).mean, z1)
      }
      weightSoFar += dw
    }
    assert(index <= totalWeight)
    assert(index >= totalWeight - centroids.get(n - 1).weight / 2)

    // weightSoFar = totalWeight - weight[n-1]/2 (very nearly)
    // so we interpolate out to max value ever seen
    val z1 = index - totalWeight - centroids.get(n - 1).weight / 2.0
    val z2 = centroids.get(n - 1).weight / 2 - z1
    weightedAverage(centroids.get(n - 1).mean, z1, max, z2)
  }

  private def flushBuffer(): Unit = {
    if (temp.isEmpty) {
      return
    }
    for (i <- 0 until temp.size()) {
      totalWeight += temp.get(i).weight
    }
    temp.addAll(centroids)
    centroids.clear()
    temp.sort(Comparator.naturalOrder())

    val normalizer = compression / (Math.PI * totalWeight)

    var wSoFar = 0.0
    var acc = temp.get(0)
    for (i <- 1 until temp.size) {
      val proposedWeight = acc.weight + temp.get(i).weight
      val z = proposedWeight * normalizer
      val q0 = wSoFar / totalWeight
      val q2 = (wSoFar + proposedWeight) / totalWeight

      if (z * z <= q0 * (1 - q0) && z * z <= q2 * (1 - q2)) {
        // next point will fit, so merge into existing centroid
        acc.add(temp.get(i))
      } else {
        // didn't fit ... move to next output, copy out first centroid
        wSoFar += acc.weight
        centroids.add(acc)
        acc = temp.get(i)
      }
    }
    centroids.add(acc)

    if (totalWeight > 0) {
      min = Math.min(min, centroids.get(0).mean)
      max = Math.max(max, centroids.get(centroids.size() - 1).mean)
    }
    temp.clear()
  }
}

object TDigest {
  private final case class Centroid(var mean: Double, var weight: Double) extends Comparable[Centroid] {
    def add(that: Centroid): Unit = {
      this.mean = (this.mean * this.weight + that.mean * that.weight) / (this.weight + that.weight)
      this.weight += that.weight
    }

    override def compareTo(that: Centroid): Int = this.mean.compareTo(that.mean)
  }


  private def weightedAverage(x1: Double, w1: Double, x2: Double, w2: Double): Double = {
    require(x1 <= x2)
    val x = (x1 * w1 + x2 * w2) / (w1 + w2)
    Math.max(x1, Math.min(x, x2))
  }
}
