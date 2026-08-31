package com.audioviz.runtime.install;

import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellationToken
        implements com.audioviz.runtime.net.RuntimeHttpSource.CancellationToken {
    private final long generation;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public CancellationToken(long generation) {
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
        this.generation = generation;
    }

    public long generation() {
        return generation;
    }

    public void cancel() {
        cancelled.set(true);
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }
}
