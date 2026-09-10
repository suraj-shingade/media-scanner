package com.mediascanner.monitor;

import com.mediascanner.model.ThroughputSample;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Reduces a long run of 1 Hz throughput samples to something a chart can draw, without throwing away
 * the parts a reader actually needs.
 *
 * <p><b>Why not average.</b> The obvious reduction is a mean per bucket, and it is what the stored-job
 * query does. On a short job that is fine. On a four-hour job each bucket spans a couple of minutes,
 * and a 40-second stall averaged across 120 seconds of healthy throughput becomes a shallow dip that
 * reads as noise. The stall is the single most interesting event in the run and averaging is precisely
 * the operation that erases it.
 *
 * <p>So each bucket contributes its <em>minimum and maximum</em>, in chronological order. The line
 * becomes an envelope: its lower edge is the worst throughput in that span and its upper edge the best.
 * A stall touches zero and stays visible at any zoom level, and a burst is not flattened into the
 * average around it. The cost is up to twice as many plotted points for a given bucket count, which is
 * cheap next to being unable to see that the job stopped for a minute.
 *
 * <p>Series are reduced independently — files/sec and MB/sec do not peak at the same instants, and
 * picking the extremes of one would misrepresent the other.
 */
public final class ThroughputDownsampler {

    private ThroughputDownsampler() {}

    /** One plotted point: a value at an elapsed offset into the job. */
    public record Point(long elapsedSeconds, double value) {}

    /**
     * @param samples       raw samples in chronological order; may be empty
     * @param value         which series to reduce, e.g. {@code ThroughputSample::getFilesPerSec}
     * @param targetBuckets how many spans to divide the run into; the result holds at most
     *                      {@code 2 * targetBuckets} points
     * @return the reduced series, chronological, always including the first and last sample
     */
    public static List<Point> downsample(List<ThroughputSample> samples,
                                         ToDoubleFunction<ThroughputSample> value,
                                         int targetBuckets) {
        List<Point> out = new ArrayList<>();
        if (samples == null || samples.isEmpty()) return out;
        if (targetBuckets < 1) targetBuckets = 1;

        // Short enough to draw honestly at full resolution — reducing would only lose detail.
        if (samples.size() <= targetBuckets * 2) {
            for (ThroughputSample s : samples) {
                out.add(new Point(s.getElapsedSeconds(), value.applyAsDouble(s)));
            }
            return out;
        }

        int n = samples.size();
        for (int bucket = 0; bucket < targetBuckets; bucket++) {
            int start = (int) ((long) bucket * n / targetBuckets);
            int end = (int) ((long) (bucket + 1) * n / targetBuckets);
            if (start >= end) continue;

            int minIndex = start;
            int maxIndex = start;
            double min = value.applyAsDouble(samples.get(start));
            double max = min;

            for (int i = start + 1; i < end; i++) {
                double v = value.applyAsDouble(samples.get(i));
                if (v < min) { min = v; minIndex = i; }
                if (v > max) { max = v; maxIndex = i; }
            }

            // Chronological order, so the line never doubles back on itself.
            int firstIndex = Math.min(minIndex, maxIndex);
            int secondIndex = Math.max(minIndex, maxIndex);
            addPoint(out, samples.get(firstIndex), value);
            if (secondIndex != firstIndex) {
                addPoint(out, samples.get(secondIndex), value);
            }
        }

        // The endpoints anchor the x-range: without them the chart can appear to start late or stop
        // early purely as an artefact of where the bucket extremes happened to fall.
        ThroughputSample first = samples.get(0);
        ThroughputSample last = samples.get(n - 1);
        if (out.get(0).elapsedSeconds() != first.getElapsedSeconds()) {
            out.add(0, new Point(first.getElapsedSeconds(), value.applyAsDouble(first)));
        }
        if (out.get(out.size() - 1).elapsedSeconds() != last.getElapsedSeconds()) {
            out.add(new Point(last.getElapsedSeconds(), value.applyAsDouble(last)));
        }
        return out;
    }

    private static void addPoint(List<Point> out, ThroughputSample s,
                                 ToDoubleFunction<ThroughputSample> value) {
        out.add(new Point(s.getElapsedSeconds(), value.applyAsDouble(s)));
    }
}
