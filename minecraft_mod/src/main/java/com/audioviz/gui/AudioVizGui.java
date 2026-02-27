package com.audioviz.gui;

import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

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

    @Override
    public void onClose() {
        menuManager.removeSession(getPlayer().getUuid());
    }
}
