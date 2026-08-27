package me.cortex.voxy.client.compat.monocle.embeddium;

import net.irisshaders.iris.vertices.ExtendedDataHelper;
import net.irisshaders.iris.vertices.NormI8;
import net.irisshaders.iris.vertices.NormalHelper;
import net.irisshaders.iris.vertices.sodium.terrain.BlockContextHolder;
import net.irisshaders.iris.vertices.sodium.terrain.VertexEncoderInterface;
import org.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import org.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexEncoder;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import static me.cortex.voxy.client.compat.monocle.embeddium.XHFPModelVertexType.STRIDE;

public final class XHFPTerrainVertex implements ChunkVertexEncoder, VertexEncoderInterface {
    private final QuadViewTerrain.QuadViewTerrainUnsafe quad = new QuadViewTerrain.QuadViewTerrainUnsafe();
    private final Vector3f normal = new Vector3f();

    private BlockContextHolder contextHolder;
    private int vertexCount;
    private float uSum;
    private float vSum;
    private boolean flipUpcomingNormal;

    @Override
    public void iris$setContextHolder(BlockContextHolder holder) {
        this.contextHolder = holder;
    }

    public void flipUpcomingQuadNormal() {
        this.flipUpcomingNormal = true;
    }

    @Override
    public long write(long ptr, Material material, Vertex vertex, int chunkId) {
        uSum += vertex.u;
        vSum += vertex.v;
        vertexCount++;

        MemoryUtil.memPutShort(ptr, XHFPModelVertexType.encodePosition(vertex.x));
        MemoryUtil.memPutShort(ptr + 2L, XHFPModelVertexType.encodePosition(vertex.y));
        MemoryUtil.memPutShort(ptr + 4L, XHFPModelVertexType.encodePosition(vertex.z));
        MemoryUtil.memPutByte(ptr + 6L, (byte) material.bits());
        MemoryUtil.memPutByte(ptr + 7L, (byte) chunkId);

        MemoryUtil.memPutInt(ptr + 8L, vertex.color);
        MemoryUtil.memPutInt(ptr + 12L, XHFPModelVertexType.encodeTexture(vertex.u, vertex.v));
        MemoryUtil.memPutInt(ptr + 16L, vertex.light);

        MemoryUtil.memPutInt(ptr + 32L, packBlockId(contextHolder));
        MemoryUtil.memPutInt(ptr + 36L, contextHolder.ignoreMidBlock() ? 0 :
            ExtendedDataHelper.computeMidBlock(vertex.x, vertex.y, vertex.z,
                contextHolder.getLocalPosX(), contextHolder.getLocalPosY(), contextHolder.getLocalPosZ()));
        MemoryUtil.memPutByte(ptr + 39L, contextHolder.getBlockEmission());

        if (vertexCount == 4) {
            vertexCount = 0;

            uSum *= 0.25f;
            vSum *= 0.25f;

            int midUv = XHFPModelVertexType.encodeTexture(uSum, vSum);
            MemoryUtil.memPutInt(ptr + 20L, midUv);
            MemoryUtil.memPutInt(ptr + 20L - STRIDE, midUv);
            MemoryUtil.memPutInt(ptr + 20L - STRIDE * 2L, midUv);
            MemoryUtil.memPutInt(ptr + 20L - STRIDE * 3L, midUv);

            uSum = 0.0f;
            vSum = 0.0f;

            quad.setup(ptr, STRIDE);
            if (flipUpcomingNormal) {
                NormalHelper.computeFaceNormalFlipped(normal, quad);
                flipUpcomingNormal = false;
            } else {
                NormalHelper.computeFaceNormal(normal, quad);
            }

            int packedNormal = NormI8.pack(normal);
            MemoryUtil.memPutInt(ptr + 28L, packedNormal);
            MemoryUtil.memPutInt(ptr + 28L - STRIDE, packedNormal);
            MemoryUtil.memPutInt(ptr + 28L - STRIDE * 2L, packedNormal);
            MemoryUtil.memPutInt(ptr + 28L - STRIDE * 3L, packedNormal);

            int tangent = NormalHelper.computeTangent(normal.x, normal.y, normal.z, quad);
            MemoryUtil.memPutInt(ptr + 24L, tangent);
            MemoryUtil.memPutInt(ptr + 24L - STRIDE, tangent);
            MemoryUtil.memPutInt(ptr + 24L - STRIDE * 2L, tangent);
            MemoryUtil.memPutInt(ptr + 24L - STRIDE * 3L, tangent);
        }

        return ptr + STRIDE;
    }

    private static int packBlockId(BlockContextHolder holder) {
        return ((holder.getBlockId() + 1) << 1) | (holder.getRenderType() & 1);
    }
}
