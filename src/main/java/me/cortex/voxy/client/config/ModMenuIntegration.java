package me.cortex.voxy.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.OptionPage;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;

public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            if (VoxyCommon.isAvailable()) {
                var screen = (VideoSettingsScreen)VideoSettingsScreen.createScreen(parent);
                var page = (OptionPage) ConfigManager.CONFIG.getModOptions().stream().filter(a->a.configId().equals("voxy")).findFirst().get().pages().get(0);
                ((IConfigPageSetter)screen).voxy$setPageJump(page);
                return screen;
            } else {
                return null;
            }
        };
    }
}