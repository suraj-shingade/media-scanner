package com.mediascanner.app;

import com.mediascanner.config.AppConfig;
import com.mediascanner.db.Database;
import com.mediascanner.engine.AppStateManager;
import com.mediascanner.ui.DarkModeManager;
import com.mediascanner.ui.MenuBarController;
import com.mediascanner.ui.ScreenNavigator;
import com.mediascanner.ui.ScreenNavigator.ScreenType;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class MediaScannerApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(MediaScannerApp.class);

    // Shared singletons accessible to controllers via static getters
    private static AppConfig appConfig;
    private static AppStateManager appStateManager;
    private static ScreenNavigator screenNavigator;
    private static DarkModeManager darkModeManager;
    private static MenuBarController menuBarController;
    private static Stage primaryStage;
    /**
     * Opened asynchronously by MainController on startup. Screens that need it (Job History,
     * a stored job summary) read it from here rather than each opening their own connection.
     */
    private static volatile Database database;

    @Override
    public void start(Stage stage) {
        primaryStage = stage;
        log.info("MediaScanner starting");

        appConfig = new AppConfig();
        appStateManager = AppStateManager.getInstance();
        BorderPane root = new BorderPane();

        screenNavigator = new ScreenNavigator(root);
        darkModeManager = new DarkModeManager(appConfig);

        menuBarController = new MenuBarController(
            appStateManager, screenNavigator, appConfig, darkModeManager, stage);
        root.setTop(menuBarController.getMenuBar());

        Scene scene = new Scene(root, 1200, 800);
        darkModeManager.setScene(scene);
        darkModeManager.apply(appConfig.isDarkMode());

        stage.setTitle("MediaScanner");
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(600);

        stage.setOnCloseRequest(e -> {
            e.consume();
            handleQuit();
        });

        screenNavigator.navigateTo(ScreenType.CONFIGURATION);

        stage.show();
        log.info("MediaScanner UI ready");
    }

    public static void handleQuit() {
        if (appStateManager.isJobActive.get()) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Quit MediaScanner");
            alert.setHeaderText("A scan is running. Stop and quit?");
            alert.getButtonTypes().setAll(
                new ButtonType("Stop & Quit"), ButtonType.CANCEL);
            Optional<ButtonType> result = alert.showAndWait();
            if (result.isPresent() && result.get().getText().equals("Stop & Quit")) {
                Platform.exit();
            }
        } else {
            Platform.exit();
        }
    }

    public static Database getDatabase() { return database; }
    public static void setDatabase(Database db) { database = db; }

    public static AppConfig getAppConfig() { return appConfig; }
    public static AppStateManager getAppStateManager() { return appStateManager; }
    public static ScreenNavigator getScreenNavigator() { return screenNavigator; }
    public static DarkModeManager getDarkModeManager() { return darkModeManager; }
    public static MenuBarController getMenuBarController() { return menuBarController; }
    public static Stage getPrimaryStage() { return primaryStage; }

    public static void main(String[] args) {
        launch(args);
    }
}
