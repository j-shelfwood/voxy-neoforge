package me.cortex.voxy.client.config;

import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NeoForge config integration for Voxy.
 * This is the SINGLE SOURCE OF TRUTH for all Voxy settings.
 *
 * Config file: config/voxy-client.toml
 *
 * VoxyConfig delegates to this class for all values.
 * Changes via Embeddium UI are synced here and persisted to TOML.
 */
@EventBusSubscriber(modid = "voxy", bus = EventBusSubscriber.Bus.MOD)
public class VoxyNeoForgeConfig {
    private static final boolean DEFAULT_ENABLED = true;
    private static final boolean DEFAULT_ENABLE_RENDERING = true;
    private static final boolean DEFAULT_INGEST_ENABLED = true;
    private static final int DEFAULT_SECTION_RENDER_DISTANCE = 16;
    private static final boolean DEFAULT_CAMERA_DISTANCE_CULLING = true;
    private static final boolean DEFAULT_VISIBILITY_CULLING = true;
    private static final int DEFAULT_SERVICE_THREADS = Math.max((int) (CpuLayout.getCoreCount() / 1.5), 1);
    private static final double DEFAULT_SUB_DIVISION_SIZE = 64.0;
    private static final boolean DEFAULT_USE_ENVIRONMENTAL_FOG = true;
    private static final boolean DEFAULT_SHADER_PACK_FOG_OVERRIDE = true;
    private static final boolean DEFAULT_DONT_USE_EMBEDDIUM_BUILDER_THREADS = false;
    private static final int DEFAULT_EARTH_CURVE_RATIO = 0;
    private static final boolean DEFAULT_RENDER_STATISTICS = false;
    private static final boolean DEFAULT_LOADING_INDICATOR = true;
    private static final int DEFAULT_RENDER_DISTANCE_OFFSET = 0;
    private static final int DEFAULT_LOD_BOUNDARY_BUFFER = 0;
    private static final int DEFAULT_SECTION_VISIBILITY_CULL_OVERSCAN = 32;

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // General settings
    static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Enable Voxy LOD rendering system")
            .define("enabled", DEFAULT_ENABLED);

    static final ModConfigSpec.BooleanValue ENABLE_RENDERING = BUILDER
            .comment("Enable LOD terrain rendering (can be disabled while keeping data ingestion)")
            .define("enableRendering", DEFAULT_ENABLE_RENDERING);

    static final ModConfigSpec.BooleanValue INGEST_ENABLED = BUILDER
            .comment("Enable automatic chunk data ingestion for LOD generation")
            .define("ingestEnabled", DEFAULT_INGEST_ENABLED);

    // Performance settings
    static final ModConfigSpec.IntValue SECTION_RENDER_DISTANCE = BUILDER
            .comment("LOD section render distance (multiplied by 32 for actual chunk distance)",
                     "Example: 16 = 512 chunks render distance")
            .defineInRange("sectionRenderDistance", DEFAULT_SECTION_RENDER_DISTANCE, 2, 64);
    static final ModConfigSpec.BooleanValue CAMERA_DISTANCE_CULLING = BUILDER
            .comment("Cull/unload distant top-level LOD nodes based on camera position",
                     "Disable for testing traversal/camera-move CPU behavior (uses more memory)")
            .define("cameraDistanceCulling", DEFAULT_CAMERA_DISTANCE_CULLING);
    static final ModConfigSpec.BooleanValue VISIBILITY_CULLING = BUILDER
            .comment("Enable frustum/HiZ/section visibility culling",
                     "Disabling this can reduce CPU orchestration but significantly increases GPU load")
            .define("visibilityCulling", DEFAULT_VISIBILITY_CULLING);

    static final ModConfigSpec.IntValue SERVICE_THREADS = BUILDER
            .comment("Number of background threads for LOD processing",
                     "Default is based on CPU core count.")
            .defineInRange("serviceThreads", DEFAULT_SERVICE_THREADS, 1, CpuLayout.getCoreCount());

    static final ModConfigSpec.DoubleValue SUB_DIVISION_SIZE = BUILDER
            .comment("Subdivision size for LOD rendering (28-256)",
                     "Lower = more detailed LODs but more GPU load")
            .defineInRange("subDivisionSize", DEFAULT_SUB_DIVISION_SIZE, 28.0, 256.0);

    // Visual settings
    static final ModConfigSpec.BooleanValue USE_ENVIRONMENTAL_FOG = BUILDER
            .comment("Apply environmental fog to LOD terrain")
            .define("useEnvironmentalFog", DEFAULT_USE_ENVIRONMENTAL_FOG);

    static final ModConfigSpec.BooleanValue SHADER_PACK_FOG_OVERRIDE = BUILDER
            .comment("Extend fog distance when shader packs are active",
                     "Prevents distant LODs from being fully fogged out by shader packs")
            .define("shaderPackFogOverride", DEFAULT_SHADER_PACK_FOG_OVERRIDE);

    // Advanced settings
    static final ModConfigSpec.BooleanValue DONT_USE_EMBEDDIUM_BUILDER_THREADS = BUILDER
            .comment("Don't share threads with Embeddium's chunk builder")
            .define("dontUseEmbeddiumBuilderThreads", DEFAULT_DONT_USE_EMBEDDIUM_BUILDER_THREADS);

    // World curvature (experimental, LOD-only - vanilla chunks are not affected)
    static final ModConfigSpec.IntValue EARTH_CURVE_RATIO = BUILDER
            .comment("World curvature effect - simulates standing on a spherical planet (LOD terrain only)",
                     "0 = disabled (flat world)",
                     "5 = subtle curvature visible at long distances",
                     "50 = strong curvature (very small planet feel)",
                     "250 = extreme curvature",
                     "Note: only affects LOD terrain, vanilla chunks remain flat.",
                     "Inspired by Distant Horizons' earth curvature feature")
            .defineInRange("earthCurveRatio", DEFAULT_EARTH_CURVE_RATIO, 0, 250);

    // Debug settings
    static final ModConfigSpec.BooleanValue RENDER_STATISTICS = BUILDER
            .comment("Show render statistics in F3 debug screen",
                     "Displays LOD traversal counts, visible sections, and quad counts")
            .define("renderStatistics", DEFAULT_RENDER_STATISTICS);

    static final ModConfigSpec.BooleanValue LOADING_INDICATOR = BUILDER
            .comment("Show Voxy loading/progress indicator overlay")
            .define("loadingIndicator", DEFAULT_LOADING_INDICATOR);

    static final ModConfigSpec.IntValue RENDER_DISTANCE_OFFSET = BUILDER
            .comment("Extra chunk rings for depth mask radius (live update)",
                    "Positive values grow vanilla mask, negative values shrink it")
            .defineInRange("renderDistanceOffset", DEFAULT_RENDER_DISTANCE_OFFSET, -16, 16);

    static final ModConfigSpec.IntValue LOD_BOUNDARY_BUFFER = BUILDER
            .comment("Fine-tune LOD handoff boundary in blocks (live update)",
                    "Positive shrinks mask inward (more LOD bleed), negative expands mask")
            .defineInRange("lodBoundaryBuffer", DEFAULT_LOD_BOUNDARY_BUFFER, -128, 128);

    static final ModConfigSpec.IntValue SECTION_VISIBILITY_CULL_OVERSCAN = BUILDER
            .comment("Extra world-space padding in blocks for per-section visibility culling",
                    "Reduces screen-edge pop-in while panning the camera at the cost of rendering a small offscreen margin")
            .defineInRange("sectionVisibilityCullOverscan", DEFAULT_SECTION_VISIBILITY_CULL_OVERSCAN, 0, 128);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private static boolean configLoaded = false;

    /**
     * Register the config with NeoForge.
     * Call this during mod construction.
     */
    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, SPEC, "voxy-client.toml");
    }

    /**
     * Called when config is loaded or reloaded.
     * Updates runtime-only settings that aren't read directly from config values.
     */
    private static void onConfigChanged() {
        configLoaded = true;
        // RenderStatistics is a runtime-only flag
        RenderStatistics.enabled = RENDER_STATISTICS.get();
    }

    @SubscribeEvent
    public static void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            onConfigChanged();
        }
    }

    @SubscribeEvent
    public static void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            onConfigChanged();
        }
    }

    /**
     * Check if config has been loaded by NeoForge.
     * Before config is loaded, getters return default values.
     */
    public static boolean isConfigLoaded() {
        return configLoaded;
    }

    /**
     * Save all current config values to the TOML file.
     * Call this after modifying values via set methods.
     */
    public static void save() {
        if (configLoaded) {
            SPEC.save();
        }
    }

    // ========== Getters ==========

    public static boolean isEnabled() {
        if (!configLoaded) return DEFAULT_ENABLED;
        try {
            return ENABLED.get();
        } catch (IllegalStateException ignored) {
            return DEFAULT_ENABLED;
        }
    }

    public static boolean isRenderingEnabled() {
        if (!configLoaded) return DEFAULT_ENABLE_RENDERING;
        try {
            return ENABLE_RENDERING.get();
        } catch (IllegalStateException ignored) {
            return DEFAULT_ENABLE_RENDERING;
        }
    }

    public static boolean isIngestEnabled() {
        if (!configLoaded) return DEFAULT_INGEST_ENABLED;
        try {
            return INGEST_ENABLED.get();
        } catch (IllegalStateException ignored) {
            return DEFAULT_INGEST_ENABLED;
        }
    }

    public static int getSectionRenderDistance() {
        if (!configLoaded) return DEFAULT_SECTION_RENDER_DISTANCE;
        try {
            return SECTION_RENDER_DISTANCE.get();
        } catch (IllegalStateException ignored) {
            return DEFAULT_SECTION_RENDER_DISTANCE;
        }
    }

    public static boolean isCameraDistanceCullingEnabled() {
        if (!configLoaded) return DEFAULT_CAMERA_DISTANCE_CULLING;
        try {
            return CAMERA_DISTANCE_CULLING.get();
        } catch (IllegalStateException ignored) {
            return DEFAULT_CAMERA_DISTANCE_CULLING;
        }
    }

    public static boolean isVisibilityCullingEnabled() {
        if (!configLoaded) return DEFAULT_VISIBILITY_CULLING;
        try {
            return VISIBILITY_CULLING.get();
        } catch (IllegalStateException ignored) {
            return DEFAULT_VISIBILITY_CULLING;
        }
    }

    public static int getServiceThreads() {
        if (!configLoaded) return DEFAULT_SERVICE_THREADS;
        try {
            return SERVICE_THREADS.get();
        } catch (IllegalStateException ignored) {
            return DEFAULT_SERVICE_THREADS;
        }
    }

    public static float getSubDivisionSize() {
        if (!configLoaded) return (float) DEFAULT_SUB_DIVISION_SIZE;
        try {
            return SUB_DIVISION_SIZE.get().floatValue();
        } catch (IllegalStateException ignored) {
            return (float) DEFAULT_SUB_DIVISION_SIZE;
        }
    }

    public static boolean useEnvironmentalFog() {
        if (!configLoaded) return DEFAULT_USE_ENVIRONMENTAL_FOG;
        try {
            return USE_ENVIRONMENTAL_FOG.get();
        } catch (IllegalStateException ignored) {
            return DEFAULT_USE_ENVIRONMENTAL_FOG;
        }
    }

    public static boolean enableShaderPackFogOverride() {
        if (!configLoaded) return DEFAULT_SHADER_PACK_FOG_OVERRIDE;
        try {
            return SHADER_PACK_FOG_OVERRIDE.get();
        } catch (IllegalStateException ignored) {
            return DEFAULT_SHADER_PACK_FOG_OVERRIDE;
        }
    }

    public static boolean dontUseEmbeddiumBuilderThreads() {
        if (!configLoaded) return DEFAULT_DONT_USE_EMBEDDIUM_BUILDER_THREADS;
        try {
            return DONT_USE_EMBEDDIUM_BUILDER_THREADS.get();
        } catch (IllegalStateException ignored) {
            return DEFAULT_DONT_USE_EMBEDDIUM_BUILDER_THREADS;
        }
    }

    public static boolean isRenderStatisticsEnabled() {
        if (!configLoaded) return DEFAULT_RENDER_STATISTICS;
        try {
            return RENDER_STATISTICS.get();
        } catch (IllegalStateException ignored) {
            return DEFAULT_RENDER_STATISTICS;
        }
    }

    public static int getEarthCurveRatio() {
        if (!configLoaded) return DEFAULT_EARTH_CURVE_RATIO;
        try {
            return EARTH_CURVE_RATIO.get();
        } catch (IllegalStateException ignored) {
            return DEFAULT_EARTH_CURVE_RATIO;
        }
    }

    public static boolean isLoadingIndicatorEnabled() {
        if (!configLoaded) return DEFAULT_LOADING_INDICATOR;
        try {
            return LOADING_INDICATOR.get();
        } catch (IllegalStateException ignored) {
            return DEFAULT_LOADING_INDICATOR;
        }
    }

    public static int getRenderDistanceOffset() {
        if (!configLoaded) return DEFAULT_RENDER_DISTANCE_OFFSET;
        try {
            return RENDER_DISTANCE_OFFSET.get();
        } catch (IllegalStateException ignored) {
            return DEFAULT_RENDER_DISTANCE_OFFSET;
        }
    }

    public static int getLodBoundaryBuffer() {
        if (!configLoaded) return DEFAULT_LOD_BOUNDARY_BUFFER;
        try {
            return LOD_BOUNDARY_BUFFER.get();
        } catch (IllegalStateException ignored) {
            return DEFAULT_LOD_BOUNDARY_BUFFER;
        }
    }

    public static int getSectionVisibilityCullOverscan() {
        if (!configLoaded) return DEFAULT_SECTION_VISIBILITY_CULL_OVERSCAN;
        try {
            return SECTION_VISIBILITY_CULL_OVERSCAN.get();
        } catch (IllegalStateException ignored) {
            return DEFAULT_SECTION_VISIBILITY_CULL_OVERSCAN;
        }
    }

    // ========== Setters (for Embeddium UI integration) ==========

    public static void setEnabled(boolean value) {
        ENABLED.set(value);
    }

    public static void setRenderingEnabled(boolean value) {
        ENABLE_RENDERING.set(value);
    }

    public static void setIngestEnabled(boolean value) {
        INGEST_ENABLED.set(value);
    }

    public static void setSectionRenderDistance(int value) {
        SECTION_RENDER_DISTANCE.set(value);
    }

    public static void setCameraDistanceCullingEnabled(boolean value) {
        CAMERA_DISTANCE_CULLING.set(value);
    }

    public static void setVisibilityCullingEnabled(boolean value) {
        VISIBILITY_CULLING.set(value);
    }

    public static void setServiceThreads(int value) {
        SERVICE_THREADS.set(value);
    }

    public static void setSubDivisionSize(float value) {
        SUB_DIVISION_SIZE.set((double) value);
    }

    public static void setUseEnvironmentalFog(boolean value) {
        USE_ENVIRONMENTAL_FOG.set(value);
    }

    public static void setShaderPackFogOverride(boolean value) {
        SHADER_PACK_FOG_OVERRIDE.set(value);
    }

    public static void setDontUseEmbeddiumBuilderThreads(boolean value) {
        DONT_USE_EMBEDDIUM_BUILDER_THREADS.set(value);
    }

    public static void setEarthCurveRatio(int value) {
        EARTH_CURVE_RATIO.set(value);
    }

    public static void setRenderStatistics(boolean value) {
        RENDER_STATISTICS.set(value);
        RenderStatistics.enabled = value;
    }

    public static void setLoadingIndicatorEnabled(boolean value) {
        LOADING_INDICATOR.set(value);
    }

    public static void setRenderDistanceOffset(int value) {
        RENDER_DISTANCE_OFFSET.set(value);
    }

    public static void setLodBoundaryBuffer(int value) {
        LOD_BOUNDARY_BUFFER.set(value);
    }

    public static void setSectionVisibilityCullOverscan(int value) {
        SECTION_VISIBILITY_CULL_OVERSCAN.set(value);
    }
}
