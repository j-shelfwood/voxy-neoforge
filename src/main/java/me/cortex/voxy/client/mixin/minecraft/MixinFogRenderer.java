package me.cortex.voxy.client.mixin.minecraft;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.compat.IrisCompatManager;
import me.cortex.voxy.client.config.RenderDistancePolicy;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.debug.RenderPathDebug;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FogRenderer.class)
public class MixinFogRenderer {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private static void voxy$extendFog(Camera camera, FogRenderer.FogMode fogMode,
                                       float farPlaneDistance, boolean shouldCreateFog,
                                       float partialTick, CallbackInfo ci) {
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) {
            RenderPathDebug.logFog("mixin_setupFog", "voxy_rendering_disabled", false,
                    false, String.valueOf(fogMode),
                    RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd());
            return;
        }

        var mc = Minecraft.getInstance();
        if (mc == null || mc.levelRenderer == null) {
            RenderPathDebug.logFog("mixin_setupFog", "minecraft_not_ready", false,
                    true, String.valueOf(fogMode),
                    RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd());
            return;
        }

        var vrs = ((IGetVoxyRenderSystem) mc.levelRenderer).getVoxyRenderSystem();
        if (vrs == null) {
            RenderPathDebug.logFog("mixin_setupFog", "no_voxy_renderer", false,
                    true, String.valueOf(fogMode),
                    RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd());
            return;
        }

        // Keep shader-pack fog/cloud pipeline authoritative.
        if (IrisCompatManager.isShaderPackEnabled()) {
            RenderPathDebug.logFog("mixin_setupFog", "shader_pack_active", true,
                    true, String.valueOf(fogMode),
                    RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd());
            return;
        }

        if (camera.getFluidInCamera() != FogType.NONE) {
            RenderPathDebug.logFog("mixin_setupFog", "submerged_preserved", false,
                    true, String.valueOf(fogMode),
                    RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd());
            return;
        }

        if (camera.getEntity() instanceof LivingEntity livingEntity
                && (livingEntity.hasEffect(MobEffects.BLINDNESS) || livingEntity.hasEffect(MobEffects.DARKNESS))) {
            RenderPathDebug.logFog("mixin_setupFog", "mob_effect_preserved", false,
                    true, String.valueOf(fogMode),
                    RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd());
            return;
        }

        if (shouldCreateFog) {
            RenderPathDebug.logFog("mixin_setupFog", "world_fog_preserved", false,
                    true, String.valueOf(fogMode),
                    RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd());
            return;
        }

        if (!VoxyConfig.CONFIG.useEnvironmentalFog()) {
            RenderSystem.setShaderFogStart(999999999f);
            RenderSystem.setShaderFogEnd(999999999f);
            RenderPathDebug.logFog("mixin_setupFog", "env_fog_disabled", false,
                    true, String.valueOf(fogMode),
                    RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd());
            return;
        }

        float fogEnd = RenderDistancePolicy.getExtendedFogEndBlocks(farPlaneDistance);
        if (fogMode == FogRenderer.FogMode.FOG_TERRAIN) {
            RenderSystem.setShaderFogStart(RenderDistancePolicy.getExtendedTerrainFogStartBlocks(fogEnd));
            RenderSystem.setShaderFogEnd(fogEnd);
            RenderPathDebug.logFog("mixin_setupFog", "terrain_extend", false,
                    true, String.valueOf(fogMode),
                    RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd());
            return;
        }

        if (fogMode == FogRenderer.FogMode.FOG_SKY) {
            RenderSystem.setShaderFogStart(0.0f);
            RenderSystem.setShaderFogEnd(fogEnd);
            RenderPathDebug.logFog("mixin_setupFog", "sky_extend", false,
                    true, String.valueOf(fogMode),
                    RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd());
        }
    }
}
