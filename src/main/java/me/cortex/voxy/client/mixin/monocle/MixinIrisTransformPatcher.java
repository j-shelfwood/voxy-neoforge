package me.cortex.voxy.client.mixin.monocle;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import me.cortex.voxy.common.Logger;
import net.irisshaders.iris.gl.shader.ShaderCompileException;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Fixes Monocle Bug: "monocle_not_supported" corrupts compute shader source.
 *
 * Root cause: Monocle's taumc ANTLR grammar does not support GLSL sized array types as
 * function return types or parameters (e.g. "float[9] func(...)", "vec3[9] param").
 * When Photon's spherical harmonics utility (spherical_harmonics.glsl) is included in
 * a compute shader (deferred4_a.csh) and processed by Monocle's TransformPatcherMixin,
 * the taumc parser fails and emits the sentinel token "monocle_not_supported" into the
 * output. Iris's own glsl_transformer then fails to parse this corrupted result, throwing
 * ShaderCompileException and killing the entire shader pipeline load.
 *
 * Fix: redirect the TransformPatcher.patchCompute() call inside CompositeRenderer so
 * that ShaderCompileExceptions caused by "monocle_not_supported" corruption are caught
 * and the compute shader is skipped (null returned) rather than crashing the pipeline.
 * Compute shaders are optional rendering features (sky SH, LPV) — skipping them
 * degrades those specific effects but allows the pack to load successfully.
 */
@Mixin(value = net.irisshaders.iris.pipeline.CompositeRenderer.class, remap = false)
public class MixinIrisTransformPatcher {

    @Redirect(
        method = "createComputes",
        at = @At(
            value = "INVOKE",
            target = "Lnet/irisshaders/iris/pipeline/transform/TransformPatcher;patchCompute(Ljava/lang/String;Ljava/lang/String;Lnet/irisshaders/iris/shaderpack/texture/TextureStage;Lit/unimi/dsi/fastutil/objects/Object2ObjectMap;)Ljava/lang/String;"
        ),
        remap = false,
        require = 0
    )
    private String safeWrapPatchCompute(
        String name,
        String compute,
        TextureStage stage,
        Object2ObjectMap<?, ?> textureMap
    ) {
        try {
            @SuppressWarnings("unchecked")
            Object2ObjectMap<net.irisshaders.iris.helpers.Tri<String, net.irisshaders.iris.gl.texture.TextureType, TextureStage>, String> typedMap =
                (Object2ObjectMap<net.irisshaders.iris.helpers.Tri<String, net.irisshaders.iris.gl.texture.TextureType, TextureStage>, String>) textureMap;
            return TransformPatcher.patchCompute(name, compute, stage, typedMap);
        } catch (ShaderCompileException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            String cause = e.getCause() != null ? e.getCause().getMessage() : "";
            if (msg.contains("monocle_not_supported") || cause.contains("monocle_not_supported")) {
                Logger.warn("[MonocleShaderFix] Compute shader '" + name
                    + "' failed due to Monocle parse corruption ('monocle_not_supported'). "
                    + "Skipping this compute stage — visual effect degraded but pack will load.");
                return null;
            }
            throw e;
        }
    }
}
