package com.mediascanner.report;

import com.mediascanner.db.Database;
import com.mediascanner.db.JobEventDao;
import com.mediascanner.db.ThroughputSampleDao;
import com.mediascanner.model.JobEvent;
import com.mediascanner.model.ThroughputSample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Buffers job events and throughput samples off the scan hot path.
 *
 * <p>Worker threads call {@link #record} and {@link #sample}, which are queue offers — never a
 * database round trip. A single daemon thread drains the queues and writes them in batches, so
 * {@code JOB_EVENT} and {@code JOB_THROUGHPUT_SAMPLE} have exactly one writer and never contend
 * with the hash-index writers for the WAL lock.
 *
 * <p>When the buffer is full {@link #record} <em>blocks</em> the calling worker rather than
 * dropping the event: a dropped event means a silently incomplete report, which is worse than
 * brief backpressure. Throughput samples are lossy by nature and are dropped instead.
 */
public class JobEventRecorder implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JobEventRecorder.class);

    static final int BATCH_SIZE = 500;
    static final long FLUSH_INTERVAL_MS = 5_000;
    private static final int QUEUE_CAPACITY = 10_000;

    /** Sentinel enqueued by close() so the flusher wakes immediately instead of waiting on poll(). */
    private static final JobEvent POISON = new JobEvent();

    private final Database database;
    private final JobEventDao eventDao;
    private final ThroughputSampleDao sampleDao;
    private final BlockingQueue<JobEvent> events = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final BlockingQueue<ThroughputSample> samples = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    private Thread flusher;
    private volatile boolean accepting;

    public JobEventRecorder(Database database) {
        this.database = database;
        this.eventDao = new JobEventDao(database);
        this.sampleDao = new ThroughputSampleDao(database);
    }

    public void start() {
        accepting = true;
        flusher = new Thread(this::flushLoop, "job-event-flusher");
        flusher.setDaemon(true);
        flusher.start();
        log.debug("JobEventRecorder started");
    }

    /** Enqueues an event. Blocks if the buffer is full; never drops. */
    public void record(JobEvent event) {
        if (event == null || !accepting) return;
        try {
            events.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Enqueues a throughput sample. Dropped if the buffer is full. */
    public void sample(ThroughputSample sample) {
        if (sample == null || !accepting) return;
        if (!samples.offer(sample)) {
            log.debug("Throughput sample buffer full; dropping one sample");
        }
    }

    private void flushLoop() {
        try {
            boolean stopping = false;
            while (!stopping) {
                List<JobEvent> batch = new ArrayList<>(BATCH_SIZE);
                try {
                    JobEvent first = events.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
                    if (first == POISON) {
                        stopping = true;
                    } else if (first != null) {
                        batch.add(first);
                        events.drainTo(batch, BATCH_SIZE - 1);
                        // drainTo may have pulled the sentinel along with real events.
                        if (removeSentinel(batch)) stopping = true;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    stopping = true;
                }
                writeEvents(batch);
                writeSamples();
            }
            drainRemaining();
        } finally {
            // The flusher owns a ThreadLocal SQLite connection; hand it back or a long session
            // accumulates one per job.
            database.releaseCurrentThreadConnection();
        }
    }

    /** Removes the sentinel from a drained batch by identity. Returns true if it was present. */
    private boolean removeSentinel(List<JobEvent> batch) {
        for (int i = 0; i < batch.size(); i++) {
            if (batch.get(i) == POISON) {
                batch.remove(i);
                return true;
            }
        }
        return false;
    }

    private void writeEvents(List<JobEvent> batch) {
        if (batch.isEmpty()) return;
        try {
            eventDao.insertBatch(batch);
        } catch (Exception e) {
            log.error("Failed to persist {} job events: {}", batch.size(), e.getMessage());
        }
    }

    private void writeSamples() {
        if (samples.isEmpty()) return;
        List<ThroughputSample> batch = new ArrayList<>();
        samples.drainTo(batch);
        try {
            sampleDao.insertBatch(batch);
        } catch (Exception e) {
            log.error("Failed to persist {} throughput samples: {}", batch.size(), e.getMessage());
        }
    }

    /** Writes whatever is still queued after the loop exits, in batch-sized chunks. */
    private void drainRemaining() {
        List<JobEvent> remaining = new ArrayList<>();
        events.drainTo(remaining);
        removeSentinel(remaining);
        for (int start = 0; start < remaining.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, remaining.size());
            writeEvents(new ArrayList<>(remaining.subList(start, end)));
        }
        writeSamples();
    }

    /** Stops accepting new records and flushes everything already buffered. */
    @Override
    public void close() {
        if (!accepting) return;
        accepting = false;
        events.offer(POISON);
        if (flusher != null) {
            try {
                flusher.join(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.debug("JobEventRecorder closed");
    }
}
