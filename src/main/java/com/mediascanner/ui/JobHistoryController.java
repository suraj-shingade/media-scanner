package com.mediascanner.ui;

import com.mediascanner.app.MediaScannerApp;
import com.mediascanner.config.AppConfig;
import com.mediascanner.db.Database;
import com.mediascanner.db.JobEventDao;
import com.mediascanner.db.JobStatisticsDao;
import com.mediascanner.db.ThroughputSampleDao;
import com.mediascanner.engine.AppStateManager;
import com.mediascanner.model.JobStatistics;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Browses every job ever recorded (FR-005-008).
 *
 * <p>{@code JOB_STATISTICS} has always persisted this, but nothing read it back — the summary
 * screen only ever showed the job that had just run, so restarting the application discarded all
 * visible history.
 */
public class JobHistoryController implements Initializable {

    private static final Logger log = LoggerFactory.getLogger(JobHistoryController.class);
    private static final DateTimeFormatter DISPLAY_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @FXML private TableView<JobStatistics> jobTable;
    @FXML private TableColumn<JobStatistics, String> startedColumn;
    @FXML private TableColumn<JobStatistics, String> statusColumn;
    @FXML private TableColumn<JobStatistics, String> jobIdColumn;
    @FXML private TableColumn<JobStatistics, String> processedColumn;
    @FXML private TableColumn<JobStatistics, String> skippedColumn;
    @FXML private TableColumn<JobStatistics, String> failedColumn;
    @FXML private TableColumn<JobStatistics, String> duplicatesColumn;
    @FXML private TableColumn<JobStatistics, String> dataColumn;
    @FXML private Label statusLabel;
    @FXML private Button openButton;
    @FXML private Button deleteButton;

    private final ObservableList<JobStatistics> jobs = FXCollections.observableArrayList();
    private Database database;
    private AppConfig config;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        startedColumn.setCellValueFactory(c -> new SimpleStringProperty(
            c.getValue().getStartTime() != null
                ? c.getValue().getStartTime().format(DISPLAY_FMT) : ""));
        statusColumn.setCellValueFactory(c -> new SimpleStringProperty(
            nullSafe(c.getValue().getStatus())));
        jobIdColumn.setCellValueFactory(c -> new SimpleStringProperty(
            nullSafe(c.getValue().getJobId())));
        processedColumn.setCellValueFactory(c -> new SimpleStringProperty(
            String.valueOf(c.getValue().getFilesProcessed())));
        skippedColumn.setCellValueFactory(c -> new SimpleStringProperty(
            String.valueOf(c.getValue().getFilesSkipped())));
        failedColumn.setCellValueFactory(c -> new SimpleStringProperty(
            String.valueOf(c.getValue().getFilesFailed())));
        duplicatesColumn.setCellValueFactory(c -> new SimpleStringProperty(
            String.valueOf(c.getValue().getDuplicatesFound())));
        dataColumn.setCellValueFactory(c -> new SimpleStringProperty(
            DataUnitFormatter.format(c.getValue().getTotalBytesProcessed())));

        jobTable.setItems(jobs);
        jobTable.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

        var hasSelection = jobTable.getSelectionModel().selectedItemProperty().isNull();
        openButton.disableProperty().bind(hasSelection);
        deleteButton.disableProperty().bind(hasSelection);

        jobTable.setRowFactory(tv -> {
            TableRow<JobStatistics> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    onOpenSummary();
                }
            });
            return row;
        });

        this.database = MediaScannerApp.getDatabase();
        this.config = MediaScannerApp.getAppConfig();
        reload();
    }

    /** Allows the caller to supply the database explicitly rather than via the app singleton. */
    public void init(Database database, AppConfig config) {
        this.database = database;
        this.config = config;
        reload();
    }

    private void reload() {
        if (database == null) {
            statusLabel.setText("Database unavailable.");
            return;
        }
        try {
            List<JobStatistics> all = new JobStatisticsDao(database).findAll();
            jobs.setAll(all);
            statusLabel.setText(all.isEmpty()
                ? "No jobs recorded yet."
                : all.size() + (all.size() == 1 ? " job recorded." : " jobs recorded."));
        } catch (Exception e) {
            log.error("Could not load job history: {}", e.getMessage());
            statusLabel.setText("Could not load job history: " + e.getMessage());
        }
    }

    @FXML private void onRefresh() {
        reload();
    }

    @FXML private void onOpenSummary() {
        JobStatistics selected = jobTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        ScreenNavigator nav = MediaScannerApp.getScreenNavigator();
        if (nav == null) return;
        Object controller = nav.navigateTo(ScreenNavigator.ScreenType.SUMMARY);
        if (controller instanceof SummaryController summary) {
            summary.loadStoredJob(selected.getJobId(), database, config);
        }
    }

    /**
     * Removes a job's stored records. Nothing in the archive is touched — deleting history must
     * never delete the user's media (FR-005-012).
     */
    @FXML private void onDelete() {
        JobStatistics selected = jobTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        if (isRunning(selected)) {
            new Alert(Alert.AlertType.WARNING,
                "This job is still running. Stop it before deleting its history.")
                .showAndWait();
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Job History");
        confirm.setHeaderText("Delete the record for " + selected.getJobId() + "?");
        confirm.setContentText("This removes the job's statistics, per-file records and throughput "
            + "history from the application database.\n\n"
            + "No files in your archive are touched, and report files already written to the "
            + "archive are left in place.");
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != ButtonType.OK) return;

        try {
            String jobId = selected.getJobId();
            new JobEventDao(database).deleteByJobId(jobId);
            new ThroughputSampleDao(database).deleteByJobId(jobId);
            new JobStatisticsDao(database).deleteJob(jobId);
            log.info("Deleted job history for {}", jobId);
            reload();
        } catch (Exception e) {
            log.error("Could not delete job {}: {}", selected.getJobId(), e.getMessage());
            new Alert(Alert.AlertType.ERROR,
                "Could not delete this job: " + e.getMessage()).showAndWait();
        }
    }

    private boolean isRunning(JobStatistics job) {
        if ("RUNNING".equalsIgnoreCase(job.getStatus()) || "PAUSED".equalsIgnoreCase(job.getStatus())) {
            return true;
        }
        AppStateManager.AppState state = AppStateManager.getInstance().getState();
        return state == AppStateManager.AppState.RUNNING
            || state == AppStateManager.AppState.PAUSED;
    }

    @FXML private void onClose() {
        ScreenNavigator nav = MediaScannerApp.getScreenNavigator();
        if (nav != null) {
            Platform.runLater(() -> nav.navigateTo(ScreenNavigator.ScreenType.CONFIGURATION));
        }
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }
}
