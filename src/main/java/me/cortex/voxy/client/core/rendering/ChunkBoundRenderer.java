package me.cortex.voxy.client.core.rendering;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlVertexArray;
import me.cortex.voxy.client.core.gl.shader.AutoBindingShader;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.ARBDirectStateAccess.glCopyNamedBufferSubData;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31.glDrawElementsInstanced;
import static org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseInstance;

// Renders an AABB around each built chunk section into a depth mask.
// LOD fragments are discarded wherever MC geometry exists (reverse depth test).
// Sections enter/leave the mask when Embeddium transitions their built state,
// mirroring the upstream Sodium approach in MixinRenderSectionManager.
public class ChunkBoundRenderer {
    private static final int INIT_MAX_CHUNK_COUNT = 1 << 12;
    private GlBuffer chunkPosBuffer = new GlBuffer(INIT_MAX_CHUNK_COUNT * 8); // ivec2 per section
    private final GlBuffer uniformBuffer = new GlBuffer(128);
    private final Long2IntOpenHashMap chunk2idx = new Long2IntOpenHashMap(INIT_MAX_CHUNK_COUNT);
    private long[] idx2chunk = new long[INIT_MAX_CHUNK_COUNT];
    private final Shader rasterShader;

    private final LongOpenHashSet addQueue = new LongOpenHashSet();
    private final LongOpenHashSet remQueue = new LongOpenHashSet();

    private final AbstractRenderPipeline pipeline;
    private volatile boolean freed;

    public ChunkBoundRenderer(AbstractRenderPipeline pipeline) {
        this.chunk2idx.defaultReturnValue(-1);
        this.pipeline = pipeline;

        String vert = ShaderLoader.parse("voxy:chunkoutline/outline.vsh");
        String taa = pipeline.taaFunction("getTAA");
        if (taa != null) {
            vert = vert + "\n\n\n" + taa;
        }
        this.rasterShader = Shader.makeAuto()
                .addSource(ShaderType.VERTEX, vert)
                .defineIf("TAA", taa != null)
                .add(ShaderType.FRAGMENT, "voxy:chunkoutline/outline.fsh")
                .compile()
                .ubo(0, this.uniformBuffer)
                .ssbo(1, this.chunkPosBuffer);
    }

    public void addSection(long pos) {
        if (this.freed) {
            return;
        }
        if (!this.remQueue.remove(pos)) {
            this.addQueue.add(pos);
        }
    }

    public void removeSection(long pos) {
        if (this.freed) {
            return;
        }
        if (!this.addQueue.remove(pos)) {
            this.remQueue.add(pos);
        }
    }

    public void render(Viewport<?> viewport) {
        if (this.freed) {
            return;
        }
        if (!this.remQueue.isEmpty()) {
            boolean wasEmpty = this.chunk2idx.isEmpty();
            this.remQueue.forEach(this::_remPos);
            this.remQueue.clear();
            if (this.chunk2idx.isEmpty() && !wasEmpty) {
                viewport.depthBoundingBuffer.clear(0);
            }
        }

        if (this.chunk2idx.isEmpty() && this.addQueue.isEmpty()) return;

        viewport.depthBoundingBuffer.clear(0);

        long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, 128);
        long matPtr = ptr; ptr += 4 * 4 * 4;

        final float vanillaRenderDistanceBlocks = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0f;
        final float offsetBlocks = VoxyConfig.CONFIG.getRenderDistanceOffset() * 16.0f;
        // Positive buffer shrinks mask inward to keep LODs visible slightly longer near the handoff edge.
        final float boundaryBufferBlocks = VoxyConfig.CONFIG.getLodBoundaryBuffer();
        final float renderDistance = Math.max(16.0f, vanillaRenderDistanceBlocks + offsetBlocks - boundaryBufferBlocks);

        {
            int sx = (int) (viewport.cameraX);
            int sy = (int) (viewport.cameraY);
            int sz = (int) (viewport.cameraZ);
            new Vector3i(sx, sy, sz).getToAddress(ptr); ptr += 4 * 4;

            var negInnerSec = new Vector3f(
                    (float) (viewport.cameraX - sx),
                    (float) (viewport.cameraY - sy),
                    (float) (viewport.cameraZ - sz));

            negInnerSec.getToAddress(ptr); ptr += 4 * 3;
            viewport.MVP.translate(negInnerSec.negate(), new Matrix4f()).getToAddress(matPtr);
            MemoryUtil.memPutFloat(ptr, renderDistance);
        }
        UploadStream.INSTANCE.commit();

        {
            glFrontFace(GL_CW);
            glEnable(GL_CULL_FACE);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_GREATER);
        }

        glBindVertexArray(GlVertexArray.STATIC_VAO);
        viewport.depthBoundingBuffer.bind();
        this.rasterShader.bind();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, SharedIndexBuffer.INSTANCE_BB_BYTE.id());
        this.pipeline.bindUniforms();

        int count = this.chunk2idx.size();
        if (count >= 32) {
            glDrawElementsInstanced(GL_TRIANGLES, 6 * 2 * 3 * 32, GL_UNSIGNED_BYTE, 0, count / 32);
        }
        if (count % 32 != 0) {
            glDrawElementsInstancedBaseInstance(GL_TRIANGLES, 6 * 2 * 3 * (count % 32), GL_UNSIGNED_BYTE, 0, 1, (count / 32) * 32);
        }

        {
            glFrontFace(GL_CCW);
            glDepthFunc(GL_LEQUAL);
            glEnable(GL_CULL_FACE);
            glEnable(GL_DEPTH_TEST);
        }

        if (!this.addQueue.isEmpty()) {
            this.addQueue.forEach(this::_addPos);
            this.addQueue.clear();
            UploadStream.INSTANCE.commit();
        }
    }

    private void _remPos(long pos) {
        int idx = this.chunk2idx.remove(pos);
        if (idx == -1) {
            Logger.warn("Chunk not in map: " + pos);
            return;
        }
        if (idx == this.chunk2idx.size()) {
            return;
        }
        if (this.idx2chunk[idx] != pos) {
            throw new IllegalStateException();
        }
        long ePos = this.idx2chunk[this.chunk2idx.size()];
        if (this.chunk2idx.put(ePos, idx) == -1) {
            throw new IllegalStateException();
        }
        this.idx2chunk[idx] = ePos;
        this.put(idx, ePos);
    }

    private void _addPos(long pos) {
        if (this.chunk2idx.containsKey(pos)) {
            Logger.warn("Chunk already in map: " + pos);
            return;
        }
        this.ensureSize1();

        int idx = this.chunk2idx.size();
        this.chunk2idx.put(pos, idx);
        this.idx2chunk[idx] = pos;
        this.put(idx, pos);
    }

    private void ensureSize1() {
        if (this.chunk2idx.size() < this.idx2chunk.length) return;
        UploadStream.INSTANCE.commit();

        int size = (int) (this.idx2chunk.length * 1.5);
        Logger.info("Resizing chunk position buffer to: " + size);
        var old = this.chunkPosBuffer;
        this.chunkPosBuffer = new GlBuffer(size * 8L);
        glCopyNamedBufferSubData(old.id, this.chunkPosBuffer.id, 0, 0, old.size());
        old.free();
        var old2 = this.idx2chunk;
        this.idx2chunk = new long[size];
        System.arraycopy(old2, 0, this.idx2chunk, 0, old2.length);
        ((AutoBindingShader) this.rasterShader).ssbo(1, this.chunkPosBuffer);
    }

    private void put(int idx, long pos) {
        long ptr2 = UploadStream.INSTANCE.upload(this.chunkPosBuffer, 8L * idx, 8);
        MemoryUtil.memPutInt(ptr2, (int) (pos & 0xFFFFFFFFL)); ptr2 += 4;
        MemoryUtil.memPutInt(ptr2, (int) ((pos >>> 32) & 0xFFFFFFFFL));
    }

    public void reset() {
        if (this.freed) {
            return;
        }
        this.chunk2idx.clear();
    }

    /**
     * Replay all currently-tracked section positions from {@code source} into this renderer.
     * Called after a /voxy reload so the new ChunkBoundRenderer inherits the built-section
     * mask from the old one without waiting for Embeddium to re-fire section-built events.
     */
    public void replayFrom(ChunkBoundRenderer source) {
        if (this.freed || source.freed) return;
        int count = source.chunk2idx.size();
        if (count == 0) return;
        // idx2chunk[0..size-1] holds the canonical packed SectionPos longs.
        for (int i = 0; i < count; i++) {
            this.addSection(source.idx2chunk[i]);
        }
        Logger.info("[ChunkBoundRenderer] Replayed " + count + " built sections from previous renderer");
    }

    public int getPendingAddCount() {
        return this.addQueue.size();
    }

    public int getPendingRemoveCount() {
        return this.remQueue.size();
    }

    public int getTrackedSectionCount() {
        return this.chunk2idx.size();
    }

    public void free() {
        if (this.freed) {
            return;
        }
        this.freed = true;
        this.rasterShader.free();
        this.uniformBuffer.free();
        this.chunkPosBuffer.free();
    }
}
