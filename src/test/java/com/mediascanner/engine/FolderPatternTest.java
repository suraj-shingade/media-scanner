package com.mediascanner.engine;

import com.mediascanner.model.Job;
import org.junit.jupiter.api.*;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

class FolderPatternTest {

    private final MetadataExtractor extractor = new MetadataExtractor();
    private final LocalDateTime testDate = LocalDateTime.of(2024, 3, 15, 10, 30, 0);

    @Test
    void testYyyyMmPattern() {
        String result = extractor.computeFolderPath(testDate, Job.FolderPattern.YYYY_MM);
        assertThat(result).isEqualTo("2024/03");
    }

    @Test
    void testYyyyMmmPattern() {
        String result = extractor.computeFolderPath(testDate, Job.FolderPattern.YYYY_MMM);
        assertThat(result).isEqualTo("2024/Mar");
    }

    @Test
    void testYyyyMmmDdPattern() {
        String result = extractor.computeFolderPath(testDate, Job.FolderPattern.YYYY_MMM_DD);
        assertThat(result).isEqualTo("2024/Mar/15");
    }

    @Test
    void testYyyyMmDdPattern() {
        String result = extractor.computeFolderPath(testDate, Job.FolderPattern.YYYY_MM_DD);
        assertThat(result).isEqualTo("2024/03/15");
    }

    @Test
    void testFutureDateNoError() {
        LocalDateTime future = LocalDateTime.of(2099, 12, 31, 23, 59, 59);
        assertThatCode(() -> extractor.computeFolderPath(future, Job.FolderPattern.YYYY_MMM))
            .doesNotThrowAnyException();
        String result = extractor.computeFolderPath(future, Job.FolderPattern.YYYY_MMM);
        assertThat(result).isEqualTo("2099/Dec");
    }

    @Test
    void testJanuaryPaddedInYyyyMm() {
        LocalDateTime jan = LocalDateTime.of(2024, 1, 5, 8, 0, 0);
        String result = extractor.computeFolderPath(jan, Job.FolderPattern.YYYY_MM);
        assertThat(result).isEqualTo("2024/01");
    }
}
