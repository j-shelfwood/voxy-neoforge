package me.cortex.voxy.client.core;

public record VoxyLoadingSnapshot(
        int meshQueue,
        int modelQueue,
        int queuedNodeRequests,
        int inFlightNodeRequests,
        int currentPhaseLevel,
        int completedRoots,
        int totalRoots,
        int loadedSections,
        boolean shuttingDown) {

    public int pendingUnits() {
        return this.meshQueue + this.modelQueue + this.queuedNodeRequests + this.inFlightNodeRequests;
    }

    public boolean hasWork() {
        return this.pendingUnits() > 0;
    }

    public float phaseProgress() {
        if (this.totalRoots <= 0) {
            return 0.0f;
        }
        return Math.max(0.0f, Math.min(1.0f, this.completedRoots / (float) this.totalRoots));
    }
}
