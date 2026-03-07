package com.audioviz.effects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BeatEffectConfig - pure data/logic class.
 * Uses a simple BeatEffect stub (no Bukkit dependencies needed).
 */
class BeatEffectConfigTest {

    /**
     * Minimal stub implementing BeatEffect for testing.
     * Does not use any Bukkit types in the methods we call.
     */
    private static BeatEffect stubEffect(String id) {
        return new BeatEffect() {
            @Override public String getId() { return id; }
            @Override public String getName() { return id; }
            @Override public void trigger(org.bukkit.Location loc,
                    com.audioviz.zones.VisualizationZone zone,
                    double intensity, java.util.Collection<org.bukkit.entity.Player> viewers) {}
        };
    }

    @Nested
    @DisplayName("Default Constructor")
    class DefaultConstructor {

        @Test
        @DisplayName("has no effects initially")
        void noEffects() {
            BeatEffectConfig config = new BeatEffectConfig();
            assertFalse(config.hasEffects());
        }

        @Test
        @DisplayName("sets default BEAT threshold to 0.4")
        void defaultBeatThreshold() {
            BeatEffectConfig config = new BeatEffectConfig();
            assertEquals(0.4f, config.getThreshold(BeatType.BEAT), 1e-6);
        }

        @Test
        @DisplayName("sets default BEAT cooldown to 150ms")
        void defaultBeatCooldown() {
            BeatEffectConfig config = new BeatEffectConfig();
            assertEquals(150L, config.getCooldown(BeatType.BEAT));
        }

        @Test
        @DisplayName("returns fallback threshold 0.5 for unconfigured types")
        void fallbackThreshold() {
            BeatEffectConfig config = new BeatEffectConfig();
            assertEquals(0.5f, config.getThreshold(BeatType.KICK), 1e-6);
        }

        @Test
        @DisplayName("returns fallback cooldown 150 for unconfigured types")
        void fallbackCooldown() {
            BeatEffectConfig config = new BeatEffectConfig();
            assertEquals(150L, config.getCooldown(BeatType.SNARE));
        }
    }

    @Nested
    @DisplayName("addEffect / removeEffect")
    class AddRemoveEffects {

        @Test
        @DisplayName("addEffect creates list for new beat type")
        void addEffectCreatesListForNewType() {
            BeatEffectConfig config = new BeatEffectConfig();
            BeatEffect effect = stubEffect("particle_burst");

            config.addEffect(BeatType.KICK, effect);

            assertTrue(config.hasEffects());
            assertEquals(1, config.getEffects(BeatType.KICK).size());
            assertEquals("particle_burst", config.getEffects(BeatType.KICK).get(0).getId());
        }

        @Test
        @DisplayName("addEffect appends to existing list")
        void addEffectAppendsToExistingList() {
            BeatEffectConfig config = new BeatEffectConfig();
            config.addEffect(BeatType.KICK, stubEffect("effect1"));
            config.addEffect(BeatType.KICK, stubEffect("effect2"));

            assertEquals(2, config.getEffects(BeatType.KICK).size());
        }

        @Test
        @DisplayName("removeEffect removes specific effect")
        void removeEffectRemovesSpecific() {
            BeatEffectConfig config = new BeatEffectConfig();
            BeatEffect effect = stubEffect("to_remove");
            config.addEffect(BeatType.KICK, effect);
            config.addEffect(BeatType.KICK, stubEffect("keep"));

            config.removeEffect(BeatType.KICK, effect);

            assertEquals(1, config.getEffects(BeatType.KICK).size());
            assertEquals("keep", config.getEffects(BeatType.KICK).get(0).getId());
        }

        @Test
        @DisplayName("removeEffect cleans up empty list")
        void removeEffectCleansUpEmptyList() {
            BeatEffectConfig config = new BeatEffectConfig();
            BeatEffect effect = stubEffect("only");
            config.addEffect(BeatType.KICK, effect);

            config.removeEffect(BeatType.KICK, effect);

            assertFalse(config.hasEffects());
            assertTrue(config.getEffects(BeatType.KICK).isEmpty());
        }

        @Test
        @DisplayName("removeEffect on missing type is a no-op")
        void removeEffectMissingType() {
            BeatEffectConfig config = new BeatEffectConfig();
            config.removeEffect(BeatType.KICK, stubEffect("nonexistent"));
            // No exception thrown
            assertFalse(config.hasEffects());
        }

        @Test
        @DisplayName("getEffects returns empty list for unconfigured type")
        void getEffectsReturnsEmptyForMissing() {
            BeatEffectConfig config = new BeatEffectConfig();
            List<BeatEffect> effects = config.getEffects(BeatType.HIHAT);
            assertNotNull(effects);
            assertTrue(effects.isEmpty());
        }
    }

    @Nested
    @DisplayName("setThreshold")
    class SetThreshold {

        @Test
        @DisplayName("stores threshold value")
        void storesValue() {
            BeatEffectConfig config = new BeatEffectConfig();
            config.setThreshold(BeatType.KICK, 0.7);
            assertEquals(0.7f, config.getThreshold(BeatType.KICK), 1e-6);
        }

        @Test
        @DisplayName("clamps threshold to maximum 1.0")
        void clampsToMax() {
            BeatEffectConfig config = new BeatEffectConfig();
            config.setThreshold(BeatType.KICK, 1.5);
            assertEquals(1.0f, config.getThreshold(BeatType.KICK), 1e-6);
        }

        @Test
        @DisplayName("clamps threshold to minimum 0.0")
        void clampsToMin() {
            BeatEffectConfig config = new BeatEffectConfig();
            config.setThreshold(BeatType.KICK, -0.5);
            assertEquals(0.0f, config.getThreshold(BeatType.KICK), 1e-6);
        }
    }

    @Nested
    @DisplayName("setCooldown")
    class SetCooldown {

        @Test
        @DisplayName("stores cooldown value")
        void storesValue() {
            BeatEffectConfig config = new BeatEffectConfig();
            config.setCooldown(BeatType.SNARE, 500L);
            assertEquals(500L, config.getCooldown(BeatType.SNARE));
        }

        @Test
        @DisplayName("clamps cooldown to minimum 0")
        void clampsToMin() {
            BeatEffectConfig config = new BeatEffectConfig();
            config.setCooldown(BeatType.SNARE, -100L);
            assertEquals(0L, config.getCooldown(BeatType.SNARE));
        }
    }

    @Nested
    @DisplayName("getBeatTypes")
    class GetBeatTypes {

        @Test
        @DisplayName("returns empty set when no effects added")
        void emptyWhenNoEffects() {
            BeatEffectConfig config = new BeatEffectConfig();
            assertTrue(config.getBeatTypes().isEmpty());
        }

        @Test
        @DisplayName("returns set of types with effects")
        void returnsTypesWithEffects() {
            BeatEffectConfig config = new BeatEffectConfig();
            config.addEffect(BeatType.KICK, stubEffect("e1"));
            config.addEffect(BeatType.SNARE, stubEffect("e2"));

            Set<BeatType> types = config.getBeatTypes();
            assertEquals(2, types.size());
            assertTrue(types.contains(BeatType.KICK));
            assertTrue(types.contains(BeatType.SNARE));
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("builds config with effects")
        void buildsWithEffects() {
            BeatEffectConfig config = new BeatEffectConfig.Builder()
                .addEffect(BeatType.KICK, stubEffect("burst"))
                .build();

            assertTrue(config.hasEffects());
            assertEquals(1, config.getEffects(BeatType.KICK).size());
        }

        @Test
        @DisplayName("builder has default thresholds for all types")
        void builderDefaultThresholds() {
            BeatEffectConfig config = new BeatEffectConfig.Builder().build();

            assertEquals(0.4f, config.getThreshold(BeatType.KICK), 1e-6);
            assertEquals(0.35f, config.getThreshold(BeatType.SNARE), 1e-6);
            assertEquals(0.3f, config.getThreshold(BeatType.HIHAT), 1e-6);
            assertEquals(0.6f, config.getThreshold(BeatType.BASS_DROP), 1e-6);
            assertEquals(0.5f, config.getThreshold(BeatType.PEAK), 1e-6);
            assertEquals(0.3f, config.getThreshold(BeatType.ANY), 1e-6);
        }

        @Test
        @DisplayName("builder has default cooldowns for all types")
        void builderDefaultCooldowns() {
            BeatEffectConfig config = new BeatEffectConfig.Builder().build();

            assertEquals(150L, config.getCooldown(BeatType.KICK));
            assertEquals(200L, config.getCooldown(BeatType.SNARE));
            assertEquals(100L, config.getCooldown(BeatType.HIHAT));
            assertEquals(2000L, config.getCooldown(BeatType.BASS_DROP));
            assertEquals(500L, config.getCooldown(BeatType.PEAK));
            assertEquals(100L, config.getCooldown(BeatType.ANY));
        }

        @Test
        @DisplayName("builder setThreshold clamps to [0, 1]")
        void builderThresholdClamping() {
            BeatEffectConfig config = new BeatEffectConfig.Builder()
                .setThreshold(BeatType.KICK, 2.0f)
                .setThreshold(BeatType.SNARE, -1.0f)
                .build();

            assertEquals(1.0f, config.getThreshold(BeatType.KICK), 1e-6);
            assertEquals(0.0f, config.getThreshold(BeatType.SNARE), 1e-6);
        }

        @Test
        @DisplayName("builder setCooldown clamps to >= 0")
        void builderCooldownClamping() {
            BeatEffectConfig config = new BeatEffectConfig.Builder()
                .setCooldown(BeatType.KICK, -500L)
                .build();

            assertEquals(0L, config.getCooldown(BeatType.KICK));
        }

        @Test
        @DisplayName("builder chains methods fluently")
        void builderChainsMethods() {
            BeatEffectConfig config = new BeatEffectConfig.Builder()
                .addEffect(BeatType.KICK, stubEffect("e1"))
                .addEffect(BeatType.SNARE, stubEffect("e2"))
                .setThreshold(BeatType.KICK, 0.8f)
                .setCooldown(BeatType.KICK, 300L)
                .build();

            assertEquals(1, config.getEffects(BeatType.KICK).size());
            assertEquals(1, config.getEffects(BeatType.SNARE).size());
            assertEquals(0.8f, config.getThreshold(BeatType.KICK), 1e-6);
            assertEquals(300L, config.getCooldown(BeatType.KICK));
        }
    }

    @Nested
    @DisplayName("canTrigger / markTriggered")
    class Cooldowns {

        @Test
        @DisplayName("canTrigger returns true initially")
        void canTriggerInitially() {
            BeatEffectConfig config = new BeatEffectConfig();
            assertTrue(config.canTrigger(BeatType.KICK));
        }

        @Test
        @DisplayName("canTrigger returns false immediately after markTriggered with nonzero cooldown")
        void cannotTriggerImmediatelyAfterMark() {
            BeatEffectConfig config = new BeatEffectConfig();
            config.setCooldown(BeatType.KICK, 1000L);
            config.markTriggered(BeatType.KICK);
            assertFalse(config.canTrigger(BeatType.KICK));
        }

        @Test
        @DisplayName("canTrigger returns true for zero cooldown after mark")
        void canTriggerWithZeroCooldown() {
            BeatEffectConfig config = new BeatEffectConfig();
            config.setCooldown(BeatType.KICK, 0L);
            config.markTriggered(BeatType.KICK);
            assertTrue(config.canTrigger(BeatType.KICK));
        }
    }

    @Nested
    @DisplayName("BeatType enum")
    class BeatTypeEnum {

        @Test
        @DisplayName("has 7 beat types")
        void hasSevenBeatTypes() {
            assertEquals(7, BeatType.values().length);
        }

        @Test
        @DisplayName("contains expected types")
        void containsExpectedTypes() {
            assertNotNull(BeatType.valueOf("KICK"));
            assertNotNull(BeatType.valueOf("SNARE"));
            assertNotNull(BeatType.valueOf("HIHAT"));
            assertNotNull(BeatType.valueOf("BASS_DROP"));
            assertNotNull(BeatType.valueOf("PEAK"));
            assertNotNull(BeatType.valueOf("ANY"));
            assertNotNull(BeatType.valueOf("BEAT"));
        }
    }
}
