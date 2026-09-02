package me.cortex.voxy.client.mixin.iris;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({LevelRenderer.class})
public class MixinLevelRenderer {
   @Shadow
   @Final
   private Minecraft minecraft;

   @Inject(
      method = {"renderLevel"},
      at = {@At("HEAD")},
      order = 100
   )
   private void voxy$injectIrisCompat(
      DeltaTracker tickCounter,
      boolean renderBlockOutline,
      Camera camera,
      GameRenderer gameRenderer,
      LightTexture lightTexture,
      Matrix4f frustumMatrix,
      Matrix4f projectionMatrix,
      CallbackInfo ci
   ) {
      if (IrisUtil.irisShaderPackEnabled()) {
         VoxyRenderSystem renderer = ((IGetVoxyRenderSystem)this).getVoxyRenderSystem();
         if (renderer != null) {
            GL11C.glViewport(0, 0, Minecraft.getInstance().getMainRenderTarget().width, Minecraft.getInstance().getMainRenderTarget().height);
            Vec3 pos = camera.getPosition();
            IrisUtil.CAPTURED_VIEWPORT_PARAMETERS = new IrisUtil.CapturedViewportParameters(
               new ChunkRenderMatrices(projectionMatrix, frustumMatrix), pos.x, pos.y, pos.z
            );
         }
      }
   }
}
