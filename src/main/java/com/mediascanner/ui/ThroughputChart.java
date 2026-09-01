package com.mediascanner.ui;

import com.mediascanner.model.ThroughputSample;
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
    private final XYChart.Series<Number, Number> filesSeries = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> mbSeries = new XYChart.Series<>();
    private final Label placeholder = new Label("Not enough samples yet to plot throughput.");
    private final StackPane body = new StackPane();
    private final VBox charts = new VBox(6);

    public ThroughputChart() {
        filesChart = buildChart("files/sec", "#2f6fd0");
        mbChart = buildChart("MB/sec", "#d0762f");

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

    private LineChart<Number, Number> buildChart(String yLabel, String colour) {
        NumberAxis xAxis = new NumberAxis();
        xAxis.setLabel("Elapsed (seconds)");
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
