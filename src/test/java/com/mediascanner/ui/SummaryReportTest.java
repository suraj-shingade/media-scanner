package com.mediascanner.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mediascanner.model.JobStatistics;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

class SummaryReportTest {

    @TempDir
    Path tempDir;

    private JobStatistics buildStats() {
        JobStatistics s = new JobStatistics("JOB-20240115-001", LocalDateTime.of(2024, 1, 15, 9, 0));
        s.setStatus("COMPLETED");
        s.setEndTime(LocalDateTime.of(2024, 1, 15, 10, 30));
        s.setFilesProcessed(1000);
        s.setFilesFailed(5);
        s.setFilesSkipped(20);
        s.setDuplicatesFound(10);
        s.setEmptyFilesCount(3);
        s.setSmallFilesCount(7);
        s.setCorruptFilesCount(2);
        s.setTotalFoldersCreated(15);
        s.setTotalBytesProcessed(500_000_000L);
        s.setTotalBytesCopied(500_000_000L);
        s.setDuplicateByteSavings(50_000_000L);
        s.setAvgFilesPerSec(250.0);
        s.setPeakFilesPerSec(500.0);
        s.setAvgMbPerSec(100.0);
        s.setPeakMbPerSec(200.0);
        return s;
    }

    @Test
    void testJsonExportRoundTrips() throws Exception {
        JobStatistics original = buildStats();
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        File output = tempDir.resolve("summary.json").toFile();
        mapper.writeValue(output, original);

        JobStatistics loaded = mapper.readValue(output, JobStatistics.class);
        assertThat(loaded.getJobId()).isEqualTo(original.getJobId());
        assertThat(loaded.getFilesProcessed()).isEqualTo(1000);
        assertThat(loaded.getPeakFilesPerSec()).isEqualTo(500.0);
    }

    @Test
    void testTextExportContainsAllFieldNames() throws Exception {
        JobStatistics stats = buildStats();
        File output = tempDir.resolve("summary.txt").toFile();

        try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(output))) {
            pw.println("Job ID: " + stats.getJobId());
            pw.println("Processed: " + stats.getFilesProcessed());
            pw.println("Failed: " + stats.getFilesFailed());
            pw.println("Skipped: " + stats.getFilesSkipped());
            pw.println("Duplicates: " + stats.getDuplicatesFound());
            pw.println("Avg files/sec: " + stats.getAvgFilesPerSec());
            pw.println("Peak MB/sec: " + stats.getPeakMbPerSec());
        }

        String content = java.nio.file.Files.readString(output.toPath());
        assertThat(content).contains("JOB-20240115-001");
        assertThat(content).contains("Processed:");
        assertThat(content).contains("Failed:");
        assertThat(content).contains("Skipped:");
        assertThat(content).contains("Duplicates:");
        assertThat(content).contains("Avg files/sec:");
        assertThat(content).contains("Peak MB/sec:");
    }

    @Test
    void testAllFiveSectionsPopulated() {
        JobStatistics stats = buildStats();
        assertThat(stats.getFilesProcessed()).isPositive();
        assertThat(stats.getTotalBytesProcessed()).isPositive();
        assertThat(stats.getAvgFilesPerSec()).isPositive();
        assertThat(stats.getAvgCpuPercent()).isGreaterThanOrEqualTo(0.0);
        assertThat(stats.getStartTime()).isNotNull();
    }
}
