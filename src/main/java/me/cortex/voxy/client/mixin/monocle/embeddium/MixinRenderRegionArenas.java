package me.cortex.voxy.client.mixin.monocle.embeddium;

import me.cortex.voxy.client.compat.monocle.embeddium.IrisModelVertexFormats;
import org.embeddedt.embeddium.impl.render.chunk.region.RenderRegion;
import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = RenderRegion.DeviceResources.class, remap = false)
public class MixinRenderRegionArenas {
    @Redirect(method = "<init>", at = @At(value = "FIELD",
        target = "Lorg/embeddedt/embeddium/impl/render/chunk/vertex/format/ChunkMeshFormats;COMPACT:Lorg/embeddedt/embeddium/impl/render/chunk/vertex/format/ChunkVertexType;",
        remap = false))
    private ChunkVertexType voxy$useShaderVertexStride() {
        return IrisModelVertexFormats.MODEL_VERTEX_XHFP;
    }
}
