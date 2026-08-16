package com.audioviz.listeners;

import com.audioviz.AudioVizPlugin;
import com.audioviz.entities.EntityPoolManager;
import com.audioviz.zones.VisualizationZone;
import com.audioviz.zones.ZoneManager;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.event.world.WorldUnloadEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AudioVizEventListenerTest {

    @Test
    void worldUnloadSynchronouslyCleansOnlyZonesInThatWorld() {
        AudioVizPlugin plugin = mock(AudioVizPlugin.class);
        ZoneManager zoneManager = mock(ZoneManager.class);
        EntityPoolManager poolManager = mock(EntityPoolManager.class);
        World unloadingWorld = mock(World.class);
        World retainedWorld = mock(World.class);
        VisualizationZone unloadingZone = mock(VisualizationZone.class);
        VisualizationZone retainedZone = mock(VisualizationZone.class);
        WorldUnloadEvent event = new WorldUnloadEvent(unloadingWorld);
        when(plugin.getZoneManager()).thenReturn(zoneManager);
        when(plugin.getEntityPoolManager()).thenReturn(poolManager);
        when(plugin.getLogger()).thenReturn(Logger.getLogger(getClass().getName()));
        when(zoneManager.getAllZones()).thenReturn(List.of(unloadingZone, retainedZone));
        when(unloadingWorld.getName()).thenReturn("unloading");
        when(retainedWorld.getName()).thenReturn("retained");
        when(unloadingZone.getName()).thenReturn("unloading-zone");
        when(unloadingZone.getWorld()).thenReturn(unloadingWorld);
        when(retainedZone.getName()).thenReturn("retained-zone");
        when(retainedZone.getWorld()).thenReturn(retainedWorld);

        new AudioVizEventListener(plugin).onWorldUnload(event);

        verify(poolManager).cleanupZoneSync("unloading-zone");
        verify(poolManager, never()).cleanupZoneSync("retained-zone");
    }
}
