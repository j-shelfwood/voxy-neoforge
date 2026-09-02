package me.cortex.voxy.client.core;

import java.util.List;
import java.util.function.BooleanSupplier;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import me.cortex.voxy.common.Logger;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45C;

public class IrisVoxyRenderPipeline extends AbstractRenderPipeline {
   private final IrisVoxyRenderPipelineData data;
   private final FullscreenBlit depthBlit = new FullscreenBlit("voxy:post/blit_texture_depth_cutout.frag");
   public final DepthFramebuffer fbTranslucent = new DepthFramebuffer(this.fb.getFormat());
   private final GlBuffer shaderUniforms;
   private static final int UNIFORM_BINDING_POINT = 5;

   public IrisVoxyRenderPipeline(
      IrisVoxyRenderPipelineData data,
      AsyncNodeManager nodeManager,
      NodeCleaner nodeCleaner,
      HierarchicalOcclusionTraverser traversal,
      BooleanSupplier frexSupplier
   ) {
      super(nodeManager, nodeCleaner, traversal, frexSupplier, data.shouldDeferTranslucency());
      this.data = data;
      if (this.data.thePipeline != null) {
         throw new IllegalStateException("Pipeline data already bound");
      } else {
         this.data.thePipeline = this;
         int[] oDT = this.data.opaqueDrawTargets;
         int[] binding = new int[oDT.length];

         for (int i = 0; i < oDT.length; i++) {
            binding[i] = 36064 + i;
            GL45C.glNamedFramebufferTexture(this.fb.framebuffer.id, 36064 + i, oDT[i], 0);
         }

         GL45C.glNamedFramebufferDrawBuffers(this.fb.framebuffer.id, binding);
         int[] tDT = this.data.translucentDrawTargets;
         binding = new int[tDT.length];

         for (int i = 0; i < tDT.length; i++) {
            binding[i] = 36064 + i;
            GL45C.glNamedFramebufferTexture(this.fbTranslucent.framebuffer.id, 36064 + i, tDT[i], 0);
         }

         GL45C.glNamedFramebufferDrawBuffers(this.fbTranslucent.framebuffer.id, binding);
         this.fb.framebuffer.verify();
         this.fbTranslucent.framebuffer.verify();
         if (data.getUniforms() != null) {
            this.shaderUniforms = new GlBuffer(data.getUniforms().size());
         } else {
            this.shaderUniforms = null;
         }
      }
   }

   @Override
   public void setupExtraModelBakeryData(ModelBakerySubsystem modelService) {
      modelService.factory.setCustomBlockStateMapping(WorldRenderingSettings.INSTANCE.getBlockStateIds());
   }

   @Override
   public void free() {
      if (this.data.thePipeline != this) {
         throw new IllegalStateException();
      } else {
         this.data.thePipeline = null;
         this.depthBlit.delete();
         this.fbTranslucent.free();
         if (this.shaderUniforms != null) {
            this.shaderUniforms.free();
         }

         super.free0();
      }
   }

   @Override
   public void preSetup(Viewport<?> viewport) {
      super.preSetup(viewport);
      if (this.shaderUniforms != null) {
         long ptr = UploadStream.INSTANCE.uploadTo(this.shaderUniforms);
         this.data.getUniforms().updater().accept(ptr);
         UploadStream.INSTANCE.commit();
      }
   }

   @Override
   protected int setup(Viewport<?> viewport, int sourceFramebuffer, int srcWidth, int srcHeight) {
      int targetWidth = GL45C.glGetTextureLevelParameteri(this.data.opaqueDrawTargets[0], 0, 4096);
      int targetHeight = GL45C.glGetTextureLevelParameteri(this.data.opaqueDrawTargets[0], 0, 4097);
      if (targetWidth > 0 && targetHeight > 0) {
         if (viewport.width != targetWidth || viewport.height != targetHeight) {
            Logger.warn(
               "Correcting Voxy viewport from " + viewport.width + "x" + viewport.height + " to Iris render target " + targetWidth + "x" + targetHeight
            );
            viewport.setScreenSize(targetWidth, targetHeight).update();
            GL45C.glViewport(0, 0, targetWidth, targetHeight);
         }

         this.fb.resize(targetWidth, targetHeight);
         this.fbTranslucent.resize(targetWidth, targetHeight);
         if (!this.data.useViewportDims) {
            srcWidth = viewport.width;
            srcHeight = viewport.height;
         }

         this.initDepthStencil(sourceFramebuffer, this.fb.framebuffer.id, srcWidth, srcHeight, viewport.width, viewport.height);
         return this.fb.getDepthTex().id;
      } else {
         throw new IllegalStateException("Iris Voxy render target has invalid dimensions: " + targetWidth + "x" + targetHeight);
      }
   }

   @Override
   protected void postOpaquePreTranslucent(Viewport<?> viewport) {
      int msk = 1280;
      GL45C.glBlitNamedFramebuffer(
         this.fb.framebuffer.id, this.fbTranslucent.framebuffer.id, 0, 0, viewport.width, viewport.height, 0, 0, viewport.width, viewport.height, msk, 9728
      );
   }

   @Override
   protected void finish(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
      if (this.data.renderToVanillaDepth && srcWidth == viewport.width && srcHeight == viewport.height) {
         GL45C.glColorMask(false, false, false, false);
         AbstractRenderPipeline.transformBlitDepth(
            this.depthBlit, this.fbTranslucent.getDepthTex().id, sourceFrameBuffer, viewport, new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView)
         );
         GL45C.glColorMask(true, true, true, true);
      } else {
         GL45C.glDisable(2960);
         GL45C.glDisable(2929);
      }
   }

   @Override
   public void bindUniforms() {
      this.bindUniforms(5);
   }

   @Override
   public void bindUniforms(int bindingPoint) {
      if (this.shaderUniforms != null) {
         GL30.glBindBufferBase(35345, bindingPoint, this.shaderUniforms.id);
      }
   }

   private void doBindings() {
      this.bindUniforms();
      if (this.data.getSsboSet() != null) {
         this.data.getSsboSet().bindingFunction().accept(10);
      }

      if (this.data.getImageSet() != null) {
         this.data.getImageSet().bindingFunction().accept(6);
      }
   }

   @Override
   public void setupAndBindOpaque(Viewport<?> viewport) {
      this.fb.bind();
      this.doBindings();
   }

   @Override
   public void setupAndBindTranslucent(Viewport<?> viewport) {
      this.fbTranslucent.bind();
      this.doBindings();
      if (this.data.getBlender() != null) {
         this.data.getBlender().run();
      }
   }

   @Override
   public void addDebug(List<String> debug) {
      debug.add("Using: " + this.getClass().getSimpleName());
      super.addDebug(debug);
   }

   public boolean supportsShadowCaster() {
      return this.data.supportsShadowCaster;
   }

   private StringBuilder buildGenericShaderHeader(AbstractSectionRenderer<?, ?> renderer, String input) {
      StringBuilder builder = new StringBuilder(input).append("\n\n\n");
      if (this.data.getUniforms() != null) {
         builder.append("layout(binding = 5, std140) uniform ShaderUniformBindings ").append(this.data.getUniforms().layout()).append(";\n\n");
      }

      if (this.data.getSsboSet() != null) {
         builder.append("#define BUFFER_BINDING_INDEX_BASE 10\n");
         builder.append(this.data.getSsboSet().layout()).append("\n\n");
      }

      if (this.data.getImageSet() != null) {
         builder.append("#define BASE_SAMPLER_BINDING_INDEX 6\n");
         builder.append(this.data.getImageSet().layout()).append("\n\n");
      }

      return builder.append("\n\n");
   }

   @Override
   public String patchOpaqueShader(AbstractSectionRenderer<?, ?> renderer, String input) {
      StringBuilder builder = this.buildGenericShaderHeader(renderer, input);
      builder.append(this.data.opaqueFragPatch());
      return builder.toString();
   }

   @Override
   public String patchTranslucentShader(AbstractSectionRenderer<?, ?> renderer, String input) {
      if (this.data.translucentFragPatch() == null) {
         return null;
      } else {
         StringBuilder builder = this.buildGenericShaderHeader(renderer, input);
         builder.append(this.data.translucentFragPatch());
         return builder.toString();
      }
   }

   @Override
   public String taaFunction(String functionName) {
      return this.taaFunction(5, functionName);
   }

   @Override
   public String taaFunction(int uboBindingPoint, String functionName) {
      StringBuilder builder = new StringBuilder();
      if (this.data.getUniforms() != null) {
         builder.append("layout(binding = " + uboBindingPoint + ", std140) uniform ShaderUniformBindings ")
            .append(this.data.getUniforms().layout())
            .append(";\n\n");
      }

      builder.append("vec2 ").append(functionName).append("()\n");
      builder.append(this.data.TAA);
      builder.append("\n");
      return builder.toString();
   }

   @Override
   public float[] getRenderScalingFactor() {
      return this.data.resolutionScale;
   }
}
