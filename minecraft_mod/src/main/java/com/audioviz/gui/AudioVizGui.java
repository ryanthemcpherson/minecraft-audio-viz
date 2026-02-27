package com.audioviz.gui;

import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.function.Consumer;

/**
 * Base class for all AudioViz GUI screens built on SGUI.
 *
 * <p>Replaces Paper's {@code Menu} interface + {@code Inventory} approach.
 * SGUI is server-side only — no client mod required. It creates virtual
 * screen handlers and sends inventory packets directly.
 *
 * <p>Subclasses override {@link #build()} to populate slots with
 * {@link GuiElementBuilder} items and click handlers.
 */
public abstract class AudioVizGui extends SimpleGui {

    protected final MenuManager menuManager;

    protected AudioVizGui(ScreenHandlerType<?> type, ServerPlayerEntity player, MenuManager menuManager) {
        super(type, player, false);
        this.menuManager = menuManager;
    }

    /**
     * Initialize title and build slots. Must be called after subclass constructor completes.
     */
    public void init() {
        setTitle(getMenuTitle());
        build();
    }

    /**
     * Get the menu title.
     */
    protected abstract Text getMenuTitle();

    /**
     * Build/populate the GUI slots. Called once on construction.
     * Subclasses add items via {@link #setSlot(int, GuiElementBuilder)}.
     */
    protected abstract void build();

    /**
     * Rebuild the GUI contents (for dynamic refresh).
     */
    public void rebuild() {
        // Clear all slots
        for (int i = 0; i < getSize(); i++) {
            clearSlot(i);
        }
        build();
    }

    /**
     * Fill all empty slots with gray glass panes (standard menu background).
     */
    protected void fillBackground() {
        GuiElementBuilder filler = new GuiElementBuilder(Items.GRAY_STAINED_GLASS_PANE)
            .setName(Text.empty())
            .hideDefaultTooltip();

        for (int i = 0; i < getSize(); i++) {
            if (getSlot(i) == null) {
                setSlot(i, filler);
            }
        }
    }

    /**
     * Play the standard UI click sound for the player.
     */
    protected void playClickSound() {
        getPlayer().playSound(
            net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value(),
            0.5f, 1.0f
        );
    }

    // ========== Layout Helpers ==========

    /**
     * Convert row/col to slot index. Row 0 = top, Col 0 = left.
     */
    protected static int slot(int row, int col) {
        return row * 9 + col;
    }

    /**
     * Add a standard back button at the given slot.
     */
    protected void setBackButton(int slotIndex, Runnable action) {
        setSlot(slotIndex, new GuiElementBuilder(Items.ARROW)
            .setName(Text.literal("Back").formatted(Formatting.WHITE))
            .setCallback((index, type, act) -> {
                playClickSound();
                action.run();
            }));
    }

    /**
     * Open an anvil text input GUI. On submit, calls onResult with the typed text.
     * On close/cancel, calls onCancel (typically reopens this menu).
     */
    protected void promptTextInput(String title, String defaultText,
                                    Consumer<String> onResult, Runnable onCancel) {
        // Track whether confirm was clicked so onClose doesn't double-fire onCancel
        boolean[] confirmed = {false};

        var anvil = new AnvilInputGui(getPlayer(), false) {
            @Override
            public void onClose() {
                if (!confirmed[0]) {
                    onCancel.run();
                }
            }
        };
        anvil.setTitle(Text.literal(title));
        anvil.setDefaultInputValue(defaultText != null ? defaultText : "");

        anvil.setSlot(2, new GuiElementBuilder(Items.LIME_CONCRETE)
            .setName(Text.literal("Confirm").formatted(Formatting.GREEN))
            .setCallback((index, type, action) -> {
                confirmed[0] = true;
                String input = anvil.getInput();
                anvil.close();
                onResult.accept(input != null ? input.trim() : "");
            }));

        anvil.open();
    }

    @Override
    public void onClose() {
        menuManager.removeSession(getPlayer().getUuid());
    }
}
