package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.device.RenderDevice;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(
   value = {DefaultChunkRenderer.class},
   remap = false
)
public abstract class MixinDefaultChunkRenderer extends ShaderChunkRenderer {
   public MixinDefaultChunkRenderer(RenderDevice device, ChunkVertexType vertexType) {
      super(device, vertexType);
   }

   @Inject(
      method = {"render(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderListIterable;Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;)V"},
      at = {@At("HEAD")},
      cancellable = true,
      require = 0
   )
   private void cancelThingie(
      ChunkRenderMatrices matrices,
      CommandList commandList,
      ChunkRenderListIterable renderLists,
      TerrainRenderPass renderPass,
      CameraTransform camera,
      CallbackInfo ci
   ) {
      this.voxy$cancelSodiumRender(matrices, renderPass, camera, ci);
   }

   @Inject(
      method = {"render(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderListIterable;Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;Z)V"},
      at = {@At("HEAD")},
      cancellable = true,
      require = 0
   )
   private void cancelThingie(
      ChunkRenderMatrices matrices,
      CommandList commandList,
      ChunkRenderListIterable renderLists,
      TerrainRenderPass renderPass,
      CameraTransform camera,
      boolean indexedRenderingEnabled,
      CallbackInfo ci
   ) {
      this.voxy$cancelSodiumRender(matrices, renderPass, camera, ci);
   }

   @Unique
   private void voxy$cancelSodiumRender(ChunkRenderMatrices matrices, TerrainRenderPass renderPass, CameraTransform camera, CallbackInfo ci) {
      if (VoxyClient.disableSodiumChunkRender() && !IrisUtil.irisShadowActive()) {
         super.begin(renderPass);
         this.doRender(matrices, renderPass, camera);
         super.end(renderPass);
         ci.cancel();
      }
   }

   @Unique
   private void doRender(ChunkRenderMatrices matrices, TerrainRenderPass renderPass, CameraTransform camera) {
      if (!IrisUtil.irisShadowActive()) {
         if (renderPass == DefaultTerrainRenderPasses.CUTOUT) {
            VoxyRenderSystem renderer = ((IGetVoxyRenderSystem)Minecraft.getInstance().levelRenderer).getVoxyRenderSystem();
            if (renderer != null) {
               if (IrisUtil.irisShaderPackEnabled() && !renderer.isUsingIrisPipeline()) {
                  return;
               }

               Viewport<?> viewport = null;
               if (IrisUtil.irisShaderPackEnabled()) {
                  viewport = renderer.getViewport();
               } else {
                  viewport = renderer.setupViewport(matrices, camera.x, camera.y, camera.z);
               }

               renderer.renderOpaque(viewport);
            }
         }
      }
   }

   @Inject(
      method = {"render(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderListIterable;Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;)V"},
      at = {@At(
         value = "INVOKE",
         target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V",
         shift = Shift.BEFORE
      )},
      require = 0
   )
   private void injectRender(
      ChunkRenderMatrices matrices,
      CommandList commandList,
      ChunkRenderListIterable renderLists,
      TerrainRenderPass renderPass,
      CameraTransform camera,
      CallbackInfo ci
   ) {
      this.doRender(matrices, renderPass, camera);
   }

   @Inject(
      method = {"render(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderListIterable;Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;Z)V"},
      at = {@At(
         value = "INVOKE",
         target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V",
         shift = Shift.BEFORE
      )},
      require = 0
   )
   private void injectRender(
      ChunkRenderMatrices matrices,
      CommandList commandList,
      ChunkRenderListIterable renderLists,
      TerrainRenderPass renderPass,
      CameraTransform camera,
      boolean indexedRenderingEnabled,
      CallbackInfo ci
   ) {
      this.doRender(matrices, renderPass, camera);
   }
}
