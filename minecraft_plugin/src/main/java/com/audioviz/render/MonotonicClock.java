package com.audioviz.render;

/**
 * Monotonic source of nanosecond timestamps for renderer timing.
 */
@FunctionalInterface
public interface MonotonicClock {
    long nanoTime();

    static MonotonicClock system() {
        return System::nanoTime;
    }
}
