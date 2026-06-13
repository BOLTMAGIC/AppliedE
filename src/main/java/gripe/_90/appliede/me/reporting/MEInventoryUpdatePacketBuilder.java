package gripe._90.appliede.me.reporting;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.Objects;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.FriendlyByteBuf;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.AEKeyFilter;
import appeng.core.sync.BasePacketHandler;
import appeng.core.sync.packets.MEInventoryUpdatePacket;
import appeng.menu.me.common.GridInventoryEntry;
import appeng.menu.me.common.IncrementalUpdateHelper;

import gripe._90.appliede.mixin.tooltip.BasePacketAccessor;
import gripe._90.appliede.mixin.tooltip.MEInventoryUpdatePacketAccessor;

import io.netty.buffer.Unpooled;

/**
 * See {@link MEInventoryUpdatePacket}
 */
public class MEInventoryUpdatePacketBuilder extends MEInventoryUpdatePacket.Builder {
    private static final int UNCOMPRESSED_PACKET_BYTE_LIMIT = 512 * 1024;
    private static final int INITIAL_BUFFER_CAPACITY = 2 * 1024;

    private final List<MEInventoryUpdatePacket> packets = new ArrayList<>();
    private final int containerId;
    private final boolean fullUpdateFlag;
    // bounded LRU cache to avoid rebuilding identical full-update packets when the player
    // repeatedly opens the terminal and the underlying grid state did not change.
    private static final int MAX_CACHE_ENTRIES = 64;
    // use a primitive long-keyed fastutil linked map for lower overhead; synchronize manually
    private static final Long2ObjectLinkedOpenHashMap<CacheEntry> PACKET_CACHE = new Long2ObjectLinkedOpenHashMap<>();

    @Nullable
    private AEKeyFilter filter;

    @Nullable
    private FriendlyByteBuf data;

    private int itemCountOffset = -1;
    private int itemCount;

    public MEInventoryUpdatePacketBuilder(int containerId, boolean fullUpdate) {
        super(containerId, fullUpdate);
        this.containerId = containerId;
        this.fullUpdateFlag = fullUpdate;
    }

    @Override
    public void setFilter(@Nullable AEKeyFilter filter) {
        this.filter = filter;
    }

    public void addChanges(
            IncrementalUpdateHelper updateHelper,
            KeyCounter networkStorage,
            Set<AEKey> craftables,
            KeyCounter requestables,
            Set<AEItemKey> transmutables) {
        // if this is a full update, attempt to reuse cached packet list for identical grid snapshot
        int hash = Objects.hash(
                networkStorage.hashCode(), craftables.hashCode(), requestables.hashCode(), transmutables.hashCode());
        if (fullUpdateFlag) {
            long cacheKey = ((long) containerId << 32) ^ (hash & 0xffffffffL);
            CacheEntry cached;
            synchronized (PACKET_CACHE) {
                cached = PACKET_CACHE.get(cacheKey);
                if (cached != null) {
                    // move to most-recent by re-inserting
                    PACKET_CACHE.remove(cacheKey);
                    PACKET_CACHE.put(cacheKey, cached);
                }
            }

            if (cached != null) {
                // reuse cached packets
                packets.addAll(cached.packets);
                // nothing to do
                updateHelper.commitChanges();
                return;
            }
        }

        for (AEKey key : updateHelper) {
            if (filter != null && !filter.matches(key)) {
                continue;
            }

            AEKey sendKey;
            var serial = updateHelper.getSerial(key);

            // Try to serialize the item into the buffer
            if (serial == null) {
                // This is a new key, not sent to the client
                sendKey = key;
                serial = updateHelper.getOrAssignSerial(key);
            } else {
                // This is an incremental update referring back to the serial
                sendKey = null;
            }

            // The queued changes are actual differences, but we need to send the real stored properties
            // to the client.
            var storedAmount = networkStorage.get(key);
            var craftable = craftables.contains(key);
            var requestable = requestables.get(key);
            var transmutable = key instanceof AEItemKey && transmutables.contains(key);

            GridInventoryEntry entry;

            if (storedAmount <= 0 && requestable <= 0 && !craftable) {
                // This happens when an update is queued but the item is no longer stored
                entry = new GridInventoryEntry(serial, sendKey, 0, 0, false);
                updateHelper.removeSerial(key);
            } else {
                entry = new GridInventoryEntry(serial, sendKey, storedAmount, requestable, craftable);
                //noinspection DataFlowIssue
                ((GridInventoryEMCEntry) entry).appliede$setTransmutable(transmutable);
            }

            add(entry);
        }

        // commit and cache if this was a full update
        updateHelper.commitChanges();

        if (fullUpdateFlag) {
            long cacheKey = ((long) containerId << 32) ^ (hash & 0xffffffffL);
            synchronized (PACKET_CACHE) {
                PACKET_CACHE.put(cacheKey, new CacheEntry(List.copyOf(packets)));
                if (PACKET_CACHE.size() > MAX_CACHE_ENTRIES) {
                    // evict eldest entry (iterator returns insertion order)
                    var it = PACKET_CACHE.keySet().iterator();
                    if (it.hasNext()) {
                        long eldest = it.nextLong();
                        PACKET_CACHE.remove(eldest);
                    }
                }
            }
        }
    }

    @Override
    public void add(GridInventoryEntry entry) {
        var data = ensureData();
        GridInventoryEMCEntry.writeEntry(data, entry);
        ++itemCount;

        if (data.writerIndex() >= UNCOMPRESSED_PACKET_BYTE_LIMIT || itemCount >= Short.MAX_VALUE) {
            flushData();
        }
    }

    @SuppressWarnings("UnreachableCode")
    private void flushData() {
        if (data != null) {
            data.markWriterIndex();
            data.writerIndex(itemCountOffset);
            data.writeShort(itemCount);
            data.resetWriterIndex();

            var packet = MEInventoryUpdatePacketAccessor.create();
            ((BasePacketAccessor) packet).invokeConfigureWrite(data);
            packets.add(packet);

            data = null;
            itemCountOffset = -1;
            itemCount = 0;
        }
    }

    private FriendlyByteBuf ensureData() {
        if (data == null) {
            data = createPacketHeader();
        }

        return data;
    }

    private FriendlyByteBuf createPacketHeader() {
        var data = new FriendlyByteBuf(Unpooled.buffer(INITIAL_BUFFER_CAPACITY));
        data.writeInt(BasePacketHandler.PacketTypes.ME_INVENTORY_UPDATE.getPacketId());
        data.writeVarInt(containerId);
        data.writeBoolean(false);

        itemCountOffset = data.writerIndex();
        data.writeShort(0);

        return data;
    }

    @Override
    public List<MEInventoryUpdatePacket> build() {
        flushData();
        return packets;
    }

    private record CacheEntry(List<MEInventoryUpdatePacket> packets) {
    }
}
