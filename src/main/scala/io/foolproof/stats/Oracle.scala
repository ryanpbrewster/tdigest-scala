package io.foolproof.stats

import java.util

class Oracle(values: Array[Double]) {
  def quantile(q: Double): Double = {
    if (q <= 0.0) {
      values.head
    } else if (q >= 1.0) {
      values.last
    } else {
      values((q * (values.length - 1)).toInt)
    }
  }

  /** Given a value, `v`, find which quantile that value belongs to. */
  def rank(v: Double): Double = {
    var idx = util.Arrays.binarySearch(values, v)
    if (idx < 0) {
      idx = -idx - 1
    }
    idx.toDouble / (values.length - 1)
  }
}
