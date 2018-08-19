package io.foolproof.stats;

import com.tdunning.math.stats.Sort;

public class TransliterationImpl {
    private final double compression;

    private double min = 0;
    private double max = 0;

    // points to the first unused centroid
    private int lastUsedCell;

    // sum_i weight[i]  See also unmergedWeight
    private double totalWeight = 0;

    // number of points that have been added to each merged centroid
    private final double[] weight;
    // mean of points added to each merged centroid
    private final double[] mean;

    // sum_i tempWeight[i]
    private double unmergedWeight = 0;

    // this is the index of the next temporary centroid
    // this is a more Java-like convention than lastUsedCell uses
    private int tempUsed = 0;
    private final double[] tempWeight;
    private final double[] tempMean;


    // array used for sorting the temp centroids.  This is a field
    // to avoid allocations during operation
    private final int[] order;
    private static boolean usePieceWiseApproximation = true;
    private static boolean useWeightLimit = true;

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
     * @param size        Size of main buffer
     */
    @SuppressWarnings("WeakerAccess")
    public TransliterationImpl(double compression, int bufferSize, int size) {
        if (size == -1) {
            size = (int) (2 * Math.ceil(compression));
            if (useWeightLimit) {
                // the weight limit approach generates smaller centroids than necessary
                // that can result in using a bit more memory than expected
                size += 10;
            }
        }
        if (bufferSize == -1) {
            // having a big buffer is good for speed
            // experiments show bufferSize = 1 gives half the performance of bufferSize=10
            // bufferSize = 2 gives 40% worse performance than 10
            // but bufferSize = 5 only costs about 5-10%
            //
            //   compression factor     time(us)
            //    50          1         0.275799
            //    50          2         0.151368
            //    50          5         0.108856
            //    50         10         0.102530
            //   100          1         0.215121
            //   100          2         0.142743
            //   100          5         0.112278
            //   100         10         0.107753
            //   200          1         0.210972
            //   200          2         0.148613
            //   200          5         0.118220
            //   200         10         0.112970
            //   500          1         0.219469
            //   500          2         0.158364
            //   500          5         0.127552
            //   500         10         0.121505
            bufferSize = (int) (5 * Math.ceil(compression));
        }
        this.compression = compression;

        weight = new double[size];
        mean = new double[size];

        tempWeight = new double[bufferSize];
        tempMean = new double[bufferSize];
        order = new int[bufferSize];

        lastUsedCell = 0;
    }

    public void add(double x, double w) {
        if (Double.isNaN(x)) {
            throw new IllegalArgumentException("Cannot add NaN to t-digest");
        }
        if (tempUsed >= tempWeight.length - lastUsedCell - 1) {
            mergeNewValues();
        }
        int where = tempUsed++;
        tempWeight[where] = w;
        tempMean[where] = x;
        unmergedWeight += w;
    }

    private void mergeNewValues() {
        if (unmergedWeight > 0) {
            merge(tempMean, tempWeight, tempUsed, order, unmergedWeight);
            tempUsed = 0;
            unmergedWeight = 0;

        }
    }

    private void merge(double[] incomingMean, double[] incomingWeight, int incomingCount, int[] incomingOrder, double unmergedWeight) {
        System.arraycopy(mean, 0, incomingMean, incomingCount, lastUsedCell);
        System.arraycopy(weight, 0, incomingWeight, incomingCount, lastUsedCell);
        incomingCount += lastUsedCell;

        if (incomingOrder == null) {
            incomingOrder = new int[incomingCount];
        }
        Sort.sort(incomingOrder, incomingMean, incomingCount);

        totalWeight += unmergedWeight;
        double normalizer = compression / (Math.PI * totalWeight);

        assert incomingCount > 0;
        lastUsedCell = 0;
        mean[lastUsedCell] = incomingMean[incomingOrder[0]];
        weight[lastUsedCell] = incomingWeight[incomingOrder[0]];
        double wSoFar = 0;

        double k1 = 0;

        // weight will contain all zeros
        double wLimit;
        wLimit = totalWeight * integratedQ(k1 + 1);
        for (int i = 1; i < incomingCount; i++) {
            int ix = incomingOrder[i];
            double proposedWeight = weight[lastUsedCell] + incomingWeight[ix];
            double projectedW = wSoFar + proposedWeight;
            boolean addThis;
            if (useWeightLimit) {
                double z = proposedWeight * normalizer;
                double q0 = wSoFar / totalWeight;
                double q2 = (wSoFar + proposedWeight) / totalWeight;
                addThis = z * z <= q0 * (1 - q0) && z * z <= q2 * (1 - q2);
            } else {
                addThis = projectedW <= wLimit;
            }

            if (addThis) {
                // next point will fit
                // so merge into existing centroid
                weight[lastUsedCell] += incomingWeight[ix];
                mean[lastUsedCell] = mean[lastUsedCell] + (incomingMean[ix] - mean[lastUsedCell]) * incomingWeight[ix] / weight[lastUsedCell];
                incomingWeight[ix] = 0;
            } else {
                // didn't fit ... move to next output, copy out first centroid
                wSoFar += weight[lastUsedCell];
                if (!useWeightLimit) {
                    k1 = integratedLocation(wSoFar / totalWeight);
                    wLimit = totalWeight * integratedQ(k1 + 1);
                }

                lastUsedCell++;
                mean[lastUsedCell] = incomingMean[ix];
                weight[lastUsedCell] = incomingWeight[ix];
                incomingWeight[ix] = 0;
            }
        }
        // points to next empty cell
        lastUsedCell++;

        // sanity check
        double sum = 0;
        for (int i = 0; i < lastUsedCell; i++) {
            sum += weight[i];
        }
        assert sum == totalWeight;

        if (totalWeight > 0) {
            min = Math.min(min, mean[0]);
            max = Math.max(max, mean[lastUsedCell - 1]);
        }
    }

    /**
     * Converts a quantile into a centroid scale value.  The centroid scale is nominally
     * the number k of the centroid that a quantile point q should belong to.  Due to
     * round-offs, however, we can't align things perfectly without splitting points
     * and centroids.  We don't want to do that, so we have to allow for offsets.
     * In the end, the criterion is that any quantile range that spans a centroid
     * scale range more than one should be split across more than one centroid if
     * possible.  This won't be possible if the quantile range refers to a single point
     * or an already existing centroid.
     * <p/>
     * This mapping is steep near q=0 or q=1 so each centroid there will correspond to
     * less q range.  Near q=0.5, the mapping is flatter so that centroids there will
     * represent a larger chunk of quantiles.
     *
     * @param q The quantile scale value to be mapped.
     * @return The centroid scale value corresponding to q.
     */
    private double integratedLocation(double q) {
        return compression * (asinApproximation(2 * q - 1) + Math.PI / 2) / Math.PI;
    }

    private double integratedQ(double k) {
        return (Math.sin(Math.min(k, compression) * Math.PI / compression - Math.PI / 2) + 1) / 2;
    }

    private static double asinApproximation(double x) {
        if (usePieceWiseApproximation) {
            if (x < 0) {
                return -asinApproximation(-x);
            } else {
                // this approximation works by breaking that range from 0 to 1 into 5 regions
                // for all but the region nearest 1, rational polynomial models get us a very
                // good approximation of asin and by interpolating as we move from region to
                // region, we can guarantee continuity and we happen to get monotonicity as well.
                // for the values near 1, we just use Math.asin as our region "approximation".

                // cutoffs for models. Note that the ranges overlap. In the overlap we do
                // linear interpolation to guarantee the overall result is "nice"
                double c0High = 0.1;
                double c1High = 0.55;
                double c2Low = 0.5;
                double c2High = 0.8;
                double c3Low = 0.75;
                double c3High = 0.9;
                double c4Low = 0.87;
                if (x > c3High) {
                    return Math.asin(x);
                } else {
                    // the models
                    double[] m0 = {0.2955302411, 1.2221903614, 0.1488583743, 0.2422015816, -0.3688700895, 0.0733398445};
                    double[] m1 = {-0.0430991920, 0.9594035750, -0.0362312299, 0.1204623351, 0.0457029620, -0.0026025285};
                    double[] m2 = {-0.034873933724, 1.054796752703, -0.194127063385, 0.283963735636, 0.023800124916, -0.000872727381};
                    double[] m3 = {-0.37588391875, 2.61991859025, -2.48835406886, 1.48605387425, 0.00857627492, -0.00015802871};

                    // the parameters for all of the models
                    double[] vars = {1, x, x * x, x * x * x, 1 / (1 - x), 1 / (1 - x) / (1 - x)};

                    // raw grist for interpolation coefficients
                    double x0 = bound((c0High - x) / c0High);
                    double x1 = bound((c1High - x) / (c1High - c2Low));
                    double x2 = bound((c2High - x) / (c2High - c3Low));
                    double x3 = bound((c3High - x) / (c3High - c4Low));

                    // interpolation coefficients
                    //noinspection UnnecessaryLocalVariable
                    double mix0 = x0;
                    double mix1 = (1 - x0) * x1;
                    double mix2 = (1 - x1) * x2;
                    double mix3 = (1 - x2) * x3;
                    double mix4 = 1 - x3;

                    // now mix all the results together, avoiding extra evaluations
                    double r = 0;
                    if (mix0 > 0) {
                        r += mix0 * eval(m0, vars);
                    }
                    if (mix1 > 0) {
                        r += mix1 * eval(m1, vars);
                    }
                    if (mix2 > 0) {
                        r += mix2 * eval(m2, vars);
                    }
                    if (mix3 > 0) {
                        r += mix3 * eval(m3, vars);
                    }
                    if (mix4 > 0) {
                        // model 4 is just the real deal
                        r += mix4 * Math.asin(x);
                    }
                    return r;
                }
            }
        } else {
            return Math.asin(x);
        }
    }

    private static double eval(double[] model, double[] vars) {
        double r = 0;
        for (int i = 0; i < model.length; i++) {
            r += model[i] * vars[i];
        }
        return r;
    }

    private static double bound(double v) {
        if (v <= 0) {
            return 0;
        } else if (v >= 1) {
            return 1;
        } else {
            return v;
        }
    }


    public double quantile(double q) {
        if (q < 0 || q > 1) {
            throw new IllegalArgumentException("q should be in [0,1], got " + q);
        }
        mergeNewValues();

        if (lastUsedCell == 0 && weight[lastUsedCell] == 0) {
            // no centroids means no data, no way to get a quantile
            return Double.NaN;
        } else if (lastUsedCell == 0) {
            // with one data point, all quantiles lead to Rome
            return mean[0];
        }

        // we know that there are at least two centroids now
        int n = lastUsedCell;

        // if values were stored in a sorted array, index would be the offset we are interested in
        final double index = q * totalWeight;

        // at the boundaries, we return min or max
        if (index < weight[0] / 2) {
            assert weight[0] > 0;
            return min + 2 * index / weight[0] * (mean[0] - min);
        }

        // in between we interpolate between centroids
        double weightSoFar = weight[0] / 2;
        for (int i = 0; i < n - 1; i++) {
            double dw = (weight[i] + weight[i + 1]) / 2;
            if (weightSoFar + dw > index) {
                // centroids i and i+1 bracket our current point
                double z1 = index - weightSoFar;
                double z2 = weightSoFar + dw - index;
                return weightedAverage(mean[i], z2, mean[i + 1], z1);
            }
            weightSoFar += dw;
        }
        assert index <= totalWeight;
        assert index >= totalWeight - weight[n - 1] / 2;

        // weightSoFar = totalWeight - weight[n-1]/2 (very nearly)
        // so we interpolate out to max value ever seen
        double z1 = index - totalWeight - weight[n - 1] / 2.0;
        double z2 = weight[n - 1] / 2 - z1;
        return weightedAverage(mean[n - 1], z1, max, z2);
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
