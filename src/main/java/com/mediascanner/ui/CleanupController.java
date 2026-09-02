package com.mediascanner.ui;

import com.mediascanner.engine.CleanupEngine;
import com.mediascanner.model.CleanupCandidate;
import com.mediascanner.model.CleanupRun;
import com.mediascanner.model.MimeGroup;
import com.mediascanner.report.CleanupReportWriter;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.stage.DirectoryChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * The Cleanup screen.
 *
 * <p>The controller owns the half of Constitution IX that the engine cannot enforce: the user must
 * see a preview and then actively confirm. {@link CleanupEngine#delete} will happily delete whatever
 * it is handed, so the guarantee that a human looked first lives here — the delete button stays
 * disabled until an analysis has produced candidates, and it routes through a confirmation dialog
 * that names the count, the size and the irreversibility.
 */
public class CleanupController implements Initializable {

    private static final Logger log = LoggerFactory.getLogger(CleanupController.class);

    @FXML private TextField directoryField;
    @FXML private Button analyzeButton;
    @FXML private Button deleteButton;
    @FXML private Button pruneButton;
    @FXML private Label warningLabel;
    @FXML private Label statusLabel;
    @FXML private TableView<GroupRow> groupTable;
    @FXML private TableColumn<GroupRow, Boolean> selectColumn;
    @FXML private TableColumn<GroupRow, String> groupColumn;
    @FXML private TableColumn<GroupRow, String> countColumn;
    @FXML private TableColumn<GroupRow, String> sizeColumn;
    @FXML private ListView<String> fileList;

    private final CleanupEngine engine = new CleanupEngine();
    private final CleanupReportWriter reportWriter = new CleanupReportWriter();
    private final ObservableList<GroupRow> rows = FXCollections.observableArrayList();

    private Path selectedDirectory;
    private CleanupRun currentRun;

    /** One row of the group summary table. */
    public static class GroupRow {
        private final MimeGroup group;
        private final SimpleBooleanProperty selected = new SimpleBooleanProperty(false);
        private final SimpleStringProperty groupName;
        private final SimpleStringProperty count;
        private final SimpleStringProperty size;

        GroupRow(MimeGroup group, int count, long bytes) {
            this.group = group;
            this.groupName = new SimpleStringProperty(
                group.isDeletable() ? group.getDisplayName()
                                    : group.getDisplayName() + "  (never deleted)");
            this.count = new SimpleStringProperty(String.valueOf(count));
            this.size = new SimpleStringProperty(DataUnitFormatter.format(bytes));
        }

        public MimeGroup getGroup() { return group; }
        public SimpleBooleanProperty selectedProperty() { return selected; }
        public boolean isSelected() { return selected.get(); }
        public SimpleStringProperty groupNameProperty() { return groupName; }
        public SimpleStringProperty countProperty() { return count; }
        public SimpleStringProperty sizeProperty() { return size; }
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        groupColumn.setCellValueFactory(c -> c.getValue().groupNameProperty());
        countColumn.setCellValueFactory(c -> c.getValue().countProperty());
        sizeColumn.setCellValueFactory(c -> c.getValue().sizeProperty());

        selectColumn.setCellValueFactory(c -> c.getValue().selectedProperty());
        selectColumn.setCellFactory(CheckBoxTableCell.forTableColumn(selectColumn));
        groupTable.setEditable(true);
        selectColumn.setEditable(true);

        groupTable.setItems(rows);
        groupTable.getSelectionModel().selectedItemProperty().addListener(
            (obs, old, row) -> showFilesFor(row));
    }

    // ------------------------------------------------------------- directory

    @FXML
    private void onBrowse() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Directory to Clean");
        File chosen = chooser.showDialog(directoryField.getScene().getWindow());
        if (chosen == null) return;

        selectedDirectory = chosen.toPath();
        directoryField.setText(chosen.getAbsolutePath());
        analyzeButton.setDisable(false);

        // Reset: a new directory invalidates any previous preview, and a delete button that
        // outlived its preview would be exactly the bug Principle IX exists to prevent.
        clearPreview();
        pruneButton.setDisable(false);
        hideWarning();
    }

    // -------------------------------------------------------------- analysis

    @FXML
    private void onAnalyze() {
        if (selectedDirectory == null) return;

        clearPreview();
        hideWarning();
        analyzeButton.setDisable(true);
        statusLabel.setText("Analyzing " + selectedDirectory + " ...");

        Path root = selectedDirectory;
        new Thread(() -> {
            try {
                CleanupRun run = engine.analyze(root, null);
                Platform.runLater(() -> onAnalysisComplete(run));
            } catch (IllegalArgumentException refused) {
                Platform.runLater(() -> {
                    showWarning(refused.getMessage());
                    statusLabel.setText("Refused: this folder cannot be cleaned.");
                    analyzeButton.setDisable(false);
                });
            } catch (Exception e) {
                log.error("Cleanup analysis failed", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Analysis failed: " + e.getMessage());
                    analyzeButton.setDisable(false);
                });
            }
        }, "cleanup-analyze").start();
    }

    private void onAnalysisComplete(CleanupRun run) {
        currentRun = run;
        analyzeButton.setDisable(false);
        rows.clear();

        int deletable = 0;
        for (MimeGroup group : MimeGroup.values()) {
            int count = run.countIn(group);
            if (count == 0) continue;
            rows.add(new GroupRow(group, count, run.bytesIn(group)));
            if (group.isDeletable()) deletable += count;
        }

        // No deletable candidates means the confirm path must be unreachable, not a confirmable
        // no-op (spec Edge Cases).
        deleteButton.setDisable(deletable == 0);
        pruneButton.setDisable(false);

        statusLabel.setText(String.format(
            "Found %d files (%s). %d can be deleted; %d are protected media.",
            run.totalFiles(), DataUnitFormatter.format(run.totalBytes()),
            deletable, run.countIn(MimeGroup.PROTECTED_MEDIA)));
    }

    private void showFilesFor(GroupRow row) {
        fileList.getItems().clear();
        if (row == null || currentRun == null) return;
        List<String> entries = new ArrayList<>();
        for (CleanupCandidate c : currentRun.inGroup(row.getGroup())) {
            entries.add(c.getPath() + "   [" + DataUnitFormatter.format(c.getSizeBytes())
                + ", " + c.getDetectedMimeType() + "]");
        }
        fileList.getItems().setAll(entries);
    }

    // ---------------------------------------------------------------- delete

    @FXML
    private void onDelete() {
        if (currentRun == null) return;

        Set<MimeGroup> selected = EnumSet.noneOf(MimeGroup.class);
        int files = 0;
        long bytes = 0;
        for (GroupRow row : rows) {
            if (row.isSelected() && row.getGroup().isDeletable()) {
                selected.add(row.getGroup());
                files += currentRun.countIn(row.getGroup());
                bytes += currentRun.bytesIn(row.getGroup());
            }
        }

        if (selected.isEmpty()) {
            showAlert("Nothing selected",
                "Tick at least one group to delete. Protected media can never be selected.");
            return;
        }

        StringBuilder groupNames = new StringBuilder();
        for (MimeGroup g : selected) {
            if (groupNames.length() > 0) groupNames.append(", ");
            groupNames.append(g.getDisplayName());
        }

        Alert confirm = new Alert(Alert.AlertType.WARNING);
        confirm.setTitle("Confirm permanent deletion");
        confirm.setHeaderText("Permanently delete " + files + " files?");
        confirm.setContentText(String.format(
            "Groups: %s%n"
            + "Files: %d%n"
            + "Total size: %s%n%n"
            + "These files will be permanently deleted. They are NOT moved to the Recycle Bin "
            + "and this action CANNOT be undone.",
            groupNames, files, DataUnitFormatter.format(bytes)));

        ButtonType deleteButtonType = new ButtonType("Delete permanently", ButtonBar.ButtonData.OK_DONE);
        confirm.getButtonTypes().setAll(ButtonType.CANCEL, deleteButtonType);

        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != deleteButtonType) {
            statusLabel.setText("Deletion cancelled. Nothing was removed.");
            return;
        }

        runDeletion(selected);
    }

    private void runDeletion(Set<MimeGroup> selected) {
        deleteButton.setDisable(true);
        statusLabel.setText("Deleting...");

        CleanupRun run = currentRun;
        new Thread(() -> {
            CleanupEngine.DeleteResult result = engine.delete(run, selected);
            Path report = null;
            try {
                report = reportWriter.write(run, result, null);
            } catch (IOException e) {
                log.error("Could not write cleanup report", e);
            }
            final Path writtenReport = report;
            Platform.runLater(() -> {
                statusLabel.setText(String.format(
                    "Deleted %d files (%s). %d skipped, %d failed.%s",
                    result.deletedCount(), DataUnitFormatter.format(result.bytesDeleted()),
                    result.getSkipped().size(), result.getFailed().size(),
                    writtenReport != null ? "  Report: " + writtenReport : ""));
                // The preview now describes a tree that no longer exists. Force a re-analysis
                // rather than letting a stale list drive a second deletion.
                clearPreview();
            });
        }, "cleanup-delete").start();
    }

    // ----------------------------------------------------------------- prune

    @FXML
    private void onPruneEmptyFolders() {
        if (selectedDirectory == null) return;

        List<Path> empties;
        try {
            empties = engine.findEmptyDirectories(selectedDirectory);
        } catch (IllegalArgumentException refused) {
            showWarning(refused.getMessage());
            return;
        }

        if (empties.isEmpty()) {
            showAlert("Nothing to prune", "No empty folders were found beneath this directory.");
            return;
        }

        StringBuilder preview = new StringBuilder();
        int shown = Math.min(empties.size(), 20);
        for (int i = 0; i < shown; i++) {
            preview.append(empties.get(i)).append(System.lineSeparator());
        }
        if (empties.size() > shown) {
            preview.append("... and ").append(empties.size() - shown).append(" more");
        }

        Alert confirm = new Alert(Alert.AlertType.WARNING);
        confirm.setTitle("Confirm folder removal");
        confirm.setHeaderText("Remove " + empties.size() + " empty folders?");
        confirm.setContentText(preview.toString());
        ButtonType removeType = new ButtonType("Remove", ButtonBar.ButtonData.OK_DONE);
        confirm.getButtonTypes().setAll(ButtonType.CANCEL, removeType);

        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != removeType) {
            statusLabel.setText("Prune cancelled. Nothing was removed.");
            return;
        }

        CleanupEngine.PruneResult result = engine.pruneEmptyDirectories(selectedDirectory);
        statusLabel.setText("Removed " + result.removedCount() + " empty folders."
            + (result.getFailed().isEmpty() ? "" : " " + result.getFailed().size() + " could not be removed."));
    }

    // ----------------------------------------------------------------- utils

    private void clearPreview() {
        rows.clear();
        fileList.getItems().clear();
        currentRun = null;
        deleteButton.setDisable(true);
    }

    private void showWarning(String message) {
        warningLabel.setText(message);
        warningLabel.setVisible(true);
        warningLabel.setManaged(true);
    }

    private void hideWarning() {
        warningLabel.setVisible(false);
        warningLabel.setManaged(false);
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
