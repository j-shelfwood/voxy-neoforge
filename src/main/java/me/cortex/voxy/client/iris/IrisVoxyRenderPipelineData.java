package me.cortex.voxy.client.iris;

import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry;
import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectBidirectionalIterator;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import kroppeb.stareval.function.FunctionReturn;
import kroppeb.stareval.function.Type;
import me.cortex.voxy.client.core.IrisVoxyRenderPipeline;
import me.cortex.voxy.client.mixin.iris.CustomUniformsAccessor;
import me.cortex.voxy.client.mixin.iris.IrisRenderingPipelineAccessor;
import me.cortex.voxy.common.Logger;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.image.ImageHolder;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.state.ValueUpdateNotifier;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.gl.uniform.DynamicLocationalUniformHolder;
import net.irisshaders.iris.gl.uniform.FloatSupplier;
import net.irisshaders.iris.gl.uniform.LocationalUniformHolder;
import net.irisshaders.iris.gl.uniform.Uniform;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformType;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.uniforms.custom.cached.BooleanCachedUniform;
import net.irisshaders.iris.uniforms.custom.cached.CachedUniform;
import net.irisshaders.iris.uniforms.custom.cached.Float2VectorCachedUniform;
import net.irisshaders.iris.uniforms.custom.cached.Float3VectorCachedUniform;
import net.irisshaders.iris.uniforms.custom.cached.Float4MatrixCachedUniform;
import net.irisshaders.iris.uniforms.custom.cached.Float4VectorCachedUniform;
import net.irisshaders.iris.uniforms.custom.cached.FloatCachedUniform;
import net.irisshaders.iris.uniforms.custom.cached.Int2VectorCachedUniform;
import net.irisshaders.iris.uniforms.custom.cached.Int3VectorCachedUniform;
import net.irisshaders.iris.uniforms.custom.cached.IntCachedUniform;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.opengl.ARBUniformBufferObject;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

public class IrisVoxyRenderPipelineData {
   public IrisVoxyRenderPipeline thePipeline;
   public final int[] opaqueDrawTargets;
   public final int[] translucentDrawTargets;
   private final String opaquePatch;
   private final String translucentPatch;
   private final IrisVoxyRenderPipelineData.StructLayout uniforms;
   private final Runnable blendingSetup;
   private final IrisVoxyRenderPipelineData.ImageSet imageSet;
   private final IrisVoxyRenderPipelineData.SSBOSet ssboSet;
   public final boolean renderToVanillaDepth;
   public final float[] resolutionScale;
   public final String TAA;
   public final boolean useViewportDims;
   public final boolean deferTranslucency;
   public final boolean supportsShadowCaster;

   private IrisVoxyRenderPipelineData(
      IrisShaderPatch patch,
      int[] opaqueDrawTargets,
      int[] translucentDrawTargets,
      IrisVoxyRenderPipelineData.StructLayout uniformSet,
      Runnable blendingSetup,
      IrisVoxyRenderPipelineData.ImageSet imageSet,
      IrisVoxyRenderPipelineData.SSBOSet ssboSet
   ) {
      this.opaqueDrawTargets = opaqueDrawTargets;
      this.translucentDrawTargets = translucentDrawTargets;
      this.opaquePatch = patch.getPatchOpaqueSource();
      this.translucentPatch = patch.getPatchTranslucentSource();
      this.uniforms = uniformSet;
      this.blendingSetup = blendingSetup;
      this.imageSet = imageSet;
      this.ssboSet = ssboSet;
      this.renderToVanillaDepth = patch.emitToVanillaDepth();
      this.TAA = patch.getTAAShift();
      this.resolutionScale = patch.getRenderScale();
      this.useViewportDims = patch.useViewportDims();
      this.deferTranslucency = patch.deferedTranslucentRendering();
      this.supportsShadowCaster = patch.supportsEuphoriaShadowCaster();
   }

   public IrisVoxyRenderPipelineData.SSBOSet getSsboSet() {
      return this.ssboSet;
   }

   public IrisVoxyRenderPipelineData.ImageSet getImageSet() {
      return this.imageSet;
   }

   public IrisVoxyRenderPipelineData.StructLayout getUniforms() {
      return this.uniforms;
   }

   public Runnable getBlender() {
      return this.blendingSetup;
   }

   public String opaqueFragPatch() {
      return this.opaquePatch;
   }

   public String translucentFragPatch() {
      return this.translucentPatch;
   }

   public static IrisVoxyRenderPipelineData buildPipeline(
      IrisRenderingPipeline ipipe, IrisShaderPatch patch, CustomUniforms cu, ShaderStorageBufferHolder ssboHolder
   ) {
      IrisVoxyRenderPipelineData.StructLayout uniforms = createUniformLayoutStructAndUpdater(createUniformSet(cu, patch));
      IrisVoxyRenderPipelineData.ImageSet imageSet = createImageSet(ipipe, patch);
      IrisVoxyRenderPipelineData.SSBOSet ssboSet = createSSBOLayouts(patch.getSSBOs(), ssboHolder);
      int[] opaqueDrawTargets = getDrawBuffers(
         patch.getOpqaueTargets(), ipipe.getFlippedAfterPrepare(), ((IrisRenderingPipelineAccessor)ipipe).getRenderTargets()
      );
      int[] translucentDrawTargets = getDrawBuffers(
         patch.getTranslucentTargets(), ipipe.getFlippedAfterPrepare(), ((IrisRenderingPipelineAccessor)ipipe).getRenderTargets()
      );
      return new IrisVoxyRenderPipelineData(patch, opaqueDrawTargets, translucentDrawTargets, uniforms, patch.createBlendSetup(), imageSet, ssboSet);
   }

   private static int[] getDrawBuffers(int[] targets, ImmutableSet<Integer> stageWritesToAlt, RenderTargets rt) {
      int[] targetTextures = new int[targets.length];

      for (int i = 0; i < targets.length; i++) {
         RenderTarget target = rt.getOrCreate(targets[i]);
         int textureId = stageWritesToAlt.contains(targets[i]) ? target.getAltTexture() : target.getMainTexture();
         targetTextures[i] = textureId;
      }

      return targetTextures;
   }

   private static String convertToGlslType(UniformType type) {
      return switch (type) {
         case INT -> "int";
         case FLOAT -> "float";
         case MAT3 -> "mat3";
         case MAT4 -> "mat4";
         case VEC2 -> "vec2";
         case VEC2I -> "ivec2";
         case VEC3 -> "vec3";
         case VEC3I -> "ivec3";
         case VEC4 -> "vec4";
         case VEC4I -> "ivec4";
         default -> throw new MatchException(null, null);
      };
   }

   public boolean shouldDeferTranslucency() {
      return false;
   }

   private static IrisVoxyRenderPipelineData.StructLayout createUniformLayoutStructAndUpdater(List<IrisVoxyRenderPipelineData.UniformWritingHolder> uniforms) {
      if (uniforms.size() == 0) {
         return null;
      } else {
         List<IrisVoxyRenderPipelineData.UniformWritingHolder>[] ordering = new List[]{new ArrayList(), new ArrayList(), new ArrayList(), new ArrayList()};

         for (IrisVoxyRenderPipelineData.UniformWritingHolder uniform : uniforms) {
            int order = getUniformOrdering(uniform.type);
            ordering[order].add(uniform);
         }

         int pos = 0;
         Int2ObjectLinkedOpenHashMap<IrisVoxyRenderPipelineData.UniformWritingHolder> layout = new Int2ObjectLinkedOpenHashMap();

         for (IrisVoxyRenderPipelineData.UniformWritingHolder uniform : ordering[0]) {
            layout.put(pos, uniform);
            pos += getSizeAndAlignment(uniform.type) >> 5;
         }

         if (!ordering[1].isEmpty() && (ordering[1].size() & 1) == 0) {
            for (IrisVoxyRenderPipelineData.UniformWritingHolder uniform : ordering[1]) {
               layout.put(pos, uniform);
               pos += getSizeAndAlignment(uniform.type) >> 5;
            }

            ordering[1].clear();
         }

         for (IrisVoxyRenderPipelineData.UniformWritingHolder uniform : ordering[2]) {
            layout.put(pos, uniform);
            pos += getSizeAndAlignment(uniform.type) >> 5;
            if (!ordering[3].isEmpty()) {
               uniform = ordering[3].removeFirst();
               layout.put(pos, uniform);
               pos += getSizeAndAlignment(uniform.type) >> 5;
            } else {
               pos++;
            }
         }

         for (IrisVoxyRenderPipelineData.UniformWritingHolder uniformx : ordering[1]) {
            layout.put(pos, uniformx);
            pos += getSizeAndAlignment(uniformx.type) >> 5;
         }

         for (IrisVoxyRenderPipelineData.UniformWritingHolder uniformx : ordering[3]) {
            layout.put(pos, uniformx);
            pos += getSizeAndAlignment(uniformx.type) >> 5;
         }

         if (layout.size() != uniforms.size()) {
            throw new IllegalStateException();
         } else {
            StringBuilder struct = new StringBuilder("{\n");
            ObjectBidirectionalIterator layoutIter = layout.int2ObjectEntrySet().iterator();

            while (layoutIter.hasNext()) {
               Entry<IrisVoxyRenderPipelineData.UniformWritingHolder> pair = (Entry<IrisVoxyRenderPipelineData.UniformWritingHolder>)layoutIter.next();
               struct.append("\t")
                  .append(convertToGlslType(((IrisVoxyRenderPipelineData.UniformWritingHolder)pair.getValue()).type))
                  .append(" ")
                  .append(((IrisVoxyRenderPipelineData.UniformWritingHolder)pair.getValue()).name)
                  .append(";\n");
            }

            struct.append("}");
            String structLayout = struct.toString();
            LongConsumer[] updaters = new LongConsumer[uniforms.size()];
            int i = 0;
            ObjectBidirectionalIterator var8 = layout.int2ObjectEntrySet().iterator();

            while (var8.hasNext()) {
               Entry<IrisVoxyRenderPipelineData.UniformWritingHolder> pair = (Entry<IrisVoxyRenderPipelineData.UniformWritingHolder>)var8.next();
               updaters[i++] = (LongConsumer)((IrisVoxyRenderPipelineData.UniformWritingHolder)pair.getValue()).writingFactory.get(pair.getIntKey() * 4L);
            }

            LongConsumer updater = ptr -> {
               for (LongConsumer u : updaters) {
                  u.accept(ptr);
               }
            };
            return new IrisVoxyRenderPipelineData.StructLayout(pos * 4, structLayout, updater);
         }
      }
   }

   private static LongConsumer createWriter(long offset, FunctionReturn ret, CachedUniform uniform) {
      if (uniform instanceof BooleanCachedUniform bcu) {
         return ptr -> {
            ptr += offset;
            bcu.writeTo(ret);
            MemoryUtil.memPutInt(ptr, ret.booleanReturn ? 1 : 0);
         };
      } else if (uniform instanceof FloatCachedUniform fcu) {
         return ptr -> {
            ptr += offset;
            fcu.writeTo(ret);
            MemoryUtil.memPutFloat(ptr, ret.floatReturn);
         };
      } else if (uniform instanceof IntCachedUniform icu) {
         return ptr -> {
            ptr += offset;
            icu.writeTo(ret);
            MemoryUtil.memPutInt(ptr, ret.intReturn);
         };
      } else if (uniform instanceof Float2VectorCachedUniform v2fcu) {
         return ptr -> {
            ptr += offset;
            v2fcu.writeTo(ret);
            ((Vector2f)ret.objectReturn).getToAddress(ptr);
         };
      } else if (uniform instanceof Float3VectorCachedUniform v3fcu) {
         return ptr -> {
            ptr += offset;
            v3fcu.writeTo(ret);
            ((Vector3f)ret.objectReturn).getToAddress(ptr);
         };
      } else if (uniform instanceof Float4VectorCachedUniform v4fcu) {
         return ptr -> {
            ptr += offset;
            v4fcu.writeTo(ret);
            ((Vector4f)ret.objectReturn).getToAddress(ptr);
         };
      } else if (uniform instanceof Int2VectorCachedUniform v2icu) {
         return ptr -> {
            ptr += offset;
            v2icu.writeTo(ret);
            ((Vector2i)ret.objectReturn).getToAddress(ptr);
         };
      } else if (uniform instanceof Int3VectorCachedUniform v3icu) {
         return ptr -> {
            ptr += offset;
            v3icu.writeTo(ret);
            ((Vector3i)ret.objectReturn).getToAddress(ptr);
         };
      } else if (uniform instanceof Float4MatrixCachedUniform f4mcu) {
         return ptr -> {
            ptr += offset;
            f4mcu.writeTo(ret);
            ((Matrix4f)ret.objectReturn).getToAddress(ptr);
         };
      } else {
         throw new IllegalStateException("Unknown uniform type " + uniform.getClass().getName());
      }
   }

   private static int P(int size, int align) {
      return size << 5 | align;
   }

   private static int getSizeAndAlignment(UniformType type) {
      return switch (type) {
         case INT, FLOAT -> P(1, 1);
         case MAT3 -> P(11, 4);
         case MAT4 -> P(16, 4);
         case VEC2, VEC2I -> P(2, 2);
         case VEC3, VEC3I -> P(3, 4);
         case VEC4, VEC4I -> P(4, 4);
         default -> throw new MatchException(null, null);
      };
   }

   private static int getUniformOrdering(UniformType type) {
      return switch (type) {
         case INT, FLOAT -> 3;
         case MAT3, VEC3, VEC3I -> 2;
         case MAT4, VEC4, VEC4I -> 0;
         case VEC2, VEC2I -> 1;
         default -> throw new MatchException(null, null);
      };
   }

   private static List<IrisVoxyRenderPipelineData.UniformWritingHolder> createUniformSet(CustomUniforms cu, final IrisShaderPatch patch) {
      final List<IrisVoxyRenderPipelineData.UniformWritingHolder> uniforms = new ArrayList<>();
      final Set<String> seenUniforms = new HashSet<>();
      DynamicLocationalUniformHolder uniformBuilder = new DynamicLocationalUniformHolder() {
         public DynamicLocationalUniformHolder uniform1i(UniformUpdateFrequency updateFrequency, String name, IntSupplier value) {
            return this.uniform1i(name, value, null);
         }

         public DynamicLocationalUniformHolder uniform1i(String name, IntSupplier value, ValueUpdateNotifier notifier) {
            this.injectDynamicUniformType(name, UniformType.INT, offset -> ptr -> MemoryUtil.memPutInt(ptr + offset, value.getAsInt()));
            return this;
         }

         public DynamicLocationalUniformHolder uniform1f(UniformUpdateFrequency updateFrequency, String name, FloatSupplier value) {
            return this.uniform1f(name, value, null);
         }

         public DynamicLocationalUniformHolder uniform1f(String name, FloatSupplier value, ValueUpdateNotifier notifier) {
            this.injectDynamicUniformType(name, UniformType.FLOAT, offset -> ptr -> MemoryUtil.memPutFloat(ptr + offset, value.getAsFloat()));
            return this;
         }

         public DynamicLocationalUniformHolder uniform3f(UniformUpdateFrequency updateFrequency, String name, Supplier<Vector3f> value) {
            return this.uniform3f(name, value, null);
         }

         public DynamicLocationalUniformHolder uniform3f(String name, Supplier<Vector3f> value, ValueUpdateNotifier notifier) {
            this.injectDynamicUniformType(name, UniformType.VEC3, offset -> ptr -> value.get().getToAddress(ptr + offset));
            return this;
         }

         private void injectDynamicUniformType(String name, UniformType type, Long2ObjectFunction<LongConsumer> supplier) {
            String[] names = patch.getUniformList();

            for (int i = 0; i < names.length; i++) {
               if (names[i].equals(name)) {
                  if (!seenUniforms.add(name)) {
                     throw new IllegalArgumentException("Already added uniform: " + name);
                  }

                  uniforms.add(new IrisVoxyRenderPipelineData.UniformWritingHolder(name, type, supplier));
                  break;
               }
            }
         }

         public DynamicLocationalUniformHolder addDynamicUniform(Uniform uniform, ValueUpdateNotifier valueUpdateNotifier) {
            throw new IllegalStateException("Type not implemented for uniform: " + uniform);
         }

         public LocationalUniformHolder addUniform(UniformUpdateFrequency uniformUpdateFrequency, Uniform uniform) {
            return this;
         }

         public OptionalInt location(String uniformName, UniformType uniformType) {
            String[] names = patch.getUniformList();

            for (int i = 0; i < names.length; i++) {
               if (names[i].equals(uniformName)) {
                  return OptionalInt.of(i);
               }
            }

            return OptionalInt.empty();
         }

         public UniformHolder externallyManagedUniform(String s, UniformType uniformType) {
            return null;
         }
      };
      CommonUniforms.addDynamicUniforms(uniformBuilder, FogMode.PER_FRAGMENT);
      cu.assignTo(uniformBuilder);
      cu.mapholderToPass(uniformBuilder, patch);
      FunctionReturn cachedReturn = new FunctionReturn();
      ((CustomUniformsAccessor)cu)
         .getLocationMap()
         .get(patch)
         .object2IntEntrySet()
         .forEach(
            entry -> {
               if (!seenUniforms.add(((CachedUniform)entry.getKey()).getName())) {
                  throw new IllegalArgumentException("Already added uniform: " + ((CachedUniform)entry.getKey()).getName());
               } else {
                  uniforms.add(
                     new IrisVoxyRenderPipelineData.UniformWritingHolder(
                        ((CachedUniform)entry.getKey()).getName(),
                        Type.convert(((CachedUniform)entry.getKey()).getType()),
                        offset -> createWriter(offset, cachedReturn, (CachedUniform)entry.getKey())
                     )
                  );
               }
            }
         );
      if (uniforms.size() != patch.getUniformList().length) {
         Set<String> uniformsUnseen = new HashSet<>(List.of(patch.getUniformList()));

         for (IrisVoxyRenderPipelineData.UniformWritingHolder uniform : uniforms) {
            uniformsUnseen.remove(uniform.name);
         }

         Logger.error(
            "The following uniforms could not be found: [" + uniformsUnseen.stream().sorted(String::compareToIgnoreCase).collect(Collectors.joining(",")) + "]"
         );
      }

      return uniforms;
   }

   private static IrisVoxyRenderPipelineData.ImageSet createImageSet(IrisRenderingPipeline ipipe, IrisShaderPatch patch) {
      Object2ObjectLinkedOpenHashMap<String, String> samplerDataSet = patch.getSamplerSet();
      if (samplerDataSet == null) {
         return null;
      } else {
         final Set<String> samplerNameSet = new LinkedHashSet<>(samplerDataSet.keySet());
         if (samplerNameSet.isEmpty()) {
            return null;
         } else {
            final Set<IrisVoxyRenderPipelineData.TextureWSampler> samplerSet = new LinkedHashSet<>();
            SamplerHolder samplerBuilder = new SamplerHolder() {
               public boolean hasSampler(String s) {
                  return samplerNameSet.contains(s);
               }

               public boolean hasSampler(String... names) {
                  for (String name : names) {
                     if (samplerNameSet.contains(name)) {
                        return true;
                     }
                  }

                  return false;
               }

               private String name(String... names) {
                  for (String name : names) {
                     if (samplerNameSet.contains(name)) {
                        return name;
                     }
                  }

                  return null;
               }

               public boolean addDefaultSampler(TextureType type, IntSupplier texture, ValueUpdateNotifier notifier, GlSampler sampler, String... names) {
                  Logger.error("Unsupported default sampler");
                  return false;
               }

               public boolean addDynamicSampler(TextureType type, IntSupplier texture, GlSampler sampler, String... names) {
                  return this.addDynamicSampler(type, texture, null, sampler, names);
               }

               public boolean addDynamicSampler(TextureType type, IntSupplier texture, ValueUpdateNotifier notifier, GlSampler sampler, String... names) {
                  if (!this.hasSampler(names)) {
                     return false;
                  } else {
                     samplerSet.add(
                        new IrisVoxyRenderPipelineData.TextureWSampler(this.name(names), texture, sampler != null ? () -> sampler.getId() : () -> -1)
                     );
                     return true;
                  }
               }

               public void addExternalSampler(int texture, String... names) {
                  if (this.hasSampler(names)) {
                     samplerSet.add(new IrisVoxyRenderPipelineData.TextureWSampler(this.name(names), () -> texture, () -> -1));
                  }
               }
            };
            ImageHolder imageBuilder = new ImageHolder() {
               public boolean hasImage(String s) {
                  return false;
               }

               public void addTextureImage(IntSupplier intSupplier, InternalTextureFormat internalTextureFormat, String s) {
               }
            };
            ipipe.addGbufferOrShadowSamplers(samplerBuilder, imageBuilder, ipipe::getFlippedAfterPrepare, false, true, true, false);
            if (samplerSet.size() != samplerNameSet.size()) {
               Logger.error(
                  "Did not find all requested samplers. Found ["
                     + samplerSet.stream().map(a -> a.name).collect(Collectors.joining(", "))
                     + "] expected "
                     + samplerNameSet
               );
            }

            StringBuilder builder = new StringBuilder();
            IrisVoxyRenderPipelineData.TextureWSampler[] samplers = new IrisVoxyRenderPipelineData.TextureWSampler[samplerSet.size()];
            int i = 0;

            for (IrisVoxyRenderPipelineData.TextureWSampler entry : samplerSet) {
               samplers[i] = entry;
               String samplerType = (String)samplerDataSet.get(entry.name);
               builder.append("layout(binding=(BASE_SAMPLER_BINDING_INDEX+")
                  .append(i)
                  .append(")) uniform ")
                  .append(samplerType)
                  .append(" ")
                  .append(entry.name)
                  .append(";\n");
               i++;
            }

            IntConsumer bindingFunction = base -> {
               for (int j = 0; j < samplers.length; j++) {
                  int unit = j + base;
                  IrisVoxyRenderPipelineData.TextureWSampler ts = samplers[j];
                  ARBDirectStateAccess.glBindTextureUnit(unit, ts.texture.getAsInt());
                  int sampler = ts.sampler.getAsInt();
                  if (sampler != -1) {
                     GL33C.glBindSampler(unit, sampler);
                  }
               }
            };
            return new IrisVoxyRenderPipelineData.ImageSet(builder.toString(), bindingFunction);
         }
      }
   }

   private static IrisVoxyRenderPipelineData.SSBOSet createSSBOLayouts(Int2ObjectMap<String> ssbos, ShaderStorageBufferHolder ssboStore) {
      if (ssboStore == null) {
         return null;
      } else if (ssbos.isEmpty()) {
         return null;
      } else {
         String header = "";
         if (ssbos.containsKey(-1)) {
            header = (String)ssbos.remove(-1);
         }

         StringBuilder builder = new StringBuilder(header);
         builder.append("\n");
         IrisVoxyRenderPipelineData.SSBOBinding[] bindings = new IrisVoxyRenderPipelineData.SSBOBinding[ssbos.size()];
         int i = 0;

         for (ObjectIterator bindingFunction = ssbos.int2ObjectEntrySet().iterator(); bindingFunction.hasNext(); i++) {
            Entry<String> entry = (Entry<String>)bindingFunction.next();
            String val = (String)entry.getValue();
            bindings[i] = new IrisVoxyRenderPipelineData.SSBOBinding(entry.getIntKey(), i);
            builder.append("layout(binding = (BUFFER_BINDING_INDEX_BASE+").append(i).append(")) restrict buffer IrisBufferBinding").append(i);
            builder.append(" ").append(val).append(";\n");
         }

         IntConsumer bindingFunction = base -> {
            for (IrisVoxyRenderPipelineData.SSBOBinding binding : bindings) {
               ARBUniformBufferObject.glBindBufferBase(37074, base + binding.bindingOffset, ssboStore.getBufferIndex(binding.irisIndex));
            }
         };
         return new IrisVoxyRenderPipelineData.SSBOSet(builder.toString(), bindingFunction);
      }
   }

   public record ImageSet(String layout, IntConsumer bindingFunction) {
   }

   private record SSBOBinding(int irisIndex, int bindingOffset) {
   }

   public record SSBOSet(String layout, IntConsumer bindingFunction) {
   }

   public record StructLayout(int size, String layout, LongConsumer updater) {
   }

   private record TextureWSampler(String name, IntSupplier texture, IntSupplier sampler) {
   }

   private record UniformWritingHolder(String name, UniformType type, Long2ObjectFunction<LongConsumer> writingFactory) {
   }
}
