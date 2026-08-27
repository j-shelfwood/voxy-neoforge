package me.cortex.voxy.client.compat.monocle.embeddium;

import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexType;

public final class IrisModelVertexFormats {
    public static final ChunkVertexType MODEL_VERTEX_XHFP = new XHFPModelVertexType();

    private IrisModelVertexFormats() {
    }
}
