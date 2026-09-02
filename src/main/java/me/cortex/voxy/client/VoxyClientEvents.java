package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ViewportEvent.RenderFog;

public class VoxyClientEvents {
   @SubscribeEvent
   public static void onRenderFog(RenderFog event) {
      if (VoxyConfig.CONFIG.enabled && VoxyConfig.CONFIG.enableRendering) {
         event.setNearPlaneDistance(999999.0F);
         event.setFarPlaneDistance(9999999.0F);
         event.setCanceled(true);
      }
   }
}
