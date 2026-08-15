package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
// MC 1.21.1 NeoForge: Iris shader integration excluded
// import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.commonImpl.VoxyCommon;
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
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class MixinDefaultChunkRenderer extends ShaderChunkRenderer {

    public MixinDefaultChunkRenderer(RenderDevice device, ChunkVertexType vertexType) {
        super(device, vertexType);
    }

    // The render signature differs between Sodium generations:
    //   0.6.x: (ChunkRenderMatrices, CommandList, ChunkRenderListIterable, TerrainRenderPass, CameraTransform)
    //   0.8.x: same + trailing boolean indexedRenderingEnabled (restored)
    // One descriptor-qualified variant per shape and injection point, require = 0 each;
    // the @Groups make "exactly one applied" per pair a hard invariant, so an unknown
    // future Sodium fails loudly at apply time instead of silently dropping the LOD pass.
    // NOTE: descriptor qualification relies on Mixin's strict selector pass; never run
    // with -Dmixin.env.remapRefMap=true (the permissive pass matches by name only and
    // would bind the wrong variant).
    @Unique
    private static final String RENDER_SODIUM_06 = "render(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderListIterable;Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;)V";
    @Unique
    private static final String RENDER_SODIUM_08 = "render(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderListIterable;Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;Lnet/caffeinemc/mods/sodium/client/render/viewport/CameraTransform;Z)V";

    @Group(name = "voxy$dcrHead", min = 1, max = 1)
    @Inject(method = RENDER_SODIUM_06, at = @At(value = "HEAD"), cancellable = true, require = 0)
    private void cancelThingie(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, CallbackInfo ci) {
        this.voxy$replaceRender(matrices, renderPass, camera, ci);
    }

    @Group(name = "voxy$dcrHead", min = 1, max = 1)
    @Inject(method = RENDER_SODIUM_08, at = @At(value = "HEAD"), cancellable = true, require = 0)
    private void cancelThingieSodium08(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, boolean indexedRenderingEnabled, CallbackInfo ci) {
        this.voxy$replaceRender(matrices, renderPass, camera, ci);
    }

    @Unique
    private void voxy$replaceRender(ChunkRenderMatrices matrices, TerrainRenderPass renderPass, CameraTransform camera, CallbackInfo ci) {
        if (VoxyClient.disableSodiumChunkRender()) {
            super.begin(renderPass);
            this.doRender(matrices, renderPass, camera);
            super.end(renderPass);
            ci.cancel();
        }
    }

    @Group(name = "voxy$dcrTail", min = 1, max = 1)
    @Inject(method = RENDER_SODIUM_06, at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V", shift = At.Shift.BEFORE), require = 0)
    private void injectRender(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, CallbackInfo ci) {
        this.doRender(matrices, renderPass, camera);
    }

    @Group(name = "voxy$dcrTail", min = 1, max = 1)
    @Inject(method = RENDER_SODIUM_08, at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V", shift = At.Shift.BEFORE), require = 0)
    private void injectRenderSodium08(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, boolean indexedRenderingEnabled, CallbackInfo ci) {
        this.doRender(matrices, renderPass, camera);
    }

    @Unique
    private void doRender(ChunkRenderMatrices matrices, TerrainRenderPass renderPass, CameraTransform camera) {
        if (renderPass == DefaultTerrainRenderPasses.CUTOUT) {
            var renderer = ((IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer).getVoxyRenderSystem();
            if (renderer != null) {
                Viewport<?> viewport = null;
                // MC 1.21.1 NeoForge: Iris shader integration excluded - irisShaderPackEnabled() returns false
                if (false) {
                    viewport = renderer.getViewport();
                } else {
                    // Sodium 0.6.x: setupViewport no longer takes FogParameters
                    viewport = renderer.setupViewport(matrices, camera.x, camera.y, camera.z);
                }
                renderer.renderOpaque(viewport);
            }
        }
    }
}
