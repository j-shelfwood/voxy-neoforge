package me.cortex.voxy.client.mixin.minecraft;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({FogRenderer.class})
public class MixinFogRenderer {
   @Inject(
      method = {"setupFog"},
      at = {@At("TAIL")}
   )
   private static void voxy$disableFog(CallbackInfo ci) {
      if (VoxyConfig.CONFIG.enabled && VoxyConfig.CONFIG.enableRendering) {
         RenderSystem.setShaderFogStart(-1024.0F);
         RenderSystem.setShaderFogEnd(1000000.0F);
         RenderSystem.setShaderFogShape(FogShape.SPHERE);
      }
   }

   @Inject(
      method = {"levelFogColor"},
      at = {@At("TAIL")}
   )
   private static void voxy$clearFogColorAlpha(CallbackInfo ci) {
      if (VoxyConfig.CONFIG.enabled && VoxyConfig.CONFIG.enableRendering) {
         float[] color = RenderSystem.getShaderFogColor();
         RenderSystem.setShaderFogColor(color[0], color[1], color[2], 0.0F);
      }
   }
}
