package com.audioviz.commands;

import com.audioviz.AudioVizPlugin;
import com.audioviz.gui.menus.MainMenu;
import com.audioviz.stages.Stage;
import com.audioviz.stages.StageManager;
import com.audioviz.stages.StageTemplate;
import com.audioviz.stages.StageZoneConfig;
import com.audioviz.stages.StageZoneRole;
import com.audioviz.zones.VisualizationZone;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AudioVizCommand implements CommandExecutor, TabCompleter {

    private final AudioVizPlugin plugin;

    public AudioVizCommand(AudioVizPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "menu" -> handleMenuCommand(sender);
            case "zone" -> handleZoneCommand(sender, Arrays.copyOfRange(args, 1, args.length));
            case "stage" -> handleStageCommand(sender, Arrays.copyOfRange(args, 1, args.length));
            case "pool" -> handlePoolCommand(sender, Arrays.copyOfRange(args, 1, args.length));
            case "status" -> handleStatusCommand(sender);
            case "test" -> handleTestCommand(sender, Arrays.copyOfRange(args, 1, args.length));
            case "help" -> sendHelp(sender);
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown command. Use /audioviz help");
            }
        }

        return true;
    }

    private void handleMenuCommand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can open the menu.");
            return;
        }

        if (!player.hasPermission("audioviz.menu") && !player.hasPermission("audioviz.admin")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to open the AudioViz menu.");
            return;
        }

        plugin.getMenuManager().openMenu(player, new MainMenu(plugin, plugin.getMenuManager()));
    }

    private void handleZoneCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("audioviz.zone") && !sender.hasPermission("audioviz.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to manage zones.");
            return;
        }
        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "Usage: /audioviz zone <create|delete|list|setsize|info>");
            return;
        }

        String action = args[0].toLowerCase();

        switch (action) {
            case "create" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "Only players can create zones.");
                    return;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /audioviz zone create <name>");
                    return;
                }
                String zoneName = args[1];
                if (plugin.getZoneManager().zoneExists(zoneName)) {
                    sender.sendMessage(ChatColor.RED + "Zone '" + zoneName + "' already exists!");
                    return;
                }
                VisualizationZone zone = plugin.getZoneManager().createZone(zoneName, player.getLocation());
                sender.sendMessage(ChatColor.GREEN + "Created zone '" + zoneName + "' at your location!");
                sender.sendMessage(ChatColor.GRAY + zone.toString());
            }

            case "delete" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /audioviz zone delete <name>");
                    return;
                }
                String zoneName = args[1];
                if (plugin.getZoneManager().deleteZone(zoneName)) {
                    sender.sendMessage(ChatColor.GREEN + "Deleted zone '" + zoneName + "'");
                } else {
                    sender.sendMessage(ChatColor.RED + "Zone '" + zoneName + "' not found.");
                }
            }

            case "list" -> {
                var zones = plugin.getZoneManager().getAllZones();
                if (zones.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "No zones defined.");
                    return;
                }
                sender.sendMessage(ChatColor.GOLD + "=== Visualization Zones ===");
                for (VisualizationZone zone : zones) {
                    int entityCount = plugin.getEntityPoolManager().getEntityCount(zone.getName());
                    sender.sendMessage(ChatColor.AQUA + "- " + zone.getName() +
                        ChatColor.GRAY + " [" + entityCount + " entities]");
                    sender.sendMessage(ChatColor.GRAY + "  " + zone.toString());
                }
            }

            case "setsize" -> {
                if (args.length < 5) {
                    sender.sendMessage(ChatColor.RED + "Usage: /audioviz zone setsize <name> <x> <y> <z>");
                    return;
                }
                String zoneName = args[1];
                VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
                if (zone == null) {
                    sender.sendMessage(ChatColor.RED + "Zone '" + zoneName + "' not found.");
                    return;
                }
                try {
                    double x = Double.parseDouble(args[2]);
                    double y = Double.parseDouble(args[3]);
                    double z = Double.parseDouble(args[4]);
                    zone.setSize(x, y, z);
                    plugin.getZoneManager().saveZones();
                    sender.sendMessage(ChatColor.GREEN + "Zone size set to " + x + "x" + y + "x" + z);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid numbers for size.");
                }
            }

            case "setrotation" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /audioviz zone setrotation <name> <degrees>");
                    return;
                }
                String zoneName = args[1];
                VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
                if (zone == null) {
                    sender.sendMessage(ChatColor.RED + "Zone '" + zoneName + "' not found.");
                    return;
                }
                try {
                    float rotation = Float.parseFloat(args[2]);
                    zone.setRotation(rotation);
                    plugin.getZoneManager().saveZones();
                    sender.sendMessage(ChatColor.GREEN + "Zone rotation set to " + rotation + " degrees");
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid rotation value.");
                }
            }

            case "info" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /audioviz zone info <name>");
                    return;
                }
                String zoneName = args[1];
                VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
                if (zone == null) {
                    sender.sendMessage(ChatColor.RED + "Zone '" + zoneName + "' not found.");
                    return;
                }
                sender.sendMessage(ChatColor.GOLD + "=== Zone: " + zone.getName() + " ===");
                sender.sendMessage(ChatColor.WHITE + "ID: " + ChatColor.GRAY + zone.getId());
                sender.sendMessage(ChatColor.WHITE + "World: " + ChatColor.GRAY + zone.getWorld().getName());
                sender.sendMessage(ChatColor.WHITE + "Origin: " + ChatColor.GRAY + formatLocation(zone.getOrigin()));
                sender.sendMessage(ChatColor.WHITE + "Size: " + ChatColor.GRAY + formatVector(zone.getSize()));
                sender.sendMessage(ChatColor.WHITE + "Rotation: " + ChatColor.GRAY + zone.getRotation() + " degrees");
                sender.sendMessage(ChatColor.WHITE + "Entities: " + ChatColor.GRAY +
                    plugin.getEntityPoolManager().getEntityCount(zoneName));
            }

            default -> sender.sendMessage(ChatColor.RED + "Unknown zone action. Use create, delete, list, setsize, setrotation, or info");
        }
    }

    private void handleStageCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("audioviz.control") && !sender.hasPermission("audioviz.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to manage stages.");
            return;
        }
        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "Usage: /audioviz stage <create|delete|list|info|activate|deactivate|move|rotate>");
            return;
        }

        StageManager stageManager = plugin.getStageManager();
        String action = args[0].toLowerCase();

        switch (action) {
            case "create" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "Only players can create stages.");
                    return;
                }
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /audioviz stage create <name> <template>");
                    sender.sendMessage(ChatColor.GRAY + "Templates: " + String.join(", ", stageManager.getTemplateNames()));
                    return;
                }
                String stageName = args[1];
                String templateName = args[2];

                if (stageManager.stageExists(stageName)) {
                    sender.sendMessage(ChatColor.RED + "Stage '" + stageName + "' already exists!");
                    return;
                }

                if (stageManager.getTemplate(templateName) == null) {
                    sender.sendMessage(ChatColor.RED + "Unknown template '" + templateName + "'.");
                    sender.sendMessage(ChatColor.GRAY + "Available: " + String.join(", ", stageManager.getTemplateNames()));
                    return;
                }

                Stage stage = stageManager.createStage(stageName, player.getLocation(), templateName);
                if (stage != null) {
                    sender.sendMessage(ChatColor.GREEN + "Created stage '" + stageName + "' from template '" + templateName + "'!");
                    sender.sendMessage(ChatColor.GRAY + "Zones: " + stage.getRoleToZone().size() +
                        " | Use '/audioviz stage activate " + stageName + "' to start");
                } else {
                    sender.sendMessage(ChatColor.RED + "Failed to create stage.");
                }
            }

            case "delete" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /audioviz stage delete <name>");
                    return;
                }
                if (stageManager.deleteStage(args[1])) {
                    sender.sendMessage(ChatColor.GREEN + "Deleted stage '" + args[1] + "' and all its zones.");
                } else {
                    sender.sendMessage(ChatColor.RED + "Stage '" + args[1] + "' not found.");
                }
            }

            case "list" -> {
                var stages = stageManager.getAllStages();
                if (stages.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "No stages defined.");
                    sender.sendMessage(ChatColor.GRAY + "Create one with: /audioviz stage create <name> <template>");
                    return;
                }
                sender.sendMessage(ChatColor.GOLD + "=== Stages ===");
                for (Stage stage : stages) {
                    String status = stage.isActive() ? ChatColor.GREEN + "ACTIVE" : ChatColor.GRAY + "inactive";
                    String pin = stage.isPinned() ? ChatColor.GOLD + "\u2605 " : "";
                    String tag = stage.getTag().isEmpty() ? "" : ChatColor.GRAY + " #" + stage.getTag();
                    sender.sendMessage(pin + ChatColor.AQUA + "- " + stage.getName() +
                        ChatColor.GRAY + " [" + stage.getTemplateName() + ", " +
                        stage.getRoleToZone().size() + " zones] " + status + tag);
                }
            }

            case "info" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /audioviz stage info <name>");
                    return;
                }
                Stage stage = stageManager.getStage(args[1]);
                if (stage == null) {
                    sender.sendMessage(ChatColor.RED + "Stage '" + args[1] + "' not found.");
                    return;
                }
                sender.sendMessage(ChatColor.GOLD + "=== Stage: " + stage.getName() + " ===");
                sender.sendMessage(ChatColor.WHITE + "ID: " + ChatColor.GRAY + stage.getId());
                sender.sendMessage(ChatColor.WHITE + "Template: " + ChatColor.GRAY + stage.getTemplateName());
                sender.sendMessage(ChatColor.WHITE + "Active: " + (stage.isActive() ? ChatColor.GREEN + "Yes" : ChatColor.RED + "No"));
                sender.sendMessage(ChatColor.WHITE + "Pinned: " + (stage.isPinned() ? ChatColor.GOLD + "\u2605 Yes" : ChatColor.GRAY + "No"));
                if (!stage.getTag().isEmpty()) {
                    sender.sendMessage(ChatColor.WHITE + "Tag: " + ChatColor.GRAY + stage.getTag());
                }
                sender.sendMessage(ChatColor.WHITE + "Anchor: " + ChatColor.GRAY + formatLocation(stage.getAnchor()));
                sender.sendMessage(ChatColor.WHITE + "Rotation: " + ChatColor.GRAY + stage.getRotation() + "\u00B0");
                sender.sendMessage(ChatColor.WHITE + "Zones:");
                for (Map.Entry<StageZoneRole, String> entry : stage.getRoleToZone().entrySet()) {
                    StageZoneRole role = entry.getKey();
                    String zoneName = entry.getValue();
                    StageZoneConfig config = stage.getZoneConfigs().get(role);
                    int entities = plugin.getEntityPoolManager().getEntityCount(zoneName);
                    sender.sendMessage(ChatColor.AQUA + "  " + role.getDisplayName() +
                        ChatColor.GRAY + " -> " + zoneName +
                        " [" + entities + " entities, pattern=" +
                        (config != null ? config.getPattern() : "?") + "]");
                }
            }

            case "activate" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /audioviz stage activate <name>");
                    return;
                }
                Stage stage = stageManager.getStage(args[1]);
                if (stage == null) {
                    sender.sendMessage(ChatColor.RED + "Stage '" + args[1] + "' not found.");
                    return;
                }
                stageManager.activateStage(stage);
                sender.sendMessage(ChatColor.GREEN + "Stage '" + stage.getName() + "' activated! " +
                    stage.getTotalEntityCount() + " entities spawned.");
            }

            case "deactivate" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /audioviz stage deactivate <name>");
                    return;
                }
                Stage stage = stageManager.getStage(args[1]);
                if (stage == null) {
                    sender.sendMessage(ChatColor.RED + "Stage '" + args[1] + "' not found.");
                    return;
                }
                stageManager.deactivateStage(stage);
                sender.sendMessage(ChatColor.GREEN + "Stage '" + stage.getName() + "' deactivated.");
            }

            case "move" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "Only players can move stages.");
                    return;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /audioviz stage move <name>");
                    return;
                }
                Stage stage = stageManager.getStage(args[1]);
                if (stage == null) {
                    sender.sendMessage(ChatColor.RED + "Stage '" + args[1] + "' not found.");
                    return;
                }
                stageManager.moveStage(stage, player.getLocation());
                sender.sendMessage(ChatColor.GREEN + "Stage '" + stage.getName() + "' moved to your location.");
            }

            case "rotate" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /audioviz stage rotate <name> <degrees>");
                    return;
                }
                Stage stage = stageManager.getStage(args[1]);
                if (stage == null) {
                    sender.sendMessage(ChatColor.RED + "Stage '" + args[1] + "' not found.");
                    return;
                }
                try {
                    float degrees = Float.parseFloat(args[2]);
                    stageManager.rotateStage(stage, degrees);
                    sender.sendMessage(ChatColor.GREEN + "Stage rotated to " + degrees + "\u00B0");
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid rotation value.");
                }
            }

            case "pin" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /audioviz stage pin <name>");
                    return;
                }
                Stage stage = stageManager.getStage(args[1]);
                if (stage == null) {
                    sender.sendMessage(ChatColor.RED + "Stage '" + args[1] + "' not found.");
                    return;
                }
                stage.setPinned(!stage.isPinned());
                stageManager.saveStages();
                sender.sendMessage(stage.isPinned()
                    ? ChatColor.GOLD + "\u2605 " + ChatColor.GREEN + "Stage '" + stage.getName() + "' pinned."
                    : ChatColor.GRAY + "Stage '" + stage.getName() + "' unpinned.");
            }

            case "tag" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /audioviz stage tag <name> <tag|clear>");
                    return;
                }
                Stage stage = stageManager.getStage(args[1]);
                if (stage == null) {
                    sender.sendMessage(ChatColor.RED + "Stage '" + args[1] + "' not found.");
                    return;
                }
                if (args[2].equalsIgnoreCase("clear")) {
                    stage.setTag("");
                    sender.sendMessage(ChatColor.GREEN + "Tag cleared from stage '" + stage.getName() + "'.");
                } else {
                    stage.setTag(args[2]);
                    sender.sendMessage(ChatColor.GREEN + "Stage '" + stage.getName() + "' tagged as '" + stage.getTag() + "'.");
                }
                stageManager.saveStages();
            }

            case "search" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /audioviz stage search <query>");
                    return;
                }
                String query = args[1].toLowerCase();
                var matches = stageManager.getAllStages().stream()
                    .filter(s -> s.getName().toLowerCase().contains(query)
                        || s.getTemplateName().toLowerCase().contains(query)
                        || s.getTag().toLowerCase().contains(query))
                    .toList();
                if (matches.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "No stages matching '" + args[1] + "'.");
                } else {
                    sender.sendMessage(ChatColor.GOLD + "=== Search Results (" + matches.size() + ") ===");
                    for (Stage s : matches) {
                        String status = s.isActive() ? ChatColor.GREEN + "ACTIVE" : ChatColor.GRAY + "inactive";
                        String pin = s.isPinned() ? ChatColor.GOLD + "\u2605 " : "";
                        String tag = s.getTag().isEmpty() ? "" : ChatColor.GRAY + " #" + s.getTag();
                        sender.sendMessage(pin + ChatColor.AQUA + s.getName() +
                            ChatColor.GRAY + " [" + s.getTemplateName() + "] " + status + tag);
                    }
                }
            }

            default -> sender.sendMessage(ChatColor.RED + "Unknown stage action. Use create, delete, list, info, activate, deactivate, move, rotate, pin, tag, or search");
        }
    }

    private void handlePoolCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("audioviz.control") && !sender.hasPermission("audioviz.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to manage entity pools.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /audioviz pool <init|cleanup> <zone> [count] [material]");
            return;
        }

        String action = args[0].toLowerCase();
        String zoneName = args[1];

        switch (action) {
            case "init" -> {
                int count = args.length > 2 ? Integer.parseInt(args[2]) : 16;
                Material material = args.length > 3 ? Material.matchMaterial(args[3]) : Material.GLOWSTONE;
                if (material == null) material = Material.GLOWSTONE;

                if (!plugin.getZoneManager().zoneExists(zoneName)) {
                    sender.sendMessage(ChatColor.RED + "Zone '" + zoneName + "' not found.");
                    return;
                }

                plugin.getEntityPoolManager().initializeBlockPool(zoneName, count, material);
                sender.sendMessage(ChatColor.GREEN + "Initializing " + count + " " + material.name() +
                    " block displays in zone '" + zoneName + "'");
            }

            case "cleanup" -> {
                plugin.getEntityPoolManager().cleanupZone(zoneName);
                sender.sendMessage(ChatColor.GREEN + "Cleaned up entities in zone '" + zoneName + "'");
            }

            default -> sender.sendMessage(ChatColor.RED + "Unknown pool action. Use init or cleanup");
        }
    }

    private void handleStatusCommand(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== AudioViz Status ===");

        // Zone count
        int zoneCount = plugin.getZoneManager().getAllZones().size();
        sender.sendMessage(ChatColor.WHITE + "Zones: " + ChatColor.AQUA + zoneCount);

        // Total entities
        int totalEntities = 0;
        for (VisualizationZone zone : plugin.getZoneManager().getAllZones()) {
            totalEntities += plugin.getEntityPoolManager().getEntityCount(zone.getName());
        }
        sender.sendMessage(ChatColor.WHITE + "Total Entities: " + ChatColor.AQUA + totalEntities);

        // WebSocket status
        var ws = plugin.getWebSocketServer();
        if (ws != null) {
            sender.sendMessage(ChatColor.WHITE + "WebSocket: " + ChatColor.GREEN + "Running on port " +
                plugin.getConfig().getInt("websocket.port", 8765));
            sender.sendMessage(ChatColor.WHITE + "Connected Clients: " + ChatColor.AQUA + ws.getConnectionCount());
        } else {
            sender.sendMessage(ChatColor.WHITE + "WebSocket: " + ChatColor.RED + "Not running");
        }
    }

    private void handleTestCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /audioviz test <zone> <animation>");
            sender.sendMessage(ChatColor.GRAY + "Animations: wave, pulse, random");
            return;
        }

        String zoneName = args[0];
        String animation = args[1].toLowerCase();

        if (!plugin.getZoneManager().zoneExists(zoneName)) {
            sender.sendMessage(ChatColor.RED + "Zone '" + zoneName + "' not found.");
            return;
        }

        int entityCount = plugin.getEntityPoolManager().getEntityCount(zoneName);
        if (entityCount == 0) {
            sender.sendMessage(ChatColor.RED + "No entities in zone. Use /audioviz pool init " + zoneName);
            return;
        }

        sender.sendMessage(ChatColor.GREEN + "Running '" + animation + "' animation on " + zoneName + "...");

        // Run a simple test animation
        runTestAnimation(zoneName, animation, entityCount);
    }

    private void runTestAnimation(String zoneName, String animation, int entityCount) {
        VisualizationZone zone = plugin.getZoneManager().getZone(zoneName);
        if (zone == null) return;

        // Schedule animation updates
        for (int frame = 0; frame < 40; frame++) { // 2 seconds at 20 tps
            final int f = frame;

            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                for (int i = 0; i < entityCount; i++) {
                    String entityId = "block_" + i;
                    float value;

                    switch (animation) {
                        case "wave" -> {
                            // Wave pattern
                            value = (float) Math.sin((f * 0.2) + (i * 0.5)) * 0.5f + 0.5f;
                        }
                        case "pulse" -> {
                            // Pulse all together
                            value = (float) Math.sin(f * 0.3) * 0.5f + 0.5f;
                        }
                        case "random" -> {
                            // Random heights
                            value = (float) Math.random();
                        }
                        default -> value = 0.5f;
                    }

                    // Calculate position in grid
                    int gridSize = (int) Math.ceil(Math.sqrt(entityCount));
                    int gridX = i % gridSize;
                    int gridZ = i / gridSize;

                    // Convert to world position using zone
                    double localX = (double) gridX / gridSize;
                    double localZ = (double) gridZ / gridSize;
                    double localY = value * 0.5; // Height based on animation value

                    org.bukkit.Location worldLoc = zone.localToWorld(localX, localY, localZ);

                    // Update entity position
                    org.bukkit.entity.Entity entity = plugin.getEntityPoolManager().getEntity(zoneName, entityId);
                    if (entity != null) {
                        entity.teleport(worldLoc);
                    }
                }
            }, frame);
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== AudioViz Commands ===");
        sender.sendMessage(ChatColor.AQUA + "/audioviz menu" + ChatColor.WHITE + " - Open the control menu");
        sender.sendMessage(ChatColor.AQUA + "/audioviz zone create <name>" + ChatColor.WHITE + " - Create zone at your location");
        sender.sendMessage(ChatColor.AQUA + "/audioviz zone delete <name>" + ChatColor.WHITE + " - Delete a zone");
        sender.sendMessage(ChatColor.AQUA + "/audioviz zone list" + ChatColor.WHITE + " - List all zones");
        sender.sendMessage(ChatColor.AQUA + "/audioviz zone setsize <name> <x> <y> <z>" + ChatColor.WHITE + " - Set zone dimensions");
        sender.sendMessage(ChatColor.AQUA + "/audioviz zone info <name>" + ChatColor.WHITE + " - Show zone details");
        sender.sendMessage(ChatColor.AQUA + "/audioviz stage create <name> <template>" + ChatColor.WHITE + " - Create stage from template");
        sender.sendMessage(ChatColor.AQUA + "/audioviz stage delete <name>" + ChatColor.WHITE + " - Delete a stage");
        sender.sendMessage(ChatColor.AQUA + "/audioviz stage list" + ChatColor.WHITE + " - List all stages");
        sender.sendMessage(ChatColor.AQUA + "/audioviz stage info <name>" + ChatColor.WHITE + " - Show stage details");
        sender.sendMessage(ChatColor.AQUA + "/audioviz stage activate <name>" + ChatColor.WHITE + " - Activate a stage");
        sender.sendMessage(ChatColor.AQUA + "/audioviz stage deactivate <name>" + ChatColor.WHITE + " - Deactivate a stage");
        sender.sendMessage(ChatColor.AQUA + "/audioviz stage pin <name>" + ChatColor.WHITE + " - Toggle pin/favorite");
        sender.sendMessage(ChatColor.AQUA + "/audioviz stage tag <name> <tag|clear>" + ChatColor.WHITE + " - Set/clear category tag");
        sender.sendMessage(ChatColor.AQUA + "/audioviz stage search <query>" + ChatColor.WHITE + " - Search stages");
        sender.sendMessage(ChatColor.AQUA + "/audioviz pool init <zone> [count] [material]" + ChatColor.WHITE + " - Create entity pool");
        sender.sendMessage(ChatColor.AQUA + "/audioviz pool cleanup <zone>" + ChatColor.WHITE + " - Remove entities");
        sender.sendMessage(ChatColor.AQUA + "/audioviz test <zone> <animation>" + ChatColor.WHITE + " - Test animation");
        sender.sendMessage(ChatColor.AQUA + "/audioviz status" + ChatColor.WHITE + " - Show plugin status");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            completions.addAll(Arrays.asList("menu", "zone", "stage", "pool", "status", "test", "help"));
        } else if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "zone" -> completions.addAll(Arrays.asList("create", "delete", "list", "setsize", "setrotation", "info"));
                case "stage" -> completions.addAll(Arrays.asList("create", "delete", "list", "info", "activate", "deactivate", "move", "rotate", "pin", "tag", "search"));
                case "pool" -> completions.addAll(Arrays.asList("init", "cleanup"));
                case "test" -> completions.addAll(plugin.getZoneManager().getZoneNames());
            }
        } else if (args.length == 3) {
            switch (args[0].toLowerCase()) {
                case "zone" -> {
                    if (!args[1].equalsIgnoreCase("create") && !args[1].equalsIgnoreCase("list")) {
                        completions.addAll(plugin.getZoneManager().getZoneNames());
                    }
                }
                case "stage" -> {
                    if (args[1].equalsIgnoreCase("create") || args[1].equalsIgnoreCase("search")) {
                        // Stage name/query - no auto-complete
                    } else if (!args[1].equalsIgnoreCase("list")) {
                        completions.addAll(plugin.getStageManager().getStageNames());
                    }
                }
                case "pool" -> completions.addAll(plugin.getZoneManager().getZoneNames());
                case "test" -> completions.addAll(Arrays.asList("wave", "pulse", "random"));
            }
        } else if (args.length == 4) {
            if (args[0].equalsIgnoreCase("stage") && args[1].equalsIgnoreCase("create")) {
                // Template names
                completions.addAll(plugin.getStageManager().getTemplateNames());
            } else if (args[0].equalsIgnoreCase("stage") && args[1].equalsIgnoreCase("tag")) {
                // Tag suggestions: existing tags + common suggestions + "clear"
                completions.add("clear");
                completions.addAll(plugin.getStageManager().getAllTags());
                completions.addAll(Arrays.asList("venue", "event", "test", "archive"));
            }
        } else if (args.length == 5 && args[0].equalsIgnoreCase("pool") && args[1].equalsIgnoreCase("init")) {
            // Material suggestions
            completions.addAll(Arrays.asList("GLOWSTONE", "SEA_LANTERN", "REDSTONE_BLOCK", "DIAMOND_BLOCK", "GOLD_BLOCK"));
        }

        String lastArg = args[args.length - 1].toLowerCase();
        return completions.stream()
            .filter(s -> s.toLowerCase().startsWith(lastArg))
            .collect(Collectors.toList());
    }

    private String formatLocation(org.bukkit.Location loc) {
        return String.format("%.1f, %.1f, %.1f", loc.getX(), loc.getY(), loc.getZ());
    }

    private String formatVector(org.bukkit.util.Vector vec) {
        return String.format("%.1f x %.1f x %.1f", vec.getX(), vec.getY(), vec.getZ());
    }
}
