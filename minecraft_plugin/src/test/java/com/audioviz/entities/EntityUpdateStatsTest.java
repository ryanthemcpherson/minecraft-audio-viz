package com.audioviz.entities;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for EntityUpdateStats - pure Java concurrency-safe stats tracker.
 */
class EntityUpdateStatsTest {

    @Nested
    @DisplayName("Initial State")
    class InitialState {

        @Test
        @DisplayName("starts with zero total updates")
        void zeroTotal() {
            EntityUpdateStats stats = new EntityUpdateStats();
            assertEquals(0, stats.getTotalUpdatesProcessed());
        }

        @Test
        @DisplayName("starts with zero updates per second")
        void zeroPerSecond() {
            EntityUpdateStats stats = new EntityUpdateStats();
            assertEquals(0, stats.getUpdatesPerSecond());
        }
    }

    @Nested
    @DisplayName("recordUpdate")
    class RecordUpdate {

        @Test
        @DisplayName("increments total count by one")
        void incrementsTotal() {
            EntityUpdateStats stats = new EntityUpdateStats();
            stats.recordUpdate();
            assertEquals(1, stats.getTotalUpdatesProcessed());
        }

        @Test
        @DisplayName("multiple calls accumulate")
        void multipleCallsAccumulate() {
            EntityUpdateStats stats = new EntityUpdateStats();
            stats.recordUpdate();
            stats.recordUpdate();
            stats.recordUpdate();
            assertEquals(3, stats.getTotalUpdatesProcessed());
        }
    }

    @Nested
    @DisplayName("recordUpdates (batch)")
    class RecordUpdates {

        @Test
        @DisplayName("adds batch count to total")
        void addsBatchCount() {
            EntityUpdateStats stats = new EntityUpdateStats();
            stats.recordUpdates(50);
            assertEquals(50, stats.getTotalUpdatesProcessed());
        }

        @Test
        @DisplayName("multiple batch calls accumulate")
        void multipleBatchesAccumulate() {
            EntityUpdateStats stats = new EntityUpdateStats();
            stats.recordUpdates(10);
            stats.recordUpdates(20);
            stats.recordUpdates(30);
            assertEquals(60, stats.getTotalUpdatesProcessed());
        }

        @Test
        @DisplayName("mixes with single recordUpdate")
        void mixesSingleAndBatch() {
            EntityUpdateStats stats = new EntityUpdateStats();
            stats.recordUpdate();
            stats.recordUpdates(5);
            stats.recordUpdate();
            assertEquals(7, stats.getTotalUpdatesProcessed());
        }
    }

    @Nested
    @DisplayName("reset")
    class Reset {

        @Test
        @DisplayName("resets total updates to zero")
        void resetsTotal() {
            EntityUpdateStats stats = new EntityUpdateStats();
            stats.recordUpdates(100);
            stats.reset();
            assertEquals(0, stats.getTotalUpdatesProcessed());
        }

        @Test
        @DisplayName("resets updates per second to zero")
        void resetsPerSecond() {
            EntityUpdateStats stats = new EntityUpdateStats();
            stats.recordUpdate();
            stats.reset();
            assertEquals(0, stats.getUpdatesPerSecond());
        }

        @Test
        @DisplayName("allows accumulation after reset")
        void accumAfterReset() {
            EntityUpdateStats stats = new EntityUpdateStats();
            stats.recordUpdates(50);
            stats.reset();
            stats.recordUpdates(10);
            assertEquals(10, stats.getTotalUpdatesProcessed());
        }
    }
}
