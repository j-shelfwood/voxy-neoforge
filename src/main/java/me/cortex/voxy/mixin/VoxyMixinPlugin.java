package me.cortex.voxy.mixin;

import me.cortex.voxy.common.util.ModCompat;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class VoxyMixinPlugin implements IMixinConfigPlugin {
    private static final String IRIS_CLASS = "net.irisshaders.iris.Iris";
    private static final String IRIS_API_CLASS = "net.irisshaders.iris.api.v0.IrisApi";
    private static final String EMBEDDIUM_CLASS = "org.embeddedt.embeddium.impl.Embeddium";
    private static final String EMBEDDIUM_PRELAUNCH_CLASS = "org.embeddedt.embeddium.impl.EmbeddiumPreLaunch";
    private static final String MONOCLE_CLASS = "dev.ferriarnus.monocle.Monocle";
    private static final String MONOCLE_TRANSFORMER_CLASS = "dev.ferriarnus.monocle.ShaderTransformer";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains(".mixin.embeddium.")) {
            // NeoForge mixin plugin runs very early. ModList can be unavailable at that time, so also
            // fall back to class presence checks.
            return isLoadedOrPresent("embeddium", EMBEDDIUM_CLASS, EMBEDDIUM_PRELAUNCH_CLASS);
        }
        if (mixinClassName.contains(".mixin.iris.")) {
            // Iris shader-pack integration must be applied before Iris creates its pipelines.
            // Use class presence checks because ModList/LoadingModList may not be initialized yet.
            return isLoadedOrPresent("iris", IRIS_CLASS, IRIS_API_CLASS);
        }
        if (mixinClassName.contains(".mixin.monocle.")) {
            // Monocle fixup mixins require Iris (they target Iris + Monocle classes).
            // Apply when either Monocle or Iris is present — MixinIrisTransformPatcher targets
            // Iris's CompositeRenderer regardless of Monocle, and MixinMonocleShaderTransformer
            // uses require=0 to fail-soft if Monocle is absent.
            return isLoadedOrPresent("iris", IRIS_CLASS, IRIS_API_CLASS);
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    private static boolean isLoaded(String modId) {
        return ModCompat.isModLoaded(modId);
    }

    private static boolean isLoadedOrPresent(String modId, String... classNames) {
        if (isLoaded(modId)) {
            return true;
        }
        for (String className : classNames) {
            if (ModCompat.isClassPresent(className)) {
                return true;
            }
        }
        return false;
    }
}
