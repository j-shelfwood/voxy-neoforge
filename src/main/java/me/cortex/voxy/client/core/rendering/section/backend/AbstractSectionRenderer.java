package me.cortex.voxy.client.core.rendering.section.backend;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.model.ModelStore;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Direction;

public abstract class AbstractSectionRenderer<T extends Viewport<T>, J extends IGeometryData> {
   protected final J geometryManager;
   protected final ModelStore modelStore;

   protected AbstractSectionRenderer(ModelStore modelStore, J geometryManager) {
      this.geometryManager = geometryManager;
      this.modelStore = modelStore;
   }

   public abstract void renderOpaque(T var1);

   public abstract void buildDrawCalls(T var1);

   public abstract void renderTemporal(T var1);

   public abstract void renderTranslucent(T var1);

   public void buildShadowDrawCalls(T viewport) {
   }

   public void renderShadow(T viewport) {
   }

   public abstract T createViewport();

   public abstract void free();

   public J getGeometryManager() {
      return this.geometryManager;
   }

   public void addDebug(List<String> lines) {
   }

   protected static void addDirectionalFaceTint(Shader.Builder<?> builder, ClientLevel cl) {
      builder.define("NO_SHADE_FACE_TINT", cl.getShade(Direction.UP, false));
      builder.define("UP_FACE_TINT", cl.getShade(Direction.UP, true));
      builder.define("DOWN_FACE_TINT", cl.getShade(Direction.DOWN, true));
      builder.define("Z_AXIS_FACE_TINT", cl.getShade(Direction.NORTH, true));
      builder.define("X_AXIS_FACE_TINT", cl.getShade(Direction.EAST, true));
   }

   protected static Shader tryCompilePatchedOrNormal(Shader.Builder<?> builder, String shader, String original) {
      boolean patched = shader != original;

      try {
         return builder.clone().defineIf("PATCHED_SHADER", patched).addSource(ShaderType.FRAGMENT, shader).compile();
      } catch (RuntimeException var5) {
         if (patched) {
            Logger.error("Failed to compile shader patch, using normal pipeline to prevent errors", var5);
            return tryCompilePatchedOrNormal(builder, original, original);
         } else {
            throw var5;
         }
      }
   }

   public record Factory<VIEWPORT extends Viewport<VIEWPORT>, GEODATA extends IGeometryData>(
      Class<? extends AbstractSectionRenderer<VIEWPORT, GEODATA>> clz, AbstractSectionRenderer.FactoryConstructor<VIEWPORT, GEODATA> constructor
   ) {
      public AbstractSectionRenderer<VIEWPORT, GEODATA> create(AbstractRenderPipeline pipeline, ModelStore store, IGeometryData geometryData) {
         return this.constructor.create(pipeline, store, (GEODATA)geometryData);
      }

      public static <VIEWPORT2 extends Viewport<VIEWPORT2>, GEODATA2 extends IGeometryData> AbstractSectionRenderer.Factory<VIEWPORT2, GEODATA2> create(
         Class<? extends AbstractSectionRenderer<VIEWPORT2, GEODATA2>> clz
      ) {
         Constructor<?>[] constructors = clz.getConstructors();
         if (constructors.length != 1) {
            Logger.error("Render backend " + clz.getCanonicalName() + " had more then 1 constructor");
            return null;
         } else {
            Constructor<?> constructor = constructors[0];
            Class<?>[] params = constructor.getParameterTypes();
            if (params.length == 3
               && params[0] == AbstractRenderPipeline.class
               && params[1] == ModelStore.class
               && IGeometryData.class.isAssignableFrom(params[2])) {
               return new AbstractSectionRenderer.Factory<>(clz, (a, b, c) -> {
                  try {
                     return (AbstractSectionRenderer<VIEWPORT2, GEODATA2>)constructor.newInstance(a, b, c);
                  } catch (IllegalAccessException | InvocationTargetException | InstantiationException var5) {
                     throw new RuntimeException(var5);
                  }
               });
            } else {
               Logger.error("Render backend " + clz.getCanonicalName() + " had invalid constructor");
               return null;
            }
         }
      }
   }

   public interface FactoryConstructor<VIEWPORT extends Viewport<VIEWPORT>, GEODATA extends IGeometryData> {
      AbstractSectionRenderer<VIEWPORT, GEODATA> create(AbstractRenderPipeline var1, ModelStore var2, GEODATA var3);
   }
}
