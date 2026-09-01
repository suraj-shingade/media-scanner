package com.mediascanner.report;

import com.mediascanner.db.Database;
import com.mediascanner.db.JobEventDao;
import com.mediascanner.model.JobEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes the three per-job reports into the target archive at terminal state
 * (FR-019, FR-020, FR-023).
 *
 * <p>Takes a {@code jobId} rather than live engine state, so reports can also be generated long
 * after the engine is gone — which is how a job that died mid-run gets its reports from the Job
 * History screen (research D8).
 *
 * <p>Each report is written as {@code <name>-<jobId>.json} and then copied to the plain
 * {@code <name>.json}. The per-job file is the durable record — a second job against the same
 * archive must not destroy the first one's report (FR-005-006) — while the plain name is what a
 * user browsing {@code _failures/} expects to find, and is what FR-019 names literally.
 */
public class JobReportService {

    private static final Logger log = LoggerFactory.getLogger(JobReportService.class);

    /** Outcome to (bucket directory, report base name). */
    private static final Map<JobEvent.Outcome, String[]> REPORTS = new LinkedHashMap<>();
    static {
        REPORTS.put(JobEvent.Outcome.SKIPPED,   new String[] {"_skipped",    "skipped-report"});
        REPORTS.put(JobEvent.Outcome.FAILED,    new String[] {"_failures",   "failure-report"});
        REPORTS.put(JobEvent.Outcome.DUPLICATE, new String[] {"_duplicates", "duplicate-report"});
    }

    private final JobEventDao eventDao;
    private final ReportWriter writer;

    public JobReportService(Database database) {
        this.eventDao = new JobEventDao(database);
        this.writer = new ReportWriter(eventDao);
    }

    JobReportService(JobEventDao eventDao, ReportWriter writer) {
        this.eventDao = eventDao;
        this.writer = writer;
    }

    /**
     * Writes every report that has at least one entry. Reports with no entries are skipped
     * entirely — no empty file and no empty bucket directory (US1 AS-3, US2 AS-5).
     *
     * @return the paths written, keyed by outcome
     */
    public Map<JobEvent.Outcome, Path> writeAll(String jobId, String targetRoot,
                                                String sourcePath) throws IOException, SQLException {
        Map<JobEvent.Outcome, Path> written = new LinkedHashMap<>();
        Path root = Paths.get(targetRoot);

        for (Map.Entry<JobEvent.Outcome, String[]> entry : REPORTS.entrySet()) {
            JobEvent.Outcome outcome = entry.getKey();
            String bucketDir = entry.getValue()[0];
            String baseName = entry.getValue()[1];

            if (eventDao.countByOutcome(jobId, outcome) == 0) {
                log.debug("No {} events for job {}; no report written", outcome, jobId);
                continue;
            }

            Path perJob = root.resolve(bucketDir).resolve(baseName + "-" + jobId + ".json");
            writer.write(perJob, jobId, outcome, sourcePath, targetRoot);

            Path latest = root.resolve(bucketDir).resolve(baseName + ".json");
            try {
                Files.copy(perJob, latest, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                log.warn("Could not update {}: {}", latest, e.getMessage());
            }

            written.put(outcome, perJob);
        }
        return written;
    }
}
