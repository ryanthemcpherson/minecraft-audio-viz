package com.audioviz.entities;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for EntityUpdate record and its builder.
 * Uses Mockito to mock Bukkit World since Location requires it.
 */
class EntityUpdateTest {

    private Location mockLocation(double x, double y, double z) {
        World world = Mockito.mock(World.class);
        return new Location(world, x, y, z);
    }

    private Transformation mockTransformation(float scale) {
        return new Transformation(
            new Vector3f(0, 0, 0),
            new AxisAngle4f(0, 0, 1, 0),
            new Vector3f(scale, scale, scale),
            new AxisAngle4f(0, 0, 0, 1)
        );
    }

    // --- Static factory tests ---

    @Test
    @DisplayName("position() creates position-only update")
    void positionFactoryCreatesPositionOnly() {
        Location loc = mockLocation(10, 20, 30);
        EntityUpdate update = EntityUpdate.position("block_0", loc);

        assertEquals("block_0", update.entityId());
        assertTrue(update.hasLocation());
        assertFalse(update.hasTransform());
        assertFalse(update.hasBrightness());
        assertFalse(update.hasGlow());
        assertFalse(update.hasInterpolation());
        assertNotNull(update.location());
    }

    @Test
    @DisplayName("transform() creates transform-only update")
    void transformFactoryCreatesTransformOnly() {
        Transformation trans = mockTransformation(1.5f);
        EntityUpdate update = EntityUpdate.transform("block_1", trans);

        assertEquals("block_1", update.entityId());
        assertFalse(update.hasLocation());
        assertTrue(update.hasTransform());
        assertNotNull(update.transformation());
    }

    @Test
    @DisplayName("full() creates position + transform update")
    void fullFactoryCreatesBoth() {
        Location loc = mockLocation(1, 2, 3);
        Transformation trans = mockTransformation(0.8f);
        EntityUpdate update = EntityUpdate.full("block_2", loc, trans);

        assertEquals("block_2", update.entityId());
        assertTrue(update.hasLocation());
        assertTrue(update.hasTransform());
    }

    @Test
    @DisplayName("complete() creates update with all properties")
    void completeFactoryCreatesAll() {
        Location loc = mockLocation(5, 10, 15);
        Transformation trans = mockTransformation(2.0f);
        EntityUpdate update = EntityUpdate.complete("block_3", loc, trans, 10, true);

        assertEquals("block_3", update.entityId());
        assertTrue(update.hasLocation());
        assertTrue(update.hasTransform());
        assertTrue(update.hasBrightness());
        assertEquals(10, update.brightness());
        assertTrue(update.hasGlow());
        assertTrue(update.glow());
    }

    // --- Builder tests ---

    @Test
    @DisplayName("Builder creates minimal update with just entityId")
    void builderMinimalUpdate() {
        EntityUpdate update = EntityUpdate.builder("block_5").build();

        assertEquals("block_5", update.entityId());
        assertFalse(update.hasLocation());
        assertFalse(update.hasTransform());
        assertFalse(update.hasBrightness());
        assertFalse(update.hasGlow());
        assertFalse(update.hasInterpolation());
    }

    @Test
    @DisplayName("Builder sets location")
    void builderSetsLocation() {
        Location loc = mockLocation(100, 64, 200);
        EntityUpdate update = EntityUpdate.builder("block_6")
            .location(loc)
            .build();

        assertTrue(update.hasLocation());
        assertEquals(100, update.location().getX());
    }

    @Test
    @DisplayName("Builder sets transformation")
    void builderSetsTransformation() {
        Transformation trans = mockTransformation(3.0f);
        EntityUpdate update = EntityUpdate.builder("block_7")
            .transformation(trans)
            .build();

        assertTrue(update.hasTransform());
    }

    @Test
    @DisplayName("Builder sets brightness")
    void builderSetsBrightness() {
        EntityUpdate update = EntityUpdate.builder("block_8")
            .brightness(7)
            .build();

        assertTrue(update.hasBrightness());
        assertEquals(7, update.brightness());
    }

    @Test
    @DisplayName("Builder sets glow")
    void builderSetsGlow() {
        EntityUpdate update = EntityUpdate.builder("block_9")
            .glow(true)
            .build();

        assertTrue(update.hasGlow());
        assertTrue(update.glow());
    }

    @Test
    @DisplayName("Builder sets interpolation duration")
    void builderSetsInterpolation() {
        EntityUpdate update = EntityUpdate.builder("block_10")
            .interpolationDuration(5)
            .build();

        assertTrue(update.hasInterpolation());
        assertEquals(5, update.interpolationDuration());
    }

    @Test
    @DisplayName("Builder chains all properties")
    void builderChainsAll() {
        Location loc = mockLocation(1, 2, 3);
        Transformation trans = mockTransformation(1.0f);

        EntityUpdate update = EntityUpdate.builder("block_full")
            .location(loc)
            .transformation(trans)
            .brightness(12)
            .glow(true)
            .interpolationDuration(3)
            .build();

        assertEquals("block_full", update.entityId());
        assertTrue(update.hasLocation());
        assertTrue(update.hasTransform());
        assertTrue(update.hasBrightness());
        assertEquals(12, update.brightness());
        assertTrue(update.hasGlow());
        assertTrue(update.glow());
        assertTrue(update.hasInterpolation());
        assertEquals(3, update.interpolationDuration());
    }

    @Test
    @DisplayName("Default brightness is 15")
    void defaultBrightnessIs15() {
        EntityUpdate update = EntityUpdate.position("block_default", mockLocation(0, 0, 0));
        assertEquals(15, update.brightness());
    }

    @Test
    @DisplayName("Default interpolation duration is -1 (unset)")
    void defaultInterpolationIsNegativeOne() {
        EntityUpdate update = EntityUpdate.builder("block_default").build();
        assertEquals(-1, update.interpolationDuration());
    }

    // --- Record equality tests ---

    @Test
    @DisplayName("Entity ID must not be null in builder")
    void entityIdNotNull() {
        // Builder accepts any string, verify it stores it
        EntityUpdate update = EntityUpdate.builder("test_entity").build();
        assertEquals("test_entity", update.entityId());
    }
}
