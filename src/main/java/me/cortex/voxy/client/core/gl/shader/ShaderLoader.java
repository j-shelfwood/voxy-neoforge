package me.cortex.voxy.client.core.gl.shader;


import net.caffeinemc.mods.sodium.client.gl.shader.ShaderConstants;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
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

        // Apply Sodium's shader constants processing (handles #define etc.).
        // Sodium 0.6 returned String; Sodium 0.8 returns ShaderParser.ParsedShader.
        processed = parseWithSodium(processed);

        // Normalize line endings and strip original #version (upstream behavior)
        processed = processed.replaceAll("\r\n", "\n");
        processed = processed.replaceFirst("\n#version .+\n", "\n");

        // Prepend our target GLSL version
        return "#version 460 core\n" + processed;
    }

    private static String parseWithSodium(String source) {
        try {
            Object parsed = Class.forName("net.caffeinemc.mods.sodium.client.gl.shader.ShaderParser")
                    .getMethod("parseShader", String.class, ShaderConstants.class)
                    .invoke(null, source, ShaderConstants.builder().build());

            if (parsed instanceof String parsedSource) {
                return parsedSource;
            }
            if (parsed != null) {
                Object src = parsed.getClass().getMethod("src").invoke(parsed);
                if (src instanceof String parsedSource) {
                    return parsedSource;
                }
            }

            throw new IllegalStateException("Unsupported Sodium ShaderParser result: " +
                    (parsed == null ? "null" : parsed.getClass().getName()));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException("Failed to parse shader with Sodium", cause);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to parse shader with Sodium", e);
        }
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
