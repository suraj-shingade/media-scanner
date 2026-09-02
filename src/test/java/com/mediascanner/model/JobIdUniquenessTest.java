package com.mediascanner.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Job identifiers must be unique, including across application restarts.
 *
 * <p>They were not. The counter behind {@code JOB-yyyyMMdd-NNN} is a static starting at 1, so the
 * first job of <em>every JVM</em> on a given day was {@code JOB-<date>-001}. Restarting the
 * application and starting a scan on the same calendar day therefore hit a PRIMARY KEY violation on
 * {@code JOB_STATISTICS.JOB_ID} and the scan died before processing a single file.
 *
 * <p>Found by clicking Resume in the running application, not by any test — which is why this one
 * now exists.
 */
class JobIdUniquenessTest {

    @TempDir Path source;
    @TempDir Path target;

    private Job newJob() {
        return Job.create(source.toString(), target.toString(),
            Job.TransferMode.COPY, Job.FolderPattern.YYYY_MMM, Job.DuplicatePolicy.SKIP,
            10, 100, 4, false, List.of());
    }

    @Test
    void testIdsAreUniqueWithinOneRun() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            assertThat(ids.add(newJob().getJobId()))
                .as("job id must not repeat within a run").isTrue();
        }
    }

    /**
     * The restart case. A fresh JVM cannot be spawned here, but the failure mode is that the id is
     * derived only from the calendar date and a counter that resets — so an id that carries no
     * sub-day component collides on the next launch. Asserting the id encodes the time of day
     * pins the actual fix.
     */
    @Test
    void testIdEncodesTimeOfDayNotJustTheDate() {
        String id = newJob().getJobId();

        assertThat(id).startsWith("JOB-");
        String[] parts = id.split("-");
        assertThat(parts)
            .as("expected JOB-<date>-<time>-<counter>, got %s", id)
            .hasSizeGreaterThanOrEqualTo(4);
        assertThat(parts[1]).as("date segment").hasSize(8).containsOnlyDigits();
        assertThat(parts[2]).as("time-of-day segment, which is what survives a restart")
            .hasSize(6).containsOnlyDigits();
    }

    @Test
    void testIdsAreUniqueUnderConcurrentCreation() throws Exception {
        int n = 200;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        Set<String> ids = ConcurrentHashMap.newKeySet();
        CountDownLatch done = new CountDownLatch(n);

        for (int i = 0; i < n; i++) {
            pool.execute(() -> {
                try {
                    ids.add(newJob().getJobId());
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(ids).as("no id collisions across threads").hasSize(n);
    }

    @Test
    void testIdIsUsableAsAFilesystemName() throws Exception {
        String id = newJob().getJobId();
        // Checkpoints live at <jobsDir>/<jobId>/checkpoint.json, so the id must be a legal folder
        // name on every target platform.
        assertThat(id).doesNotContainAnyWhitespaces()
                      .doesNotContain(":", "/", "\\", "*", "?", "\"", "<", ">", "|");
        Path dir = Files.createDirectories(target.resolve(id));
        assertThat(dir).exists();
    }
}
