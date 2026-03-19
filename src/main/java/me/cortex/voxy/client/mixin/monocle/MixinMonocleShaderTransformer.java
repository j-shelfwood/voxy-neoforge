package me.cortex.voxy.client.mixin.monocle;

import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import me.cortex.voxy.common.Logger;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Fixes Monocle Bug: iris_MidTex / iris_Entity undefined in transformed fragment shaders.
 *
 * Monocle's replaceMidTexCoord() / replaceMCEntity() rename mc_midTexCoord→iris_MidTex
 * and mc_Entity→iris_Entity in ALL shader stage references, but only inject the definition
 * into the vertex shader stage (where the attribute declaration lives). In the fragment
 * shader, if mc_midTexCoord or mc_Entity appear as referenced identifiers (e.g. via a
 * shared include), Monocle renames them to iris_MidTex/iris_Entity but never injects
 * the corresponding definition — leaving the GLSL compiler with an undefined variable.
 *
 * Fix: after ShaderTransformer.transform() completes, inspect the fragment output. If it
 * references iris_MidTex or iris_Entity without a definition, inject a zero-value
 * fallback definition immediately after the #version line so the shader compiles.
 * This is a graceful degradation — the feature using mc_midTexCoord (POM tile borders)
 * simply gets zeroed data, which is visually acceptable.
 */
@Mixin(targets = "dev.ferriarnus.monocle.ShaderTransformer", remap = false)
public class MixinMonocleShaderTransformer {

    @Inject(
        method = "transform",
        at = @At("RETURN"),
        remap = false,
        require = 0
    )
    private static void fixFragmentUndefinedVariables(
        String name,
        String vertex, String geometry, String tessControl, String tessEval, String fragment,
        AlphaTest alpha,
        @Coerce Object vertexType,
        Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap,
        CallbackInfoReturnable<Map<PatchShaderType, String>> cir
    ) {
        Map<PatchShaderType, String> result = cir.getReturnValue();
        if (result == null) return;

        String frag = result.get(PatchShaderType.FRAGMENT);
        if (frag == null) return;

        // Detect usage without definition.
        // After Monocle's transform, iris_MidTex definitions look like "vec2 iris_MidTex ="
        // If the fragment has "iris_MidTex" references but no such definition, it's broken.
        boolean needsMidTex = frag.contains("iris_MidTex") && !frag.contains("iris_MidTex =");
        // iris_Entity definitions: "ivec2 iris_Entity =" / "uint iris_Entity =" etc.
        boolean needsEntity = frag.contains("iris_Entity") && !frag.contains("iris_Entity =");

        if (!needsMidTex && !needsEntity) return;

        Logger.warn("[MonocleShaderFix] Fragment shader '" + name + "' references undefined Monocle variables:"
            + (needsMidTex ? " iris_MidTex" : "") + (needsEntity ? " iris_Entity" : "")
            + " — injecting zero fallback definitions");

        // Inject after the first newline (end of #version line)
        int versionEnd = frag.indexOf('\n');
        if (versionEnd < 0) {
            versionEnd = frag.length();
        }

        StringBuilder inject = new StringBuilder();
        if (needsMidTex) {
            // mc_midTexCoord is used for POM tile border detection; zeroing disables it without crashing.
            inject.append("\nvec2 iris_MidTex = vec2(0.0);");
        }
        if (needsEntity) {
            // Determine type from vertex shader output or default to ivec2 (most common Monocle output)
            String vertSrc = result.get(PatchShaderType.VERTEX);
            if (vertSrc != null && vertSrc.contains("uint iris_Entity")) {
                inject.append("\nuint iris_Entity = 0u;");
            } else if (vertSrc != null && vertSrc.contains("ivec4 iris_Entity")) {
                inject.append("\nivec4 iris_Entity = ivec4(0);");
            } else {
                inject.append("\nivec2 iris_Entity = ivec2(0);");
            }
        }

        String fixed = frag.substring(0, versionEnd) + inject + frag.substring(versionEnd);
        result.put(PatchShaderType.FRAGMENT, fixed);
    }
}
