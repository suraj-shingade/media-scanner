package com.mediascanner.ui;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.layout.BorderPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class ScreenNavigator {

    public enum ScreenType {
        CONFIGURATION, DASHBOARD, SUMMARY, JOB_HISTORY
    }

    private static final Logger log = LoggerFactory.getLogger(ScreenNavigator.class);

    private final BorderPane rootPane;
    private Object currentController;

    public ScreenNavigator(BorderPane rootPane) {
        this.rootPane = rootPane;
    }

    public Object navigateTo(ScreenType screen) {
        String fxml = switch (screen) {
            case CONFIGURATION -> "/fxml/main.fxml";
            case DASHBOARD -> "/fxml/dashboard.fxml";
            case SUMMARY -> "/fxml/summary.fxml";
            case JOB_HISTORY -> "/fxml/job-history.fxml";
        };

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxml));
            Parent view = loader.load();
            currentController = loader.getController();
            rootPane.setCenter(view);
            return currentController;
        } catch (IOException e) {
            log.error("Failed to navigate to {}: {}", screen, e.getMessage());
            return null;
        }
    }

    public Object getCurrentController() {
        return currentController;
    }
}
