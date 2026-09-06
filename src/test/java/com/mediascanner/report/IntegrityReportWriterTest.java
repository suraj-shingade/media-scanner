package com.mediascanner.report;

import com.mediascanner.model.IntegrityFinding;
import com.mediascanner.model.IntegrityRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The report is the artefact the user keeps, so what it says about its own limits matters as much as
 * what it says about the files.
 */
class IntegrityReportWriterTest {

    @TempDir
    Path reportDir;

    private IntegrityRun runWith(IntegrityRun.Mode mode) {
        return new IntegrityRun("VERIFY-TEST", Paths.get("C:", "archive"), mode, Instant.now());
    }

    @Test
    void testACleanDeepRunStillWritesAReport() throws Exception {
        IntegrityRun run = runWith(IntegrityRun.Mode.DEEP);
        run.record(IntegrityFinding.intact(Paths.get("C:", "archive", "a.jpg"), 10, "abc"));
        run.markFinished(Instant.now());

        Path written = new IntegrityReportWriter(reportDir).write(run);

        // A report only written on failure could never be used as evidence of success.
        assertThat(written).exists();
        String json = Files.readString(written);
        assertThat(json).contains("\"clean\" : true");
        assertThat(json).contains("\"INTACT\" : 1");
        assertThat(json).contains("\"filesVerified\" : 1");
    }

    @Test
    void testAQuickReportStatesWhatItCouldNotCheck() throws Exception {
        IntegrityRun run = runWith(IntegrityRun.Mode.QUICK);
        run.record(IntegrityFinding.intact(Paths.get("C:", "archive", "a.jpg"), 10, "abc"));
        run.markFinished(Instant.now());

        String json = Files.readString(new IntegrityReportWriter(reportDir).write(run));

        assertThat(json).contains("\"mode\" : \"QUICK\"");
        assertThat(json).contains("altered in place without changing its length");
    }

    @Test
    void testACancelledReportSaysItsSilenceMeansNothing() throws Exception {
        IntegrityRun run = runWith(IntegrityRun.Mode.DEEP);
        run.markCancelled();
        run.markFinished(Instant.now());

        String json = Files.readString(new IntegrityReportWriter(reportDir).write(run));

        assertThat(json).contains("\"completed\" : false");
        assertThat(json).contains("\"clean\" : false");
        assertThat(json).contains("cancelled before it finished");
    }

    @Test
    void testACorruptFindingCarriesBothHashes() throws Exception {
        IntegrityRun run = runWith(IntegrityRun.Mode.DEEP);
        run.record(IntegrityFinding.corrupted(
            Paths.get("C:", "archive", "bad.jpg"), 42, "expected-hash", "observed-hash"));
        run.markFinished(Instant.now());

        String json = Files.readString(new IntegrityReportWriter(reportDir).write(run));

        // SC-005: actionable without re-running the job.
        assertThat(json).contains("expected-hash").contains("observed-hash");
        assertThat(json).contains("\"status\" : \"CORRUPTED\"");
        assertThat(json).contains("\"problem\" : true");
    }
}
