package com.mediascanner.report;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.mediascanner.engine.CleanupEngine;
import com.mediascanner.model.CleanupCandidate;
import com.mediascanner.model.CleanupRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;

/**
 * Writes the durable record of a Cleanup run (FR-055 – FR-058).
 *
 * <p>This report matters more than the scan reports do. Those describe files that still exist and can
 * be re-examined; this one describes files that no longer exist anywhere. Once the run finishes it is
 * the only evidence of what was removed, which is why Constitution IX makes it mandatory rather than
 * advisory.
 *
 * <p>Written to {@code ~/.mediascanner/cleanup/} rather than into a target archive, because the
 * Cleanup screen operates on an arbitrary directory and there may be no archive at all.
 */
public class CleanupReportWriter {

    private static final Logger log = LoggerFactory.getLogger(CleanupReportWriter.class);

    private final JsonFactory jsonFactory = new JsonFactory();
    private final Path reportDir;

    public CleanupReportWriter() {
        this(Paths.get(System.getProperty("user.home"), ".mediascanner", "cleanup"));
    }

    public CleanupReportWriter(Path reportDir) {
        this.reportDir = reportDir;
    }

    public Path reportPathFor(String runId) {
        return reportDir.resolve("cleanup-report-" + runId + ".json");
    }

    /**
     * @return the path written, or null when there was nothing to record.
     */
    public Path write(CleanupRun run,
                      CleanupEngine.DeleteResult deleteResult,
                      CleanupEngine.PruneResult pruneResult) throws IOException {

        boolean deletedAnything = deleteResult != null && !deleteResult.getDeleted().isEmpty();
        boolean prunedAnything = pruneResult != null && !pruneResult.getRemoved().isEmpty();
        boolean hadProblems = deleteResult != null
            && (!deleteResult.getSkipped().isEmpty() || !deleteResult.getFailed().isEmpty());

        if (!deletedAnything && !prunedAnything && !hadProblems) {
            return null;
        }

        Path destination = reportPathFor(run.getRunId());
        Files.createDirectories(destination.getParent());

        try (OutputStream out = Files.newOutputStream(destination);
             JsonGenerator gen = jsonFactory.createGenerator(out, JsonEncoding.UTF8)) {
            gen.setPrettyPrinter(new DefaultPrettyPrinter());

            gen.writeStartObject();
            gen.writeStringField("runId", run.getRunId());
            gen.writeStringField("root", run.getRoot().toString());
            gen.writeStringField("startedAt", run.getStartedAt().toString());
            gen.writeStringField("generatedAt", Instant.now().toString());
            gen.writeBooleanField("permanentDeletion", true);

            if (deleteResult != null) {
                gen.writeNumberField("filesDeleted", deleteResult.getDeleted().size());
                gen.writeNumberField("bytesDeleted", deleteResult.bytesDeleted());
                gen.writeNumberField("filesSkipped", deleteResult.getSkipped().size());
                gen.writeNumberField("filesFailed", deleteResult.getFailed().size());

                writeCandidates(gen, "deleted", deleteResult.getDeleted());
                writeCandidates(gen, "skipped", deleteResult.getSkipped());
                writeCandidates(gen, "failed", deleteResult.getFailed());
            }

            if (pruneResult != null) {
                gen.writeNumberField("directoriesRemoved", pruneResult.getRemoved().size());
                gen.writeArrayFieldStart("directoriesRemovedPaths");
                for (Path dir : pruneResult.getRemoved()) {
                    gen.writeString(dir.toString());
                }
                gen.writeEndArray();

                if (!pruneResult.getFailed().isEmpty()) {
                    gen.writeArrayFieldStart("directoriesFailed");
                    for (Path dir : pruneResult.getFailed()) {
                        gen.writeString(dir.toString());
                    }
                    gen.writeEndArray();
                }
            }

            gen.writeEndObject();
        }

        log.info("Wrote cleanup report for run {} -> {}", run.getRunId(), destination);
        return destination;
    }

    private void writeCandidates(JsonGenerator gen, String field, List<CleanupCandidate> items)
            throws IOException {
        gen.writeArrayFieldStart(field);
        for (CleanupCandidate c : items) {
            gen.writeStartObject();
            gen.writeStringField("path", c.getPath().toString());
            gen.writeNumberField("sizeBytes", c.getSizeBytes());
            gen.writeStringField("detectedMimeType", c.getDetectedMimeType());
            gen.writeStringField("group", c.getGroup().name());
            gen.writeStringField("outcome", c.getOutcome().name());
            if (c.getReason() != null) {
                gen.writeStringField("reason", c.getReason());
            }
            gen.writeEndObject();
        }
        gen.writeEndArray();
    }
}
