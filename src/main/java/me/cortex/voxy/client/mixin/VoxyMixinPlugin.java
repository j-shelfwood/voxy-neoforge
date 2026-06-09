package me.cortex.voxy.client.mixin;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates the optional Iris integration mixins ({@code me.cortex.voxy.client.mixin.iris.*}) so they only
 * apply when the Iris mod is actually installed. Without this, Iris-internal mixin targets would be absent
 * and the game could fail to launch for users who do not run Iris.
 *
 * <p>{@link LoadingModList} is the FML mod list available during the mixin bootstrap phase (the runtime
 * {@code ModList} is not yet populated when mixins are applied), so detection is done via
 * {@code getModFileById("iris")}.
 */
public class VoxyMixinPlugin implements IMixinConfigPlugin {
    private static final String IRIS_MIXIN_PACKAGE = "me.cortex.voxy.client.mixin.iris.";
    private static final boolean IRIS_LOADED = LoadingModList.get().getModFileById("iris") != null;

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith(IRIS_MIXIN_PACKAGE)) {
            return IRIS_LOADED;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
