package com.audioviz.decorators;

import com.audioviz.AudioVizPlugin;
import com.audioviz.decorators.impl.*;
import com.audioviz.stages.Stage;
import org.bukkit.Material;

import java.util.function.BiFunction;

/**
 * Registry of all decorator types with their factories, icons, and metadata.
 */
public enum DecoratorType {

    BILLBOARD("billboard", "DJ Billboard", Material.OAK_SIGN,
        "Floating DJ name display above the stage",
        DJBillboardDecorator::new),

    TEXT_FX("text_fx", "Beat Text FX", Material.WRITABLE_BOOK,
        "Beat-reactive hype text with glow effects",
        BeatTextFXDecorator::new),

    SPOTLIGHT("spotlight", "Spotlights", Material.END_ROD,
        "Sweeping spotlight beams that follow the music",
        SpotlightDecorator::new),

    FLOOR_TILES("floor_tiles", "Stage Floor", Material.AMETHYST_BLOCK,
        "Bass-reactive LED floor tile grid",
        FloorTileDecorator::new),

    CROWD("crowd", "Crowd FX", Material.FIREWORK_ROCKET,
        "Particle effects around audience members",
        CrowdInteractionDecorator::new),

    TRANSITION("transition", "DJ Transitions", Material.NETHER_STAR,
        "Dramatic effects when the DJ switches",
        DJTransitionDecorator::new);

    private final String id;
    private final String displayName;
    private final Material icon;
    private final String description;
    private final BiFunction<Stage, AudioVizPlugin, StageDecorator> factory;

    DecoratorType(String id, String displayName, Material icon, String description,
                  BiFunction<Stage, AudioVizPlugin, StageDecorator> factory) {
        this.id = id;
        this.displayName = displayName;
        this.icon = icon;
        this.description = description;
        this.factory = factory;
    }

    /**
     * Create a new decorator instance for the given stage.
     */
    public StageDecorator create(Stage stage, AudioVizPlugin plugin) {
        return factory.apply(stage, plugin);
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Material getIcon() {
        return icon;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Find a DecoratorType by its string ID.
     */
    public static DecoratorType fromId(String id) {
        for (DecoratorType type : values()) {
            if (type.id.equals(id)) {
                return type;
            }
        }
        return null;
    }
}
