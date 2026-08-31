package com.audioviz.runtime.supervisor;

public record RuntimeHealth(
    long generation,
    String launchNonce,
    long sequence,
    boolean processAlive,
    boolean rendererConnected,
    boolean ingressHealthy,
    boolean eventLoopHealthy,
    boolean renderLoopHealthy,
    long lastRenderAgeMillis,
    int ingressQueueDepth,
    int renderQueueDepth
) {
    public RuntimeHealth {
        if (generation <= 0 || sequence < 0) {
            throw new IllegalArgumentException("invalid health identity");
        }
        if (launchNonce == null || launchNonce.length() < 16 || launchNonce.length() > 128) {
            throw new IllegalArgumentException("invalid health nonce");
        }
        if (
            lastRenderAgeMillis < 0 || lastRenderAgeMillis > 60_000 ||
            ingressQueueDepth < 0 || ingressQueueDepth > 1_000_000 ||
            renderQueueDepth < 0 || renderQueueDepth > 1_000_000
        ) {
            throw new IllegalArgumentException("invalid health bounds");
        }
    }

    public boolean healthy() {
        return processAlive && rendererConnected && ingressHealthy &&
            eventLoopHealthy && renderLoopHealthy;
    }

    @Override
    public String toString() {
        return "RuntimeHealth[generation=" + generation +
            ", sequence=" + sequence +
            ", processAlive=" + processAlive +
            ", rendererConnected=" + rendererConnected +
            ", ingressHealthy=" + ingressHealthy +
            ", eventLoopHealthy=" + eventLoopHealthy +
            ", renderLoopHealthy=" + renderLoopHealthy +
            ", lastRenderAgeMillis=" + lastRenderAgeMillis +
            ", ingressQueueDepth=" + ingressQueueDepth +
            ", renderQueueDepth=" + renderQueueDepth + "]";
    }
}
