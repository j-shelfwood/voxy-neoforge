package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.config.VoxyNeoForgeConfig;
import me.cortex.voxy.client.debug.RenderPathDebug;
import me.cortex.voxy.client.hud.VoxyLoadingHud;
import me.cortex.voxy.client.compat.IrisCompatManager;
import net.minecraft.client.renderer.FogRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/**
 * Client event handlers for Voxy on NeoForge.
 */
@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public class VoxyClientEvents {

    /**
     * Observe the final fog state after vanilla setup completes.
     *
     * The actual non-shader override now lives in MixinFogRenderer so that
     * vanilla terrain, sky, and Voxy all consume one shared fog policy.
     */
    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        boolean shaderPackEnabled = IrisCompatManager.isShaderPackEnabled();
        boolean voxyRenderingEnabled = VoxyConfig.CONFIG.isEnabled() && VoxyNeoForgeConfig.isRenderingEnabled();

        if (shaderPackEnabled) {
            RenderPathDebug.logFog("neoforge_event", "shader_pack_active", true,
                    voxyRenderingEnabled, String.valueOf(event.getMode()),
                    event.getNearPlaneDistance(), event.getFarPlaneDistance());
            return;
        }

        RenderPathDebug.logFog("neoforge_event", "observe_only", false,
                voxyRenderingEnabled, String.valueOf(event.getMode()),
                event.getNearPlaneDistance(), event.getFarPlaneDistance());
    }

    /**
     * Save config when the game is shutting down.
     * This ensures settings changed during the session are always persisted,
     * not just when the user explicitly clicks Apply in the options screen.
     */
    @SubscribeEvent
    public static void onGameShuttingDown(GameShuttingDownEvent event) {
        VoxyNeoForgeConfig.save();
    }

    @SubscribeEvent
    public static void onRenderGuiPost(RenderGuiEvent.Post event) {
        if (!VoxyConfig.CONFIG.isEnabled() || !VoxyNeoForgeConfig.isRenderingEnabled()) {
            return;
        }
        if (!VoxyNeoForgeConfig.isLoadingIndicatorEnabled()) {
            return;
        }
        VoxyLoadingHud.INSTANCE.render(event.getGuiGraphics(), event.getPartialTick());
    }

}
