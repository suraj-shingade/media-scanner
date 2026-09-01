package com.mediascanner.report;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.mediascanner.model.JobStatistics;
import com.mediascanner.model.ThroughputSample;
import com.mediascanner.ui.DataUnitFormatter;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports a job summary as JSON, CSV, or a self-contained HTML page (FR-005-010).
 *
 * <p>All three formats are generated from one ordered field map, so they cannot drift apart — the
 * matching test asserts exactly that. The HTML embeds its own CSS and draws the throughput chart as
 * inline SVG: a viewer with no network must still see the whole thing (US4 AS-3).
 */
public class SummaryExporter {

    public enum Format { JSON, CSV, HTML }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private final JsonFactory jsonFactory = new JsonFactory();

    /**
     * Every figure the constitution's end-of-job summary requires, in a stable display order.
     * Values are strings so all three formats render identically.
     */
    public Map<String, String> toFieldMap(JobStatistics s) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("Job ID", nullSafe(s.getJobId()));
        f.put("Status", nullSafe(s.getStatus()));
        f.put("Start time", s.getStartTime() != null ? s.getStartTime().format(ISO) : "");
        f.put("End time", s.getEndTime() != null ? s.getEndTime().format(ISO) : "");
        f.put("Duration", formatDuration(s));

        f.put("Files processed", String.valueOf(s.getFilesProcessed()));
        f.put("Files copied", String.valueOf(s.getFilesCopied()));
        f.put("Files moved", String.valueOf(s.getFilesMoved()));
        f.put("Files skipped", String.valueOf(s.getFilesSkipped()));
        f.put("Files failed", String.valueOf(s.getFilesFailed()));
        f.put("Duplicates found", String.valueOf(s.getDuplicatesFound()));
        f.put("Empty files", String.valueOf(s.getEmptyFilesCount()));
        f.put("Small files", String.valueOf(s.getSmallFilesCount()));
        f.put("Corrupt files", String.valueOf(s.getCorruptFilesCount()));
        f.put("Folders created", String.valueOf(s.getTotalFoldersCreated()));

        f.put("Data processed", DataUnitFormatter.format(s.getTotalBytesProcessed()));
        f.put("Data copied", DataUnitFormatter.format(s.getTotalBytesCopied()));
        f.put("Data moved", DataUnitFormatter.format(s.getTotalBytesMoved()));
        f.put("Data skipped", DataUnitFormatter.format(s.getTotalBytesSkipped()));
        f.put("Duplicate data saved", DataUnitFormatter.format(s.getDuplicateByteSavings()));

        f.put("Average files/sec", decimal(s.getAvgFilesPerSec()));
        f.put("Peak files/sec", decimal(s.getPeakFilesPerSec()));
        f.put("Average MB/sec", decimal(s.getAvgMbPerSec()));
        f.put("Peak MB/sec", decimal(s.getPeakMbPerSec()));
        f.put("Average GB/sec", decimal(s.getAvgMbPerSec() / 1024.0));
        f.put("Peak GB/sec", decimal(s.getPeakMbPerSec() / 1024.0));
        f.put("Average CPU %", decimal(s.getAvgCpuPercent()));
        f.put("Peak CPU %", decimal(s.getPeakCpuPercent()));
        f.put("Average memory GB", decimal(s.getAvgMemoryGb()));
        f.put("Peak memory GB", decimal(s.getPeakMemoryGb()));
        return f;
    }

    public void export(Path destination, Format format, JobStatistics stats,
                       List<ThroughputSample> samples) throws IOException {
        if (destination.getParent() != null) {
            Files.createDirectories(destination.getParent());
        }
        switch (format) {
            case JSON -> writeJson(destination, stats);
            case CSV -> writeCsv(destination, stats);
            case HTML -> writeHtml(destination, stats, samples);
        }
    }

    private void writeJson(Path destination, JobStatistics stats) throws IOException {
        try (OutputStream out = Files.newOutputStream(destination);
             JsonGenerator gen = jsonFactory.createGenerator(out, JsonEncoding.UTF8)) {
            gen.setPrettyPrinter(new DefaultPrettyPrinter());
            gen.writeStartObject();
            for (Map.Entry<String, String> e : toFieldMap(stats).entrySet()) {
                gen.writeStringField(e.getKey(), e.getValue());
            }
            gen.writeEndObject();
        }
    }

    private void writeCsv(Path destination, JobStatistics stats) throws IOException {
        Map<String, String> fields = toFieldMap(stats);
        StringBuilder header = new StringBuilder();
        StringBuilder values = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (!first) {
                header.append(',');
                values.append(',');
            }
            header.append(csvEscape(e.getKey()));
            values.append(csvEscape(e.getValue()));
            first = false;
        }
        try (Writer w = Files.newBufferedWriter(destination, StandardCharsets.UTF_8)) {
            w.write(header.toString());
            w.write("\n");
            w.write(values.toString());
            w.write("\n");
        }
    }

    /** RFC 4180: quote when the value contains a comma, quote, CR or LF; double any inner quote. */
    static String csvEscape(String value) {
        if (value == null) return "";
        boolean needsQuoting = value.contains(",") || value.contains("\"")
            || value.contains("\n") || value.contains("\r");
        if (!needsQuoting) return value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    static String htmlEscape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;");
    }

    private void writeHtml(Path destination, JobStatistics stats, List<ThroughputSample> samples)
            throws IOException {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
            .append("<meta charset=\"utf-8\">\n")
            .append("<title>MediaScanner Job Summary ")
            .append(htmlEscape(stats.getJobId())).append("</title>\n")
            .append("<style>\n")
            .append("body{font-family:system-ui,-apple-system,Segoe UI,sans-serif;margin:2rem auto;")
            .append("max-width:60rem;padding:0 1rem;color:#1a1a1a;background:#fff}\n")
            .append("h1{font-size:1.5rem;margin-bottom:.25rem}\n")
            .append(".sub{color:#666;margin-top:0;font-size:.9rem}\n")
            .append("table{border-collapse:collapse;width:100%;margin:1.5rem 0}\n")
            .append("td,th{border-bottom:1px solid #e5e5e5;padding:.45rem .6rem;text-align:left;")
            .append("font-size:.9rem}\n")
            .append("th{width:40%;font-weight:600;color:#444}\n")
            .append("td{font-variant-numeric:tabular-nums}\n")
            .append("svg{max-width:100%;height:auto;border:1px solid #e5e5e5;border-radius:4px}\n")
            .append("@media(prefers-color-scheme:dark){body{background:#161616;color:#eee}")
            .append("td,th{border-color:#333}th{color:#bbb}.sub{color:#999}")
            .append("svg{border-color:#333}}\n")
            .append("</style>\n</head>\n<body>\n");

        html.append("<h1>MediaScanner Job Summary</h1>\n")
            .append("<p class=\"sub\">").append(htmlEscape(stats.getJobId())).append("</p>\n");

        html.append("<table>\n");
        for (Map.Entry<String, String> e : toFieldMap(stats).entrySet()) {
            html.append("<tr><th>").append(htmlEscape(e.getKey()))
                .append("</th><td>").append(htmlEscape(e.getValue()))
                .append("</td></tr>\n");
        }
        html.append("</table>\n");

        html.append(renderChartSvg(samples));
        html.append("</body>\n</html>\n");

        Files.writeString(destination, html.toString(), StandardCharsets.UTF_8);
    }

    /** Inline SVG line chart — no script, no external asset, so it renders offline. */
    String renderChartSvg(List<ThroughputSample> samples) {
        if (samples == null || samples.size() < 2) {
            return "<p class=\"sub\">No throughput history recorded for this job.</p>\n";
        }
        int width = 900;
        int height = 260;
        int pad = 36;

        double maxFiles = samples.stream().mapToDouble(ThroughputSample::getFilesPerSec).max().orElse(1);
        double maxMb = samples.stream().mapToDouble(ThroughputSample::getMbPerSec).max().orElse(1);
        long maxElapsed = samples.stream().mapToLong(ThroughputSample::getElapsedSeconds).max().orElse(1);
        if (maxFiles <= 0) maxFiles = 1;
        if (maxMb <= 0) maxMb = 1;
        if (maxElapsed <= 0) maxElapsed = 1;

        StringBuilder files = new StringBuilder();
        StringBuilder mb = new StringBuilder();
        for (ThroughputSample s : samples) {
            double x = pad + (width - 2.0 * pad) * s.getElapsedSeconds() / maxElapsed;
            double yFiles = height - pad - (height - 2.0 * pad) * s.getFilesPerSec() / maxFiles;
            double yMb = height - pad - (height - 2.0 * pad) * s.getMbPerSec() / maxMb;
            files.append(String.format(java.util.Locale.ROOT, "%.1f,%.1f ", x, yFiles));
            mb.append(String.format(java.util.Locale.ROOT, "%.1f,%.1f ", x, yMb));
        }

        return "<h2 style=\"font-size:1.1rem\">Throughput</h2>\n"
            + "<svg viewBox=\"0 0 " + width + " " + height + "\" role=\"img\" "
            + "aria-label=\"Throughput over the life of the job\">\n"
            + "<line x1=\"" + pad + "\" y1=\"" + (height - pad) + "\" x2=\"" + (width - pad)
            + "\" y2=\"" + (height - pad) + "\" stroke=\"#999\" stroke-width=\"1\"/>\n"
            + "<line x1=\"" + pad + "\" y1=\"" + pad + "\" x2=\"" + pad + "\" y2=\""
            + (height - pad) + "\" stroke=\"#999\" stroke-width=\"1\"/>\n"
            + "<polyline fill=\"none\" stroke=\"#2f6fd0\" stroke-width=\"1.5\" points=\""
            + files.toString().trim() + "\"/>\n"
            + "<polyline fill=\"none\" stroke=\"#d0762f\" stroke-width=\"1.5\" points=\""
            + mb.toString().trim() + "\"/>\n"
            + "<text x=\"" + (pad + 6) + "\" y=\"" + (pad - 12)
            + "\" font-size=\"12\" fill=\"#2f6fd0\">files/sec (peak "
            + decimal(maxFiles) + ")</text>\n"
            + "<text x=\"" + (pad + 220) + "\" y=\"" + (pad - 12)
            + "\" font-size=\"12\" fill=\"#d0762f\">MB/sec (peak "
            + decimal(maxMb) + ")</text>\n"
            + "<text x=\"" + (width - pad) + "\" y=\"" + (height - pad + 18)
            + "\" font-size=\"11\" fill=\"#777\" text-anchor=\"end\">"
            + maxElapsed + "s</text>\n"
            + "</svg>\n";
    }

    private String formatDuration(JobStatistics s) {
        if (s.getStartTime() == null || s.getEndTime() == null) return "";
        Duration d = Duration.between(s.getStartTime(), s.getEndTime());
        return String.format("%d:%02d:%02d", d.toHours(), d.toMinutesPart(), d.toSecondsPart());
    }

    private static String decimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }
}
