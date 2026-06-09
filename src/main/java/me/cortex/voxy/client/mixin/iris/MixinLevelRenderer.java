package me.cortex.voxy.client.mixin.iris;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
// Sodium 0.6.13: net.caffeinemc.mods.sodium.client.util.FogStorage no longer exists; fog capture removed
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.opengl.GL11C.glViewport;

@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {
    @Shadow @Final private Minecraft minecraft;

    // MC 1.21.1 mojmap verified: LevelRenderer.renderLevel(DeltaTracker, boolean, Camera, GameRenderer, LightTexture, Matrix4f frustumMatrix, Matrix4f projectionMatrix).
    // Upstream targets newer MC (GraphicsResourceAllocator/GpuBufferSlice/basicProjectionMatrix signature); rewritten to the 1.21.1 params so the HEAD inject applies.
    @Inject(method = "renderLevel", at = @At("HEAD"), order = 100)
    private void voxy$injectIrisCompat(
            DeltaTracker tickCounter,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f frustumMatrix,
            Matrix4f projectionMatrix,
            CallbackInfo ci) {
        if (IrisUtil.irisShaderPackEnabled()) {
            var renderer = ((IGetVoxyRenderSystem) this).getVoxyRenderSystem();
            if (renderer != null) {
                //Fixthe fucking viewport dims, fuck iris
                glViewport(0,0,Minecraft.getInstance().getMainRenderTarget().width, Minecraft.getInstance().getMainRenderTarget().height);

                var pos = camera.getPosition(); // MC 1.21.1: Camera.getPosition() (was position() in newer MC)
                // frustumMatrix is the modelView matrix in 1.21.1 (was positionMatrix in the newer-MC signature)
                // Sodium 0.6.13: FogParameters/FogStorage removed; setupViewport no longer captures fog
                IrisUtil.CAPTURED_VIEWPORT_PARAMETERS = new IrisUtil.CapturedViewportParameters(new ChunkRenderMatrices(projectionMatrix, frustumMatrix), pos.x, pos.y, pos.z);
            }
        }
    }
}
