package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.common.world.WorldEngine;

public final class LodPriorityUtil {
    public static final long DISTANCE_BUCKET_BLOCKS =
            Math.max(8L, Long.getLong("voxy.priorityDistanceBucketBlocks", 16L));

    private LodPriorityUtil() {
    }

    public static double distanceSquaredToSection(long position, double cameraX, double cameraY, double cameraZ) {
        int level = WorldEngine.getLevel(position);
        double size = 1 << (level + 5);
        double minX = (long) WorldEngine.getX(position) << (level + 5);
        double minY = (long) WorldEngine.getY(position) << (level + 5);
        double minZ = (long) WorldEngine.getZ(position) << (level + 5);
        double maxX = minX + size;
        double maxY = minY + size;
        double maxZ = minZ + size;

        double dx = axisDistance(cameraX, minX, maxX);
        double dy = axisDistance(cameraY, minY, maxY);
        double dz = axisDistance(cameraZ, minZ, maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    public static long distanceBucket(long position, double cameraX, double cameraY, double cameraZ) {
        double distance = Math.sqrt(distanceSquaredToSection(position, cameraX, cameraY, cameraZ));
        return (long) Math.floor(distance / DISTANCE_BUCKET_BLOCKS);
    }

    private static double axisDistance(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0.0;
    }
}
