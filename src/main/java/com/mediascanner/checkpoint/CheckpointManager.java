package com.mediascanner.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mediascanner.db.JobStatisticsDao;
import com.mediascanner.model.CheckpointState;
import com.mediascanner.model.Job;
import com.mediascanner.model.JobStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class CheckpointManager {

    private static final Logger log = LoggerFactory.getLogger(CheckpointManager.class);
    private static final long FILES_TRIGGER = 1_000;
    private static final long TIME_TRIGGER_SECS = 60;

    private final Job job;
    private final JobStatistics stats;
    private final JobStatisticsDao dao;
    private final Path jobsDir;
    private final ObjectMapper mapper;
    private final AtomicLong fileCounter = new AtomicLong(0);

    private ScheduledExecutorService scheduler;

    public CheckpointManager(Job job, JobStatistics stats, JobStatisticsDao dao, Path jobsDir) {
        this.job = job;
        this.stats = stats;
        this.dao = dao;
        this.jobsDir = jobsDir;
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "checkpoint-scheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::writeCheckpoint,
            TIME_TRIGGER_SECS, TIME_TRIGGER_SECS, TimeUnit.SECONDS);
        log.info("CheckpointManager started for job {}", job.getJobId());
    }

    public void stop() {
        writeCheckpoint();
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    public void onFileProcessed() {
        long count = fileCounter.incrementAndGet();
        if (count % FILES_TRIGGER == 0) {
            writeCheckpoint();
        }
    }

    public void writeCheckpoint() {
        try {
            Path jobDir = jobsDir.resolve(job.getJobId());
            Files.createDirectories(jobDir);
            Path tmpFile = jobDir.resolve("checkpoint.json.tmp");
            Path finalFile = jobDir.resolve("checkpoint.json");

            CheckpointState state = buildState();
            mapper.writeValue(tmpFile.toFile(), state);
            Files.move(tmpFile, finalFile, StandardCopyOption.ATOMIC_MOVE,
                       StandardCopyOption.REPLACE_EXISTING);

            dao.updateCounters(stats);
            log.debug("Checkpoint written for job {} at {} files",
                job.getJobId(), stats.getFilesProcessed());
        } catch (Exception e) {
            log.warn("Checkpoint write failed: {}", e.getMessage());
        }
    }

    private CheckpointState buildState() {
        CheckpointState state = new CheckpointState();
        state.setJobId(job.getJobId());
        state.setStatus(job.getStatus().name());
        state.setSourcePath(job.getSourcePath());
        state.setTargetPath(job.getTargetPath());
        state.setProcessedFiles(stats.getFilesProcessed());
        state.setFailedFiles(stats.getFilesFailed());
        state.setSkippedFiles(stats.getFilesSkipped());
        state.setEmptyFiles(stats.getEmptyFilesCount());
        state.setSmallFiles(stats.getSmallFilesCount());
        state.setCheckpointTime(Instant.now().toString());
        return state;
    }
}
