package com.mediascanner.db;

import com.mediascanner.model.FileHashRecord;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * The duplicate gate must stay atomic under concurrency (T027, research D3).
 *
 * <p>Before V002 this was handled by a UNIQUE constraint that happened to serialise a
 * check-then-act pair in ScanEngine. Now it is an explicit INSERT OR IGNORE, and exactly one
 * claimant must win no matter how many workers race for the same content.
 *
 * <p>This also exercises the per-thread SQLite connections added during the audit: every worker
 * here gets its own connection from the same Database instance.
 */
class HashCanonicalConcurrencyIT {

    @TempDir
    Path tempDir;

    private Database db;
    private HashIndexDao dao;

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(tempDir.resolve("concurrent.db"));
        dao = new HashIndexDao(db);
    }

    @AfterEach
    void tearDown() {
        if (db != null) db.close();
    }

    @Test
    void testExactlyOneClaimantWinsForTheSameHash() throws Exception {
        int workers = 16;
        String sharedHash = "shared-content-hash";
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger claimed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < workers; i++) {
                final String path = "C:\\src\\copy-" + i + ".jpg";
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        if (dao.claimCanonical(sharedHash, path)) {
                            claimed.incrementAndGet();
                        } else {
                            rejected.incrementAndGet();
                        }
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    } finally {
                        db.releaseCurrentThreadConnection();
                    }
                    return null;
                }));
            }

            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(claimed.get()).as("exactly one canonical claimant").isEqualTo(1);
        assertThat(rejected.get()).as("everyone else is a duplicate").isEqualTo(workers - 1);
        assertThat(dao.findCanonicalPath(sharedHash)).isNotNull();
    }

    @Test
    void testDistinctHashesAllClaimSuccessfully() throws Exception {
        int workers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        AtomicInteger claimed = new AtomicInteger();

        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < workers; i++) {
                final int n = i;
                futures.add(pool.submit(() -> {
                    try {
                        if (dao.claimCanonical("hash-" + n, "C:\\src\\file-" + n + ".jpg")) {
                            claimed.incrementAndGet();
                        }
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    } finally {
                        db.releaseCurrentThreadConnection();
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(claimed.get()).isEqualTo(workers);
    }

    /** SC-007: a duplicate path must cache its own hash so it is never re-read. */
    @Test
    void testDuplicatePathStillCachesItsHash() throws Exception {
        Instant now = Instant.now();
        insert("C:\\Photos\\original.jpg", "original.jpg", 2048, "same-hash", now);
        insert("C:\\Backup\\duplicate.jpg", "duplicate.jpg", 2048, "same-hash", now);

        assertThat(dao.findByFilePath("C:\\Photos\\original.jpg")).isNotNull();
        assertThat(dao.findByFilePath("C:\\Backup\\duplicate.jpg"))
            .as("the duplicate path caches its hash instead of being re-read every run")
            .isNotNull();

        // And the cache is honoured on the next run for both paths.
        assertThat(dao.findAndValidateCache("C:\\Backup\\duplicate.jpg", 2048, now)).isNotNull();
    }

    @Test
    void testClearAllResetsBothIndexAndGate() throws Exception {
        insert("C:\\a.jpg", "a.jpg", 10, "h", Instant.now());
        dao.claimCanonical("h", "C:\\a.jpg");

        dao.clearAll();

        assertThat(dao.findByFilePath("C:\\a.jpg")).isNull();
        assertThat(dao.findCanonicalPath("h"))
            .as("clearing the cache must also clear the gate, or nothing can ever be canonical again")
            .isNull();
    }

    private void insert(String path, String name, long size, String hash, Instant ts)
            throws Exception {
        FileHashRecord r = new FileHashRecord();
        r.setFilePath(path);
        r.setFileName(name);
        r.setFileSizeBytes(size);
        r.setFileModificationTs(ts);
        r.setSha256Hash(hash);
        r.setCreatedAt(ts);
        r.setLastProcessedAt(ts);
        dao.insert(r);
    }
}
