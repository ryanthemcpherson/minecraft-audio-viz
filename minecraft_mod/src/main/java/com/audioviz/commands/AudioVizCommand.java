package com.audioviz.commands;

import com.audioviz.AudioVizMod;
import com.audioviz.zones.VisualizationZone;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
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
        dispatcher.register(CommandManager.literal("audioviz")
            .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
            .then(CommandManager.literal("zone")
                .then(CommandManager.literal("create")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .executes(ctx -> createZone(ctx, mod))))
                .then(CommandManager.literal("delete")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .executes(ctx -> deleteZone(ctx, mod))))
                .then(CommandManager.literal("list")
                    .executes(ctx -> listZones(ctx, mod)))
                .then(CommandManager.literal("info")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .executes(ctx -> zoneInfo(ctx, mod))))
                .then(CommandManager.literal("setsize")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .then(CommandManager.argument("x", IntegerArgumentType.integer(1, 100))
                            .then(CommandManager.argument("y", IntegerArgumentType.integer(1, 100))
                                .then(CommandManager.argument("z", IntegerArgumentType.integer(1, 100))
                                    .executes(ctx -> setZoneSize(ctx, mod)))))))
            )
            .then(CommandManager.literal("pattern")
                .then(CommandManager.argument("name", StringArgumentType.string())
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

        // Cleanup renderers
        mod.getMapRenderer().destroyDisplay(name);
        mod.getVirtualRenderer().destroyPool(name);

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
        ctx.getSource().sendFeedback(() -> Text.literal("AudioViz Status")
            .formatted(Formatting.AQUA, Formatting.BOLD), false);
        ctx.getSource().sendFeedback(() -> Text.literal("  Zones: " +
            mod.getZoneManager().getZoneCount()).formatted(Formatting.WHITE), false);
        ctx.getSource().sendFeedback(() -> Text.literal("  Map displays: " +
            mod.getMapRenderer().getActiveZones().size()).formatted(Formatting.WHITE), false);
        ctx.getSource().sendFeedback(() -> Text.literal("  Virtual entity pools: " +
            mod.getVirtualRenderer().getActiveZones().size()).formatted(Formatting.WHITE), false);
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

    private static int showHelp(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource s = ctx.getSource();
        s.sendFeedback(() -> Text.literal("AudioViz Commands:").formatted(Formatting.AQUA, Formatting.BOLD), false);
        s.sendFeedback(() -> Text.literal("  /audioviz zone create <name>").formatted(Formatting.WHITE), false);
        s.sendFeedback(() -> Text.literal("  /audioviz zone delete <name>").formatted(Formatting.WHITE), false);
        s.sendFeedback(() -> Text.literal("  /audioviz zone list").formatted(Formatting.WHITE), false);
        s.sendFeedback(() -> Text.literal("  /audioviz zone info <name>").formatted(Formatting.WHITE), false);
        s.sendFeedback(() -> Text.literal("  /audioviz zone setsize <name> <x> <y> <z>").formatted(Formatting.WHITE), false);
        s.sendFeedback(() -> Text.literal("  /audioviz pattern <name>").formatted(Formatting.WHITE), false);
        s.sendFeedback(() -> Text.literal("  /audioviz status").formatted(Formatting.WHITE), false);
        return 1;
    }
}
