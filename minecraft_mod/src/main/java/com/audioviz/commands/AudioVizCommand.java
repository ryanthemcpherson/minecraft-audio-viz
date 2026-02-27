package com.audioviz.commands;

import com.audioviz.AudioVizMod;
import com.audioviz.effects.BeatEffectConfig;
import com.audioviz.effects.BeatType;
import com.audioviz.gui.menus.MainMenu;
import com.audioviz.gui.menus.StageListMenu;
import com.audioviz.stages.Stage;
import com.audioviz.zones.VisualizationZone;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

/**
 * Brigadier commands for AudioViz.
 * Ported from Paper's CommandExecutor/TabCompleter to Brigadier builder pattern.
 */
public class AudioVizCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, AudioVizMod mod) {
        // Tab completion providers
        SuggestionProvider<ServerCommandSource> zoneNames = (ctx, builder) -> {
            for (String name : mod.getZoneManager().getZoneNames()) {
                if (name.toLowerCase().startsWith(builder.getRemaining().toLowerCase()))
                    builder.suggest(name);
            }
            return builder.buildFuture();
        };

        SuggestionProvider<ServerCommandSource> stageNames = (ctx, builder) -> {
            for (String name : mod.getStageManager().getStageNames()) {
                if (name.toLowerCase().startsWith(builder.getRemaining().toLowerCase()))
                    builder.suggest(name);
            }
            return builder.buildFuture();
        };

        SuggestionProvider<ServerCommandSource> templateNames = (ctx, builder) -> {
            for (String t : new String[]{"small", "medium", "large", "custom"}) {
                if (t.startsWith(builder.getRemaining().toLowerCase()))
                    builder.suggest(t);
            }
            return builder.buildFuture();
        };

        SuggestionProvider<ServerCommandSource> patternNames = (ctx, builder) -> {
            var bpm = mod.getBitmapPatternManager();
            if (bpm != null) {
                for (String id : bpm.getPatternIds()) {
                    if (id.toLowerCase().startsWith(builder.getRemaining().toLowerCase()))
                        builder.suggest(id);
                }
            }
            return builder.buildFuture();
        };

        dispatcher.register(CommandManager.literal("audioviz")
            .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
            .then(CommandManager.literal("zone")
                .then(CommandManager.literal("create")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .executes(ctx -> createZone(ctx, mod))))
                .then(CommandManager.literal("delete")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .suggests(zoneNames)
                        .executes(ctx -> deleteZone(ctx, mod))))
                .then(CommandManager.literal("list")
                    .executes(ctx -> listZones(ctx, mod)))
                .then(CommandManager.literal("info")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .suggests(zoneNames)
                        .executes(ctx -> zoneInfo(ctx, mod))))
                .then(CommandManager.literal("setsize")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .suggests(zoneNames)
                        .then(CommandManager.argument("x", IntegerArgumentType.integer(1, 100))
                            .then(CommandManager.argument("y", IntegerArgumentType.integer(1, 100))
                                .then(CommandManager.argument("z", IntegerArgumentType.integer(1, 100))
                                    .executes(ctx -> setZoneSize(ctx, mod)))))))
            )
            .then(CommandManager.literal("stage")
                .then(CommandManager.literal("list")
                    .executes(ctx -> stageList(ctx, mod)))
                .then(CommandManager.literal("create")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .then(CommandManager.argument("template", StringArgumentType.word())
                            .suggests(templateNames)
                            .executes(ctx -> stageCreate(ctx, mod)))))
                .then(CommandManager.literal("delete")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .suggests(stageNames)
                        .executes(ctx -> stageDelete(ctx, mod))))
                .then(CommandManager.literal("activate")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .suggests(stageNames)
                        .executes(ctx -> stageActivate(ctx, mod))))
                .then(CommandManager.literal("place")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .suggests(stageNames)
                        .executes(ctx -> stagePlace(ctx, mod))))
            )
            .then(CommandManager.literal("cancel")
                .executes(ctx -> cancelPlacement(ctx, mod)))
            .then(CommandManager.literal("show")
                .then(CommandManager.literal("zone")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .suggests(zoneNames)
                        .executes(ctx -> showZoneBoundary(ctx, mod))))
                .then(CommandManager.literal("all")
                    .executes(ctx -> showAllBoundaries(ctx, mod)))
                .then(CommandManager.literal("stage")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .suggests(stageNames)
                        .executes(ctx -> showStageBoundaries(ctx, mod))))
                .then(CommandManager.literal("off")
                    .executes(ctx -> hideBoundaries(ctx, mod))))
            .then(CommandManager.literal("select")
                .executes(ctx -> toggleSelectMode(ctx, mod))
                .then(CommandManager.literal("clear")
                    .executes(ctx -> clearSelection(ctx, mod))))
            .then(CommandManager.literal("menu")
                .executes(ctx -> openMenu(ctx, mod)))
            .then(CommandManager.literal("pattern")
                .then(CommandManager.argument("name", StringArgumentType.string())
                    .suggests(patternNames)
                    .executes(ctx -> setPattern(ctx, mod))))
            .then(CommandManager.literal("particles")
                .executes(ctx -> toggleParticles(ctx, mod))
                .then(CommandManager.literal("on")
                    .executes(ctx -> setParticles(ctx, mod, true)))
                .then(CommandManager.literal("off")
                    .executes(ctx -> setParticles(ctx, mod, false)))
                .then(CommandManager.argument("zone", StringArgumentType.word())
                    .suggests(zoneNames)
                    .executes(ctx -> toggleZoneParticles(ctx, mod))))
            .then(CommandManager.literal("lights")
                .then(CommandManager.argument("zone", StringArgumentType.word())
                    .suggests(zoneNames)
                    .executes(ctx -> toggleLights(ctx, mod))))
            .then(CommandManager.literal("beats")
                .then(CommandManager.argument("zone", StringArgumentType.word())
                    .suggests(zoneNames)
                    .executes(ctx -> toggleBeats(ctx, mod))))
            .then(CommandManager.literal("status")
                .executes(ctx -> showStatus(ctx, mod)))
            .then(CommandManager.literal("help")
                .executes(ctx -> showHelp(ctx)))
            .executes(ctx -> showHelp(ctx))
        );
    }

    private static int createZone(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("Only players can create zones"));
            return 0;
        }

        String name = StringArgumentType.getString(ctx, "name");
        if (mod.getZoneManager().zoneExists(name)) {
            source.sendError(Text.literal("Zone '" + name + "' already exists"));
            return 0;
        }

        BlockPos pos = player.getBlockPos();
        ServerWorld world = player.getEntityWorld();
        VisualizationZone zone = mod.getZoneManager().createZone(name, world, pos);

        if (zone != null) {
            source.sendFeedback(() -> Text.literal("Created zone '" + name + "' at " +
                pos.getX() + ", " + pos.getY() + ", " + pos.getZ())
                .formatted(Formatting.GREEN), true);
            return 1;
        }
        source.sendError(Text.literal("Failed to create zone"));
        return 0;
    }

    private static int deleteZone(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
        String name = StringArgumentType.getString(ctx, "name");

        // Cleanup all renderers, bitmap state, and audio-reactive subsystems
        mod.getMapRenderer().destroyDisplay(name);
        if (mod.getBitmapToEntityBridge() != null) mod.getBitmapToEntityBridge().destroyWall(name);
        mod.getVirtualRenderer().destroyPool(name);
        var bpm = mod.getBitmapPatternManager();
        if (bpm != null) bpm.deactivateZone(name);
        if (mod.getAmbientLightManager() != null) mod.getAmbientLightManager().teardownZone(name);
        if (mod.getBeatEventManager() != null) mod.getBeatEventManager().removeZoneConfig(name);


        if (mod.getZoneManager().deleteZone(name)) {
            ctx.getSource().sendFeedback(() -> Text.literal("Deleted zone '" + name + "'")
                .formatted(Formatting.YELLOW), true);
            return 1;
        }
        ctx.getSource().sendError(Text.literal("Zone '" + name + "' not found"));
        return 0;
    }

    private static int listZones(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
        var zones = mod.getZoneManager().getAllZones();
        if (zones.isEmpty()) {
            ctx.getSource().sendFeedback(() -> Text.literal("No zones configured")
                .formatted(Formatting.GRAY), false);
            return 1;
        }

        ctx.getSource().sendFeedback(() -> Text.literal("Zones (" + zones.size() + "):")
            .formatted(Formatting.AQUA), false);
        for (VisualizationZone zone : zones) {
            ctx.getSource().sendFeedback(() -> Text.literal("  " + zone.toString())
                .formatted(Formatting.WHITE), false);
        }
        return 1;
    }

    private static int zoneInfo(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
        String name = StringArgumentType.getString(ctx, "name");
        VisualizationZone zone = mod.getZoneManager().getZone(name);
        if (zone == null) {
            ctx.getSource().sendError(Text.literal("Zone '" + name + "' not found"));
            return 0;
        }
        ctx.getSource().sendFeedback(() -> Text.literal(zone.toString())
            .formatted(Formatting.AQUA), false);
        boolean hasMap = mod.getMapRenderer().hasDisplay(name);
        boolean hasPool = mod.getVirtualRenderer().hasPool(name);
        boolean hasWall = mod.getBitmapToEntityBridge() != null && mod.getBitmapToEntityBridge().hasWall(name);
        String bitmapPattern = mod.getBitmapPatternManager() != null && mod.getBitmapPatternManager().isActive(name)
            ? mod.getBitmapPatternManager().getActivePatternId(name) : "none";
        ctx.getSource().sendFeedback(() -> Text.literal("  Map display: " + hasMap +
            " | Entity pool: " + hasPool + " | Wall: " + hasWall).formatted(Formatting.GRAY), false);
        ctx.getSource().sendFeedback(() -> Text.literal("  Bitmap pattern: " + bitmapPattern)
            .formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int setZoneSize(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
        String name = StringArgumentType.getString(ctx, "name");
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int y = IntegerArgumentType.getInteger(ctx, "y");
        int z = IntegerArgumentType.getInteger(ctx, "z");

        VisualizationZone zone = mod.getZoneManager().getZone(name);
        if (zone == null) {
            ctx.getSource().sendError(Text.literal("Zone '" + name + "' not found"));
            return 0;
        }

        zone.setSize(x, y, z);
        mod.getZoneManager().saveZones();
        ctx.getSource().sendFeedback(() -> Text.literal("Zone '" + name + "' size set to " +
            x + "x" + y + "x" + z).formatted(Formatting.GREEN), true);
        return 1;
    }

    private static int showStatus(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
        var s = ctx.getSource();
        s.sendFeedback(() -> Text.literal("AudioViz Status")
            .formatted(Formatting.AQUA, Formatting.BOLD), false);
        s.sendFeedback(() -> Text.literal("  Zones: " +
            mod.getZoneManager().getZoneCount()).formatted(Formatting.WHITE), false);
        s.sendFeedback(() -> Text.literal("  Stages: " +
            mod.getStageManager().getStageCount()).formatted(Formatting.WHITE), false);
        s.sendFeedback(() -> Text.literal("  Map displays: " +
            mod.getMapRenderer().getActiveZones().size()).formatted(Formatting.WHITE), false);
        s.sendFeedback(() -> Text.literal("  Entity pools: " +
            mod.getVirtualRenderer().getActiveZones().size()).formatted(Formatting.WHITE), false);
        int wallCount = mod.getBitmapToEntityBridge() != null
            ? mod.getBitmapToEntityBridge().getActiveWalls().size() : 0;
        s.sendFeedback(() -> Text.literal("  Entity walls: " + wallCount).formatted(Formatting.WHITE), false);
        int patternCount = mod.getBitmapPatternManager() != null
            ? mod.getBitmapPatternManager().getPatternIds().size() : 0;
        s.sendFeedback(() -> Text.literal("  Bitmap patterns: " + patternCount)
            .formatted(Formatting.WHITE), false);
        boolean wsConnected = mod.getWebSocketServer() != null;
        s.sendFeedback(() -> Text.literal("  WebSocket: " + (wsConnected ? "running" : "stopped"))
            .formatted(wsConnected ? Formatting.GREEN : Formatting.RED), false);
        boolean voiceAvail = mod.getVoicechatIntegration() != null;
        s.sendFeedback(() -> Text.literal("  Voice chat: " + (voiceAvail ? "available" : "not loaded"))
            .formatted(voiceAvail ? Formatting.GREEN : Formatting.GRAY), false);
        boolean particlesOn = mod.getParticleEffectManager() != null && mod.getParticleEffectManager().isEnabled();
        s.sendFeedback(() -> Text.literal("  Particles: " + (particlesOn ? "enabled" : "disabled"))
            .formatted(particlesOn ? Formatting.GREEN : Formatting.GRAY), false);
        boolean lightsActive = mod.getAmbientLightManager() != null;
        s.sendFeedback(() -> Text.literal("  Ambient lights: " + (lightsActive ? "ready" : "n/a"))
            .formatted(lightsActive ? Formatting.GREEN : Formatting.GRAY), false);
        boolean beatsActive = mod.getBeatEventManager() != null;
        s.sendFeedback(() -> Text.literal("  Beat effects: " + (beatsActive ? "ready" : "n/a"))
            .formatted(beatsActive ? Formatting.GREEN : Formatting.GRAY), false);
        return 1;
    }

    private static int setPattern(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
        String patternName = StringArgumentType.getString(ctx, "name");
        var patternManager = mod.getBitmapPatternManager();
        if (patternManager == null) {
            ctx.getSource().sendError(Text.literal("Pattern manager not initialized"));
            return 0;
        }

        if (!patternManager.getPatternIds().contains(patternName)) {
            ctx.getSource().sendError(Text.literal("Unknown pattern: " + patternName));
            ctx.getSource().sendFeedback(() -> Text.literal("Available: " +
                String.join(", ", patternManager.getPatternIds())).formatted(Formatting.GRAY), false);
            return 0;
        }

        // Set pattern on all active zones
        for (String zoneName : mod.getZoneManager().getZoneNames()) {
            patternManager.setPattern(zoneName, patternName);
        }
        ctx.getSource().sendFeedback(() -> Text.literal("Pattern set to '" + patternName + "'")
            .formatted(Formatting.GREEN), true);
        return 1;
    }

    private static int openMenu(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendError(Text.literal("Only players can open menus"));
            return 0;
        }
        mod.getMenuManager().openMenu(player,
            new MainMenu(player, mod.getMenuManager(), mod));
        return 1;
    }

    private static int stageList(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
        var stages = mod.getStageManager().getAllStages();
        if (stages.isEmpty()) {
            ctx.getSource().sendFeedback(() -> Text.literal("No stages configured")
                .formatted(Formatting.GRAY), false);
            return 1;
        }
        ctx.getSource().sendFeedback(() -> Text.literal("Stages (" + stages.size() + "):")
            .formatted(Formatting.GOLD), false);
        for (Stage stage : stages) {
            ctx.getSource().sendFeedback(() -> Text.literal("  " + stage.getName() +
                " [" + stage.getTemplateName() + "] " +
                (stage.isActive() ? "ACTIVE" : "inactive") +
                " (" + stage.getRoleToZone().size() + " zones)")
                .formatted(stage.isActive() ? Formatting.GREEN : Formatting.GRAY), false);
        }
        return 1;
    }

    private static int stageCreate(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendError(Text.literal("Only players can create stages"));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        String template = StringArgumentType.getString(ctx, "template");

        if (mod.getStageManager().getStage(name) != null) {
            ctx.getSource().sendError(Text.literal("Stage '" + name + "' already exists"));
            return 0;
        }

        Stage stage = mod.getStageManager().createStage(name, player.getBlockPos(),
            player.getEntityWorld().getRegistryKey().getValue().toString(), template);
        if (stage != null) {
            ctx.getSource().sendFeedback(() -> Text.literal("Created stage '" + name +
                "' with template '" + template + "'").formatted(Formatting.GREEN), true);
            return 1;
        }
        ctx.getSource().sendError(Text.literal("Failed to create stage"));
        return 0;
    }

    private static int stageDelete(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
        String name = StringArgumentType.getString(ctx, "name");
        if (mod.getStageManager().getStage(name) == null) {
            ctx.getSource().sendError(Text.literal("Stage '" + name + "' not found"));
            return 0;
        }
        mod.getStageManager().deleteStage(name);
        ctx.getSource().sendFeedback(() -> Text.literal("Deleted stage '" + name + "'")
            .formatted(Formatting.YELLOW), true);
        return 1;
    }

    private static int stageActivate(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
        String name = StringArgumentType.getString(ctx, "name");
        Stage stage = mod.getStageManager().getStage(name);
        if (stage == null) {
            ctx.getSource().sendError(Text.literal("Stage '" + name + "' not found"));
            return 0;
        }
        if (stage.isActive()) {
            mod.getStageManager().deactivateStage(stage);
            ctx.getSource().sendFeedback(() -> Text.literal("Deactivated stage '" + name + "'")
                .formatted(Formatting.YELLOW), true);
        } else {
            mod.getStageManager().activateStage(stage);
            ctx.getSource().sendFeedback(() -> Text.literal("Activated stage '" + name + "'")
                .formatted(Formatting.GREEN), true);
        }
        return 1;
    }

    private static int stagePlace(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendError(Text.literal("Only players can place zones"));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        Stage stage = mod.getStageManager().getStage(name);
        if (stage == null) {
            ctx.getSource().sendError(Text.literal("Stage '" + name + "' not found"));
            return 0;
        }
        if (stage.getActiveRoles().isEmpty()) {
            ctx.getSource().sendError(Text.literal("Stage has no zones to place"));
            return 0;
        }

        mod.getZonePlacementManager().startSession(player, stage, () ->
            mod.getMenuManager().openMenu(player,
                new com.audioviz.gui.menus.StageEditorMenu(player, mod.getMenuManager(), mod,
                    stage.getName(), () -> {})));
        return 1;
    }

    private static int cancelPlacement(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendError(Text.literal("Only players can cancel placement"));
            return 0;
        }
        if (!mod.getZonePlacementManager().hasActiveSession(player)) {
            ctx.getSource().sendError(Text.literal("No active placement session"));
            return 0;
        }
        var session = mod.getZonePlacementManager().getSession(player);
        session.stop(true);
        return 1;
    }

    // ==================== Zone Boundary Commands ====================

    private static int showZoneBoundary(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
        String name = StringArgumentType.getString(ctx, "name");
        var renderer = mod.getZoneBoundaryRenderer();
        if (renderer == null) {
            ctx.getSource().sendError(Text.literal("Boundary renderer not initialized"));
            return 0;
        }
        if (mod.getZoneManager().getZone(name) == null) {
            ctx.getSource().sendError(Text.literal("Zone '" + name + "' not found"));
            return 0;
        }
        boolean showing = renderer.toggle(name);
        ctx.getSource().sendFeedback(() -> Text.literal(showing ? "Showing" : "Hiding")
            .append(Text.literal(" boundaries for '" + name + "'"))
            .formatted(showing ? Formatting.GREEN : Formatting.YELLOW), false);
        return 1;
    }

    private static int showAllBoundaries(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
        var renderer = mod.getZoneBoundaryRenderer();
        if (renderer == null) return 0;
        renderer.showAll();
        int count = mod.getZoneManager().getZoneCount();
        ctx.getSource().sendFeedback(() -> Text.literal("Showing boundaries for " + count + " zones")
            .formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int showStageBoundaries(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
        String name = StringArgumentType.getString(ctx, "name");
        var renderer = mod.getZoneBoundaryRenderer();
        if (renderer == null) return 0;
        Stage stage = mod.getStageManager().getStage(name);
        if (stage == null) {
            ctx.getSource().sendError(Text.literal("Stage '" + name + "' not found"));
            return 0;
        }
        renderer.showStage(name);
        ctx.getSource().sendFeedback(() -> Text.literal("Showing boundaries for stage '" + name + "'")
            .formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int hideBoundaries(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
        var renderer = mod.getZoneBoundaryRenderer();
        if (renderer == null) return 0;
        renderer.hideAll();
        ctx.getSource().sendFeedback(() -> Text.literal("All zone boundaries hidden")
            .formatted(Formatting.YELLOW), false);
        return 1;
    }

    // ==================== Zone Selection Commands ====================

    private static int toggleSelectMode(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendError(Text.literal("Only players can use selection mode"));
            return 0;
        }
        var selManager = mod.getZoneSelectionManager();
        if (selManager == null) return 0;
        selManager.toggleSelectionMode(player);
        return 1;
    }

    private static int clearSelection(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendError(Text.literal("Only players can use selection mode"));
            return 0;
        }
        var selManager = mod.getZoneSelectionManager();
        if (selManager == null) return 0;
        selManager.clearSelection(player);
        return 1;
    }

    // ==================== Particle / Light / Beat Commands ====================

    private static int toggleParticles(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
        var pm = mod.getParticleEffectManager();
        if (pm == null) { ctx.getSource().sendError(Text.literal("Particle manager not initialized")); return 0; }
        boolean now = !pm.isEnabled();
        pm.setEnabled(now);
        ctx.getSource().sendFeedback(() -> Text.literal("Particle effects " + (now ? "enabled" : "disabled"))
            .formatted(now ? Formatting.GREEN : Formatting.YELLOW), true);
        return 1;
    }

    private static int setParticles(CommandContext<ServerCommandSource> ctx, AudioVizMod mod, boolean enabled) {
        var pm = mod.getParticleEffectManager();
        if (pm == null) { ctx.getSource().sendError(Text.literal("Particle manager not initialized")); return 0; }
        pm.setEnabled(enabled);
        ctx.getSource().sendFeedback(() -> Text.literal("Particle effects " + (enabled ? "enabled" : "disabled"))
            .formatted(enabled ? Formatting.GREEN : Formatting.YELLOW), true);
        return 1;
    }

    private static int toggleZoneParticles(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
        String zoneName = StringArgumentType.getString(ctx, "zone");
        var pm = mod.getParticleEffectManager();
        if (pm == null) { ctx.getSource().sendError(Text.literal("Particle manager not initialized")); return 0; }
        if (mod.getZoneManager().getZone(zoneName) == null) {
            ctx.getSource().sendError(Text.literal("Zone '" + zoneName + "' not found"));
            return 0;
        }
        if (pm.getEnabledEffects(zoneName.toLowerCase()).isEmpty()) {
            pm.enableDefaultEffects(zoneName.toLowerCase());
            ctx.getSource().sendFeedback(() -> Text.literal("Enabled default particle effects for '" + zoneName + "'")
                .formatted(Formatting.GREEN), true);
        } else {
            // Disable all effects for zone
            for (String effectId : new java.util.ArrayList<>(pm.getEnabledEffects(zoneName.toLowerCase()))) {
                pm.disableEffect(zoneName.toLowerCase(), effectId);
            }
            ctx.getSource().sendFeedback(() -> Text.literal("Disabled particle effects for '" + zoneName + "'")
                .formatted(Formatting.YELLOW), true);
        }
        return 1;
    }

    private static int toggleLights(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
        String zoneName = StringArgumentType.getString(ctx, "zone");
        var lm = mod.getAmbientLightManager();
        if (lm == null) { ctx.getSource().sendError(Text.literal("Light manager not initialized")); return 0; }
        VisualizationZone zone = mod.getZoneManager().getZone(zoneName);
        if (zone == null) {
            ctx.getSource().sendError(Text.literal("Zone '" + zoneName + "' not found"));
            return 0;
        }
        if (lm.hasZone(zoneName)) {
            lm.teardownZone(zoneName);
            ctx.getSource().sendFeedback(() -> Text.literal("Ambient lights removed from '" + zoneName + "'")
                .formatted(Formatting.YELLOW), true);
        } else {
            lm.initializeZone(zone);
            ctx.getSource().sendFeedback(() -> Text.literal("Ambient lights placed around '" + zoneName + "'")
                .formatted(Formatting.GREEN), true);
        }
        return 1;
    }

    private static int toggleBeats(CommandContext<ServerCommandSource> ctx, AudioVizMod mod) {
        String zoneName = StringArgumentType.getString(ctx, "zone");
        var bm = mod.getBeatEventManager();
        if (bm == null) { ctx.getSource().sendError(Text.literal("Beat manager not initialized")); return 0; }
        if (mod.getZoneManager().getZone(zoneName) == null) {
            ctx.getSource().sendError(Text.literal("Zone '" + zoneName + "' not found"));
            return 0;
        }
        if (bm.getZoneConfig(zoneName) != null) {
            bm.removeZoneConfig(zoneName);
            ctx.getSource().sendFeedback(() -> Text.literal("Beat effects removed from '" + zoneName + "'")
                .formatted(Formatting.YELLOW), true);
        } else {
            BeatEffectConfig config = new BeatEffectConfig.Builder()
                .addEffect(BeatType.BEAT, bm.get("particle_burst"))
                .build();
            bm.setZoneConfig(zoneName, config);
            ctx.getSource().sendFeedback(() -> Text.literal("Beat effects enabled for '" + zoneName + "' (particle burst)")
                .formatted(Formatting.GREEN), true);
        }
        return 1;
    }

    private static int showHelp(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource s = ctx.getSource();
        s.sendFeedback(() -> Text.literal("AudioViz Commands:").formatted(Formatting.AQUA, Formatting.BOLD), false);
        s.sendFeedback(() -> Text.literal("  /audioviz menu").formatted(Formatting.WHITE), false);
        s.sendFeedback(() -> Text.literal("  /audioviz zone create|delete|list|info|setsize").formatted(Formatting.WHITE), false);
        s.sendFeedback(() -> Text.literal("  /audioviz stage list|create|delete|activate|place").formatted(Formatting.WHITE), false);
        s.sendFeedback(() -> Text.literal("  /audioviz pattern <name>").formatted(Formatting.WHITE), false);
        s.sendFeedback(() -> Text.literal("  /audioviz show zone|all|stage|off").formatted(Formatting.WHITE)
            .append(Text.literal(" - Zone boundaries").formatted(Formatting.GRAY)), false);
        s.sendFeedback(() -> Text.literal("  /audioviz select").formatted(Formatting.WHITE)
            .append(Text.literal(" - Toggle look-at zone selection").formatted(Formatting.GRAY)), false);
        s.sendFeedback(() -> Text.literal("  /audioviz particles [on|off|<zone>]").formatted(Formatting.WHITE)
            .append(Text.literal(" - Toggle particle effects").formatted(Formatting.GRAY)), false);
        s.sendFeedback(() -> Text.literal("  /audioviz lights <zone>").formatted(Formatting.WHITE)
            .append(Text.literal(" - Toggle ambient lights").formatted(Formatting.GRAY)), false);
        s.sendFeedback(() -> Text.literal("  /audioviz beats <zone>").formatted(Formatting.WHITE)
            .append(Text.literal(" - Toggle beat effects").formatted(Formatting.GRAY)), false);
        s.sendFeedback(() -> Text.literal("  /audioviz cancel").formatted(Formatting.WHITE)
            .append(Text.literal(" - Cancel zone placement").formatted(Formatting.GRAY)), false);
        s.sendFeedback(() -> Text.literal("  /audioviz status").formatted(Formatting.WHITE), false);
        return 1;
    }
}
