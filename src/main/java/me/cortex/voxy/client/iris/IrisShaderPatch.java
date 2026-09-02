package me.cortex.voxy.client.iris;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.JsonAdapter;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.lang.reflect.Type;
import java.util.List;
import java.util.function.Function;
import me.cortex.voxy.common.Logger;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import org.lwjgl.opengl.ARBDrawBuffersBlend;
import org.lwjgl.opengl.GL33;

public class IrisShaderPatch {
   public static final int VERSION = 1;
   public static final boolean IMPERSONATE_DISTANT_HORIZONS = System.getProperty("voxy.impersonateDHShader", "false").equalsIgnoreCase("true");
   private final IrisShaderPatch.PatchGson patchData;
   private final ShaderPack pack;
   private final Int2ObjectMap<String> ssbos;
   private static final Gson GSON = new GsonBuilder().excludeFieldsWithModifiers(new int[]{2}).setLenient().create();

   private IrisShaderPatch(IrisShaderPatch.PatchGson patchData, ShaderPack pack) {
      this.patchData = patchData;
      this.pack = pack;
      if (patchData.ssbos == null) {
         this.ssbos = new Int2ObjectOpenHashMap();
      } else {
         this.ssbos = patchData.ssbos;
      }
   }

   public boolean useViewportDims() {
      return this.patchData.useViewportDims;
   }

   public Int2ObjectMap<String> getSSBOs() {
      return new Int2ObjectLinkedOpenHashMap(this.ssbos);
   }

   public String getPatchOpaqueSource() {
      return this.patchData.opaquePatchData;
   }

   public String getPatchTranslucentSource() {
      return this.patchData.translucentPatchData;
   }

   public String getTAAShift() {
      return this.patchData.taaOffset == null ? "{return vec2(0.0);}" : this.patchData.taaOffset;
   }

   public String[] getUniformList() {
      return this.patchData.uniforms;
   }

   public Object2ObjectLinkedOpenHashMap<String, String> getSamplerSet() {
      return this.patchData.samplers;
   }

   public int[] getOpqaueTargets() {
      return this.patchData.opaqueDrawBuffers;
   }

   public int[] getTranslucentTargets() {
      return this.patchData.translucentDrawBuffers;
   }

   public boolean emitToVanillaDepth() {
      return !this.patchData.excludeLodsFromVanillaDepth;
   }

   public float[] getRenderScale() {
      if (this.patchData.renderScale != null && this.patchData.renderScale.length != 0) {
         return this.patchData.renderScale.length == 1
            ? new float[]{this.patchData.renderScale[0], this.patchData.renderScale[0]}
            : new float[]{Math.max(0.01F, this.patchData.renderScale[0]), Math.max(0.01F, this.patchData.renderScale[1])};
      } else {
         return new float[]{1.0F, 1.0F};
      }
   }

   public boolean deferedTranslucentRendering() {
      return false;
   }

   public boolean supportsEuphoriaShadowCaster() {
      if (this.patchData.shadowCasterVersion == 1 && this.patchData.uniforms != null && this.patchData.samplers != null) {
         List<String> uniforms = List.of(this.patchData.uniforms);
         return uniforms.contains("shadowModelView") && uniforms.contains("shadowProjection") && this.patchData.samplers.containsKey("shadowtex0");
      } else {
         return false;
      }
   }

   public Runnable createBlendSetup() {
      return this.patchData.blending != null && !this.patchData.blending.isEmpty() ? () -> {
         Int2ObjectOpenHashMap<IrisShaderPatch.BlendState> BS = this.patchData.blending;
         IrisShaderPatch.BlendState init = (IrisShaderPatch.BlendState)BS.getOrDefault(-1, null);
         if (init != null) {
            if (init.off) {
               GL33.glDisable(3042);
            } else {
               GL33.glEnable(3042);
               GL33.glBlendFuncSeparate(init.sRGB, init.dRGB, init.sA, init.dA);
            }
         }

         ObjectIterator var3 = BS.int2ObjectEntrySet().iterator();

         while (var3.hasNext()) {
            Entry<IrisShaderPatch.BlendState> entry = (Entry<IrisShaderPatch.BlendState>)var3.next();
            if (entry.getIntKey() != -1) {
               IrisShaderPatch.BlendState s = (IrisShaderPatch.BlendState)entry.getValue();
               if (s.off) {
                  GL33.glDisablei(3042, s.buffer);
               } else {
                  GL33.glEnablei(3042, s.buffer);
                  ARBDrawBuffersBlend.glBlendFuncSeparateiARB(s.buffer, s.sRGB, s.dRGB, s.sA, s.dA);
               }
            }
         }
      } : () -> {};
   }

   public static IrisShaderPatch makePatch(ShaderPack ipack, AbsolutePackPath directory, Function<AbsolutePackPath, String> sourceProvider) {
      String voxyPatchData = sourceProvider.apply(directory.resolve("voxy.json"));
      if (voxyPatchData == null) {
         return null;
      } else if (voxyPatchData.isBlank()) {
         return null;
      } else {
         voxyPatchData = voxyPatchData.replace("\\", "\\\\");
         IrisShaderPatch.PatchGson patchData = null;

         try {
            StringBuilder builder = new StringBuilder(voxyPatchData.length());

            for (String line : voxyPatchData.split("\n")) {
               int idx = line.indexOf("//");
               if (idx != -1) {
                  builder.append(line, 0, idx);
                  builder.append(line.substring(idx).replace("\"", "\\\""));
               } else {
                  builder.append(line);
               }

               builder.append("\n");
            }

            voxyPatchData = builder.toString();
            patchData = (IrisShaderPatch.PatchGson)GSON.fromJson(voxyPatchData, IrisShaderPatch.PatchGson.class);
            if (patchData == null) {
               throw new IllegalStateException("Voxy patch json returned null, this is most likely due to malformed json file");
            }

            String opaque = sourceProvider.apply(directory.resolve("voxy_opaque.glsl"));
            if (opaque != null) {
               Logger.info("External opaque shader patch applied");
               patchData.opaquePatchData = opaque;
            }

            String translucent = sourceProvider.apply(directory.resolve("voxy_translucent.glsl"));
            if (translucent != null) {
               Logger.info("External translucent shader patch applied");
               patchData.translucentPatchData = translucent;
            }

            String taa = sourceProvider.apply(directory.resolve("voxy_taa.glsl"));
            if (taa != null) {
               Logger.info("External taa shader patch applied");
               patchData.taaOffset = taa;
            }

            String invalidPatchDataReason = patchData.checkValid();
            if (invalidPatchDataReason != null) {
               throw new IllegalStateException("voxy json patch not valid: " + invalidPatchDataReason);
            }
         } catch (Exception var11) {
            patchData = null;
            Logger.error("Failed to parse patch data gson", var11);
            throw new ShaderLoadError("Failed to parse patch data gson", var11);
         }

         if (patchData == null) {
            return null;
         } else if (patchData.version != VERSION) {
            Logger.error("Shader has voxy patch data, but patch version is incorrect. expected " + VERSION + " got " + patchData.version);
            throw new IllegalStateException("Shader version mismatch expected " + VERSION + " got " + patchData.version);
         } else {
            return new IrisShaderPatch(patchData, ipack);
         }
      }
   }

   public record BlendState(int buffer, boolean off, int sRGB, int dRGB, int sA, int dA) {
      public static IrisShaderPatch.BlendState ALL_OFF = new IrisShaderPatch.BlendState(-1, true, 0, 0, 0, 0);
   }

   private static final class BlendStateDeserializer implements JsonDeserializer<Int2ObjectMap<IrisShaderPatch.BlendState>> {
      private static int parseType(String type) {
         type = type.toUpperCase();
         if (!type.startsWith("GL_")) {
            type = "GL_" + type;
         }
         return switch (type) {
            case "GL_ZERO" -> 0;
            case "GL_ONE" -> 1;
            case "GL_SRC_COLOR" -> 768;
            case "GL_ONE_MINUS_SRC_COLOR" -> 769;
            case "GL_SRC_ALPHA" -> 770;
            case "GL_ONE_MINUS_SRC_ALPHA" -> 771;
            case "GL_DST_ALPHA" -> 772;
            case "GL_ONE_MINUS_DST_ALPHA" -> 773;
            case "GL_DST_COLOR" -> 774;
            case "GL_ONE_MINUS_DST_COLOR" -> 775;
            case "GL_SRC_ALPHA_SATURATE" -> 776;
            case "GL_SRC1_COLOR" -> 35065;
            case "GL_ONE_MINUS_SRC1_COLOR" -> 35066;
            case "GL_ONE_MINUS_SRC1_ALPHA" -> 35067;
            default -> {
               Logger.error("Unknown blend option " + type);
               yield -1;
            }
         };
      }

      public Int2ObjectMap<IrisShaderPatch.BlendState> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
         if (json == null) {
            return null;
         } else {
            Int2ObjectMap<IrisShaderPatch.BlendState> ret = new Int2ObjectOpenHashMap();

            try {
               if (json.isJsonPrimitive()) {
                  if (json.getAsString().equalsIgnoreCase("off")) {
                     ret.put(-1, IrisShaderPatch.BlendState.ALL_OFF);
                     return ret;
                  }
               } else if (json.isJsonObject()) {
                  for (java.util.Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
                     int buffer = Integer.parseInt(entry.getKey());
                     IrisShaderPatch.BlendState state = null;
                     JsonElement val = entry.getValue();
                     List<String> bs = null;
                     if (val.isJsonArray()) {
                        bs = val.getAsJsonArray().asList().stream().<String>map(JsonElement::getAsString).toList();
                     } else if (val.isJsonPrimitive()) {
                        String str = val.getAsString();
                        if (str.equalsIgnoreCase("off")) {
                           state = new IrisShaderPatch.BlendState(buffer, true, 0, 0, 0, 0);
                        } else {
                           String[] parts = str.split(" ");
                           if (parts.length < 4) {
                              state = new IrisShaderPatch.BlendState(buffer, true, -1, -1, -1, -1);
                           } else {
                              bs = List.of(parts);
                           }
                        }
                     } else {
                        Logger.error("Unknown blend state " + val);
                        state = null;
                     }

                     if (bs != null) {
                        int[] v = bs.stream().mapToInt(IrisShaderPatch.BlendStateDeserializer::parseType).toArray();
                        state = new IrisShaderPatch.BlendState(buffer, false, v[0], v[1], v[2], v[3]);
                     }

                     ret.put(buffer, state);
                  }

                  return ret;
               }
            } catch (Exception var13) {
               Logger.error(var13);
            }

            Logger.error("Failed to parse blend state: " + json);
            return ret;
         }
      }
   }

   private static class PatchGson {
      public int version;
      public int shadowCasterVersion;
      public int[] opaqueDrawBuffers;
      public int[] translucentDrawBuffers;
      public String[] uniforms;
      @JsonAdapter(IrisShaderPatch.SamplerDeserializer.class)
      public Object2ObjectLinkedOpenHashMap<String, String> samplers;
      public String opaquePatchData;
      public String translucentPatchData;
      @JsonAdapter(IrisShaderPatch.SSBODeserializer.class)
      public Int2ObjectOpenHashMap<String> ssbos;
      @JsonAdapter(IrisShaderPatch.BlendStateDeserializer.class)
      public Int2ObjectOpenHashMap<IrisShaderPatch.BlendState> blending;
      public String taaOffset;
      public boolean excludeLodsFromVanillaDepth;
      public float[] renderScale;
      public boolean useViewportDims;

      public String checkValid() {
         if (this.blending != null) {
            int i = 0;

            for (ObjectIterator var2 = this.blending.values().iterator(); var2.hasNext(); i++) {
               IrisShaderPatch.BlendState state = (IrisShaderPatch.BlendState)var2.next();
               if (state.buffer != -1 && (state.buffer < 0 || this.translucentDrawBuffers.length <= state.buffer)) {
                  if (state.buffer < 0) {
                     return "Blending buffer is <0 at index: " + i;
                  }

                  return "Blending buffer index out of bounds at " + i + " was " + state.buffer + " maximum is " + (this.translucentDrawBuffers.length - 1);
               }
            }
         }

         if (this.opaquePatchData == null) {
            return "Opaque patch data is null";
         } else if (this.uniforms == null) {
            return "Uniforms are null";
         } else if (this.opaqueDrawBuffers == null) {
            return "Opaque draw buffers are null";
         } else {
            return this.translucentDrawBuffers == null ? "Translucent draw buffers are null" : null;
         }
      }
   }

   private static final class SSBODeserializer implements JsonDeserializer<Int2ObjectOpenHashMap<String>> {
      public Int2ObjectOpenHashMap<String> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
         Int2ObjectOpenHashMap<String> ret = new Int2ObjectOpenHashMap();
         if (json == null) {
            return null;
         } else {
            try {
               for (java.util.Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
                  ret.put(Integer.parseInt(entry.getKey()), entry.getValue().getAsString());
               }
            } catch (Exception var7) {
               Logger.error(var7);
            }

            return ret;
         }
      }
   }

   private static final class SamplerDeserializer implements JsonDeserializer<Object2ObjectLinkedOpenHashMap<String, String>> {
      public Object2ObjectLinkedOpenHashMap<String, String> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
         Object2ObjectLinkedOpenHashMap<String, String> ret = new Object2ObjectLinkedOpenHashMap();
         if (json == null) {
            return null;
         } else {
            try {
               if (json.isJsonArray()) {
                  for (JsonElement entry : json.getAsJsonArray()) {
                     String name = entry.getAsString();
                     String type = "sampler2D";
                     if (name.matches("shadowtex")) {
                        type = "sampler2DShadow";
                     }

                     ret.put(name, type);
                  }
               } else {
                  for (java.util.Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
                     String type = "sampler2D";
                     if (entry.getValue().isJsonNull()) {
                        if (entry.getKey().matches("shadowtex")) {
                           type = "sampler2DShadow";
                        }
                     } else {
                        type = entry.getValue().getAsString();
                     }

                     ret.put(entry.getKey(), type);
                  }
               }
            } catch (Exception var9) {
               Logger.error(var9);
            }

            return ret;
         }
      }
   }
}
