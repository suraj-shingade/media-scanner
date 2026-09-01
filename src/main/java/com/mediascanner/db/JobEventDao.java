package com.mediascanner.db;

import com.mediascanner.model.JobEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reads and writes {@code JOB_EVENT} — the per-file record behind the skipped, failure and
 * duplicate reports.
 *
 * <p>Writes are always batched (one transaction per batch) because the alternative is one
 * {@code fsync} per non-transferred file. Reads for reporting are <em>streamed</em>: the caller
 * receives each row through a {@link Consumer} and no list is ever materialised, which is what
 * keeps report generation at constant memory regardless of entry count (FR-005-005, SC-003).
 */
public class JobEventDao {

    private static final Logger log = LoggerFactory.getLogger(JobEventDao.class);

    private final Database database;

    public JobEventDao(Database database) {
        this.database = database;
    }

    public void insertBatch(List<JobEvent> events) throws SQLException {
        if (events == null || events.isEmpty()) return;

        String sql = """
            INSERT INTO JOB_EVENT
              (JOB_ID, OUTCOME, FILE_PATH, FILE_NAME, FILE_SIZE, REASON,
               SHA256_HASH, MATCHED_PATH, DESTINATION_PATH, RECORDED_AT)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        Connection conn = database.getConnection();
        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (JobEvent e : events) {
                ps.setString(1, e.getJobId());
                ps.setString(2, e.getOutcome().name());
                ps.setString(3, e.getFilePath());
                ps.setString(4, e.getFileName());
                ps.setLong(5, e.getFileSize());
                ps.setString(6, e.getReason());
                ps.setString(7, e.getSha256Hash());
                ps.setString(8, e.getMatchedPath());
                ps.setString(9, e.getDestinationPath());
                ps.setString(10, (e.getRecordedAt() != null ? e.getRecordedAt() : Instant.now()).toString());
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
     * Streams every event for a job and outcome to {@code consumer}, oldest first, stopping after
     * {@code limit} rows. Nothing is buffered — this is a forward-only cursor walk.
     *
     * @param limit maximum rows to deliver; pass {@link Integer#MAX_VALUE} for all
     * @return the number of rows actually delivered
     */
    public long streamByOutcome(String jobId, JobEvent.Outcome outcome, int limit,
                                Consumer<JobEvent> consumer) throws SQLException {
        String sql = """
            SELECT * FROM JOB_EVENT
             WHERE JOB_ID = ? AND OUTCOME = ?
             ORDER BY ID
             LIMIT ?
            """;
        long delivered = 0;
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, jobId);
            ps.setString(2, outcome.name());
            ps.setInt(3, limit);
            ps.setFetchSize(1000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    consumer.accept(mapRow(rs));
                    delivered++;
                }
            }
        }
        return delivered;
    }

    public long countByOutcome(String jobId, JobEvent.Outcome outcome) throws SQLException {
        String sql = "SELECT COUNT(*) FROM JOB_EVENT WHERE JOB_ID = ? AND OUTCOME = ?";
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, jobId);
            ps.setString(2, outcome.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    public long sumBytesByOutcome(String jobId, JobEvent.Outcome outcome) throws SQLException {
        String sql = "SELECT COALESCE(SUM(FILE_SIZE), 0) FROM JOB_EVENT WHERE JOB_ID = ? AND OUTCOME = ?";
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, jobId);
            ps.setString(2, outcome.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    public int deleteByJobId(String jobId) throws SQLException {
        String sql = "DELETE FROM JOB_EVENT WHERE JOB_ID = ?";
        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, jobId);
            return ps.executeUpdate();
        }
    }

    private JobEvent mapRow(ResultSet rs) throws SQLException {
        JobEvent e = new JobEvent();
        e.setId(rs.getLong("ID"));
        e.setJobId(rs.getString("JOB_ID"));
        e.setOutcome(JobEvent.Outcome.valueOf(rs.getString("OUTCOME")));
        e.setFilePath(rs.getString("FILE_PATH"));
        e.setFileName(rs.getString("FILE_NAME"));
        e.setFileSize(rs.getLong("FILE_SIZE"));
        e.setReason(rs.getString("REASON"));
        e.setSha256Hash(rs.getString("SHA256_HASH"));
        e.setMatchedPath(rs.getString("MATCHED_PATH"));
        e.setDestinationPath(rs.getString("DESTINATION_PATH"));
        String recordedAt = rs.getString("RECORDED_AT");
        if (recordedAt != null) e.setRecordedAt(Instant.parse(recordedAt));
        return e;
    }
}
