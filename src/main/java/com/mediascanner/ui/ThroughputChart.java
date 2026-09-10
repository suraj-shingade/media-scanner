package com.mediascanner.ui;

import com.mediascanner.model.ThroughputSample;
import com.mediascanner.monitor.ThroughputDownsampler;
import javafx.geometry.Insets;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Reusable throughput chart (FR-031), used both live on the dashboard and for a stored job on the
 * summary screen.
 *
 * <p>Files/sec and MB/sec are plotted on <em>separate stacked charts</em> rather than one shared
 * axis. They routinely differ by an order of magnitude — a job doing 900 files/sec at 40 MB/sec
 * flattens the MB/sec line onto the x-axis, which is how the first version of this rendered and it
 * made half of FR-028 unreadable. JavaFX {@code LineChart} supports only one y-axis, so two charts
 * sharing an x-range is the way to keep both legible.
 *
 * <p>Built on JavaFX's own charting deliberately: the constitution locks the UI stack, and a
 * charting dependency would need an amendment for something the platform already provides.
 */
public class ThroughputChart extends VBox {

    /** Below this, a chart is noise rather than information (US5 AS-5). */
    private static final int MIN_SAMPLES = 5;

    private final LineChart<Number, Number> filesChart;
    private final LineChart<Number, Number> mbChart;
    private final NumberAxis filesXAxis;
    private final NumberAxis mbXAxis;
    private final XYChart.Series<Number, Number> filesSeries = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> mbSeries = new XYChart.Series<>();
    private final Label placeholder = new Label("Not enough samples yet to plot throughput.");
    private final StackPane body = new StackPane();
    private final VBox charts = new VBox(6);

    public ThroughputChart() {
        filesXAxis = newTimeAxis();
        mbXAxis = newTimeAxis();
        filesChart = buildChart(filesXAxis, "files/sec", "#2f6fd0");
        mbChart = buildChart(mbXAxis, "MB/sec", "#d0762f");

        VBox.setVgrow(filesChart, Priority.ALWAYS);
        VBox.setVgrow(mbChart, Priority.ALWAYS);
        charts.getChildren().addAll(filesChart, mbChart);

        filesSeries.setName("files/sec");
        mbSeries.setName("MB/sec");
        filesChart.getData().add(filesSeries);
        mbChart.getData().add(mbSeries);

        placeholder.getStyleClass().add("chart-placeholder");
        body.getChildren().addAll(charts, placeholder);
        VBox.setVgrow(body, Priority.ALWAYS);
        getChildren().add(body);
        setPadding(new Insets(4));
        showPlaceholder(true);
    }

    private NumberAxis newTimeAxis() {
        NumberAxis axis = new NumberAxis();
        axis.setLabel("Elapsed (seconds)");
        return axis;
    }

    private LineChart<Number, Number> buildChart(NumberAxis xAxis, String yLabel, String colour) {
        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel(yLabel);

        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setAnimated(false);       // animation on a 1 Hz feed just makes it jitter
        chart.setCreateSymbols(false);  // thousands of symbols is what makes LineChart crawl
        chart.setLegendVisible(false);  // the y-axis label already names the series
        chart.setMinHeight(130);
        chart.setStyle("CHART_COLOR_1: " + colour + ";");
        return chart;
    }

    /** Replaces the plotted series. Safe to call repeatedly; must run on the FX thread. */
    public void setSamples(List<ThroughputSample> samples) {
        filesSeries.getData().clear();
        mbSeries.getData().clear();

        if (samples == null || samples.size() < MIN_SAMPLES) {
            showPlaceholder(true);
            return;
        }
        for (ThroughputSample s : samples) {
            filesSeries.getData().add(
                new XYChart.Data<>(s.getElapsedSeconds(), s.getFilesPerSec()));
            mbSeries.getData().add(
                new XYChart.Data<>(s.getElapsedSeconds(), s.getMbPerSec()));
        }
        showPlaceholder(false);
    }

    /**
     * Plots an entire run, however long, by reducing each series to an envelope (FR-077).
     *
     * <p>Replaces the whole series rather than appending, so it is the expensive path — the caller
     * decides how often to call it. What it buys is that no part of the run is ever discarded from
     * view: a four-hour job shows all four hours, and a stall an hour ago is still on screen.
     *
     * @param targetBuckets spans to divide the run into; plotted points are at most twice this
     */
    public void setWholeRun(List<ThroughputSample> samples, int targetBuckets) {
        filesSeries.getData().clear();
        mbSeries.getData().clear();
        // Whole-run wants the axis to fit the data again, undoing any pinned window.
        releaseAxes();

        if (samples == null || samples.size() < MIN_SAMPLES) {
            showPlaceholder(true);
            return;
        }

        // Reduced independently: files/sec and MB/sec do not peak at the same instants.
        plot(filesSeries, ThroughputDownsampler.downsample(
            samples, ThroughputSample::getFilesPerSec, targetBuckets));
        plot(mbSeries, ThroughputDownsampler.downsample(
            samples, ThroughputSample::getMbPerSec, targetBuckets));
        showPlaceholder(false);
    }

    private void plot(XYChart.Series<Number, Number> series,
                      List<ThroughputDownsampler.Point> points) {
        List<XYChart.Data<Number, Number>> data = new java.util.ArrayList<>(points.size());
        for (ThroughputDownsampler.Point p : points) {
            data.add(new XYChart.Data<>(p.elapsedSeconds(), p.value()));
        }
        // One bulk mutation rather than N — each add fires a layout pass on the chart.
        series.getData().setAll(data);
    }

    /**
     * Shows the most recent {@code windowSeconds} as a fixed-width window that scrolls, the way Task
     * Manager does (FR-086).
     *
     * <p>The important part is that the x-axis stops auto-ranging. Left to itself the axis fits
     * whatever data it holds, so as a job runs the visible span keeps stretching and the trace
     * compresses toward the left — which is what made the whole-run view feel heavy and unreadable.
     * Pinning the bounds to a window that slides keeps the horizontal scale constant: a spike is the
     * same width an hour in as it was in the first minute, and the eye can compare them.
     *
     * <p>The window is drawn at full resolution. At 1 Hz even ten minutes is 600 points, which
     * LineChart handles without help — downsampling is only needed for the whole-run view.
     */
    public void showWindow(List<ThroughputSample> samples, long windowSeconds) {
        if (samples == null || samples.isEmpty()) {
            clear();
            return;
        }

        long latest = samples.get(samples.size() - 1).getElapsedSeconds();
        long lower = Math.max(0, latest - windowSeconds);
        // Before the first full window has elapsed, hold the axis at its full width rather than
        // growing it — otherwise the trace stretches as it fills, which reads as speeding up.
        long upper = Math.max(windowSeconds, latest);

        List<XYChart.Data<Number, Number>> files = new java.util.ArrayList<>();
        List<XYChart.Data<Number, Number>> mb = new java.util.ArrayList<>();
        for (ThroughputSample s : samples) {
            if (s.getElapsedSeconds() < lower) continue;
            files.add(new XYChart.Data<>(s.getElapsedSeconds(), s.getFilesPerSec()));
            mb.add(new XYChart.Data<>(s.getElapsedSeconds(), s.getMbPerSec()));
        }

        pinAxis(filesXAxis, lower, upper);
        pinAxis(mbXAxis, lower, upper);
        filesSeries.getData().setAll(files);
        mbSeries.getData().setAll(mb);
        showPlaceholder(files.size() < MIN_SAMPLES);
    }

    private void pinAxis(NumberAxis axis, long lower, long upper) {
        axis.setAutoRanging(false);
        axis.setLowerBound(lower);
        axis.setUpperBound(upper);
        // Roughly six labels, rounded to a whole second so they do not flicker between frames.
        axis.setTickUnit(Math.max(1, (upper - lower) / 6.0));
    }

    private void releaseAxes() {
        filesXAxis.setAutoRanging(true);
        mbXAxis.setAutoRanging(true);
    }

    /** Appends one live reading, trimming the oldest so the live chart stays bounded. */
    public void appendSample(long elapsedSeconds, double filesPerSec, double mbPerSec,
                             int maxPoints) {
        filesSeries.getData().add(new XYChart.Data<>(elapsedSeconds, filesPerSec));
        mbSeries.getData().add(new XYChart.Data<>(elapsedSeconds, mbPerSec));
        while (filesSeries.getData().size() > maxPoints) {
            filesSeries.getData().remove(0);
            mbSeries.getData().remove(0);
        }
        showPlaceholder(filesSeries.getData().size() < MIN_SAMPLES);
    }

    public void clear() {
        filesSeries.getData().clear();
        mbSeries.getData().clear();
        showPlaceholder(true);
    }

    private void showPlaceholder(boolean show) {
        placeholder.setVisible(show);
        charts.setVisible(!show);
    }
}
