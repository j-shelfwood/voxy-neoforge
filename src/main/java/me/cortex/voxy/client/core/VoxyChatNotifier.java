package me.cortex.voxy.client.core;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

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

    // Dedup: suppress identical notifications within this window.
    // Track per-key state because renderer/pipeline creation can emit different
    // notification types during the same join/reload sequence.
    private static final Map<String, Long> RECENT_KEYS = new HashMap<>();
    private static final long COOLDOWN_NANOS = 5_000_000_000L;
    private static final long RETENTION_NANOS = 60_000_000_000L;

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

    public static void resetSession(String reason) {
        synchronized (RECENT_KEYS) {
            RECENT_KEYS.clear();
        }
        me.cortex.voxy.common.Logger.info("[VoxyChatNotifier] Reset notification session; reason='" + reason + "'");
    }

    /** Returns true if this key was sent recently and the message should be suppressed. */
    private static boolean isDuplicate(String key) {
        long now = System.nanoTime();
        synchronized (RECENT_KEYS) {
            pruneExpired(now);
            Long last = RECENT_KEYS.get(key);
            RECENT_KEYS.put(key, now);
            return last != null && (now - last) < COOLDOWN_NANOS;
        }
    }

    private static void pruneExpired(long now) {
        Iterator<Map.Entry<String, Long>> iterator = RECENT_KEYS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if ((now - entry.getValue()) >= RETENTION_NANOS) {
                iterator.remove();
            }
        }
    }

    private static void sendFormatted(ChatFormatting color, String key, String text) {
        if (isDuplicate(key)) {
            me.cortex.voxy.common.Logger.info("[VoxyChatNotifier] Suppressed duplicate notification; key='" + key + "'");
            return;
        }
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
