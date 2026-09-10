package com.mediascanner.ui;

import com.mediascanner.app.MediaScannerApp;
import com.mediascanner.checkpoint.JobStateExporter;
import com.mediascanner.config.AppConfig;
import com.mediascanner.db.Database;
import com.mediascanner.engine.AppStateManager;
import com.mediascanner.engine.ScanEngine;
import com.mediascanner.model.CheckpointState;
import com.mediascanner.model.Job;
import com.mediascanner.model.JobStatistics;
import com.mediascanner.model.ThroughputSample;
import com.mediascanner.monitor.ProgressTracker;
import com.mediascanner.monitor.ResourceMonitor;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.Executors;

public class DashboardController implements Initializable {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    @FXML private Label totalFoundLabel;
    @FXML private Label processedLabel;
    @FXML private Label remainingLabel;
    @FXML private Label copiedLabel;
    @FXML private Label movedLabel;
    @FXML private Label skippedLabel;
    @FXML private Label failedLabel;
    @FXML private Label duplicatesLabel;
    @FXML private ProgressBar progressBar;
    @FXML private Label totalBytesLabel;
    @FXML private Label bytesCopiedLabel;
    @FXML private Label bytesMovedLabel;
    @FXML private Label bytesSkippedLabel;
    @FXML private Label dupSavingsLabel;
    @FXML private Label fps5sLabel;
    @FXML private Label fps30sLabel;
    @FXML private Label fpsJobLabel;
    @FXML private Label mbsLabel;
    @FXML private Label etaLabel;
    @FXML private Label cpuLabel;
    @FXML private Label memoryLabel;
    @FXML private Label threadsLabel;
    @FXML private Button pauseButton;
    @FXML private Button resumeButton;
    @FXML private Label statusLabel;
    @FXML private javafx.scene.layout.VBox chartContainer;

    private ScanEngine scanEngine;
    private ProgressTracker progressTracker;
    private ResourceMonitor resourceMonitor;
    private Job job;
    private Database database;
    private AppConfig config;
    private Timeline refreshTimeline;
    private ThroughputChart chart;

    /** Points kept when showing only the recent tail. 10 minutes at 1 Hz. */
    private static final int RECENT_CHART_POINTS = 600;

    /** Spans the whole run is divided into. At most twice this many points are plotted. */
    private static final int WHOLE_RUN_BUCKETS = 400;

    /**
     * Rebuilding the whole-run series replaces every point, so it is not something to do at 1 Hz on
     * the FX thread. Sampling stays at 1 Hz; only the redraw is throttled.
     */
    private static final int WHOLE_RUN_REDRAW_EVERY_SECONDS = 5;

    /**
     * Every sample taken this job, never trimmed (FR-078).
     *
     * <p>The previous implementation kept only the last 600 points, so on any job longer than ten
     * minutes the earlier history was gone from the screen for good — on a multi-hour run you could
     * see the last ten minutes and nothing else. At 1 Hz a 20-hour job is 72 000 of these, a few MB,
     * which is nothing next to the heap the scan itself uses.
     */
    private final List<ThroughputSample> liveSamples = new ArrayList<>();

    private ToggleButton wholeRunToggle;
    private Label sampleCountLabel;

    /**
     * Wall-clock start of charting, so elapsed seconds are real.
     *
     * <p>This used to be a counter incremented once per UI refresh. Under load the FX thread drops
     * and delays ticks, so the live chart's x-axis drifted away from the engine's own elapsed clock —
     * the same job told two different stories about when something happened, and only the stored one
     * was true.
     */
    private long chartStartMillis;

    @Override
    public void initialize(URL location, ResourceBundle resources) {}

    public void init(Job job, Database database, AppConfig config) {
        this.job = job;
        this.database = database;
        this.config = config;
        this.progressTracker = new ProgressTracker();
        this.resourceMonitor = new ResourceMonitor();
        this.scanEngine = new ScanEngine(config, database, progressTracker);

        // Register engine with menu bar controller so menu actions work
        MenuBarController menuBar = MediaScannerApp.getMenuBarController();
        if (menuBar != null) {
            menuBar.setScanEngine(scanEngine);
        }

        // Track target path for Tools > View Failure Report
        if (job.getTargetPath() != null) {
            AppStateManager.getInstance().setLastJobTargetPath(job.getTargetPath());
        }

        resourceMonitor.start();
        chartStartMillis = System.currentTimeMillis();
        if (chartContainer != null) {
            chart = new ThroughputChart();
            chartContainer.getChildren().add(buildChartControls());
            chartContainer.getChildren().add(chart);
            javafx.scene.layout.VBox.setVgrow(chart, javafx.scene.layout.Priority.ALWAYS);
        }
        startRefreshTimeline();
        startScanAsync();
    }

    public ScanEngine getScanEngine() {
        return scanEngine;
    }

    /**
     * Stops this dashboard's background work when it is discarded.
     *
     * <p>Without this the refresh {@link Timeline} outlived the screen: navigating away left it
     * firing once a second forever, against a chart no longer in the scene graph, for the rest of the
     * application's life. Every job started added another one.
     */
    public void shutdown() {
        if (refreshTimeline != null) {
            refreshTimeline.stop();
            refreshTimeline = null;
        }
        if (resourceMonitor != null) {
            resourceMonitor.stop();
        }
        liveSamples.clear();
    }

    private void startRefreshTimeline() {
        refreshTimeline = new Timeline(
            new KeyFrame(Duration.seconds(1), e -> Platform.runLater(this::refreshUI)));
        refreshTimeline.setCycleCount(Timeline.INDEFINITE);
        refreshTimeline.play();
    }

    private void refreshUI() {
        ProgressTracker.Snapshot snap = progressTracker.snapshot();
        long total = snap.filesTotal;

        totalFoundLabel.setText(String.valueOf(total));
        processedLabel.setText(String.valueOf(snap.filesProcessed));
        remainingLabel.setText(String.valueOf(Math.max(0, total - snap.filesProcessed)));
        skippedLabel.setText(String.valueOf(snap.filesSkipped));
        failedLabel.setText(String.valueOf(snap.filesFailed));
        duplicatesLabel.setText(String.valueOf(snap.filesDuplicate));
        totalBytesLabel.setText(DataUnitFormatter.format(snap.bytesProcessed));

        if (total > 0) {
            progressBar.setProgress((double) snap.filesProcessed / total);
        }

        fps5sLabel.setText(String.format("%.1f", snap.avgFilesPerSec5s));
        fps30sLabel.setText(String.format("%.1f", snap.avgFilesPerSec30s));
        fpsJobLabel.setText(String.format("%.1f", snap.avgFilesPerSecJob));
        mbsLabel.setText(DataUnitFormatter.formatRate(snap.avgMbPerSec5s));

        if (snap.etaSeconds > 0) {
            long etaSec = (long) snap.etaSeconds;
            etaLabel.setText(String.format("%02d:%02d:%02d",
                etaSec / 3600, (etaSec % 3600) / 60, etaSec % 60));
        }

        cpuLabel.setText(String.format("%.1f%%", resourceMonitor.getCpuPercent()));
        memoryLabel.setText(String.format("%.2f GB", resourceMonitor.getMemoryGb()));
        threadsLabel.setText(String.valueOf(resourceMonitor.getActiveThreads()));

        recordAndRenderSample(snap);

        progressTracker.tick();
    }

    // ----------------------------------------------------------------- charting

    /** The Whole run / Last 10 minutes switch, plus how much history is being held. */
    private javafx.scene.Node buildChartControls() {
        wholeRunToggle = new ToggleButton("Whole run");
        wholeRunToggle.setSelected(true);
        wholeRunToggle.setTooltip(new Tooltip(
            "Whole run shows every sample since the job started, reduced to an envelope so stalls "
          + "and bursts stay visible. Switch off to follow only the last 10 minutes."));
        // Switching view must repaint now rather than at the next tick, or the button feels dead.
        wholeRunToggle.setOnAction(e -> renderChart(true));

        sampleCountLabel = new Label("0 samples");
        sampleCountLabel.getStyleClass().add("subtle");

        HBox controls = new HBox(10, wholeRunToggle, sampleCountLabel);
        controls.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return controls;
    }

    private void recordAndRenderSample(ProgressTracker.Snapshot snap) {
        if (chart == null) return;

        long elapsed = (System.currentTimeMillis() - chartStartMillis) / 1000;
        liveSamples.add(new ThroughputSample(
            job.getJobId(), Instant.now(), elapsed,
            snap.avgFilesPerSec5s, snap.avgMbPerSec5s,
            resourceMonitor.getCpuPercent(), resourceMonitor.getMemoryGb()));

        if (sampleCountLabel != null) {
            sampleCountLabel.setText(liveSamples.size() + " samples · "
                + formatElapsed(elapsed) + " elapsed");
        }
        renderChart(false);
    }

    /**
     * @param force repaint even if the whole-run throttle would otherwise skip this tick
     */
    private void renderChart(boolean force) {
        if (chart == null || liveSamples.isEmpty()) return;

        boolean wholeRun = wholeRunToggle == null || wholeRunToggle.isSelected();
        if (wholeRun) {
            long elapsed = liveSamples.get(liveSamples.size() - 1).getElapsedSeconds();
            if (force || elapsed % WHOLE_RUN_REDRAW_EVERY_SECONDS == 0) {
                chart.setWholeRun(liveSamples, WHOLE_RUN_BUCKETS);
            }
            return;
        }

        // Tail view: cheaper to append than to rebuild, so keep the incremental path.
        ThroughputSample latest = liveSamples.get(liveSamples.size() - 1);
        if (force) {
            int from = Math.max(0, liveSamples.size() - RECENT_CHART_POINTS);
            chart.setWholeRun(liveSamples.subList(from, liveSamples.size()), RECENT_CHART_POINTS);
        } else {
            chart.appendSample(latest.getElapsedSeconds(), latest.getFilesPerSec(),
                latest.getMbPerSec(), RECENT_CHART_POINTS);
        }
    }

    private static String formatElapsed(long seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return String.format("%dm %02ds", seconds / 60, seconds % 60);
        return String.format("%dh %02dm", seconds / 3600, (seconds % 3600) / 60);
    }

    private void startScanAsync() {
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "scan-engine");
            t.setDaemon(true);
            return t;
        }).submit(() -> {
            try {
                scanEngine.start(job);
                Platform.runLater(this::onScanComplete);
            } catch (Exception e) {
                log.error("Scan engine error: {}", e.getMessage());
                Platform.runLater(() -> statusLabel.setText("Error: " + e.getMessage()));
            }
        });
    }

    private void onScanComplete() {
        if (refreshTimeline != null) refreshTimeline.stop();
        resourceMonitor.stop();
        refreshUI();
        // The whole-run redraw is throttled to every few seconds, so the last one may be skipped.
        // Force it, or the final chart stops short of where the job actually ended.
        renderChart(true);
        navigateToSummary();
    }

    private void navigateToSummary() {
        ScreenNavigator nav = MediaScannerApp.getScreenNavigator();
        if (nav != null) {
            Object ctrl = nav.navigateTo(ScreenNavigator.ScreenType.SUMMARY);
            if (ctrl instanceof SummaryController sc) {
                sc.init(scanEngine.getJobStatistics(), database, config, job.getTargetPath());
            }
        }
    }

    @FXML private void onPause() {
        scanEngine.pause();
        pauseButton.setDisable(true);
        resumeButton.setDisable(false);
        statusLabel.setText("Paused");
    }

    @FXML private void onResume() {
        scanEngine.resume();
        pauseButton.setDisable(false);
        resumeButton.setDisable(true);
        statusLabel.setText("Running...");
    }

    @FXML private void onStop() {
        scanEngine.stop();
        if (refreshTimeline != null) refreshTimeline.stop();
        resourceMonitor.stop();
        statusLabel.setText("Stopped");
        navigateToSummary();
    }

    @FXML private void onExportState() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Job State");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("JSON Files", "*.json"));
        File file = chooser.showSaveDialog(pauseButton.getScene().getWindow());
        if (file != null) {
            try {
                JobStateExporter exporter = new JobStateExporter();
                CheckpointState state = new CheckpointState();
                state.setJobId(job.getJobId());
                state.setSourcePath(job.getSourcePath());
                state.setTargetPath(job.getTargetPath());
                JobStatistics stats = scanEngine.getJobStatistics();
                if (stats != null) {
                    state.setProcessedFiles(stats.getFilesProcessed());
                    state.setFailedFiles(stats.getFilesFailed());
                    state.setSkippedFiles(stats.getFilesSkipped());
                }
                exporter.export(state, Paths.get(file.getAbsolutePath()));
                statusLabel.setText("State exported to " + file.getName());
            } catch (Exception e) {
                log.error("Export failed: {}", e.getMessage());
            }
        }
    }
}
