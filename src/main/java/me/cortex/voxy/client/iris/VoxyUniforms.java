package me.cortex.voxy.client.iris;

import java.util.function.Supplier;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

public class VoxyUniforms {
   public static Matrix4f getViewProjection() {
      IGetVoxyRenderSystem getVrs = (IGetVoxyRenderSystem)Minecraft.getInstance().levelRenderer;
      if (getVrs != null && getVrs.getVoxyRenderSystem() != null) {
         VoxyRenderSystem vrs = getVrs.getVoxyRenderSystem();
         return new Matrix4f(vrs.getViewport().MVP);
      } else {
         return new Matrix4f();
      }
   }

   public static Matrix4f getModelView() {
      IGetVoxyRenderSystem getVrs = (IGetVoxyRenderSystem)Minecraft.getInstance().levelRenderer;
      if (getVrs != null && getVrs.getVoxyRenderSystem() != null) {
         VoxyRenderSystem vrs = getVrs.getVoxyRenderSystem();
         return new Matrix4f(vrs.getViewport().modelView);
      } else {
         return new Matrix4f();
      }
   }

   public static Matrix4f getProjection() {
      IGetVoxyRenderSystem getVrs = (IGetVoxyRenderSystem)Minecraft.getInstance().levelRenderer;
      if (getVrs != null && getVrs.getVoxyRenderSystem() != null) {
         VoxyRenderSystem vrs = getVrs.getVoxyRenderSystem();
         Matrix4f mat = vrs.getViewport().projection;
         return mat == null ? new Matrix4f() : new Matrix4f(mat);
      } else {
         return new Matrix4f();
      }
   }

   public static void addUniforms(UniformHolder uniforms) {
      uniforms.uniform1i(UniformUpdateFrequency.PER_FRAME, "vxRenderDistance", () -> VoxyConfig.CONFIG.sectionRenderDistance * 32)
         .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxViewProj", VoxyUniforms::getViewProjection)
         .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxViewProjInv", new VoxyUniforms.Inverted(VoxyUniforms::getViewProjection))
         .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxViewProjPrev", new VoxyUniforms.PreviousMat(VoxyUniforms::getViewProjection))
         .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxModelView", VoxyUniforms::getModelView)
         .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxModelViewInv", new VoxyUniforms.Inverted(VoxyUniforms::getModelView))
         .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxModelViewPrev", new VoxyUniforms.PreviousMat(VoxyUniforms::getModelView))
         .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxProj", VoxyUniforms::getProjection)
         .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxProjInv", new VoxyUniforms.Inverted(VoxyUniforms::getProjection))
         .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "vxProjPrev", new VoxyUniforms.PreviousMat(VoxyUniforms::getProjection));
      if (IrisShaderPatch.IMPERSONATE_DISTANT_HORIZONS) {
         uniforms.uniform1f(UniformUpdateFrequency.PER_FRAME, "dhNearPlane", () -> 16)
            .uniform1f(UniformUpdateFrequency.PER_FRAME, "dhFarPlane", () -> 48000)
            .uniform1i(UniformUpdateFrequency.PER_FRAME, "dhRenderDistance", () -> VoxyConfig.CONFIG.sectionRenderDistance * 32 * 16)
            .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "dhProjection", VoxyUniforms::getProjection)
            .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "dhProjectionInverse", new VoxyUniforms.Inverted(VoxyUniforms::getProjection))
            .uniformMatrix(UniformUpdateFrequency.PER_FRAME, "dhPreviousProjection", new VoxyUniforms.PreviousMat(VoxyUniforms::getProjection));
      }
   }

   private record Inverted(Supplier<Matrix4fc> parent) implements Supplier<Matrix4fc> {
      public Matrix4fc get() {
         Matrix4f copy = new Matrix4f(this.parent.get());
         copy.invert();
         return copy;
      }
   }

   private static class PreviousMat implements Supplier<Matrix4fc> {
      private final Supplier<Matrix4fc> parent;
      private Matrix4f previous;

      PreviousMat(Supplier<Matrix4fc> parent) {
         this.parent = parent;
         this.previous = new Matrix4f();
      }

      public Matrix4fc get() {
         Matrix4f previous = this.previous;
         this.previous = new Matrix4f(this.parent.get());
         return previous;
      }
   }
}
