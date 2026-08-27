package me.cortex.voxy.client.mixin.monocle.embeddium;

import me.cortex.voxy.client.compat.monocle.embeddium.IrisModelVertexFormats;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import org.embeddedt.embeddium.impl.gui.EmbeddiumOptions;
import org.embeddedt.embeddium.impl.render.chunk.RenderSectionManager;
import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinRenderSectionManager {
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
        target = "Lorg/embeddedt/embeddium/impl/render/chunk/DefaultChunkRenderer;<init>(Lorg/embeddedt/embeddium/impl/gl/device/RenderDevice;Lorg/embeddedt/embeddium/impl/render/chunk/vertex/format/ChunkVertexType;)V"))
    private ChunkVertexType voxy$swapRendererVertexType(ChunkVertexType vertexType) {
        return IrisApi.getInstance().isShaderPackInUse() ? IrisModelVertexFormats.MODEL_VERTEX_XHFP : vertexType;
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
        target = "Lorg/embeddedt/embeddium/impl/render/chunk/compile/executor/ChunkBuilder;<init>(Lnet/minecraft/client/multiplayer/ClientLevel;Lorg/embeddedt/embeddium/impl/render/chunk/vertex/format/ChunkVertexType;)V"))
    private ChunkVertexType voxy$swapBuilderVertexType(ChunkVertexType vertexType) {
        return IrisApi.getInstance().isShaderPackInUse() ? IrisModelVertexFormats.MODEL_VERTEX_XHFP : vertexType;
    }

    @Redirect(method = "getSearchDistance", at = @At(value = "FIELD",
        target = "Lorg/embeddedt/embeddium/impl/gui/EmbeddiumOptions$PerformanceSettings;useFogOcclusion:Z",
        remap = false))
    private boolean voxy$disableFogOcclusionWithShaders(EmbeddiumOptions.PerformanceSettings settings) {
        return Iris.getCurrentPack().isPresent() ? false : settings.useFogOcclusion;
    }
}
