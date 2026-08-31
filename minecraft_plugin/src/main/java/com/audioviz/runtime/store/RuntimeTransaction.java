package com.audioviz.runtime.store;

import java.util.Objects;
import java.util.UUID;

public record RuntimeTransaction(
    int schemaVersion,
    UUID operationId,
    TransactionPhase phase,
    String sourceStagingId,
    RuntimeState candidate,
    RuntimeState previous
) {
    public enum TransactionPhase {
        PROMOTION_PREPARED("promotion_prepared"),
        CANDIDATE_ACTIVATING("candidate_activating"),
        CANDIDATE_ACTIVE("candidate_active"),
        HEALTH_COMMITTING("health_committing");

        private final String wireName;

        TransactionPhase(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        static TransactionPhase fromWireName(String value) {
            for (TransactionPhase phase : values()) {
                if (phase.wireName.equals(value)) {
                    return phase;
                }
            }
            throw new IllegalArgumentException("unsupported transaction phase");
        }
    }

    public RuntimeTransaction {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(candidate, "candidate");
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("invalid transaction schema");
        }
        if (phase == TransactionPhase.PROMOTION_PREPARED) {
            validateStagingId(sourceStagingId);
            if (previous != null) {
                throw new IllegalArgumentException("promotion cannot contain previous state");
            }
        } else if (sourceStagingId != null) {
            throw new IllegalArgumentException("activation cannot contain staging state");
        }
    }

    public RuntimeTransaction withPhase(TransactionPhase nextPhase) {
        return new RuntimeTransaction(
            schemaVersion,
            operationId,
            nextPhase,
            nextPhase == TransactionPhase.PROMOTION_PREPARED ? sourceStagingId : null,
            candidate,
            previous
        );
    }

    private static void validateStagingId(String value) {
        if (value == null || !value.startsWith("stage-") || value.length() != 42) {
            throw new IllegalArgumentException("invalid staging identifier");
        }
        try {
            String encoded = value.substring("stage-".length());
            UUID parsed = UUID.fromString(encoded);
            if (!parsed.toString().equals(encoded)) {
                throw new IllegalArgumentException("non-canonical staging identifier");
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("invalid staging identifier", error);
        }
    }
}
