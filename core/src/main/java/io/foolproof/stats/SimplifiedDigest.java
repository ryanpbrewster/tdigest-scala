package io.foolproof.stats;

import java.util.Arrays;

public class SimplifiedDigest {
    private final double compression;

    private double min = 0;
    private double max = 0;

    private double totalWeight = 0;

    private final Centroid[] centroids;
    private int numCentroids = 0;
    private int numBuffered = 0;

    public SimplifiedDigest(double compression) {
        this(compression, 5 * (int) Math.ceil(compression));
    }
    public SimplifiedDigest(double compression, int size) {
        this.compression = compression;
        this.centroids = new Centroid[size];
    }

    public void add(double x, double w) {
        if (numCentroids + numBuffered + 1 >= centroids.length) {
            flushBuffer();
        }
        centroids[numCentroids + numBuffered++] = new Centroid(x, w);
        totalWeight += w;
    }

    private void flushBuffer() {
        if (numBuffered == 0) {
            return;
        }
        numBuffered += numCentroids;
        numCentroids = 0;
        Arrays.sort(centroids, 0, numBuffered);

        double normalizer = compression / (Math.PI * totalWeight);
        double wSoFar = 0;
        Centroid acc = centroids[0];
        for (int i = 1; i < numBuffered; i++) {
            double proposedWeight = acc.weight + centroids[i].weight;
            double z = proposedWeight * normalizer;
            double q0 = wSoFar / totalWeight;
            double q2 = (wSoFar + proposedWeight) / totalWeight;

            if (z * z <= q0 * (1 - q0) && z * z <= q2 * (1 - q2)) {
                // next point will fit, so merge into existing centroid
                acc.add(centroids[i]);
            } else {
                // didn't fit ... move to next output, copy out first centroid
                wSoFar += acc.weight;
                centroids[numCentroids++] = acc;
                acc = centroids[i];
            }
        }
        centroids[numCentroids++] = acc;

        // sanity check
        double sum = 0;
        for (int i=0; i < numCentroids; i++) {
            sum += centroids[i].weight;
        }
        assert sum == totalWeight;

        min = Math.min(min, centroids[0].mean);
        max = Math.max(max, centroids[numCentroids-1].mean);
        numBuffered = 0;
    }

    public double quantile(double q) {
        if (q < 0 || q > 1) {
            throw new IllegalArgumentException("q should be in [0,1], got " + q);
        }
        flushBuffer();

        if (numCentroids == 0) {
            // no centroids means no data, no way to get a quantile
            return Double.NaN;
        } else if (numCentroids == 1) {
            // with one data point, all quantiles lead to Rome
            return centroids[0].mean;
        }

        // we know that there are at least two centroids now
        int n = numCentroids;

        // if values were stored in a sorted array, index would be the offset we are interested in
        final double index = q * totalWeight;

        // at the boundaries, we return min or max
        if (index < centroids[0].weight / 2) {
            assert centroids[0].weight > 0;
            return min + 2 * index / centroids[0].weight * (centroids[0].mean - min);
        }

        // in between we interpolate between centroids
        double weightSoFar = centroids[0].weight / 2;
        for (int i = 0; i < n - 1; i++) {
            double dw = (centroids[i].weight + centroids[i + 1].weight) / 2;
            if (weightSoFar + dw > index) {
                // centroids i and i+1 bracket our current point
                double z1 = index - weightSoFar;
                double z2 = weightSoFar + dw - index;
                return weightedAverage(centroids[i].mean, z2, centroids[i + 1].mean, z1);
            }
            weightSoFar += dw;
        }
        assert index <= totalWeight;
        assert index >= totalWeight - centroids[n - 1].weight / 2;

        // weightSoFar = totalWeight - weight[n-1]/2 (very nearly)
        // so we interpolate out to max value ever seen
        double z1 = index - totalWeight - centroids[n - 1].weight / 2.0;
        double z2 = centroids[n - 1].weight / 2 - z1;
        return weightedAverage(centroids[n - 1].mean, z1, max, z2);
    }

    private static double weightedAverage(double x1, double w1, double x2, double w2) {
        assert x1 <= x2;
        final double x = (x1 * w1 + x2 * w2) / (w1 + w2);
        return Math.max(x1, Math.min(x, x2));
    }

    private static final class Centroid implements Comparable<Centroid> {
        private double mean;
        private double weight;
        private Centroid(double mean, double weight) {
            this.mean = mean;
            this.weight = weight;
        }

        void add(Centroid that) {
            this.mean = (this.mean * this.weight + that.mean * that.weight) / (this.weight + that.weight);
            this.weight += that.weight;
        }

        @Override
        public int compareTo(Centroid that) {
            return Double.compare(this.mean, that.mean);
        }
    }
}
