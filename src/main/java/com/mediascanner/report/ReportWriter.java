package com.mediascanner.report;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.mediascanner.db.JobEventDao;
import com.mediascanner.model.JobEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Streams one outcome's events from SQLite straight into a JSON report file.
 *
 * <p>Nothing is buffered: rows arrive from a forward-only cursor and are written to a
 * {@link JsonGenerator} one at a time, so peak memory is independent of entry count
 * (FR-005-005, SC-003). Jackson also handles escaping for us, which is what makes Windows
 * backslashes, non-ASCII names and embedded quotes safe (FR-005-013).
 */
public class ReportWriter {

    private static final Logger log = LoggerFactory.getLogger(ReportWriter.class);

    /** Beyond this, the report is truncated with an explicit in-file notice (research D7). */
    public static final int DEFAULT_MAX_ENTRIES = 100_000;

    private final JobEventDao eventDao;
    private final JsonFactory jsonFactory = new JsonFactory();
    private final int maxEntries;

    public ReportWriter(JobEventDao eventDao) {
        this(eventDao, DEFAULT_MAX_ENTRIES);
    }

    public ReportWriter(JobEventDao eventDao, int maxEntries) {
        this.eventDao = eventDao;
        this.maxEntries = maxEntries;
    }

    /**
     * Writes the report for one outcome.
     *
     * @return the number of entries written (may be less than the true total when truncated)
     */
    public long write(Path destination, String jobId, JobEvent.Outcome outcome,
                      String sourcePath, String targetPath) throws IOException, SQLException {

        long totalCount = eventDao.countByOutcome(jobId, outcome);
        long totalBytes = eventDao.sumBytesByOutcome(jobId, outcome);
        boolean truncated = totalCount > maxEntries;

        Files.createDirectories(destination.getParent());

        long written;
        try (OutputStream out = Files.newOutputStream(destination);
             JsonGenerator gen = jsonFactory.createGenerator(out, JsonEncoding.UTF8)) {
            gen.setPrettyPrinter(new DefaultPrettyPrinter());

            gen.writeStartObject();
            gen.writeStringField("jobId", jobId);
            gen.writeStringField("outcome", outcome.name());
            gen.writeStringField("generatedAt", Instant.now().toString());
            gen.writeStringField("sourcePath", sourcePath);
            gen.writeStringField("targetPath", targetPath);
            gen.writeNumberField("totalCount", totalCount);
            if (outcome == JobEvent.Outcome.DUPLICATE) {
                gen.writeNumberField("totalBytesSaved", totalBytes);
            } else {
                gen.writeNumberField("totalBytes", totalBytes);
            }
            gen.writeBooleanField("truncated", truncated);
            if (truncated) {
                gen.writeNumberField("entriesOmitted", totalCount - maxEntries);
                gen.writeStringField("truncationNotice",
                    "Report capped at " + maxEntries + " entries. The complete record for this job "
                    + "remains queryable in the application database via the Job History screen.");
            }

            gen.writeArrayFieldStart("entries");
            written = eventDao.streamByOutcome(jobId, outcome, maxEntries, event -> {
                try {
                    writeEntry(gen, event, outcome);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            gen.writeEndArray();

            gen.writeEndObject();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }

        log.info("Wrote {} report for job {}: {} entries{} -> {}",
            outcome, jobId, written, truncated ? " (truncated)" : "", destination);
        return written;
    }

    private void writeEntry(JsonGenerator gen, JobEvent event, JobEvent.Outcome outcome)
            throws IOException {
        gen.writeStartObject();
        gen.writeStringField("filePath", event.getFilePath());
        gen.writeStringField("fileName", event.getFileName());
        gen.writeNumberField("fileSize", event.getFileSize());
        gen.writeStringField("reason", event.getReason());
        if (outcome == JobEvent.Outcome.DUPLICATE) {
            gen.writeStringField("sha256Hash", event.getSha256Hash());
            gen.writeStringField("matchedPath", event.getMatchedPath());
        }
        if (event.getDestinationPath() != null) {
            gen.writeStringField("destinationPath", event.getDestinationPath());
        }
        gen.writeStringField("recordedAt",
            event.getRecordedAt() != null ? event.getRecordedAt().toString() : null);
        gen.writeEndObject();
    }
}
