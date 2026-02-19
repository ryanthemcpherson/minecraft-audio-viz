package com.audioviz.zones;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VisualizationZone coordinate transformation and bounds checking.
 * Uses Mockito to mock World since Location requires it.
 */
class VisualizationZoneTest {

    private World mockWorld;

    @BeforeEach
    void setUp() {
        mockWorld = Mockito.mock(World.class);
        Mockito.when(mockWorld.getName()).thenReturn("world");
    }

    private Location loc(double x, double y, double z) {
        return new Location(mockWorld, x, y, z);
    }

    // --- Constructor tests ---

    @Test
    @DisplayName("Default zone has 10x10x10 size and 0 rotation")
    void defaultZoneSize() {
        VisualizationZone zone = new VisualizationZone("test", loc(0, 0, 0));

        assertEquals("test", zone.getName());
        assertNotNull(zone.getId());
        assertEquals(10.0, zone.getSize().getX());
        assertEquals(10.0, zone.getSize().getY());
        assertEquals(10.0, zone.getSize().getZ());
        assertEquals(0f, zone.getRotation());
    }

    @Test
    @DisplayName("Full constructor preserves all values")
    void fullConstructorPreservesValues() {
        UUID id = UUID.randomUUID();
        VisualizationZone zone = new VisualizationZone(
            "custom", id, loc(100, 64, 200),
            new Vector(20, 15, 20), 45f
        );

        assertEquals("custom", zone.getName());
        assertEquals(id, zone.getId());
        assertEquals(100, zone.getOrigin().getX());
        assertEquals(64, zone.getOrigin().getY());
        assertEquals(200, zone.getOrigin().getZ());
        assertEquals(20.0, zone.getSize().getX());
        assertEquals(15.0, zone.getSize().getY());
        assertEquals(20.0, zone.getSize().getZ());
        assertEquals(45f, zone.getRotation());
    }

    // --- localToWorld tests ---

    @Test
    @DisplayName("localToWorld(0,0,0) returns origin")
    void localToWorldOriginReturnsOrigin() {
        VisualizationZone zone = new VisualizationZone("test", loc(100, 64, 200));
        Location result = zone.localToWorld(0, 0, 0);

        assertEquals(100, result.getX(), 1e-6);
        assertEquals(64, result.getY(), 1e-6);
        assertEquals(200, result.getZ(), 1e-6);
    }

    @Test
    @DisplayName("localToWorld(1,1,1) returns origin + size")
    void localToWorldMaxReturnsOriginPlusSize() {
        VisualizationZone zone = new VisualizationZone("test", loc(100, 64, 200));
        Location result = zone.localToWorld(1, 1, 1);

        assertEquals(110, result.getX(), 1e-6); // 100 + 10
        assertEquals(74, result.getY(), 1e-6);  // 64 + 10
        assertEquals(210, result.getZ(), 1e-6);  // 200 + 10
    }

    @Test
    @DisplayName("localToWorld(0.5, 0.5, 0.5) returns center")
    void localToWorldCenterReturnsCenter() {
        VisualizationZone zone = new VisualizationZone("test", loc(0, 0, 0));
        Location result = zone.localToWorld(0.5, 0.5, 0.5);

        assertEquals(5.0, result.getX(), 1e-6);
        assertEquals(5.0, result.getY(), 1e-6);
        assertEquals(5.0, result.getZ(), 1e-6);
    }

    @Test
    @DisplayName("localToWorld respects zone size")
    void localToWorldRespectsSize() {
        VisualizationZone zone = new VisualizationZone("test", loc(0, 0, 0));
        zone.setSize(20, 30, 40);

        Location result = zone.localToWorld(0.5, 0.5, 0.5);

        assertEquals(10.0, result.getX(), 1e-6);
        assertEquals(15.0, result.getY(), 1e-6);
        assertEquals(20.0, result.getZ(), 1e-6);
    }

    @Test
    @DisplayName("localToWorld with 90-degree rotation swaps X and Z")
    void localToWorldWith90DegRotation() {
        VisualizationZone zone = new VisualizationZone("test", loc(0, 0, 0));
        zone.setRotation(90f);

        // With 90 degree rotation: (1, 0, 0) in local should map to (0, 0, 1) in world
        // scaledX=10, scaledZ=0, rotatedX = 10*cos(90) - 0*sin(90) = 0, rotatedZ = 10*sin(90) + 0*cos(90) = 10
        Location result = zone.localToWorld(1, 0, 0);

        assertEquals(0.0, result.getX(), 1e-6);
        assertEquals(0.0, result.getY(), 1e-6);
        assertEquals(10.0, result.getZ(), 1e-6);
    }

    @Test
    @DisplayName("localToWorld with 180-degree rotation inverts X and Z")
    void localToWorldWith180DegRotation() {
        VisualizationZone zone = new VisualizationZone("test", loc(0, 0, 0));
        zone.setRotation(180f);

        // (1, 0, 0) -> cos(180)*10 = -10, sin(180)*10 = 0
        Location result = zone.localToWorld(1, 0, 0);

        assertEquals(-10.0, result.getX(), 1e-4);
        assertEquals(0.0, result.getY(), 1e-6);
        assertEquals(0.0, result.getZ(), 1e-4);
    }

    @ParameterizedTest
    @CsvSource({
        "0.0, 0.0, 0.0, 100.0, 64.0, 200.0",
        "0.5, 0.5, 0.5, 105.0, 69.0, 205.0",
        "1.0, 1.0, 1.0, 110.0, 74.0, 210.0",
        "0.25, 0.75, 0.1, 102.5, 71.5, 201.0"
    })
    @DisplayName("localToWorld parametric test (no rotation)")
    void localToWorldParametric(double lx, double ly, double lz,
                                 double expectedX, double expectedY, double expectedZ) {
        VisualizationZone zone = new VisualizationZone("test", loc(100, 64, 200));
        Location result = zone.localToWorld(lx, ly, lz);

        assertEquals(expectedX, result.getX(), 1e-6, "X mismatch");
        assertEquals(expectedY, result.getY(), 1e-6, "Y mismatch");
        assertEquals(expectedZ, result.getZ(), 1e-6, "Z mismatch");
    }

    // --- getCenter tests ---

    @Test
    @DisplayName("getCenter() returns center of zone")
    void getCenterReturnsCenter() {
        VisualizationZone zone = new VisualizationZone("test", loc(0, 0, 0));
        Location center = zone.getCenter();

        assertEquals(5.0, center.getX(), 1e-6);
        assertEquals(5.0, center.getY(), 1e-6);
        assertEquals(5.0, center.getZ(), 1e-6);
    }

    // --- contains tests ---

    @Test
    @DisplayName("contains() returns true for point inside zone")
    void containsTrueForInsidePoint() {
        VisualizationZone zone = new VisualizationZone("test", loc(0, 0, 0));
        assertTrue(zone.contains(loc(5, 5, 5)));
    }

    @Test
    @DisplayName("contains() returns true for point on boundary")
    void containsTrueForBoundary() {
        VisualizationZone zone = new VisualizationZone("test", loc(0, 0, 0));
        assertTrue(zone.contains(loc(0, 0, 0)));   // origin
        assertTrue(zone.contains(loc(10, 10, 10))); // max corner
    }

    @Test
    @DisplayName("contains() returns false for point outside zone")
    void containsFalseForOutsidePoint() {
        VisualizationZone zone = new VisualizationZone("test", loc(0, 0, 0));
        assertFalse(zone.contains(loc(-1, 5, 5)));
        assertFalse(zone.contains(loc(11, 5, 5)));
        assertFalse(zone.contains(loc(5, -1, 5)));
        assertFalse(zone.contains(loc(5, 11, 5)));
        assertFalse(zone.contains(loc(5, 5, -1)));
        assertFalse(zone.contains(loc(5, 5, 11)));
    }

    @Test
    @DisplayName("contains() returns false for different world")
    void containsFalseForDifferentWorld() {
        World otherWorld = Mockito.mock(World.class);
        Mockito.when(otherWorld.getName()).thenReturn("nether");

        VisualizationZone zone = new VisualizationZone("test", loc(0, 0, 0));
        Location otherLoc = new Location(otherWorld, 5, 5, 5);

        assertFalse(zone.contains(otherLoc));
    }

    // --- Setter tests ---

    @Test
    @DisplayName("setSize() updates zone size")
    void setSizeUpdatesSize() {
        VisualizationZone zone = new VisualizationZone("test", loc(0, 0, 0));
        zone.setSize(20, 30, 40);

        assertEquals(20.0, zone.getSize().getX());
        assertEquals(30.0, zone.getSize().getY());
        assertEquals(40.0, zone.getSize().getZ());
    }

    @Test
    @DisplayName("setRotation() wraps at 360")
    void setRotationWraps() {
        VisualizationZone zone = new VisualizationZone("test", loc(0, 0, 0));
        zone.setRotation(450f);
        assertEquals(90f, zone.getRotation());
    }

    @Test
    @DisplayName("setOrigin() clones location (defensive copy)")
    void setOriginClonesLocation() {
        Location original = loc(10, 20, 30);
        VisualizationZone zone = new VisualizationZone("test", original);

        // Modifying the original should not affect the zone
        original.setX(999);
        assertEquals(10.0, zone.getOrigin().getX());
    }

    @Test
    @DisplayName("getOrigin() returns clone (defensive copy)")
    void getOriginReturnsCopy() {
        VisualizationZone zone = new VisualizationZone("test", loc(10, 20, 30));
        Location origin = zone.getOrigin();
        origin.setX(999);

        // Modifying the returned origin should not affect the zone
        assertEquals(10.0, zone.getOrigin().getX());
    }

    @Test
    @DisplayName("toString() contains zone name and coordinates")
    void toStringContainsInfo() {
        VisualizationZone zone = new VisualizationZone("myzone", loc(100, 64, 200));
        String str = zone.toString();

        assertTrue(str.contains("myzone"));
        assertTrue(str.contains("100.0"));
        assertTrue(str.contains("64.0"));
        assertTrue(str.contains("200.0"));
    }
}
