package me.cortex.voxy.client.mixin.minecraft;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Guards world rendering during multiplayer join before the local player entity exists.
 *
 * Sable (and potentially other mods) assume {@code minecraft.player} is non-null in
 * {@code GameRenderer.renderLevel} hooks. Voxy's earlier renderer/world setup on join can
 * cause {@code renderLevel} to run while the client is still connecting, triggering NPEs.
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer {
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "renderLevel", at = @At("HEAD"), cancellable = true, order = 100)
    private void voxy$skipRenderUntilPlayerExists(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (this.minecraft.player == null) {
            ci.cancel();
        }
    }
}