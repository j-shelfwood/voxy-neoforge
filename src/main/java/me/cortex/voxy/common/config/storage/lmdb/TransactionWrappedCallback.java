package me.cortex.voxy.common.config.storage.lmdb;

public interface TransactionWrappedCallback<T> {
    T exec(TransactionWrapper wrapper);
}
