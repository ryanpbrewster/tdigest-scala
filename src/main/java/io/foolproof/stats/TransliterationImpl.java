package io.foolproof.stats;

import java.util.ArrayList;
import java.util.Collections;

public class TransliterationImpl {
    private final double compression;
    private final int maxSize;

    private double min = 0;
    private double max = 0;

    private double totalWeight = 0;

    private final ArrayList<Centroid> centroids = new ArrayList<>();
    private final ArrayList<Centroid> temp = new ArrayList<>();

    public TransliterationImpl(double compression) {
        this.compression = compression;
        this.maxSize = (int) (5 * Math.ceil(compression));
    }

    public void add(double x, double w) {
        if (Double.isNaN(x)) {
            throw new IllegalArgumentException("Cannot add NaN to t-digest");
        }
        if (temp.size() >= maxSize - centroids.size() - 1) {
            flushBuffer();
        }
        temp.add(new Centroid(x, w));
    }

    private void flushBuffer() {
        if (temp.isEmpty()) {
            return;
        }
        for (Centroid c : temp) {
            totalWeight += c.weight;
        }
        temp.addAll(centroids);
        centroids.clear();
        Collections.sort(temp);

        double normalizer = compression / (Math.PI * totalWeight);

        double wSoFar = 0;
        Centroid acc = temp.get(0);
        for (int i = 1; i < temp.size(); i++) {
            double proposedWeight = acc.weight + temp.get(i).weight;
            double z = proposedWeight * normalizer;
            double q0 = wSoFar / totalWeight;
            double q2 = (wSoFar + proposedWeight) / totalWeight;

            if (z * z <= q0 * (1 - q0) && z * z <= q2 * (1 - q2)) {
                // next point will fit, so merge into existing centroid
                acc.add(temp.get(i));
            } else {
                // didn't fit ... move to next output, copy out first centroid
                wSoFar += acc.weight;
                centroids.add(acc);
                acc = temp.get(i);
            }
        }
        centroids.add(acc);

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
        temp.clear();
    }

    public double quantile(double q) {
        if (q < 0 || q > 1) {
            throw new IllegalArgumentException("q should be in [0,1], got " + q);
        }
        flushBuffer();

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
