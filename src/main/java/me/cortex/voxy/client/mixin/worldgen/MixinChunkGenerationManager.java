package me.cortex.voxy.client.mixin.worldgen;

import com.ethan.voxyworldgenv2.core.ChunkGenerationManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ChunkGenerationManager.class, remap = false)
public class MixinChunkGenerationManager {

    //c2me deadlock fix: we cant @Redirect inside synthetic lambda methods (mixin
    //limitation). the actual fix is applied via a bytecode patch at build time that
    //changes the executor passed to whenCompleteAsync from the minecraft server (which
    //c2me can block via managedBlock) to ForkJoinPool.commonPool(). see build.gradle.

    //original shutdown calls releaseAllTickets after stopWorker, but stopWorker blocks
    //on in-flight chunk requests that need those tickets released first. flip the order.
    @Inject(method = "shutdown", at = @At("HEAD"))
    private void voxy$fixShutdownOrder(CallbackInfo ci) {
        try {
            var m = ChunkGenerationManager.class.getDeclaredMethod("releaseAllTickets");
            m.setAccessible(true);
            m.invoke(this);
        } catch (Exception ignored) {
        }
    }
}
