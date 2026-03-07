package me.cortex.voxy.client.hud;

import me.cortex.voxy.client.core.VoxyLoadingSnapshot;
import net.minecraft.util.Mth;

final class VoxyLoadingIndicatorController {
    private static final long INITIAL_IDLE_GRACE_MS = 900L;

    private int rendererIdentity = 0;
    private boolean initialLoadComplete = false;
    private long initialIdleStartMs = -1L;

    private int peakPending = 1;
    private float displayedProgress = 0f;
    private float alpha = 0f;
    private float pulse = 0f;

    private VoxyLoadingIndicatorModel.Mode mode = VoxyLoadingIndicatorModel.Mode.HIDDEN;

    public VoxyLoadingIndicatorModel update(VoxyLoadingSnapshot snapshot, int rendererIdentity, float partialTick) {
        long nowMs = System.currentTimeMillis();
        if (this.rendererIdentity != rendererIdentity) {
            this.reset(rendererIdentity);
        }

        if (snapshot == null || snapshot.shuttingDown()) {
            this.mode = VoxyLoadingIndicatorModel.Mode.HIDDEN;
            this.alpha += (0f - this.alpha) * 0.25f;
            return new VoxyLoadingIndicatorModel(this.mode, this.alpha, this.displayedProgress, this.pulse, 0, 0, false, 0);
        }

        int pending = snapshot.pendingUnits();
        boolean active = snapshot.hasWork();

        if (!this.initialLoadComplete) {
            if (active) {
                this.initialIdleStartMs = -1L;
                this.mode = VoxyLoadingIndicatorModel.Mode.INITIAL_LOAD;
                this.peakPending = Math.max(this.peakPending, pending);
                float targetProgress = 1.0f - (pending / (float) Math.max(1, this.peakPending));
                this.displayedProgress += (targetProgress - this.displayedProgress) * 0.18f;
                this.alpha += (0.82f - this.alpha) * 0.15f;
            } else {
                this.displayedProgress += (1.0f - this.displayedProgress) * 0.2f;
                this.mode = VoxyLoadingIndicatorModel.Mode.INITIAL_LOAD;
                this.alpha += (0.72f - this.alpha) * 0.15f;
                if (this.initialIdleStartMs < 0L) {
                    this.initialIdleStartMs = nowMs;
                } else if ((nowMs - this.initialIdleStartMs) >= INITIAL_IDLE_GRACE_MS) {
                    this.initialLoadComplete = true;
                    this.mode = VoxyLoadingIndicatorModel.Mode.HIDDEN;
                }
            }
        } else {
            if (active) {
                this.mode = VoxyLoadingIndicatorModel.Mode.STREAMING;
                this.alpha += (0.68f - this.alpha) * 0.2f;
            } else {
                this.mode = VoxyLoadingIndicatorModel.Mode.HIDDEN;
                this.alpha += (0f - this.alpha) * 0.2f;
            }
        }

        this.pulse = (this.pulse + (0.015f + partialTick * 0.003f)) % 1.0f;
        this.alpha = Mth.clamp(this.alpha, 0f, 1f);
        this.displayedProgress = Mth.clamp(this.displayedProgress, 0f, 1f);

        return new VoxyLoadingIndicatorModel(
                this.mode,
                this.alpha,
                this.displayedProgress,
                this.pulse,
                snapshot.meshQueue(),
                snapshot.modelQueue(),
                snapshot.nodeWorkPending(),
                snapshot.loadedSections());
    }

    private void reset(int rendererIdentity) {
        this.rendererIdentity = rendererIdentity;
        this.initialLoadComplete = false;
        this.initialIdleStartMs = -1L;
        this.peakPending = 1;
        this.displayedProgress = 0f;
        this.alpha = 0f;
        this.pulse = 0f;
        this.mode = VoxyLoadingIndicatorModel.Mode.HIDDEN;
    }
}
