package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.core.rendering.LodPriorityUtil;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.common.world.WorldEngine;

import java.util.Comparator;
import java.util.PriorityQueue;

final class LodPhaseScheduler {
    private final NodeManager manager;

    private final PriorityQueue<PhaseRequest> activeRequests = new PriorityQueue<>(PhaseRequest.COMPARATOR);
    private final PriorityQueue<PhaseRequest> deferredRequests = new PriorityQueue<>(PhaseRequest.COMPARATOR);
    private final LongOpenHashSet queuedRequests = new LongOpenHashSet();
    private final LongArrayList reusableRoots = new LongArrayList();
    private final LongArrayList reusableChildren = new LongArrayList(8);

    private double cameraX;
    private double cameraY;
    private double cameraZ;
    private long sequence;
    private boolean rootsDirty = true;
    private boolean phaseScanPending = true;
    private int currentPhaseLevel = WorldEngine.MAX_LOD_LAYER;
    private int completedRoots;
    private int totalRoots;
    private long starvationRecoveries;
    private long staleRequestDrops;

    LodPhaseScheduler(NodeManager manager) {
        this.manager = manager;
    }

    public void updateCamera(double x, double y, double z) {
        this.cameraX = x;
        this.cameraY = y;
        this.cameraZ = z;
    }

    public void onRootsChanged() {
        this.rootsDirty = true;
        this.phaseScanPending = true;
        this.clearQueues();
    }

    public void reset() {
        this.currentPhaseLevel = WorldEngine.MAX_LOD_LAYER;
        this.completedRoots = 0;
        this.totalRoots = 0;
        this.starvationRecoveries = 0L;
        this.staleRequestDrops = 0L;
        this.rootsDirty = true;
        this.phaseScanPending = true;
        this.clearQueues();
    }

    public void enqueueVisibleCandidate(long position) {
        this.refreshPhaseState();
        this.enqueueCandidate(position, true);
    }

    public int drainRequests(int budget) {
        this.refreshPhaseState();
        if (budget <= 0 || this.currentPhaseLevel == WorldEngine.MAX_LOD_LAYER) {
            return 0;
        }
        this.seedCurrentPhaseIfNeeded();
        int processed = 0;
        while (processed < budget) {
            PhaseRequest request = this.pollNext();
            if (request == null) {
                break;
            }
            this.queuedRequests.remove(request.position);
            if (!this.manager.isRequestableAtPhase(request.position, this.currentPhaseLevel)) {
                this.staleRequestDrops++;
                this.phaseScanPending = true;
                continue;
            }
            this.manager.processRequest(request.position);
            processed++;
        }
        if (processed == 0 && this.activeRequests.isEmpty() && this.completedRoots < this.totalRoots) {
            this.phaseScanPending = true;
            this.starvationRecoveries++;
        }
        return processed;
    }

    public boolean hasRunnableWork() {
        this.refreshPhaseState();
        if (!this.activeRequests.isEmpty()) {
            return true;
        }
        return this.currentPhaseLevel < WorldEngine.MAX_LOD_LAYER && this.phaseScanPending;
    }

    public RenderGenerationService.MeshUrgency getMeshUrgency(long position, boolean visibleUrgency) {
        int targetPhase = this.manager.getRequestPhaseLevel(position);
        int phaseLevel = targetPhase == Integer.MIN_VALUE ? Math.max(this.currentPhaseLevel, WorldEngine.getLevel(position)) : targetPhase;
        return new RenderGenerationService.MeshUrgency(phaseLevel, visibleUrgency);
    }

    public DebugState getDebugState() {
        this.refreshPhaseState();
        return new DebugState(
                this.currentPhaseLevel,
                this.completedRoots,
                this.totalRoots,
                this.activeRequests.size(),
                this.deferredRequests.size(),
                this.starvationRecoveries,
                this.staleRequestDrops
        );
    }

    public LoadingState getLoadingState(int inFlightRequests) {
        this.refreshPhaseState();
        return new LoadingState(
                this.activeRequests.size(),
                inFlightRequests,
                this.currentPhaseLevel,
                this.completedRoots,
                this.totalRoots
        );
    }

    public int getCurrentPhaseLevel() {
        this.refreshPhaseState();
        return this.currentPhaseLevel;
    }

    private void refreshPhaseState() {
        if (this.rootsDirty) {
            this.totalRoots = this.manager.snapshotTopLevelRoots().size();
            this.rootsDirty = false;
        }
        int phase = WorldEngine.MAX_LOD_LAYER;
        int complete = this.totalRoots == 0 ? 0 : this.manager.countRootsCompleteAtPhase(phase);
        while (phase > 0 && complete == this.totalRoots) {
            phase--;
            complete = this.totalRoots == 0 ? 0 : this.manager.countRootsCompleteAtPhase(phase);
        }
        if (this.totalRoots == 0) {
            complete = 0;
        }
        if (phase != this.currentPhaseLevel) {
            this.currentPhaseLevel = phase;
            this.phaseScanPending = true;
            this.clearQueues();
        }
        this.completedRoots = complete;
    }

    private void seedCurrentPhaseIfNeeded() {
        if (this.activeRequests.size() > 0 || this.currentPhaseLevel == WorldEngine.MAX_LOD_LAYER) {
            return;
        }
        if (!this.phaseScanPending && this.completedRoots == this.totalRoots) {
            return;
        }
        this.reusableRoots.clear();
        this.reusableRoots.addAll(this.manager.snapshotTopLevelRoots());
        this.reusableRoots.sort((a, b) -> {
            int cmp = Long.compare(
                    LodPriorityUtil.distanceBucket(a, this.cameraX, this.cameraY, this.cameraZ),
                    LodPriorityUtil.distanceBucket(b, this.cameraX, this.cameraY, this.cameraZ)
            );
            return cmp != 0 ? cmp : Long.compare(a, b);
        });
        for (int i = 0; i < this.reusableRoots.size(); i++) {
            this.scanNodeForCurrentPhase(this.reusableRoots.getLong(i), false);
        }
        this.phaseScanPending = false;
        this.promoteDeferredForCurrentPhase();
    }

    private void scanNodeForCurrentPhase(long position, boolean visibleCandidate) {
        NodeManager.NodeState state = this.manager.getNodeState(position);
        if (!state.exists() || state.request()) {
            return;
        }
        int requestPhase = this.manager.getRequestPhaseLevel(position);
        if (requestPhase == this.currentPhaseLevel) {
            this.enqueueCandidate(position, visibleCandidate);
            return;
        }
        if (state.lodLevel() <= this.currentPhaseLevel) {
            return;
        }
        this.manager.getExistingChildren(position, this.reusableChildren);
        if (this.reusableChildren.isEmpty()) {
            return;
        }
        this.reusableChildren.sort((a, b) -> {
            int cmp = Long.compare(
                    LodPriorityUtil.distanceBucket(a, this.cameraX, this.cameraY, this.cameraZ),
                    LodPriorityUtil.distanceBucket(b, this.cameraX, this.cameraY, this.cameraZ)
            );
            return cmp != 0 ? cmp : Long.compare(a, b);
        });
        for (int i = 0; i < this.reusableChildren.size(); i++) {
            this.scanNodeForCurrentPhase(this.reusableChildren.getLong(i), visibleCandidate);
        }
    }

    private void enqueueCandidate(long position, boolean visibleCandidate) {
        int phaseLevel = this.manager.getRequestPhaseLevel(position);
        if (phaseLevel == Integer.MIN_VALUE) {
            return;
        }
        if (!this.queuedRequests.add(position)) {
            return;
        }
        PhaseRequest request = new PhaseRequest(
                position,
                phaseLevel,
                LodPriorityUtil.distanceBucket(position, this.cameraX, this.cameraY, this.cameraZ),
                visibleCandidate,
                this.computeUrgency(position, phaseLevel),
                this.sequence++
        );
        if (phaseLevel == this.currentPhaseLevel) {
            this.activeRequests.add(request);
        } else {
            this.deferredRequests.add(request);
        }
    }

    private void promoteDeferredForCurrentPhase() {
        PriorityQueue<PhaseRequest> remaining = new PriorityQueue<>(PhaseRequest.COMPARATOR);
        while (!this.deferredRequests.isEmpty()) {
            PhaseRequest request = this.deferredRequests.poll();
            if (request.phaseLevel == this.currentPhaseLevel) {
                this.activeRequests.add(request);
            } else {
                remaining.add(request);
            }
        }
        this.deferredRequests.addAll(remaining);
    }

    private PhaseRequest pollNext() {
        this.promoteDeferredForCurrentPhase();
        return this.activeRequests.poll();
    }

    private int computeUrgency(long position, int phaseLevel) {
        NodeManager.NodeState state = this.manager.getNodeState(position);
        if (state.inner() && !state.hasMesh()) {
            return 0;
        }
        if (state.leaf() && phaseLevel == state.lodLevel() - 1) {
            return 1;
        }
        return 2;
    }

    private void clearQueues() {
        this.activeRequests.clear();
        this.deferredRequests.clear();
        this.queuedRequests.clear();
    }

    record DebugState(int currentPhaseLevel, int completedRoots, int totalRoots, int queuedCurrentPhase,
                      int queuedDeferredPhase, long starvationRecoveries, long staleRequestDrops) {}

    record LoadingState(int queuedRequests, int inFlightRequests, int currentPhaseLevel, int completedRoots, int totalRoots) {}

    private record PhaseRequest(long position, int phaseLevel, long distanceBucket, boolean visibleCandidate, int urgencyRank, long sequence) {
        private static final Comparator<PhaseRequest> COMPARATOR =
                Comparator.comparingInt(PhaseRequest::phaseLevel).reversed()
                        .thenComparingLong(PhaseRequest::distanceBucket)
                        .thenComparingInt(request -> request.visibleCandidate ? 0 : 1)
                        .thenComparingInt(PhaseRequest::urgencyRank)
                        .thenComparingLong(PhaseRequest::sequence);
    }
}
