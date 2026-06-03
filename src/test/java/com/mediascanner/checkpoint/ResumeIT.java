package com.mediascanner.checkpoint;

import com.mediascanner.db.Database;
import com.mediascanner.db.JobStatisticsDao;
import com.mediascanner.model.JobStatistics;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

class ResumeIT {

    static Path testDbPath;
    static Database db;
    static JobStatisticsDao dao;

    @BeforeAll
    static void setUp() throws Exception {
        testDbPath = Files.createTempDirectory("mediascanner-resume").resolve("test.db");
        db = new Database(testDbPath);
        dao = new JobStatisticsDao(db);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (db != null) db.close();
        Files.deleteIfExists(testDbPath);
        Files.deleteIfExists(testDbPath.getParent());
    }

    @Test
    void testActiveJobFoundAfterPause() throws Exception {
        JobStatistics stats = new JobStatistics("JOB-20240115-001", LocalDateTime.now());
        stats.setStatus("RUNNING");
        dao.insert(stats);

        stats.setFilesProcessed(500);
        stats.setStatus("PAUSED");
        dao.updateCounters(stats);

        JobStatistics active = dao.findActiveJob();
        assertThat(active).isNotNull();
        assertThat(active.getJobId()).isEqualTo("JOB-20240115-001");
        assertThat(active.getFilesProcessed()).isEqualTo(500);
        assertThat(active.getStatus()).isEqualTo("PAUSED");
    }

    @Test
    void testNoActiveJobAfterCompletion() throws Exception {
        dao.markCompleted("JOB-20240115-001", LocalDateTime.now());
        JobStatistics active = dao.findActiveJob();
        assertThat(active).isNull();
    }

    @Test
    void testCheckpointLoadedAfterReopen() throws Exception {
        String jobId = "JOB-20240116-001";
        JobStatistics stats = new JobStatistics(jobId, LocalDateTime.now());
        dao.insert(stats);
        stats.setFilesProcessed(1000);
        stats.setStatus("RUNNING");
        dao.updateCounters(stats);

        db.close();
        db = new Database(testDbPath);
        dao = new JobStatisticsDao(db);

        // Find the specific job by ID to avoid ordering ambiguity with other active jobs
        JobStatistics loaded = dao.findById(jobId);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getFilesProcessed()).isEqualTo(1000);
        assertThat(loaded.getStatus()).isEqualTo("RUNNING");
    }
}
