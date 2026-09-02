package me.cortex.voxy.client.core.gl.shader;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderConstants;
import org.apache.commons.io.IOUtils;

public class ShaderLoader {
   private static final Pattern IMPORT_PATTERN = Pattern.compile("#import <(?<namespace>.*):(?<path>.*)>");

   public static String parse(String id) {
      String shaderSource = getShaderSource(id);
      shaderSource = processImports(shaderSource);
      String processed = "\n" + shaderSource + "\n//beans";
      processed = parseWithSodium(processed);
      processed = processed.replaceAll("\r\n", "\n");
      processed = processed.replaceFirst("\n#version .+\n", "\n");
      return "#version 460 core\n" + processed;
   }

   private static String parseWithSodium(String source) {
      try {
         Object parsed = Class.forName("net.caffeinemc.mods.sodium.client.gl.shader.ShaderParser")
            .getMethod("parseShader", String.class, ShaderConstants.class)
            .invoke(null, source, ShaderConstants.builder().build());
         if (parsed instanceof String parsedSource) {
            return parsedSource;
         } else if (parsed != null && parsed.getClass().getMethod("src").invoke(parsed) instanceof String parsedSource) {
            return parsedSource;
         } else {
            throw new IllegalStateException("Unsupported Sodium ShaderParser result: " + (parsed == null ? "null" : parsed.getClass().getName()));
         }
      } catch (InvocationTargetException var4) {
         Throwable cause = var4.getCause();
         if (cause instanceof RuntimeException runtimeException) {
            throw runtimeException;
         } else if (cause instanceof Error error) {
            throw error;
         } else {
            throw new RuntimeException("Failed to parse shader with Sodium", cause);
         }
      } catch (ReflectiveOperationException var5) {
         throw new RuntimeException("Failed to parse shader with Sodium", var5);
      }
   }

   private static String getShaderSource(String id) {
      String[] parts = id.split(":", 2);
      String namespace = parts.length > 1 ? parts[0] : "voxy";
      String path = parts.length > 1 ? parts[1] : parts[0];
      String resourcePath = String.format("/assets/%s/shaders/%s", namespace, path);

      try {
         String var6;
         try (InputStream in = ShaderLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
               throw new RuntimeException("Shader not found: " + resourcePath + " (id=" + id + ")");
            }

            var6 = IOUtils.toString(in, StandardCharsets.UTF_8);
         }

         return var6;
      } catch (IOException var10) {
         throw new RuntimeException("Failed to read shader source: " + resourcePath, var10);
      }
   }

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
