package com.mediascanner.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mediascanner.config.AppConfig;
import com.mediascanner.db.Database;
import com.mediascanner.db.JobStatisticsDao;
import com.mediascanner.model.JobStatistics;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
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

    private JobStatistics stats;
    private Database database;
    private AppConfig config;
    private String targetPath;

    @Override
    public void initialize(URL location, ResourceBundle resources) {}

    public void init(JobStatistics stats, Database database, AppConfig config, String targetPath) {
        this.stats = stats;
        this.database = database;
        this.config = config;
        this.targetPath = targetPath;

        if (stats != null) populateUI();
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
        if (stats == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Summary (JSON)");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("JSON Files", "*.json"));
        chooser.setInitialFileName(stats.getJobId() + "-summary.json");
        File file = chooser.showSaveDialog(totalFoundLabel.getScene().getWindow());
        if (file != null) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                mapper.registerModule(new JavaTimeModule());
                mapper.enable(SerializationFeature.INDENT_OUTPUT);
                mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
                mapper.writeValue(file, stats);
                log.info("Summary exported to {}", file.getAbsolutePath());
            } catch (Exception e) {
                showAlert("Export Error", e.getMessage());
            }
        }
    }

    @FXML private void onExportText() {
        if (stats == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Summary (Text)");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Text Files", "*.txt"));
        chooser.setInitialFileName(stats.getJobId() + "-summary.txt");
        File file = chooser.showSaveDialog(totalFoundLabel.getScene().getWindow());
        if (file != null) {
            try (PrintWriter pw = new PrintWriter(new FileWriter(file))) {
                pw.println("MediaScanner Job Summary");
                pw.println("========================");
                pw.println("Job ID:           " + stats.getJobId());
                pw.println("Start Time:       " + stats.getStartTime());
                pw.println("End Time:         " + stats.getEndTime());
                pw.println();
                pw.println("Files");
                pw.println("  Processed:      " + stats.getFilesProcessed());
                pw.println("  Failed:         " + stats.getFilesFailed());
                pw.println("  Skipped:        " + stats.getFilesSkipped());
                pw.println("  Duplicates:     " + stats.getDuplicatesFound());
                pw.println("  Empty:          " + stats.getEmptyFilesCount());
                pw.println("  Small:          " + stats.getSmallFilesCount());
                pw.println("  Corrupt:        " + stats.getCorruptFilesCount());
                pw.println("  Folders:        " + stats.getTotalFoldersCreated());
                pw.println();
                pw.println("Data");
                pw.println("  Total:          " + DataUnitFormatter.format(stats.getTotalBytesProcessed()));
                pw.println("  Copied:         " + DataUnitFormatter.format(stats.getTotalBytesCopied()));
                pw.println("  Moved:          " + DataUnitFormatter.format(stats.getTotalBytesMoved()));
                pw.println("  Dup Savings:    " + DataUnitFormatter.format(stats.getDuplicateByteSavings()));
                pw.println();
                pw.println("Performance");
                pw.println("  Avg files/sec:  " + String.format("%.1f", stats.getAvgFilesPerSec()));
                pw.println("  Peak files/sec: " + String.format("%.1f", stats.getPeakFilesPerSec()));
                pw.println("  Avg MB/sec:     " + DataUnitFormatter.formatRate(stats.getAvgMbPerSec()));
                pw.println("  Peak MB/sec:    " + DataUnitFormatter.formatRate(stats.getPeakMbPerSec()));
                log.info("Text summary exported to {}", file.getAbsolutePath());
            } catch (Exception e) {
                showAlert("Export Error", e.getMessage());
            }
        }
    }

    @FXML private void onStartNewScan() {
        try {
            FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/fxml/main.fxml"));
            Parent root = loader.load();
            Stage stage = (Stage) totalFoundLabel.getScene().getWindow();
            stage.setScene(new Scene(root, 1000, 700));
        } catch (Exception e) {
            log.error("Failed to return to main: {}", e.getMessage());
        }
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
