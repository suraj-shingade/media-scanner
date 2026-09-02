package com.mediascanner.ui;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import org.junit.jupiter.api.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Loads every FXML screen through a real {@link FXMLLoader}.
 *
 * <p>FXML binds by reflection at load time, so a renamed {@code fx:id}, a missing
 * {@code @FXML} field, a typo in an {@code onAction} handler, or a missing import is invisible to
 * the compiler and only shows up when a user opens that screen. This is the cheapest way to catch
 * all four.
 *
 * <p>Skips itself when no JavaFX toolkit can start (a headless machine with no virtual display)
 * rather than failing the build for an environment problem. CI runs it under xvfb on Linux.
 *
 * <p>Skipped outright on macOS. JavaFX there requires the toolkit to own the process main thread,
 * and starting it inside a Surefire fork does not throw — it takes the JVM down with a SIGSEGV,
 * which no try/catch can guard. FXML loading is parsing and reflection, so it is platform
 * independent; validating it on Linux and Windows covers the risk this test exists for.
 */
class FxmlLoadIT {

    private static boolean toolkitStarted;

    @BeforeAll
    static void startToolkit() {
        if (System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            toolkitStarted = false;
            return;
        }
        try {
            CountDownLatch latch = new CountDownLatch(1);
            Platform.startup(latch::countDown);
            toolkitStarted = latch.await(30, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyRunning) {
            toolkitStarted = true;
        } catch (Throwable t) {
            toolkitStarted = false;
        }
    }

    @AfterAll
    static void stopToolkit() {
        if (toolkitStarted) {
            try {
                Platform.exit();
            } catch (Throwable ignored) {
                // Nothing useful to do if the toolkit refuses to shut down in a test JVM.
            }
        }
    }

    private void assertLoads(String resource) throws Exception {
        assumeTrue(toolkitStarted,
            "JavaFX toolkit unavailable here (always skipped on macOS: see class javadoc)");

        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Object> loaded = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                FXMLLoader loader =
                    new FXMLLoader(FxmlLoadIT.class.getResource(resource));
                loaded.set(loader.load());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });

        assertThat(done.await(30, TimeUnit.SECONDS))
            .as("loading %s completed", resource).isTrue();
        if (failure.get() != null) {
            throw new AssertionError("Failed to load " + resource + ": " + failure.get(), failure.get());
        }
        assertThat(loaded.get()).as("%s produced a root node", resource).isNotNull();
    }

    @Test
    void testMainScreenLoads() throws Exception {
        assertLoads("/fxml/main.fxml");
    }

    @Test
    void testDashboardLoads() throws Exception {
        assertLoads("/fxml/dashboard.fxml");
    }

    @Test
    void testSummaryLoads() throws Exception {
        assertLoads("/fxml/summary.fxml");
    }

    @Test
    void testJobHistoryLoads() throws Exception {
        assertLoads("/fxml/job-history.fxml");
    }

    @Test
    void testPreferencesLoads() throws Exception {
        assertLoads("/fxml/preferences.fxml");
    }

    @Test
    void testAboutLoads() throws Exception {
        assertLoads("/fxml/about.fxml");
    }

    @Test
    void testShortcutsLoads() throws Exception {
        assertLoads("/fxml/shortcuts.fxml");
    }
}
