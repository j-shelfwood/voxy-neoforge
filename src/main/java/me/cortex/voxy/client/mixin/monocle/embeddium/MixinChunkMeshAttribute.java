package me.cortex.voxy.client.mixin.monocle.embeddium;

import me.cortex.voxy.client.compat.monocle.embeddium.IrisChunkMeshAttributes;
import org.apache.commons.lang3.ArrayUtils;
import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkMeshAttribute;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = ChunkMeshAttribute.class, remap = false)
public class MixinChunkMeshAttribute {
    @SuppressWarnings("target")
    @Shadow(remap = false)
    @Final
    @Mutable
    private static ChunkMeshAttribute[] $VALUES;

    static {
        int baseOrdinal = $VALUES.length;

        IrisChunkMeshAttributes.NORMAL = ChunkMeshAttributeAccessor.voxy$create("NORMAL", baseOrdinal);
        IrisChunkMeshAttributes.TANGENT = ChunkMeshAttributeAccessor.voxy$create("TANGENT", baseOrdinal + 1);
        IrisChunkMeshAttributes.MID_TEX_COORD = ChunkMeshAttributeAccessor.voxy$create("MID_TEX_COORD", baseOrdinal + 2);
        IrisChunkMeshAttributes.BLOCK_ID = ChunkMeshAttributeAccessor.voxy$create("BLOCK_ID", baseOrdinal + 3);
        IrisChunkMeshAttributes.MID_BLOCK = ChunkMeshAttributeAccessor.voxy$create("MID_BLOCK", baseOrdinal + 4);

        $VALUES = ArrayUtils.addAll($VALUES,
            IrisChunkMeshAttributes.NORMAL,
            IrisChunkMeshAttributes.TANGENT,
            IrisChunkMeshAttributes.MID_TEX_COORD,
            IrisChunkMeshAttributes.BLOCK_ID,
            IrisChunkMeshAttributes.MID_BLOCK);
    }
}
