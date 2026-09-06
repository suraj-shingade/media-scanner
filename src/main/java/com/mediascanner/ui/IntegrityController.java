package com.mediascanner.ui;

import com.mediascanner.app.MediaScannerApp;
import com.mediascanner.db.Database;
import com.mediascanner.db.HashIndexDao;
import com.mediascanner.engine.IntegrityEngine;
import com.mediascanner.model.IntegrityFinding;
import com.mediascanner.model.IntegrityRun;
import com.mediascanner.model.IntegrityStatus;
import com.mediascanner.report.IntegrityReportWriter;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ResourceBundle;

/**
 * The Verify Archive screen (feature 011).
 *
 * <p>This controller carries none of the safety burden {@link CleanupController} does, because the
 * engine it drives cannot change anything. Its job is the opposite one: making sure the user reads the
 * result correctly. A quick pass that finds nothing is not proof of integrity, and a cancelled run
 * proves less still — so the verdict line and the caveat beneath it are as much a part of the feature
 * as the check itself.
 */
public class IntegrityController implements Initializable {

    private static final Logger log = LoggerFactory.getLogger(IntegrityController.class);

    /** Repainting per file would swamp the FX thread on a large archive. */
    private static final int PROGRESS_EVERY = 25;

    @FXML private TextField archiveField;
    @FXML private RadioButton quickMode;
    @FXML private RadioButton deepMode;
    @FXML private Button verifyButton;

    @FXML private VBox activityBox;
    @FXML private ProgressBar activityBar;
    @FXML private Label activityLabel;
    @FXML private Button cancelButton;

    @FXML private VBox resultBox;
    @FXML private Label verdictLabel;
    @FXML private Label caveatLabel;
    @FXML private GridPane statsGrid;
    @FXML private Label reportLabel;
    @FXML private Label statusLabel;

    @FXML private TableView<FindingRow> findingTable;
    @FXML private TableColumn<FindingRow, String> statusColumn;
    @FXML private TableColumn<FindingRow, String> pathColumn;
    @FXML private TableColumn<FindingRow, String> detailColumn;

    private final ObservableList<FindingRow> rows = FXCollections.observableArrayList();
    private final IntegrityReportWriter reportWriter = new IntegrityReportWriter();

    private IntegrityEngine engine;
    private Path archiveRoot;
    private int statRow;

    /** One row of the findings table. */
    public static class FindingRow {
        private final SimpleStringProperty status;
        private final SimpleStringProperty path;
        private final SimpleStringProperty detail;
        private final boolean problem;

        FindingRow(IntegrityFinding finding) {
            this.status = new SimpleStringProperty(finding.getStatus().getDisplayName());
            this.path = new SimpleStringProperty(finding.getPath().toString());
            this.detail = new SimpleStringProperty(
                finding.getReason() == null ? "" : finding.getReason());
            this.problem = finding.isProblem();
        }

        public SimpleStringProperty statusProperty() { return status; }
        public SimpleStringProperty pathProperty() { return path; }
        public SimpleStringProperty detailProperty() { return detail; }
        public boolean isProblem() { return problem; }
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        ToggleGroup modes = new ToggleGroup();
        quickMode.setToggleGroup(modes);
        deepMode.setToggleGroup(modes);

        statusColumn.setCellValueFactory(c -> c.getValue().statusProperty());
        pathColumn.setCellValueFactory(c -> c.getValue().pathProperty());
        detailColumn.setCellValueFactory(c -> c.getValue().detailProperty());

        // Problems in red; untracked files are informational and stay in the default colour, so the
        // table cannot make a healthy archive look damaged.
        findingTable.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(FindingRow row, boolean empty) {
                super.updateItem(row, empty);
                setStyle(empty || row == null || !row.isProblem()
                    ? "" : "-fx-text-fill: #b00020;");
            }
        });
        findingTable.setItems(rows);

        Database database = MediaScannerApp.getDatabase();
        if (database != null) {
            engine = new IntegrityEngine(new HashIndexDao(database));
        } else {
            statusLabel.setText("No database is open yet — run or open a job first.");
        }
    }

    @FXML
    private void onBrowse() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Archive to Verify");
        File chosen = chooser.showDialog(archiveField.getScene().getWindow());
        if (chosen == null) return;

        archiveRoot = chosen.toPath();
        archiveField.setText(chosen.getAbsolutePath());
        verifyButton.setDisable(engine == null);
        hideResult();
        statusLabel.setText("Ready. Choose how thoroughly to check, then Verify.");
    }

    @FXML
    private void onVerify() {
        if (archiveRoot == null || engine == null) return;

        IntegrityRun.Mode mode = deepMode.isSelected()
            ? IntegrityRun.Mode.DEEP : IntegrityRun.Mode.QUICK;

        hideResult();
        beginActivity(mode == IntegrityRun.Mode.DEEP
            ? "Re-hashing every file..." : "Checking every file is present...");

        Path root = archiveRoot;
        new Thread(() -> {
            try {
                IntegrityRun run = engine.verify(root, mode, progress -> {
                    if (progress.checked() % PROGRESS_EVERY != 0) return;
                    Platform.runLater(() -> activityLabel.setText(String.format(
                        "Checked %d   read %s",
                        progress.checked(), DataUnitFormatter.format(progress.bytesRead()))));
                });

                Path report = null;
                try {
                    report = reportWriter.write(run);
                } catch (IOException e) {
                    log.error("Could not write integrity report", e);
                }

                final Path reportFinal = report;
                Platform.runLater(() -> {
                    endActivity();
                    showResult(run, reportFinal);
                });
            } catch (Exception e) {
                log.error("Verification failed", e);
                Platform.runLater(() -> {
                    endActivity();
                    statusLabel.setText("Verification failed: " + e.getMessage());
                });
            }
        }, "integrity-verify").start();
    }

    @FXML
    private void onCancel() {
        if (engine != null) engine.cancel();
        activityLabel.setText("Stopping...");
        cancelButton.setDisable(true);
    }

    // ------------------------------------------------------------------ result

    private void showResult(IntegrityRun run, Path report) {
        rows.clear();
        for (IntegrityFinding finding : run.getFindings()) {
            rows.add(new FindingRow(finding));
        }

        verdictLabel.setText(verdictFor(run));
        verdictLabel.setStyle(run.isClean()
            ? "-fx-font-size: 15px; -fx-text-fill: #1a7f37; -fx-font-weight: bold;"
            : "-fx-font-size: 15px; -fx-text-fill: #b00020; -fx-font-weight: bold;");
        caveatLabel.setText(caveatFor(run));

        statsGrid.getChildren().clear();
        statRow = 0;
        addStat("Files checked", String.valueOf(run.verifiedCount()));
        addStat("Intact", String.valueOf(run.countOf(IntegrityStatus.INTACT)));
        addProblemStat("Missing", run.countOf(IntegrityStatus.MISSING));
        addProblemStat("Wrong size", run.countOf(IntegrityStatus.RESIZED));
        addProblemStat("Corrupted", run.countOf(IntegrityStatus.CORRUPTED));
        addProblemStat("Unreadable", run.countOf(IntegrityStatus.UNREADABLE));
        if (run.countOf(IntegrityStatus.UNTRACKED) > 0) {
            addStat("Untracked (not errors)", String.valueOf(run.countOf(IntegrityStatus.UNTRACKED)));
        }
        if (run.countOf(IntegrityStatus.UNVERIFIABLE) > 0) {
            addStat("No destination recorded",
                String.valueOf(run.countOf(IntegrityStatus.UNVERIFIABLE)));
        }
        if (run.getBytesRead() > 0) {
            addStat("Data read", DataUnitFormatter.format(run.getBytesRead()));
        }
        addStat("Time taken", formatDuration(run.elapsedMillis()));

        reportLabel.setText(report != null ? "Report written to " + report : "");
        reportLabel.setManaged(report != null);
        reportLabel.setVisible(report != null);

        resultBox.setVisible(true);
        resultBox.setManaged(true);
        statusLabel.setText(verdictFor(run));
    }

    private String verdictFor(IntegrityRun run) {
        if (run.wasCancelled()) {
            return "Stopped before finishing — " + run.verifiedCount()
                 + " files checked, " + run.problemCount() + " problems found so far.";
        }
        if (run.problemCount() == 0) {
            return run.getMode() == IntegrityRun.Mode.DEEP
                ? "Archive verified. All " + run.countOf(IntegrityStatus.INTACT)
                  + " files match the hash recorded when they were written."
                : "All " + run.countOf(IntegrityStatus.INTACT)
                  + " files are present and the right size.";
        }
        return run.problemCount() + " problem" + (run.problemCount() == 1 ? "" : "s")
             + " found across " + run.verifiedCount() + " files checked.";
    }

    /** The sentence that keeps the verdict from being over-read. */
    private String caveatFor(IntegrityRun run) {
        if (run.wasCancelled()) {
            return "Files this run did not reach were not checked. Nothing can be concluded about them.";
        }
        if (run.getMode() == IntegrityRun.Mode.QUICK) {
            return "Quick mode checked existence and size only. A file altered in place without "
                 + "changing its length would still be reported as intact — run a deep verification "
                 + "for proof of contents.";
        }
        return "Every file was re-read and re-hashed. Nothing in the archive was modified.";
    }

    private void addStat(String label, String value) {
        addStatRow(label, value, false);
    }

    private void addProblemStat(String label, int count) {
        if (count == 0) return;
        addStatRow(label, String.valueOf(count), true);
    }

    private void addStatRow(String label, String value, boolean problem) {
        Label name = new Label(label);
        name.getStyleClass().add("subtle");
        Label figure = new Label(value);
        figure.setStyle(problem
            ? "-fx-font-weight: bold; -fx-text-fill: #b00020;"
            : "-fx-font-weight: bold;");
        statsGrid.add(name, 0, statRow);
        statsGrid.add(figure, 1, statRow);
        statRow++;
    }

    private void hideResult() {
        resultBox.setVisible(false);
        resultBox.setManaged(false);
        rows.clear();
    }

    // ---------------------------------------------------------------- activity

    private void beginActivity(String message) {
        engine.resetCancel();
        activityLabel.setText(message);
        activityBar.setProgress(-1.0);
        cancelButton.setDisable(false);
        activityBox.setVisible(true);
        activityBox.setManaged(true);
        verifyButton.setDisable(true);
        statusLabel.setText(message);
    }

    private void endActivity() {
        // Reset to a determinate value, not merely hidden: JavaFX keeps animating an indeterminate
        // progress bar that is invisible, which cost a full core on the Delete screen (audit N8).
        activityBar.setProgress(0);
        activityBox.setVisible(false);
        activityBox.setManaged(false);
        verifyButton.setDisable(false);
    }

    private static String formatDuration(long millis) {
        long seconds = millis / 1000;
        if (seconds < 60) return seconds + "s";
        return String.format("%d min %02d s", seconds / 60, seconds % 60);
    }
}
