package me.cortex.voxy.client.mixin.sodium;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(
   value = {RenderSectionManager.class},
   remap = false
)
public class MixinRenderSectionManager {
   @Unique
   private static final boolean BOBBY_INSTALLED = ModList.get().isLoaded("bobby");
   @Shadow
   @Final
   private ClientLevel level;
   @Shadow
   @Final
   private ChunkBuilder builder;
   @Unique
   private long cachedChunkPos = -1L;
   @Unique
   private int cachedChunkStatus;
   @Unique
   private int bottomSectionY;

   @Inject(
      method = {"<init>(Lnet/minecraft/client/multiplayer/ClientLevel;ILnet/caffeinemc/mods/sodium/client/gl/device/CommandList;)V"},
      at = {@At("TAIL")},
      require = 0
   )
   private void voxy$resetChunkTracker(ClientLevel level, int renderDistance, CommandList commandList, CallbackInfo ci) {
      this.voxy$resetChunkTracker();
   }

   @Inject(
      method = {"<init>(Lnet/minecraft/client/multiplayer/ClientLevel;ILnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/SortBehavior;Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;)V"},
      at = {@At("TAIL")},
      require = 0
   )
   private void voxy$resetChunkTracker(ClientLevel level, int renderDistance, SortBehavior sortBehavior, CommandList commandList, CallbackInfo ci) {
      this.voxy$resetChunkTracker();
   }

   @Unique
   private void voxy$resetChunkTracker() {
      if (this.level.levelRenderer != null) {
         VoxyRenderSystem system = ((IGetVoxyRenderSystem)this.level.levelRenderer).getVoxyRenderSystem();
         if (system != null) {
            system.chunkBoundRenderer.reset();
         }
      }

      this.bottomSectionY = this.level.getMinBuildHeight() >> 4;
   }

   @Inject(
      method = {"onChunkRemoved"},
      at = {@At("HEAD")}
   )
   private void injectIngest(int x, int z, CallbackInfo ci) {
      if (VoxyConfig.CONFIG.ingestEnabled && !BOBBY_INSTALLED) {
         ICheekyClientChunkCache cccm = (ICheekyClientChunkCache)this.level.getChunkSource();
         if (cccm != null) {
            LevelChunk chunk = cccm.voxy$cheekyGetChunk(x, z);
            if (chunk != null) {
               VoxelIngestService.tryAutoIngestChunk(chunk);
            }
         }
      }
   }

   @Inject(
      method = {"onChunkAdded"},
      at = {@At("HEAD")}
   )
   private void voxy$ingestOnAdd(int x, int z, CallbackInfo ci) {
      if (this.level.levelRenderer != null && VoxyConfig.CONFIG.ingestEnabled) {
         ClientChunkCache cccm = this.level.getChunkSource();
         if (cccm != null) {
            LevelChunk chunk = cccm.getChunk(x, z, ChunkStatus.FULL, false);
            if (chunk != null) {
               VoxelIngestService.tryAutoIngestChunk(chunk);
            }
         }
      }
   }

   @Redirect(
      method = {"updateSectionInfo"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;setInfo(Lnet/caffeinemc/mods/sodium/client/render/chunk/data/BuiltSectionInfo;)Z"
      )
   )
   private boolean voxy$updateOnUpload(RenderSection instance, BuiltSectionInfo info) {
      boolean wasBuilt = instance.getFlags() != 0;
      int flags = instance.getFlags();
      if (!instance.setInfo(info)) {
         return false;
      } else if (wasBuilt == (instance.getFlags() != 0)) {
         return true;
      } else {
         flags |= instance.getFlags();
         if (flags == 0) {
            return true;
         } else {
            VoxyRenderSystem system = ((IGetVoxyRenderSystem)this.level.levelRenderer).getVoxyRenderSystem();
            if (system == null) {
               return true;
            } else {
               int x = instance.getChunkX();
               int y = instance.getChunkY();
               int z = instance.getChunkZ();
               if (wasBuilt && VoxyConfig.CONFIG.ingestEnabled) {
                  Long2IntOpenHashMap tracker = ((AccessorChunkTracker)ChunkTrackerHolder.get(this.level)).getChunkStatus();
                  long key = ChunkPos.asLong(x, z);
                  if (key != this.cachedChunkPos) {
                     this.cachedChunkPos = key;
                     this.cachedChunkStatus = tracker.getOrDefault(key, 0);
                  }

                  if (this.cachedChunkStatus == 3) {
                     LevelChunkSection section = this.level.getChunk(x, z).getSection(y - this.bottomSectionY);
                     LevelLightEngine lp = this.level.getLightEngine();
                     SectionPos csp = SectionPos.of(x, y, z);
                     DataLayer blp = lp.getLayerListener(LightLayer.BLOCK).getDataLayerData(csp);
                     DataLayer slp = lp.getLayerListener(LightLayer.SKY).getDataLayerData(csp);
                     VoxelIngestService.rawIngest(system.getEngine(), section, x, y, z, blp == null ? null : blp.copy(), slp == null ? null : slp.copy());
                  }
               }

               long pos = SectionPos.asLong(x, y, z);
               if (wasBuilt) {
                  system.chunkBoundRenderer.removeSection(pos);
               } else {
                  system.chunkBoundRenderer.addSection(pos);
               }

               return true;
            }
         }
      }
   }
}
