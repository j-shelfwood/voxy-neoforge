package me.cortex.voxy.common.util;

import java.lang.ref.Cleaner;

import static me.cortex.voxy.common.util.GlobalCleaner.CLEANER;

public class ResizingThreadLocalMemoryBuffer {
    private static Pair<Cleaner.Cleanable, MemoryBuffer> createMemoryBuffer(long initalSize) {
        var buffer = new MemoryBuffer(initalSize);
        var ref = MemoryBuffer.createUntrackedUnfreeableRawFrom(buffer.address, buffer.size);
        var cleanable = CLEANER.register(ref, buffer::free);
        return new Pair<>(cleanable, ref);
    }

    //TODO: make this much better
    private final ThreadLocal<Pair<Cleaner.Cleanable, MemoryBuffer>> threadLocal;

    public ResizingThreadLocalMemoryBuffer(long initalSize) {
        this.threadLocal = ThreadLocal.withInitial(()->createMemoryBuffer(initalSize));
    }

    public MemoryBuffer get() {
        return this.threadLocal.get().right();
    }

    public MemoryBuffer get(long minSize) {
        var current = this.threadLocal.get();
        if (current.right().size<minSize) {
            //Not big enough
            current.left().clean();//free old memory and clear from cleaner
            //create new
            current = createMemoryBuffer(minSize+1024);//add 1kb just as extra to stop constant resize
            this.threadLocal.set(current);
        }
        return current.right();
    }
}
