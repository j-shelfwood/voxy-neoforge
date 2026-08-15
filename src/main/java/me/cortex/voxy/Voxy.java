package me.cortex.voxy;

import me.cortex.voxy.client.config.VoxyNeoForgeConfig;
import me.cortex.voxy.common.Logger;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;

/**
 * Main mod class for Voxy on NeoForge.
 *
 * Handles config registration and config screen setup.
 * Actual initialization happens via mixins (MixinRenderSystem).
 */
@Mod("voxy")
public class Voxy {

    public Voxy(IEventBus modEventBus, ModContainer container) {
        // Only register client config on client side
        if (FMLLoader.getDist() == Dist.CLIENT) {
            // Register NeoForge config
            VoxyNeoForgeConfig.register(container);

            // Register the built-in NeoForge config screen
            container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

            // Register Sodium Options API integration if available
            // This adds Voxy settings to Sodium's Video Settings menu
            // Uses reflection to avoid hard dependency - graceful fallback if not present
            tryRegisterSodiumOptionsIntegration();
        }
    }

    /**
     * Attempts to register the Sodium Options API integration.
     * Uses reflection to avoid class loading errors when the API is not present.
     * Falls back gracefully to NeoForge config screen if unavailable.
     */
    private static void tryRegisterSodiumOptionsIntegration() {
        if (!ModList.get().isLoaded("sodiumoptionsapi")) {
            Logger.info("SodiumOptionsAPI not found - Voxy settings available via Mods menu");
            return;
        }

        try {
            // SodiumOptionsAPI (and our page behind it) target Sodium 0.6's options GUI.
            // Probe for it first: on Sodium 0.8+ those classes are gone, and registering
            // anyway would defer the NoClassDefFoundError to when the Video Settings
            // screen opens - outside any of our try/catch blocks.
            Class.forName("net.caffeinemc.mods.sodium.client.gui.options.OptionPage");
            // Load and invoke the integration class only when we know the API is present
            // This prevents NoClassDefFoundError when SodiumOptionsAPI is not installed
            Class<?> sodiumOptionsClass = Class.forName("me.cortex.voxy.client.config.VoxySodiumOptions");
            sodiumOptionsClass.getMethod("register").invoke(null);
            Logger.info("Registered Voxy settings in Sodium Video Settings menu");
        } catch (Throwable e) {
            Logger.warn("Failed to register Sodium Options integration: " + e.getMessage());
            Logger.info("Voxy settings available via Mods menu instead");
        }
    }
}
