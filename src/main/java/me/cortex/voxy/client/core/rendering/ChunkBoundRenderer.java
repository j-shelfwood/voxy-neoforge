package me.cortex.voxy.client.core.rendering;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
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
import net.minecraft.core.SectionPos;
import org.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderList;
import org.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderListIterable;
import org.embeddedt.embeddium.impl.render.chunk.region.RenderRegion;
import org.embeddedt.embeddium.impl.util.iterator.ByteIterator;
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

// Renders a depth-only AABB mask for the chunk sections Embeddium actually decided to draw this frame.
// This keeps the LOD handoff aligned to Embeddium's real visible geometry instead of approximating it from
// section build-state transitions or chunk-tracking heuristics.
public class ChunkBoundRenderer {
    private static final int INIT_MAX_SPAN_COUNT = 1 << 12;
    private static final int PERF_LOG_INTERVAL_FRAMES =
            Math.max(1, Integer.getInteger("voxy.chunkMaskPerfLogIntervalFrames", 300));

    private GlBuffer chunkPosBuffer = new GlBuffer(INIT_MAX_SPAN_COUNT * 16L); // ivec4 per vertical span
    private final GlBuffer uniformBuffer = new GlBuffer(128);
    private final LongOpenHashSet sections = new LongOpenHashSet(INIT_MAX_SPAN_COUNT);
    private final LongOpenHashSet frameSections = new LongOpenHashSet(INIT_MAX_SPAN_COUNT);
    private final Long2LongOpenHashMap columnMasks = new Long2LongOpenHashMap(INIT_MAX_SPAN_COUNT);
    private int spanCount;
    private final Shader rasterShader;
    private final AbstractRenderPipeline pipeline;
    private volatile boolean freed;
    private int perfLogFrameCounter;
    private boolean spansDirty = true;

    public ChunkBoundRenderer(AbstractRenderPipeline pipeline) {
        this.columnMasks.defaultReturnValue(0L);
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

    public void syncFromVisibleRenderLists(ChunkRenderListIterable renderLists) {
        if (this.freed) {
            return;
        }

        this.frameSections.clear();
        for (var iterator = renderLists.iterator(false); iterator.hasNext(); ) {
            ChunkRenderList renderList = iterator.next();
            RenderRegion region = renderList.getRegion();
            ByteIterator sectionIterator = renderList.sectionsWithGeometryIterator(false);
            if (sectionIterator == null) {
                continue;
            }
            while (sectionIterator.hasNext()) {
                var section = region.getSection(sectionIterator.nextByteAsInt());
                if (section == null) {
                    continue;
                }
                this.frameSections.add(SectionPos.asLong(section.getChunkX(), section.getChunkY(), section.getChunkZ()));
            }
        }

        if (sameSectionSet(this.sections, this.frameSections)) {
            return;
        }

        this.sections.clear();
        this.sections.addAll(this.frameSections);
        this.rebuildColumnMasks();
        this.spansDirty = true;
    }

    public void render(Viewport<?> viewport) {
        if (this.freed) {
            return;
        }

        viewport.depthBoundingBuffer.clear(0);
        if (this.sections.isEmpty()) {
            return;
        }

        long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, 128);
        long matPtr = ptr;
        ptr += 4 * 4 * 4;

        int bx = (int) (viewport.cameraX);
        int by = (int) (viewport.cameraY);
        int bz = (int) (viewport.cameraZ);
        new Vector3i(bx, by, bz).getToAddress(ptr);
        ptr += 4 * 4;

        var negInnerBlock = new Vector3f(
                (float) (viewport.cameraX - bx),
                (float) (viewport.cameraY - by),
                (float) (viewport.cameraZ - bz));
        negInnerBlock.getToAddress(ptr);
        ptr += 4 * 3;

        viewport.MVP.translate(negInnerBlock.negate(), new Matrix4f()).getToAddress(matPtr);
        MemoryUtil.memPutFloat(ptr, Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0f);
        UploadStream.INSTANCE.commit();

        glFrontFace(GL_CW);
        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_GREATER);

        glBindVertexArray(GlVertexArray.STATIC_VAO);
        viewport.depthBoundingBuffer.bind();
        this.rasterShader.bind();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, SharedIndexBuffer.INSTANCE_BB_BYTE.id());
        this.pipeline.bindUniforms();

        if (this.spansDirty) {
            this.rebuildSpanBuffer();
        }

        int count = this.spanCount;
        if (count >= 32) {
            glDrawElementsInstanced(GL_TRIANGLES, 6 * 2 * 3 * 32, GL_UNSIGNED_BYTE, 0, count / 32);
        }
        if ((count % 32) != 0) {
            glDrawElementsInstancedBaseInstance(GL_TRIANGLES, 6 * 2 * 3 * (count % 32), GL_UNSIGNED_BYTE, 0, 1, (count / 32) * 32);
        }

        glFrontFace(GL_CCW);
        glDepthFunc(GL_LEQUAL);
        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);

        this.maybeLogPerf(viewport);
    }

    private void rebuildColumnMasks() {
        this.columnMasks.clear();
        for (long pos : this.sections) {
            int bit = sectionBit(SectionPos.y(pos));
            if (bit < 0) {
                continue;
            }
            long key = packChunkKey(SectionPos.x(pos), SectionPos.z(pos));
            this.columnMasks.put(key, this.columnMasks.get(key) | (1L << bit));
        }
    }

    private void ensureSpanCapacity(int requiredSpans) {
        if ((requiredSpans * 16L) <= this.chunkPosBuffer.size()) {
            return;
        }
        UploadStream.INSTANCE.commit();

        int size = Math.max(requiredSpans, (int) ((this.chunkPosBuffer.size() / 16L) * 1.5));
        Logger.info("Resizing chunk position buffer to: " + size);
        var old = this.chunkPosBuffer;
        this.chunkPosBuffer = new GlBuffer(size * 16L);
        glCopyNamedBufferSubData(old.id, this.chunkPosBuffer.id, 0, 0, old.size());
        old.free();
        ((AutoBindingShader) this.rasterShader).ssbo(1, this.chunkPosBuffer);
    }

    private void rebuildSpanBuffer() {
        int requiredSpans = 0;
        for (Long2LongOpenHashMap.Entry entry : this.columnMasks.long2LongEntrySet()) {
            requiredSpans += Long.bitCount(entry.getLongValue());
        }
        this.ensureSpanCapacity(requiredSpans);

        int index = 0;
        int baseSectionY = getMinSection();
        for (Long2LongOpenHashMap.Entry entry : this.columnMasks.long2LongEntrySet()) {
            long mask = entry.getLongValue();
            if (mask == 0L) {
                continue;
            }

            int x = unpackChunkX(entry.getLongKey());
            int z = unpackChunkZ(entry.getLongKey());
            int bit = 0;
            while (bit < 64) {
                if ((mask & (1L << bit)) == 0L) {
                    bit++;
                    continue;
                }

                int startBit = bit;
                do {
                    bit++;
                } while (bit < 64 && (mask & (1L << bit)) != 0L);

                this.putSpan(index++, x, z, baseSectionY + startBit, baseSectionY + bit);
            }
        }
        this.spanCount = index;
        this.spansDirty = false;
    }

    private void putSpan(int idx, int x, int z, int yMinSection, int yMaxSectionExclusive) {
        long ptr = UploadStream.INSTANCE.upload(this.chunkPosBuffer, 16L * idx, 16);
        MemoryUtil.memPutInt(ptr, x);
        ptr += 4;
        MemoryUtil.memPutInt(ptr, z);
        ptr += 4;
        MemoryUtil.memPutInt(ptr, yMinSection);
        ptr += 4;
        MemoryUtil.memPutInt(ptr, yMaxSectionExclusive);
    }

    private static boolean sameSectionSet(LongOpenHashSet a, LongOpenHashSet b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (long pos : a) {
            if (!b.contains(pos)) {
                return false;
            }
        }
        return true;
    }

    private static long packChunkKey(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }

    private static int unpackChunkX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackChunkZ(long key) {
        return (int) key;
    }

    private static int getMinSection() {
        var level = Minecraft.getInstance().level;
        return level != null ? level.getMinSection() : -64;
    }

    private static int sectionBit(int sectionY) {
        int bit = sectionY - getMinSection();
        return bit >= 0 && bit < 64 ? bit : -1;
    }

    public void reset() {
        if (this.freed) {
            return;
        }
        this.sections.clear();
        this.frameSections.clear();
        this.columnMasks.clear();
        this.spanCount = 0;
        this.spansDirty = true;
    }

    public void replayFrom(ChunkBoundRenderer source) {
        if (this.freed || source.freed || source.sections.isEmpty()) {
            return;
        }
        this.sections.clear();
        this.sections.addAll(source.sections);
        this.rebuildColumnMasks();
        this.spansDirty = true;
        Logger.info("[ChunkBoundRenderer] Replayed " + source.sections.size() + " visible sections from previous renderer");
    }

    public int getPendingAddCount() {
        return 0;
    }

    public int getPendingRemoveCount() {
        return 0;
    }

    public int getTrackedSectionCount() {
        return this.sections.size();
    }

    public int getTrackedSpanCount() {
        return this.spanCount;
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

    private void maybeLogPerf(Viewport<?> viewport) {
        if (++this.perfLogFrameCounter < PERF_LOG_INTERVAL_FRAMES) {
            return;
        }
        this.perfLogFrameCounter = 0;
        Logger.info(
                "VOXY_PERF chunk_mask",
                "tracked_sections=" + this.sections.size(),
                "draw_spans=" + this.spanCount,
                "camera=" + ((int) viewport.cameraX) + "," + ((int) viewport.cameraY) + "," + ((int) viewport.cameraZ),
                "viewport=" + viewport.width + "x" + viewport.height
        );
    }
}
