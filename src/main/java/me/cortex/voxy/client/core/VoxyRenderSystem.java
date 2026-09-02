package me.cortex.voxy.client.core;

import com.mojang.blaze3d.platform.GlStateManager;
import java.util.Arrays;
import java.util.List;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.ChunkBoundRenderer;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.ViewportSelector;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.section.IUsesMeshlets;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL45C;

public class VoxyRenderSystem {
   private final WorldEngine worldIn;
   private final ModelBakerySubsystem modelService;
   private final RenderGenerationService renderGen;
   private final IGeometryData geometryData;
   private final AsyncNodeManager nodeManager;
   private final NodeCleaner nodeCleaner;
   private final HierarchicalOcclusionTraverser traversal;
   private final RenderDistanceTracker renderDistanceTracker;
   public final ChunkBoundRenderer chunkBoundRenderer;
   private final ViewportSelector<?> viewportSelector;
   private final AbstractRenderPipeline pipeline;
   private int shadowDebugLogCount;
   private boolean shadowGuardLogged;
   private boolean shadowCasterDisabled;

   private static AbstractSectionRenderer.Factory<?, ? extends IGeometryData> getRenderBackendFactory() {
      return MDICSectionRenderer.FACTORY;
   }

   public VoxyRenderSystem(WorldEngine world, ServiceManager sm) {
      world.acquireRef();
      System.gc();
      if (Minecraft.getInstance().options.getEffectiveRenderDistance() < 3) {
         Logger.warn("Having a vanilla render distance of 2 can cause rare culling near the edge of your screen issues, please use 3 or more");
      }

      int[] oldBufferBindings = new int[10];

      for (int i = 0; i < oldBufferBindings.length; i++) {
         oldBufferBindings[i] = GL30C.glGetIntegeri(37075, i);
      }

      try {
         GL30C.glFinish();
         GL30C.glFinish();
         this.worldIn = world;
         long geometryCapacity = getGeometryBufferSize();
         AbstractSectionRenderer.Factory<? extends Viewport<?>, ?> backendFactory = (AbstractSectionRenderer.Factory<? extends Viewport<?>, ?>)getRenderBackendFactory();
         this.modelService = new ModelBakerySubsystem(world.getMapper());
         this.renderGen = new RenderGenerationService(world, this.modelService, sm, IUsesMeshlets.class.isAssignableFrom(backendFactory.clz()));
         this.geometryData = new BasicSectionGeometryData(1048576, geometryCapacity);
         this.nodeManager = new AsyncNodeManager(2097152, this.geometryData, this.renderGen);
         this.nodeCleaner = new NodeCleaner(this.nodeManager);
         this.traversal = new HierarchicalOcclusionTraverser(this.nodeManager, this.nodeCleaner, this.renderGen);
         world.setDirtyCallback(this.nodeManager::worldEvent);
         Arrays.stream(world.getMapper().getBiomeEntries()).forEach(this.modelService::addBiome);
         world.getMapper().setBiomeCallback(this.modelService::addBiome);
         this.nodeManager.start();
         this.pipeline = RenderPipelineFactory.createPipeline(this.nodeManager, this.nodeCleaner, this.traversal, this::frexStillHasWork);
         this.pipeline.setupExtraModelBakeryData(this.modelService);
         AbstractSectionRenderer<? extends Viewport<?>, ?> sectionRenderer = (AbstractSectionRenderer<? extends Viewport<?>, ?>)backendFactory.create(
            this.pipeline, this.modelService.getStore(), this.geometryData
         );
         this.pipeline.setSectionRenderer(sectionRenderer);
         this.viewportSelector = new ViewportSelector<>(sectionRenderer::createViewport);
         Level level = Minecraft.getInstance().level;
         int minSec = level.getMinSection() >> 5;
         int maxSec = level.getMaxSection() - 1 >> 5;
         this.renderDistanceTracker = new RenderDistanceTracker(20, minSec, maxSec, this.nodeManager::addTopLevel, this.nodeManager::removeTopLevel);
         this.setRenderDistance(VoxyConfig.CONFIG.sectionRenderDistance);
         this.chunkBoundRenderer = new ChunkBoundRenderer(this.pipeline);
         Logger.info(
            "Voxy render system created with "
               + geometryCapacity
               + " geometry capacity, using pipeline '"
               + this.pipeline.getClass().getSimpleName()
               + "' with renderer '"
               + sectionRenderer.getClass().getSimpleName()
               + "'"
         );
      } catch (RuntimeException var11) {
         world.releaseRef();
         throw var11;
      }

      for (int i = 0; i < oldBufferBindings.length; i++) {
         GL30C.glBindBufferBase(37074, i, oldBufferBindings[i]);
      }

      for (int i = 0; i < 12; i++) {
         GlStateManager._activeTexture(33984 + i);
         GlStateManager._bindTexture(0);
         GL33.glBindSampler(i, 0);
      }
   }

   public Viewport<?> setupViewport(ChunkRenderMatrices matrices, double cameraX, double cameraY, double cameraZ) {
      int[] dims = new int[4];
      GL11.glGetIntegerv(2978, dims);
      return this.setupViewport(matrices, cameraX, cameraY, cameraZ, dims[2], dims[3]);
   }

   public Viewport<?> setupViewport(ChunkRenderMatrices matrices, double cameraX, double cameraY, double cameraZ, int width, int height) {
      Viewport<? extends Viewport<?>> viewport = (Viewport<? extends Viewport<?>>)this.getViewport();
      if (viewport == null) {
         return null;
      } else {
         Matrix4f projection = computeProjectionMat(matrices.projection());
         float[] factor = this.pipeline.getRenderScalingFactor();
         if (factor != null) {
            width = (int)(width * factor[0]);
            height = (int)(height * factor[1]);
         }

         viewport.setVanillaProjection(matrices.projection())
            .setProjection(projection)
            .setModelView(new Matrix4f(matrices.modelView()))
            .setCamera(cameraX, cameraY, cameraZ)
            .setScreenSize(width, height)
            .update();
         if (VoxyClient.getOcclusionDebugState() == 0) {
            viewport.frameId++;
         }

         return viewport;
      }
   }

   public void renderShadow(Matrix4fc projection, Matrix4fc modelView, double cameraX, double cameraY, double cameraZ, int resolution, float shadowDistance) {
      if (this.pipeline instanceof IrisVoxyRenderPipeline irisPipeline) {
         if (!irisPipeline.supportsShadowCaster()) {
            this.logShadowGuard("shaderpack compatibility check failed");
         } else if (this.shadowCasterDisabled) {
            this.logShadowGuard("caster disabled after an earlier render failure");
         } else if (resolution <= 0) {
            this.logShadowGuard("invalid resolution=" + resolution);
         } else {
            Viewport<?> viewport = this.viewportSelector.getShadowViewport();
            viewport.setVanillaProjection(projection)
               .setProjection(new Matrix4f(projection))
               .setModelView(new Matrix4f(modelView))
               .setCamera(cameraX, cameraY, cameraZ)
               .setScreenSize(resolution, resolution);
            viewport.shadowMapBias = 1.0F - 25.6F / Math.max(25.7F, shadowDistance);
            viewport.updateShadow();
            if (this.shadowDebugLogCount++ < 3) {
               Logger.info("Voxy Iris shadow caster active: " + resolution + "x" + resolution + ", distance=" + shadowDistance);
            }

            VoxyRenderSystem.ShadowGlState state = new VoxyRenderSystem.ShadowGlState();

            try {
               this.pipeline.renderShadow(viewport);
            } catch (RuntimeException var18) {
               this.shadowCasterDisabled = true;
               Logger.error("Disabling Voxy shadow caster after a render failure", var18);
            } finally {
               state.restore();
            }
         }
      } else {
         this.logShadowGuard("pipeline=" + this.pipeline.getClass().getSimpleName());
      }
   }

   private void logShadowGuard(String reason) {
      if (!this.shadowGuardLogged) {
         this.shadowGuardLogged = true;
         Logger.warn("Voxy Iris shadow caster skipped: " + reason);
      }
   }

   public void renderOpaque(Viewport<?> viewport) {
      if (viewport != null) {
         TimingStatistics.resetSamplers();
         long startTime = System.nanoTime();
         TimingStatistics.all.start();
         GPUTiming.INSTANCE.marker();
         TimingStatistics.main.start();
         int[] oldBufferBindings = new int[10];

         for (int i = 0; i < oldBufferBindings.length; i++) {
            oldBufferBindings[i] = GL30C.glGetIntegeri(37075, i);
         }

         int oldFB = GL11.glGetInteger(36006);
         int[] dims = new int[4];
         GL11.glGetIntegerv(2978, dims);
         GL30C.glViewport(0, 0, viewport.width, viewport.height);
         if (oldFB == 0) {
            throw new IllegalStateException("Cannot use the default framebuffer as cannot source from it");
         } else {
            this.pipeline.preSetup(viewport);
            TimingStatistics.E.start();
            if (!VoxyClient.disableSodiumChunkRender()) {
               this.chunkBoundRenderer.render(viewport);
            } else {
               viewport.depthBoundingBuffer.clear(0.0F);
            }

            TimingStatistics.E.stop();
            GPUTiming.INSTANCE.marker();
            this.pipeline.runPipeline(viewport, oldFB, dims[2], dims[3]);
            GPUTiming.INSTANCE.marker();
            TimingStatistics.main.stop();
            TimingStatistics.postDynamic.start();
            PrintfDebugUtil.tick();
            UploadStream.INSTANCE.tick();

            while (this.renderDistanceTracker.setCenterAndProcess(viewport.cameraX, viewport.cameraZ) && VoxyClient.isFrexActive()) {
            }

            TimingStatistics.H.start();

            do {
               this.modelService.tick(900000L);
            } while (VoxyClient.isFrexActive() && !this.modelService.areQueuesEmpty());

            TimingStatistics.H.stop();
            GPUTiming.INSTANCE.marker();
            TimingStatistics.postDynamic.stop();
            GPUTiming.INSTANCE.tick();
            GL30C.glBindFramebuffer(36160, oldFB);
            GL30C.glViewport(dims[0], dims[1], dims[2], dims[3]);
            GL30C.glUseProgram(0);
            GL30C.glEnable(2929);
            GlStateManager._glBindVertexArray(0);
            GlStateManager._activeTexture(33985);

            for (int i = 0; i < 12; i++) {
               GlStateManager._activeTexture(33984 + i);
               GlStateManager._bindTexture(0);
               GL33.glBindSampler(i, 0);
            }

            for (int i = 0; i < oldBufferBindings.length; i++) {
               GL30C.glBindBufferBase(37074, i, oldBufferBindings[i]);
            }

            TimingStatistics.all.stop();
         }
      }
   }

   private void autoBalanceSubDivSize() {
      boolean canDecreaseSize = this.renderGen.getTaskCount() < 300;
      int MIN_FPS = 55;
      int MAX_FPS = 65;
      float INCREASE_PER_SECOND = 60.0F;
      float DECREASE_PER_SECOND = 30.0F;
      if (Minecraft.getInstance().getFps() < MIN_FPS) {
         VoxyConfig.CONFIG.subDivisionSize = Math.min(
            VoxyConfig.CONFIG.subDivisionSize + INCREASE_PER_SECOND / Math.max(1.0F, (float)Minecraft.getInstance().getFps()), 256.0F
         );
      }

      if (MAX_FPS < Minecraft.getInstance().getFps() && canDecreaseSize) {
         VoxyConfig.CONFIG.subDivisionSize = Math.max(
            VoxyConfig.CONFIG.subDivisionSize - DECREASE_PER_SECOND / Math.max(1.0F, (float)Minecraft.getInstance().getFps()), 28.0F
         );
      }
   }

   private static Matrix4f makeProjectionMatrix(float near, float far) {
      Matrix4f projection = new Matrix4f();
      Minecraft client = Minecraft.getInstance();
      GameRenderer gameRenderer = client.gameRenderer;
      float fov = ((Integer)client.options.fov().get()).floatValue();
      projection.setPerspective(fov * (float) (Math.PI / 180.0), (float)client.getWindow().getWidth() / client.getWindow().getHeight(), near, far);
      return projection;
   }

   private static Matrix4f computeProjectionMat(Matrix4fc base) {
      float nearVoxy = Minecraft.getInstance().gameRenderer.getRenderDistance() <= 32.0F ? 8.0F : 16.0F;
      nearVoxy = VoxyClient.disableSodiumChunkRender() ? 0.1F : nearVoxy;
      return base.mulLocal(makeProjectionMatrix(0.05F, Minecraft.getInstance().gameRenderer.getDepthFar()).invert(), new Matrix4f())
         .mulLocal(makeProjectionMatrix(nearVoxy, 48000.0F));
   }

   private boolean frexStillHasWork() {
      if (!VoxyClient.isFrexActive()) {
         return false;
      } else {
         UploadStream.INSTANCE.tick();
         this.modelService.tick(100000000L);
         GL11.glFinish();
         return this.nodeManager.hasWork() || this.renderGen.getTaskCount() != 0 || !this.modelService.areQueuesEmpty();
      }
   }

   public void setRenderDistance(int renderDistance) {
      this.renderDistanceTracker.setRenderDistance(renderDistance);
   }

   public Viewport<?> getViewport() {
      return this.viewportSelector.getViewport();
   }

   public boolean isUsingIrisPipeline() {
      return this.pipeline instanceof IrisVoxyRenderPipeline;
   }

   public void addDebugInfo(List<String> debug) {
      debug.add(
         "Buf/Tex [#/Mb]: ["
            + GlBuffer.getCount()
            + "/"
            + GlBuffer.getTotalSize() / 1000000L
            + "],["
            + GlTexture.getCount()
            + "/"
            + GlTexture.getEstimatedTotalSize() / 1000000L
            + "]"
      );
      this.modelService.addDebugData(debug);
      this.renderGen.addDebugData(debug);
      this.nodeManager.addDebug(debug);
      this.pipeline.addDebug(debug);
      TimingStatistics.update();
      debug.add(
         "Voxy frame runtime (millis): "
            + TimingStatistics.dynamic.pVal()
            + ", "
            + TimingStatistics.main.pVal()
            + ", "
            + TimingStatistics.postDynamic.pVal()
            + ", "
            + TimingStatistics.all.pVal()
      );
      debug.add(
         "Extra time: " + TimingStatistics.A.pVal() + ", " + TimingStatistics.B.pVal() + ", " + TimingStatistics.C.pVal() + ", " + TimingStatistics.D.pVal()
      );
      debug.add(
         "Extra 2 time: "
            + TimingStatistics.E.pVal()
            + ", "
            + TimingStatistics.F.pVal()
            + ", "
            + TimingStatistics.G.pVal()
            + ", "
            + TimingStatistics.H.pVal()
            + ", "
            + TimingStatistics.I.pVal()
      );
      debug.add(GPUTiming.INSTANCE.getDebug());
      PrintfDebugUtil.addToOut(debug);
   }

   public void shutdown() {
      Logger.info("Flushing download stream");
      DownloadStream.INSTANCE.flushWaitClear();
      Logger.info("Shutting down rendering");

      try {
         this.worldIn.setDirtyCallback(null);
         this.worldIn.getMapper().setBiomeCallback(null);
         this.worldIn.getMapper().setStateCallback(null);
         this.nodeManager.stop();
         this.modelService.shutdown();
         this.renderGen.shutdown();
         this.traversal.free();
         this.nodeCleaner.free();
         this.geometryData.free();
         this.chunkBoundRenderer.free();
         this.viewportSelector.free();
      } catch (Exception var3) {
         Logger.error("Error shutting down renderer components", var3);
      }

      Logger.info("Shutting down render pipeline");

      try {
         this.pipeline.free();
      } catch (Exception var2) {
         Logger.error("Error releasing render pipeline", var2);
      }

      Logger.info("Flushing download stream");
      DownloadStream.INSTANCE.flushWaitClear();
      this.worldIn.releaseRef();
      Logger.info("Render shutdown completed");
   }

   private static long getGeometryBufferSize() {
      long geometryCapacity = Math.min(1L << 64 - Long.numberOfLeadingZeros(Capabilities.INSTANCE.ssboMaxSize - 1L) << 1, 4294967296L) - 1024L;
      if (Capabilities.INSTANCE.isIntel) {
         geometryCapacity = Math.max(geometryCapacity, 1073741824L);
      }

      if (Capabilities.INSTANCE.canQueryGpuMemory) {
         long limit = Capabilities.INSTANCE.getFreeDedicatedGpuMemory() - 1610612736L;
         limit = Math.max(536870912L, limit);
         geometryCapacity = Math.min(geometryCapacity, limit);
      }

      String override = System.getProperty("voxy.geometryBufferSizeOverrideMB", "");
      if (!override.isEmpty()) {
         geometryCapacity = Long.parseLong(override) * 1024L * 1024L;
      }

      return geometryCapacity;
   }

   public WorldEngine getEngine() {
      return this.worldIn;
   }

   private static final class ShadowGlState {
      private static final int TEXTURE_UNIT_COUNT = 3;
      private static final int BUFFER_BINDING_COUNT = 12;
      private final int drawFramebuffer = GL30C.glGetInteger(36006);
      private final int readFramebuffer = GL30C.glGetInteger(36010);
      private final int program = GL30C.glGetInteger(35725);
      private final int vertexArray = GL30C.glGetInteger(34229);
      private final int activeTexture = GL30C.glGetInteger(34016);
      private final int drawIndirectBuffer = GL30C.glGetInteger(36675);
      private final int dispatchIndirectBuffer = GL30C.glGetInteger(37103);
      private final int parameterBuffer = GL30C.glGetInteger(33007);
      private final int depthFunction = GL30C.glGetInteger(2932);
      private final int cullFaceMode = GL30C.glGetInteger(2885);
      private final int frontFace = GL30C.glGetInteger(2886);
      private final int provokingVertex = GL30C.glGetInteger(36431);
      private final boolean depthTest = GL30C.glIsEnabled(2929);
      private final boolean cullFace = GL30C.glIsEnabled(2884);
      private final boolean blend = GL30C.glIsEnabled(3042);
      private final boolean depthMask = GL30C.glGetBoolean(2930);
      private final int[] viewport = new int[4];
      private final int[] colorMask = new int[4];
      private final int[] textures = new int[3];
      private final int[] samplers = new int[3];
      private final int uniformBuffer;
      private final int[] shaderStorageBuffers = new int[12];

      private ShadowGlState() {
         GL11.glGetIntegerv(2978, this.viewport);
         GL11.glGetIntegerv(3107, this.colorMask);

         for (int i = 0; i < 3; i++) {
            GL13C.glActiveTexture(33984 + i);
            this.textures[i] = GL30C.glGetInteger(32873);
            this.samplers[i] = GL30C.glGetIntegeri(35097, i);
         }

         GL13C.glActiveTexture(this.activeTexture);
         this.uniformBuffer = GL30C.glGetIntegeri(35368, 0);

         for (int i = 0; i < 12; i++) {
            this.shaderStorageBuffers[i] = GL30C.glGetIntegeri(37075, i);
         }
      }

      private void restore() {
         GL30C.glBindFramebuffer(36009, this.drawFramebuffer);
         GL30C.glBindFramebuffer(36008, this.readFramebuffer);
         GL30C.glViewport(this.viewport[0], this.viewport[1], this.viewport[2], this.viewport[3]);
         GL30C.glUseProgram(this.program);
         GL30C.glBindVertexArray(this.vertexArray);
         GL15C.glBindBuffer(36671, this.drawIndirectBuffer);
         GL15C.glBindBuffer(37102, this.dispatchIndirectBuffer);
         GL15C.glBindBuffer(33006, this.parameterBuffer);
         GL30C.glBindBufferBase(35345, 0, this.uniformBuffer);

         for (int i = 0; i < 12; i++) {
            GL30C.glBindBufferBase(37074, i, this.shaderStorageBuffers[i]);
         }

         for (int i = 0; i < 3; i++) {
            GL45C.glBindTextureUnit(i, this.textures[i]);
            GL33C.glBindSampler(i, this.samplers[i]);
         }

         GL13C.glActiveTexture(this.activeTexture);
         GL30C.glDepthFunc(this.depthFunction);
         GL30C.glCullFace(this.cullFaceMode);
         GL30C.glFrontFace(this.frontFace);
         GL32C.glProvokingVertex(this.provokingVertex);
         GL30C.glDepthMask(this.depthMask);
         GL30C.glColorMask(this.colorMask[0] != 0, this.colorMask[1] != 0, this.colorMask[2] != 0, this.colorMask[3] != 0);
         setEnabled(2929, this.depthTest);
         setEnabled(2884, this.cullFace);
         setEnabled(3042, this.blend);
      }

      private static void setEnabled(int capability, boolean enabled) {
         if (enabled) {
            GL30C.glEnable(capability);
         } else {
            GL30C.glDisable(capability);
         }
      }
   }
}
