package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.AutoBindingShader;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryUtil;

public class HierarchicalOcclusionTraverser {
   public static final boolean HIERARCHICAL_SHADER_DEBUG = System.getProperty("voxy.hierarchicalShaderDebug", "false").equals("true");
   public static final int MAX_REQUEST_QUEUE_SIZE = 50;
   public static final int MAX_QUEUE_SIZE = 200000;
   private static final int MAX_ITERATIONS = 5;
   private static final int LOCAL_WORK_SIZE_BITS = 5;
   private final AsyncNodeManager nodeManager;
   private final NodeCleaner nodeCleaner;
   private final RenderGenerationService meshGen;
   private final GlBuffer requestBuffer;
   private final GlBuffer nodeBuffer;
   private final GlBuffer uniformBuffer = new GlBuffer(1024L).zero();
   private final GlBuffer statisticsBuffer = new GlBuffer(1024L).zero();
   private int topNodeCount;
   private final Int2IntOpenHashMap topNode2idxMapping = new Int2IntOpenHashMap();
   private final int[] idx2topNodeMapping = new int[200000];
   private final GlBuffer topNodeIds = new GlBuffer(800000L).zero();
   private final GlBuffer queueMetaBuffer = new GlBuffer(80L).zero();
   private final GlBuffer scratchQueueA = new GlBuffer(800000L).zero();
   private final GlBuffer scratchQueueB = new GlBuffer(800000L).zero();
   private static int BINDING_COUNTER = 1;
   private static final int SCENE_UNIFORM_BINDING = BINDING_COUNTER++;
   private static final int REQUEST_QUEUE_BINDING = BINDING_COUNTER++;
   private static final int RENDER_QUEUE_BINDING = BINDING_COUNTER++;
   private static final int NODE_DATA_BINDING = BINDING_COUNTER++;
   private static final int NODE_QUEUE_INDEX_BINDING = BINDING_COUNTER++;
   private static final int NODE_QUEUE_META_BINDING = BINDING_COUNTER++;
   private static final int NODE_QUEUE_SOURCE_BINDING = BINDING_COUNTER++;
   private static final int NODE_QUEUE_SINK_BINDING = BINDING_COUNTER++;
   private static final int RENDER_TRACKER_BINDING = BINDING_COUNTER++;
   private static final int STATISTICS_BUFFER_BINDING = BINDING_COUNTER++;
   private final int hizSampler = GL45.glGenSamplers();
   private final AutoBindingShader traversal = Shader.makeAuto(PrintfDebugUtil.PRINTF_processor)
      .defineIf("DEBUG", HIERARCHICAL_SHADER_DEBUG)
      .define("MAX_ITERATIONS", 5)
      .define("LOCAL_SIZE_BITS", 5)
      .define("MAX_REQUEST_QUEUE_SIZE", 50)
      .define("HIZ_BINDING", 0)
      .define("SCENE_UNIFORM_BINDING", SCENE_UNIFORM_BINDING)
      .define("REQUEST_QUEUE_BINDING", REQUEST_QUEUE_BINDING)
      .define("RENDER_QUEUE_BINDING", RENDER_QUEUE_BINDING)
      .define("NODE_DATA_BINDING", NODE_DATA_BINDING)
      .define("NODE_QUEUE_INDEX_BINDING", NODE_QUEUE_INDEX_BINDING)
      .define("NODE_QUEUE_META_BINDING", NODE_QUEUE_META_BINDING)
      .define("NODE_QUEUE_SOURCE_BINDING", NODE_QUEUE_SOURCE_BINDING)
      .define("NODE_QUEUE_SINK_BINDING", NODE_QUEUE_SINK_BINDING)
      .define("RENDER_TRACKER_BINDING", RENDER_TRACKER_BINDING)
      .defineIf("HAS_STATISTICS", RenderStatistics.enabled)
      .defineIf("STATISTICS_BUFFER_BINDING", RenderStatistics.enabled, STATISTICS_BUFFER_BINDING)
      .add(ShaderType.COMPUTE, "voxy:lod/hierarchical/traversal_dev.comp")
      .compile();
   private static final long SCRATCH = MemoryUtil.nmemAlloc(32L);

   public HierarchicalOcclusionTraverser(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, RenderGenerationService meshGen) {
      this.nodeCleaner = nodeCleaner;
      this.nodeManager = nodeManager;
      this.meshGen = meshGen;
      this.requestBuffer = new GlBuffer(408L).zero();
      this.nodeBuffer = new GlBuffer(nodeManager.maxNodeCount * 16L).fill(-1);
      GL45.glSamplerParameteri(this.hizSampler, 10241, 9984);
      GL45.glSamplerParameteri(this.hizSampler, 10240, 9728);
      GL45.glSamplerParameteri(this.hizSampler, 10243, 33071);
      GL45.glSamplerParameteri(this.hizSampler, 10242, 33071);
      this.traversal
         .ubo("SCENE_UNIFORM_BINDING", this.uniformBuffer)
         .ssbo("REQUEST_QUEUE_BINDING", this.requestBuffer)
         .ssbo("NODE_DATA_BINDING", this.nodeBuffer)
         .ssbo("NODE_QUEUE_META_BINDING", this.queueMetaBuffer)
         .ssbo("RENDER_TRACKER_BINDING", this.nodeCleaner.visibilityBuffer)
         .ssboIf("STATISTICS_BUFFER_BINDING", this.statisticsBuffer);
      this.topNode2idxMapping.defaultReturnValue(-1);
      this.nodeManager.setTLNAddRemoveCallbacks(this::addTLN, this::remTLN);
   }

   private void addTLN(int id) {
      int aid = this.topNodeCount++;
      if (this.topNodeCount > this.topNodeIds.size() / 4L) {
         throw new IllegalStateException("Top level node count greater than capacity");
      } else {
         MemoryUtil.memPutInt(SCRATCH, id);
         GL45.nglClearNamedBufferSubData(this.topNodeIds.id, 33334, aid * 4L, 4L, 36244, 5125, SCRATCH);
         if (this.topNode2idxMapping.put(id, aid) != -1) {
            throw new IllegalStateException();
         } else {
            this.idx2topNodeMapping[aid] = id;
         }
      }
   }

   private void remTLN(int id) {
      int idx = this.topNode2idxMapping.remove(id);
      this.topNodeCount--;
      if (idx == -1) {
         throw new IllegalStateException();
      } else if (idx != this.topNodeCount) {
         int endTLNId = this.idx2topNodeMapping[this.topNodeCount];
         this.idx2topNodeMapping[idx] = endTLNId;
         if (this.topNode2idxMapping.put(endTLNId, idx) == -1) {
            throw new IllegalStateException();
         } else {
            MemoryUtil.memPutInt(SCRATCH, endTLNId);
            GL45.nglClearNamedBufferSubData(this.topNodeIds.id, 33334, idx * 4L, 4L, 36244, 5125, SCRATCH);
         }
      }
   }

   private static void setFrustum(Viewport<?> viewport, long ptr) {
      for (int i = 0; i < 6; i++) {
         Vector4f plane = viewport.frustumPlanes[i];
         plane.getToAddress(ptr);
         ptr += 16L;
      }
   }

   private void uploadUniform(Viewport<?> viewport, HierarchicalOcclusionTraverser.TraversalMode mode) {
      long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0L, 1024L);
      viewport.MVP.getToAddress(ptr);
      ptr += 64L;
      viewport.section.getToAddress(ptr);
      ptr += 12L;
      MemoryUtil.memPutInt(ptr, viewport.hiZBuffer.getPackedLevels());
      ptr += 4L;
      viewport.innerTranslation.getToAddress(ptr);
      ptr += 12L;
      float screenspaceAreaDecreasingSize = VoxyConfig.CONFIG.subDivisionSize * VoxyConfig.CONFIG.subDivisionSize;
      MemoryUtil.memPutFloat(ptr, screenspaceAreaDecreasingSize / (viewport.width * viewport.height));
      ptr += 4L;
      setFrustum(viewport, ptr);
      ptr += 96L;
      MemoryUtil.memPutInt(ptr, (int)(viewport.getRenderList().size() / 4L - 1L));
      ptr += 4L;
      MemoryUtil.memPutInt(ptr, this.nodeCleaner.visibilityId);
      ptr += 4L;
      double TARGET_COUNT = 4000.0;
      double iFillness = Math.max(0.0, (4000.0 - this.meshGen.getTaskCount()) / 4000.0);
      iFillness = Math.pow(iFillness, 2.0);
      int requestSize = (int)Math.ceil(iFillness * 50.0);
      int requestCount = mode == HierarchicalOcclusionTraverser.TraversalMode.MAIN ? Math.max(0, Math.min(50, requestSize)) : 0;
      MemoryUtil.memPutInt(ptr, requestCount);
      ptr += 4L;
      MemoryUtil.memPutInt(ptr, mode.flags);
   }

   private void bindings(Viewport<?> viewport) {
      GL45.glBindBuffer(37102, this.queueMetaBuffer.id);
      GL45.glBindTextureUnit(0, viewport.hiZBuffer.getHizTextureId());
      GL45.glBindSampler(0, this.hizSampler);
      GL45.glBindBufferBase(37074, RENDER_QUEUE_BINDING, viewport.getRenderList().id);
   }

   public void doTraversal(Viewport<?> viewport) {
      this.doTraversal(viewport, HierarchicalOcclusionTraverser.TraversalMode.MAIN);
   }

   public void doTraversal(Viewport<?> viewport, HierarchicalOcclusionTraverser.TraversalMode mode) {
      this.uploadUniform(viewport, mode);
      this.traversal.bind();
      this.bindings(viewport);
      PrintfDebugUtil.bind();
      if (RenderStatistics.enabled) {
         this.statisticsBuffer.zero();
      }

      GL45.nglClearNamedBufferSubData(viewport.getRenderList().id, 33334, 0L, 4L, 36244, 5125, 0L);
      this.traverseInternal();
      this.downloadResetRequestQueue();
      if (RenderStatistics.enabled) {
         DownloadStream.INSTANCE.download(this.statisticsBuffer, down -> {
            for (int i = 0; i < 5; i++) {
               RenderStatistics.hierarchicalTraversalCounts[i] = MemoryUtil.memGetInt(down.address + i * 4L);
            }

            for (int i = 0; i < 5; i++) {
               RenderStatistics.hierarchicalRenderSections[i] = MemoryUtil.memGetInt(down.address + 20L + i * 4L);
            }
         });
      }

      GL45.glBindSampler(0, 0);
      GL45.glBindTextureUnit(0, 0);
   }

   private void traverseInternal() {
      GL45.glPixelStorei(3314, 0);
      GL45.glPixelStorei(32878, 0);
      GL45.glPixelStorei(3316, 0);
      GL45.glPixelStorei(3315, 0);
      GL45.glPixelStorei(32877, 0);
      int firstDispatchSize = this.topNodeCount + 32 - 1 >> 5;
      long ptr = UploadStream.INSTANCE.upload(this.queueMetaBuffer, 0L, 80L);
      MemoryUtil.memPutInt(ptr + 0L, firstDispatchSize);
      MemoryUtil.memPutInt(ptr + 4L, 1);
      MemoryUtil.memPutInt(ptr + 8L, 1);
      MemoryUtil.memPutInt(ptr + 12L, this.topNodeCount);

      for (int i = 1; i < 5; i++) {
         MemoryUtil.memPutInt(ptr + i * 16 + 0L, 0);
         MemoryUtil.memPutInt(ptr + i * 16 + 4L, 1);
         MemoryUtil.memPutInt(ptr + i * 16 + 8L, 1);
         MemoryUtil.memPutInt(ptr + i * 16 + 12L, 0);
      }

      UploadStream.INSTANCE.commit();
      GL45.glUniform1ui(NODE_QUEUE_INDEX_BINDING, 0);
      GL45.glBindBufferBase(37074, NODE_QUEUE_SOURCE_BINDING, this.topNodeIds.id);
      GL45.glBindBufferBase(37074, NODE_QUEUE_SINK_BINDING, this.scratchQueueB.id);
      GL42.glMemoryBarrier(8768);
      GL45.glDispatchCompute(firstDispatchSize, 1, 1);
      GL42.glMemoryBarrier(8256);

      for (int iter = 1; iter < 5; iter++) {
         GL45.glUniform1ui(NODE_QUEUE_INDEX_BINDING, iter);
         GL45.glBindBufferBase(37074, NODE_QUEUE_SOURCE_BINDING, ((iter & 1) == 0 ? this.scratchQueueA : this.scratchQueueB).id);
         GL45.glBindBufferBase(37074, NODE_QUEUE_SINK_BINDING, ((iter & 1) == 0 ? this.scratchQueueB : this.scratchQueueA).id);
         GL42.glMemoryBarrier(8256);
         GL45.glDispatchComputeIndirect(iter * 4 * 4);
      }

      GL42.glMemoryBarrier(8256);
   }

   private void downloadResetRequestQueue() {
      GL42.glMemoryBarrier(8192);
      DownloadStream.INSTANCE.download(this.requestBuffer, this::forwardDownloadResult);
      GL45.nglClearNamedBufferSubData(this.requestBuffer.id, 33334, 0L, 4L, 36244, 5125, 0L);
   }

   private void forwardDownloadResult(long ptr, long size) {
      int count = MemoryUtil.memGetInt(ptr);
      ptr += 8L;
      if (count >= 0 && count <= 50000) {
         if (count > (this.requestBuffer.size() >> 3) - 1L) {
            count = (int)((this.requestBuffer.size() >> 3) - 1L);
            MemoryUtil.memPutInt(ptr - 8L, count);
         }

         if (count != 0) {
            this.nodeManager.submitRequestBatch(new MemoryBuffer(count * 8L + 8L).cpyFrom(ptr - 8L));
         }
      } else {
         Logger.error(new IllegalStateException("Count unexpected extreme value: " + count + " things may get weird"));
      }
   }

   public GlBuffer getNodeBuffer() {
      return this.nodeBuffer;
   }

   public void free() {
      this.traversal.free();
      this.requestBuffer.free();
      this.nodeBuffer.free();
      this.uniformBuffer.free();
      this.statisticsBuffer.free();
      this.queueMetaBuffer.free();
      this.topNodeIds.free();
      this.scratchQueueA.free();
      this.scratchQueueB.free();
      GL45.glDeleteSamplers(this.hizSampler);
   }

   public static enum TraversalMode {
      MAIN(7),
      SHADOW(0);

      private final int flags;

      private TraversalMode(int flags) {
         this.flags = flags;
      }
   }
}
