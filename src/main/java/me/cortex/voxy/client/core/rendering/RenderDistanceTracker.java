package me.cortex.voxy.client.core.rendering;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.util.Mth;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.function.LongConsumer;

public class RenderDistanceTracker {
    private static final int CHECK_DISTANCE_BLOCKS = 128;
    private final LongConsumer addTopLevelNode;
    private final LongConsumer removeTopLevelNode;
    private final int processRate;
    private final int minSec;
    private final int maxSec;
    private final LongOpenHashSet activeColumns = new LongOpenHashSet();
    private final PriorityQueue<ColumnOp> addFrontier = new PriorityQueue<>(ColumnOp.COMPARATOR);
    private final PriorityQueue<ColumnOp> removeFrontier = new PriorityQueue<>(ColumnOp.COMPARATOR);
    private int renderDistance;
    private double posX;
    private double posZ;
    private int centerSecX;
    private int centerSecZ;
    private long nextSequence;

    public RenderDistanceTracker(int rate, int minSec, int maxSec, LongConsumer addTopLevelNode, LongConsumer removeTopLevelNode) {
        this.addTopLevelNode = addTopLevelNode;
        this.removeTopLevelNode = removeTopLevelNode;
        this.renderDistance = 2;
        this.processRate = rate;
        this.minSec = minSec;
        this.maxSec = maxSec;
        this.rebuildFrontiers(0, 0);
    }

    public void setRenderDistance(int renderDistance) {
        if (renderDistance == this.renderDistance) {
            return;
        }
        this.renderDistance = renderDistance;
        this.rebuildFrontiers(this.centerSecX, this.centerSecZ);
    }

    public boolean setCenterAndProcess(double x, double z) {
        double dx = this.posX-x;
        double dz = this.posZ-z;
        if (CHECK_DISTANCE_BLOCKS*CHECK_DISTANCE_BLOCKS<dx*dx+dz*dz) {
            this.posX = x;
            this.posZ = z;
            this.rebuildFrontiers(Mth.floor(x) >> 9, Mth.floor(z) >> 9);
        }
        return this.process(this.processRate) != 0;
    }

    private void add(int x, int z) {
        for (int y = this.minSec; y <= this.maxSec; y++) {
            this.addTopLevelNode.accept(WorldEngine.getWorldSectionId(4, x, y, z));
        }
    }

    private void rem(int x, int z) {
        // Optional testing mode: keep previously loaded top-level nodes instead of unloading by camera distance.
        if (!VoxyConfig.CONFIG.isCameraDistanceCullingEnabled()) {
            return;
        }
        for (int y = this.minSec; y <= this.maxSec; y++) {
            this.removeTopLevelNode.accept(WorldEngine.getWorldSectionId(4, x, y, z));
        }
    }

    private void rebuildFrontiers(int newCenterSecX, int newCenterSecZ) {
        this.centerSecX = newCenterSecX;
        this.centerSecZ = newCenterSecZ;
        this.addFrontier.clear();
        this.removeFrontier.clear();

        LongOpenHashSet desiredColumns = new LongOpenHashSet();
        for (int dx = -this.renderDistance; dx <= this.renderDistance; dx++) {
            int x = this.centerSecX + dx;
            int maxDz = (int) Math.sqrt(this.renderDistance * this.renderDistance - dx * dx);
            for (int dz = -maxDz; dz <= maxDz; dz++) {
                int z = this.centerSecZ + dz;
                long packed = pack(x, z);
                desiredColumns.add(packed);
                if (!this.activeColumns.contains(packed)) {
                    this.addFrontier.add(new ColumnOp(x, z, distanceSq(x, z), this.nextSequence++));
                }
            }
        }

        if (VoxyConfig.CONFIG.isCameraDistanceCullingEnabled()) {
            for (long packed : this.activeColumns) {
                if (!desiredColumns.contains(packed)) {
                    this.removeFrontier.add(new ColumnOp(unpackX(packed), unpackZ(packed),
                            distanceSq(unpackX(packed), unpackZ(packed)), this.nextSequence++));
                }
            }
        }
    }

    private int process(int maxOperations) {
        int processed = 0;
        while (maxOperations > 0 && !this.addFrontier.isEmpty()) {
            ColumnOp op = this.addFrontier.poll();
            long packed = pack(op.x, op.z);
            if (this.activeColumns.add(packed)) {
                this.add(op.x, op.z);
                processed++;
            }
            maxOperations--;
        }
        while (maxOperations > 0 && !this.removeFrontier.isEmpty()) {
            ColumnOp op = this.removeFrontier.poll();
            long packed = pack(op.x, op.z);
            if (this.activeColumns.remove(packed)) {
                this.rem(op.x, op.z);
                processed++;
            }
            maxOperations--;
        }
        return processed;
    }

    private long distanceSq(int x, int z) {
        long dx = (long) x - this.centerSecX;
        long dz = (long) z - this.centerSecZ;
        return dx * dx + dz * dz;
    }

    private static long pack(int x, int z) {
        return Integer.toUnsignedLong(x) | (Integer.toUnsignedLong(z) << 32);
    }

    private static int unpackX(long packed) {
        return (int) (packed & 0xFFFFFFFFL);
    }

    private static int unpackZ(long packed) {
        return (int) ((packed >>> 32) & 0xFFFFFFFFL);
    }

    private record ColumnOp(int x, int z, long distanceSq, long sequence) {
        private static final Comparator<ColumnOp> COMPARATOR =
                Comparator.comparingLong(ColumnOp::distanceSq)
                        .thenComparingInt(ColumnOp::x)
                        .thenComparingInt(ColumnOp::z)
                        .thenComparingLong(ColumnOp::sequence);
    }
}
