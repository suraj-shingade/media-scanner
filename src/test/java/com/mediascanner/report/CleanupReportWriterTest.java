package com.mediascanner.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediascanner.engine.CleanupEngine;
import com.mediascanner.model.CleanupCandidate;
import com.mediascanner.model.CleanupRun;
import com.mediascanner.model.MimeGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The report is the only surviving evidence of a permanent deletion, so it is tested for what it
 * must still say after the files themselves are gone (FR-055 – FR-058).
 */
class CleanupReportWriterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void recordsDeletedSkippedAndFailedEntries(@TempDir Path dir) throws IOException {
        CleanupRun run = new CleanupRun("CLEAN-20260901-120000", dir.resolve("src"), Instant.now());

        CleanupCandidate deleted = new CleanupCandidate(
            dir.resolve("installer.exe"), 4096, "application/x-dosexec", MimeGroup.EXECUTABLE);
        deleted.markDeleted();

        CleanupCandidate skipped = new CleanupCandidate(
            dir.resolve("changed.exe"), 2048, "application/x-dosexec", MimeGroup.EXECUTABLE);
        skipped.markSkipped("Contents changed since preview: now Protected media");

        CleanupCandidate failed = new CleanupCandidate(
            dir.resolve("locked.exe"), 1024, "application/x-dosexec", MimeGroup.EXECUTABLE);
        failed.markFailed("AccessDeniedException: locked.exe");

        CleanupEngine.DeleteResult result = new CleanupEngine.DeleteResult();
        result.getDeleted().add(deleted);
        result.getSkipped().add(skipped);
        result.getFailed().add(failed);

        CleanupReportWriter writer = new CleanupReportWriter(dir.resolve("reports"));
        Path report = writer.write(run, result, null);

        assertThat(report).isNotNull().exists();

        JsonNode json = mapper.readTree(Files.readAllBytes(report));
        assertThat(json.get("runId").asText()).isEqualTo("CLEAN-20260901-120000");
        assertThat(json.get("permanentDeletion").asBoolean()).isTrue();
        assertThat(json.get("filesDeleted").asInt()).isEqualTo(1);
        assertThat(json.get("bytesDeleted").asLong()).isEqualTo(4096);
        assertThat(json.get("deleted")).hasSize(1);
        assertThat(json.get("skipped")).hasSize(1);
        assertThat(json.get("failed")).hasSize(1);

        assertThat(json.get("deleted").get(0).get("path").asText()).contains("installer.exe");
        assertThat(json.get("deleted").get(0).get("group").asText()).isEqualTo("EXECUTABLE");
        assertThat(json.get("skipped").get(0).get("reason").asText()).contains("Contents changed");
        assertThat(json.get("failed").get(0).get("reason").asText()).contains("AccessDenied");
    }

    @Test
    void recordsPrunedDirectories(@TempDir Path dir) throws IOException {
        CleanupRun run = new CleanupRun("CLEAN-20260901-130000", dir, Instant.now());
        CleanupEngine.PruneResult prune = new CleanupEngine.PruneResult();
        prune.getRemoved().add(dir.resolve("a/b/c"));
        prune.getRemoved().add(dir.resolve("a/b"));

        CleanupReportWriter writer = new CleanupReportWriter(dir.resolve("reports"));
        Path report = writer.write(run, null, prune);

        JsonNode json = mapper.readTree(Files.readAllBytes(report));
        assertThat(json.get("directoriesRemoved").asInt()).isEqualTo(2);
        assertThat(json.get("directoriesRemovedPaths")).hasSize(2);
    }

    @Test
    void writesNothingWhenNothingHappened(@TempDir Path dir) throws IOException {
        CleanupRun run = new CleanupRun("CLEAN-20260901-140000", dir, Instant.now());
        CleanupReportWriter writer = new CleanupReportWriter(dir.resolve("reports"));

        Path report = writer.write(run, new CleanupEngine.DeleteResult(),
            new CleanupEngine.PruneResult());

        assertThat(report)
            .as("an empty report file would be noise, not a record")
            .isNull();
    }

    @Test
    void reportRemainsReadableAfterTheFilesAreGone(@TempDir Path dir) throws IOException {
        Path victim = Files.write(dir.resolve("gone.exe"), new byte[]{1, 2, 3});
        CleanupRun run = new CleanupRun("CLEAN-20260901-150000", dir, Instant.now());

        CleanupCandidate candidate = new CleanupCandidate(
            victim, 3, "application/x-dosexec", MimeGroup.EXECUTABLE);
        candidate.markDeleted();
        CleanupEngine.DeleteResult result = new CleanupEngine.DeleteResult();
        result.getDeleted().add(candidate);

        CleanupReportWriter writer = new CleanupReportWriter(dir.resolve("reports"));
        Path report = writer.write(run, result, null);
        Files.delete(victim);

        // Nothing about reading the record may depend on the deleted file still existing.
        JsonNode json = mapper.readTree(Files.readAllBytes(report));
        assertThat(victim).doesNotExist();
        assertThat(json.get("deleted").get(0).get("path").asText()).contains("gone.exe");
    }
}
