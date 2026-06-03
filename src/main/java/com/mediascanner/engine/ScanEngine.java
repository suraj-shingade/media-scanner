package com.mediascanner.engine;

import com.mediascanner.checkpoint.CheckpointManager;
import com.mediascanner.config.AppConfig;
import com.mediascanner.db.Database;
import com.mediascanner.db.HashIndexDao;
import com.mediascanner.db.JobStatisticsDao;
import com.mediascanner.model.*;
import com.mediascanner.monitor.ProgressTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

public class ScanEngine {

    private static final Logger log = LoggerFactory.getLogger(ScanEngine.class);

    private final AppConfig config;
    private final Database database;
    private final HashIndexDao hashIndexDao;
    private final JobStatisticsDao jobStatisticsDao;
    private final ProgressTracker progressTracker;

    private ExecutorService workerPool;
    private volatile boolean pauseRequested = false;
    private volatile boolean stopRequested = false;

    private Job currentJob;
    private JobStatistics jobStatistics;

    // RAM-aggressive caches (US9: T071)
    private final Map<String, Boolean> destFolderCache = new HashMap<>();
    private final Map<String, LocalDateTime> metadataCache = new HashMap<>();

    public ScanEngine(AppConfig config, Database database, ProgressTracker progressTracker) {
        this.config = config;
        this.database = database;
        this.hashIndexDao = new HashIndexDao(database);
        this.jobStatisticsDao = new JobStatisticsDao(database);
        this.progressTracker = progressTracker;
    }

    public void start(Job job) throws Exception {
        this.currentJob = job;
        this.pauseRequested = false;
        this.stopRequested = false;

        int threadCount = job.getWorkerThreadCount() > 0
            ? job.getWorkerThreadCount() : config.getWorkerThreadCount();
        workerPool = Executors.newFixedThreadPool(threadCount);
        log.info("ScanEngine starting job {} with {} threads", job.getJobId(), threadCount);

        applyHighPriorityMode(job);

        jobStatistics = new JobStatistics(job.getJobId(), job.getStartTime());
        jobStatisticsDao.insert(jobStatistics);

        destFolderCache.clear();
        metadataCache.clear();

        FileScanner scanner = new FileScanner(job.getIgnoreRules());
        FileValidator validator = new FileValidator(job.getImageSizeThresholdKb(),
                                                    job.getVideoSizeThresholdKb());
        MetadataExtractor extractor = new MetadataExtractor();
        HashEngine hashEngine = new HashEngine(hashIndexDao);
        FileTransfer transfer = new FileTransfer(job.getTargetPath());
        CheckpointManager checkpointManager = new CheckpointManager(
            job, jobStatistics, jobStatisticsDao, config.getJobsDir());

        checkpointManager.start();

        try (Stream<Path> fileStream = scanner.walkFileTree(Paths.get(job.getSourcePath()))) {
            List<Future<?>> futures = new ArrayList<>();
            fileStream.forEach(path -> {
                if (stopRequested) return;
                Future<?> f = workerPool.submit(() -> processFile(
                    path, job, validator, extractor, hashEngine, transfer,
                    checkpointManager));
                futures.add(f);
            });

            for (Future<?> f : futures) {
                try { f.get(); } catch (Exception e) {
                    log.warn("Worker exception: {}", e.getMessage());
                }
            }
        }

        workerPool.shutdown();
        checkpointManager.stop();

        if (!stopRequested) {
            jobStatistics.setStatus("COMPLETED");
            jobStatisticsDao.markCompleted(job.getJobId(), LocalDateTime.now());
            log.info("Job {} completed. Processed: {}, Failed: {}, Skipped: {}",
                job.getJobId(), jobStatistics.getFilesProcessed(),
                jobStatistics.getFilesFailed(), jobStatistics.getFilesSkipped());
        }
    }

    private void processFile(Path path, Job job, FileValidator validator,
                              MetadataExtractor extractor, HashEngine hashEngine,
                              FileTransfer transfer, CheckpointManager checkpointManager) {
        while (pauseRequested && !stopRequested) {
            try { Thread.sleep(100); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (stopRequested) return;

        try {
            String pathStr = path.toAbsolutePath().toString();

            MediaFile mediaFile = new MediaFile();
            mediaFile.setAbsolutePath(pathStr);
            mediaFile.setFileName(path.getFileName().toString());
            String name = path.getFileName().toString();
            int dot = name.lastIndexOf('.');
            mediaFile.setExtension(dot >= 0 ? name.substring(dot + 1).toLowerCase() : "");

            // Validate
            validator.validate(mediaFile, path);
            if (mediaFile.getValidationStatus() == MediaFile.ValidationStatus.FAILED) {
                synchronized (jobStatistics) {
                    jobStatistics.setFilesFailed(jobStatistics.getFilesFailed() + 1);
                    jobStatistics.setCorruptFilesCount(jobStatistics.getCorruptFilesCount() + 1);
                    jobStatistics.setTotalBytesSkipped(
                        jobStatistics.getTotalBytesSkipped() + mediaFile.getSizeBytes());
                }
                progressTracker.incrementFailed();
                return;
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
                return;
            }

            // Extract metadata — check cache first
            LocalDateTime cachedDate = metadataCache.get(pathStr);
            if (cachedDate != null) {
                mediaFile.setExtractedDate(cachedDate);
                mediaFile.setDateSource(MediaFile.DateSource.EMBEDDED_CAPTURE);
            } else {
                extractor.extract(mediaFile, path);
                if (mediaFile.getExtractedDate() != null) {
                    metadataCache.put(pathStr, mediaFile.getExtractedDate());
                }
            }

            if (mediaFile.getExtractedDate() == null) {
                mediaFile.setValidationStatus(MediaFile.ValidationStatus.SKIPPED);
                mediaFile.setSkipReason(MediaFile.SkipReason.METADATA_MISSING);
                synchronized (jobStatistics) {
                    jobStatistics.setFilesSkipped(jobStatistics.getFilesSkipped() + 1);
                }
                progressTracker.incrementSkipped();
                return;
            }

            // Compute destination path
            String subDir = extractor.computeFolderPath(mediaFile.getExtractedDate(),
                                                         job.getFolderPattern());
            String destDir = job.getTargetPath() + "/" + subDir;
            String destPath = destDir + "/" + mediaFile.getFileName();
            mediaFile.setDestinationPath(destPath);

            // Create dest folder if needed
            if (!destFolderCache.getOrDefault(destDir, false)) {
                java.nio.file.Files.createDirectories(Paths.get(destDir));
                synchronized (jobStatistics) {
                    if (!destFolderCache.containsKey(destDir)) {
                        jobStatistics.setTotalFoldersCreated(
                            jobStatistics.getTotalFoldersCreated() + 1);
                    }
                }
                destFolderCache.put(destDir, true);
            }

            // Hash and duplicate check
            String hash = hashEngine.computeHash(path, mediaFile);
            FileHashRecord existing = hashIndexDao.findBySha256(hash);
            if (existing != null && !existing.getFilePath().equals(pathStr)) {
                handleDuplicate(mediaFile, job, transfer, hash);
                return;
            }

            // Transfer
            Path destination = transfer.resolveCollisionFreePath(Paths.get(destPath));
            mediaFile.setDestinationPath(destination.toString());

            if (job.getTransferMode() == Job.TransferMode.COPY) {
                transfer.copy(path, destination);
                synchronized (jobStatistics) {
                    jobStatistics.setFilesCopied(jobStatistics.getFilesCopied() + 1);
                    jobStatistics.setTotalBytesCopied(
                        jobStatistics.getTotalBytesCopied() + mediaFile.getSizeBytes());
                }
            } else {
                transfer.move(path, destination);
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
            progressTracker.incrementProcessed(mediaFile.getSizeBytes());
            checkpointManager.onFileProcessed();

        } catch (Exception e) {
            log.error("Error processing file {}: {}", path, e.getMessage());
            synchronized (jobStatistics) {
                jobStatistics.setFilesFailed(jobStatistics.getFilesFailed() + 1);
            }
            progressTracker.incrementFailed();
        }
    }

    private void handleDuplicate(MediaFile mediaFile, Job job, FileTransfer transfer,
                                  String hash) throws Exception {
        synchronized (jobStatistics) {
            jobStatistics.setDuplicatesFound(jobStatistics.getDuplicatesFound() + 1);
            jobStatistics.setDuplicateByteSavings(
                jobStatistics.getDuplicateByteSavings() + mediaFile.getSizeBytes());
        }
        progressTracker.incrementDuplicate();
        mediaFile.setOutcome(MediaFile.Outcome.DUPLICATE);

        switch (job.getDuplicatePolicy()) {
            case SKIP -> { /* do nothing */ }
            case MOVE_TO_BUCKET -> {
                Path dupDir = Paths.get(job.getTargetPath(), "_duplicates");
                java.nio.file.Files.createDirectories(dupDir);
                Path dest = transfer.resolveCollisionFreePath(
                    dupDir.resolve(mediaFile.getFileName()));
                transfer.copy(Paths.get(mediaFile.getAbsolutePath()), dest);
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
                } while (java.nio.file.Files.exists(dest));
                transfer.copy(Paths.get(mediaFile.getAbsolutePath()), dest);
            }
        }
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
                kernel32.SetPriorityClass(process, new com.sun.jna.platform.win32.WinDef.DWORD(0x00000080)); // HIGH_PRIORITY_CLASS
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
}
