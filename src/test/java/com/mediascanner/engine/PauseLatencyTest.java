package com.mediascanner.engine;

import com.mediascanner.config.AppConfig;
import com.mediascanner.db.Database;
import com.mediascanner.model.*;
import com.mediascanner.monitor.ProgressTracker;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

class PauseLatencyTest {

    @TempDir Path sourceDir;
    @TempDir Path targetDir;

    Database db;
    Path dbPath;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = Files.createTempDirectory("pause-test-db").resolve("test.db");
        db = new Database(dbPath);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) db.close();
        Files.deleteIfExists(dbPath);
        Files.deleteIfExists(dbPath.getParent());
    }

    @Test
    void testPauseRequestFlagSetImmediately() throws Exception {
        AppConfig config = new AppConfig();
        ProgressTracker tracker = new ProgressTracker();
        ScanEngine engine = new ScanEngine(config, db, tracker);

        assertThat(engine.isPauseRequested()).isFalse();
        engine.pause();
        assertThat(engine.isPauseRequested()).isTrue();
        engine.resume();
        assertThat(engine.isPauseRequested()).isFalse();
    }

    @Test
    void testStopRequestFlagSetImmediately() throws Exception {
        AppConfig config = new AppConfig();
        ProgressTracker tracker = new ProgressTracker();
        ScanEngine engine = new ScanEngine(config, db, tracker);

        assertThat(engine.isStopRequested()).isFalse();
        engine.stop();
        assertThat(engine.isStopRequested()).isTrue();
    }
}
