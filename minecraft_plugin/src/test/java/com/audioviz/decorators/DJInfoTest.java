package com.audioviz.decorators;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DJInfo - immutable record with sentinel and comparison logic.
 */
class DJInfoTest {

    @Nested
    @DisplayName("none() sentinel")
    class NoneSentinel {

        @Test
        @DisplayName("none() returns empty DJ info")
        void noneReturnsEmpty() {
            DJInfo none = DJInfo.none();
            assertEquals("", none.djName());
            assertEquals("", none.djId());
            assertEquals(0.0, none.bpm());
            assertFalse(none.isActive());
            assertEquals(0L, none.timestamp());
        }

        @Test
        @DisplayName("none() is not present")
        void noneIsNotPresent() {
            assertFalse(DJInfo.none().isPresent());
        }

        @Test
        @DisplayName("none() returns same instance")
        void noneSameInstance() {
            assertSame(DJInfo.none(), DJInfo.none());
        }
    }

    @Nested
    @DisplayName("isPresent")
    class IsPresent {

        @Test
        @DisplayName("present when djName is non-empty")
        void presentWithName() {
            DJInfo info = new DJInfo("DJ Cool", "", 128.0, true, 1000L);
            assertTrue(info.isPresent());
        }

        @Test
        @DisplayName("present when djId is non-empty")
        void presentWithId() {
            DJInfo info = new DJInfo("", "dj-123", 0.0, false, 0L);
            assertTrue(info.isPresent());
        }

        @Test
        @DisplayName("present when both name and id are non-empty")
        void presentWithBoth() {
            DJInfo info = new DJInfo("DJ Cool", "dj-123", 128.0, true, 1000L);
            assertTrue(info.isPresent());
        }

        @Test
        @DisplayName("not present when both name and id are empty")
        void notPresentWhenBothEmpty() {
            DJInfo info = new DJInfo("", "", 128.0, true, 1000L);
            assertFalse(info.isPresent());
        }
    }

    @Nested
    @DisplayName("isDifferentDJ")
    class IsDifferentDJ {

        @Test
        @DisplayName("same DJ id is not different")
        void sameDjIdNotDifferent() {
            DJInfo a = new DJInfo("DJ A", "dj-1", 128.0, true, 1000L);
            DJInfo b = new DJInfo("DJ A Renamed", "dj-1", 140.0, true, 2000L);
            assertFalse(a.isDifferentDJ(b));
        }

        @Test
        @DisplayName("different DJ id is different")
        void differentDjIdIsDifferent() {
            DJInfo a = new DJInfo("DJ A", "dj-1", 128.0, true, 1000L);
            DJInfo b = new DJInfo("DJ B", "dj-2", 140.0, true, 2000L);
            assertTrue(a.isDifferentDJ(b));
        }

        @Test
        @DisplayName("null other is different when present")
        void nullOtherIsDifferent() {
            DJInfo a = new DJInfo("DJ A", "dj-1", 128.0, true, 1000L);
            assertTrue(a.isDifferentDJ(null));
        }

        @Test
        @DisplayName("null other is not different when not present")
        void nullOtherNotDifferentWhenNotPresent() {
            DJInfo none = DJInfo.none();
            assertFalse(none.isDifferentDJ(null));
        }

        @Test
        @DisplayName("empty id compared to empty id is not different")
        void emptyIdsNotDifferent() {
            DJInfo a = new DJInfo("", "", 0, false, 0);
            DJInfo b = new DJInfo("", "", 0, false, 0);
            assertFalse(a.isDifferentDJ(b));
        }
    }

    @Nested
    @DisplayName("Record Fields")
    class RecordFields {

        @Test
        @DisplayName("stores all fields correctly")
        void storesAllFields() {
            DJInfo info = new DJInfo("DJ Test", "dj-42", 174.0, true, 9999L);
            assertEquals("DJ Test", info.djName());
            assertEquals("dj-42", info.djId());
            assertEquals(174.0, info.bpm(), 0.001);
            assertTrue(info.isActive());
            assertEquals(9999L, info.timestamp());
        }

        @Test
        @DisplayName("record equality based on all fields")
        void recordEquality() {
            DJInfo a = new DJInfo("DJ A", "dj-1", 128.0, true, 1000L);
            DJInfo b = new DJInfo("DJ A", "dj-1", 128.0, true, 1000L);
            assertEquals(a, b);
        }

        @Test
        @DisplayName("record inequality when fields differ")
        void recordInequality() {
            DJInfo a = new DJInfo("DJ A", "dj-1", 128.0, true, 1000L);
            DJInfo b = new DJInfo("DJ A", "dj-1", 140.0, true, 1000L);
            assertNotEquals(a, b);
        }
    }
}
