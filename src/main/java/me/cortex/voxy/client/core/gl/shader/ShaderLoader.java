package me.cortex.voxy.client.core.gl.shader;


import me.cortex.voxy.common.Logger;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderConstants;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderParser;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NeoForge-compatible shader loader for Voxy.
 *
 * On Fabric, Sodium's ShaderLoader.getShaderSource() uses a flat classloader that can
 * access all mod resources. On NeoForge, each mod has an isolated classloader, so
 * Sodium's classloader cannot access Voxy's shader resources.
 *
 * This loader bypasses Sodium's resource loading and uses Voxy's own classloader.
 *
 * Upstream reference: https://github.com/MCRcortex/voxy
 * See: src/main/java/me/cortex/voxy/client/core/gl/shader/ShaderLoader.java
 */
public class ShaderLoader {
    private static final Pattern IMPORT_PATTERN = Pattern.compile("#import <(?<namespace>.*):(?<path>.*)>");

    // Sodium 0.8 changed ShaderParser.parseShader's return type from String to the
    // ParsedShader record (same parameters, source now behind .src()). Resolve the
    // method reflectively so one build runs on both Sodium 0.6.x and 0.8.x.
    private static final MethodHandle PARSE_SHADER;
    private static final MethodHandle PARSED_SHADER_SRC; // non-null only on Sodium 0.8+

    static {
        MethodHandle parse;
        MethodHandle src = null;
        try {
            var method = ShaderParser.class.getMethod("parseShader", String.class, ShaderConstants.class);
            parse = MethodHandles.lookup().unreflect(method);
            if (method.getReturnType() != String.class) {
                src = MethodHandles.lookup().unreflect(method.getReturnType().getMethod("src"));
            }
        } catch (ReflectiveOperationException e) {
            // Log before throwing: later touches of this class throw NoClassDefFoundError
            // without the cause, so this line is the one durable diagnostic.
            Logger.error("Failed to resolve Sodium's ShaderParser.parseShader - incompatible Sodium version?", e);
            throw new ExceptionInInitializerError(e);
        }
        PARSE_SHADER = parse;
        PARSED_SHADER_SRC = src;
    }

    /** Calls Sodium's shader preprocessor, unwrapping the 0.8 ParsedShader record when present. */
    private static String parseWithSodium(String source, ShaderConstants constants) {
        try {
            Object out = PARSE_SHADER.invoke(source, constants);
            return PARSED_SHADER_SRC == null ? (String) out : (String) PARSED_SHADER_SRC.invoke(out);
        } catch (RuntimeException | Error e) {
            throw e; // preserve pre-patch propagation exactly
        } catch (Throwable t) {
            throw new RuntimeException("Sodium shader preprocessing failed", t);
        }
    }

    /**
     * Parse and load a shader, matching upstream Voxy behavior.
     *
     * Upstream code:
     *   return "#version 460 core\n" + ShaderParser.parseShader(
     *       "\n#import <" + id + ">\n//beans", ShaderConstants.builder().build()
     *   ).src().replaceAll("\r\n", "\n").replaceFirst("\n#version .+\n", "\n");
     *
     * The key is the leading "\n" before #import - this ensures the regex
     * "\n#version .+\n" can match the #version directive in the loaded shader.
     */
    public static String parse(String id) {
        // Load shader source using Voxy's classloader (NeoForge classloader isolation fix)
        String shaderSource = getShaderSource(id);

        // Process any nested #import directives recursively
        shaderSource = processImports(shaderSource);

        // Match upstream format: "\n" + content + "\n//beans"
        // The leading \n is critical for the regex to work
        String processed = "\n" + shaderSource + "\n//beans";

        // Apply Sodium's shader constants processing (handles #define etc.)
        processed = parseWithSodium(processed, ShaderConstants.builder().build());

        // Normalize line endings and strip original #version (upstream behavior)
        processed = processed.replaceAll("\r\n", "\n");
        processed = processed.replaceFirst("\n#version .+\n", "\n");

        // Prepend our target GLSL version
        return "#version 460 core\n" + processed;
    }

    /**
     * Load shader source using Voxy's classloader.
     * Path format: "namespace:path" -> "/assets/{namespace}/shaders/{path}"
     */
    private static String getShaderSource(String id) {
        String[] parts = id.split(":", 2);
        String namespace = parts.length > 1 ? parts[0] : "voxy";
        String path = parts.length > 1 ? parts[1] : parts[0];

        String resourcePath = String.format("/assets/%s/shaders/%s", namespace, path);

        try (InputStream in = ShaderLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new RuntimeException("Shader not found: " + resourcePath + " (id=" + id + ")");
            }
            return IOUtils.toString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read shader source: " + resourcePath, e);
        }
    }

    /**
     * Process #import directives recursively, loading from Voxy's resources.
     */
    private static String processImports(String source) {
        StringBuilder result = new StringBuilder();
        for (String line : source.split("\n")) {
            if (line.trim().startsWith("#import")) {
                Matcher matcher = IMPORT_PATTERN.matcher(line.trim());
                if (matcher.matches()) {
                    String namespace = matcher.group("namespace");
                    String path = matcher.group("path");
                    String importId = namespace + ":" + path;
                    String importedSource = getShaderSource(importId);
                    result.append(processImports(importedSource));
                } else {
                    result.append(line);
                }
            } else {
                result.append(line);
            }
            result.append("\n");
        }
        return result.toString();
    }
}
