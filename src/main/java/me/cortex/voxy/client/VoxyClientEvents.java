package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Client event handlers for Voxy on NeoForge.
 *
 * Handles fog rendering to push fog to infinity so Voxy LODs render
 * without a fog wall at vanilla render distance.
 */
public class VoxyClientEvents {

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (!VoxyConfig.CONFIG.enabled || !VoxyConfig.CONFIG.enableRendering) {
            return;
        }

        // Remove all vanilla fog modes while Voxy is active.
        // This is intentionally broad: terrain fog, sky fog, and underwater-style
        // distance fog all get suppressed so the LOD horizon does not show a wall.
        event.setNearPlaneDistance(999999.0f);
        event.setFarPlaneDistance(9999999.0f);
        event.setCanceled(true);
    }
}
