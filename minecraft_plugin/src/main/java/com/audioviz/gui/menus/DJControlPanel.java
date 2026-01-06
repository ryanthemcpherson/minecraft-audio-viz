package com.audioviz.gui.menus;

import com.audioviz.AudioVizPlugin;
import com.audioviz.effects.BeatEffect;
import com.audioviz.effects.BeatEffectConfig;
import com.audioviz.effects.BeatEventManager;
import com.audioviz.effects.BeatType;
import com.audioviz.gui.Menu;
import com.audioviz.gui.MenuManager;
import com.audioviz.gui.builder.ItemBuilder;
import com.audioviz.zones.VisualizationZone;
import org.bukkit.ChatColor;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Live DJ control panel for adjusting visualization parameters in real-time.
 * Provides sliders for intensity, effect toggles, and zone selection.
 */
public class DJControlPanel implements Menu {

    private final AudioVizPlugin plugin;
    private final MenuManager menuManager;

    // Per-player settings (in-memory for now)
    private static final Map<UUID, DJSettings> playerSettings = new HashMap<>();

    // Slot positions
    private static final int SLOT_ZONE_SELECTOR = 4;
    private static final int[] INTENSITY_SLOTS = {19, 20, 21, 22, 23, 24, 25};
    private static final int SLOT_PARTICLES = 37;
    private static final int SLOT_SCREEN_SHAKE = 38;
    private static final int SLOT_LIGHTNING = 39;
    private static final int SLOT_EXPLOSION = 40;
    private static final int SLOT_BEAT_SENSITIVITY = 42;
    private static final int SLOT_PRESET_1 = 46;
    private static final int SLOT_PRESET_2 = 47;
    private static final int SLOT_PRESET_3 = 48;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_SAVE = 53;

    public DJControlPanel(AudioVizPlugin plugin, MenuManager menuManager) {
        this.plugin = plugin;
        this.menuManager = menuManager;
    }

    @Override
    public String getTitle() {
        return "\u00A7d\u00A7lDJ Control Panel";
    }

    @Override
    public int getSize() {
        return 54; // 6 rows
    }

    @Override
    public void build(Inventory inventory, Player viewer) {
        inventory.clear();

        DJSettings settings = getSettings(viewer);

        // Fill background
        ItemStack filler = ItemBuilder.glassPane(DyeColor.BLACK);
        for (int i = 0; i < 54; i++) {
            inventory.setItem(i, filler);
        }

        // Header decoration
        ItemStack purple = ItemBuilder.glassPane(DyeColor.PURPLE);
        for (int i = 0; i < 9; i++) {
            inventory.setItem(i, purple);
        }

        // Zone selector
        List<String> zoneNames = new ArrayList<>(plugin.getZoneManager().getZoneNames());
        String currentZone = settings.selectedZone;
        if (currentZone == null && !zoneNames.isEmpty()) {
            currentZone = zoneNames.get(0);
            settings.selectedZone = currentZone;
        }

        inventory.setItem(SLOT_ZONE_SELECTOR, new ItemBuilder(Material.BEACON)
            .name("&6Selected Zone")
            .lore(
                "&7Current: &e" + (currentZone != null ? currentZone : "None"),
                "",
                "&7Zones available: &f" + zoneNames.size(),
                "",
                "&eClick to cycle zones",
                "&eRight-click to go back"
            )
            .glow()
            .build());

        // Intensity label
        inventory.setItem(10, new ItemBuilder(Material.BLAZE_POWDER)
            .name("&6Intensity")
            .lore(
                "&7Controls visualization strength",
                "&7Current: &f" + settings.intensity + "/7"
            )
            .build());

        // Intensity slider (7 levels)
        for (int i = 0; i < 7; i++) {
            int level = i + 1;
            boolean active = level <= settings.intensity;
            Material mat = active ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;

            inventory.setItem(INTENSITY_SLOTS[i], new ItemBuilder(mat)
                .name((active ? "&a" : "&7") + "Level " + level)
                .lore("&eClick to set")
                .build());
        }

        // Effect toggles header
        inventory.setItem(28, new ItemBuilder(Material.FIREWORK_ROCKET)
            .name("&dBeat Effects")
            .lore("&7Toggle reactive effects")
            .build());

        // Effect toggles
        inventory.setItem(SLOT_PARTICLES, ItemBuilder.toggle(
            "&bParticle Burst",
            settings.particlesEnabled,
            "&7Spawn particles on beats"
        ));

        inventory.setItem(SLOT_SCREEN_SHAKE, ItemBuilder.toggle(
            "&eScreen Shake",
            settings.screenShakeEnabled,
            "&7Camera shake on bass"
        ));

        inventory.setItem(SLOT_LIGHTNING, ItemBuilder.toggle(
            "&fLightning Strike",
            settings.lightningEnabled,
            "&7Visual lightning on drops"
        ));

        inventory.setItem(SLOT_EXPLOSION, ItemBuilder.toggle(
            "&cExplosion Effect",
            settings.explosionEnabled,
            "&7Explosion particles on drops"
        ));

        // Beat sensitivity
        inventory.setItem(SLOT_BEAT_SENSITIVITY, ItemBuilder.slider(
            "&6Beat Sensitivity",
            settings.beatSensitivity,
            10,
            "&7How sensitive to audio peaks"
        ));

        // Presets
        inventory.setItem(SLOT_PRESET_1, new ItemBuilder(Material.MUSIC_DISC_BLOCKS)
            .name("&aPreset: Chill")
            .lore(
                "&7Low intensity, particles only",
                "",
                "&eClick to apply"
            )
            .build());

        inventory.setItem(SLOT_PRESET_2, new ItemBuilder(Material.MUSIC_DISC_CAT)
            .name("&ePreset: Party")
            .lore(
                "&7Medium intensity, all effects",
                "",
                "&eClick to apply"
            )
            .build());

        inventory.setItem(SLOT_PRESET_3, new ItemBuilder(Material.MUSIC_DISC_PIGSTEP)
            .name("&cPreset: Rave")
            .lore(
                "&7Max intensity, all effects",
                "&7High sensitivity",
                "",
                "&eClick to apply"
            )
            .glow()
            .build());

        // Back button
        inventory.setItem(SLOT_BACK, ItemBuilder.backButton());

        // Save settings
        inventory.setItem(SLOT_SAVE, new ItemBuilder(Material.WRITABLE_BOOK)
            .name("&aSave Settings")
            .lore(
                "&7Save current configuration",
                "&7to the zone",
                "",
                "&eClick to save"
            )
            .build());
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        DJSettings settings = getSettings(player);

        // Zone selector
        if (slot == SLOT_ZONE_SELECTOR) {
            List<String> zoneNames = new ArrayList<>(plugin.getZoneManager().getZoneNames());
            if (!zoneNames.isEmpty()) {
                int currentIndex = zoneNames.indexOf(settings.selectedZone);
                int newIndex;
                if (click.isRightClick()) {
                    newIndex = (currentIndex - 1 + zoneNames.size()) % zoneNames.size();
                } else {
                    newIndex = (currentIndex + 1) % zoneNames.size();
                }
                settings.selectedZone = zoneNames.get(newIndex);
                playSound(player, Sound.UI_BUTTON_CLICK);
                menuManager.refreshMenu(player);
            }
            return;
        }

        // Intensity slider
        for (int i = 0; i < INTENSITY_SLOTS.length; i++) {
            if (slot == INTENSITY_SLOTS[i]) {
                settings.intensity = i + 1;
                playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING);
                menuManager.refreshMenu(player);
                return;
            }
        }

        // Effect toggles
        switch (slot) {
            case SLOT_PARTICLES -> {
                settings.particlesEnabled = !settings.particlesEnabled;
                playToggleSound(player, settings.particlesEnabled);
                menuManager.refreshMenu(player);
            }
            case SLOT_SCREEN_SHAKE -> {
                settings.screenShakeEnabled = !settings.screenShakeEnabled;
                playToggleSound(player, settings.screenShakeEnabled);
                menuManager.refreshMenu(player);
            }
            case SLOT_LIGHTNING -> {
                settings.lightningEnabled = !settings.lightningEnabled;
                playToggleSound(player, settings.lightningEnabled);
                menuManager.refreshMenu(player);
            }
            case SLOT_EXPLOSION -> {
                settings.explosionEnabled = !settings.explosionEnabled;
                playToggleSound(player, settings.explosionEnabled);
                menuManager.refreshMenu(player);
            }

            // Beat sensitivity
            case SLOT_BEAT_SENSITIVITY -> {
                if (click.isRightClick()) {
                    settings.beatSensitivity = Math.max(1, settings.beatSensitivity - 1);
                } else {
                    settings.beatSensitivity = Math.min(10, settings.beatSensitivity + 1);
                }
                playSound(player, Sound.UI_BUTTON_CLICK);
                menuManager.refreshMenu(player);
            }

            // Presets
            case SLOT_PRESET_1 -> {
                applyPreset(settings, "chill");
                playSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
                player.sendMessage(ChatColor.GREEN + "Applied preset: Chill");
                menuManager.refreshMenu(player);
            }
            case SLOT_PRESET_2 -> {
                applyPreset(settings, "party");
                playSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
                player.sendMessage(ChatColor.YELLOW + "Applied preset: Party");
                menuManager.refreshMenu(player);
            }
            case SLOT_PRESET_3 -> {
                applyPreset(settings, "rave");
                playSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
                player.sendMessage(ChatColor.RED + "Applied preset: Rave");
                menuManager.refreshMenu(player);
            }

            // Back
            case SLOT_BACK -> menuManager.openMenu(player, new MainMenu(plugin, menuManager));

            // Save
            case SLOT_SAVE -> {
                saveSettingsToZone(player, settings);
            }
        }
    }

    private DJSettings getSettings(Player player) {
        return playerSettings.computeIfAbsent(player.getUniqueId(), k -> new DJSettings());
    }

    private void applyPreset(DJSettings settings, String preset) {
        switch (preset) {
            case "chill" -> {
                settings.intensity = 3;
                settings.particlesEnabled = true;
                settings.screenShakeEnabled = false;
                settings.lightningEnabled = false;
                settings.explosionEnabled = false;
                settings.beatSensitivity = 4;
            }
            case "party" -> {
                settings.intensity = 5;
                settings.particlesEnabled = true;
                settings.screenShakeEnabled = true;
                settings.lightningEnabled = true;
                settings.explosionEnabled = false;
                settings.beatSensitivity = 6;
            }
            case "rave" -> {
                settings.intensity = 7;
                settings.particlesEnabled = true;
                settings.screenShakeEnabled = true;
                settings.lightningEnabled = true;
                settings.explosionEnabled = true;
                settings.beatSensitivity = 9;
            }
        }
    }

    private void playSound(Player player, Sound sound) {
        player.playSound(player.getLocation(), sound, 0.5f, 1f);
    }

    private void playToggleSound(Player player, boolean enabled) {
        if (enabled) {
            player.playSound(player.getLocation(), Sound.BLOCK_LEVER_CLICK, 0.5f, 1.2f);
        } else {
            player.playSound(player.getLocation(), Sound.BLOCK_LEVER_CLICK, 0.5f, 0.8f);
        }
    }

    /**
     * Saves the current DJ settings to the zone's effect configuration.
     */
    private void saveSettingsToZone(Player player, DJSettings settings) {
        if (settings.selectedZone == null) {
            player.sendMessage(ChatColor.RED + "No zone selected!");
            playSound(player, Sound.ENTITY_VILLAGER_NO);
            return;
        }

        BeatEventManager beatManager = plugin.getBeatEventManager();
        if (beatManager == null) {
            player.sendMessage(ChatColor.RED + "Beat effects system not available!");
            playSound(player, Sound.ENTITY_VILLAGER_NO);
            return;
        }

        // Build configuration based on DJ panel settings
        BeatEffectConfig.Builder configBuilder = new BeatEffectConfig.Builder();

        // Calculate threshold from sensitivity (higher sensitivity = lower threshold)
        float baseThreshold = 1.0f - (settings.beatSensitivity / 10.0f);

        // Add particle effects if enabled
        if (settings.particlesEnabled) {
            BeatEffect particleBurst = beatManager.get("particle_burst");
            if (particleBurst != null) {
                configBuilder.addEffect(BeatType.KICK, particleBurst);
                configBuilder.addEffect(BeatType.SNARE, particleBurst);
                configBuilder.addEffect(BeatType.HIHAT, particleBurst);
                configBuilder.addEffect(BeatType.PEAK, particleBurst);
            }
        }

        // Add screen shake if enabled (only on kick and bass drops)
        if (settings.screenShakeEnabled) {
            BeatEffect screenShake = beatManager.get("screen_shake");
            if (screenShake != null) {
                configBuilder.addEffect(BeatType.KICK, screenShake);
                configBuilder.addEffect(BeatType.BASS_DROP, screenShake);
            }
        }

        // Add lightning if enabled (bass drops only)
        if (settings.lightningEnabled) {
            BeatEffect lightning = beatManager.get("lightning");
            if (lightning != null) {
                configBuilder.addEffect(BeatType.BASS_DROP, lightning);
            }
        }

        // Add explosion if enabled (bass drops only)
        if (settings.explosionEnabled) {
            BeatEffect explosion = beatManager.get("explosion_visual");
            if (explosion != null) {
                configBuilder.addEffect(BeatType.BASS_DROP, explosion);
            }
        }

        // Set thresholds based on sensitivity
        configBuilder.setThreshold(BeatType.KICK, Math.max(0.3f, baseThreshold));
        configBuilder.setThreshold(BeatType.SNARE, Math.max(0.25f, baseThreshold - 0.1f));
        configBuilder.setThreshold(BeatType.HIHAT, Math.max(0.2f, baseThreshold - 0.2f));
        configBuilder.setThreshold(BeatType.BASS_DROP, Math.max(0.5f, baseThreshold + 0.2f));
        configBuilder.setThreshold(BeatType.PEAK, Math.max(0.4f, baseThreshold + 0.1f));

        // Set cooldowns (scale with intensity - higher intensity = shorter cooldowns)
        float intensityMultiplier = settings.intensity / 7.0f;
        configBuilder.setCooldown(BeatType.KICK, (long) (150 * (1.5f - intensityMultiplier)));
        configBuilder.setCooldown(BeatType.SNARE, (long) (200 * (1.5f - intensityMultiplier)));
        configBuilder.setCooldown(BeatType.HIHAT, (long) (100 * (1.5f - intensityMultiplier)));
        configBuilder.setCooldown(BeatType.BASS_DROP, (long) (2500 * (1.5f - intensityMultiplier)));
        configBuilder.setCooldown(BeatType.PEAK, (long) (600 * (1.5f - intensityMultiplier)));

        // Apply configuration to zone
        beatManager.setZoneConfig(settings.selectedZone, configBuilder.build());

        playSound(player, Sound.ENTITY_VILLAGER_YES);
        player.sendMessage(ChatColor.GREEN + "Settings saved for zone: " + ChatColor.YELLOW + settings.selectedZone);
        player.sendMessage(ChatColor.GRAY + "  Effects: " +
            (settings.particlesEnabled ? ChatColor.GREEN + "Particles " : "") +
            (settings.screenShakeEnabled ? ChatColor.YELLOW + "Shake " : "") +
            (settings.lightningEnabled ? ChatColor.WHITE + "Lightning " : "") +
            (settings.explosionEnabled ? ChatColor.RED + "Explosion" : ""));
    }

    /**
     * Holds DJ settings per player.
     */
    private static class DJSettings {
        String selectedZone = null;
        int intensity = 5;
        boolean particlesEnabled = true;
        boolean screenShakeEnabled = true;
        boolean lightningEnabled = false;
        boolean explosionEnabled = false;
        int beatSensitivity = 5;
    }
}
