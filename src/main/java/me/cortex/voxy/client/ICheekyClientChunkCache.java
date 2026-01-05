package me.cortex.voxy.client;

import net.minecraft.world.level.chunk.LevelChunk;

public interface ICheekyClientChunkCache {
    LevelChunk voxy$cheekyGetChunk(int x, int z);
}
