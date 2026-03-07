package com.audioviz.stages;

import org.bukkit.Material;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StageTemplate - builder, builtins, and accessor logic.
 */
class StageTemplateTest {

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("builds template with name")
        void buildsWithName() {
            StageTemplate template = StageTemplate.builder("test")
                .build();
            assertEquals("test", template.getName());
        }

        @Test
        @DisplayName("defaults description to empty string")
        void defaultDescription() {
            StageTemplate template = StageTemplate.builder("test").build();
            assertEquals("", template.getDescription());
        }

        @Test
        @DisplayName("defaults icon to JUKEBOX")
        void defaultIcon() {
            StageTemplate template = StageTemplate.builder("test").build();
            assertEquals(Material.JUKEBOX, template.getIcon());
        }

        @Test
        @DisplayName("defaults entity count to 16")
        void defaultEntityCount() {
            StageTemplate template = StageTemplate.builder("test").build();
            assertEquals(16, template.getDefaultEntityCount());
        }

        @Test
        @DisplayName("sets description")
        void setsDescription() {
            StageTemplate template = StageTemplate.builder("test")
                .description("A test template")
                .build();
            assertEquals("A test template", template.getDescription());
        }

        @Test
        @DisplayName("sets icon")
        void setsIcon() {
            StageTemplate template = StageTemplate.builder("test")
                .icon(Material.BEACON)
                .build();
            assertEquals(Material.BEACON, template.getIcon());
        }

        @Test
        @DisplayName("sets default entity count")
        void setsDefaultEntityCount() {
            StageTemplate template = StageTemplate.builder("test")
                .defaultEntityCount(64)
                .build();
            assertEquals(64, template.getDefaultEntityCount());
        }

        @Test
        @DisplayName("adds roles")
        void addsRoles() {
            StageTemplate template = StageTemplate.builder("test")
                .role(StageZoneRole.MAIN_STAGE)
                .role(StageZoneRole.LEFT_WING)
                .build();

            Set<StageZoneRole> roles = template.getRoles();
            assertEquals(2, roles.size());
            assertTrue(roles.contains(StageZoneRole.MAIN_STAGE));
            assertTrue(roles.contains(StageZoneRole.LEFT_WING));
        }

        @Test
        @DisplayName("getRoleCount matches roles set size")
        void roleCount() {
            StageTemplate template = StageTemplate.builder("test")
                .role(StageZoneRole.MAIN_STAGE)
                .role(StageZoneRole.SKYBOX)
                .role(StageZoneRole.AUDIENCE)
                .build();

            assertEquals(3, template.getRoleCount());
        }

        @Test
        @DisplayName("adds size overrides")
        void addsSizeOverrides() {
            Vector customSize = new Vector(20, 16, 12);
            StageTemplate template = StageTemplate.builder("test")
                .role(StageZoneRole.MAIN_STAGE)
                .sizeOverride(StageZoneRole.MAIN_STAGE, customSize)
                .build();

            assertEquals(customSize, template.getSizeOverrides().get(StageZoneRole.MAIN_STAGE));
        }

        @Test
        @DisplayName("adds offset overrides")
        void addsOffsetOverrides() {
            Vector customOffset = new Vector(-18, 0, 0);
            StageTemplate template = StageTemplate.builder("test")
                .role(StageZoneRole.LEFT_WING)
                .offsetOverride(StageZoneRole.LEFT_WING, customOffset)
                .build();

            assertEquals(customOffset, template.getOffsetOverrides().get(StageZoneRole.LEFT_WING));
        }

        @Test
        @DisplayName("adds config overrides via consumer")
        void addsConfigOverrides() {
            StageTemplate template = StageTemplate.builder("test")
                .role(StageZoneRole.MAIN_STAGE)
                .configOverride(StageZoneRole.MAIN_STAGE, config -> {
                    config.setEntityCount(100);
                    config.setPattern("aurora");
                })
                .build();

            StageZoneConfig config = template.getDefaultConfigs().get(StageZoneRole.MAIN_STAGE);
            assertNotNull(config);
            assertEquals(100, config.getEntityCount());
            assertEquals("aurora", config.getPattern());
        }

        @Test
        @DisplayName("roles set is unmodifiable")
        void rolesUnmodifiable() {
            StageTemplate template = StageTemplate.builder("test")
                .role(StageZoneRole.MAIN_STAGE)
                .build();

            assertThrows(UnsupportedOperationException.class,
                () -> template.getRoles().add(StageZoneRole.SKYBOX));
        }

        @Test
        @DisplayName("size overrides map is unmodifiable")
        void sizeOverridesUnmodifiable() {
            StageTemplate template = StageTemplate.builder("test").build();
            assertThrows(UnsupportedOperationException.class,
                () -> template.getSizeOverrides().put(StageZoneRole.MAIN_STAGE, new Vector(1, 1, 1)));
        }

        @Test
        @DisplayName("offset overrides map is unmodifiable")
        void offsetOverridesUnmodifiable() {
            StageTemplate template = StageTemplate.builder("test").build();
            assertThrows(UnsupportedOperationException.class,
                () -> template.getOffsetOverrides().put(StageZoneRole.MAIN_STAGE, new Vector(1, 1, 1)));
        }

        @Test
        @DisplayName("default configs map is unmodifiable")
        void defaultConfigsUnmodifiable() {
            StageTemplate template = StageTemplate.builder("test").build();
            assertThrows(UnsupportedOperationException.class,
                () -> template.getDefaultConfigs().put(StageZoneRole.MAIN_STAGE, new StageZoneConfig()));
        }
    }

    @Nested
    @DisplayName("getSizeForRole")
    class GetSizeForRole {

        @Test
        @DisplayName("returns override when present")
        void returnsOverride() {
            Vector custom = new Vector(30, 20, 10);
            StageTemplate template = StageTemplate.builder("test")
                .role(StageZoneRole.MAIN_STAGE)
                .sizeOverride(StageZoneRole.MAIN_STAGE, custom)
                .build();

            assertEquals(custom, template.getSizeForRole(StageZoneRole.MAIN_STAGE));
        }

        @Test
        @DisplayName("returns default size when no override")
        void returnsDefault() {
            StageTemplate template = StageTemplate.builder("test")
                .role(StageZoneRole.MAIN_STAGE)
                .build();

            assertEquals(StageZoneRole.MAIN_STAGE.getDefaultSize(), template.getSizeForRole(StageZoneRole.MAIN_STAGE));
        }
    }

    @Nested
    @DisplayName("getOffsetForRole")
    class GetOffsetForRole {

        @Test
        @DisplayName("returns override when present")
        void returnsOverride() {
            Vector custom = new Vector(-20, 5, 0);
            StageTemplate template = StageTemplate.builder("test")
                .role(StageZoneRole.LEFT_WING)
                .offsetOverride(StageZoneRole.LEFT_WING, custom)
                .build();

            assertEquals(custom, template.getOffsetForRole(StageZoneRole.LEFT_WING));
        }

        @Test
        @DisplayName("returns default offset when no override")
        void returnsDefault() {
            StageTemplate template = StageTemplate.builder("test")
                .role(StageZoneRole.SKYBOX)
                .build();

            assertEquals(StageZoneRole.SKYBOX.getDefaultOffset(), template.getOffsetForRole(StageZoneRole.SKYBOX));
        }
    }

    @Nested
    @DisplayName("getEstimatedEntityCount")
    class GetEstimatedEntityCount {

        @Test
        @DisplayName("uses default entity count for roles without config")
        void usesDefaultForUnconfiguredRoles() {
            StageTemplate template = StageTemplate.builder("test")
                .defaultEntityCount(32)
                .role(StageZoneRole.MAIN_STAGE)
                .role(StageZoneRole.SKYBOX)
                .build();

            assertEquals(64, template.getEstimatedEntityCount()); // 32 + 32
        }

        @Test
        @DisplayName("uses config entity count for configured roles")
        void usesConfigForConfiguredRoles() {
            StageTemplate template = StageTemplate.builder("test")
                .defaultEntityCount(16)
                .role(StageZoneRole.MAIN_STAGE)
                .configOverride(StageZoneRole.MAIN_STAGE, c -> c.setEntityCount(100))
                .role(StageZoneRole.SKYBOX)
                .build();

            assertEquals(116, template.getEstimatedEntityCount()); // 100 + 16
        }

        @Test
        @DisplayName("returns 0 for template with no roles")
        void zeroForNoRoles() {
            StageTemplate template = StageTemplate.builder("empty").build();
            assertEquals(0, template.getEstimatedEntityCount());
        }
    }

    @Nested
    @DisplayName("Built-in Templates")
    class BuiltinTemplates {

        @Test
        @DisplayName("getBuiltinTemplates returns 4 templates")
        void hasFourTemplates() {
            Map<String, StageTemplate> templates = StageTemplate.getBuiltinTemplates();
            assertEquals(4, templates.size());
        }

        @Test
        @DisplayName("contains club, concert, arena, festival")
        void containsExpectedNames() {
            Map<String, StageTemplate> templates = StageTemplate.getBuiltinTemplates();
            assertTrue(templates.containsKey("club"));
            assertTrue(templates.containsKey("concert"));
            assertTrue(templates.containsKey("arena"));
            assertTrue(templates.containsKey("festival"));
        }

        @Test
        @DisplayName("getBuiltinTemplates is unmodifiable")
        void builtinMapUnmodifiable() {
            Map<String, StageTemplate> templates = StageTemplate.getBuiltinTemplates();
            assertThrows(UnsupportedOperationException.class,
                () -> templates.put("hack", StageTemplate.builder("hack").build()));
        }

        @Test
        @DisplayName("getBuiltin returns template by name")
        void getBuiltinByName() {
            StageTemplate club = StageTemplate.getBuiltin("club");
            assertNotNull(club);
            assertEquals("club", club.getName());
        }

        @Test
        @DisplayName("getBuiltin is case-insensitive (lowercases)")
        void getBuiltinCaseInsensitive() {
            StageTemplate concert = StageTemplate.getBuiltin("CONCERT");
            assertNotNull(concert);
            assertEquals("concert", concert.getName());
        }

        @Test
        @DisplayName("getBuiltin returns null for unknown name")
        void getBuiltinNull() {
            assertNull(StageTemplate.getBuiltin("nonexistent"));
        }

        @Test
        @DisplayName("club template has 2 roles")
        void clubHasTwoRoles() {
            StageTemplate club = StageTemplate.getBuiltin("club");
            assertEquals(2, club.getRoleCount());
            assertTrue(club.getRoles().contains(StageZoneRole.MAIN_STAGE));
            assertTrue(club.getRoles().contains(StageZoneRole.AUDIENCE));
        }

        @Test
        @DisplayName("concert template has 5 roles")
        void concertHasFiveRoles() {
            StageTemplate concert = StageTemplate.getBuiltin("concert");
            assertEquals(5, concert.getRoleCount());
        }

        @Test
        @DisplayName("arena template has 7 roles")
        void arenaHasSevenRoles() {
            StageTemplate arena = StageTemplate.getBuiltin("arena");
            assertEquals(7, arena.getRoleCount());
        }

        @Test
        @DisplayName("festival template has 8 roles (all)")
        void festivalHasEightRoles() {
            StageTemplate festival = StageTemplate.getBuiltin("festival");
            assertEquals(8, festival.getRoleCount());
        }

        @Test
        @DisplayName("each builtin has a description")
        void eachHasDescription() {
            for (StageTemplate template : StageTemplate.getBuiltinTemplates().values()) {
                assertFalse(template.getDescription().isEmpty(),
                    template.getName() + " should have a description");
            }
        }

        @Test
        @DisplayName("each builtin has a non-null icon")
        void eachHasIcon() {
            for (StageTemplate template : StageTemplate.getBuiltinTemplates().values()) {
                assertNotNull(template.getIcon(),
                    template.getName() + " should have an icon");
            }
        }
    }
}
