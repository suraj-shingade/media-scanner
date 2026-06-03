package com.mediascanner.ui;

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class DataUnitFormatterTest {

    @Test void testBytes() {
        assertThat(DataUnitFormatter.format(999)).isEqualTo("999 B");
    }
    @Test void testOneKb() {
        assertThat(DataUnitFormatter.format(1_024)).isEqualTo("1.0 KB");
    }
    @Test void test1023Kb() {
        assertThat(DataUnitFormatter.format(1_023 * 1_024)).isEqualTo("1023.0 KB");
    }
    @Test void testOneMb() {
        assertThat(DataUnitFormatter.format(1_024 * 1_024)).isEqualTo("1.0 MB");
    }
    @Test void testOneGb() {
        assertThat(DataUnitFormatter.format(1_024L * 1_024 * 1_024)).contains("GB");
    }
    @Test void testOneTb() {
        assertThat(DataUnitFormatter.format(1_024L * 1_024 * 1_024 * 1_024)).contains("TB");
    }
    @Test void testZeroBytes() {
        assertThat(DataUnitFormatter.format(0)).isEqualTo("0 B");
    }
    @Test void testRateFormatMbSec() {
        assertThat(DataUnitFormatter.formatRate(512.0)).contains("MB/s");
    }
    @Test void testRateFormatGbSec() {
        assertThat(DataUnitFormatter.formatRate(2048.0)).contains("GB/s");
    }
}
