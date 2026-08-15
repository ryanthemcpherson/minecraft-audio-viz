package com.audioviz.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MonotonicClockTest {

    @Test
    void systemClockProvidesNanosecondTimestamps() {
        MonotonicClock clock = MonotonicClock.system();

        long before = clock.nanoTime();
        long after = clock.nanoTime();

        assertTrue(after >= before);
    }
}
