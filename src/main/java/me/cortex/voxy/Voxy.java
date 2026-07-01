package me.cortex.voxy;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

//NeoForge entrypoint. Everything is event/mixin driven so the constructor is empty.
// VoxyClient registers the /voxy command via @EventBusSubscriber.
// MixinRenderSystem calls initVoxyClient once GL is up.
// VoxyConfigMenu adds voxy's page to Sodium settings.
// Config is persisted via VoxyConfig (JSON).
@Mod("voxy")
public class Voxy {
    public Voxy(IEventBus modEventBus, ModContainer container) {
    }
}
