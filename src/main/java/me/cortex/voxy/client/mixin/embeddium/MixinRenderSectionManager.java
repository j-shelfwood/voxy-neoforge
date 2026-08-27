package me.cortex.voxy.client.mixin.embeddium;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import org.embeddedt.embeddium.impl.gl.device.CommandList;
import org.embeddedt.embeddium.impl.render.chunk.RenderSection;
import org.embeddedt.embeddium.impl.render.chunk.RenderSectionManager;
import org.embeddedt.embeddium.impl.render.chunk.compile.executor.ChunkBuilder;
import org.embeddedt.embeddium.impl.render.chunk.data.BuiltSectionInfo;
import org.embeddedt.embeddium.impl.render.chunk.map.ChunkTrackerHolder;
import net.minecraft.client.multiplayer.ClientLevel;
import me.cortex.voxy.common.util.ModCompat;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinRenderSectionManager {
    @Unique
    private static final boolean BOBBY_INSTALLED = ModCompat.isModLoaded("bobby");

    @Shadow @Final private ClientLevel world;
    @Shadow @Final private ChunkBuilder builder;

    @Unique private static final boolean VOXY_LOG_SECTION_TRANSITIONS =
            System.getProperty("voxy.logSectionTransitions", "true").equalsIgnoreCase("true");
    @Unique private static final long VOXY_SECTION_TRANSITION_LOG_INTERVAL_NANOS =
            Long.getLong("voxy.sectionTransitionLogIntervalMs", 2000L) * 1_000_000L;
    @Unique private static final boolean VOXY_LOG_INGEST_DIAG =
            System.getProperty("voxy.logIngestDiagnostics", "true").equalsIgnoreCase("true");
    @Unique private static final long VOXY_INGEST_RETRY_NANOS =
            Long.getLong("voxy.ingestRetryMs", 2000L) * 1_000_000L;
    @Unique private static final int VOXY_INGEST_DIAG_LOG_EVERY =
            Integer.getInteger("voxy.ingestDiagLogEvery", 200);
    @Unique private static long voxy$lastSectionTransitionLogNanos = System.nanoTime();
    @Unique private static int voxy$transitionBuiltToUnbuilt;
    @Unique private static int voxy$transitionUnbuiltToBuilt;
    @Unique private static int voxy$transitionNoop;
    @Unique private final Long2LongOpenHashMap voxy$recentChunkIngestNanos = new Long2LongOpenHashMap();
    @Unique private int voxy$ingestAttempted;
    @Unique private int voxy$ingestSucceeded;
    @Unique private int voxy$ingestSkippedCooldown;
    @Unique private int voxy$ingestChunkMissing;

    // Reset mask when Embeddium recreates RenderSectionManager (render distance change, reload, etc).
    // Safe because Embeddium immediately re-queues all sections for build after construction,
    // so the mask will be repopulated as sections complete.
    @Inject(method = "<init>", at = @At("TAIL"))
    private void voxy$init(ClientLevel level, int renderDistance, CommandList commandList, CallbackInfo ci) {
        if (level.levelRenderer != null) {
            var system = ((IGetVoxyRenderSystem)(level.levelRenderer)).getVoxyRenderSystem();
            if (system != null) {
                system.chunkBoundRenderer.reset();
            }
        }
        this.bottomSectionY = ((net.minecraft.world.level.Level)this.world).getMinBuildHeight() >> 4;
    }

    // Ingest on chunk remove (non-Bobby path)
    @Inject(method = "onChunkRemoved", at = @At("HEAD"))
    private void voxy$ingestOnRemove(int x, int z, CallbackInfo ci) {
        if (VoxyConfig.CONFIG.isIngestEnabled() && !BOBBY_INSTALLED) {
            var cccm = (ICheekyClientChunkCache) this.world.getChunkSource();
            if (cccm != null) {
                var chunk = cccm.voxy$cheekyGetChunk(x, z);
                if (chunk != null) {
                    VoxelIngestService.tryAutoIngestChunk(chunk);
                }
            }
        }
    }

    // Ingest on chunk add
    @Inject(method = "onChunkAdded", at = @At("TAIL"))
    private void voxy$ingestOnAdd(int x, int z, CallbackInfo ci) {
        this.voxy$tryIngestChunk(x, z, "chunk_added");
    }

    @Unique private long cachedChunkPos = -1;
    @Unique private int cachedChunkStatus;
    @Unique private int bottomSectionY;

    // Built/unbuilt transitions are still useful for ingest timing diagnostics, but the depth mask
    // is now driven from Embeddium's actual visible render lists inside MixinDefaultChunkRenderer.
    @Redirect(method = "updateSectionInfo", at = @At(value = "INVOKE", target = "Lorg/embeddedt/embeddium/impl/render/chunk/RenderSection;setInfo(Lorg/embeddedt/embeddium/impl/render/chunk/data/BuiltSectionInfo;)V"))
    private void voxy$updateOnUpload(RenderSection instance, BuiltSectionInfo info) {
        boolean wasBuilt = instance.isBuilt();
        instance.setInfo(info);
        boolean isBuilt = instance.isBuilt();

        VoxyRenderSystem system = ((IGetVoxyRenderSystem)(this.world.levelRenderer)).getVoxyRenderSystem();
        if (wasBuilt == isBuilt) {
            voxy$transitionNoop++;
            voxy$logSectionTransitionStats(system);
            return; // No state change — nothing to do
        }

        if (system == null) {
            voxy$logSectionTransitionStats(null);
            return;
        }

        int x = instance.getChunkX(), y = instance.getChunkY(), z = instance.getChunkZ();
        if (!wasBuilt) {
            voxy$transitionUnbuiltToBuilt++;
            this.voxy$tryIngestChunk(x, z, "section_built");
        } else {
            voxy$transitionBuiltToUnbuilt++;

            // rawIngest on section being cleared (non-Bobby path)
            if (VoxyConfig.CONFIG.isIngestEnabled()) {
                var tracker = ((AccessorChunkTracker) ChunkTrackerHolder.get(this.world)).getChunkStatus();
                long key = ChunkPos.asLong(x, z);
                if (key != this.cachedChunkPos) {
                    this.cachedChunkPos = key;
                    this.cachedChunkStatus = tracker.getOrDefault(key, 0);
                }
                if (this.cachedChunkStatus == 3) {
                    var section = this.world.getChunk(x, z).getSection(y - this.bottomSectionY);
                    var lp = this.world.getLightEngine();
                    var csp = SectionPos.of(x, y, z);
                    var blp = lp.getLayerListener(LightLayer.BLOCK).getDataLayerData(csp);
                    var slp = lp.getLayerListener(LightLayer.SKY).getDataLayerData(csp);
                    VoxelIngestService.rawIngest(system.getEngine(), section, x, y, z,
                            blp == null ? null : blp.copy(), slp == null ? null : slp.copy());
                }
            }
        }

        voxy$logSectionTransitionStats(system);
    }

    @Unique
    private static void voxy$logSectionTransitionStats(VoxyRenderSystem system) {
        if (!VOXY_LOG_SECTION_TRANSITIONS) return;

        long now = System.nanoTime();
        if ((now - voxy$lastSectionTransitionLogNanos) < VOXY_SECTION_TRANSITION_LOG_INTERVAL_NANOS) {
            return;
        }

        int tracked = system != null ? system.chunkBoundRenderer.getTrackedSectionCount() : -1;

        Logger.info("[VoxyDiag] sectionTransitions intervalMs="
                + (VOXY_SECTION_TRANSITION_LOG_INTERVAL_NANOS / 1_000_000L)
                + " builtToUnbuilt=" + voxy$transitionBuiltToUnbuilt
                + " unbuiltToBuilt=" + voxy$transitionUnbuiltToBuilt
                + " unchanged=" + voxy$transitionNoop
                + " visibleMaskTracked=" + tracked);

        voxy$transitionBuiltToUnbuilt = 0;
        voxy$transitionUnbuiltToBuilt = 0;
        voxy$transitionNoop = 0;
        voxy$lastSectionTransitionLogNanos = now;
    }

    @Unique
    private void voxy$tryIngestChunk(int x, int z, String reason) {
        if (this.world.levelRenderer == null || !VoxyConfig.CONFIG.isIngestEnabled()) {
            return;
        }
        long now = System.nanoTime();
        long key = ChunkPos.asLong(x, z);
        long last = this.voxy$recentChunkIngestNanos.getOrDefault(key, Long.MIN_VALUE);
        if (last != Long.MIN_VALUE && (now - last) < VOXY_INGEST_RETRY_NANOS) {
            this.voxy$ingestSkippedCooldown++;
            return;
        }
        if (this.voxy$recentChunkIngestNanos.size() > 32768) {
            this.voxy$recentChunkIngestNanos.clear();
        }
        this.voxy$recentChunkIngestNanos.put(key, now);

        this.voxy$ingestAttempted++;
        var chunkSource = this.world.getChunkSource();
        if (chunkSource == null) {
            return;
        }
        var chunk = chunkSource.getChunk(x, z, ChunkStatus.FULL, false);
        if (chunk == null) {
            this.voxy$ingestChunkMissing++;
            this.voxy$maybeLogIngestDiag(reason);
            return;
        }
        if (VoxelIngestService.tryAutoIngestChunk(chunk)) {
            this.voxy$ingestSucceeded++;
        }
        this.voxy$maybeLogIngestDiag(reason);
    }

    @Unique
    private void voxy$maybeLogIngestDiag(String reason) {
        if (!VOXY_LOG_INGEST_DIAG) return;
        if (VOXY_INGEST_DIAG_LOG_EVERY <= 0) return;
        if ((this.voxy$ingestAttempted % VOXY_INGEST_DIAG_LOG_EVERY) != 0) return;

        Logger.info("[VoxyDiag] ingestAttempts=" + this.voxy$ingestAttempted
                + " succeeded=" + this.voxy$ingestSucceeded
                + " missingChunk=" + this.voxy$ingestChunkMissing
                + " skippedCooldown=" + this.voxy$ingestSkippedCooldown
                + " reason=" + reason
                + " retryMs=" + (VOXY_INGEST_RETRY_NANOS / 1_000_000L));
    }
}
