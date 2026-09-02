package me.cortex.voxy.client.core;

import java.util.List;
import java.util.function.BooleanSupplier;
import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.common.util.TrackedObject;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

public abstract class AbstractRenderPipeline extends TrackedObject {
   private final BooleanSupplier frexStillHasWork;
   private final AsyncNodeManager nodeManager;
   private final NodeCleaner nodeCleaner;
   private final HierarchicalOcclusionTraverser traversal;
   protected AbstractSectionRenderer<?, ?> sectionRenderer;
   private final FullscreenBlit depthMaskBlit = new FullscreenBlit("voxy:post/fullscreen2.vert", "voxy:post/noop.frag");
   private final FullscreenBlit depthSetBlit = new FullscreenBlit("voxy:post/fullscreen2.vert", "voxy:post/depth0.frag");
   private final FullscreenBlit depthCopy = new FullscreenBlit("voxy:post/fullscreen2.vert", "voxy:post/depth_copy.frag");
   public final DepthFramebuffer fb = new DepthFramebuffer(35056);
   protected final boolean deferTranslucency;
   private static final int DEPTH_SAMPLER = GL42.glGenSamplers();
   private static final long SCRATCH = MemoryUtil.nmemAlloc(64L);

   protected AbstractRenderPipeline(
      AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier, boolean deferTranslucency
   ) {
      this.frexStillHasWork = frexSupplier;
      this.nodeManager = nodeManager;
      this.nodeCleaner = nodeCleaner;
      this.traversal = traversal;
      this.deferTranslucency = deferTranslucency;
   }

   public void setupExtraModelBakeryData(ModelBakerySubsystem modelService) {
   }

   public final void setSectionRenderer(AbstractSectionRenderer<?, ?> sectionRenderer) {
      if (this.sectionRenderer != null) {
         throw new IllegalStateException();
      } else {
         this.sectionRenderer = sectionRenderer;
      }
   }

   public void preSetup(Viewport<?> viewport) {
   }

   protected abstract int setup(Viewport<?> var1, int var2, int var3, int var4);

   protected abstract void postOpaquePreTranslucent(Viewport<?> var1);

   protected void finish(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
      GL11C.glDisable(2960);
      GL30C.glBindFramebuffer(36160, sourceFrameBuffer);
   }

   public void runPipeline(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
      int depthTexture = this.setup(viewport, sourceFrameBuffer, srcWidth, srcHeight);
      AbstractSectionRenderer rs = this.sectionRenderer;
      rs.renderOpaque(viewport);
      int occlusionDebug = VoxyClient.getOcclusionDebugState();
      if (occlusionDebug == 0) {
         this.innerPrimaryWork(viewport, depthTexture);
      }

      if (occlusionDebug <= 1) {
         rs.buildDrawCalls(viewport);
      }

      rs.renderTemporal(viewport);
      this.postOpaquePreTranslucent(viewport);
      if (!this.deferTranslucency) {
         rs.renderTranslucent(viewport);
      }

      this.finish(viewport, sourceFrameBuffer, srcWidth, srcHeight);
      GL30C.glBindFramebuffer(36160, sourceFrameBuffer);
   }

   protected void initDepthStencil(int sourceFrameBuffer, int targetFb, int srcWidth, int srcHeight, int width, int height) {
      GL45.glClearNamedFramebufferfi(targetFb, 34041, 0, 1.0F, 1);
      GL30C.glBindFramebuffer(36160, targetFb);
      this.depthCopy.bind();
      int depthTexture = GL45.glGetNamedFramebufferAttachmentParameteri(sourceFrameBuffer, 36096, 36049);
      GL45C.glBindTextureUnit(0, depthTexture);
      GL42.glBindSampler(0, DEPTH_SAMPLER);
      GL42.glUniform2f(1, (float)width / srcWidth, (float)height / srcHeight);
      GL11C.glColorMask(false, false, false, false);
      this.depthCopy.blit();
      GL11C.glEnable(2960);
      GL11C.glStencilOp(7680, 7680, 7681);
      GL11C.glStencilFunc(519, 0, 255);
      GL11C.glStencilMask(255);
      GL11C.glEnable(2929);
      GL42.glDepthFunc(517);
      this.depthMaskBlit.blit();
      GL11C.glDisable(2929);
      GL11C.glStencilOp(7680, 7680, 7680);
      GL11C.glStencilFunc(514, 0, 255);
      this.depthSetBlit.blit();
      GL42.glDepthFunc(515);
      GL11C.glColorMask(true, true, true, true);
      GL11C.glStencilOp(7680, 7680, 7680);
      GL11C.glStencilFunc(514, 1, 255);
   }

   protected static void transformBlitDepth(FullscreenBlit blitShader, int srcDepthTex, int dstFB, Viewport<?> viewport, Matrix4f targetTransform) {
      GL11C.glDisable(2960);
      GL30C.glBindFramebuffer(36160, dstFB);
      blitShader.bind();
      GL45C.glBindTextureUnit(0, srcDepthTex);
      new Matrix4f(viewport.MVP).invert().getToAddress(SCRATCH);
      GL42.nglUniformMatrix4fv(1, 1, false, SCRATCH);
      targetTransform.getToAddress(SCRATCH);
      GL42.nglUniformMatrix4fv(2, 1, false, SCRATCH);
      GL11C.glEnable(2929);
      blitShader.blit();
      GL11C.glDisable(2960);
      GL11C.glDisable(2929);
   }

   protected void innerPrimaryWork(Viewport<?> viewport, int depthBuffer) {
      viewport.hiZBuffer.buildMipChain(depthBuffer, viewport.width, viewport.height);

      do {
         TimingStatistics.main.stop();
         TimingStatistics.dynamic.start();
         TimingStatistics.D.start();
         DownloadStream.INSTANCE.tick();
         TimingStatistics.D.stop();
         this.nodeManager.tick(this.traversal.getNodeBuffer(), this.nodeCleaner);
         this.nodeCleaner.tick(this.traversal.getNodeBuffer());
         TimingStatistics.dynamic.stop();
         TimingStatistics.main.start();
         GL42.glMemoryBarrier(1152);
         TimingStatistics.F.start();
         this.traversal.doTraversal(viewport);
         TimingStatistics.F.stop();
      } while (this.frexStillHasWork.getAsBoolean());
   }

   @Override
   protected void free0() {
      this.fb.free();
      this.sectionRenderer.free();
      this.depthMaskBlit.delete();
      this.depthSetBlit.delete();
      this.depthCopy.delete();
      super.free0();
   }

   public void addDebug(List<String> debug) {
      this.sectionRenderer.addDebug(debug);
      RenderStatistics.addDebug(debug);
   }

   public abstract void setupAndBindOpaque(Viewport<?> var1);

   public abstract void setupAndBindTranslucent(Viewport<?> var1);

   public void renderShadow(Viewport<?> viewport) {
      this.traversal.doTraversal(viewport, HierarchicalOcclusionTraverser.TraversalMode.SHADOW);
      AbstractSectionRenderer renderer = this.sectionRenderer;
      renderer.buildShadowDrawCalls(viewport);
      renderer.renderShadow(viewport);
   }

   public void bindUniforms() {
      this.bindUniforms(-1);
   }

   public void bindUniforms(int index) {
   }

   public String taaFunction(String functionName) {
      return this.taaFunction(-1, functionName);
   }

   public String taaFunction(int uboBindingPoint, String functionName) {
      return null;
   }

   public String patchOpaqueShader(AbstractSectionRenderer<?, ?> renderer, String input) {
      return null;
   }

   public String patchTranslucentShader(AbstractSectionRenderer<?, ?> renderer, String input) {
      return null;
   }

   public float[] getRenderScalingFactor() {
      return null;
   }

   static {
      GL42.glSamplerParameteri(DEPTH_SAMPLER, 10240, 9728);
      GL42.glSamplerParameteri(DEPTH_SAMPLER, 10241, 9728);
   }
}
