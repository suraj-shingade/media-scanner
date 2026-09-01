package com.mediascanner.ui;

import com.mediascanner.checkpoint.JobStateExporter;
import com.mediascanner.config.AppConfig;
import com.mediascanner.db.HashIndexDao;
import com.mediascanner.engine.AppStateManager;
import com.mediascanner.engine.AppStateManager.AppState;
import com.mediascanner.engine.ScanEngine;
import com.mediascanner.model.CheckpointState;
import com.mediascanner.ui.ScreenNavigator.ScreenType;
import javafx.application.Platform;
import javafx.beans.binding.BooleanBinding;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class MenuBarController {

    private static final Logger log = LoggerFactory.getLogger(MenuBarController.class);

    private final AppStateManager appStateManager;
    private final ScreenNavigator screenNavigator;
    private final AppConfig appConfig;
    private final DarkModeManager darkModeManager;
    private final Stage primaryStage;

    private final MenuBar menuBar;

    // Job menu items that need state bindings
    private MenuItem miStartScan;
    private MenuItem miPause;
    private MenuItem miResume;
    private MenuItem miStop;
    private MenuItem miExportJobState;

    // View menu items
    private MenuItem miDashboard;
    private MenuItem miSummary;
    private CheckMenuItem miDarkMode;

    // Tools menu items
    private MenuItem miClearHashCache;

    // Reference to running scan engine (set by DashboardController)
    private ScanEngine scanEngineRef;
    private HashIndexDao hashIndexDao;

    public MenuBarController(AppStateManager appStateManager, ScreenNavigator screenNavigator,
                             AppConfig appConfig, DarkModeManager darkModeManager,
                             Stage primaryStage) {
        this.appStateManager = appStateManager;
        this.screenNavigator = screenNavigator;
        this.appConfig = appConfig;
        this.darkModeManager = darkModeManager;
        this.primaryStage = primaryStage;

        this.menuBar = buildMenuBar();
        wireStateBindings();
        registerMacOSHandlers();
    }

    public MenuBar getMenuBar() {
        return menuBar;
    }

    public void setScanEngine(ScanEngine engine) {
        this.scanEngineRef = engine;
    }

    public void setHashIndexDao(HashIndexDao dao) {
        this.hashIndexDao = dao;
    }

    // -------------------------------------------------------------------------
    // Menu construction
    // -------------------------------------------------------------------------

    private MenuBar buildMenuBar() {
        MenuBar bar = new MenuBar();

        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            bar.setUseSystemMenuBar(true);
        }

        bar.getMenus().addAll(
            buildFileMenu(),
            buildEditMenu(),
            buildJobMenu(),
            buildViewMenu(),
            buildToolsMenu(),
            buildHelpMenu()
        );

        return bar;
    }

    private Menu buildFileMenu() {
        Menu menu = new Menu("File");

        MenuItem miNewScan = new MenuItem("New Scan");
        miNewScan.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("shortcut+N"));
        miNewScan.setOnAction(e -> onNewScan());

        MenuItem miOpenJobState = new MenuItem("Open Job State…");
        miOpenJobState.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("shortcut+O"));
        miOpenJobState.setOnAction(e -> onOpenJobState());

        String os = System.getProperty("os.name", "").toLowerCase();
        String quitLabel = os.contains("mac") ? "Quit MediaScanner" : "Quit";
        MenuItem miQuit = new MenuItem(quitLabel);
        miQuit.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("shortcut+Q"));
        miQuit.setOnAction(e -> onQuit());

        menu.getItems().addAll(miNewScan, miOpenJobState, new SeparatorMenuItem(), miQuit);
        return menu;
    }

    private Menu buildEditMenu() {
        Menu menu = new Menu("Edit");

        MenuItem miPreferences = new MenuItem("Preferences…");
        miPreferences.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("shortcut+COMMA"));
        miPreferences.setOnAction(e -> onPreferences());

        menu.getItems().add(miPreferences);
        return menu;
    }

    private Menu buildJobMenu() {
        Menu menu = new Menu("Job");

        miStartScan = new MenuItem("Start Scan");
        miStartScan.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("shortcut+ENTER"));
        miStartScan.setOnAction(e -> onStartScan());

        miPause = new MenuItem("Pause");
        miPause.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("shortcut+P"));
        miPause.setOnAction(e -> onPause());

        miResume = new MenuItem("Resume");
        miResume.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("shortcut+R"));
        miResume.setOnAction(e -> onResume());

        miStop = new MenuItem("Stop");
        miStop.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("shortcut+PERIOD"));
        miStop.setOnAction(e -> onStop());

        miExportJobState = new MenuItem("Export Job State…");
        miExportJobState.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("shortcut+E"));
        miExportJobState.setOnAction(e -> onExportJobState());

        menu.getItems().addAll(
            miStartScan, miPause, miResume, miStop,
            new SeparatorMenuItem(), miExportJobState);
        return menu;
    }

    private Menu buildViewMenu() {
        Menu menu = new Menu("View");

        MenuItem miConfiguration = new MenuItem("Configuration");
        miConfiguration.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("shortcut+1"));
        miConfiguration.setOnAction(e -> onViewConfiguration());

        miDashboard = new MenuItem("Dashboard");
        miDashboard.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("shortcut+2"));
        miDashboard.setOnAction(e -> onViewDashboard());

        miSummary = new MenuItem("Summary");
        miSummary.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("shortcut+3"));
        miSummary.setOnAction(e -> onViewSummary());

        MenuItem miJobHistory = new MenuItem("Job History");
        miJobHistory.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("shortcut+4"));
        miJobHistory.setOnAction(e -> onViewJobHistory());

        miDarkMode = new CheckMenuItem("Toggle Dark Mode");
        miDarkMode.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("shortcut+D"));
        miDarkMode.setOnAction(e -> onToggleDarkMode());

        menu.getItems().addAll(
            miConfiguration, miDashboard, miSummary, miJobHistory,
            new SeparatorMenuItem(), miDarkMode);
        return menu;
    }

    private Menu buildToolsMenu() {
        Menu menu = new Menu("Tools");

        MenuItem miViewFailureReport = new MenuItem("View Failure Report");
        miViewFailureReport.setOnAction(e -> onViewFailureReport());

        MenuItem miOpenLogFile = new MenuItem("Open Log File");
        miOpenLogFile.setOnAction(e -> onOpenLogFile());

        miClearHashCache = new MenuItem("Clear Hash Cache…");
        miClearHashCache.setOnAction(e -> onClearHashCache());

        menu.getItems().addAll(
            miViewFailureReport, miOpenLogFile,
            new SeparatorMenuItem(), miClearHashCache);
        return menu;
    }

    private Menu buildHelpMenu() {
        Menu menu = new Menu("Help");

        MenuItem miAbout = new MenuItem("About MediaScanner");
        miAbout.setOnAction(e -> onAbout());

        MenuItem miUserGuide = new MenuItem("Open User Guide");
        miUserGuide.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("F1"));
        miUserGuide.setOnAction(e -> onOpenUserGuide());

        MenuItem miShortcuts = new MenuItem("Keyboard Shortcuts");
        miShortcuts.setOnAction(e -> onKeyboardShortcuts());

        menu.getItems().addAll(miAbout, miUserGuide, new SeparatorMenuItem(), miShortcuts);
        return menu;
    }

    // -------------------------------------------------------------------------
    // State bindings (T010)
    // -------------------------------------------------------------------------

    private void wireStateBindings() {
        var state = appStateManager.stateProperty();

        // Start: enabled when IDLE or COMPLETED
        BooleanBinding startDisabled = state.isNotEqualTo(AppState.IDLE)
            .and(state.isNotEqualTo(AppState.COMPLETED));
        miStartScan.disableProperty().bind(startDisabled);

        // Pause: enabled only when RUNNING
        miPause.disableProperty().bind(state.isNotEqualTo(AppState.RUNNING));

        // Resume: enabled only when PAUSED
        miResume.disableProperty().bind(state.isNotEqualTo(AppState.PAUSED));

        // Stop: enabled when RUNNING or PAUSED
        BooleanBinding stopDisabled = state.isNotEqualTo(AppState.RUNNING)
            .and(state.isNotEqualTo(AppState.PAUSED));
        miStop.disableProperty().bind(stopDisabled);

        // Export: enabled when RUNNING, PAUSED, or COMPLETED
        BooleanBinding exportDisabled = state.isNotEqualTo(AppState.RUNNING)
            .and(state.isNotEqualTo(AppState.PAUSED))
            .and(state.isNotEqualTo(AppState.COMPLETED));
        miExportJobState.disableProperty().bind(exportDisabled);

        // Dashboard: enabled when RUNNING, PAUSED, or COMPLETED
        miDashboard.disableProperty().bind(exportDisabled);

        // Summary: enabled only when COMPLETED
        miSummary.disableProperty().bind(state.isNotEqualTo(AppState.COMPLETED));

        // Clear cache: disabled when job is active
        miClearHashCache.disableProperty().bind(appStateManager.isJobActive);

        // Dark mode checkmark reflects persisted preference
        miDarkMode.setSelected(appConfig.isDarkMode());
    }

    // -------------------------------------------------------------------------
    // macOS native menu integration (T025, T053, T056)
    // -------------------------------------------------------------------------

    private void registerMacOSHandlers() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("mac")) return;
        try {
            Desktop desktop = Desktop.getDesktop();
            if (Desktop.isDesktopSupported() && desktop.isSupported(Desktop.Action.APP_ABOUT)) {
                desktop.setAboutHandler(e -> Platform.runLater(this::onAbout));
            }
            if (Desktop.isDesktopSupported() && desktop.isSupported(Desktop.Action.APP_PREFERENCES)) {
                desktop.setPreferencesHandler(e -> Platform.runLater(this::onPreferences));
            }
        } catch (Exception e) {
            log.warn("macOS handler registration failed (non-fatal): {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // File menu handlers (US1)
    // -------------------------------------------------------------------------

    public void onNewScan() {
        screenNavigator.navigateTo(ScreenType.CONFIGURATION);
    }

    public void onOpenJobState() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open Job State");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("JSON Files", "*.json"));
        File file = chooser.showOpenDialog(primaryStage);
        if (file == null) return;

        try {
            JobStateExporter exporter = new JobStateExporter();
            CheckpointState state = exporter.importFrom(Paths.get(file.getAbsolutePath()));
            if (state != null) {
                Object ctrl = screenNavigator.navigateTo(ScreenType.CONFIGURATION);
                if (ctrl instanceof MainController mc) {
                    mc.loadCheckpoint(state);
                }
            } else {
                showError("Open Job State", "Could not import: paths not accessible.");
            }
        } catch (Exception e) {
            showError("Open Job State", e.getMessage());
        }
    }

    public void onQuit() {
        com.mediascanner.app.MediaScannerApp.handleQuit();
    }

    // -------------------------------------------------------------------------
    // Edit menu handlers (US2)
    // -------------------------------------------------------------------------

    public void onPreferences() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/preferences.fxml"));
            Parent root = loader.load();
            PreferencesController ctrl = loader.getController();
            ctrl.init(appConfig);

            Stage dialog = new Stage();
            dialog.setTitle("Preferences");
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.initOwner(primaryStage);
            dialog.setScene(new Scene(root));
            dialog.setResizable(false);
            dialog.showAndWait();
        } catch (Exception e) {
            log.error("Failed to open Preferences: {}", e.getMessage());
            showError("Preferences", "Could not open Preferences: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Job menu handlers (US3)
    // -------------------------------------------------------------------------

    public void onStartScan() {
        Object ctrl = screenNavigator.navigateTo(ScreenType.CONFIGURATION);
        if (ctrl instanceof MainController mc) {
            mc.triggerStartScan();
        }
    }

    public void onPause() {
        if (scanEngineRef != null) scanEngineRef.pause();
    }

    public void onResume() {
        if (scanEngineRef != null) scanEngineRef.resume();
    }

    public void onStop() {
        if (scanEngineRef != null) scanEngineRef.stop();
    }

    public void onExportJobState() {
        if (scanEngineRef == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Job State");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("JSON Files", "*.json"));
        chooser.setInitialFileName("job-state.json");
        File file = chooser.showSaveDialog(primaryStage);
        if (file == null) return;

        try {
            JobStateExporter exporter = new JobStateExporter();
            CheckpointState state = new CheckpointState();
            var job = scanEngineRef.getCurrentJob();
            if (job != null) {
                state.setJobId(job.getJobId());
                state.setSourcePath(job.getSourcePath());
                state.setTargetPath(job.getTargetPath());
            }
            var stats = scanEngineRef.getJobStatistics();
            if (stats != null) {
                state.setProcessedFiles(stats.getFilesProcessed());
                state.setFailedFiles(stats.getFilesFailed());
                state.setSkippedFiles(stats.getFilesSkipped());
            }
            exporter.export(state, Paths.get(file.getAbsolutePath()));
            log.info("Job state exported to {}", file.getAbsolutePath());
        } catch (Exception e) {
            log.error("Export failed: {}", e.getMessage());
            showError("Export Job State", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // View menu handlers (US4)
    // -------------------------------------------------------------------------

    public void onViewConfiguration() {
        screenNavigator.navigateTo(ScreenType.CONFIGURATION);
    }

    public void onViewDashboard() {
        screenNavigator.navigateTo(ScreenType.DASHBOARD);
    }

    public void onViewSummary() {
        screenNavigator.navigateTo(ScreenType.SUMMARY);
    }

    public void onViewJobHistory() {
        screenNavigator.navigateTo(ScreenType.JOB_HISTORY);
    }

    public void onToggleDarkMode() {
        darkModeManager.toggle();
        miDarkMode.setSelected(appConfig.isDarkMode());
    }

    // -------------------------------------------------------------------------
    // Tools menu handlers (US5)
    // -------------------------------------------------------------------------

    public void onViewFailureReport() {
        String targetPath = appStateManager.getLastJobTargetPath();
        if (targetPath == null || targetPath.isBlank()) {
            showInfo("View Failure Report", "No failure report found for the last job.");
            return;
        }
        Path reportFile = Paths.get(targetPath, "_failures", "failure-report.json");
        if (Files.exists(reportFile)) {
            try {
                Desktop.getDesktop().open(reportFile.toFile());
            } catch (Exception e) {
                log.error("Cannot open failure report: {}", e.getMessage());
                showError("View Failure Report", "Cannot open failure report: " + e.getMessage());
            }
        } else {
            showInfo("View Failure Report", "No failure report found for the last job.");
        }
    }

    public void onOpenLogFile() {
        Path logFile = Paths.get(System.getProperty("user.home"), ".mediascanner", "logs", "mediascanner.log");
        if (Files.exists(logFile)) {
            try {
                Desktop.getDesktop().open(logFile.toFile());
            } catch (Exception e) {
                log.error("Cannot open log file: {}", e.getMessage());
                showError("Open Log File", "Cannot open log file: " + e.getMessage());
            }
        } else {
            showInfo("Open Log File", "No log file found. Run a scan first.");
        }
    }

    public void onClearHashCache() {
        if (hashIndexDao == null) {
            showInfo("Clear Hash Cache", "Hash cache is not available (no database connection).");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Clear Hash Cache");
        confirm.setHeaderText("Clear the hash cache?");
        confirm.setContentText("All files will be re-hashed on the next scan.");
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            try {
                hashIndexDao.clearAll();
                showInfo("Clear Hash Cache", "Hash cache cleared.");
            } catch (Exception e) {
                log.error("Failed to clear hash cache: {}", e.getMessage());
                showError("Clear Hash Cache", "Failed to clear hash cache: " + e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Help menu handlers (US6)
    // -------------------------------------------------------------------------

    public void onAbout() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/about.fxml"));
            Parent root = loader.load();
            AboutController ctrl = loader.getController();
            ctrl.init(appConfig);

            Stage dialog = new Stage();
            dialog.setTitle("About MediaScanner");
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.initOwner(primaryStage);
            dialog.setScene(new Scene(root));
            dialog.setResizable(false);
            dialog.showAndWait();
        } catch (Exception e) {
            log.error("Failed to open About dialog: {}", e.getMessage());
        }
    }

    public void onOpenUserGuide() {
        var url = getClass().getResource("/docs/user-guide.html");
        if (url != null) {
            try {
                Desktop.getDesktop().browse(url.toURI());
            } catch (Exception e) {
                log.error("Cannot open user guide: {}", e.getMessage());
                showInfo("User Guide", "Could not open user guide: " + e.getMessage());
            }
        } else {
            showInfo("User Guide", "User guide not found in this build.");
        }
    }

    public void onKeyboardShortcuts() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/shortcuts.fxml"));
            Parent root = loader.load();
            Stage dialog = new Stage();
            dialog.setTitle("Keyboard Shortcuts");
            dialog.initOwner(primaryStage);
            dialog.setScene(new Scene(root));
            dialog.show();
        } catch (Exception e) {
            log.error("Failed to open Shortcuts dialog: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
