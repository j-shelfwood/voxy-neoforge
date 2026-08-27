package me.cortex.voxy.client.mixin.monocle.embeddium;

import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkMeshAttribute;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = ChunkMeshAttribute.class, remap = false)
public interface ChunkMeshAttributeAccessor {
    @Invoker("<init>")
    static ChunkMeshAttribute voxy$create(String name, int ordinal) {
        throw new AssertionError();
    }
}
