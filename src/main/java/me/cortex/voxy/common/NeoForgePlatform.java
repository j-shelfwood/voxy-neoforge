package me.cortex.voxy.common;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.LoadingModList;

//FabricLoader standin. FFAPI doesnt ship a fabric loader on this pack, so all FabricLoader
//usages go through here insted.
public final class NeoForgePlatform {
    private NeoForgePlatform() {}

    //safe durring early mixin bootstrap when ModList isnt built yet
    public static boolean isModLoaded(String modId) {
        try {
            var ml = ModList.get();
            if (ml != null) {
                return ml.isLoaded(modId);
            }
            var lml = LoadingModList.get();
            return lml != null && lml.getModFileById(modId) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
