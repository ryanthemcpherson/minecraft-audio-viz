package com.audioviz.commands;

import com.audioviz.AudioVizPlugin;
import com.audioviz.gui.menus.MainMenu;
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

    private void handlePoolCommand(CommandSender sender, String[] args) {
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
        sender.sendMessage(ChatColor.AQUA + "/audioviz pool init <zone> [count] [material]" + ChatColor.WHITE + " - Create entity pool");
        sender.sendMessage(ChatColor.AQUA + "/audioviz pool cleanup <zone>" + ChatColor.WHITE + " - Remove entities");
        sender.sendMessage(ChatColor.AQUA + "/audioviz test <zone> <animation>" + ChatColor.WHITE + " - Test animation");
        sender.sendMessage(ChatColor.AQUA + "/audioviz status" + ChatColor.WHITE + " - Show plugin status");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            completions.addAll(Arrays.asList("menu", "zone", "pool", "status", "test", "help"));
        } else if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "zone" -> completions.addAll(Arrays.asList("create", "delete", "list", "setsize", "setrotation", "info"));
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
                case "pool" -> completions.addAll(plugin.getZoneManager().getZoneNames());
                case "test" -> completions.addAll(Arrays.asList("wave", "pulse", "random"));
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
