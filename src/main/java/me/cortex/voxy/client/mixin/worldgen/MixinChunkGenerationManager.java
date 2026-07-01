package me.cortex.voxy.client.mixin.worldgen;

import com.ethan.voxyworldgenv2.core.ChunkGenerationManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

@Mixin(value = ChunkGenerationManager.class, remap = false)
public class MixinChunkGenerationManager {

    //the whenCompleteAsync call lives inside a compiler-generated lambda, not
    //directly in workerLoop, so we target the synthetic method
    @Redirect(method = "lambda$workerLoop$6",
              at = @At(value = "INVOKE",
                       target = "Ljava/util/concurrent/CompletableFuture;whenCompleteAsync(Ljava/util/function/BiConsumer;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"),
              require = 1)
    private <T> CompletableFuture<T> voxy$fixC2meDeadlock(CompletableFuture<T> future,
                                                           BiConsumer<? super T, ? super Throwable> action,
                                                           Executor executor) {
        return future.whenComplete(action);
    }

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
