package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.renderer.FogRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Client event handlers for Voxy on NeoForge.
 *
 * Handles fog rendering to push fog to infinity so Voxy LODs render
 * without a fog wall at vanilla render distance.
 */
@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public class VoxyClientEvents {

    /**
     * Push terrain fog to infinity when Voxy is enabled.
     *
     * This event fires AFTER setupFog() completes but BEFORE terrain renders.
     * By setting fog distances to very large values and cancelling the event,
     * we prevent the fog wall from appearing at vanilla render distance.
     *
     * Both vanilla terrain and Voxy LODs will render without fog-based distance fading.
     * This is the same approach used by Distant Horizons.
     */
    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        // Only modify terrain fog when Voxy is enabled and rendering
        if (event.getMode() == FogRenderer.FogMode.FOG_TERRAIN
                && VoxyConfig.CONFIG.enabled
                && VoxyConfig.CONFIG.enableRendering) {

            // Push fog to very large values (not MAX_VALUE to avoid shader math issues)
            // This removes the fog wall at vanilla render distance
            event.setNearPlaneDistance(999999.0f);
            event.setFarPlaneDistance(9999999.0f);

            // MUST cancel for changes to take effect (per NeoForge docs)
            event.setCanceled(true);
        }
    }
}
