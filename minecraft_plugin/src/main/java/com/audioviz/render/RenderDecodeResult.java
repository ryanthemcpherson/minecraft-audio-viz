package com.audioviz.render;

import java.util.Objects;

/** Result of validating and publishing one render frame. */
public record RenderDecodeResult(Status status, ZoneRenderSnapshot snapshot, String reason) {

    public enum Status {
        ACCEPTED,
        SUPERSEDED,
        REJECTED
    }

    public RenderDecodeResult {
        Objects.requireNonNull(status, "status");
        if (status == Status.ACCEPTED && snapshot == null) {
            throw new IllegalArgumentException("accepted results require a snapshot");
        }
        if (status != Status.ACCEPTED && snapshot != null) {
            throw new IllegalArgumentException("non-accepted results cannot expose a snapshot");
        }
    }

    public boolean accepted() {
        return status == Status.ACCEPTED;
    }

    static RenderDecodeResult accepted(ZoneRenderSnapshot snapshot) {
        return new RenderDecodeResult(Status.ACCEPTED, snapshot, null);
    }

    static RenderDecodeResult superseded() {
        return new RenderDecodeResult(Status.SUPERSEDED, null, "newer snapshot already published");
    }

    static RenderDecodeResult rejected(String reason) {
        return new RenderDecodeResult(
            Status.REJECTED,
            null,
            Objects.requireNonNullElse(reason, "render frame rejected"));
    }
}
