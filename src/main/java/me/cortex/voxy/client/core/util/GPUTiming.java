package me.cortex.voxy.client.core.util;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue;
import me.cortex.voxy.common.util.TrackedObject;

import java.lang.reflect.Array;
import java.util.Arrays;

import static org.lwjgl.opengl.ARBTimerQuery.GL_TIMESTAMP;
import static org.lwjgl.opengl.ARBTimerQuery.glQueryCounter;
import static org.lwjgl.opengl.GL15.glDeleteQueries;
import static org.lwjgl.opengl.GL15.glGenQueries;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL33.glGetQueryObjecti64;

public class GPUTiming {
    public static GPUTiming INSTANCE = new GPUTiming();

    private final GlTimestampQuerySet<String> timingSet = new GlTimestampQuerySet(String.class);

    private float[] timings = new float[0];
    private String[] lables = new String[0];

    private boolean enabled = false;

    public void marker() {
        this.marker(null);
    }

    public void marker(String lable) {
        if (this.enabled) {
            this.timingSet.capture(lable);
        }
    }

    public void setEnabled(boolean enable) {
        if (this.enabled != enable) {
            this.enabled = enable;
        }
    }

    public String getDebug() {
        if (!this.enabled) {
            return "";
        }
        StringBuilder str = new StringBuilder("GpuTime: [");
        for (int i = 0; i < this.timings.length; i++) {
            if (this.lables[i] != null) {
                str.append(this.lables[i]+":"+String.format("%.2f", this.timings[i]));
            } else {
                str.append(String.format("%.2f", this.timings[i]));
            }
            if (i!=this.timings.length-1) {
                str.append(", ");
            }
        }
        str.append(']');
        return str.toString();
    }

    public void tick() {
        this.timingSet.download((meta,data)->{
            long current = data[0];

            if (data.length-1!=this.timings.length) {
                this.timings = new float[data.length-1];
                this.lables = new String[meta.length-1];
            }

            Arrays.fill(this.lables, null);
            for (int i = 1; i < meta.length; i++) {
                long next = data[i];
                long delta = next - current;
                float time = (float) (((double)delta)/1_000_000);
                this.timings[i-1] = Math.max(this.timings[i-1]*0.99f+time*0.01f, time);
                this.lables[i-1] = meta[i-1];
                current = next;
            }
        });
        this.timingSet.tick();
    }

    public void free() {
        this.timingSet.free();
    }

    public interface TimingDataConsumer <T> {
        void accept(T metadata, long[] timings);
    }
    private static final class GlTimestampQuerySet <T> extends TrackedObject {

        private record InflightRequest<T>(int[] queries, T[] meta, TimingDataConsumer<T[]> callback) {
            private boolean callbackIfReady(IntArrayFIFOQueue queryPool) {
                boolean ready = glGetQueryObjecti(this.queries[this.queries.length-1], GL_QUERY_RESULT_AVAILABLE) == GL_TRUE;
                if (!ready) {
                    return false;
                }
                long[] results = new long[this.queries.length];
                for (int i = 0; i < this.queries.length; i++) {
                    results[i] = glGetQueryObjecti64(this.queries[i], GL_QUERY_RESULT);
                    queryPool.enqueue(this.queries[i]);
                }
                this.callback.accept(this.meta, results);
                return true;
            }
        }
        private final IntArrayFIFOQueue POOL = new IntArrayFIFOQueue();
        private final ObjectArrayFIFOQueue<InflightRequest<T>> INFLIGHT = new ObjectArrayFIFOQueue();

        private final int[] queries = new int[64];
        private final T[] metadata;
        private int index;


        private GlTimestampQuerySet(Class<T> metaClass) {
            this.metadata = (T[]) Array.newInstance(metaClass, 64);
        }

        public void capture(T metadata) {
            if (this.index > this.metadata.length) {
                throw new IllegalStateException();
            }
            int slot = this.index++;
            this.metadata[slot] = metadata;
            int query = this.getQuery();
            glQueryCounter(query, GL_TIMESTAMP);
            this.queries[slot] = query;

        }

        public void download(TimingDataConsumer<T[]> consumer) {
            if (this.index != 0) {
                var queries = Arrays.copyOf(this.queries, this.index);
                var metadata = Arrays.copyOf(this.metadata, this.index);
                Arrays.fill(this.metadata, null);
                this.index = 0;
                this.INFLIGHT.enqueue(new InflightRequest(queries, metadata, consumer));
            }
        }

        public void tick() {
            while (!INFLIGHT.isEmpty()) {
                if (INFLIGHT.first().callbackIfReady(POOL)) {
                    INFLIGHT.dequeue();
                } else {
                    break;
                }
            }
        }

        private int getQuery() {
            if (POOL.isEmpty()) {
                return glGenQueries();
            } else {
                return POOL.dequeueInt();
            }
        }

        @Override
        public void free() {
            super.free0();
            while (!POOL.isEmpty()) {
                glDeleteQueries(POOL.dequeueInt());
            }
            while (!INFLIGHT.isEmpty()) {
                glDeleteQueries(INFLIGHT.dequeue().queries);
            }
        }
    }
    /*
    private static final class GlTimestampQuerySet extends TrackedObject {
        private final int query = glGenQueries();
        public final GlBuffer store;
        public final int[] metadata;
        public int index;
        public GlTimestampQuerySet(int maxCount) {
            this.store = new GlBuffer(maxCount*8L);
            this.metadata = new int[maxCount];
        }

        public void capture(int metadata) {
            if (this.index>this.metadata.length) {
                throw new IllegalStateException();
            }
            int slot = this.index++;
            this.metadata[slot] = metadata;
            glQueryCounter(this.query, GL_TIMESTAMP);//This should be gpu side, so should be fast
            glFinish();
            glGetQueryBufferObjectui64v(this.query, this.store.id, GL_QUERY_RESULT_NO_WAIT, slot*8L);
            glMemoryBarrier(-1);
        }

        public void download(TimingDataConsumer consumer) {
            var meta = Arrays.copyOf(this.metadata, this.index);
            this.index = 0;
            //DownloadStream.INSTANCE.download(this.store, buffer->consumer.accept(meta, buffer));
        }

        @Override
        public void free() {
            super.free0();
            glDeleteQueries(this.query);
            this.store.free();
        }
    }*/
}
