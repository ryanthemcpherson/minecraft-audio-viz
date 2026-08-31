package com.audioviz.runtime.supervisor;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

public interface ManagedRuntimeProcess {
    RuntimeLaunch launch();

    long pid();

    CompletionStage<Integer> onExit();

    boolean isAlive();

    void requestShutdown();

    void terminate(Duration graceful, Duration normal);
}
