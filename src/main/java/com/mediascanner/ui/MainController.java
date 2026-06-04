package com.mediascanner.ui;

import com.mediascanner.app.MediaScannerApp;
import com.mediascanner.checkpoint.JobStateExporter;
import com.mediascanner.config.AppConfig;
import com.mediascanner.db.Database;
import com.mediascanner.db.JobStatisticsDao;
import com.mediascanner.model.*;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.nio.file.Paths;
import java.util.*;

public class MainController implements Initializable {

    private static final Logger log = LoggerFactory.getLogger(MainController.class);

    @FXML private TextField sourcePathField;
    @FXML private TextField targetPathField;
    @FXML private RadioButton copyRadio;
    @FXML private RadioButton moveRadio;
    @FXML private ComboBox<String> folderPatternCombo;
    @FXML private ComboBox<String> duplicatePolicyCombo;
    @FXML private Button startButton;
    @FXML private Label statusLabel;

    private final ToggleGroup transferModeGroup = new ToggleGroup();
    private AppConfig config;
    private Database database;
    private JobStateExporter exporter;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        config = MediaScannerApp.getAppConfig();
        if (config == null) config = new AppConfig();
        exporter = new JobStateExporter();

        copyRadio.setToggleGroup(transferModeGroup);
        moveRadio.setToggleGroup(transferModeGroup);
        copyRadio.setSelected(true);

        folderPatternCombo.getItems().addAll(
            "YYYY/MMM", "YYYY/MM", "YYYY/MMM/DD", "YYYY/MM/DD");
        folderPatternCombo.setValue("YYYY/MMM");

        duplicatePolicyCombo.getItems().addAll(
            "Skip", "Move to /_duplicates", "Keep Both");
        duplicatePolicyCombo.setValue("Skip");

        checkStartButtonState();
        openDatabaseAndCheckResume();
    }

    private void openDatabaseAndCheckResume() {
        new Thread(() -> {
            try {
                database = new Database(config.getDbPath());
                if (database.isCorruptionWarning()) {
                    Platform.runLater(() -> showAlert("Database Warning",
                        "Hash cache lost — all files will be re-hashed this run."));
                }
                // Wire the hash index DAO to the menu bar controller
                var menuBar = MediaScannerApp.getMenuBarController();
                if (menuBar != null) {
                    menuBar.setHashIndexDao(new com.mediascanner.db.HashIndexDao(database));
                }
                JobStatisticsDao dao = new JobStatisticsDao(database);
                JobStatistics active = dao.findActiveJob();
                if (active != null) {
                    Platform.runLater(() -> offerResume(active));
                }
            } catch (Exception e) {
                log.error("Database initialization failed: {}", e.getMessage());
                Platform.runLater(() -> statusLabel.setText("DB error: " + e.getMessage()));
            }
        }, "db-init").start();
    }

    private void offerResume(JobStatistics active) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Resume Previous Job?");
        alert.setHeaderText("An interrupted job was found: " + active.getJobId());
        alert.setContentText("Processed: " + active.getFilesProcessed() + " files. Resume?");
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            statusLabel.setText("Import the job state file to resume.");
        }
    }

    public void loadCheckpoint(CheckpointState state) {
        if (state == null) return;
        sourcePathField.setText(state.getSourcePath() != null ? state.getSourcePath() : "");
        targetPathField.setText(state.getTargetPath() != null ? state.getTargetPath() : "");
        checkStartButtonState();
        statusLabel.setText("Loaded job " + state.getJobId()
            + " (" + state.getProcessedFiles() + " files processed)");
    }

    public void triggerStartScan() {
        if (!startButton.isDisabled()) {
            onStartScan();
        }
    }

    @FXML private void onBrowseSource() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Source Directory");
        File dir = chooser.showDialog(sourcePathField.getScene().getWindow());
        if (dir != null) {
            sourcePathField.setText(dir.getAbsolutePath());
            checkStartButtonState();
        }
    }

    @FXML private void onBrowseTarget() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Target Directory");
        File dir = chooser.showDialog(targetPathField.getScene().getWindow());
        if (dir != null) {
            targetPathField.setText(dir.getAbsolutePath());
            checkStartButtonState();
        }
    }

    private void checkStartButtonState() {
        String src = sourcePathField.getText();
        String tgt = targetPathField.getText();
        boolean valid = src != null && !src.isBlank()
            && tgt != null && !tgt.isBlank()
            && !src.equals(tgt);
        startButton.setDisable(!valid);
    }

    @FXML private void onTransferModeChanged() { /* selection read at scan time */ }
    @FXML private void onFolderPatternChanged() { /* selection read at scan time */ }
    @FXML private void onDuplicatePolicyChanged() { /* selection read at scan time */ }

    @FXML private void onStartScan() {
        String source = sourcePathField.getText();
        String target = targetPathField.getText();
        boolean isMove = moveRadio.isSelected();

        Job.TransferMode mode = isMove ? Job.TransferMode.MOVE : Job.TransferMode.COPY;
        Job.FolderPattern pattern = mapFolderPattern(folderPatternCombo.getValue());
        Job.DuplicatePolicy policy = mapDuplicatePolicy(duplicatePolicyCombo.getValue());

        Job job;
        try {
            job = Job.create(source, target, mode, pattern, policy,
                             config.getImageSizeThresholdKb(),
                             config.getVideoSizeThresholdKb(),
                             config.getWorkerThreadCount(),
                             config.isHighPriorityMode(),
                             config.getIgnoreRules());
        } catch (IllegalArgumentException e) {
            showAlert("Invalid Path Configuration", e.getMessage());
            return;
        }

        navigateToDashboard(job);
    }

    private void navigateToDashboard(Job job) {
        ScreenNavigator nav = MediaScannerApp.getScreenNavigator();
        if (nav != null) {
            Object ctrl = nav.navigateTo(ScreenNavigator.ScreenType.DASHBOARD);
            if (ctrl instanceof DashboardController dc) {
                dc.init(job, database, config);
            }
        } else {
            showAlert("Error", "Navigation service unavailable.");
        }
    }

    @FXML private void onImportJobState() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import Job State");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("JSON Files", "*.json"));
        File file = chooser.showOpenDialog(startButton.getScene().getWindow());
        if (file != null) {
            try {
                CheckpointState state = exporter.importFrom(Paths.get(file.getAbsolutePath()));
                if (state != null) {
                    loadCheckpoint(state);
                } else {
                    showAlert("Import Failed", "Could not import: paths not accessible.");
                }
            } catch (Exception e) {
                showAlert("Import Error", e.getMessage());
            }
        }
    }

    private Job.FolderPattern mapFolderPattern(String value) {
        return switch (value) {
            case "YYYY/MM" -> Job.FolderPattern.YYYY_MM;
            case "YYYY/MMM/DD" -> Job.FolderPattern.YYYY_MMM_DD;
            case "YYYY/MM/DD" -> Job.FolderPattern.YYYY_MM_DD;
            default -> Job.FolderPattern.YYYY_MMM;
        };
    }

    private Job.DuplicatePolicy mapDuplicatePolicy(String value) {
        if (value == null) return Job.DuplicatePolicy.SKIP;
        if (value.startsWith("Move")) return Job.DuplicatePolicy.MOVE_TO_BUCKET;
        if (value.startsWith("Keep")) return Job.DuplicatePolicy.KEEP_BOTH;
        return Job.DuplicatePolicy.SKIP;
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
