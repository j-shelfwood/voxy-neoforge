package me.cortex.voxy.client.hud;

final class VoxyLoadingIndicatorModel {
    enum Mode {
        HIDDEN,
        INITIAL_LOAD,
        STREAMING
    }

    final Mode mode;
    final float alpha;
    final float progress;
    final float pulse;
    final int meshQueue;
    final int modelQueue;
    final int queuedNodeRequests;
    final int inFlightNodeRequests;
    final int currentPhaseLevel;
    final int completedRoots;
    final int totalRoots;
    final int loadedSections;

    VoxyLoadingIndicatorModel(Mode mode, float alpha, float progress, float pulse, int meshQueue, int modelQueue,
                              int queuedNodeRequests, int inFlightNodeRequests, int currentPhaseLevel,
                              int completedRoots, int totalRoots,
                              int loadedSections) {
        this.mode = mode;
        this.alpha = alpha;
        this.progress = progress;
        this.pulse = pulse;
        this.meshQueue = meshQueue;
        this.modelQueue = modelQueue;
        this.queuedNodeRequests = queuedNodeRequests;
        this.inFlightNodeRequests = inFlightNodeRequests;
        this.currentPhaseLevel = currentPhaseLevel;
        this.completedRoots = completedRoots;
        this.totalRoots = totalRoots;
        this.loadedSections = loadedSections;
    }

    boolean visible() {
        return this.mode != Mode.HIDDEN || this.alpha > 0.01f;
    }
}
