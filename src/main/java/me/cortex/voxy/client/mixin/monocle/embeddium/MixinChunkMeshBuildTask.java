package me.cortex.voxy.client.mixin.monocle.embeddium;

import com.llamalad7.mixinextras.sugar.Local;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.vertices.BlockSensitiveBufferBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildBuffers;
import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildContext;
import org.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildOutput;
import org.embeddedt.embeddium.impl.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import org.embeddedt.embeddium.impl.util.task.CancellationToken;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public class MixinChunkMeshBuildTask {
    @Inject(method = "execute(Lorg/embeddedt/embeddium/impl/render/chunk/compile/ChunkBuildContext;Lorg/embeddedt/embeddium/impl/util/task/CancellationToken;)Lorg/embeddedt/embeddium/impl/render/chunk/compile/ChunkBuildOutput;",
        at = @At(value = "INVOKE",
            target = "Lorg/embeddedt/embeddium/impl/render/chunk/compile/pipeline/BlockRenderer;renderModel(Lorg/embeddedt/embeddium/api/render/chunk/BlockRenderContext;Lorg/embeddedt/embeddium/impl/render/chunk/compile/ChunkBuildBuffers;)V"))
    private void voxy$beginBlockContext(ChunkBuildContext buildContext, CancellationToken cancellationToken,
                                        CallbackInfoReturnable<ChunkBuildOutput> cir,
                                        @Local ChunkBuildBuffers buffers,
                                        @Local BlockState blockState,
                                        @Local(ordinal = 0) BlockPos.MutableBlockPos blockPos) {
        if (WorldRenderingSettings.INSTANCE.getBlockStateIds() == null) {
            return;
        }
        ((BlockSensitiveBufferBuilder) buffers).beginBlock(
            WorldRenderingSettings.INSTANCE.getBlockStateIds().getInt(blockState),
            (byte) 0,
            (byte) blockState.getLightEmission(),
            blockPos.getX(), blockPos.getY(), blockPos.getZ()
        );
    }

    @Inject(method = "execute(Lorg/embeddedt/embeddium/impl/render/chunk/compile/ChunkBuildContext;Lorg/embeddedt/embeddium/impl/util/task/CancellationToken;)Lorg/embeddedt/embeddium/impl/render/chunk/compile/ChunkBuildOutput;",
        at = @At(value = "INVOKE",
            target = "Lorg/embeddedt/embeddium/impl/render/chunk/compile/pipeline/FluidRenderer;render(Lorg/embeddedt/embeddium/impl/world/WorldSlice;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lorg/embeddedt/embeddium/impl/render/chunk/compile/ChunkBuildBuffers;)V"))
    private void voxy$beginFluidContext(ChunkBuildContext buildContext, CancellationToken cancellationToken,
                                        CallbackInfoReturnable<ChunkBuildOutput> cir,
                                        @Local ChunkBuildBuffers buffers,
                                        @Local BlockState blockState,
                                        @Local FluidState fluidState,
                                        @Local(ordinal = 0) BlockPos.MutableBlockPos blockPos) {
        if (WorldRenderingSettings.INSTANCE.getBlockStateIds() == null) {
            return;
        }
        ((BlockSensitiveBufferBuilder) buffers).beginBlock(
            WorldRenderingSettings.INSTANCE.getBlockStateIds().getInt(fluidState.createLegacyBlock()),
            (byte) 1,
            (byte) blockState.getLightEmission(),
            blockPos.getX(), blockPos.getY(), blockPos.getZ()
        );
    }

    @Inject(method = "execute(Lorg/embeddedt/embeddium/impl/render/chunk/compile/ChunkBuildContext;Lorg/embeddedt/embeddium/impl/util/task/CancellationToken;)Lorg/embeddedt/embeddium/impl/render/chunk/compile/ChunkBuildOutput;",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;isSolidRender(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z"))
    private void voxy$endBlockContext(ChunkBuildContext buildContext, CancellationToken cancellationToken,
                                      CallbackInfoReturnable<ChunkBuildOutput> cir,
                                      @Local ChunkBuildBuffers buffers,
                                      @Local BlockState blockState) {
        ((BlockSensitiveBufferBuilder) buffers).endBlock();
    }
}
