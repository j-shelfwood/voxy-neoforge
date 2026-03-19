package me.cortex.voxy.client.iris;

import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import kroppeb.stareval.function.FunctionReturn;
import kroppeb.stareval.function.Type;
import me.cortex.voxy.client.mixin.iris.CustomUniformsAccessor;
import me.cortex.voxy.client.mixin.iris.IrisRenderingPipelineAccessor;
import me.cortex.voxy.client.core.IrisVoxyRenderPipeline;
import me.cortex.voxy.client.core.rendering.util.LightMapHelper;
import me.cortex.voxy.common.Logger;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.image.ImageHolder;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.state.ValueUpdateNotifier;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.gl.uniform.*;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.shaderpack.IdMap;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.uniforms.custom.cached.*;
import org.joml.*;
import org.lwjgl.system.MemoryUtil;

import java.util.*;
import java.util.function.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.lwjgl.opengl.ARBDirectStateAccess.glBindTextureUnit;
import static org.lwjgl.opengl.ARBUniformBufferObject.glBindBufferBase;
import static org.lwjgl.opengl.GL33C.glBindSampler;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER;

public class IrisVoxyRenderPipelineData {
    public IrisVoxyRenderPipeline thePipeline;
    public final int[] opaqueDrawTargets;
    public final int[] translucentDrawTargets;
    private final String opaquePatch;
    private final String translucentPatch;
    private final StructLayout uniforms;
    private final Runnable blendingSetup;
    private final ImageSet imageSet;
    private final SSBOSet ssboSet;
    public final boolean renderToVanillaDepth;
    public final float[] resolutionScale;
    public final String TAA;
    public final boolean useViewportDims;
    public final boolean deferTranslucency;
    /** True when this pipeline was built from DH_NATIVE_CANDIDATE mode (pack has dh_terrain.fsh, no voxy.json). */
    public final boolean isDhNativeCandidate;

    private IrisVoxyRenderPipelineData(IrisShaderPatch patch, int[] opaqueDrawTargets, int[] translucentDrawTargets, StructLayout uniformSet, Runnable blendingSetup, ImageSet imageSet, SSBOSet ssboSet, Set<String> missingUniforms) {
        this.opaqueDrawTargets = opaqueDrawTargets;
        this.translucentDrawTargets = translucentDrawTargets;
        this.opaquePatch = patch.getPatchOpaqueSource();
        this.translucentPatch = patch.getPatchTranslucentSource();
        this.uniforms = uniformSet;
        this.blendingSetup = blendingSetup;
        this.imageSet = imageSet;
        this.ssboSet = ssboSet;
        this.renderToVanillaDepth = patch.emitToVanillaDepth();
        if (!missingUniforms.isEmpty()) {
            Logger.warn("[IrisVoxyRenderPipelineData] Missing uniforms unresolved during pipeline build: " + missingUniforms);
        }
        this.TAA = patch.getTAAShift();
        this.resolutionScale = patch.getRenderScale();
        this.useViewportDims = patch.useViewportDims();
        this.deferTranslucency = patch.deferedTranslucentRendering();
        this.isDhNativeCandidate = patch.isDhNativeCandidate;
    }

    public SSBOSet getSsboSet() {
        return this.ssboSet;
    }

    public ImageSet getImageSet() {
        return this.imageSet;
    }

    public StructLayout getUniforms() {
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


    public static IrisVoxyRenderPipelineData buildPipeline(IrisRenderingPipeline ipipe, IrisShaderPatch patch, CustomUniforms cu, ShaderStorageBufferHolder ssboHolder, IdMap idMap) {
        var accessor = (IrisRenderingPipelineAccessor) ipipe;
        var uniformResult = createUniformSet(cu, patch, idMap, accessor.getPackDirectives(), accessor.getUpdateNotifier());
        var uniforms = createUniformLayoutStructAndUpdater(uniformResult.uniforms());

        var imageSet = createImageSet(ipipe, patch);

        var ssboSet = createSSBOLayouts(patch.getSSBOs(), ssboHolder);

        var opaqueDrawTargets = getDrawBuffers(patch.getOpqaueTargets(), ipipe.getFlippedAfterPrepare(), ((IrisRenderingPipelineAccessor)ipipe).getRenderTargets());
        var translucentDrawTargets = getDrawBuffers(patch.getTranslucentTargets(), ipipe.getFlippedAfterPrepare(), ((IrisRenderingPipelineAccessor)ipipe).getRenderTargets());

        String samplerNames = imageSet != null
                ? imageSet.patchSamplerBindings().keySet() + " + layout-injected:" + imageSet.patchSamplerBindings()
                : "none";
        // Log sampler layout header for debugging binding issues
        String samplerLayout = imageSet != null ? imageSet.layout().trim() : "(none)";
        Logger.info("[IrisVoxyRenderPipelineData] buildPipeline OK:"
                + "\n  emitToVanillaDepth=" + patch.emitToVanillaDepth()
                + " useViewportDims=" + patch.useViewportDims()
                + "\n  opaqueBuffers(tex)=" + java.util.Arrays.toString(opaqueDrawTargets)
                + " translucentBuffers(tex)=" + java.util.Arrays.toString(translucentDrawTargets)
                + "\n  uniforms=" + uniformResult.uniforms().size()
                + " resolvedNames=" + uniformResult.uniforms().stream().map(u -> u.name()).collect(java.util.stream.Collectors.joining(",", "[", "]"))
                + "\n  missingUniforms=" + (uniformResult.missingUniforms().isEmpty() ? "none" : uniformResult.missingUniforms())
                + "\n  samplerLayout=\n    " + samplerLayout.replace("\n", "\n    ")
                + "\n  taaShift=" + (patch.getTAAShift() != null && !patch.getTAAShift().trim().equals("{return vec2(0.0);}") ? "custom(len=" + patch.getTAAShift().length() + ")" : "noop")
                + " opaquePatchLen=" + (patch.getPatchOpaqueSource() == null ? "null" : patch.getPatchOpaqueSource().length())
                + " translucentPatchLen=" + (patch.getPatchTranslucentSource() == null ? "null" : patch.getPatchTranslucentSource().length()));
        return new IrisVoxyRenderPipelineData(patch, opaqueDrawTargets, translucentDrawTargets, uniforms, patch.createBlendSetup(), imageSet, ssboSet, uniformResult.missingUniforms());
    }

    private static int[] getDrawBuffers(int[] targets, ImmutableSet<Integer> stageWritesToAlt, RenderTargets rt) {
        int[] targetTextures = new int[targets.length];
        int availableTargets = rt.getRenderTargetCount();
        if (availableTargets <= 0) {
            Logger.error("[IrisVoxyRenderPipelineData] RenderTargets reported zero available draw targets");
            return targetTextures;
        }
        for(int i = 0; i < targets.length; i++) {
            int requestedTarget = targets[i];
            if (requestedTarget < 0 || requestedTarget >= availableTargets) {
                Logger.warn("[IrisVoxyRenderPipelineData] Draw target " + requestedTarget
                        + " is outside available range [0.." + (availableTargets - 1) + "], leaving texture id=0");
                targetTextures[i] = 0;
                continue;
            }
            try {
                RenderTarget target = rt.getOrCreate(requestedTarget);
                int textureId = stageWritesToAlt.contains(requestedTarget) ? target.getAltTexture() : target.getMainTexture();
                targetTextures[i] = textureId;
            } catch (RuntimeException e) {
                Logger.error("[IrisVoxyRenderPipelineData] Failed to resolve draw target " + requestedTarget
                        + " (index " + i + "/" + targets.length
                        + ", availableTargets=" + availableTargets + ")", e);
                // Keep pipeline alive even if a single target lookup fails.
                targetTextures[i] = 0;
            }
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
        };
    }

    public boolean shouldDeferTranslucency() {
        return false;
    }

    public record StructLayout(int size, String layout, LongConsumer updater) {}
    private static StructLayout createUniformLayoutStructAndUpdater(List<UniformWritingHolder> uniforms) {
        if (uniforms.size() == 0) {
            return null;
        }

        List<UniformWritingHolder>[] ordering = new List[]{new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>()};

        //Creates an optimial struct layout for the uniforms
        for (var uniform : uniforms) {
            int order = getUniformOrdering(uniform.type);
            ordering[order].add(uniform);
        }

        //Emit the ordering, note this is not optimial, but good enough, e.g. if have even number of align 2, emit that after align 4
        int pos = 0;
        Int2ObjectLinkedOpenHashMap<UniformWritingHolder> layout = new Int2ObjectLinkedOpenHashMap<>();
        for (var uniform : ordering[0]) {//Emit exact align 4
            layout.put(pos, uniform); pos += getSizeAndAlignment(uniform.type)>>5;
        }
        if (!ordering[1].isEmpty() && (ordering[1].size()&1)==0) {
            //Emit all the align 2 as there is an even number of them
            for (var uniform : ordering[1]) {
                layout.put(pos, uniform); pos += getSizeAndAlignment(uniform.type)>>5;
            }
            ordering[1].clear();
        }
        //Emit align 3
        for (var uniform : ordering[2]) {//Emit size odd, alignment must be 4
            layout.put(pos, uniform); pos += getSizeAndAlignment(uniform.type)>>5;
            //We must get a size 1 to pad to align 4
            if (!ordering[3].isEmpty()) {//Size 1
                uniform = ordering[3].removeFirst();
                layout.put(pos, uniform); pos += getSizeAndAlignment(uniform.type)>>5;
            } else {//Padding must be injected
                pos += 1;
            }
        }

        //Emit align 2
        for (var uniform : ordering[1]) {
            layout.put(pos, uniform); pos += getSizeAndAlignment(uniform.type)>>5;
        }

        //Emit align 1
        for (var uniform : ordering[3]) {
            layout.put(pos, uniform); pos += getSizeAndAlignment(uniform.type)>>5;
        }

        if (layout.size()!=uniforms.size()) {
            throw new IllegalStateException();
        }

        //We have our ordering and aligned offsets, generate an updater aswell as the layout

        String structLayout;
        {
            StringBuilder struct = new StringBuilder("{\n");
            for (var pair : layout.int2ObjectEntrySet()) {
                struct.append("\t").append(convertToGlslType(pair.getValue().type)).append(" ").append(pair.getValue().name).append(";\n");
            }
            struct.append("}");
            structLayout = struct.toString();
        }

        LongConsumer updater;
        {
            LongConsumer[] updaters = new LongConsumer[uniforms.size()];
            int i = 0;
            for (var pair : layout.int2ObjectEntrySet()) {
                updaters[i++] = pair.getValue().writingFactory.get(pair.getIntKey()*4L);
            }

            updater = ptr -> {
                for (var u : updaters) {
                    u.accept(ptr);
                }
            };//Writes all the uniforms to the locations
        }
        return new StructLayout(pos*4, structLayout, updater);//*4 since each slot is 4 bytes
    }

    private static LongConsumer createWriter(long offset, FunctionReturn ret, CachedUniform uniform) {
        if (uniform instanceof BooleanCachedUniform bcu) {
            return ptr->{ptr += offset;
                bcu.writeTo(ret);
                MemoryUtil.memPutInt(ptr, ret.booleanReturn?1:0);
            };
        } else if (uniform instanceof FloatCachedUniform fcu) {
            return ptr->{ptr += offset;
                fcu.writeTo(ret);
                MemoryUtil.memPutFloat(ptr, ret.floatReturn);
            };
        } else if (uniform instanceof IntCachedUniform icu) {
            return ptr->{ptr += offset;
                icu.writeTo(ret);
                MemoryUtil.memPutInt(ptr, ret.intReturn);
            };
        } else if (uniform instanceof Float2VectorCachedUniform v2fcu) {
            return ptr->{ptr += offset;
                v2fcu.writeTo(ret);
                ((Vector2f)ret.objectReturn).getToAddress(ptr);
            };
        } else if (uniform instanceof Float3VectorCachedUniform v3fcu) {
            return ptr->{ptr += offset;
                v3fcu.writeTo(ret);
                ((Vector3f)ret.objectReturn).getToAddress(ptr);
            };
        } else if (uniform instanceof Float4VectorCachedUniform v4fcu) {
            return ptr->{ptr += offset;
                v4fcu.writeTo(ret);
                ((Vector4f)ret.objectReturn).getToAddress(ptr);
            };
        } else if (uniform instanceof Int2VectorCachedUniform v2icu) {
            return ptr->{ptr += offset;
                v2icu.writeTo(ret);
                ((Vector2i)ret.objectReturn).getToAddress(ptr);
            };
        } else if (uniform instanceof Int3VectorCachedUniform v3icu) {
            return ptr->{ptr += offset;
                v3icu.writeTo(ret);
                ((Vector3i)ret.objectReturn).getToAddress(ptr);
            };
        } else if (uniform instanceof Float4MatrixCachedUniform f4mcu) {
            return ptr->{ptr += offset;
                f4mcu.writeTo(ret);
                ((Matrix4f)ret.objectReturn).getToAddress(ptr);
            };
        } else {
            throw new IllegalStateException("Unknown uniform type " + uniform.getClass().getName());
        }
    }


    private static int P(int size, int align) {
        return size<<5|align;
    }
    private static int getSizeAndAlignment(UniformType type) {
        return switch (type) {
            case INT, FLOAT -> P(1,1);//Size, Alignment
            case MAT3 -> P(4+4+3,4);//is funky as each row is a vec3 padded to a vec4
            case MAT4 -> P(4*4,4);
            case VEC2, VEC2I -> P(2,2);
            case VEC3, VEC3I -> P(3,4);
            case VEC4, VEC4I -> P(4,4);
        };
    }
    private static int getUniformOrdering(UniformType type) {
        return switch (type) {
            case MAT4, VEC4, VEC4I -> 0;
            case VEC2, VEC2I -> 1;
            case VEC3, VEC3I, MAT3 -> 2;
            case INT, FLOAT -> 3;
        };
    }

    private record UniformWritingHolder(String name, UniformType type, Long2ObjectFunction<LongConsumer> writingFactory) {

    }
    private static UniformSetResult createUniformSet(CustomUniforms cu, IrisShaderPatch patch, IdMap idMap, PackDirectives packDirectives, FrameUpdateNotifier updateNotifier) {
        //This is a fking awful hack... but it works thinks

        List<UniformWritingHolder> uniforms = new ArrayList<>();
        Set<String> seenUniforms = new HashSet<>();
        DynamicLocationalUniformHolder uniformBuilder = new DynamicLocationalUniformHolder() {
            @Override
            public DynamicLocationalUniformHolder uniform1i(UniformUpdateFrequency updateFrequency, String name, IntSupplier value) {
                return this.uniform1i(name, value, null);
            }

            @Override
            public DynamicLocationalUniformHolder uniform1i(String name, IntSupplier value, ValueUpdateNotifier notifier) {
                this.injectDynamicUniformType(name, UniformType.INT, offset->{
                    return ptr->{
                        MemoryUtil.memPutInt(ptr+offset, value.getAsInt());
                    };
                });
                return this;
            }


            @Override
            public DynamicLocationalUniformHolder uniform1f(UniformUpdateFrequency updateFrequency, String name, FloatSupplier value) {
                return this.uniform1f(name, value, null);
            }

            @Override
            public DynamicLocationalUniformHolder uniform1f(UniformUpdateFrequency updateFrequency, String name, IntSupplier value) {
                return this.uniform1f(name, (FloatSupplier) value::getAsInt, null);
            }

            @Override
            public DynamicLocationalUniformHolder uniform1f(UniformUpdateFrequency updateFrequency, String name, DoubleSupplier value) {
                return this.uniform1f(name, (FloatSupplier)(() -> (float) value.getAsDouble()), null);
            }

            @Override
            public DynamicLocationalUniformHolder uniform1f(String name, FloatSupplier value, ValueUpdateNotifier notifier) {
                this.injectDynamicUniformType(name, UniformType.FLOAT, offset->{
                    return ptr->{
                        MemoryUtil.memPutFloat(ptr+offset, value.getAsFloat());
                    };
                });
                return this;
            }

            @Override
            public DynamicLocationalUniformHolder uniform1f(String name, IntSupplier value, ValueUpdateNotifier notifier) {
                return this.uniform1f(name, (FloatSupplier) value::getAsInt, notifier);
            }

            @Override
            public DynamicLocationalUniformHolder uniform1f(String name, DoubleSupplier value, ValueUpdateNotifier notifier) {
                return this.uniform1f(name, (FloatSupplier)(() -> (float) value.getAsDouble()), notifier);
            }


            @Override
            public DynamicLocationalUniformHolder uniform3f(UniformUpdateFrequency updateFrequency, String name, Supplier<Vector3f> value) {
                return this.uniform3f(name, value, null);
            }

            @Override
            public DynamicLocationalUniformHolder uniform3f(String name, Supplier<Vector3f> value, ValueUpdateNotifier notifier) {
                this.injectDynamicUniformType(name, UniformType.VEC3, offset->{
                    return ptr->{
                      value.get().getToAddress(ptr+offset);
                    };
                });
                return this;
            }

            private void injectDynamicUniformType(String name, UniformType type, Long2ObjectFunction<LongConsumer> supplier) {
                var names = patch.getUniformList();
                for (int i = 0; i < names.length; i++) {
                    if (names[i].equals(name)) {
                        if (!seenUniforms.add(name)) {
                            // Duplicate: already registered by an earlier call (non-dynamic or dynamic pass). Skip.
                            return;
                        }
                        uniforms.add(new UniformWritingHolder(name, type, supplier));
                        break;
                    }
                }
            }

            @Override
            public DynamicUniformHolder uniformMatrix(String name, Supplier<Matrix4fc> value, ValueUpdateNotifier notifier) {
                this.injectDynamicUniformType(name, UniformType.MAT4, offset -> ptr -> {
                    value.get().getToAddress(ptr + offset);
                });
                return this;
            }

            @Override
            public DynamicUniformHolder uniform4f(String name, Supplier<Vector4f> value, ValueUpdateNotifier notifier) {
                this.injectDynamicUniformType(name, UniformType.VEC4, offset -> ptr -> {
                    value.get().getToAddress(ptr + offset);
                });
                return this;
            }

            @Override
            public DynamicLocationalUniformHolder uniform2f(String name, Supplier<Vector2f> value, ValueUpdateNotifier notifier) {
                this.injectDynamicUniformType(name, UniformType.VEC2, offset -> ptr -> {
                    value.get().getToAddress(ptr + offset);
                });
                return this;
            }

            @Override
            public DynamicLocationalUniformHolder uniform2i(String name, Supplier<Vector2i> value, ValueUpdateNotifier notifier) {
                this.injectDynamicUniformType(name, UniformType.VEC2I, offset -> ptr -> {
                    value.get().getToAddress(ptr + offset);
                });
                return this;
            }

            @Override
            public DynamicUniformHolder uniform4i(String name, Supplier<Vector4i> value, ValueUpdateNotifier notifier) {
                this.injectDynamicUniformType(name, UniformType.VEC4I, offset -> ptr -> {
                    value.get().getToAddress(ptr + offset);
                });
                return this;
            }

            @Override
            public DynamicUniformHolder uniform4fArray(String name, Supplier<float[]> value, ValueUpdateNotifier notifier) {
                // 4fArray uniforms are not mappable into std140 UBO cleanly; skip silently.
                return this;
            }

            // non-notifier variants from LocationalUniformHolder defaults call addUniform (no-op).
            // Override the ones that packs may declare in their uniforms[] list to capture them properly.

            @Override
            public LocationalUniformHolder uniform3d(UniformUpdateFrequency updateFrequency, String name, Supplier<Vector3d> value) {
                // Pack may declare vec3 skyColor / previousCameraPosition. Store as VEC3 float (precision loss OK for shading).
                this.injectDynamicUniformType(name, UniformType.VEC3, offset -> ptr -> {
                    var v = value.get();
                    MemoryUtil.memPutFloat(ptr + offset, (float) v.x);
                    MemoryUtil.memPutFloat(ptr + offset + 4, (float) v.y);
                    MemoryUtil.memPutFloat(ptr + offset + 8, (float) v.z);
                });
                return this;
            }

            @Override
            public LocationalUniformHolder uniform1b(UniformUpdateFrequency updateFrequency, String name, BooleanSupplier value) {
                this.injectDynamicUniformType(name, UniformType.INT, offset -> ptr -> {
                    MemoryUtil.memPutInt(ptr + offset, value.getAsBoolean() ? 1 : 0);
                });
                return this;
            }

            @Override
            public LocationalUniformHolder uniform4f(UniformUpdateFrequency updateFrequency, String name, Supplier<Vector4f> value) {
                this.injectDynamicUniformType(name, UniformType.VEC4, offset -> ptr -> {
                    value.get().getToAddress(ptr + offset);
                });
                return this;
            }

            @Override
            public LocationalUniformHolder uniformMatrix(UniformUpdateFrequency updateFrequency, String name, Supplier<Matrix4fc> value) {
                this.injectDynamicUniformType(name, UniformType.MAT4, offset -> ptr -> {
                    value.get().getToAddress(ptr + offset);
                });
                return this;
            }

            @Override
            public DynamicLocationalUniformHolder addDynamicUniform(Uniform uniform, ValueUpdateNotifier valueUpdateNotifier) {
                // Fallback for unimplemented typed paths — should not be reached after explicit overrides above.
                Logger.warn("[IrisVoxyRenderPipelineData] addDynamicUniform fallback hit for: " + uniform);
                return this;
            }

            @Override
            public LocationalUniformHolder addUniform(UniformUpdateFrequency uniformUpdateFrequency, Uniform uniform) {
                return this;
            }

            @Override
            public OptionalInt location(String uniformName, UniformType uniformType) {
                //Yes am aware how performant inefficent this is... just dont care tbh since is on setup and is small
                var names = patch.getUniformList();
                for (int i = 0; i < names.length; i++) {
                    if (names[i].equals(uniformName)) {
                        return OptionalInt.of(i);//Have a base uniform offset of 10
                    }
                }
                return OptionalInt.empty();
            }

            @Override
            public UniformHolder externallyManagedUniform(String s, UniformType uniformType) {
                return null;
            }
        };
        // Register all non-dynamic Iris uniforms first (includes vx* matrix uniforms via MixinMatrixUniforms,
        // plus cloudHeight, screenBrightness, skyColor, previousCameraPosition, endFlashIntensity, etc.)
        CommonUniforms.addNonDynamicUniforms(uniformBuilder, idMap, packDirectives, updateNotifier);
        // Dynamic uniforms (sunAngle, worldTime, fog params, etc.)
        CommonUniforms.addDynamicUniforms(uniformBuilder, FogMode.PER_FRAGMENT);
        // Note: duplicates from custom uniforms are silently skipped by injectDynamicUniformType.
        cu.assignTo(uniformBuilder);
        cu.mapholderToPass(uniformBuilder, patch);

        FunctionReturn cachedReturn = new FunctionReturn();
        ((CustomUniformsAccessor)cu).getLocationMap().get(patch).object2IntEntrySet().forEach(entry-> {
            if (!seenUniforms.add(entry.getKey().getName())) {
                // Skip: already registered by CommonUniforms or an earlier custom uniform.
                // Packs like BSL define worldTime/sunAngle as custom uniforms that duplicate Iris builtins.
                return;
            }
            uniforms.add(new UniformWritingHolder(entry.getKey().getName(), Type.convert(entry.getKey().getType()),offset->createWriter(offset, cachedReturn, entry.getKey())));
        });

        if (uniforms.size() != patch.getUniformList().length) {
            Set<String> uniformsUnseen = new HashSet<>(List.of(patch.getUniformList()));
            for (var uniform : uniforms) {
                uniformsUnseen.remove(uniform.name);
            }
            Logger.error("The following uniforms could not be found: [" + uniformsUnseen.stream().sorted(String::compareToIgnoreCase).collect(Collectors.joining(","))+"]");
            return new UniformSetResult(uniforms, uniformsUnseen);
        }
        //In _theory_ this should work?
        return new UniformSetResult(uniforms, Set.of());
    }

    record UniformSetResult(List<UniformWritingHolder> uniforms, Set<String> missingUniforms) {}

    private record TextureWSampler(String name, IntSupplier texture, IntSupplier sampler) { }
    /**
     * @param layout          GLSL declarations to prepend (sampler uniforms not declared in the patch source)
     * @param bindingFunction binds all samplers to their texture units at render time
     * @param patchSamplerBindings  name→binding-index (absolute) for samplers the patch source already declares;
     *                        used by patchOpaqueShader/patchTranslucentShader to inject layout(binding=N)
     */
    public record ImageSet(String layout, IntConsumer bindingFunction, Map<String, Integer> patchSamplerBindings) {
        // BASE_SAMPLER_BINDING_INDEX = 6 must match createImageSet and IrisVoxyRenderPipeline.BASE_SAMPLER_BINDING_INDEX_VALUE
        private static final int BASE_BINDING = 6;

        /** Inject layout(binding=N) qualifiers into sampler declarations in patch GLSL source.
         * Uses literal integers to avoid NVIDIA C1154 "non constant expression in layout value". */
        public String applyBindingsToSource(String source) {
            if (source == null || patchSamplerBindings.isEmpty()) return source;
            for (var entry : patchSamplerBindings.entrySet()) {
                String name = entry.getKey();
                int absoluteBinding = BASE_BINDING + entry.getValue();
                // Replace "uniform <type> <name>" with "layout(binding=N) uniform <type> <name>"
                // Handles both "uniform sampler2D name" and "uniform sampler2DShadow name" patterns.
                String pattern = "uniform\\s+(\\S+)\\s+" + java.util.regex.Pattern.quote(name) + "\\s*;";
                String replacement = "layout(binding=" + absoluteBinding + ") uniform $1 " + name + ";";
                source = source.replaceAll(pattern, replacement);
            }
            return source;
        }
    }

    private static final Pattern SAMPLER_UNIFORM_DECL_PATTERN =
            Pattern.compile("(?m)^\\s*(?:layout\\s*\\([^\\)]*\\)\\s*)?uniform\\s+((?:u?sampler\\w+))\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;");

    private static boolean patchRequiresVoxyDepthBridge(IrisShaderPatch patch) {
        String opaque = String.valueOf(patch.getPatchOpaqueSource());
        String trans = String.valueOf(patch.getPatchTranslucentSource());
        String all = opaque + "\n" + trans;
        return all.contains("vxDepthTexOpaque")
                || all.contains("vxDepthTexTrans")
                || all.contains("lod_mod_support");
    }

    private static Map<String, String> discoverSamplerUniforms(String source) {
        if (source == null || source.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, String> discovered = new LinkedHashMap<>();
        var matcher = SAMPLER_UNIFORM_DECL_PATTERN.matcher(source);
        while (matcher.find()) {
            String type = matcher.group(1);
            String name = matcher.group(2);
            if (type == null || name == null || type.isBlank() || name.isBlank()) {
                continue;
            }
            discovered.putIfAbsent(name, type);
        }
        return discovered;
    }

    private static ImageSet createImageSet(IrisRenderingPipeline ipipe, IrisShaderPatch patch) {
        var patchSamplerData = patch.getSamplerSet();
        if (patchSamplerData == null) return null;

        // Some packs (e.g. Photon voxy support) declare additional sampler uniforms in the
        // patch GLSL/includes but omit them from voxy.json samplers. Merge discovered uniforms
        // so they still receive binding + texture assignment.
        Map<String, String> samplerDataSet = new LinkedHashMap<>(patchSamplerData);
        var discoveredOpaqueSamplers = discoverSamplerUniforms(patch.getPatchOpaqueSource());
        var discoveredTransSamplers = discoverSamplerUniforms(patch.getPatchTranslucentSource());
        discoveredOpaqueSamplers.forEach(samplerDataSet::putIfAbsent);
        discoveredTransSamplers.forEach(samplerDataSet::putIfAbsent);
        if (samplerDataSet.size() != patchSamplerData.size()) {
            Set<String> added = new LinkedHashSet<>(samplerDataSet.keySet());
            added.removeAll(patchSamplerData.keySet());
            Logger.info("[IrisVoxyRenderPipelineData] Added implicit sampler uniforms from patch source: " + added);
        }

        Set<String> samplerNameSet = new LinkedHashSet<>(samplerDataSet.keySet());
        if (samplerNameSet.isEmpty()) return null;
        Set<TextureWSampler> samplerSet = new LinkedHashSet<>();
        Map<String, IntSupplier> externalTextures = new HashMap<>();
        externalTextures.put("lightmap", LightMapHelper::getLightmapTextureId);
        SamplerHolder samplerBuilder = new SamplerHolder() {
            @Override
            public boolean hasSampler(String s) {
                return samplerNameSet.contains(s);
            }

            public boolean hasSampler(String... names) {
                for (var name : names) {
                    if (samplerNameSet.contains(name)) return true;
                }
                return false;
            }

            private String name(String... names) {
                for (var name : names) {
                    if (samplerNameSet.contains(name)) return name;
                }
                return null;
            }

            @Override
            public boolean addDefaultSampler(TextureType type, IntSupplier texture, ValueUpdateNotifier notifier, GlSampler sampler, String... names) {
                Logger.error("Unsupported default sampler");
                return false;
            }

            @Override
            public boolean addDynamicSampler(TextureType type, IntSupplier texture, GlSampler sampler, String... names) {
                return this.addDynamicSampler(type, texture, null, sampler, names);
            }

            @Override
            public boolean addDynamicSampler(TextureType type, IntSupplier texture, ValueUpdateNotifier notifier, GlSampler sampler, String... names) {
                if (!this.hasSampler(names)) return false;
                samplerSet.add(new TextureWSampler(this.name(names), texture, sampler != null ? sampler::getId : () -> -1));
                return true;
            }

            @Override
            public void addExternalSampler(int texture, String... names) {
                if (!this.hasSampler(names)) return;
                var name = this.name(names);
                samplerSet.add(new TextureWSampler(name, externalTextures.getOrDefault(name, () -> texture), () -> -1));
            }
        };

        //Unsupported
        ImageHolder imageBuilder = new ImageHolder() {
            @Override
            public boolean hasImage(String s) {
                return false;
            }

            @Override
            public void addTextureImage(IntSupplier intSupplier, InternalTextureFormat internalTextureFormat, String s) {

            }
        };

        ipipe.addGbufferOrShadowSamplers(samplerBuilder, imageBuilder, ipipe::getFlippedAfterPrepare, false, true, true, false);

        //samplerSet contains our samplers
        Set<String> foundSamplerNames = samplerSet.stream().map(a -> a.name).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (samplerSet.size() != samplerNameSet.size()) {
            Set<String> missingSamplers = new java.util.LinkedHashSet<>(samplerNameSet);
            missingSamplers.removeAll(foundSamplerNames);
            Logger.error("[IrisVoxyRenderPipelineData] createImageSet: samplers NOT found in Iris pipeline: " + missingSamplers
                    + " | found: " + foundSamplerNames + " | requested: " + samplerNameSet);
            if (patchRequiresVoxyDepthBridge(patch)) {
                if (missingSamplers.contains("vxDepthTexOpaque") || missingSamplers.contains("vxDepthTexTrans")) {
                    throw new IllegalStateException("Required VOXY depth bridge samplers are unresolved: " + missingSamplers);
                }
            }
        } else {
            Logger.info("[IrisVoxyRenderPipelineData] createImageSet: all " + samplerSet.size() + " samplers resolved: " + foundSamplerNames);
        }

        // Build a map of sampler name -> binding index.
        // Samplers that the patch source already declares need their binding injected at shader-patch time
        // rather than via a standalone re-declaration (which NVIDIA rejects as "declaration conflicts").
        Map<String, Integer> patchSamplerBindings = new LinkedHashMap<>();
        String patchSources = String.valueOf(patch.getPatchOpaqueSource()) + "\n" + String.valueOf(patch.getPatchTranslucentSource());

        StringBuilder builder = new StringBuilder();
        TextureWSampler[] samplers = new TextureWSampler[samplerSet.size()];
        // BASE_SAMPLER_BINDING_INDEX = 6 (must match IrisVoxyRenderPipeline.BASE_SAMPLER_BINDING_INDEX_VALUE)
        final int BASE_BINDING = 6;
        int i = 0;
        for (var entry : samplerSet) {
            samplers[i]=entry;

            String samplerType = samplerDataSet.get(entry.name);
            // Check if the patch source actually declares this sampler as a uniform (not just uses the name).
            // Use a regex to find "uniform <type> <name>" — bare name usage (e.g. texture2D(noisetex,...)) does NOT count.
            boolean declaredInPatch = patchSources.matches("(?s).*\\buniform\\s+\\S+\\s+" + java.util.regex.Pattern.quote(entry.name) + "\\s*[;(,].*");
            if (declaredInPatch) {
                // Will inject layout(binding=N) into the patch source at shader-compile time.
                patchSamplerBindings.put(entry.name, i);
            } else {
                // Use literal integer binding (NVIDIA rejects macro expressions like (BASE+N) in layout qualifiers).
                builder.append("layout(binding=").append(BASE_BINDING + i).append(") uniform ").append(samplerType).append(" ").append(entry.name).append(";\n");
            }
            i++;
        }


        final int[] bindCallCounter = new int[]{0};
        IntConsumer bindingFunction = base->{
            bindCallCounter[0]++;
            boolean logSnapshot = bindCallCounter[0] == 1 || (bindCallCounter[0] % 300) == 0;
            StringBuilder snapshot = logSnapshot ? new StringBuilder() : null;
            for (int j = 0; j < samplers.length; j++) {
                int unit = j+base;
                var ts = samplers[j];
                int textureId = ts.texture.getAsInt();
                glBindTextureUnit(unit, textureId);
                int sampler = ts.sampler.getAsInt();
                if (sampler != -1) {
                    glBindSampler(unit, sampler);
                }//TODO: might need to bind sampler 0
                if (logSnapshot) {
                    if (j != 0) snapshot.append(", ");
                    snapshot.append(ts.name)
                            .append("@").append(unit)
                            .append("=tex").append(textureId)
                            .append("/samp").append(sampler);
                }
            }
            if (logSnapshot) {
                Logger.info("[IrisVoxyRenderPipelineData] Sampler bind snapshot call="
                        + bindCallCounter[0] + " base=" + base + " { " + snapshot + " }");
            }
        };
        return new ImageSet(builder.toString(), bindingFunction, Collections.unmodifiableMap(patchSamplerBindings));
    }

    public record SSBOSet(String layout, IntConsumer bindingFunction){}
    private record SSBOBinding(int irisIndex, int bindingOffset) {}
    private static SSBOSet createSSBOLayouts(Int2ObjectMap<String> ssbos, ShaderStorageBufferHolder ssboStore) {
        if (ssboStore == null) return null;//If there is no store, there cannot be any ssbos
        if (ssbos.isEmpty()) return null;
        // BASE_SSBO_BINDING_INDEX = 10 must match IrisVoxyRenderPipeline.SSBO_BINDING_BASE
        final int SSBO_BASE = 10;
        String header = "";
        if (ssbos.containsKey(-1)) header = ssbos.remove(-1);
        StringBuilder builder = new StringBuilder(header);
        builder.append("\n");
        SSBOBinding[] bindings = new SSBOBinding[ssbos.size()];
        int i = 0;
        for (var entry : ssbos.int2ObjectEntrySet()) {
            var val = entry.getValue();
            bindings[i] = new SSBOBinding(entry.getIntKey(), i);
            // Use literal integer binding to avoid NVIDIA C1154 "non constant expression in layout value".
            builder.append("layout(binding = ").append(SSBO_BASE + i).append(") restrict buffer IrisBufferBinding").append(i);
            builder.append(" ").append(val).append(";\n");
            i++;
        }
        //ssboStore.getBufferIndex()
        IntConsumer bindingFunction = base->{
            for (var binding : bindings) {
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, base+binding.bindingOffset, ssboStore.getBufferIndex(binding.irisIndex));
            }
        };
        return new SSBOSet(builder.toString(), bindingFunction);
    }
}
