package com.audioviz.mixin;

import com.audioviz.AudioVizMod;
import com.audioviz.stages.ZonePlacementManager;
import com.audioviz.zones.ZoneSelectionManager;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts hand swing packets to detect left-click in air.
 * Fabric's AttackBlockCallback only fires when the player targets a block,
 * so this mixin fills the gap for zone placement and selection in mid-air.
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {

    @Shadow
    public ServerPlayerEntity player;

    @Inject(method = "onHandSwing", at = @At("HEAD"))
    private void audioviz$onHandSwing(HandSwingC2SPacket packet, CallbackInfo ci) {
        AudioVizMod mod = AudioVizMod.getInstance();
        if (mod == null) return;

        ServerPlayerEntity spe = this.player;
        if (spe == null) return;

        // Zone placement takes priority
        ZonePlacementManager zpm = mod.getZonePlacementManager();
        if (zpm != null && zpm.hasActiveSession(spe)) {
            if (!zpm.wasBlockClickHandledThisTick(spe)) {
                zpm.handleLeftClick(spe);
            }
            return;
        }

        // Zone selection mode
        ZoneSelectionManager zsm = mod.getZoneSelectionManager();
        if (zsm != null && zsm.isInSelectionMode(spe)) {
            if (zpm == null || !zpm.wasBlockClickHandledThisTick(spe)) {
                zsm.handleLeftClick(spe);
            }
        }
    }
}
