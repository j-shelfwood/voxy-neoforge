package me.cortex.voxy;

import me.cortex.voxy.client.VoxyClientEvents;
import me.cortex.voxy.client.config.VoxyNeoForgeConfig;
import me.cortex.voxy.common.Logger;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod("voxy")
public class Voxy {
   public Voxy(IEventBus modEventBus, ModContainer container) {
      if (FMLLoader.getDist() == Dist.CLIENT) {
         NeoForge.EVENT_BUS.register(VoxyClientEvents.class);
         VoxyNeoForgeConfig.register(container);
         container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
         tryRegisterSodiumOptionsIntegration();
      }
   }

   private static void tryRegisterSodiumOptionsIntegration() {
      if (!ModList.get().isLoaded("sodiumoptionsapi")) {
         Logger.info("SodiumOptionsAPI not found - Voxy settings available via Mods menu");
      } else {
         try {
            Class<?> sodiumOptionsClass = Class.forName("me.cortex.voxy.client.config.VoxySodiumOptions");
            sodiumOptionsClass.getMethod("register").invoke(null);
            Logger.info("Registered Voxy settings in Sodium Video Settings menu");
         } catch (Throwable var1) {
            Logger.warn("Failed to register Sodium Options integration: " + var1.getMessage());
            Logger.info("Voxy settings available via Mods menu instead");
         }
      }
   }
}
