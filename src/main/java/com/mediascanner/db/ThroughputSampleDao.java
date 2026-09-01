package com.mediascanner.db;

import com.mediascanner.model.ThroughputSample;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes {@code JOB_THROUGHPUT_SAMPLE} (FR-031).
 *
 * <p>Samples go in at 1 Hz, so an eight-hour job holds ~28 800 rows per series — far more than a
 * JavaFX {@code LineChart} renders acceptably. {@link #findDownsampled} therefore averages in SQL
 * down to a target point count rather than returning raw rows.
 */
public class ThroughputSampleDao {

    private final Database database;

    public ThroughputSampleDao(Database database) {
        this.database = database;
    }

    public void insertBatch(List<ThroughputSample> samples) throws SQLException {
        if (samples == null || samples.isEmpty()) return;

        String sql = """
            INSERT INTO JOB_THROUGHPUT_SAMPLE
              (JOB_ID, SAMPLE_AT, ELAPSED_SECONDS, FILES_PER_SEC, MB_PER_SEC,
               CPU_PERCENT, MEMORY_GB)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

        Connection conn = database.getConnection();
        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (ThroughputSample s : samples) {
                ps.setString(1, s.getJobId());
                ps.setString(2, (s.getSampleAt() != null ? s.getSampleAt() : Instant.now()).toString());
                ps.setLong(3, s.getElapsedSeconds());
                ps.setDouble(4, s.getFilesPerSec());
                ps.setDouble(5, s.getMbPerSec());
                ps.setDouble(6, s.getCpuPercent());
                ps.setDouble(7, s.getMemoryGb());
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException ex) {
            conn.rollback();
            throw ex;
        } finally {
            conn.setAutoCommit(previousAutoCommit);
        }
    }

    /**
     * Returns at most {@code targetPoints} samples for a job, averaging raw samples into
     * equal-width elapsed-time buckets. A job shorter than {@code targetPoints} seconds comes back
     * at full resolution.
     */
    public List<ThroughputSample> findDownsampled(String jobId, int targetPoints)
            throws SQLException {
        if (targetPoints < 1) targetPoints = 1;

        long maxElapsed = findMaxElapsedSeconds(jobId);
        long bucketWidth = Math.max(1, (maxElapsed / targetPoints) + 1);

        String sql = """
            SELECT (ELAPSED_SECONDS / ?) AS BUCKET,
                   MIN(SAMPLE_AT)        AS SAMPLE_AT,
                   MIN(ELAPSED_SECONDS)  AS ELAPSED_SECONDS,
                   AVG(FILES_PER_SEC)    AS FILES_PER_SEC,
                   AVG(MB_PER_SEC)       AS MB_PER_SEC,
                   AVG(CPU_PERCENT)      AS CPU_PERCENT,
                   AVG(MEMORY_GB)        AS MEMORY_GB
              FROM JOB_THROUGHPUT_SAMPLE
             WHERE JOB_ID = ?
             GROUP BY BUCKET
             ORDER BY BUCKET
            """;

        List<ThroughputSample> samples = new ArrayList<>();
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setLong(1, bucketWidth);
            ps.setString(2, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ThroughputSample s = new ThroughputSample();
                    s.setJobId(jobId);
                    String sampleAt = rs.getString("SAMPLE_AT");
                    if (sampleAt != null) s.setSampleAt(Instant.parse(sampleAt));
                    s.setElapsedSeconds(rs.getLong("ELAPSED_SECONDS"));
                    s.setFilesPerSec(rs.getDouble("FILES_PER_SEC"));
                    s.setMbPerSec(rs.getDouble("MB_PER_SEC"));
                    s.setCpuPercent(rs.getDouble("CPU_PERCENT"));
                    s.setMemoryGb(rs.getDouble("MEMORY_GB"));
                    samples.add(s);
                }
            }
        }
        return samples;
    }

    public long countByJobId(String jobId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM JOB_THROUGHPUT_SAMPLE WHERE JOB_ID = ?";
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    public int deleteByJobId(String jobId) throws SQLException {
        String sql = "DELETE FROM JOB_THROUGHPUT_SAMPLE WHERE JOB_ID = ?";
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, jobId);
            return ps.executeUpdate();
        }
    }

    private long findMaxElapsedSeconds(String jobId) throws SQLException {
        String sql = "SELECT COALESCE(MAX(ELAPSED_SECONDS), 0) FROM JOB_THROUGHPUT_SAMPLE WHERE JOB_ID = ?";
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }
}
