package com.mediascanner.ui;

import com.mediascanner.checkpoint.JobStateExporter;
import com.mediascanner.config.AppConfig;
import com.mediascanner.db.Database;
import com.mediascanner.db.JobStatisticsDao;
import com.mediascanner.engine.ScanEngine;
import com.mediascanner.model.CheckpointState;
import com.mediascanner.model.Job;
import com.mediascanner.model.JobStatistics;
import com.mediascanner.monitor.ProgressTracker;
import com.mediascanner.monitor.ResourceMonitor;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.nio.file.Paths;
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

    private ScanEngine scanEngine;
    private ProgressTracker progressTracker;
    private ResourceMonitor resourceMonitor;
    private Job job;
    private Database database;
    private AppConfig config;
    private Timeline refreshTimeline;

    @Override
    public void initialize(URL location, ResourceBundle resources) {}

    public void init(Job job, Database database, AppConfig config) {
        this.job = job;
        this.database = database;
        this.config = config;
        this.progressTracker = new ProgressTracker();
        this.resourceMonitor = new ResourceMonitor();
        this.scanEngine = new ScanEngine(config, database, progressTracker);

        resourceMonitor.start();
        startRefreshTimeline();
        startScanAsync();
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

        progressTracker.tick();
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
        navigateToSummary();
    }

    private void navigateToSummary() {
        try {
            FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/fxml/summary.fxml"));
            Parent root = loader.load();
            SummaryController controller = loader.getController();
            JobStatistics stats = scanEngine.getJobStatistics();
            controller.init(stats, database, config, job.getTargetPath());

            Stage stage = (Stage) processedLabel.getScene().getWindow();
            stage.setScene(new Scene(root, 900, 700));
        } catch (Exception e) {
            log.error("Failed to navigate to summary: {}", e.getMessage());
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
