package com.mediascanner.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediascanner.model.JobStatistics;
import com.mediascanner.model.ThroughputSample;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class SummaryExporterTest {

    @TempDir
    Path tempDir;

    private final SummaryExporter exporter = new SummaryExporter();
    private final ObjectMapper mapper = new ObjectMapper();

    private JobStatistics sampleStats() {
        JobStatistics s = new JobStatistics("job-20260901-001",
            LocalDateTime.of(2026, 9, 1, 10, 0, 0));
        s.setEndTime(LocalDateTime.of(2026, 9, 1, 11, 30, 45));
        s.setStatus("COMPLETED");
        s.setFilesProcessed(12_345);
        s.setFilesCopied(12_000);
        s.setFilesMoved(345);
        s.setFilesSkipped(67);
        s.setFilesFailed(3);
        s.setDuplicatesFound(89);
        s.setEmptyFilesCount(2);
        s.setSmallFilesCount(60);
        s.setCorruptFilesCount(3);
        s.setTotalFoldersCreated(48);
        s.setTotalBytesProcessed(53_687_091_200L);
        s.setTotalBytesCopied(50_000_000_000L);
        s.setTotalBytesMoved(3_687_091_200L);
        s.setTotalBytesSkipped(1_048_576L);
        s.setDuplicateByteSavings(5_368_709_120L);
        s.setAvgFilesPerSec(120.5);
        s.setPeakFilesPerSec(880.25);
        s.setAvgMbPerSec(45.75);
        s.setPeakMbPerSec(310.5);
        s.setAvgCpuPercent(62.5);
        s.setPeakCpuPercent(98.75);
        s.setAvgMemoryGb(3.25);
        s.setPeakMemoryGb(7.5);
        return s;
    }

    private List<ThroughputSample> sampleSeries(int count) {
        List<ThroughputSample> samples = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            samples.add(new ThroughputSample("job-20260901-001", Instant.now(), i,
                100 + i, 40 + (i % 7), 50 + (i % 30), 2.0 + (i % 3)));
        }
        return samples;
    }

    /** US4: all three formats must carry the same figures. This is the point of the field map. */
    @Test
    void testAllThreeFormatsCarryIdenticalFigures() throws Exception {
        JobStatistics stats = sampleStats();
        Map<String, String> expected = exporter.toFieldMap(stats);

        Path json = tempDir.resolve("s.json");
        Path csv = tempDir.resolve("s.csv");
        Path html = tempDir.resolve("s.html");
        exporter.export(json, SummaryExporter.Format.JSON, stats, sampleSeries(20));
        exporter.export(csv, SummaryExporter.Format.CSV, stats, sampleSeries(20));
        exporter.export(html, SummaryExporter.Format.HTML, stats, sampleSeries(20));

        JsonNode jsonRoot = mapper.readTree(Files.readAllBytes(json));
        String csvText = Files.readString(csv);
        String htmlText = Files.readString(html);

        for (Map.Entry<String, String> e : expected.entrySet()) {
            assertThat(jsonRoot.get(e.getKey()).asText())
                .as("JSON field " + e.getKey()).isEqualTo(e.getValue());
            assertThat(csvText).as("CSV contains " + e.getKey())
                .contains(SummaryExporter.csvEscape(e.getValue()));
            assertThat(htmlText).as("HTML contains " + e.getKey())
                .contains(SummaryExporter.htmlEscape(e.getValue()));
        }
    }

    @Test
    void testFieldMapCoversTheConstitutionSummary() {
        Map<String, String> f = exporter.toFieldMap(sampleStats());

        assertThat(f).containsKeys(
            "Files processed", "Files copied", "Files moved", "Files skipped", "Files failed",
            "Duplicates found", "Folders created",
            "Data processed", "Data copied", "Data moved", "Data skipped", "Duplicate data saved",
            "Average files/sec", "Peak files/sec", "Average MB/sec", "Peak MB/sec",
            "Average GB/sec", "Peak GB/sec",
            "Average CPU %", "Peak CPU %", "Average memory GB", "Peak memory GB",
            "Start time", "End time", "Duration");
        assertThat(f.get("Duration")).isEqualTo("1:30:45");
        assertThat(f.get("Start time")).isEqualTo("2026-09-01T10:00:00");
    }

    @Test
    void testCsvHasHeaderAndSingleValueRow() throws Exception {
        Path csv = tempDir.resolve("s.csv");
        exporter.export(csv, SummaryExporter.Format.CSV, sampleStats(), List.of());

        List<String> lines = Files.readAllLines(csv);
        assertThat(lines).hasSize(2);
        long headerCols = lines.get(0).chars().filter(c -> c == ',').count();
        assertThat(headerCols).isGreaterThan(20);
        assertThat(lines.get(0)).startsWith("Job ID,Status");
    }

    /** FR-005-013: a path with a comma, a quote or a newline must not break the CSV. */
    @Test
    void testCsvEscaping() {
        assertThat(SummaryExporter.csvEscape("plain")).isEqualTo("plain");
        assertThat(SummaryExporter.csvEscape("a,b")).isEqualTo("\"a,b\"");
        assertThat(SummaryExporter.csvEscape("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(SummaryExporter.csvEscape("line1\nline2")).isEqualTo("\"line1\nline2\"");
        assertThat(SummaryExporter.csvEscape(null)).isEmpty();
    }

    @Test
    void testHtmlEscaping() {
        assertThat(SummaryExporter.htmlEscape("<script>alert('x')</script>"))
            .isEqualTo("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;");
        assertThat(SummaryExporter.htmlEscape("a & b")).isEqualTo("a &amp; b");
    }

    /** US4 AS-3: self-contained means no external reference of any kind. */
    @Test
    void testHtmlIsSelfContained() throws Exception {
        Path html = tempDir.resolve("s.html");
        exporter.export(html, SummaryExporter.Format.HTML, sampleStats(), sampleSeries(50));

        String text = Files.readString(html);
        assertThat(text).doesNotContain("http://").doesNotContain("https://");
        assertThat(text).doesNotContain("<script");
        assertThat(text).doesNotContain("src=");
        assertThat(text).contains("<svg").contains("<polyline");
    }

    @Test
    void testHtmlChartFallsBackWhenTooFewSamples() throws Exception {
        Path html = tempDir.resolve("short.html");
        exporter.export(html, SummaryExporter.Format.HTML, sampleStats(), List.of());

        String text = Files.readString(html);
        assertThat(text).contains("No throughput history recorded");
        assertThat(text).doesNotContain("<polyline");
    }

    @Test
    void testChartHandlesAllZeroSamplesWithoutDivideByZero() {
        List<ThroughputSample> flat = List.of(
            new ThroughputSample("j", Instant.now(), 0, 0, 0, 0, 0),
            new ThroughputSample("j", Instant.now(), 0, 0, 0, 0, 0));

        String svg = exporter.renderChartSvg(flat);

        assertThat(svg).contains("<svg");
        assertThat(svg).doesNotContain("NaN").doesNotContain("Infinity");
    }

    @Test
    void testExportCreatesMissingDirectories() throws Exception {
        Path nested = tempDir.resolve("a").resolve("b").resolve("s.json");
        exporter.export(nested, SummaryExporter.Format.JSON, sampleStats(), List.of());
        assertThat(nested).exists();
    }

    @Test
    void testRunningJobWithNoEndTimeExportsCleanly() throws Exception {
        JobStatistics running = new JobStatistics("job-live", LocalDateTime.now());
        Path json = tempDir.resolve("live.json");

        exporter.export(json, SummaryExporter.Format.JSON, running, List.of());

        JsonNode root = mapper.readTree(Files.readAllBytes(json));
        assertThat(root.get("End time").asText()).isEmpty();
        assertThat(root.get("Duration").asText()).isEmpty();
        assertThat(root.get("Job ID").asText()).isEqualTo("job-live");
    }
}
