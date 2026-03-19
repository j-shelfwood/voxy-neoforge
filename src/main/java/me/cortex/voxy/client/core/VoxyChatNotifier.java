package me.cortex.voxy.client.core;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * Sends brief status messages to the player's chat overlay when Voxy's pipeline
 * changes (shader pack loaded, fallback triggered, etc.).
 *
 * All sends are deferred via {@code mc.execute()} so they always fire on the
 * game thread when the player object is guaranteed to exist.
 *
 * Duplicate suppression: identical messages within 5 seconds are silently dropped.
 * This handles the 2-3× recreate storm that happens on world join as Minecraft,
 * Iris, and Embeddium each fire allChanged() in sequence.
 */
public final class VoxyChatNotifier {
    private static final String PREFIX = "[Voxy] ";

    // Dedup: suppress identical notifications within this window
    private static volatile String lastKey = null;
    private static volatile long lastTimeMs = 0L;
    private static final long COOLDOWN_MS = 5_000L;

    private VoxyChatNotifier() {}

    // ── pipeline outcomes ────────────────────────────────────────────────────

    /** Shader pack loaded and Voxy has a dedicated voxy.json integration. */
    public static void notifyVoxyPatch(String packName) {
        sendFormatted(ChatFormatting.GREEN,
                "voxy_patch:" + packName,
                PREFIX + "Shader pack \"" + packName + "\" — Voxy integration active (voxy.json)");
    }

    /** Shader pack has dh_terrain.fsh; Voxy impersonates Distant Horizons. */
    public static void notifyDhNative(String packName) {
        sendFormatted(ChatFormatting.GREEN,
                "dh_native:" + packName,
                PREFIX + "Shader pack \"" + packName + "\" — Voxy integration active (DH native mode)");
    }

    /**
     * Shader pack is active but has no Voxy/DH support.
     * Voxy falls back to the normal (unlit) pipeline.
     */
    public static void notifyIrisFallback(String packName) {
        sendFormatted(ChatFormatting.YELLOW,
                "iris_fallback:" + packName,
                PREFIX + "Shader pack \"" + packName + "\" has no Voxy support — LODs rendered without shader integration");
    }

    /**
     * Iris pipeline was null / not instrumented at renderer creation time.
     * Voxy uses the normal pipeline; a recreate is expected on the next tick.
     */
    public static void notifyIrisPipelineNull() {
        sendFormatted(ChatFormatting.YELLOW,
                "iris_null",
                PREFIX + "Iris pipeline not ready — using fallback, will retry");
    }

    /** No shader pack active; using the standard pipeline. */
    public static void notifyNoPack() {
        // Intentionally silent — vanilla mode is the expected baseline.
    }

    // ── error states ─────────────────────────────────────────────────────────

    /** Exception building the Iris render pipeline (shader pack issue). */
    public static void notifyIrisPipelineError(String packName, String reason) {
        sendFormatted(ChatFormatting.RED,
                "iris_error:" + packName,
                PREFIX + "Failed to build Voxy pipeline for \"" + packName + "\": " + reason);
    }

    // ── internals ────────────────────────────────────────────────────────────

    /** Returns true if this key was sent recently and the message should be suppressed. */
    private static boolean isDuplicate(String key) {
        long now = System.currentTimeMillis();
        if (key.equals(lastKey) && (now - lastTimeMs) < COOLDOWN_MS) {
            return true;
        }
        lastKey = key;
        lastTimeMs = now;
        return false;
    }

    private static void sendFormatted(ChatFormatting color, String key, String text) {
        if (isDuplicate(key)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        MutableComponent component = Component.literal(text)
                .setStyle(Style.EMPTY.withColor(color).withBold(false));
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendSystemMessage(component);
            }
        });
    }
}
