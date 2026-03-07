package com.audioviz.decorators;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DecoratorConfig - pure data class, no Bukkit dependencies.
 */
class DecoratorConfigTest {

    @Nested
    @DisplayName("Default Constructor")
    class DefaultConstructor {

        @Test
        @DisplayName("enabled is true by default")
        void enabledByDefault() {
            DecoratorConfig config = new DecoratorConfig();
            assertTrue(config.isEnabled());
        }

        @Test
        @DisplayName("settings map is empty by default")
        void emptySettingsByDefault() {
            DecoratorConfig config = new DecoratorConfig();
            assertTrue(config.getSettings().isEmpty());
        }
    }

    @Nested
    @DisplayName("Parameterized Constructor")
    class ParameterizedConstructor {

        @Test
        @DisplayName("stores enabled flag")
        void storesEnabled() {
            DecoratorConfig config = new DecoratorConfig(false, Map.of("key", "val"));
            assertFalse(config.isEnabled());
        }

        @Test
        @DisplayName("copies settings map")
        void copiesSettings() {
            Map<String, Object> original = new LinkedHashMap<>();
            original.put("speed", 1.5);
            DecoratorConfig config = new DecoratorConfig(true, original);

            // Modifying original should not affect config
            original.put("extra", "data");
            assertFalse(config.getSettings().containsKey("extra"));
            assertEquals(1.5, config.getSettings().get("speed"));
        }

        @Test
        @DisplayName("handles null settings gracefully")
        void handlesNullSettings() {
            DecoratorConfig config = new DecoratorConfig(true, null);
            assertNotNull(config.getSettings());
            assertTrue(config.getSettings().isEmpty());
        }
    }

    @Nested
    @DisplayName("Copy Constructor")
    class CopyConstructor {

        @Test
        @DisplayName("copies all fields")
        void copiesAllFields() {
            DecoratorConfig source = new DecoratorConfig();
            source.setEnabled(false);
            source.set("color", "red");
            source.set("speed", 2.0);

            DecoratorConfig copy = new DecoratorConfig(source);

            assertFalse(copy.isEnabled());
            assertEquals("red", copy.getString("color", ""));
            assertEquals(2.0, copy.getDouble("speed", 0.0), 1e-10);
        }

        @Test
        @DisplayName("modifying copy does not affect source")
        void copyIsIndependent() {
            DecoratorConfig source = new DecoratorConfig();
            source.set("key", "original");

            DecoratorConfig copy = new DecoratorConfig(source);
            copy.set("key", "modified");

            assertEquals("original", source.getString("key", ""));
            assertEquals("modified", copy.getString("key", ""));
        }
    }

    @Nested
    @DisplayName("Typed Getters")
    class TypedGetters {

        @Test
        @DisplayName("getString returns string value")
        void getStringReturnsString() {
            DecoratorConfig config = new DecoratorConfig();
            config.set("name", "glow");
            assertEquals("glow", config.getString("name", "default"));
        }

        @Test
        @DisplayName("getString returns default for missing key")
        void getStringMissingKey() {
            DecoratorConfig config = new DecoratorConfig();
            assertEquals("fallback", config.getString("missing", "fallback"));
        }

        @Test
        @DisplayName("getString returns default for non-string value")
        void getStringNonString() {
            DecoratorConfig config = new DecoratorConfig();
            config.set("number", 42);
            assertEquals("default", config.getString("number", "default"));
        }

        @Test
        @DisplayName("getInt returns integer value")
        void getIntReturnsInt() {
            DecoratorConfig config = new DecoratorConfig();
            config.set("count", 10);
            assertEquals(10, config.getInt("count", 0));
        }

        @Test
        @DisplayName("getInt parses string to int")
        void getIntParsesString() {
            DecoratorConfig config = new DecoratorConfig();
            config.set("count", "42");
            assertEquals(42, config.getInt("count", 0));
        }

        @Test
        @DisplayName("getInt returns default for unparseable string")
        void getIntUnparseable() {
            DecoratorConfig config = new DecoratorConfig();
            config.set("count", "notanumber");
            assertEquals(99, config.getInt("count", 99));
        }

        @Test
        @DisplayName("getInt returns default for missing key")
        void getIntMissingKey() {
            DecoratorConfig config = new DecoratorConfig();
            assertEquals(5, config.getInt("missing", 5));
        }

        @Test
        @DisplayName("getInt handles double Number value")
        void getIntFromDouble() {
            DecoratorConfig config = new DecoratorConfig();
            config.set("count", 3.7);
            assertEquals(3, config.getInt("count", 0));
        }

        @Test
        @DisplayName("getDouble returns double value")
        void getDoubleReturnsDouble() {
            DecoratorConfig config = new DecoratorConfig();
            config.set("speed", 1.5);
            assertEquals(1.5, config.getDouble("speed", 0.0), 1e-10);
        }

        @Test
        @DisplayName("getDouble parses string to double")
        void getDoubleParsesString() {
            DecoratorConfig config = new DecoratorConfig();
            config.set("speed", "2.75");
            assertEquals(2.75, config.getDouble("speed", 0.0), 1e-10);
        }

        @Test
        @DisplayName("getDouble returns default for unparseable string")
        void getDoubleUnparseable() {
            DecoratorConfig config = new DecoratorConfig();
            config.set("speed", "fast");
            assertEquals(1.0, config.getDouble("speed", 1.0), 1e-10);
        }

        @Test
        @DisplayName("getDouble returns default for missing key")
        void getDoubleMissingKey() {
            DecoratorConfig config = new DecoratorConfig();
            assertEquals(0.5, config.getDouble("missing", 0.5), 1e-10);
        }

        @Test
        @DisplayName("getFloat delegates to getDouble")
        void getFloatDelegatesToDouble() {
            DecoratorConfig config = new DecoratorConfig();
            config.set("val", 1.5);
            assertEquals(1.5f, config.getFloat("val", 0.0f), 1e-6);
        }

        @Test
        @DisplayName("getBoolean returns boolean value")
        void getBooleanReturnsBoolean() {
            DecoratorConfig config = new DecoratorConfig();
            config.set("active", true);
            assertTrue(config.getBoolean("active", false));
        }

        @Test
        @DisplayName("getBoolean parses string true")
        void getBooleanParsesStringTrue() {
            DecoratorConfig config = new DecoratorConfig();
            config.set("active", "true");
            assertTrue(config.getBoolean("active", false));
        }

        @Test
        @DisplayName("getBoolean parses string false")
        void getBooleanParsesStringFalse() {
            DecoratorConfig config = new DecoratorConfig();
            config.set("active", "false");
            assertFalse(config.getBoolean("active", true));
        }

        @Test
        @DisplayName("getBoolean returns default for missing key")
        void getBooleanMissingKey() {
            DecoratorConfig config = new DecoratorConfig();
            assertTrue(config.getBoolean("missing", true));
        }

        @Test
        @DisplayName("getBoolean returns default for non-boolean value")
        void getBooleanNonBoolean() {
            DecoratorConfig config = new DecoratorConfig();
            config.set("flag", 42);
            assertTrue(config.getBoolean("flag", true));
        }

        @Test
        @DisplayName("getStringList returns list of strings")
        void getStringListReturnsList() {
            DecoratorConfig config = new DecoratorConfig();
            config.set("colors", List.of("red", "green", "blue"));
            List<String> result = config.getStringList("colors", List.of());
            assertEquals(3, result.size());
            assertEquals("red", result.get(0));
            assertEquals("green", result.get(1));
            assertEquals("blue", result.get(2));
        }

        @Test
        @DisplayName("getStringList converts non-string items to string")
        void getStringListConvertsItems() {
            DecoratorConfig config = new DecoratorConfig();
            config.set("items", List.of(1, 2.5, true));
            List<String> result = config.getStringList("items", List.of());
            assertEquals("1", result.get(0));
            assertEquals("2.5", result.get(1));
            assertEquals("true", result.get(2));
        }

        @Test
        @DisplayName("getStringList returns default for missing key")
        void getStringListMissingKey() {
            DecoratorConfig config = new DecoratorConfig();
            List<String> defaultList = List.of("a", "b");
            List<String> result = config.getStringList("missing", defaultList);
            assertEquals(defaultList, result);
        }

        @Test
        @DisplayName("getStringList returns default for non-list value")
        void getStringListNonList() {
            DecoratorConfig config = new DecoratorConfig();
            config.set("items", "notalist");
            List<String> result = config.getStringList("items", List.of("default"));
            assertEquals(List.of("default"), result);
        }
    }

    @Nested
    @DisplayName("Set and Remove")
    class SetAndRemove {

        @Test
        @DisplayName("set adds a setting")
        void setAddsSetting() {
            DecoratorConfig config = new DecoratorConfig();
            config.set("key", "value");
            assertEquals("value", config.getSettings().get("key"));
        }

        @Test
        @DisplayName("set overwrites existing setting")
        void setOverwrites() {
            DecoratorConfig config = new DecoratorConfig();
            config.set("key", "old");
            config.set("key", "new");
            assertEquals("new", config.getString("key", ""));
        }

        @Test
        @DisplayName("remove deletes a setting")
        void removeDeletesSetting() {
            DecoratorConfig config = new DecoratorConfig();
            config.set("key", "value");
            config.remove("key");
            assertFalse(config.getSettings().containsKey("key"));
        }

        @Test
        @DisplayName("remove on missing key is a no-op")
        void removeNoOp() {
            DecoratorConfig config = new DecoratorConfig();
            config.remove("nonexistent");
            assertTrue(config.getSettings().isEmpty());
        }
    }

    @Nested
    @DisplayName("setEnabled")
    class SetEnabled {

        @Test
        @DisplayName("can disable config")
        void canDisable() {
            DecoratorConfig config = new DecoratorConfig();
            config.setEnabled(false);
            assertFalse(config.isEnabled());
        }

        @Test
        @DisplayName("can re-enable config")
        void canReEnable() {
            DecoratorConfig config = new DecoratorConfig();
            config.setEnabled(false);
            config.setEnabled(true);
            assertTrue(config.isEnabled());
        }
    }

    @Test
    @DisplayName("toString includes enabled and settings")
    void toStringFormat() {
        DecoratorConfig config = new DecoratorConfig();
        config.set("color", "red");
        String str = config.toString();

        assertTrue(str.contains("enabled=true"));
        assertTrue(str.contains("color=red"));
    }
}
