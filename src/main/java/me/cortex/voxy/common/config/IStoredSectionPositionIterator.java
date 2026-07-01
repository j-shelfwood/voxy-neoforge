package me.cortex.voxy.common.config;

import java.util.function.LongConsumer;

public interface IStoredSectionPositionIterator {
    void iteratePositions(int level, LongConsumer callback);
}
