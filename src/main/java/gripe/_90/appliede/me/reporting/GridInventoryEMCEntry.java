package gripe._90.appliede.me.reporting;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import gripe._90.appliede.AppliedEConfig;

import appeng.api.stacks.AEKey;
import appeng.menu.me.common.GridInventoryEntry;

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
            var bytes = KEY_CACHE.get(what);
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
    Map<AEKey, byte[]> KEY_CACHE = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<AEKey, byte[]> eldest) {
            return size() > KEY_CACHE_MAX;
        }
    });

    // Simple hit/miss counters for the KEY_CACHE to aid tuning
    LongAdder KEY_CACHE_HITS = new LongAdder();
    LongAdder KEY_CACHE_MISSES = new LongAdder();

    /**
     * Precompute and cache the serialized bytes for an AEKey using the same format as
     * AEKey.writeOptionalKey(FriendlyByteBuf). Safe to call repeatedly.
     */
    static void warmKey(AEKey key) {
        if (key == null) return;
        if (KEY_CACHE.containsKey(key)) {
            KEY_CACHE_HITS.increment();
            return;
        }

        var tmp = new FriendlyByteBuf(Unpooled.buffer(256));
        AEKey.writeOptionalKey(tmp, key);
        int len = tmp.readableBytes();
        byte[] data = new byte[len];
        // copy bytes without modifying reader/writer indices
        tmp.getBytes(0, data);
        KEY_CACHE.put(key, data);
    }
}
