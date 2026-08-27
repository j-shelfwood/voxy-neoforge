package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.RenderDistancePolicy;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.compat.IrisCompatManager;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.debug.RenderPathDebug;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Expand vanilla cloud mesh radius when Voxy is active.
 *
 * Vanilla buildClouds() iterates a small fixed tile range (-3..4). At high LOD
 * distances this creates a visible cloud boundary and repeating near-field pattern.
 * We scale the range to Voxy's section render distance (in blocks / 8 per tile).
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRendererClouds {

    @Unique
    private static int voxy$cloudTileRadius() {
        boolean shaderPackEnabled = IrisCompatManager.isShaderPackEnabled();
        int configuredChunks = RenderDistancePolicy.getConfiguredRenderDistanceChunks();

        if (shaderPackEnabled) {
            RenderPathDebug.logVanillaClouds("shader_pack_active", true, configuredChunks, -1, 3);
            return 3;
        }
        var levelRenderer = Minecraft.getInstance().levelRenderer;
        if (!(levelRenderer instanceof IGetVoxyRenderSystem ivrs)) {
            RenderPathDebug.logVanillaClouds("no_voxy_renderer", false, configuredChunks, -1, 3);
            return 3;
        }
        var vrs = ivrs.getVoxyRenderSystem();
        if (vrs == null || !VoxyConfig.CONFIG.isRenderingEnabled()) {
            RenderPathDebug.logVanillaClouds("voxy_rendering_disabled", false, configuredChunks, -1, 3);
            return 3;
        }
        int renderDistanceBlocks = RenderDistancePolicy.getConfiguredRenderDistanceBlocks();
        int tileRadius = RenderDistancePolicy.getVanillaCloudTileRadius();
        RenderPathDebug.logVanillaClouds("voxy_extend", false, configuredChunks, renderDistanceBlocks, tileRadius);
        return tileRadius;
    }

    // Lower bound constants for k/l loops.
    @ModifyConstant(method = "buildClouds", constant = @Constant(intValue = -3, ordinal = 0), require = 0)
    private int voxy$cloudLoopLowerK(int original) {
        return -voxy$cloudTileRadius();
    }

    @ModifyConstant(method = "buildClouds", constant = @Constant(intValue = -3, ordinal = 1), require = 0)
    private int voxy$cloudLoopLowerL(int original) {
        return -voxy$cloudTileRadius();
    }

    // Upper bound constants for k/l loops.
    @ModifyConstant(method = "buildClouds", constant = @Constant(intValue = 4, ordinal = 0), require = 0)
    private int voxy$cloudLoopUpperK(int original) {
        return voxy$cloudTileRadius() + 1;
    }

    @ModifyConstant(method = "buildClouds", constant = @Constant(intValue = 4, ordinal = 1), require = 0)
    private int voxy$cloudLoopUpperL(int original) {
        return voxy$cloudTileRadius() + 1;
    }
}
