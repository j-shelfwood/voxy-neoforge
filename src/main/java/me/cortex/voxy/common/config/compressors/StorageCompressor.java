package me.cortex.voxy.common.config.compressors;

import me.cortex.voxy.common.util.MemoryBuffer;

public interface StorageCompressor {
    MemoryBuffer compress(MemoryBuffer saveData);

    MemoryBuffer decompress(MemoryBuffer saveData);

    void close();
}
