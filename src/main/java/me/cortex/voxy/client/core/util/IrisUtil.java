package me.cortex.voxy.client.core.util;

import java.io.IOException;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.neoforged.fml.ModList;

public class IrisUtil {
   public static IrisUtil.CapturedViewportParameters CAPTURED_VIEWPORT_PARAMETERS;
   public static final boolean IRIS_INSTALLED = ModList.get().isLoaded("iris");
   public static final boolean SHADER_SUPPORT = true;

   private static boolean irisShadowActive0() {
      return ShadowRenderer.ACTIVE;
   }

   public static boolean irisShadowActive() {
      return IRIS_INSTALLED && irisShadowActive0();
   }

   public static int irisShadowResolution() {
      return ShadowRenderer.RESOLUTION;
   }

   public static void clearIrisSamplers() {
      if (IRIS_INSTALLED) {
         clearIrisSamplers0();
      }
   }

   public static void reload() {
      if (IRIS_INSTALLED) {
         reload0();
      }
   }

   private static void reload0() {
      try {
         if (IrisApi.getInstance().isShaderPackInUse()) {
            Iris.reload();
         }
      } catch (IOException var1) {
         throw new RuntimeException(var1);
      }
   }

   private static void clearIrisSamplers0() {
      for (int i = 0; i < 16; i++) {
         IrisRenderSystem.bindSamplerToUnit(i, 0);
      }
   }

   private static boolean irisShaderPackEnabled0() {
      return Iris.isPackInUseQuick();
   }

   public static boolean irisShaderPackEnabled() {
      return IRIS_INSTALLED && irisShaderPackEnabled0();
   }

   public static void disableIrisShaders() {
      if (IRIS_INSTALLED) {
         disableIrisShaders0();
      }
   }

   private static void disableIrisShaders0() {
      IrisApi.getInstance().getConfig().setShadersEnabledAndApply(false);
   }

   public record CapturedViewportParameters(ChunkRenderMatrices matrices, double x, double y, double z) {
      public Viewport<?> apply(VoxyRenderSystem vrs) {
         return vrs.setupViewport(this.matrices, this.x, this.y, this.z);
      }

      public Viewport<?> apply(VoxyRenderSystem vrs, int width, int height) {
         return vrs.setupViewport(this.matrices, this.x, this.y, this.z, width, height);
      }
   }
}
