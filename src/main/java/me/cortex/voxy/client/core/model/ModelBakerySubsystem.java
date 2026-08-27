package me.cortex.voxy.client.core.model;


import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.other.Mapper;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;

public class ModelBakerySubsystem {
    //Redo to just make it request the block faces with the async texture download stream which
    // basicly solves all the render stutter due to the baking

    private final ModelStore storage = new ModelStore();
    public final ModelFactory factory;
    private final Mapper mapper;
    private final AtomicInteger blockIdCount = new AtomicInteger();
    private final ConcurrentLinkedDeque<Integer> blockIdQueue = new ConcurrentLinkedDeque<>();//TODO: replace with custom DS

    private final Thread processingThread;
    private volatile boolean isRunning = true;
    public ModelBakerySubsystem(Mapper mapper) {
        this.mapper = mapper;
        this.factory = new ModelFactory(mapper, this.storage);
        this.processingThread = new Thread(()->{//TODO replace this with something good/integrate it into the async processor so that we just have less threads overall
            while (this.isRunning && !Thread.currentThread().isInterrupted()) {
                this.factory.processAllThings();
                // Sleep less when there's a large backlog (initial world load with many block states).
                // Drops to 1ms during heavy load so baked textures are available sooner,
                // reducing IdNotYetComputedException retries in RenderGenerationService.
                int sleepMs = this.blockIdCount.get() > 20 ? 1 : 10;
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    // Interrupted during shutdown — exit cleanly
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "Model factory processor");
        this.processingThread.start();
    }

    public void tick(long totalBudget) {
        this.tick(totalBudget, -1);
    }

    public void tick(long totalBudget, int framebufferBindingHint) {
        long start = System.nanoTime();
        this.factory.tickAndProcessUploads();
        //Always do 1 iteration minimum
        Integer i = this.blockIdQueue.poll();
        if (i != null) {
            int j = 0;
            if (i != null) {
                int fbBinding = framebufferBindingHint >= 0 ? framebufferBindingHint : glGetInteger(GL_FRAMEBUFFER_BINDING);

                do {
                    this.factory.addEntry(i);
                    j++;
                    // Process a small guaranteed batch, scaled by available frame budget.
                    // This prevents excessive render-thread spikes when frame time is already tight.
                    int minBlocks = totalBudget >= 1_500_000L ? 8 : (totalBudget >= 700_000L ? 4 : 2);
                    if (minBlocks < j && (totalBudget < (System.nanoTime() - start) + 50_000))
                        break;
                    i = this.blockIdQueue.poll();
                } while (i != null);

                glBindFramebuffer(GL_FRAMEBUFFER, fbBinding);//This is done here as stops needing to set then unset the fb in the thing 1000x
            }
            this.blockIdCount.addAndGet(-j);
        }

        //TimingStatistics.modelProcess.stop();
    }

    public void shutdown() {
        this.isRunning = false;
        this.processingThread.interrupt();
        try {
            // Give the processing thread a short window to exit cleanly.
            // Do NOT block the render thread indefinitely — the thread only does CPU work
            // and can be abandoned safely; free() below releases GPU resources on the render thread.
            this.processingThread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (this.processingThread.isAlive()) {
            Logger.warn("[ModelBakerySubsystem] Processing thread did not exit within 500ms, continuing shutdown anyway");
        }

        this.factory.free();
        this.storage.free();
    }

    // Lock-free set of block IDs that have been seen (queued or baked).
    // ConcurrentHashMap.newKeySet() gives lock-free add/contains/remove without serializing
    // all worker threads on a single ReentrantLock during initial load with thousands of IDs.
    private final Set<Integer> seenIds = ConcurrentHashMap.newKeySet(6000);
    private final ConcurrentHashMap<Integer, Long> nextRequeueMs = new ConcurrentHashMap<>();
    private static final long REQUEUE_COOLDOWN_MS = 3_000L;
    public void requestBlockBake(int blockId) {
        if (this.mapper.getBlockStateCount() < blockId) {
            Logger.error("Error, got bakeing request for out of range state id. StateId: " + blockId + " max id: " + this.mapper.getBlockStateCount(), new Exception());
            return;
        }
        // Fast path: already baked
        if (!this.factory.needsModelBakeForBlockId(blockId)) {
            return;
        }
        boolean isNew = this.seenIds.add(blockId);
        if (!isNew) {
            // Some states can remain unbaked for a while (dependency ordering/modpack quirks).
            // Allow low-frequency re-queue with cooldown to avoid hot retry churn.
            if (this.factory.needsModelBakeForBlockId(blockId)) {
                long now = System.currentTimeMillis();
                long next = this.nextRequeueMs.getOrDefault(blockId, 0L);
                if (now >= next) {
                    this.nextRequeueMs.put(blockId, now + REQUEUE_COOLDOWN_MS);
                    this.blockIdQueue.add(blockId);
                    this.blockIdCount.incrementAndGet();
                }
            }
            return;
        }
        this.nextRequeueMs.remove(blockId);
        this.blockIdQueue.add(blockId);
        this.blockIdCount.incrementAndGet();
    }

    public void addBiome(Mapper.BiomeEntry biomeEntry) {
        this.factory.addBiome(biomeEntry);
    }

    public void addDebugData(List<String> debug) {
        debug.add(String.format("MQ/IF/MC: %04d, %03d, %04d", this.blockIdCount.get(), this.factory.getInflightCount(),  this.factory.getBakedCount()));//Model bake queue/in flight/model baked count
    }

    public ModelStore getStore() {
        return this.storage;
    }

    public boolean areQueuesEmpty() {
        return this.blockIdCount.get()==0 && this.factory.getInflightCount() == 0;
    }

    public int getProcessingCount() {
        return this.blockIdCount.get() + this.factory.getInflightCount();
    }
}
