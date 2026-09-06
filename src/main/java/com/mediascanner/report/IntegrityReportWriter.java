package com.mediascanner.report;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.mediascanner.model.IntegrityFinding;
import com.mediascanner.model.IntegrityRun;
import com.mediascanner.model.IntegrityStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

/**
 * Writes the durable record of a verification run (FR-074 – FR-076).
 *
 * <p>Unlike the cleanup report, this one is written for <em>every</em> completed run, including a clean
 * one. A report that only appears when something is wrong cannot be used as evidence that something was
 * right, and "prove the archive is intact" is the point of the feature.
 *
 * <p>Written to {@code ~/.mediascanner/integrity/} rather than into the archive, for two reasons: the
 * archive may be read-only or on removable media, and writing into the thing being verified would make
 * the next run report the report itself as an untracked file.
 */
public class IntegrityReportWriter {

    private static final Logger log = LoggerFactory.getLogger(IntegrityReportWriter.class);

    private final JsonFactory jsonFactory = new JsonFactory();
    private final Path reportDir;

    public IntegrityReportWriter() {
        this(Paths.get(System.getProperty("user.home"), ".mediascanner", "integrity"));
    }

    public IntegrityReportWriter(Path reportDir) {
        this.reportDir = reportDir;
    }

    public Path reportPathFor(String runId) {
        return reportDir.resolve("integrity-report-" + runId + ".json");
    }

    public Path write(IntegrityRun run) throws IOException {
        Path destination = reportPathFor(run.getRunId());
        Files.createDirectories(destination.getParent());

        try (OutputStream out = Files.newOutputStream(destination);
             JsonGenerator gen = jsonFactory.createGenerator(out, JsonEncoding.UTF8)) {
            gen.setPrettyPrinter(new DefaultPrettyPrinter());

            gen.writeStartObject();
            gen.writeStringField("runId", run.getRunId());
            gen.writeStringField("archiveRoot", run.getArchiveRoot().toString());
            gen.writeStringField("mode", run.getMode().name());
            gen.writeStringField("modeDescription", run.getMode().getDisplayName());
            gen.writeStringField("startedAt", run.getStartedAt().toString());
            gen.writeStringField("generatedAt", Instant.now().toString());
            gen.writeNumberField("elapsedMillis", run.elapsedMillis());
            gen.writeBooleanField("completed", !run.wasCancelled());
            gen.writeBooleanField("clean", run.isClean());
            gen.writeNumberField("bytesRead", run.getBytesRead());

            // Stated in the report itself, because a quick pass that found nothing is the single
            // most misreadable result this feature can produce (FR-066).
            gen.writeStringField("caveat", caveatFor(run));

            gen.writeObjectFieldStart("counts");
            for (IntegrityStatus status : IntegrityStatus.values()) {
                gen.writeNumberField(status.name(), run.countOf(status));
            }
            gen.writeEndObject();

            gen.writeNumberField("filesVerified", run.verifiedCount());
            gen.writeNumberField("problems", run.problemCount());

            gen.writeArrayFieldStart("findings");
            for (IntegrityFinding finding : run.getFindings()) {
                writeFinding(gen, finding);
            }
            gen.writeEndArray();

            gen.writeEndObject();
        }

        log.info("Wrote integrity report for run {} -> {}", run.getRunId(), destination);
        return destination;
    }

    /** The sentence that stops a result being over-read. */
    private String caveatFor(IntegrityRun run) {
        if (run.wasCancelled()) {
            return "This run was cancelled before it finished. Files it did not reach were not "
                 + "checked, and the absence of findings for them means nothing.";
        }
        if (run.getMode() == IntegrityRun.Mode.QUICK) {
            return "Quick mode checked existence and size only. A file altered in place without "
                 + "changing its length is reported as intact. Run a deep verification for proof "
                 + "of contents.";
        }
        return "Deep mode re-read and re-hashed every recorded file. Every file reported intact "
             + "matched the SHA-256 captured when it was transferred.";
    }

    private void writeFinding(JsonGenerator gen, IntegrityFinding finding) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("path", finding.getPath().toString());
        gen.writeStringField("status", finding.getStatus().name());
        gen.writeBooleanField("problem", finding.isProblem());

        if (finding.getStatus() != IntegrityStatus.UNTRACKED) {
            gen.writeNumberField("expectedSize", finding.getExpectedSize());
        }
        if (finding.getObservedSize() != null) {
            gen.writeNumberField("observedSize", finding.getObservedSize());
        }
        if (finding.getExpectedHash() != null) {
            gen.writeStringField("expectedSha256", finding.getExpectedHash());
        }
        if (finding.getObservedHash() != null) {
            gen.writeStringField("observedSha256", finding.getObservedHash());
        }
        if (finding.getReason() != null) {
            gen.writeStringField("reason", finding.getReason());
        }
        gen.writeEndObject();
    }
}
