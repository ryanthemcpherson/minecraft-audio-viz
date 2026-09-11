package com.audioviz.stages;

import com.audioviz.AudioVizPlugin;
import com.audioviz.decorators.StageDecoratorManager;
import com.audioviz.entities.EntityPoolManager;
import com.audioviz.websocket.VizWebSocketServer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StageManagerRestoreTest {
    @TempDir Path directory;
    private final AudioVizPlugin plugin = mock(AudioVizPlugin.class);
    private final EntityPoolManager pools = mock(EntityPoolManager.class);
    private final StageDecoratorManager decorators = mock(StageDecoratorManager.class);
    private final VizWebSocketServer websocket = mock(VizWebSocketServer.class);
    private MockedStatic<Bukkit> bukkit;
    private StageManager manager;

    @BeforeEach
    void setUp() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getLogger()).thenReturn(Logger.getLogger(getClass().getName()));
        when(plugin.getEntityPoolManager()).thenReturn(pools);
        when(plugin.getDecoratorManager()).thenReturn(decorators);
        when(plugin.getWebSocketServer()).thenReturn(websocket);
        manager = new StageManager(plugin);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    @Test
    void reloadRebuildsActiveStagePoolsAndDecoratorsWithoutRewritingSavedState() throws Exception {
        writeStages(stageYaml("active", true), stageYaml("inactive", false));
        byte[] original = Files.readAllBytes(directory.resolve("stages.yml"));

        manager.loadStages();

        Stage active = manager.getStage("active");
        assertTrue(active.isActive());
        assertEquals(2000L, active.getLastActivatedAt());
        verify(pools).initializeBlockPool("active_main", 32, Material.GLOWSTONE);
        verify(pools, never()).initializeBlockPool(eq("inactive_main"), anyInt(), any());
        verify(decorators).activateDecorators(active);
        verify(decorators, never()).activateDecorators(manager.getStage("inactive"));
        verify(websocket).broadcast(any(com.google.gson.JsonObject.class));
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("stages.yml")));
    }

    @Test
    void oneFailedRestorationDoesNotPreventOtherActiveStagesFromStarting() throws Exception {
        writeStages(stageYaml("broken", true), stageYaml("healthy", true));
        doThrow(new IllegalStateException("fixture initialization failure"))
            .when(pools).initializeBlockPool(eq("broken_main"), anyInt(), any());

        assertDoesNotThrow(manager::loadStages);

        verify(pools).initializeBlockPool("healthy_main", 32, Material.GLOWSTONE);
        verify(decorators).activateDecorators(manager.getStage("healthy"));
        assertEquals(2000L, manager.getStage("healthy").getLastActivatedAt());
    }

    @Test
    void explicitActivationStillUpdatesAndPersistsActivationTime() throws Exception {
        writeStages(stageYaml("inactive", false));
        manager.loadStages();
        Stage stage = manager.getStage("inactive");

        manager.activateStage(stage);

        assertTrue(stage.isActive());
        assertTrue(stage.getLastActivatedAt() > 2000L);
        var saved = YamlConfiguration.loadConfiguration(directory.resolve("stages.yml").toFile());
        assertTrue(saved.getBoolean("stages.inactive.active"));
        assertEquals(stage.getLastActivatedAt(), saved.getLong("stages.inactive.last_activated_at"));
    }

    private void writeStages(String... stages) throws Exception {
        Files.writeString(directory.resolve("stages.yml"), "stages:\n" + String.join("", stages));
    }

    private String stageYaml(String name, boolean active) {
        return """
              %s:
                id: 11111111-1111-1111-1111-111111111111
                active: %s
                template: custom
                last_activated_at: 2000
                anchor:
                  world: world
                  x: 0
                  y: 64
                  z: 0
                zones:
                  main_stage:
                    zone_name: %s_main
                    config:
                      entity_count: 32
                      block_type: GLOWSTONE
            """.formatted(name, active, name);
    }
}
