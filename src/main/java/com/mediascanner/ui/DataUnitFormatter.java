package com.mediascanner.ui;

public class DataUnitFormatter {

    private static final long KB = 1_024L;
    private static final long MB = 1_024L * KB;
    private static final long GB = 1_024L * MB;
    private static final long TB = 1_024L * GB;

    public static String format(long bytes) {
        if (bytes < KB) return bytes + " B";
        if (bytes < MB) return String.format("%.1f KB", (double) bytes / KB);
        if (bytes < GB) return String.format("%.1f MB", (double) bytes / MB);
        if (bytes < TB) return String.format("%.2f GB", (double) bytes / GB);
        return String.format("%.2f TB", (double) bytes / TB);
    }

    public static String formatRate(double mbPerSec) {
        if (mbPerSec >= 1024.0) {
            return String.format("%.2f GB/s", mbPerSec / 1024.0);
        }
        if (mbPerSec >= 1.0) {
            return String.format("%.1f MB/s", mbPerSec);
        }
        return String.format("%.1f KB/s", mbPerSec * 1024.0);
    }
}
