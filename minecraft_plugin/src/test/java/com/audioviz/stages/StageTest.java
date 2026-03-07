package com.audioviz.stages;

import com.audioviz.decorators.DecoratorConfig;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Stage - uses Mockito for Bukkit Location/World.
 */
class StageTest {

    private World mockWorld;

    private Location mockLocation(double x, double y, double z) {
        return new Location(mockWorld, x, y, z);
    }

    @BeforeEach
    void setUp() {
        mockWorld = Mockito.mock(World.class);
    }

    @Nested
    @DisplayName("Primary Constructor")
    class PrimaryConstructor {

        @Test
        @DisplayName("stores name correctly")
        void storesName() {
            Stage stage = new Stage("TestStage", mockLocation(0, 64, 0), "club");
            assertEquals("TestStage", stage.getName());
        }

        @Test
        @DisplayName("generates random UUID")
        void generatesUUID() {
            Stage stage = new Stage("TestStage", mockLocation(0, 64, 0), "club");
            assertNotNull(stage.getId());
        }

        @Test
        @DisplayName("two stages get different UUIDs")
        void uniqueUUIDs() {
            Stage s1 = new Stage("Stage1", mockLocation(0, 0, 0), "club");
            Stage s2 = new Stage("Stage2", mockLocation(0, 0, 0), "club");
            assertNotEquals(s1.getId(), s2.getId());
        }

        @Test
        @DisplayName("clones anchor location")
        void clonesAnchor() {
            Location anchor = mockLocation(10, 64, 20);
            Stage stage = new Stage("Test", anchor, "club");

            Location returned = stage.getAnchor();
            assertEquals(10, returned.getX());
            assertNotSame(anchor, returned, "getAnchor should return a clone");
        }

        @Test
        @DisplayName("defaults rotation to 0")
        void defaultRotation() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            assertEquals(0f, stage.getRotation());
        }

        @Test
        @DisplayName("stores template name")
        void storesTemplateName() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "concert");
            assertEquals("concert", stage.getTemplateName());
        }

        @Test
        @DisplayName("defaults to inactive")
        void defaultsInactive() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            assertFalse(stage.isActive());
        }

        @Test
        @DisplayName("starts with empty role-to-zone map")
        void emptyRoleToZone() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            assertTrue(stage.getRoleToZone().isEmpty());
        }

        @Test
        @DisplayName("starts with empty zone configs")
        void emptyZoneConfigs() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            assertTrue(stage.getZoneConfigs().isEmpty());
        }

        @Test
        @DisplayName("starts with empty decorator configs")
        void emptyDecoratorConfigs() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            assertTrue(stage.getDecoratorConfigs().isEmpty());
        }

        @Test
        @DisplayName("defaults tag to empty string")
        void defaultTag() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            assertEquals("", stage.getTag());
        }

        @Test
        @DisplayName("defaults pinned to false")
        void defaultPinned() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            assertFalse(stage.isPinned());
        }

        @Test
        @DisplayName("sets createdAt to current time")
        void createdAtSet() {
            long before = System.currentTimeMillis();
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            long after = System.currentTimeMillis();

            assertTrue(stage.getCreatedAt() >= before);
            assertTrue(stage.getCreatedAt() <= after);
        }

        @Test
        @DisplayName("defaults lastActivatedAt to 0")
        void lastActivatedAtZero() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            assertEquals(0L, stage.getLastActivatedAt());
        }
    }

    @Nested
    @DisplayName("Full Constructor (Deserialization)")
    class FullConstructor {

        @Test
        @DisplayName("preserves all provided fields")
        void preservesAllFields() {
            UUID id = UUID.randomUUID();
            Map<StageZoneRole, String> roleToZone = new EnumMap<>(StageZoneRole.class);
            roleToZone.put(StageZoneRole.MAIN_STAGE, "stage_main");
            Map<StageZoneRole, StageZoneConfig> zoneConfigs = new EnumMap<>(StageZoneRole.class);
            Map<String, DecoratorConfig> decoratorConfigs = new LinkedHashMap<>();
            decoratorConfigs.put("glow", new DecoratorConfig());

            Stage stage = new Stage("FullTest", id, mockLocation(5, 10, 15), 45f,
                "arena", true, roleToZone, zoneConfigs, decoratorConfigs,
                "festival", true, 1000L, 2000L);

            assertEquals("FullTest", stage.getName());
            assertEquals(id, stage.getId());
            assertEquals(5, stage.getAnchor().getX());
            assertEquals(45f, stage.getRotation());
            assertEquals("arena", stage.getTemplateName());
            assertTrue(stage.isActive());
            assertEquals("stage_main", stage.getZoneName(StageZoneRole.MAIN_STAGE));
            assertNotNull(stage.getDecoratorConfig("glow"));
            assertEquals("festival", stage.getTag());
            assertTrue(stage.isPinned());
            assertEquals(1000L, stage.getCreatedAt());
            assertEquals(2000L, stage.getLastActivatedAt());
        }

        @Test
        @DisplayName("handles null decorator configs")
        void handlesNullDecoratorConfigs() {
            Stage stage = new Stage("Test", UUID.randomUUID(), mockLocation(0, 0, 0), 0f,
                "club", false, new EnumMap<>(StageZoneRole.class),
                new EnumMap<>(StageZoneRole.class), null,
                null, false, 0L, 0L);

            assertNotNull(stage.getDecoratorConfigs());
            assertTrue(stage.getDecoratorConfigs().isEmpty());
        }

        @Test
        @DisplayName("handles null tag")
        void handlesNullTag() {
            Stage stage = new Stage("Test", UUID.randomUUID(), mockLocation(0, 0, 0), 0f,
                "club", false, new EnumMap<>(StageZoneRole.class),
                new EnumMap<>(StageZoneRole.class), null,
                null, false, 0L, 0L);

            assertEquals("", stage.getTag());
        }
    }

    @Nested
    @DisplayName("setRotation")
    class SetRotation {

        @Test
        @DisplayName("stores rotation value")
        void storesValue() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            stage.setRotation(90f);
            assertEquals(90f, stage.getRotation());
        }

        @Test
        @DisplayName("wraps rotation with modulo 360")
        void wrapsModulo360() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            stage.setRotation(450f);
            assertEquals(90f, stage.getRotation());
        }

        @Test
        @DisplayName("handles negative rotation with modulo")
        void handlesNegative() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            stage.setRotation(-90f);
            assertEquals(-90f, stage.getRotation());
        }
    }

    @Nested
    @DisplayName("setAnchor")
    class SetAnchor {

        @Test
        @DisplayName("updates anchor location")
        void updatesAnchor() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            stage.setAnchor(mockLocation(100, 200, 300));
            assertEquals(100, stage.getAnchor().getX());
            assertEquals(200, stage.getAnchor().getY());
            assertEquals(300, stage.getAnchor().getZ());
        }

        @Test
        @DisplayName("clones the provided location")
        void clonesLocation() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            Location loc = mockLocation(50, 50, 50);
            stage.setAnchor(loc);

            Location returned = stage.getAnchor();
            assertNotSame(loc, returned);
        }
    }

    @Nested
    @DisplayName("setActive")
    class SetActive {

        @Test
        @DisplayName("can activate stage")
        void canActivate() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            stage.setActive(true);
            assertTrue(stage.isActive());
        }

        @Test
        @DisplayName("can deactivate stage")
        void canDeactivate() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            stage.setActive(true);
            stage.setActive(false);
            assertFalse(stage.isActive());
        }
    }

    @Nested
    @DisplayName("Zone Role Mapping")
    class ZoneRoleMapping {

        private Stage stageWithZones() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            stage.getRoleToZone().put(StageZoneRole.MAIN_STAGE, "test_main");
            stage.getRoleToZone().put(StageZoneRole.LEFT_WING, "test_left");
            stage.getRoleToZone().put(StageZoneRole.RIGHT_WING, "test_right");
            return stage;
        }

        @Test
        @DisplayName("getZoneName returns zone for role")
        void getZoneName() {
            Stage stage = stageWithZones();
            assertEquals("test_main", stage.getZoneName(StageZoneRole.MAIN_STAGE));
        }

        @Test
        @DisplayName("getZoneName returns null for unmapped role")
        void getZoneNameNull() {
            Stage stage = stageWithZones();
            assertNull(stage.getZoneName(StageZoneRole.SKYBOX));
        }

        @Test
        @DisplayName("getZoneNames returns all zone names")
        void getZoneNames() {
            Stage stage = stageWithZones();
            Collection<String> names = stage.getZoneNames();
            assertEquals(3, names.size());
            assertTrue(names.contains("test_main"));
            assertTrue(names.contains("test_left"));
            assertTrue(names.contains("test_right"));
        }

        @Test
        @DisplayName("getZoneNames returns unmodifiable collection")
        void getZoneNamesUnmodifiable() {
            Stage stage = stageWithZones();
            Collection<String> names = stage.getZoneNames();
            assertThrows(UnsupportedOperationException.class, () -> names.add("sneaky"));
        }

        @Test
        @DisplayName("getActiveRoles returns all mapped roles")
        void getActiveRoles() {
            Stage stage = stageWithZones();
            Set<StageZoneRole> roles = stage.getActiveRoles();
            assertEquals(3, roles.size());
            assertTrue(roles.contains(StageZoneRole.MAIN_STAGE));
            assertTrue(roles.contains(StageZoneRole.LEFT_WING));
            assertTrue(roles.contains(StageZoneRole.RIGHT_WING));
        }

        @Test
        @DisplayName("getActiveRoles returns unmodifiable set")
        void getActiveRolesUnmodifiable() {
            Stage stage = stageWithZones();
            Set<StageZoneRole> roles = stage.getActiveRoles();
            assertThrows(UnsupportedOperationException.class, () -> roles.add(StageZoneRole.SKYBOX));
        }

        @Test
        @DisplayName("ownsZone returns true for owned zone")
        void ownsZoneTrue() {
            Stage stage = stageWithZones();
            assertTrue(stage.ownsZone("test_main"));
        }

        @Test
        @DisplayName("ownsZone is case-insensitive")
        void ownsZoneCaseInsensitive() {
            Stage stage = stageWithZones();
            assertTrue(stage.ownsZone("TEST_MAIN"));
            assertTrue(stage.ownsZone("Test_Main"));
        }

        @Test
        @DisplayName("ownsZone returns false for unknown zone")
        void ownsZoneFalse() {
            Stage stage = stageWithZones();
            assertFalse(stage.ownsZone("unknown_zone"));
        }

        @Test
        @DisplayName("getRoleForZone returns correct role")
        void getRoleForZone() {
            Stage stage = stageWithZones();
            assertEquals(StageZoneRole.LEFT_WING, stage.getRoleForZone("test_left"));
        }

        @Test
        @DisplayName("getRoleForZone is case-insensitive")
        void getRoleForZoneCaseInsensitive() {
            Stage stage = stageWithZones();
            assertEquals(StageZoneRole.MAIN_STAGE, stage.getRoleForZone("TEST_MAIN"));
        }

        @Test
        @DisplayName("getRoleForZone returns null for unknown zone")
        void getRoleForZoneNull() {
            Stage stage = stageWithZones();
            assertNull(stage.getRoleForZone("nonexistent"));
        }
    }

    @Nested
    @DisplayName("getOrCreateConfig")
    class GetOrCreateConfig {

        @Test
        @DisplayName("creates default config for new role")
        void createsDefault() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            StageZoneConfig config = stage.getOrCreateConfig(StageZoneRole.MAIN_STAGE);

            assertNotNull(config);
            assertEquals("spectrum", config.getPattern());
            assertEquals(16, config.getEntityCount());
        }

        @Test
        @DisplayName("returns existing config on second call")
        void returnsSameInstance() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            StageZoneConfig first = stage.getOrCreateConfig(StageZoneRole.MAIN_STAGE);
            first.setPattern("wave");

            StageZoneConfig second = stage.getOrCreateConfig(StageZoneRole.MAIN_STAGE);
            assertSame(first, second);
            assertEquals("wave", second.getPattern());
        }
    }

    @Nested
    @DisplayName("getTotalEntityCount")
    class GetTotalEntityCount {

        @Test
        @DisplayName("returns 0 when no configs")
        void zeroWhenEmpty() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            assertEquals(0, stage.getTotalEntityCount());
        }

        @Test
        @DisplayName("sums entity counts across configs")
        void sumsConfigs() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            StageZoneConfig main = stage.getOrCreateConfig(StageZoneRole.MAIN_STAGE);
            main.setEntityCount(64);
            StageZoneConfig left = stage.getOrCreateConfig(StageZoneRole.LEFT_WING);
            left.setEntityCount(32);

            assertEquals(96, stage.getTotalEntityCount());
        }
    }

    @Nested
    @DisplayName("Decorator Configs")
    class DecoratorConfigs {

        @Test
        @DisplayName("setDecoratorConfig stores config")
        void setDecoratorConfig() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            DecoratorConfig dc = new DecoratorConfig();
            dc.set("color", "red");

            stage.setDecoratorConfig("glow", dc);

            assertNotNull(stage.getDecoratorConfig("glow"));
            assertEquals("red", stage.getDecoratorConfig("glow").getString("color", ""));
        }

        @Test
        @DisplayName("getDecoratorConfig returns null for missing")
        void getDecoratorConfigNull() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            assertNull(stage.getDecoratorConfig("nonexistent"));
        }

        @Test
        @DisplayName("removeDecoratorConfig removes config")
        void removeDecoratorConfig() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            stage.setDecoratorConfig("glow", new DecoratorConfig());
            stage.removeDecoratorConfig("glow");
            assertNull(stage.getDecoratorConfig("glow"));
        }
    }

    @Nested
    @DisplayName("Organization Metadata")
    class OrganizationMetadata {

        @Test
        @DisplayName("setTag trims and lowercases")
        void setTagTrimsAndLowercases() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            stage.setTag("  Festival  ");
            assertEquals("festival", stage.getTag());
        }

        @Test
        @DisplayName("setTag handles null")
        void setTagHandlesNull() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            stage.setTag("test");
            stage.setTag(null);
            assertEquals("", stage.getTag());
        }

        @Test
        @DisplayName("setPinned stores value")
        void setPinned() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            stage.setPinned(true);
            assertTrue(stage.isPinned());
        }

        @Test
        @DisplayName("setLastActivatedAt stores value")
        void setLastActivatedAt() {
            Stage stage = new Stage("Test", mockLocation(0, 0, 0), "club");
            stage.setLastActivatedAt(999L);
            assertEquals(999L, stage.getLastActivatedAt());
        }
    }

    @Nested
    @DisplayName("toString")
    class ToString {

        @Test
        @DisplayName("includes name, template, roles count, active state")
        void includesKeyInfo() {
            Stage stage = new Stage("MyStage", mockLocation(0, 0, 0), "concert");
            stage.setActive(true);
            stage.getRoleToZone().put(StageZoneRole.MAIN_STAGE, "zone_main");

            String str = stage.toString();
            assertTrue(str.contains("MyStage"));
            assertTrue(str.contains("concert"));
            assertTrue(str.contains("1"));
            assertTrue(str.contains("true"));
        }
    }
}
