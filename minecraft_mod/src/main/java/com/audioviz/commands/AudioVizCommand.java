package com.audioviz.commands;

import com.audioviz.AudioVizMod;
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
            )
            .then(CommandManager.literal("menu")
                .executes(ctx -> openMenu(ctx, mod)))
            .then(CommandManager.literal("pattern")
                .then(CommandManager.argument("name", StringArgumentType.string())
                    .suggests(patternNames)
                    .executes(ctx -> setPattern(ctx, mod))))
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

        // Cleanup all renderers and bitmap state
        mod.getMapRenderer().destroyDisplay(name);
        mod.getBitmapToEntityBridge().destroyWall(name);
        mod.getVirtualRenderer().destroyPool(name);
        var bpm = mod.getBitmapPatternManager();
        if (bpm != null) bpm.deactivateZone(name);

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
        ctx.getSource().sendFeedback(() -> Text.literal("  Map display: " +
            mod.getMapRenderer().hasDisplay(name)).formatted(Formatting.GRAY), false);
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
        s.sendFeedback(() -> Text.literal("  Entity walls: " +
            mod.getBitmapToEntityBridge().getActiveWalls().size()).formatted(Formatting.WHITE), false);
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

    private static int showHelp(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource s = ctx.getSource();
        s.sendFeedback(() -> Text.literal("AudioViz Commands:").formatted(Formatting.AQUA, Formatting.BOLD), false);
        s.sendFeedback(() -> Text.literal("  /audioviz menu").formatted(Formatting.WHITE), false);
        s.sendFeedback(() -> Text.literal("  /audioviz zone create|delete|list|info|setsize").formatted(Formatting.WHITE), false);
        s.sendFeedback(() -> Text.literal("  /audioviz stage list|create|delete|activate").formatted(Formatting.WHITE), false);
        s.sendFeedback(() -> Text.literal("  /audioviz pattern <name>").formatted(Formatting.WHITE), false);
        s.sendFeedback(() -> Text.literal("  /audioviz status").formatted(Formatting.WHITE), false);
        return 1;
    }
}
