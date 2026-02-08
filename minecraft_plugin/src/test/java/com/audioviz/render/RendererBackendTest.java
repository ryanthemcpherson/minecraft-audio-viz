package com.audioviz.render;

import com.audioviz.entities.EntityUpdate;
import com.audioviz.patterns.AudioState;
import com.audioviz.zones.VisualizationZone;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the RendererBackend interface contract and backend type resolution.
 */
class RendererBackendTest {

    @Nested
    class InterfaceContract {

        /** Verify the interface has the expected method signatures. */
        @Test
        void interfaceDefinesExpectedMethods() {
            // If this compiles, the contract is correct. Verify at runtime
            // that a stub implementation can be instantiated.
            RendererBackend stub = createStub();

            assertEquals(RendererBackendType.DISPLAY_ENTITIES, stub.getType());
            assertEquals(0, stub.getElementCount("test"));
            assertTrue(stub.getElementIds("test").isEmpty());
        }

        @Test
        void stubReceivesUpdateFrame() {
            RendererBackend stub = createStub();
            Location loc = new Location(null, 1, 2, 3);
            EntityUpdate update = EntityUpdate.position("block_0", loc);
            List<EntityUpdate> updates = List.of(update);

            // Should not throw
            stub.updateFrame("test", updates);
        }

        @Test
        void stubReceivesAudioState() {
            RendererBackend stub = createStub();
            double[] bands = {0.5, 0.3, 0.2, 0.1, 0.05};
            AudioState state = new AudioState(bands, 0.4, false, 0.0, 1);

            // Should not throw
            stub.updateAudioState(state);
        }

        @Test
        void stubHandlesVisibility() {
            RendererBackend stub = createStub();

            // Should not throw
            stub.setVisible("test", true);
            stub.setVisible("test", false);
        }

        @Test
        void stubHandlesTeardown() {
            RendererBackend stub = createStub();

            // Should not throw
            stub.teardown("test");
        }

        private RendererBackend createStub() {
            return new RendererBackend() {
                @Override
                public RendererBackendType getType() {
                    return RendererBackendType.DISPLAY_ENTITIES;
                }

                @Override
                public void initialize(VisualizationZone zone, int count, Material material) {}

                @Override
                public void updateFrame(String zoneName, List<EntityUpdate> updates) {}

                @Override
                public void updateAudioState(AudioState audioState) {}

                @Override
                public void setVisible(String zoneName, boolean visible) {}

                @Override
                public void teardown(String zoneName) {}

                @Override
                public int getElementCount(String zoneName) { return 0; }

                @Override
                public Set<String> getElementIds(String zoneName) {
                    return Collections.emptySet();
                }
            };
        }
    }

    @Nested
    class BackendTypeTests {

        @Test
        void allBackendTypesHaveStableKeys() {
            assertEquals("display_entities", RendererBackendType.DISPLAY_ENTITIES.key());
            assertEquals("particles", RendererBackendType.PARTICLES.key());
            assertEquals("hologram", RendererBackendType.HOLOGRAM.key());
        }

        @Test
        void fromKeyIsCaseInsensitive() {
            assertEquals(RendererBackendType.DISPLAY_ENTITIES,
                RendererBackendType.fromKey("Display_Entities"));
            assertEquals(RendererBackendType.PARTICLES,
                RendererBackendType.fromKey("PARTICLES"));
            assertEquals(RendererBackendType.HOLOGRAM,
                RendererBackendType.fromKey("Hologram"));
        }

        @Test
        void fromKeyReturnsNullForInvalidKey() {
            assertNull(RendererBackendType.fromKey("invalid_backend"));
            assertNull(RendererBackendType.fromKey(""));
            assertNull(RendererBackendType.fromKey(null));
        }

        @Test
        void exactThreeBackendTypes() {
            assertEquals(3, RendererBackendType.values().length);
        }
    }

    @Nested
    class EntityUpdateBatching {

        @Test
        void positionOnlyUpdate() {
            Location loc = new Location(null, 10, 20, 30);
            EntityUpdate update = EntityUpdate.position("block_0", loc);

            assertEquals("block_0", update.entityId());
            assertTrue(update.hasLocation());
            assertFalse(update.hasTransform());
            assertFalse(update.hasBrightness());
            assertFalse(update.hasGlow());
        }

        @Test
        void fullUpdateWithAllProperties() {
            Location loc = new Location(null, 1, 2, 3);
            Transformation transform = new Transformation(
                new Vector3f(0, 0, 0),
                new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(0.5f, 0.5f, 0.5f),
                new AxisAngle4f(0, 0, 0, 1)
            );
            EntityUpdate update = EntityUpdate.complete("block_0", loc, transform, 12, true);

            assertTrue(update.hasLocation());
            assertTrue(update.hasTransform());
            assertTrue(update.hasBrightness());
            assertEquals(12, update.brightness());
            assertTrue(update.hasGlow());
            assertTrue(update.glow());
        }

        @Test
        void builderCreatesPartialUpdate() {
            EntityUpdate update = EntityUpdate.builder("block_5")
                .brightness(10)
                .glow(true)
                .build();

            assertEquals("block_5", update.entityId());
            assertFalse(update.hasLocation());
            assertFalse(update.hasTransform());
            assertTrue(update.hasBrightness());
            assertEquals(10, update.brightness());
            assertTrue(update.hasGlow());
            assertTrue(update.glow());
        }

        @Test
        void builderWithInterpolation() {
            EntityUpdate update = EntityUpdate.builder("block_0")
                .interpolationDuration(5)
                .build();

            assertTrue(update.hasInterpolation());
            assertEquals(5, update.interpolationDuration());
        }

        @Test
        void batchListPreservesOrder() {
            List<EntityUpdate> batch = List.of(
                EntityUpdate.position("block_0", new Location(null, 0, 0, 0)),
                EntityUpdate.position("block_1", new Location(null, 1, 1, 1)),
                EntityUpdate.position("block_2", new Location(null, 2, 2, 2))
            );

            assertEquals(3, batch.size());
            assertEquals("block_0", batch.get(0).entityId());
            assertEquals("block_1", batch.get(1).entityId());
            assertEquals("block_2", batch.get(2).entityId());
        }
    }
}
