package com.lookahead.domain.publication;

import java.util.LinkedHashMap;
import java.util.function.Supplier;

/** Byte and entry bounded LRU of verified content bytes; never caches authorization. */
public final class BoundedContentCache {
    private final long maximumBytes;
    private final int maximumEntries;
    private final LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long bytes;
    public BoundedContentCache(long maximumBytes, int maximumEntries) {
        if (maximumBytes < 0 || maximumEntries < 1) throw new IllegalArgumentException("Invalid cache bounds");
        this.maximumBytes = maximumBytes; this.maximumEntries = maximumEntries;
    }
    public synchronized byte[] read(String key, Supplier<byte[]> loader) {
        byte[] cached = entries.get(key);
        if (cached != null) return cached.clone();
        byte[] value = loader.get();
        if (maximumBytes > 0 && value.length <= maximumBytes) {
            while (!entries.isEmpty() && (bytes + value.length > maximumBytes || entries.size() >= maximumEntries)) {
                var oldest = entries.entrySet().iterator(); bytes -= oldest.next().getValue().length; oldest.remove();
            }
            entries.put(key, value.clone()); bytes += value.length;
        }
        return value;
    }
    public synchronized long bytes() { return bytes; }
    public synchronized int size() { return entries.size(); }
}
