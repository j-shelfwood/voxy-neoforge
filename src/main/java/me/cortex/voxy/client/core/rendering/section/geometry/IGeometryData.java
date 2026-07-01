package me.cortex.voxy.client.core.rendering.section.geometry;

public interface IGeometryData {
    int getSectionCount();
    void free();
    long getMaxCapacity();
}
