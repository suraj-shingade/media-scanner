package com.mediascanner.engine;

import com.mediascanner.engine.AppStateManager.AppState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppStateManagerTest {

    private AppStateManager manager;

    @BeforeEach
    void setUp() {
        manager = AppStateManager.getInstance();
        manager.stateProperty().set(AppState.IDLE);
        manager.setLastJobTargetPath(null);
    }

    @Test
    void initialStateIsIdle() {
        assertThat(manager.getState()).isEqualTo(AppState.IDLE);
    }

    @Test
    void setStateRunningUpdatesProperty() {
        manager.stateProperty().set(AppState.RUNNING);
        assertThat(manager.getState()).isEqualTo(AppState.RUNNING);
    }

    @Test
    void isJobActiveTrueForRunning() {
        manager.stateProperty().set(AppState.RUNNING);
        assertThat(manager.isJobActive.get()).isTrue();
    }

    @Test
    void isJobActiveTrueForPaused() {
        manager.stateProperty().set(AppState.PAUSED);
        assertThat(manager.isJobActive.get()).isTrue();
    }

    @Test
    void isJobActiveFalseForIdle() {
        manager.stateProperty().set(AppState.IDLE);
        assertThat(manager.isJobActive.get()).isFalse();
    }

    @Test
    void isJobActiveFalseForCompleted() {
        manager.stateProperty().set(AppState.COMPLETED);
        assertThat(manager.isJobActive.get()).isFalse();
    }

    @Test
    void hasCompletedJobTrueOnlyForCompleted() {
        for (AppState s : AppState.values()) {
            manager.stateProperty().set(s);
            boolean expected = s == AppState.COMPLETED;
            assertThat(manager.hasCompletedJob.get())
                .as("hasCompletedJob for state " + s)
                .isEqualTo(expected);
        }
    }

    @Test
    void lastJobTargetPathDefaultsToEmpty() {
        assertThat(manager.getLastJobTargetPath()).isEmpty();
    }

    @Test
    void setLastJobTargetPathUpdatesValue() {
        manager.setLastJobTargetPath("/some/path");
        assertThat(manager.getLastJobTargetPath()).isEqualTo("/some/path");
    }

    @Test
    void setLastJobTargetPathNullBecomesEmpty() {
        manager.setLastJobTargetPath(null);
        assertThat(manager.getLastJobTargetPath()).isEmpty();
    }
}
