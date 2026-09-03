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
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Delete Files &amp; Folders screen, in two modes.
 *
 * <p><b>Choose what to delete</b> — the user ticks the file types, formats and whether to remove
 * empty folders, confirms once, and the job runs: files are deleted as they are found, in a single
 * pass, with live progress and a summary at the end.
 *
 * <p><b>Review first</b> — scan, look at what was found grouped by detected type, then choose.
 *
 * <p>The confirmation is the half of Constitution IX the engine cannot enforce. In the direct mode
 * it names the <em>criteria</em> rather than a file count, because it is shown before the walk
 * begins. That is a deliberate trade: one pass over the disk and no pause between deciding and
 * doing, at the cost of not knowing the count in advance. The engine still re-checks every file's
 * content immediately before deleting it, so the dialog is the last human gate, not the only one.
 */
public class CleanupController implements Initializable {

    private static final Logger log = LoggerFactory.getLogger(CleanupController.class);

    /** Repainting per file would swamp the FX thread on a large tree. */
    private static final int PROGRESS_EVERY = 20;

    // Shared
    @FXML private TextField directoryField;
    @FXML private Label warningLabel;
    @FXML private Label statusLabel;
    @FXML private TabPane modeTabs;

    // Live activity
    @FXML private VBox activityBox;
    @FXML private ProgressBar activityBar;
    @FXML private Label activityLabel;
    @FXML private Button cancelButton;

    // Result
    @FXML private VBox resultBox;
    @FXML private GridPane statsGrid;
    @FXML private Label resultReportLabel;

    // Mode 1: direct
    @FXML private CheckBox cbAndroid;
    @FXML private CheckBox cbExecutable;
    @FXML private CheckBox cbArchive;
    @FXML private CheckBox cbDocument;
    @FXML private CheckBox cbAudio;
    @FXML private CheckBox cbOther;
    @FXML private CheckBox cbEmptyFolders;
    @FXML private TextField extensionField;
    @FXML private ListView<String> extensionList;
    @FXML private Button directDeleteButton;

    // Mode 2: review
    @FXML private Button analyzeButton;
    @FXML private Button deleteButton;
    @FXML private Button pruneButton;
    @FXML private TableView<GroupRow> groupTable;
    @FXML private TableColumn<GroupRow, Boolean> selectColumn;
    @FXML private TableColumn<GroupRow, String> groupColumn;
    @FXML private TableColumn<GroupRow, String> countColumn;
    @FXML private TableColumn<GroupRow, String> sizeColumn;
    @FXML private ListView<String> fileList;

    private final CleanupEngine engine = new CleanupEngine();
    private final CleanupReportWriter reportWriter = new CleanupReportWriter();
    private final ObservableList<GroupRow> rows = FXCollections.observableArrayList();
    private final ObservableList<String> extensions = FXCollections.observableArrayList();

    private Path selectedDirectory;
    private CleanupRun currentRun;
    private int statRow;

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

        extensionList.setItems(extensions);
        extensionList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        Runnable refresh = this::refreshDirectDeleteState;
        for (CheckBox cb : deletableChecks()) {
            cb.selectedProperty().addListener((o, a, b) -> refresh.run());
        }
        cbEmptyFolders.selectedProperty().addListener((o, a, b) -> refresh.run());
        extensions.addListener((javafx.collections.ListChangeListener<String>) c -> refresh.run());
    }

    private List<CheckBox> deletableChecks() {
        return List.of(cbAndroid, cbExecutable, cbArchive, cbDocument, cbAudio, cbOther);
    }

    private MimeGroup groupFor(CheckBox cb) {
        if (cb == cbAndroid) return MimeGroup.ANDROID_PACKAGE;
        if (cb == cbExecutable) return MimeGroup.EXECUTABLE;
        if (cb == cbArchive) return MimeGroup.ARCHIVE;
        if (cb == cbDocument) return MimeGroup.DOCUMENT;
        if (cb == cbAudio) return MimeGroup.AUDIO;
        return MimeGroup.OTHER;
    }

    // ------------------------------------------------------------- directory

    @FXML
    private void onBrowse() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Folder to Clean");
        File chosen = chooser.showDialog(directoryField.getScene().getWindow());
        if (chosen == null) return;

        selectedDirectory = chosen.toPath();
        directoryField.setText(chosen.getAbsolutePath());
        analyzeButton.setDisable(false);
        pruneButton.setDisable(false);

        clearPreview();
        hideWarning();
        hideResult();
        refreshDirectDeleteState();
        statusLabel.setText("Folder selected. Choose what to delete, or use Review first.");
    }

    // ------------------------------------------------------- mode 1: direct

    @FXML
    private void onAddExtension() {
        String normalised = CleanupEngine.normaliseExtension(extensionField.getText());
        if (normalised.isEmpty()) return;
        if (!extensions.contains(normalised)) {
            extensions.add(normalised);
        }
        extensionField.clear();
        extensionField.requestFocus();
    }

    @FXML
    private void onRemoveExtension() {
        extensions.removeAll(List.copyOf(extensionList.getSelectionModel().getSelectedItems()));
    }

    private void refreshDirectDeleteState() {
        boolean anySelection = !selectedGroups().isEmpty()
            || !extensions.isEmpty()
            || cbEmptyFolders.isSelected();
        directDeleteButton.setDisable(selectedDirectory == null || !anySelection);
    }

    private Set<MimeGroup> selectedGroups() {
        Set<MimeGroup> groups = EnumSet.noneOf(MimeGroup.class);
        for (CheckBox cb : deletableChecks()) {
            if (cb.isSelected()) groups.add(groupFor(cb));
        }
        return groups;
    }

    /** Confirm the criteria, then run: delete as found, one pass, stats at the end. */
    @FXML
    private void onDirectDelete() {
        if (selectedDirectory == null) return;

        Set<MimeGroup> groups = selectedGroups();
        Set<String> exts = new LinkedHashSet<>(extensions);
        boolean pruneEmpty = cbEmptyFolders.isSelected();

        if (groups.isEmpty() && exts.isEmpty() && !pruneEmpty) {
            showAlert("Nothing selected", "Choose at least one file type, format, or empty folders.");
            return;
        }

        hideResult();
        hideWarning();
        Path root = selectedDirectory;

        if (!confirmBeforeStarting(root, groups, exts, pruneEmpty)) {
            statusLabel.setText("Cancelled. Nothing was removed.");
            return;
        }

        if (groups.isEmpty() && exts.isEmpty()) {
            runPrune(root);
            return;
        }
        runStreamingDelete(root, groups, exts, pruneEmpty);
    }

    /** The one human gate, shown before any file is touched. */
    private boolean confirmBeforeStarting(Path root, Set<MimeGroup> groups,
                                          Set<String> exts, boolean pruneEmpty) {
        StringBuilder what = new StringBuilder();
        for (MimeGroup g : groups) {
            what.append("    - ").append(g.getDisplayName()).append(System.lineSeparator());
        }
        if (!exts.isEmpty()) {
            what.append("    - files ending in ").append(String.join(", ", exts))
                .append(System.lineSeparator());
        }
        if (pruneEmpty) {
            what.append("    - empty folders").append(System.lineSeparator());
        }

        Alert confirm = new Alert(Alert.AlertType.WARNING);
        confirm.setTitle("Start deleting?");
        confirm.setHeaderText("Permanently delete matching files in this folder?");
        confirm.setContentText(String.format(
            "Folder:%n    %s%n%n"
            + "Will delete:%n%s%n"
            + "Files are deleted as they are found. They are NOT moved to the Recycle Bin, "
            + "and this CANNOT be undone.%n%n"
            + "Photos and videos are never deleted, whatever they are named.",
            root, what));

        ButtonType go = new ButtonType("Start deleting", ButtonBar.ButtonData.OK_DONE);
        confirm.getButtonTypes().setAll(ButtonType.CANCEL, go);
        Optional<ButtonType> answer = confirm.showAndWait();
        return answer.isPresent() && answer.get() == go;
    }

    private void runStreamingDelete(Path root, Set<MimeGroup> groups,
                                    Set<String> exts, boolean pruneEmpty) {
        beginActivity("Starting...");
        long startedAt = System.currentTimeMillis();

        new Thread(() -> {
            try {
                CleanupEngine.StreamingResult streamed = engine.deleteWhileScanning(
                    root, groups, exts,
                    progress -> {
                        if (progress.examined() % PROGRESS_EVERY != 0) return;
                        Platform.runLater(() -> activityLabel.setText(String.format(
                            "Examined %d   deleted %d   freed %s",
                            progress.examined(), progress.deleted(),
                            DataUnitFormatter.format(progress.bytes()))));
                    });

                CleanupEngine.PruneResult pruned = null;
                if (pruneEmpty && !engine.isCancelled()) {
                    Platform.runLater(() -> activityLabel.setText("Removing empty folders..."));
                    try {
                        pruned = engine.pruneEmptyDirectories(root);
                    } catch (RuntimeException e) {
                        log.warn("Prune failed: {}", e.getMessage());
                    }
                }

                Path report = null;
                try {
                    report = reportWriter.write(streamed.getRun(), streamed.getResult(), null);
                } catch (IOException e) {
                    log.error("Could not write cleanup report", e);
                }

                final CleanupEngine.PruneResult prunedFinal = pruned;
                final Path reportFinal = report;
                final long elapsed = System.currentTimeMillis() - startedAt;
                Platform.runLater(() -> {
                    endActivity();
                    showStats(streamed.getExamined(), streamed.getResult(),
                              prunedFinal, reportFinal, elapsed);
                    clearPreview();
                });
            } catch (IllegalArgumentException refused) {
                Platform.runLater(() -> {
                    endActivity();
                    showWarning(refused.getMessage());
                    statusLabel.setText("Refused: this folder cannot be cleaned.");
                });
            } catch (Exception e) {
                log.error("Cleanup failed", e);
                Platform.runLater(() -> {
                    endActivity();
                    statusLabel.setText("Failed: " + e.getMessage());
                });
            }
        }, "cleanup-stream").start();
    }

    private void runPrune(Path root) {
        beginActivity("Removing empty folders...");
        long startedAt = System.currentTimeMillis();
        new Thread(() -> {
            try {
                CleanupEngine.PruneResult result = engine.pruneEmptyDirectories(root);
                final long elapsed = System.currentTimeMillis() - startedAt;
                Platform.runLater(() -> {
                    endActivity();
                    statsGrid.getChildren().clear();
                    statRow = 0;
                    addStat("Empty folders removed", String.valueOf(result.removedCount()));
                    if (!result.getFailed().isEmpty()) {
                        addStat("Could not remove", String.valueOf(result.getFailed().size()));
                    }
                    addStat("Time taken", formatDuration(elapsed));
                    showResultPanel(null);
                    statusLabel.setText("Done. " + result.removedCount() + " folders removed.");
                });
            } catch (IllegalArgumentException refused) {
                Platform.runLater(() -> {
                    endActivity();
                    showWarning(refused.getMessage());
                });
            }
        }, "cleanup-prune").start();
    }

    // ------------------------------------------------------- mode 2: review

    @FXML
    private void onAnalyze() {
        if (selectedDirectory == null) return;

        clearPreview();
        hideWarning();
        hideResult();
        analyzeButton.setDisable(true);
        beginActivity("Analyzing " + selectedDirectory + " ...");

        Path root = selectedDirectory;
        AtomicInteger seen = new AtomicInteger();
        new Thread(() -> {
            try {
                CleanupRun run = engine.analyze(root, candidate -> {
                    int n = seen.incrementAndGet();
                    if (n % PROGRESS_EVERY == 0) {
                        Platform.runLater(() -> activityLabel.setText(
                            "Analyzing... " + n + " files examined"));
                    }
                });
                Platform.runLater(() -> {
                    endActivity();
                    onAnalysisComplete(run);
                });
            } catch (IllegalArgumentException refused) {
                Platform.runLater(() -> {
                    endActivity();
                    showWarning(refused.getMessage());
                    statusLabel.setText("Refused: this folder cannot be cleaned.");
                    analyzeButton.setDisable(false);
                });
            } catch (Exception e) {
                log.error("Cleanup analysis failed", e);
                Platform.runLater(() -> {
                    endActivity();
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

    /** Review mode keeps its count-based confirmation: here the count is already known. */
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

        Alert confirm = new Alert(Alert.AlertType.WARNING);
        confirm.setTitle("Confirm permanent deletion");
        confirm.setHeaderText("Permanently delete " + files + " files?");
        confirm.setContentText(String.format(
            "Files: %d%nTotal size: %s%n%n"
            + "These files will be permanently deleted. They are NOT moved to the Recycle Bin "
            + "and this CANNOT be undone.",
            files, DataUnitFormatter.format(bytes)));
        ButtonType go = new ButtonType("Delete permanently", ButtonBar.ButtonData.OK_DONE);
        confirm.getButtonTypes().setAll(ButtonType.CANCEL, go);
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != go) {
            statusLabel.setText("Deletion cancelled. Nothing was removed.");
            return;
        }

        final CleanupRun run = currentRun;
        beginActivity("Deleting " + files + " files...");
        long startedAt = System.currentTimeMillis();
        new Thread(() -> {
            CleanupEngine.DeleteResult result = engine.delete(run, selected);
            Path report = null;
            try {
                report = reportWriter.write(run, result, null);
            } catch (IOException e) {
                log.error("Could not write cleanup report", e);
            }
            final Path reportFinal = report;
            final long elapsed = System.currentTimeMillis() - startedAt;
            Platform.runLater(() -> {
                endActivity();
                showStats(run.totalFiles(), result, null, reportFinal, elapsed);
                clearPreview();
            });
        }, "cleanup-delete").start();
    }

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

        Alert confirm = new Alert(Alert.AlertType.WARNING);
        confirm.setTitle("Confirm folder removal");
        confirm.setHeaderText("Remove " + empties.size() + " empty folders?");
        confirm.setContentText(previewOf(empties));
        ButtonType removeType = new ButtonType("Remove", ButtonBar.ButtonData.OK_DONE);
        confirm.getButtonTypes().setAll(ButtonType.CANCEL, removeType);
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != removeType) {
            statusLabel.setText("Cancelled. Nothing was removed.");
            return;
        }
        runPrune(selectedDirectory);
    }

    // ------------------------------------------------------------- activity

    @FXML
    private void onCancel() {
        engine.cancel();
        activityLabel.setText("Stopping...");
        cancelButton.setDisable(true);
    }

    private void beginActivity(String message) {
        engine.resetCancel();
        activityLabel.setText(message);
        activityBar.setProgress(-1.0);
        cancelButton.setDisable(false);
        activityBox.setVisible(true);
        activityBox.setManaged(true);
        modeTabs.setDisable(true);
        statusLabel.setText(message);
    }

    private void endActivity() {
        // Stop the indeterminate animation as well as hiding it: JavaFX keeps animating a
        // progress bar that is merely invisible, which burns a core for as long as the screen
        // stays open.
        activityBar.setProgress(0);
        activityBox.setVisible(false);
        activityBox.setManaged(false);
        modeTabs.setDisable(false);
    }

    // ---------------------------------------------------------------- stats

    private void showStats(int examined, CleanupEngine.DeleteResult result,
                           CleanupEngine.PruneResult pruned, Path report, long elapsedMillis) {
        statsGrid.getChildren().clear();
        statRow = 0;
        addStat("Files examined", String.valueOf(examined));
        addStat("Files deleted", String.valueOf(result.deletedCount()));
        addStat("Space freed", DataUnitFormatter.format(result.bytesDeleted()));
        if (pruned != null) {
            addStat("Empty folders removed", String.valueOf(pruned.removedCount()));
        }
        if (!result.getSkipped().isEmpty()) {
            addStat("Skipped (protected or changed)", String.valueOf(result.getSkipped().size()));
        }
        if (!result.getFailed().isEmpty()) {
            addStat("Failed to delete", String.valueOf(result.getFailed().size()));
        }
        addStat("Time taken", formatDuration(elapsedMillis));

        Map<String, Integer> byType = new TreeMap<>();
        for (CleanupCandidate c : result.getDeleted()) {
            byType.merge(c.getGroup().getDisplayName(), 1, Integer::sum);
        }
        if (!byType.isEmpty()) {
            addSeparatorRow();
            for (Map.Entry<String, Integer> e : byType.entrySet()) {
                addStat(e.getKey(), String.valueOf(e.getValue()));
            }
        }

        showResultPanel(report);
        statusLabel.setText(engine.isCancelled()
            ? "Stopped. " + result.deletedCount() + " files deleted before cancelling."
            : "Done. " + result.deletedCount() + " files deleted, "
              + DataUnitFormatter.format(result.bytesDeleted()) + " freed.");
    }

    private void addStat(String label, String value) {
        Label name = new Label(label);
        name.getStyleClass().add("subtle");
        Label figure = new Label(value);
        figure.setStyle("-fx-font-weight: bold;");
        statsGrid.add(name, 0, statRow);
        statsGrid.add(figure, 1, statRow);
        statRow++;
    }

    private void addSeparatorRow() {
        Separator sep = new Separator();
        GridPane.setColumnSpan(sep, 2);
        GridPane.setMargin(sep, new Insets(6, 0, 6, 0));
        statsGrid.add(sep, 0, statRow);
        statRow++;
    }

    private void showResultPanel(Path report) {
        resultReportLabel.setText(report != null ? "Report written to " + report : "");
        resultReportLabel.setManaged(report != null);
        resultReportLabel.setVisible(report != null);
        resultBox.setVisible(true);
        resultBox.setManaged(true);
    }

    private void hideResult() {
        resultBox.setVisible(false);
        resultBox.setManaged(false);
    }

    private static String formatDuration(long millis) {
        long seconds = millis / 1000;
        if (seconds < 60) return seconds + "s";
        return String.format("%d min %02d s", seconds / 60, seconds % 60);
    }

    // ----------------------------------------------------------------- utils

    private String previewOf(List<Path> paths) {
        StringBuilder preview = new StringBuilder();
        int shown = Math.min(paths.size(), 20);
        for (int i = 0; i < shown; i++) {
            preview.append(paths.get(i)).append(System.lineSeparator());
        }
        if (paths.size() > shown) {
            preview.append("... and ").append(paths.size() - shown).append(" more");
        }
        return preview.toString();
    }

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
