package com.audioviz.particles;

import com.audioviz.zones.VisualizationZone;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Spawns particles in the world from ParticleSpawn requests.
 * Converts local (0-1) coordinates to world coordinates via zone transforms.
 *
 * Paper equivalent: world.spawnParticle(type, loc, count, dx, dy, dz, speed)
 * Fabric equivalent: world.spawnParticles(type, x, y, z, count, dx, dy, dz, speed)
 */
public class ParticleSpawner {

    /**
     * Spawn all particles from a list of ParticleSpawn requests in the given zone.
     */
    public static void spawnAll(List<ParticleSpawn> spawns, VisualizationZone zone) {
        ServerWorld world = zone.getWorld();
        if (world == null) return;

        for (ParticleSpawn spawn : spawns) {
            Vec3d worldPos = zone.localToWorld(spawn.getX(), spawn.getY(), spawn.getZ());

            world.spawnParticles(
                spawn.getType(),
                worldPos.x, worldPos.y, worldPos.z,
                spawn.getCount(),
                spawn.getOffsetX(), spawn.getOffsetY(), spawn.getOffsetZ(),
                spawn.getSpeed()
            );
        }
    }
}
