package gripe._90.appliede.me.reporting;

import java.util.concurrent.atomic.LongAdder;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;

import net.minecraft.network.FriendlyByteBuf;

import appeng.api.stacks.AEKey;
import appeng.menu.me.common.GridInventoryEntry;

import gripe._90.appliede.AppliedEConfig;

import io.netty.buffer.Unpooled;

@SuppressWarnings("unused")
public interface GridInventoryEMCEntry {
    boolean appliede$isTransmutable();

    void appliede$setTransmutable(boolean extractable);

    @SuppressWarnings("UnreachableCode")
    static GridInventoryEntry readEntry(FriendlyByteBuf buffer) {
        var serial = buffer.readVarLong();
        var what = AEKey.readOptionalKey(buffer);
        var storedAmount = buffer.readVarLong();
        var requestableAmount = buffer.readVarLong();
        var craftable = buffer.readBoolean();
        var transmutable = buffer.readBoolean();

        var entry = new GridInventoryEntry(serial, what, storedAmount, requestableAmount, craftable);
        //noinspection DataFlowIssue
        ((GridInventoryEMCEntry) entry).appliede$setTransmutable(transmutable);
        return entry;
    }

    static void writeEntry(FriendlyByteBuf buffer, GridInventoryEntry entry) {
        buffer.writeVarLong(entry.getSerial());
        // Try to use cached serialized AEKey bytes to avoid repeated AEKey -> buffer serialization
        var what = entry.getWhat();
        if (what != null) {
            // ensure a cached serialization exists (warm if necessary)
            warmKey(what);
            byte[] bytes;
            synchronized (KEY_CACHE) {
                bytes = KEY_CACHE.get(what);
                if (bytes != null) {
                    // move to most-recent by reinserting
                    KEY_CACHE.remove(what);
                    KEY_CACHE.put(what, bytes);
                }
            }

            if (bytes != null) {
                KEY_CACHE_HITS.increment();
                buffer.writeBytes(bytes);
            } else {
                KEY_CACHE_MISSES.increment();
                AEKey.writeOptionalKey(buffer, what);
            }
        } else {
            AEKey.writeOptionalKey(buffer, null);
        }
        buffer.writeVarLong(entry.getStoredAmount());
        buffer.writeVarLong(entry.getRequestableAmount());
        buffer.writeBoolean(entry.isCraftable());
        buffer.writeBoolean(((GridInventoryEMCEntry) entry).appliede$isTransmutable());
    }

    // Simple bounded LRU cache for serialized AEKey byte forms to reduce allocations when writing
    // entries frequently (synchronized for simplicity; accesses are cheap and on server thread).
    int KEY_CACHE_MAX = AppliedEConfig.CONFIG.getKeyCacheMax();
    // fastutil linked map used as LRU; we manually synchronize accesses and evict eldest when needed
    Object2ObjectLinkedOpenHashMap<AEKey, byte[]> KEY_CACHE = new Object2ObjectLinkedOpenHashMap<>();

    // Simple hit/miss counters for the KEY_CACHE to aid tuning
    LongAdder KEY_CACHE_HITS = new LongAdder();
    LongAdder KEY_CACHE_MISSES = new LongAdder();

    /**
     * Precompute and cache the serialized bytes for an AEKey using the same format as
     * AEKey.writeOptionalKey(FriendlyByteBuf). Safe to call repeatedly.
     */
    static void warmKey(AEKey key) {
        if (key == null) return;

        synchronized (KEY_CACHE) {
            if (KEY_CACHE.containsKey(key)) {
                KEY_CACHE_HITS.increment();
                return;
            }
        }

        var tmp = new FriendlyByteBuf(Unpooled.buffer(256));
        AEKey.writeOptionalKey(tmp, key);
        int len = tmp.readableBytes();
        byte[] data = new byte[len];
        // copy bytes without modifying reader/writer indices
        tmp.getBytes(0, data);

        synchronized (KEY_CACHE) {
            KEY_CACHE.put(key, data);
            if (KEY_CACHE.size() > KEY_CACHE_MAX) {
                var it = KEY_CACHE.keySet().iterator();
                if (it.hasNext()) {
                    var eldest = it.next();
                    KEY_CACHE.remove(eldest);
                }
            }
        }
    }

    // Simple accessors for instrumentation
    static long getKeyCacheHits() {
        return KEY_CACHE_HITS.sum();
    }

    static long getKeyCacheMisses() {
        return KEY_CACHE_MISSES.sum();
    }

    static int getKeyCacheSize() {
        synchronized (KEY_CACHE) {
            return KEY_CACHE.size();
        }
    }
}
