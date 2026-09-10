package com.mediascanner.monitor;

import com.mediascanner.model.ThroughputSample;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The downsampler exists so a long job can be seen whole. Its job is to lose resolution without
 * losing events, so most of these tests are about what must survive the reduction.
 */
class ThroughputDownsamplerTest {

    private List<ThroughputSample> run(double... filesPerSec) {
        List<ThroughputSample> samples = new ArrayList<>();
        for (int i = 0; i < filesPerSec.length; i++) {
            samples.add(new ThroughputSample(
                "JOB-TEST", Instant.EPOCH.plusSeconds(i), i, filesPerSec[i], filesPerSec[i] / 10,
                50.0, 1.0));
        }
        return samples;
    }

    private List<ThroughputSample> flatRun(int seconds, double value) {
        double[] values = new double[seconds];
        java.util.Arrays.fill(values, value);
        return run(values);
    }

    @Test
    void testShortRunsAreNotReducedAtAll() {
        List<ThroughputSample> samples = flatRun(20, 100.0);

        List<ThroughputDownsampler.Point> points = ThroughputDownsampler.downsample(
            samples, ThroughputSample::getFilesPerSec, 50);

        assertThat(points).as("reducing a short run would only lose detail").hasSize(20);
    }

    /**
     * The reason this class exists. A stall inside a bucket must remain visible; an average would
     * dilute it into the healthy throughput on either side.
     */
    @Test
    void testAStallSurvivesReduction() {
        double[] values = new double[3600];          // one hour at 1 Hz
        java.util.Arrays.fill(values, 500.0);
        for (int i = 1800; i < 1840; i++) {          // 40 seconds of nothing
            values[i] = 0.0;
        }

        List<ThroughputDownsampler.Point> points = ThroughputDownsampler.downsample(
            run(values), ThroughputSample::getFilesPerSec, 100);

        assertThat(points).as("the stall is still on the chart")
            .anyMatch(p -> p.value() == 0.0);
        assertThat(points.stream().mapToDouble(ThroughputDownsampler.Point::value).max().orElse(-1))
            .as("the healthy rate either side is also preserved").isEqualTo(500.0);
    }

    @Test
    void testABurstSurvivesReduction() {
        double[] values = new double[2000];
        java.util.Arrays.fill(values, 100.0);
        values[977] = 4200.0;

        List<ThroughputDownsampler.Point> points = ThroughputDownsampler.downsample(
            run(values), ThroughputSample::getFilesPerSec, 100);

        assertThat(points).anyMatch(p -> p.value() == 4200.0);
    }

    @Test
    void testResultIsBoundedByTargetBuckets() {
        List<ThroughputSample> samples = flatRun(72_000, 300.0);   // a 20-hour job

        List<ThroughputDownsampler.Point> points = ThroughputDownsampler.downsample(
            samples, ThroughputSample::getFilesPerSec, 400);

        // Two per bucket (min and max) plus at most two endpoint anchors.
        assertThat(points.size()).isLessThanOrEqualTo(400 * 2 + 2);
        assertThat(points).isNotEmpty();
    }

    @Test
    void testPointsStayInChronologicalOrder() {
        double[] values = new double[5000];
        for (int i = 0; i < values.length; i++) {
            values[i] = (i % 7) * 130.0;            // forces min and max to alternate within buckets
        }

        List<ThroughputDownsampler.Point> points = ThroughputDownsampler.downsample(
            run(values), ThroughputSample::getFilesPerSec, 200);

        for (int i = 1; i < points.size(); i++) {
            assertThat(points.get(i).elapsedSeconds())
                .as("a line that doubles back on itself is unreadable")
                .isGreaterThanOrEqualTo(points.get(i - 1).elapsedSeconds());
        }
    }

    @Test
    void testFirstAndLastSampleAreAlwaysPlotted() {
        double[] values = new double[10_000];
        java.util.Arrays.fill(values, 250.0);
        values[0] = 251.0;
        values[9_999] = 249.0;

        List<ThroughputDownsampler.Point> points = ThroughputDownsampler.downsample(
            run(values), ThroughputSample::getFilesPerSec, 100);

        assertThat(points.get(0).elapsedSeconds()).isZero();
        assertThat(points.get(points.size() - 1).elapsedSeconds()).isEqualTo(9_999);
    }

    @Test
    void testSeriesAreReducedIndependently() {
        // MB/sec peaks where files/sec troughs — a shared reduction would misrepresent one of them.
        List<ThroughputSample> samples = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            double fps = (i == 500) ? 0.0 : 400.0;
            double mbps = (i == 500) ? 900.0 : 20.0;
            samples.add(new ThroughputSample("JOB-TEST", Instant.EPOCH.plusSeconds(i), i,
                fps, mbps, 50.0, 1.0));
        }

        List<ThroughputDownsampler.Point> files = ThroughputDownsampler.downsample(
            samples, ThroughputSample::getFilesPerSec, 50);
        List<ThroughputDownsampler.Point> mb = ThroughputDownsampler.downsample(
            samples, ThroughputSample::getMbPerSec, 50);

        assertThat(files).anyMatch(p -> p.value() == 0.0);
        assertThat(mb).anyMatch(p -> p.value() == 900.0);
    }

    @Test
    void testEmptyAndNullInputAreSafe() {
        assertThat(ThroughputDownsampler.downsample(
            List.of(), ThroughputSample::getFilesPerSec, 100)).isEmpty();
        assertThat(ThroughputDownsampler.downsample(
            null, ThroughputSample::getFilesPerSec, 100)).isEmpty();
    }

    @Test
    void testASingleSampleIsPlotted() {
        List<ThroughputDownsampler.Point> points = ThroughputDownsampler.downsample(
            flatRun(1, 42.0), ThroughputSample::getFilesPerSec, 100);

        assertThat(points).hasSize(1);
        assertThat(points.get(0).value()).isEqualTo(42.0);
    }
}
