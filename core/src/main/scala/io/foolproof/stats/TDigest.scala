package io.foolproof.stats

import java.util
import java.util.Comparator

class TDigest(compression: Double, bufferSize: Int) {
  import TDigest._

  private var min = 0.0
  private var max = 0.0

  private var totalWeight = 0.0
  private var bufferedWeight = 0.0

  private final val centroids = new Array[Centroid](bufferSize)
  private var numCentroids = 0
  private final val buffer = new Array[Centroid](bufferSize)
  private var numBuffered = 0


  def add(x: Double, w: Double): Unit = {
    if (numBuffered + numCentroids + 1 > bufferSize) {
      flushBuffer()
    }
    buffer(numBuffered) = new Centroid(x, w)
    bufferedWeight += w
    numBuffered += 1
  }


  def quantile(q: Double): Double = {
    flushBuffer()

    if (numCentroids == 0) {
      // no centroids means no data, no way to get a quantile
      return Double.NaN
    } else if (numCentroids == 1) {
      // with one data point, all quantiles lead to Rome
      return centroids(0).mean
    } else if (q <= 0) {
      return min
    } else if (q >= 1) {
      return max
    }

    // we know that there are at least two centroids now
    val n = numCentroids

    // if values were stored in a sorted array, index would be the offset we are interested in
    val index = q * totalWeight

    // at the boundaries, we return min or max
    if (index < centroids(0).weight / 2) {
      assert(centroids(0).weight > 0)
      return min + 2 * index / centroids(0).weight * (centroids(0).mean - min)
    }

    // in between we interpolate between centroids
    var weightSoFar = centroids(0).weight / 2
    for (i <- 0 until n - 1) {
      val dw = (centroids(i).weight + centroids(i + 1).weight) / 2
      if (weightSoFar + dw > index) {
        // centroids i and i+1 bracket our current point
        val z1 = index - weightSoFar
        val z2 = weightSoFar + dw - index
        return weightedAverage(centroids(i).mean, z2, centroids(i + 1).mean, z1)
      }
      weightSoFar += dw
    }
    assert(index <= totalWeight)
    assert(index >= totalWeight - centroids(n - 1).weight / 2)

    // weightSoFar = totalWeight - weight[n-1]/2 (very nearly)
    // so we interpolate out to max value ever seen
    val z1 = index - totalWeight - centroids(n - 1).weight / 2.0
    val z2 = centroids(n - 1).weight / 2 - z1
    weightedAverage(centroids(n - 1).mean, z1, max, z2)
  }

  private def flushBuffer(): Unit = {
    if (buffer.isEmpty) {
      return
    }

    System.arraycopy(centroids, 0, buffer, numBuffered, numCentroids)
    totalWeight += bufferedWeight
    numBuffered += numCentroids
    numCentroids = 0
    util.Arrays.sort(buffer, 0, numBuffered, CENTROID_COMPARATOR)

    val normalizer = compression / (Math.PI * totalWeight)

    var wSoFar = 0.0
    var acc = buffer(0)
    for (i <- 1 until numBuffered) {
      val proposedWeight = acc.weight + buffer(i).weight
      val z = proposedWeight * normalizer
      val q0 = wSoFar / totalWeight
      val q2 = (wSoFar + proposedWeight) / totalWeight

      if (z * z <= q0 * (1 - q0) && z * z <= q2 * (1 - q2)) {
        // next point will fit, so merge into existing centroid
        acc.add(buffer(i))
      } else {
        // didn't fit ... move to next output, copy out first centroid
        wSoFar += acc.weight
        centroids(numCentroids) = acc
        numCentroids += 1
        acc = buffer(i)
      }
    }
    centroids(numCentroids) = acc
    numCentroids += 1

    if (min > centroids(0).mean) {
      min = centroids(0).mean
    }
    if (max < centroids(numCentroids - 1).mean) {
      max = centroids(numCentroids - 1).mean
    }
    numBuffered = 0
    bufferedWeight = 0
  }
}

object TDigest {
  private final class Centroid(var mean: Double, var weight: Double) {
    def add(that: Centroid): Unit = {
      this.mean = (this.mean * this.weight + that.mean * that.weight) / (this.weight + that.weight)
      this.weight += that.weight
    }
  }
  private final val CENTROID_COMPARATOR: Comparator[Centroid] = (a: Centroid, b: Centroid) => a.mean.compareTo(b.mean)


  private def weightedAverage(x1: Double, w1: Double, x2: Double, w2: Double): Double = {
    require(x1 <= x2)
    val x = (x1 * w1 + x2 * w2) / (w1 + w2)
    Math.max(x1, Math.min(x, x2))
  }
}
