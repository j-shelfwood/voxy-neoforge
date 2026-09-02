package me.cortex.voxy.client.mixin.iris;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.common.Logger;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.uniforms.CameraUniforms;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ShadowRenderer.class, remap = false)
public class MixinShadowRenderer {
    private static int voxy$shadowHookLogCount;

    @Inject(
            method = "renderShadows",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;viewport(IIII)V",
                    ordinal = 0,
                    shift = At.Shift.BEFORE
            )
    )
    private void voxy$renderShadowTerrain(LevelRendererAccessor levelRenderer, Camera camera, CallbackInfo ci) {
        var renderer = ((IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer).getVoxyRenderSystem();
        if (voxy$shadowHookLogCount++ < 3) {
            Logger.info("Voxy Iris shadow hook reached: renderer=" + (renderer != null)
                    + ", projection=" + (ShadowRenderer.PROJECTION != null)
                    + ", modelView=" + (ShadowRenderer.MODELVIEW != null)
                    + ", resolution=" + ShadowRenderer.RESOLUTION);
        }
        if (renderer == null || ShadowRenderer.PROJECTION == null || ShadowRenderer.MODELVIEW == null) {
            return;
        }

        var cameraPosition = CameraUniforms.getUnshiftedCameraPosition();
        float shadowDistance = Math.max(16.0f, ShadowRenderer.renderDistance * 16.0f);
        renderer.renderShadow(
                ShadowRenderer.PROJECTION,
                ShadowRenderer.MODELVIEW,
                cameraPosition.x,
                cameraPosition.y,
                cameraPosition.z,
                ShadowRenderer.RESOLUTION,
                shadowDistance
        );
    }
}
