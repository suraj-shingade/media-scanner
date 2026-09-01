package com.mediascanner.ui;

import com.mediascanner.app.MediaScannerApp;
import com.mediascanner.config.AppConfig;
import com.mediascanner.db.Database;
import com.mediascanner.db.JobStatisticsDao;
import com.mediascanner.db.ThroughputSampleDao;
import com.mediascanner.model.ThroughputSample;
import com.mediascanner.report.SummaryExporter;
import com.mediascanner.model.JobStatistics;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.ResourceBundle;

public class SummaryController implements Initializable {

    private static final Logger log = LoggerFactory.getLogger(SummaryController.class);

    @FXML private Label totalFoundLabel;
    @FXML private Label transferredLabel;
    @FXML private Label failedLabel;
    @FXML private Label skippedLabel;
    @FXML private Label duplicatesLabel;
    @FXML private Label emptyLabel;
    @FXML private Label smallLabel;
    @FXML private Label corruptLabel;
    @FXML private Label foldersLabel;
    @FXML private Label totalBytesLabel;
    @FXML private Label bytesCopiedLabel;
    @FXML private Label bytesMovedLabel;
    @FXML private Label dupSavingsLabel;
    @FXML private Label avgFpsLabel;
    @FXML private Label peakFpsLabel;
    @FXML private Label avgMbsLabel;
    @FXML private Label peakMbsLabel;
    @FXML private Label jobIdLabel;
    @FXML private Label startTimeLabel;
    @FXML private Label endTimeLabel;
    @FXML private Label durationLabel;
    @FXML private VBox chartContainer;

    private JobStatistics stats;
    private Database database;
    private AppConfig config;
    private String targetPath;
    private final SummaryExporter exporter = new SummaryExporter();
    private ThroughputChart chart;
    private List<ThroughputSample> samples = Collections.emptyList();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        chart = new ThroughputChart();
        if (chartContainer != null) {
            chartContainer.getChildren().add(chart);
        }
    }

    public void init(JobStatistics stats, Database database, AppConfig config, String targetPath) {
        this.stats = stats;
        this.database = database;
        this.config = config;
        this.targetPath = targetPath;

        if (stats != null) {
            populateUI();
            loadThroughput();
        }
    }

    /**
     * Populates this screen for a job that finished earlier, entirely from stored data
     * (FR-005-009). The live path above needs a running engine; this one only needs a job id.
     */
    public void loadStoredJob(String jobId, Database database, AppConfig config) {
        this.database = database;
        this.config = config;
        try {
            JobStatistics stored = new JobStatisticsDao(database).findByJobId(jobId);
            if (stored == null) {
                showAlert("Job Not Found", "No stored record for job " + jobId + ".");
                return;
            }
            this.stats = stored;
            this.targetPath = null;
            populateUI();
            loadThroughput();
        } catch (Exception e) {
            log.error("Could not load stored job {}: {}", jobId, e.getMessage());
            showAlert("Error", "Could not load job " + jobId + ": " + e.getMessage());
        }
    }

    private void loadThroughput() {
        if (database == null || stats == null) return;
        try {
            samples = new ThroughputSampleDao(database).findDownsampled(stats.getJobId(), 600);
        } catch (Exception e) {
            log.warn("Could not load throughput samples: {}", e.getMessage());
            samples = Collections.emptyList();
        }
        if (chart != null) chart.setSamples(samples);
    }

    private void populateUI() {
        long total = stats.getFilesProcessed() + stats.getFilesFailed() + stats.getFilesSkipped();
        totalFoundLabel.setText(String.valueOf(total));
        transferredLabel.setText(String.valueOf(stats.getFilesProcessed()));
        failedLabel.setText(String.valueOf(stats.getFilesFailed()));
        skippedLabel.setText(String.valueOf(stats.getFilesSkipped()));
        duplicatesLabel.setText(String.valueOf(stats.getDuplicatesFound()));
        emptyLabel.setText(String.valueOf(stats.getEmptyFilesCount()));
        smallLabel.setText(String.valueOf(stats.getSmallFilesCount()));
        corruptLabel.setText(String.valueOf(stats.getCorruptFilesCount()));
        foldersLabel.setText(String.valueOf(stats.getTotalFoldersCreated()));

        totalBytesLabel.setText(DataUnitFormatter.format(stats.getTotalBytesProcessed()));
        bytesCopiedLabel.setText(DataUnitFormatter.format(stats.getTotalBytesCopied()));
        bytesMovedLabel.setText(DataUnitFormatter.format(stats.getTotalBytesMoved()));
        dupSavingsLabel.setText(DataUnitFormatter.format(stats.getDuplicateByteSavings()));

        avgFpsLabel.setText(String.format("%.1f", stats.getAvgFilesPerSec()));
        peakFpsLabel.setText(String.format("%.1f", stats.getPeakFilesPerSec()));
        avgMbsLabel.setText(DataUnitFormatter.formatRate(stats.getAvgMbPerSec()));
        peakMbsLabel.setText(DataUnitFormatter.formatRate(stats.getPeakMbPerSec()));

        jobIdLabel.setText(stats.getJobId() != null ? stats.getJobId() : "");
        if (stats.getStartTime() != null) startTimeLabel.setText(stats.getStartTime().toString());
        if (stats.getEndTime() != null) {
            endTimeLabel.setText(stats.getEndTime().toString());
            Duration dur = Duration.between(stats.getStartTime(), stats.getEndTime());
            durationLabel.setText(String.format("%02d:%02d:%02d",
                dur.toHours(), dur.toMinutesPart(), dur.toSecondsPart()));
        }
    }

    @FXML private void onViewFailureReport() {
        if (targetPath == null) return;
        Path reportFile = Paths.get(targetPath, "_failures", "failure-report.json");
        if (Files.exists(reportFile)) {
            try {
                Desktop.getDesktop().open(reportFile.toFile());
            } catch (Exception e) {
                log.error("Cannot open failure report: {}", e.getMessage());
                showAlert("Error", "Cannot open failure report: " + e.getMessage());
            }
        } else {
            showAlert("No Failures", "No failure report found — no files failed during this job.");
        }
    }

    @FXML private void onExportJson() {
        exportAs(SummaryExporter.Format.JSON, "JSON Files", "*.json", ".json");
    }

    @FXML private void onExportCsv() {
        exportAs(SummaryExporter.Format.CSV, "CSV Files", "*.csv", ".csv");
    }

    @FXML private void onExportHtml() {
        exportAs(SummaryExporter.Format.HTML, "HTML Files", "*.html", ".html");
    }

    /**
     * All three formats go through one exporter so their figures cannot drift apart
     * (FR-005-010).
     */
    private void exportAs(SummaryExporter.Format format, String filterLabel,
                          String glob, String extension) {
        if (stats == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Summary (" + format + ")");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(filterLabel, glob));
        chooser.setInitialFileName(stats.getJobId() + "-summary" + extension);
        File file = chooser.showSaveDialog(totalFoundLabel.getScene().getWindow());
        if (file == null) return;
        try {
            exporter.export(file.toPath(), format, stats, samples);
            log.info("Summary exported to {}", file.getAbsolutePath());
        } catch (Exception e) {
            // US4 AS-4: an unwritable target must not take the application down with it.
            log.error("Export failed: {}", e.getMessage());
            showAlert("Export Error", "Could not write " + file.getName() + ": " + e.getMessage());
        }
    }

    @FXML private void onStartNewScan() {
        ScreenNavigator nav = MediaScannerApp.getScreenNavigator();
        if (nav != null) {
            nav.navigateTo(ScreenNavigator.ScreenType.CONFIGURATION);
        }
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
