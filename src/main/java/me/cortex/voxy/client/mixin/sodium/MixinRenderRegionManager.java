package me.cortex.voxy.client.mixin.sodium;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderRegionManager.class, remap = false)
public class MixinRenderRegionManager {
    // NOTE: this mixin is not listed in client.voxy.mixins.json. Its redirect target
    // (Math.toIntExact inside uploadResults) exists in neither Sodium 0.6.13 nor 0.8.12,
    // so require = 0 keeps it from hard-failing if it is ever wired back in.
    @Redirect(method = "uploadResults(Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;Ljava/util/Collection;)V", at = @At(value = "INVOKE", target = "Ljava/lang/Math;toIntExact(J)I"), remap = false, require = 0)
    private int voxy$cancelFade(long time) {
        var vrs = ((IGetVoxyRenderSystem)(Minecraft.getInstance().levelRenderer)).getVoxyRenderSystem();
        if (vrs!=null) {
            return -2;
        } else {
            return Math.toIntExact(time);
        }
    }
}