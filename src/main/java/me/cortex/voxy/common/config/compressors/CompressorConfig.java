package me.cortex.voxy.common.config.compressors;

import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.Serialization;

public abstract class CompressorConfig {
    static {
        Serialization.CONFIG_TYPES.add(CompressorConfig.class);
    }

    public abstract StorageCompressor build(ConfigBuildCtx ctx);
}
