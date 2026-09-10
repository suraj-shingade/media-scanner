package com.mediascanner.ui;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.layout.BorderPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class ScreenNavigator {

    public enum ScreenType {
        CONFIGURATION, DASHBOARD, SUMMARY, JOB_HISTORY, CLEANUP, ARCHIVE_INTEGRITY
    }

    private static final Logger log = LoggerFactory.getLogger(ScreenNavigator.class);

    private final BorderPane rootPane;
    private Object currentController;

    public ScreenNavigator(BorderPane rootPane) {
        this.rootPane = rootPane;
    }

    /**
     * The dashboard is kept alive across navigations while a job is running.
     *
     * <p>Every other screen is cheap to rebuild from scratch. The dashboard is not: it owns the live
     * throughput history, the refresh timeline and the resource monitor, and reloading its FXML built
     * a second, uninitialised controller — {@code init} is only called when a job <em>starts</em>, so
     * View → Dashboard mid-job produced a blank chart while the previous controller's timeline kept
     * firing forever against a node no longer in the scene.
     */
    private Parent dashboardView;
    private Object dashboardController;

    /**
     * Discards the retained dashboard so the next job starts from an empty chart.
     *
     * <p>This is what actually resets the graphs, and it is deliberately tied to starting a job
     * rather than to opening the screen: navigating away and back must not erase the history of a run
     * still in progress.
     */
    public void resetDashboard() {
        if (dashboardController instanceof DashboardController dc) {
            dc.shutdown();
        }
        dashboardView = null;
        dashboardController = null;
    }

    public Object navigateTo(ScreenType screen) {
        if (screen == ScreenType.DASHBOARD && dashboardView != null) {
            rootPane.setCenter(dashboardView);
            currentController = dashboardController;
            return dashboardController;
        }

        String fxml = switch (screen) {
            case CONFIGURATION -> "/fxml/main.fxml";
            case DASHBOARD -> "/fxml/dashboard.fxml";
            case SUMMARY -> "/fxml/summary.fxml";
            case JOB_HISTORY -> "/fxml/job-history.fxml";
            case CLEANUP -> "/fxml/cleanup.fxml";
            case ARCHIVE_INTEGRITY -> "/fxml/integrity.fxml";
        };

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxml));
            Parent view = loader.load();
            currentController = loader.getController();
            if (screen == ScreenType.DASHBOARD) {
                dashboardView = view;
                dashboardController = currentController;
            }
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
