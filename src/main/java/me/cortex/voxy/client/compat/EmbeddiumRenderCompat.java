package me.cortex.voxy.client.compat;

import me.cortex.voxy.client.config.RenderDistancePolicy;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.debug.RenderPathDebug;
import net.minecraft.client.Minecraft;
import org.embeddedt.embeddium.api.render.clouds.ModifyCloudRenderingEvent;

public final class EmbeddiumRenderCompat {
    private static boolean registered;

    private EmbeddiumRenderCompat() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        ModifyCloudRenderingEvent.BUS.addListener(EmbeddiumRenderCompat::onModifyCloudRendering);
    }

    private static void onModifyCloudRendering(ModifyCloudRenderingEvent event) {
        boolean shaderPackEnabled = IrisCompatManager.isShaderPackEnabled();
        String cloudsType = String.valueOf(Minecraft.getInstance().options.getCloudsType());

        if (!VoxyConfig.CONFIG.isRenderingEnabled()) {
            RenderPathDebug.logEmbeddiumClouds(shaderPackEnabled, "voxy_rendering_disabled",
                    event.getCloudRenderDistance(), event.getCloudRenderDistance(),
                    event.getCloudRenderDistance(), cloudsType);
            return;
        }
        if (shaderPackEnabled) {
            RenderPathDebug.logEmbeddiumClouds(true, "shader_pack_active",
                    event.getCloudRenderDistance(), event.getCloudRenderDistance(),
                    event.getCloudRenderDistance(), cloudsType);
            return;
        }

        int originalDistance = event.getCloudRenderDistance();
        int targetDistance = RenderDistancePolicy.getEmbeddiumCloudRenderDistanceChunks();
        int appliedDistance = originalDistance;
        if (targetDistance > originalDistance) {
            event.setCloudRenderDistance(targetDistance);
            appliedDistance = targetDistance;
        }
        RenderPathDebug.logEmbeddiumClouds(false, "voxy_extend",
                originalDistance, targetDistance, appliedDistance, cloudsType);
    }
}
