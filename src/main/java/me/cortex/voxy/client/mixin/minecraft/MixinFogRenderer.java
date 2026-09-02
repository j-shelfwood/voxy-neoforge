package me.cortex.voxy.client.mixin.minecraft;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FogRenderer.class)
public class MixinFogRenderer {
    @Inject(method = "setupFog", at = @At("TAIL"))
    private static void voxy$disableFog(CallbackInfo ci) {
        if (!VoxyConfig.CONFIG.enabled || !VoxyConfig.CONFIG.enableRendering) {
            return;
        }

        RenderSystem.setShaderFogStart(-1024.0f);
        RenderSystem.setShaderFogEnd(1000000.0f);
        RenderSystem.setShaderFogShape(FogShape.SPHERE);
    }

    /**
     * Zeroes out the fog colour alpha so that Sodium's chunk shader fog is fully
     * transparent even though it always compiles with USE_FOG (ChunkFogMode.SMOOTH
     * is hard-coded in ShaderChunkRenderer.begin()).
     *
     * Sodium reads fog colour via RenderSystem.getShaderFogColor(); setting alpha=0
     * makes:
     *   mix(fragColor, fogColor, fogFactor * fogColor.a) == fragColor
     * for all fragment distances, eliminating the atmospheric haze on the LOD horizon.
     *
     * FogRenderer.levelFogColor() is the method that actually calls
     * RenderSystem.setShaderFogColor(fogRed, fogGreen, fogBlue) (with no alpha arg,
     * defaulting to 1.0). We inject at TAIL to override the alpha with 0.
     */
    @Inject(method = "levelFogColor", at = @At("TAIL"))
    private static void voxy$clearFogColorAlpha(CallbackInfo ci) {
        if (!VoxyConfig.CONFIG.enabled || !VoxyConfig.CONFIG.enableRendering) {
            return;
        }
        // Keep RGB unchanged but zero out alpha so Sodium's fog blend is a no-op
        float[] color = RenderSystem.getShaderFogColor();
        RenderSystem.setShaderFogColor(color[0], color[1], color[2], 0.0f);
    }
}
