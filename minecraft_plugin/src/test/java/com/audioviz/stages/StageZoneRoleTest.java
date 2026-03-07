package com.audioviz.stages;

import org.bukkit.Material;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StageZoneRole enum.
 */
class StageZoneRoleTest {

    @Test
    @DisplayName("enum has 8 roles")
    void hasEightRoles() {
        assertEquals(8, StageZoneRole.values().length);
    }

    @ParameterizedTest
    @EnumSource(StageZoneRole.class)
    @DisplayName("every role has a non-null display name")
    void everyRoleHasDisplayName(StageZoneRole role) {
        assertNotNull(role.getDisplayName());
        assertFalse(role.getDisplayName().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(StageZoneRole.class)
    @DisplayName("every role has a non-null suggested pattern")
    void everyRoleHasSuggestedPattern(StageZoneRole role) {
        assertNotNull(role.getSuggestedPattern());
        assertFalse(role.getSuggestedPattern().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(StageZoneRole.class)
    @DisplayName("every role has a non-null icon")
    void everyRoleHasIcon(StageZoneRole role) {
        assertNotNull(role.getIcon());
    }

    @ParameterizedTest
    @EnumSource(StageZoneRole.class)
    @DisplayName("getDefaultOffset returns a clone (not same instance)")
    void defaultOffsetReturnsClone(StageZoneRole role) {
        Vector offset1 = role.getDefaultOffset();
        Vector offset2 = role.getDefaultOffset();
        assertEquals(offset1, offset2);
        assertNotSame(offset1, offset2, "getDefaultOffset should return a clone");
    }

    @ParameterizedTest
    @EnumSource(StageZoneRole.class)
    @DisplayName("getDefaultSize returns a clone (not same instance)")
    void defaultSizeReturnsClone(StageZoneRole role) {
        Vector size1 = role.getDefaultSize();
        Vector size2 = role.getDefaultSize();
        assertEquals(size1, size2);
        assertNotSame(size1, size2, "getDefaultSize should return a clone");
    }

    @Nested
    @DisplayName("Specific Role Values")
    class SpecificRoleValues {

        @Test
        @DisplayName("MAIN_STAGE has origin offset")
        void mainStageOffset() {
            Vector offset = StageZoneRole.MAIN_STAGE.getDefaultOffset();
            assertEquals(0, offset.getX());
            assertEquals(0, offset.getY());
            assertEquals(0, offset.getZ());
        }

        @Test
        @DisplayName("MAIN_STAGE suggests spectrum pattern")
        void mainStagePattern() {
            assertEquals("spectrum", StageZoneRole.MAIN_STAGE.getSuggestedPattern());
        }

        @Test
        @DisplayName("MAIN_STAGE icon is JUKEBOX")
        void mainStageIcon() {
            assertEquals(Material.JUKEBOX, StageZoneRole.MAIN_STAGE.getIcon());
        }

        @Test
        @DisplayName("LEFT_WING has negative X offset")
        void leftWingOffset() {
            Vector offset = StageZoneRole.LEFT_WING.getDefaultOffset();
            assertTrue(offset.getX() < 0, "Left wing should have negative X offset");
        }

        @Test
        @DisplayName("RIGHT_WING has positive X offset")
        void rightWingOffset() {
            Vector offset = StageZoneRole.RIGHT_WING.getDefaultOffset();
            assertTrue(offset.getX() > 0, "Right wing should have positive X offset");
        }

        @Test
        @DisplayName("LEFT_WING and RIGHT_WING are symmetric on X axis")
        void wingsAreSymmetric() {
            Vector left = StageZoneRole.LEFT_WING.getDefaultOffset();
            Vector right = StageZoneRole.RIGHT_WING.getDefaultOffset();
            assertEquals(-left.getX(), right.getX(), 1e-10, "Wings should mirror on X");
            assertEquals(left.getY(), right.getY(), 1e-10, "Wings should have same Y");
            assertEquals(left.getZ(), right.getZ(), 1e-10, "Wings should have same Z");
        }

        @Test
        @DisplayName("LEFT_WING and RIGHT_WING have same size")
        void wingsHaveSameSize() {
            Vector leftSize = StageZoneRole.LEFT_WING.getDefaultSize();
            Vector rightSize = StageZoneRole.RIGHT_WING.getDefaultSize();
            assertEquals(leftSize, rightSize);
        }

        @Test
        @DisplayName("SKYBOX has positive Y offset")
        void skyboxOffset() {
            Vector offset = StageZoneRole.SKYBOX.getDefaultOffset();
            assertTrue(offset.getY() > 0, "Skybox should be above (positive Y)");
        }

        @Test
        @DisplayName("AUDIENCE has positive Z offset")
        void audienceOffset() {
            Vector offset = StageZoneRole.AUDIENCE.getDefaultOffset();
            assertTrue(offset.getZ() > 0, "Audience should be in front (positive Z)");
        }

        @Test
        @DisplayName("BACKSTAGE has negative Z offset")
        void backstageOffset() {
            Vector offset = StageZoneRole.BACKSTAGE.getDefaultOffset();
            assertTrue(offset.getZ() < 0, "Backstage should be behind (negative Z)");
        }

        @Test
        @DisplayName("BALCONY display name is Balcony")
        void balconyDisplayName() {
            assertEquals("Balcony", StageZoneRole.BALCONY.getDisplayName());
        }
    }

    @ParameterizedTest
    @EnumSource(StageZoneRole.class)
    @DisplayName("default size has all positive components")
    void defaultSizeAllPositive(StageZoneRole role) {
        Vector size = role.getDefaultSize();
        assertTrue(size.getX() > 0, "Size X should be positive");
        assertTrue(size.getY() > 0, "Size Y should be positive");
        assertTrue(size.getZ() > 0, "Size Z should be positive");
    }
}
