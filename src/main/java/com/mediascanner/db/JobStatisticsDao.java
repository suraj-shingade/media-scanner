package com.mediascanner.db;

import com.mediascanner.model.JobStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class JobStatisticsDao {

    private static final Logger log = LoggerFactory.getLogger(JobStatisticsDao.class);
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final Database database;

    public JobStatisticsDao(Database database) {
        this.database = database;
    }

    public void insert(JobStatistics stats) throws SQLException {
        String sql = """
            INSERT INTO JOB_STATISTICS
              (JOB_ID, STATUS, START_TIME, FILES_PROCESSED, FILES_FAILED, FILES_SKIPPED,
               DUPLICATES_FOUND, FILES_COPIED, FILES_MOVED, EMPTY_FILES_COUNT,
               SMALL_FILES_COUNT, CORRUPT_FILES_COUNT, TOTAL_BYTES_PROCESSED,
               TOTAL_BYTES_MOVED, TOTAL_BYTES_COPIED, TOTAL_BYTES_SKIPPED,
               DUPLICATE_BYTE_SAVINGS, TOTAL_FOLDERS_CREATED,
               AVG_MB_PER_SEC, PEAK_MB_PER_SEC, AVG_FILES_PER_SEC, PEAK_FILES_PER_SEC,
               AVG_CPU_PERCENT, PEAK_CPU_PERCENT, AVG_MEMORY_GB, PEAK_MEMORY_GB)
            VALUES (?, ?, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
            """;
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, stats.getJobId());
            ps.setString(2, stats.getStatus() != null ? stats.getStatus() : "RUNNING");
            ps.setString(3, stats.getStartTime().format(ISO_FMT));
            ps.executeUpdate();
        }
    }

    public void updateCounters(JobStatistics stats) throws SQLException {
        String sql = """
            UPDATE JOB_STATISTICS SET
              STATUS = ?, FILES_PROCESSED = ?, FILES_FAILED = ?, FILES_SKIPPED = ?,
              DUPLICATES_FOUND = ?, FILES_COPIED = ?, FILES_MOVED = ?,
              EMPTY_FILES_COUNT = ?, SMALL_FILES_COUNT = ?, CORRUPT_FILES_COUNT = ?,
              TOTAL_BYTES_PROCESSED = ?, TOTAL_BYTES_MOVED = ?, TOTAL_BYTES_COPIED = ?,
              TOTAL_BYTES_SKIPPED = ?, DUPLICATE_BYTE_SAVINGS = ?, TOTAL_FOLDERS_CREATED = ?,
              AVG_MB_PER_SEC = ?, PEAK_MB_PER_SEC = ?, AVG_FILES_PER_SEC = ?,
              PEAK_FILES_PER_SEC = ?, AVG_CPU_PERCENT = ?, PEAK_CPU_PERCENT = ?,
              AVG_MEMORY_GB = ?, PEAK_MEMORY_GB = ?,
              PEAK_DISK_READ_MB_SEC = ?, PEAK_DISK_WRITE_MB_SEC = ?
            WHERE JOB_ID = ?
            """;
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, stats.getStatus());
            ps.setLong(2, stats.getFilesProcessed());
            ps.setLong(3, stats.getFilesFailed());
            ps.setLong(4, stats.getFilesSkipped());
            ps.setLong(5, stats.getDuplicatesFound());
            ps.setLong(6, stats.getFilesCopied());
            ps.setLong(7, stats.getFilesMoved());
            ps.setLong(8, stats.getEmptyFilesCount());
            ps.setLong(9, stats.getSmallFilesCount());
            ps.setLong(10, stats.getCorruptFilesCount());
            ps.setLong(11, stats.getTotalBytesProcessed());
            ps.setLong(12, stats.getTotalBytesMoved());
            ps.setLong(13, stats.getTotalBytesCopied());
            ps.setLong(14, stats.getTotalBytesSkipped());
            ps.setLong(15, stats.getDuplicateByteSavings());
            ps.setLong(16, stats.getTotalFoldersCreated());
            ps.setDouble(17, stats.getAvgMbPerSec());
            ps.setDouble(18, stats.getPeakMbPerSec());
            ps.setDouble(19, stats.getAvgFilesPerSec());
            ps.setDouble(20, stats.getPeakFilesPerSec());
            ps.setDouble(21, stats.getAvgCpuPercent());
            ps.setDouble(22, stats.getPeakCpuPercent());
            ps.setDouble(23, stats.getAvgMemoryGb());
            ps.setDouble(24, stats.getPeakMemoryGb());
            ps.setDouble(25, stats.getPeakDiskReadMbSec());
            ps.setDouble(26, stats.getPeakDiskWriteMbSec());
            ps.setString(27, stats.getJobId());
            ps.executeUpdate();
        }
    }

    public JobStatistics findActiveJob() throws SQLException {
        String sql = "SELECT * FROM JOB_STATISTICS WHERE STATUS IN ('RUNNING', 'PAUSED') LIMIT 1";
        try (Statement stmt = database.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? mapRow(rs) : null;
        }
    }

    public JobStatistics findById(String jobId) throws SQLException {
        String sql = "SELECT * FROM JOB_STATISTICS WHERE JOB_ID = ?";
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    public void markCompleted(String jobId, LocalDateTime endTime) throws SQLException {
        String sql = "UPDATE JOB_STATISTICS SET STATUS = 'COMPLETED', END_TIME = ? WHERE JOB_ID = ?";
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, endTime.format(ISO_FMT));
            ps.setString(2, jobId);
            ps.executeUpdate();
        }
    }

    public void markStopped(String jobId, LocalDateTime endTime) throws SQLException {
        String sql = "UPDATE JOB_STATISTICS SET STATUS = 'STOPPED', END_TIME = ? WHERE JOB_ID = ?";
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, endTime.format(ISO_FMT));
            ps.setString(2, jobId);
            ps.executeUpdate();
        }
    }

    /**
     * Marks a job left RUNNING or PAUSED by a crash. Without this, findActiveJob keeps offering the
     * same dead job on every launch.
     */
    public void markInterrupted(String jobId) throws SQLException {
        String sql = "UPDATE JOB_STATISTICS SET STATUS = 'INTERRUPTED' WHERE JOB_ID = ?";
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, jobId);
            ps.executeUpdate();
        }
    }

    public void updateStatus(String jobId, String status) throws SQLException {
        String sql = "UPDATE JOB_STATISTICS SET STATUS = ? WHERE JOB_ID = ?";
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, jobId);
            ps.executeUpdate();
        }
    }

    /** All recorded jobs, newest first, for the Job History screen (FR-005-008). */
    public java.util.List<JobStatistics> findAll() throws SQLException {
        String sql = "SELECT * FROM JOB_STATISTICS ORDER BY START_TIME DESC";
        java.util.List<JobStatistics> jobs = new java.util.ArrayList<>();
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                jobs.add(mapRow(rs));
            }
        }
        return jobs;
    }

    public JobStatistics findByJobId(String jobId) throws SQLException {
        String sql = "SELECT * FROM JOB_STATISTICS WHERE JOB_ID = ?";
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    /** Removes the job row only. Callers must also clear its events and samples. */
    public int deleteJob(String jobId) throws SQLException {
        String sql = "DELETE FROM JOB_STATISTICS WHERE JOB_ID = ?";
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, jobId);
            return ps.executeUpdate();
        }
    }

    private JobStatistics mapRow(ResultSet rs) throws SQLException {
        JobStatistics s = new JobStatistics();
        s.setJobId(rs.getString("JOB_ID"));
        s.setStatus(rs.getString("STATUS"));
        String startStr = rs.getString("START_TIME");
        if (startStr != null) s.setStartTime(LocalDateTime.parse(startStr, ISO_FMT));
        String endStr = rs.getString("END_TIME");
        if (endStr != null) s.setEndTime(LocalDateTime.parse(endStr, ISO_FMT));
        s.setFilesProcessed(rs.getLong("FILES_PROCESSED"));
        s.setFilesFailed(rs.getLong("FILES_FAILED"));
        s.setFilesSkipped(rs.getLong("FILES_SKIPPED"));
        s.setDuplicatesFound(rs.getLong("DUPLICATES_FOUND"));
        s.setFilesCopied(rs.getLong("FILES_COPIED"));
        s.setFilesMoved(rs.getLong("FILES_MOVED"));
        s.setEmptyFilesCount(rs.getLong("EMPTY_FILES_COUNT"));
        s.setSmallFilesCount(rs.getLong("SMALL_FILES_COUNT"));
        s.setCorruptFilesCount(rs.getLong("CORRUPT_FILES_COUNT"));
        s.setTotalBytesProcessed(rs.getLong("TOTAL_BYTES_PROCESSED"));
        s.setTotalBytesMoved(rs.getLong("TOTAL_BYTES_MOVED"));
        s.setTotalBytesCopied(rs.getLong("TOTAL_BYTES_COPIED"));
        s.setTotalBytesSkipped(rs.getLong("TOTAL_BYTES_SKIPPED"));
        s.setDuplicateByteSavings(rs.getLong("DUPLICATE_BYTE_SAVINGS"));
        s.setTotalFoldersCreated(rs.getLong("TOTAL_FOLDERS_CREATED"));
        s.setAvgMbPerSec(rs.getDouble("AVG_MB_PER_SEC"));
        s.setPeakMbPerSec(rs.getDouble("PEAK_MB_PER_SEC"));
        s.setAvgFilesPerSec(rs.getDouble("AVG_FILES_PER_SEC"));
        s.setPeakFilesPerSec(rs.getDouble("PEAK_FILES_PER_SEC"));
        s.setAvgCpuPercent(rs.getDouble("AVG_CPU_PERCENT"));
        s.setPeakCpuPercent(rs.getDouble("PEAK_CPU_PERCENT"));
        s.setAvgMemoryGb(rs.getDouble("AVG_MEMORY_GB"));
        s.setPeakMemoryGb(rs.getDouble("PEAK_MEMORY_GB"));
        s.setPeakDiskReadMbSec(rs.getDouble("PEAK_DISK_READ_MB_SEC"));
        s.setPeakDiskWriteMbSec(rs.getDouble("PEAK_DISK_WRITE_MB_SEC"));
        return s;
    }
}
