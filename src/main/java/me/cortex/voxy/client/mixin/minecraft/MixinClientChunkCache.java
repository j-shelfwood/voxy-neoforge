package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.util.ModCompat;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
public class MixinClientChunkCache implements ICheekyClientChunkCache {
    @Unique
    private static final boolean BOBBY_INSTALLED = ModCompat.isModLoaded("bobby");

    @Shadow private ClientLevel level;
    @Shadow volatile ClientChunkCache.Storage storage;

    @Override
    public LevelChunk voxy$cheekyGetChunk(int x, int z) {
        return this.storage.getChunk(this.storage.getIndex(x, z));
    }

    // Bobby ingest on chunk unload
    @Inject(method = "drop", at = @At("HEAD"))
    public void voxy$onChunkUnload(ChunkPos pos, CallbackInfo ci) {
        if (VoxyConfig.CONFIG.isIngestEnabled() && BOBBY_INSTALLED) {
            var chunk = this.voxy$cheekyGetChunk(pos.x, pos.z);
            if (chunk != null) {
                VoxelIngestService.tryAutoIngestChunk(chunk);
            }
        }
    }
}
