package io.foolproof.stats;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

public class TransliterationImpl {
    private static final class Centroid implements Comparable<Centroid> {
        double mean;
        double weight;
        private Centroid(double mean, double weight) {
            this.mean = mean;
            this.weight = weight;
        }

        void add(Centroid that) {
            this.weight += that.weight;
            this.mean = this.mean + (that.mean - this.mean) * that.weight / this.weight;
        }

        @Override
        public int compareTo(Centroid that) {
            return Double.compare(this.mean, that.mean);
        }
    }
    private final double compression;

    private double min = 0;
    private double max = 0;

    // sum_i weight[i]  See also unmergedWeight
    private double totalWeight = 0;

    // number of points that have been added to each merged centroid
    private final ArrayList<Centroid> centroids = new ArrayList<>();

    // sum_i tempWeight[i]
    private double unmergedWeight = 0;

    // this is the index of the next temporary centroid
    // this is a more Java-like convention than lastUsedCell uses
    private final ArrayList<Centroid> temp = new ArrayList<>();

    private final int maxSize;
    private final int bufferSize;


    /**
     * Allocates a buffer merging t-digest.  This is the normally used constructor that
     * allocates default sized internal arrays.  Other versions are available, but should
     * only be used for special cases.
     *
     * @param compression The compression factor
     */
    @SuppressWarnings("WeakerAccess")
    public TransliterationImpl(double compression) {
        this(compression, -1);
    }

    /**
     * If you know the size of the temporary buffer for incoming points, you can use this entry point.
     *
     * @param compression Compression factor for t-digest.  Same as 1/\delta in the paper.
     * @param bufferSize  How many samples to retain before merging.
     */
    @SuppressWarnings("WeakerAccess")
    public TransliterationImpl(double compression, int bufferSize) {
        // we can guarantee that we only need 2 * ceiling(compression).
        this(compression, bufferSize, -1);
    }

    /**
     * Fully specified constructor.  Normally only used for deserializing a buffer t-digest.
     *
     * @param compression Compression factor
     * @param bufferSize  Number of temporary centroids
     * @param maxSize        Size of main buffer
     */
    @SuppressWarnings("WeakerAccess")
    public TransliterationImpl(double compression, int bufferSize, int maxSize) {
        this.compression = compression;
        this.bufferSize = bufferSize > 0 ? bufferSize : (int) (5 * Math.ceil(compression));
        this.maxSize = maxSize > 0 ? maxSize : (int) (2 * Math.ceil(compression)) + 10;

        // points to the first unused centroid
        int lastUsedCell = 0;
    }

    public void add(double x, double w) {
        if (Double.isNaN(x)) {
            throw new IllegalArgumentException("Cannot add NaN to t-digest");
        }
        if (temp.size() >= bufferSize - centroids.size() - 1) {
            mergeNewValues();
        }
        temp.add(new Centroid(x, w));
        unmergedWeight += w;
    }

    private void mergeNewValues() {
        if (unmergedWeight > 0) {
            merge(temp, unmergedWeight);
            temp.clear();
            unmergedWeight = 0;
        }
    }

    private void merge(ArrayList<Centroid> incoming, double unmergedWeight) {
        incoming.addAll(centroids);
        centroids.clear();
        Collections.sort(incoming);

        totalWeight += unmergedWeight;
        double normalizer = compression / (Math.PI * totalWeight);

        assert incoming.size() > 0;
        centroids.add(incoming.get(0));
        double wSoFar = 0;

        for (int i = 1; i < incoming.size(); i++) {
            double proposedWeight = centroids.get(centroids.size()-1).weight + incoming.get(i).weight;
            double z = proposedWeight * normalizer;
            double q0 = wSoFar / totalWeight;
            double q2 = (wSoFar + proposedWeight) / totalWeight;

            if (z * z <= q0 * (1 - q0) && z * z <= q2 * (1 - q2)) {
                // next point will fit, so merge into existing centroid
                centroids.get(centroids.size()-1).add(incoming.get(i));
            } else {
                // didn't fit ... move to next output, copy out first centroid
                wSoFar += centroids.get(centroids.size()-1).weight;
                centroids.add(incoming.get(i));
            }
        }

        // sanity check
        double sum = 0;
        for (Centroid c : centroids) {
            sum += c.weight;
        }
        assert sum == totalWeight;

        if (totalWeight > 0) {
            min = Math.min(min, centroids.get(0).mean);
            max = Math.max(max, centroids.get(centroids.size()-1).mean);
        }
    }

    public double quantile(double q) {
        if (q < 0 || q > 1) {
            throw new IllegalArgumentException("q should be in [0,1], got " + q);
        }
        mergeNewValues();

        if (centroids.isEmpty()) {
            // no centroids means no data, no way to get a quantile
            return Double.NaN;
        } else if (centroids.size() == 1) {
            // with one data point, all quantiles lead to Rome
            return centroids.get(0).mean;
        }

        // we know that there are at least two centroids now
        int n = centroids.size();

        // if values were stored in a sorted array, index would be the offset we are interested in
        final double index = q * totalWeight;

        // at the boundaries, we return min or max
        if (index < centroids.get(0).weight / 2) {
            assert centroids.get(0).weight > 0;
            return min + 2 * index / centroids.get(0).weight * (centroids.get(0).mean - min);
        }

        // in between we interpolate between centroids
        double weightSoFar = centroids.get(0).weight / 2;
        for (int i = 0; i < n - 1; i++) {
            double dw = (centroids.get(i).weight + centroids.get(i + 1).weight) / 2;
            if (weightSoFar + dw > index) {
                // centroids i and i+1 bracket our current point
                double z1 = index - weightSoFar;
                double z2 = weightSoFar + dw - index;
                return weightedAverage(centroids.get(i).mean, z2, centroids.get(i + 1).mean, z1);
            }
            weightSoFar += dw;
        }
        assert index <= totalWeight;
        assert index >= totalWeight - centroids.get(n - 1).weight / 2;

        // weightSoFar = totalWeight - weight[n-1]/2 (very nearly)
        // so we interpolate out to max value ever seen
        double z1 = index - totalWeight - centroids.get(n - 1).weight / 2.0;
        double z2 = centroids.get(n - 1).weight / 2 - z1;
        return weightedAverage(centroids.get(n - 1).mean, z1, max, z2);
    }

    private static double weightedAverage(double x1, double w1, double x2, double w2) {
        if (x1 <= x2) {
            return weightedAverageSorted(x1, w1, x2, w2);
        } else {
            return weightedAverageSorted(x2, w2, x1, w1);
        }
    }

    /**
     * Compute the weighted average between <code>x1</code> with a weight of
     * <code>w1</code> and <code>x2</code> with a weight of <code>w2</code>.
     * This expects <code>x1</code> to be less than or equal to <code>x2</code>
     * and is guaranteed to return a number between <code>x1</code> and
     * <code>x2</code>.
     */
    private static double weightedAverageSorted(double x1, double w1, double x2, double w2) {
        assert x1 <= x2;
        final double x = (x1 * w1 + x2 * w2) / (w1 + w2);
        return Math.max(x1, Math.min(x, x2));
    }
}
