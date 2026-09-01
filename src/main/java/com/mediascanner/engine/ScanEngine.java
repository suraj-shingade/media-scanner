package com.mediascanner.engine;

import com.mediascanner.checkpoint.CheckpointManager;
import com.mediascanner.config.AppConfig;
import com.mediascanner.db.Database;
import com.mediascanner.db.HashIndexDao;
import com.mediascanner.db.JobStatisticsDao;
import com.mediascanner.model.*;
import com.mediascanner.monitor.ProgressTracker;
import com.mediascanner.monitor.ResourceMonitor;
import com.mediascanner.monitor.ThroughputHistory;
import com.mediascanner.model.ThroughputSample;
import com.mediascanner.report.JobEventRecorder;
import com.mediascanner.report.JobReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

public class ScanEngine {

    private static final Logger log = LoggerFactory.getLogger(ScanEngine.class);
    private static final int RECENT_FILES_LIMIT = 8;

    /**
     * Pending tasks allowed per worker thread. The queue is bounded so that walking a 10M-file
     * source tree cannot outrun the workers and exhaust the heap; when it fills, the producer
     * thread runs the task itself (CallerRunsPolicy), which throttles the walk naturally.
     */
    private static final int QUEUE_DEPTH_PER_THREAD = 64;

    private final AppConfig config;
    private final Database database;
    private final HashIndexDao hashIndexDao;
    private final JobStatisticsDao jobStatisticsDao;
    private final ProgressTracker progressTracker;

    private ThreadPoolExecutor workerPool;
    private volatile boolean pauseRequested = false;
    private volatile boolean stopRequested = false;

    private Job currentJob;
    private JobStatistics jobStatistics;

    // Live activity visible to the dashboard
    private final AtomicReference<String> currentFilePath = new AtomicReference<>("");
    private final ConcurrentLinkedDeque<String> recentFiles = new ConcurrentLinkedDeque<>();

    /** Destination folders already created this run. Shared across workers, so it must be concurrent. */
    private final Set<String> destFolderCache = ConcurrentHashMap.newKeySet();

    /** Files an earlier run already placed in the archive; the measure of what a resume saved. */
    private final java.util.concurrent.atomic.AtomicLong filesAlreadyPresent =
        new java.util.concurrent.atomic.AtomicLong();

    private final JobReportService reportService;
    private final ThroughputHistory throughputHistory = new ThroughputHistory();
    private JobEventRecorder recorder;
    private ResourceMonitor resourceMonitor;
    private ScheduledExecutorService samplerPool;

    public ScanEngine(AppConfig config, Database database, ProgressTracker progressTracker) {
        this.config = config;
        this.database = database;
        this.hashIndexDao = new HashIndexDao(database);
        this.jobStatisticsDao = new JobStatisticsDao(database);
        this.progressTracker = progressTracker;
        this.reportService = new JobReportService(database);
    }

    public void start(Job job) throws IOException, SQLException {
        this.currentJob = job;
        this.pauseRequested = false;
        this.stopRequested = false;
        AppStateManager.getInstance().setState(AppStateManager.AppState.RUNNING);

        int threadCount = job.getWorkerThreadCount() > 0
            ? job.getWorkerThreadCount() : config.getWorkerThreadCount();
        workerPool = newWorkerPool(threadCount);
        log.info("ScanEngine starting job {} with {} threads", job.getJobId(), threadCount);

        applyHighPriorityMode(job);

        jobStatistics = new JobStatistics(job.getJobId(), job.getStartTime());
        jobStatisticsDao.insert(jobStatistics);

        destFolderCache.clear();
        filesAlreadyPresent.set(0);
        recentFiles.clear();
        currentFilePath.set("");

        recorder = new JobEventRecorder(database);
        recorder.start();

        FileScanner scanner = new FileScanner(job.getIgnoreRules());
        // Files excluded during the walk never reach a worker, so they are recorded here (FR-020).
        scanner.setSkipListener((path, reason) -> recordWalkSkip(job, path, reason));
        FileValidator validator = new FileValidator(job.getImageSizeThresholdKb(),
                                                    job.getVideoSizeThresholdKb());
        MetadataExtractor extractor = new MetadataExtractor();
        HashEngine hashEngine = new HashEngine(hashIndexDao);
        FileTransfer transfer = new FileTransfer(job.getTargetPath());
        CheckpointManager checkpointManager = new CheckpointManager(
            job, jobStatistics, jobStatisticsDao, config.getJobsDir());

        checkpointManager.start();
        startThroughputSampling(job);
        startTotalFileCount(job, new FileScanner(job.getIgnoreRules()));

        try (Stream<Path> fileStream = scanner.walkFileTree(Paths.get(job.getSourcePath()))) {
            fileStream.forEach(path -> {
                if (stopRequested) return;
                workerPool.execute(() -> processFile(
                    path, job, validator, extractor, hashEngine, transfer, checkpointManager));
            });
        }

        awaitWorkerCompletion();
        checkpointManager.stop();
        stopThroughputSampling();
        currentFilePath.set("");

        // Flush every buffered event before the reports read them back out of SQLite.
        recorder.close();
        writeReports(job);

        if (!stopRequested) {
            jobStatistics.setStatus("COMPLETED");
            jobStatisticsDao.markCompleted(job.getJobId(), LocalDateTime.now());
            AppStateManager.getInstance().setState(AppStateManager.AppState.COMPLETED);
            log.info("Job {} completed. Processed: {}, Failed: {}, Skipped: {}",
                job.getJobId(), jobStatistics.getFilesProcessed(),
                jobStatistics.getFilesFailed(), jobStatistics.getFilesSkipped());
        } else {
            AppStateManager.getInstance().setState(AppStateManager.AppState.IDLE);
        }
    }

    /**
     * Fixed pool with a bounded queue. Worker threads close their own SQLite connection as they
     * die, so a long session does not leak one connection per thread per job.
     */
    private ThreadPoolExecutor newWorkerPool(int threadCount) {
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread t = new Thread(() -> {
                try {
                    runnable.run();
                } finally {
                    database.releaseCurrentThreadConnection();
                }
            }, "scan-worker-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(
            threadCount, threadCount,
            0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(threadCount * QUEUE_DEPTH_PER_THREAD),
            factory,
            new ThreadPoolExecutor.CallerRunsPolicy());
    }

    private void awaitWorkerCompletion() {
        workerPool.shutdown();
        try {
            while (!workerPool.awaitTermination(1, TimeUnit.SECONDS)) {
                if (stopRequested) {
                    workerPool.shutdownNow();
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workerPool.shutdownNow();
        }
    }

    /**
     * Counts media files in the source tree on a background thread so the dashboard has a
     * denominator for percent-complete and ETA (FR-026, FR-029). In Move mode the walk races with
     * files leaving the tree, so the count already transferred is added back.
     */
    private void startTotalFileCount(Job job, FileScanner scanner) {
        Thread counter = new Thread(() -> {
            try (Stream<Path> stream = scanner.walkFileTree(Paths.get(job.getSourcePath()))) {
                long counted = stream.count();
                long alreadyMoved = job.getTransferMode() == Job.TransferMode.MOVE
                    ? progressTracker.snapshot().filesProcessed : 0;
                long total = counted + alreadyMoved;
                progressTracker.setFilesTotal(total);
                log.info("Total media files found in source: {}", total);
            } catch (Exception e) {
                log.warn("Total file count pass failed (ETA unavailable): {}", e.getMessage());
            }
        }, "file-counter");
        counter.setDaemon(true);
        counter.start();
    }

    private void processFile(Path path, Job job, FileValidator validator,
                              MetadataExtractor extractor, HashEngine hashEngine,
                              FileTransfer transfer, CheckpointManager checkpointManager) {
        if (!awaitResumeOrAbort()) return;
        try {
            MediaFile mediaFile = buildMediaFile(path);
            currentFilePath.set(mediaFile.getAbsolutePath());

            if (!validateAndRecord(mediaFile, path, validator)) return;
            if (!extractAndRecord(mediaFile, path, extractor)) return;

            resolveDestination(mediaFile, job, extractor);

            String hash = hashEngine.computeHash(path, mediaFile);
            // One atomic statement decides ownership: whoever inserts the row owns the content. The
            // old findBySha256-then-compare-paths pair was only correct because a UNIQUE constraint
            // happened to serialise it.
            if (!hashIndexDao.claimCanonical(hash, mediaFile.getAbsolutePath())) {
                String canonicalPath = hashIndexDao.findCanonicalPath(hash);
                // A re-run or a resume re-walks files this source already claimed. The holder being
                // this very path means we are looking at our own earlier work, not a duplicate —
                // without this guard every file in a second run is reported as its own duplicate.
                if (canonicalPath != null
                        && !canonicalPath.equals(mediaFile.getAbsolutePath())) {
                    handleDuplicate(mediaFile, job, transfer, hash, canonicalPath);
                    return;
                }
                if (alreadyTransferred(mediaFile, hash)) {
                    countAsAlreadyPresent(mediaFile, checkpointManager);
                    return;
                }
            }

            performTransfer(path, mediaFile, job, transfer, hash);
            progressTracker.incrementProcessed(mediaFile.getSizeBytes());
            checkpointManager.onFileProcessed();
            addToRecentFiles(mediaFile.getFileName());

        } catch (Exception e) {
            log.error("Error processing file {}: {}", path, e.getMessage());
            synchronized (jobStatistics) {
                jobStatistics.setFilesFailed(jobStatistics.getFilesFailed() + 1);
            }
            progressTracker.incrementFailed();
            recorder.record(JobEvent.failed(job.getJobId(), buildMediaFile(path),
                e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    /** Blocks while paused; returns false if the thread was interrupted or stop was requested. */
    private boolean awaitResumeOrAbort() {
        while (pauseRequested && !stopRequested) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !stopRequested;
    }

    private static MediaFile buildMediaFile(Path path) {
        String pathStr = path.toAbsolutePath().toString();
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        MediaFile mediaFile = new MediaFile();
        mediaFile.setAbsolutePath(pathStr);
        mediaFile.setFileName(name);
        mediaFile.setExtension(dot >= 0 ? name.substring(dot + 1).toLowerCase() : "");
        return mediaFile;
    }

    private boolean validateAndRecord(MediaFile mediaFile, Path path, FileValidator validator) {
        validator.validate(mediaFile, path);
        if (mediaFile.getValidationStatus() == MediaFile.ValidationStatus.FAILED) {
            synchronized (jobStatistics) {
                jobStatistics.setFilesFailed(jobStatistics.getFilesFailed() + 1);
                jobStatistics.setCorruptFilesCount(jobStatistics.getCorruptFilesCount() + 1);
                jobStatistics.setTotalBytesSkipped(
                    jobStatistics.getTotalBytesSkipped() + mediaFile.getSizeBytes());
            }
            progressTracker.incrementFailed();
            recorder.record(JobEvent.failed(jobId(), mediaFile, mediaFile.getFailureReason()));
            return false;
        }
        if (mediaFile.getValidationStatus() == MediaFile.ValidationStatus.SKIPPED) {
            synchronized (jobStatistics) {
                jobStatistics.setFilesSkipped(jobStatistics.getFilesSkipped() + 1);
                if (mediaFile.getSkipReason() == MediaFile.SkipReason.EMPTY_FILE) {
                    jobStatistics.setEmptyFilesCount(jobStatistics.getEmptyFilesCount() + 1);
                } else if (mediaFile.getSkipReason() == MediaFile.SkipReason.SMALL_FILE) {
                    jobStatistics.setSmallFilesCount(jobStatistics.getSmallFilesCount() + 1);
                }
                jobStatistics.setTotalBytesSkipped(
                    jobStatistics.getTotalBytesSkipped() + mediaFile.getSizeBytes());
            }
            progressTracker.incrementSkipped();
            recorder.record(JobEvent.skipped(jobId(), mediaFile, mediaFile.getSkipReason()));
            return false;
        }
        return true;
    }

    private boolean extractAndRecord(MediaFile mediaFile, Path path, MetadataExtractor extractor) {
        extractor.extract(mediaFile, path);
        if (mediaFile.getExtractedDate() == null) {
            mediaFile.setValidationStatus(MediaFile.ValidationStatus.SKIPPED);
            mediaFile.setSkipReason(MediaFile.SkipReason.METADATA_MISSING);
            synchronized (jobStatistics) {
                jobStatistics.setFilesSkipped(jobStatistics.getFilesSkipped() + 1);
            }
            progressTracker.incrementSkipped();
            recorder.record(JobEvent.skipped(jobId(), mediaFile, mediaFile.getSkipReason()));
            return false;
        }
        return true;
    }

    private void resolveDestination(MediaFile mediaFile, Job job,
                                     MetadataExtractor extractor) throws IOException {
        String subDir = extractor.computeFolderPath(mediaFile.getExtractedDate(),
                                                     job.getFolderPattern());
        Path destDirPath = Paths.get(job.getTargetPath()).resolve(subDir);
        mediaFile.setDestinationPath(destDirPath.resolve(mediaFile.getFileName()).toString());
        String destDir = destDirPath.toString();
        if (!destFolderCache.contains(destDir)) {
            Files.createDirectories(destDirPath);
            // Only the thread that wins the race counts the folder as newly created.
            if (destFolderCache.add(destDir)) {
                synchronized (jobStatistics) {
                    jobStatistics.setTotalFoldersCreated(
                        jobStatistics.getTotalFoldersCreated() + 1);
                }
            }
        }
    }

    private void performTransfer(Path src, MediaFile mediaFile, Job job,
                                  FileTransfer transfer, String hash) throws IOException {
        Path destination = transfer.resolveCollisionFreePath(
            Paths.get(mediaFile.getDestinationPath()));
        mediaFile.setDestinationPath(destination.toString());
        if (job.getTransferMode() == Job.TransferMode.COPY) {
            transfer.copy(src, destination);
            synchronized (jobStatistics) {
                jobStatistics.setFilesCopied(jobStatistics.getFilesCopied() + 1);
                jobStatistics.setTotalBytesCopied(
                    jobStatistics.getTotalBytesCopied() + mediaFile.getSizeBytes());
            }
        } else {
            transfer.move(src, destination);
            synchronized (jobStatistics) {
                jobStatistics.setFilesMoved(jobStatistics.getFilesMoved() + 1);
                jobStatistics.setTotalBytesMoved(
                    jobStatistics.getTotalBytesMoved() + mediaFile.getSizeBytes());
            }
        }
        mediaFile.setOutcome(MediaFile.Outcome.TRANSFERRED);
        synchronized (jobStatistics) {
            jobStatistics.setFilesProcessed(jobStatistics.getFilesProcessed() + 1);
            jobStatistics.setTotalBytesProcessed(
                jobStatistics.getTotalBytesProcessed() + mediaFile.getSizeBytes());
        }

        // Remember where it landed. A later run reads this back instead of recomputing the
        // destination, which is what makes resume safe once a collision suffix has been applied.
        try {
            hashIndexDao.recordCanonicalDestination(hash, destination.toString(),
                mediaFile.getSizeBytes());
        } catch (SQLException e) {
            log.warn("Could not record destination for {}: {}", destination, e.getMessage());
        }
    }

    /**
     * True when this exact content is already sitting at the destination recorded by an earlier
     * run. Costs one stat call and no file reads — the hash came from the Stage 1 index cache.
     *
     * <p>If the recorded destination is missing or the wrong size, the earlier transfer did not
     * finish and the file is transferred again.
     */
    private boolean alreadyTransferred(MediaFile mediaFile, String hash) {
        try {
            HashIndexDao.TransferredCopy copy = hashIndexDao.findCanonicalDestination(hash);
            if (copy == null) return false;
            Path destination = Paths.get(copy.path());
            return Files.exists(destination) && Files.size(destination) == copy.size();
        } catch (Exception e) {
            log.debug("Could not verify prior transfer of {}: {}",
                mediaFile.getAbsolutePath(), e.getMessage());
            return false;
        }
    }

    /**
     * A file an earlier run already placed in the archive. Counted as processed so percent-complete
     * and ETA stay meaningful across a resume, but not counted as copied or moved — no bytes moved
     * this run.
     */
    private void countAsAlreadyPresent(MediaFile mediaFile, CheckpointManager checkpointManager) {
        mediaFile.setOutcome(MediaFile.Outcome.TRANSFERRED);
        synchronized (jobStatistics) {
            jobStatistics.setFilesProcessed(jobStatistics.getFilesProcessed() + 1);
        }
        filesAlreadyPresent.incrementAndGet();
        progressTracker.incrementProcessed(0);
        checkpointManager.onFileProcessed();
        log.debug("Already in archive, skipping: {}", mediaFile.getAbsolutePath());
    }

    private void addToRecentFiles(String fileName) {
        recentFiles.addFirst(fileName);
        while (recentFiles.size() > RECENT_FILES_LIMIT) {
            recentFiles.pollLast();
        }
    }

    private void handleDuplicate(MediaFile mediaFile, Job job, FileTransfer transfer,
                                  String hash, String matchedPath) throws IOException {
        synchronized (jobStatistics) {
            jobStatistics.setDuplicatesFound(jobStatistics.getDuplicatesFound() + 1);
            jobStatistics.setDuplicateByteSavings(
                jobStatistics.getDuplicateByteSavings() + mediaFile.getSizeBytes());
        }
        progressTracker.incrementDuplicate();
        mediaFile.setOutcome(MediaFile.Outcome.DUPLICATE);

        switch (job.getDuplicatePolicy()) {
            case SKIP -> { /* intentionally no action */ }
            case MOVE_TO_BUCKET -> {
                Path dupDir = Paths.get(job.getTargetPath(), "_duplicates");
                Files.createDirectories(dupDir);
                Path dest = transfer.resolveCollisionFreePath(
                    dupDir.resolve(mediaFile.getFileName()));
                transfer.copy(Paths.get(mediaFile.getAbsolutePath()), dest);
                mediaFile.setDestinationPath(dest.toString());
            }
            case KEEP_BOTH -> {
                String baseName = mediaFile.getFileName();
                int dot = baseName.lastIndexOf('.');
                String nameNoExt = dot >= 0 ? baseName.substring(0, dot) : baseName;
                String ext = dot >= 0 ? baseName.substring(dot) : "";
                Path destDir = Paths.get(mediaFile.getDestinationPath()).getParent();
                int n = 1;
                Path dest;
                do {
                    dest = destDir.resolve(nameNoExt + "_DUP_" + n + ext);
                    n++;
                } while (Files.exists(dest));
                transfer.copy(Paths.get(mediaFile.getAbsolutePath()), dest);
                mediaFile.setDestinationPath(dest.toString());
            }
        }

        recorder.record(JobEvent.duplicate(job.getJobId(), mediaFile, hash, matchedPath,
            job.getDuplicatePolicy() == Job.DuplicatePolicy.SKIP
                ? null : mediaFile.getDestinationPath()));
    }

    private void applyHighPriorityMode(Job job) {
        if (!job.isHighPriorityMode()) return;
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                com.sun.jna.platform.win32.Kernel32 kernel32 =
                    com.sun.jna.platform.win32.Kernel32.INSTANCE;
                com.sun.jna.platform.win32.WinNT.HANDLE process =
                    kernel32.GetCurrentProcess();
                kernel32.SetPriorityClass(process,
                    new com.sun.jna.platform.win32.WinDef.DWORD(0x00000080));
                log.info("High-Priority Mode: Windows HIGH_PRIORITY_CLASS set");
            } else if (os.contains("mac") || os.contains("nix") || os.contains("nux")) {
                CLibrary.INSTANCE.setpriority(0, 0, -10);
                log.info("High-Priority Mode: Unix nice -10 set");
            }
        } catch (Exception e) {
            log.warn("High-Priority Mode failed (non-blocking): {}", e.getMessage());
        }
    }

    public void pause() {
        pauseRequested = true;
        AppStateManager.getInstance().setState(AppStateManager.AppState.PAUSED);
        if (currentJob != null) {
            try {
                jobStatisticsDao.updateStatus(currentJob.getJobId(), "PAUSED");
            } catch (Exception e) {
                log.warn("Could not update status to PAUSED: {}", e.getMessage());
            }
        }
        log.info("Pause requested");
    }

    public void resume() {
        pauseRequested = false;
        AppStateManager.getInstance().setState(AppStateManager.AppState.RUNNING);
        if (currentJob != null) {
            try {
                jobStatisticsDao.updateStatus(currentJob.getJobId(), "RUNNING");
            } catch (Exception e) {
                log.warn("Could not update status to RUNNING: {}", e.getMessage());
            }
        }
        log.info("Resumed");
    }

    public void stop() {
        stopRequested = true;
        pauseRequested = false;
        if (workerPool != null) {
            workerPool.shutdown();
        }
        if (currentJob != null) {
            try {
                jobStatisticsDao.markStopped(currentJob.getJobId(), LocalDateTime.now());
            } catch (Exception e) {
                log.warn("Could not mark job stopped: {}", e.getMessage());
            }
        }
        log.info("Stop requested");
    }

    public boolean isPauseRequested() { return pauseRequested; }
    public boolean isStopRequested() { return stopRequested; }
    public Job getCurrentJob() { return currentJob; }
    public JobStatistics getJobStatistics() { return jobStatistics; }

    /** Path of the file currently being processed by a worker thread. */
    public String getCurrentFilePath() { return currentFilePath.get(); }

    /** Snapshot of the most recently transferred file names (newest first, max 8). */
    public List<String> getRecentFiles() { return new ArrayList<>(recentFiles); }

    /**
     * Samples throughput once a second for the whole job (FR-031). Also drives
     * {@link ProgressTracker#tick()}, so rolling averages and ETA stay correct even when no UI is
     * attached — previously only the dashboard called it.
     */
    private void startThroughputSampling(Job job) {
        throughputHistory.clear();
        resourceMonitor = new ResourceMonitor();
        resourceMonitor.start();

        long startMillis = System.currentTimeMillis();
        samplerPool = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "throughput-sampler");
            t.setDaemon(true);
            return t;
        });
        samplerPool.scheduleAtFixedRate(() -> {
            try {
                progressTracker.tick();
                ProgressTracker.Snapshot snap = progressTracker.snapshot();
                double cpu = resourceMonitor.getCpuPercent();
                double memGb = resourceMonitor.getMemoryGb();
                long elapsed = (System.currentTimeMillis() - startMillis) / 1000;

                throughputHistory.addSample(snap.avgFilesPerSec5s, snap.avgMbPerSec5s, cpu, memGb);
                recorder.sample(new ThroughputSample(job.getJobId(), java.time.Instant.now(),
                    elapsed, snap.avgFilesPerSec5s, snap.avgMbPerSec5s, cpu, memGb));

                trackPeaks(snap, cpu, memGb);
            } catch (Exception e) {
                log.debug("Throughput sample failed: {}", e.getMessage());
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    /** Keeps the peak/average figures the end-of-job summary requires (Principle IV). */
    private void trackPeaks(ProgressTracker.Snapshot snap, double cpu, double memGb) {
        synchronized (jobStatistics) {
            jobStatistics.setPeakFilesPerSec(
                Math.max(jobStatistics.getPeakFilesPerSec(), snap.avgFilesPerSec5s));
            jobStatistics.setPeakMbPerSec(
                Math.max(jobStatistics.getPeakMbPerSec(), snap.avgMbPerSec5s));
            jobStatistics.setPeakCpuPercent(Math.max(jobStatistics.getPeakCpuPercent(), cpu));
            jobStatistics.setPeakMemoryGb(Math.max(jobStatistics.getPeakMemoryGb(), memGb));
            jobStatistics.setAvgFilesPerSec(snap.avgFilesPerSecJob);
            jobStatistics.setAvgMbPerSec(snap.avgMbPerSec5s);
        }
    }

    private void stopThroughputSampling() {
        if (samplerPool != null) samplerPool.shutdownNow();
        if (resourceMonitor != null) resourceMonitor.stop();
    }

    /** Count of files an earlier run had already transferred (feature 007 resume). */
    public long getFilesAlreadyPresent() { return filesAlreadyPresent.get(); }

    /** Live throughput window for the dashboard chart. */
    public ThroughputHistory getThroughputHistory() { return throughputHistory; }

    private String jobId() {
        return currentJob != null ? currentJob.getJobId() : "unknown";
    }

    /**
     * Records a file excluded during the walk. These never reach a worker, so their counters are
     * bumped here too - without this the dashboard would under-report skips (FR-020).
     */
    private void recordWalkSkip(Job job, Path path, MediaFile.SkipReason reason) {
        MediaFile mediaFile = buildMediaFile(path);
        try {
            mediaFile.setSizeBytes(Files.size(path));
        } catch (IOException e) {
            mediaFile.setSizeBytes(0);
        }
        synchronized (jobStatistics) {
            jobStatistics.setFilesSkipped(jobStatistics.getFilesSkipped() + 1);
        }
        progressTracker.incrementSkipped();
        recorder.record(JobEvent.skipped(job.getJobId(), mediaFile, reason));
    }

    /**
     * Writes the three reports into the target archive. A failure here must not discard the SQLite
     * record - the reports stay regenerable from the Job History screen.
     */
    private void writeReports(Job job) {
        try {
            reportService.writeAll(job.getJobId(), job.getTargetPath(), job.getSourcePath());
        } catch (Exception e) {
            log.error("Could not write job reports for {} (records remain in the database "
                + "and can be regenerated): {}", job.getJobId(), e.getMessage());
        }
    }
}
