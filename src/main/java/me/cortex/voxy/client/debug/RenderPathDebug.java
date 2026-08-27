package me.cortex.voxy.client.debug;

import me.cortex.voxy.common.Logger;

import java.util.HashSet;
import java.util.Set;

public final class RenderPathDebug {
    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("voxy.cloudDebug", "true"));

    private static final Set<String> seenVanillaCloudKeys = new HashSet<>();
    private static final Set<String> seenEmbeddiumCloudKeys = new HashSet<>();
    private static final Set<String> seenFogKeys = new HashSet<>();

    private RenderPathDebug() {
    }

    public static void logVanillaClouds(String reason, boolean shaderPackEnabled,
                                        int configuredChunks, int renderDistanceBlocks,
                                        int tileRadius) {
        if (!ENABLED) {
            return;
        }
        String key = reason + "|" + shaderPackEnabled + "|" + configuredChunks + "|" + renderDistanceBlocks + "|" + tileRadius;
        if (!seenVanillaCloudKeys.add(key)) {
            return;
        }
        Logger.info("[CloudDiag] vanillaClouds reason=", reason,
                " shaderPack=", shaderPackEnabled,
                " configuredChunks=", configuredChunks,
                " renderDistanceBlocks=", renderDistanceBlocks,
                " tileRadius=", tileRadius);
    }

    public static void logEmbeddiumClouds(boolean shaderPackEnabled, String reason,
                                          int originalDistance, int targetDistance,
                                          int appliedDistance, String cloudsType) {
        if (!ENABLED) {
            return;
        }
        String key = shaderPackEnabled + "|" + reason + "|" + originalDistance + "|" + targetDistance + "|" + appliedDistance + "|" + cloudsType;
        if (!seenEmbeddiumCloudKeys.add(key)) {
            return;
        }
        Logger.info("[CloudDiag] embeddiumClouds reason=", reason,
                " shaderPack=", shaderPackEnabled,
                " cloudsType=", cloudsType,
                " originalDistance=", originalDistance,
                " targetDistance=", targetDistance,
                " appliedDistance=", appliedDistance);
    }

    public static void logFog(String source, String reason, boolean shaderPackEnabled,
                              boolean voxyRenderingEnabled, String fogMode,
                              float nearPlane, float farPlane) {
        if (!ENABLED) {
            return;
        }
        String key = source + "|" + reason + "|" + shaderPackEnabled + "|" + voxyRenderingEnabled + "|" + fogMode + "|" + nearPlane + "|" + farPlane;
        if (!seenFogKeys.add(key)) {
            return;
        }
        Logger.info("[CloudDiag] fog source=", source,
                " reason=", reason,
                " shaderPack=", shaderPackEnabled,
                " voxyRendering=", voxyRenderingEnabled,
                " fogMode=", fogMode,
                " near=", nearPlane,
                " far=", farPlane);
    }
}
