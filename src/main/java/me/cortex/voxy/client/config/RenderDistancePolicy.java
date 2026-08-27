package me.cortex.voxy.client.config;

public final class RenderDistancePolicy {
    private static final int MIN_RENDER_DISTANCE = 2;
    private static final int SECTION_TO_CHUNK_MULTIPLIER = 32;
    private static final int SAFETY_RINGS =
            Integer.getInteger("voxy.renderDistanceSafetyRings", 2);
    private static final float TERRAIN_FOG_FRACTION =
            Float.parseFloat(System.getProperty("voxy.terrainFogFraction", "0.1"));
    private static final float MIN_TERRAIN_FOG_SPAN = 64.0f;
    private static final float MAX_TERRAIN_FOG_SPAN = 2048.0f;

    private RenderDistancePolicy() {
    }

    public static int getSafetyRings() {
        return SAFETY_RINGS;
    }

    public static int getEffectiveSectionRenderDistance() {
        return getEffectiveSectionRenderDistance(VoxyConfig.CONFIG.getSectionRenderDistance());
    }

    public static int getEffectiveSectionRenderDistance(int configuredRenderDistance) {
        return Math.max(MIN_RENDER_DISTANCE, configuredRenderDistance + SAFETY_RINGS);
    }

    public static int getConfiguredRenderDistanceChunks() {
        return VoxyConfig.CONFIG.getSectionRenderDistance() * SECTION_TO_CHUNK_MULTIPLIER;
    }

    public static int getConfiguredRenderDistanceBlocks() {
        return getConfiguredRenderDistanceChunks() * 16;
    }

    public static int getVanillaCloudTileRadius() {
        return Math.max(3, (int) Math.ceil(getConfiguredRenderDistanceBlocks() / 8.0) + 1);
    }

    public static int getEmbeddiumCloudRenderDistanceChunks() {
        int desiredBlocks = getConfiguredRenderDistanceBlocks();

        // Embeddium turns render-distance chunks into cloud radius via:
        // cloudDistance = renderDistance * 2 + 9
        // worldRadius ~= cloudDistance * 12 blocks
        // Therefore: worldRadius ~= renderDistance * 24 + 108.
        return Math.max(1, (int) Math.ceil(Math.max(0, desiredBlocks - 108) / 24.0));
    }

    public static float getExtendedFogEndBlocks(float vanillaFarPlaneDistance) {
        return Math.max(vanillaFarPlaneDistance, getConfiguredRenderDistanceBlocks());
    }

    public static float getExtendedTerrainFogStartBlocks(float fogEndBlocks) {
        float fogSpan = Math.max(MIN_TERRAIN_FOG_SPAN,
                Math.min(MAX_TERRAIN_FOG_SPAN, fogEndBlocks * TERRAIN_FOG_FRACTION));
        return Math.max(0.0f, fogEndBlocks - fogSpan);
    }

    public static float getTraversalDistanceSquaredBlocks() {
        int effectiveSectionDistance = getEffectiveSectionRenderDistance();
        return (float) Math.pow(effectiveSectionDistance * 16 * SECTION_TO_CHUNK_MULTIPLIER, 2);
    }
}
