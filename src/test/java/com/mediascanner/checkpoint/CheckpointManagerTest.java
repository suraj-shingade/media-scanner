package com.mediascanner.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediascanner.db.Database;
import com.mediascanner.db.JobStatisticsDao;
import com.mediascanner.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

class CheckpointManagerTest {

    @TempDir
    Path tempDir;

    static Path dbPath;
    static Database db;
    static JobStatisticsDao dao;

    @BeforeAll
    static void setUpDb() throws Exception {
        dbPath = Files.createTempDirectory("checkpoint-test-db").resolve("test.db");
        db = new Database(dbPath);
        dao = new JobStatisticsDao(db);
    }

    @AfterAll
    static void tearDownDb() throws Exception {
        if (db != null) db.close();
        Files.deleteIfExists(dbPath);
        Files.deleteIfExists(dbPath.getParent());
    }

    private Job buildJob(String suffix) {
        return Job.create("/source" + suffix, "/target" + suffix,
            Job.TransferMode.COPY, Job.FolderPattern.YYYY_MMM,
            Job.DuplicatePolicy.SKIP, 10, 100, 4, false,
            java.util.List.of());
    }

    @Test
    void testCheckpointWrittenAt1000FileTrigger() throws Exception {
        Job job = buildJob("_1k");
        JobStatistics stats = new JobStatistics(job.getJobId(), LocalDateTime.now());
        dao.insert(stats);
        CheckpointManager manager = new CheckpointManager(job, stats, dao, tempDir);
        manager.start();

        for (int i = 0; i < 1000; i++) {
            manager.onFileProcessed();
        }
        Thread.sleep(200);

        Path checkpointFile = tempDir.resolve(job.getJobId()).resolve("checkpoint.json");
        assertThat(Files.exists(checkpointFile)).isTrue();
        manager.stop();
    }

    @Test
    void testCheckpointJsonSchemaMatchesContract() throws Exception {
        Job job = buildJob("_schema");
        JobStatistics stats = new JobStatistics(job.getJobId(), LocalDateTime.now());
        dao.insert(stats);
        stats.setFilesProcessed(500);
        stats.setFilesFailed(10);
        stats.setFilesSkipped(20);
        CheckpointManager manager = new CheckpointManager(job, stats, dao, tempDir);
        manager.writeCheckpoint();

        Path checkpointFile = tempDir.resolve(job.getJobId()).resolve("checkpoint.json");
        assertThat(Files.exists(checkpointFile)).isTrue();

        ObjectMapper mapper = new ObjectMapper();
        CheckpointState state = mapper.readValue(checkpointFile.toFile(), CheckpointState.class);
        assertThat(state.getJobId()).isEqualTo(job.getJobId());
        assertThat(state.getSourcePath()).isEqualTo("/source_schema");
        assertThat(state.getTargetPath()).isEqualTo("/target_schema");
        assertThat(state.getProcessedFiles()).isEqualTo(500);
        assertThat(state.getFailedFiles()).isEqualTo(10);
        assertThat(state.getSkippedFiles()).isEqualTo(20);
        assertThat(state.getCheckpointTime()).isNotNull();
    }

    @Test
    void testAtomicWriteNoTmpFileLeft() throws Exception {
        Job job = buildJob("_atomic");
        JobStatistics stats = new JobStatistics(job.getJobId(), LocalDateTime.now());
        dao.insert(stats);
        CheckpointManager manager = new CheckpointManager(job, stats, dao, tempDir);
        manager.writeCheckpoint();

        Path jobDir = tempDir.resolve(job.getJobId());
        boolean tmpExists = Files.walk(jobDir)
            .anyMatch(p -> p.getFileName().toString().endsWith(".tmp"));
        assertThat(tmpExists).isFalse();
    }
}
