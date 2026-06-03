package com.mediascanner.engine;

import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class AppStateManager {

    public enum AppState {
        IDLE, RUNNING, PAUSED, COMPLETED
    }

    private static final AppStateManager INSTANCE = new AppStateManager();

    private final ObjectProperty<AppState> state =
        new SimpleObjectProperty<>(AppState.IDLE);
    private final StringProperty lastJobTargetPath = new SimpleStringProperty("");

    public final BooleanBinding isJobActive =
        state.isEqualTo(AppState.RUNNING).or(state.isEqualTo(AppState.PAUSED));
    public final BooleanBinding hasCompletedJob =
        state.isEqualTo(AppState.COMPLETED);

    private AppStateManager() {}

    public static AppStateManager getInstance() {
        return INSTANCE;
    }

    public void setState(AppState newState) {
        try {
            if (javafx.application.Platform.isFxApplicationThread()) {
                state.set(newState);
            } else {
                javafx.application.Platform.runLater(() -> state.set(newState));
            }
        } catch (IllegalStateException e) {
            // FX toolkit not initialized (e.g. unit test context) — set directly
            state.set(newState);
        }
    }

    public AppState getState() {
        return state.get();
    }

    public ObjectProperty<AppState> stateProperty() {
        return state;
    }

    public String getLastJobTargetPath() {
        return lastJobTargetPath.get();
    }

    public void setLastJobTargetPath(String path) {
        lastJobTargetPath.set(path != null ? path : "");
    }

    public StringProperty lastJobTargetPathProperty() {
        return lastJobTargetPath;
    }
}
