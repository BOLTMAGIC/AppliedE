package gripe._90.appliede.me.service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.OnDatapackSyncEvent;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridService;
import appeng.api.networking.IGridServiceProvider;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionHost;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.MEStorage;
import appeng.me.storage.NullInventory;

import gripe._90.appliede.AppliedEConfig;
import gripe._90.appliede.me.misc.TransmutationPattern;
import gripe._90.appliede.mixin.misc.TransmutationOfflineAccessor;
import gripe._90.appliede.part.EMCModulePart;

import moze_intel.projecte.api.capabilities.IKnowledgeProvider;
import moze_intel.projecte.api.event.PlayerKnowledgeChangeEvent;
import moze_intel.projecte.api.proxy.IEMCProxy;
import moze_intel.projecte.api.proxy.ITransmutationProxy;

@SuppressWarnings("unused")
public class KnowledgeService implements IGridService, IGridServiceProvider {
    private static final int TICKS_PER_SYNC = AppliedEConfig.CONFIG.getSyncThrottleInterval();

    private final List<IManagedGridNode> moduleNodes = new ArrayList<>();
    private final Object2ObjectOpenHashMap<UUID, Supplier<IKnowledgeProvider>> providers = new Object2ObjectOpenHashMap<>();
    private final EMCStorage storage = new EMCStorage(this);
    private final List<IPatternDetails> temporaryPatterns = new ArrayList<>();
    private final TeamProjectEHandler.Proxy tpeHandler = new TeamProjectEHandler.Proxy();

    private final IGrid grid;
    private Set<AEItemKey> knownItemCache;
    /** Cache of EMC values for known AEItemKey instances to avoid repeated ItemStack creation and ProjectE lookups. */
    private final Object2LongOpenHashMap<AEItemKey> emcCache = new Object2LongOpenHashMap<>();
    // persisted simple string -> long map (key string -> emc) loaded from disk (primitive long to avoid boxing)
    private final Object2LongOpenHashMap<String> persistedEmc = new Object2LongOpenHashMap<>();
    private final Path emcCacheFile;
    // LRU cache for AEItemKey -> String to avoid repeated toString() allocations in hot loops
    // Converted to fastutil linked map with manual synchronization to avoid boxed iteration overhead
    private final it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap<AEItemKey, String> keyStringCache
            = new it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap<>();
    private final LongAdder KEY_STRING_CACHE_HITS = new LongAdder();
    private final LongAdder KEY_STRING_CACHE_MISSES = new LongAdder();
    // Warm queue for serialized AEKey caching. Background thread dedupes and main thread finalizes.
    private final ConcurrentLinkedQueue<appeng.api.stacks.AEKey> warmQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<appeng.api.stacks.AEKey> finalWarmQueue = new ConcurrentLinkedQueue<>();
    // cache of created TransmutationPattern objects for known items to avoid allocations on each getPatterns()
    private final Object2ObjectOpenHashMap<AEItemKey, TransmutationPattern> patternCache = new Object2ObjectOpenHashMap<>();
    private final List<TransmutationPattern> tierPatterns = new ArrayList<>();
    private int cachedHighestTier = 1;

    private boolean needsSync;
    private int ticksSinceLastSync;
    // Shared scheduled executor used for lightweight background tasks to avoid creating
    // an executor per KnowledgeService instance (which can exhaust native threads).
    private static final ScheduledExecutorService SHARED_SCHEDULER = Executors.newScheduledThreadPool(2, r -> {
        var t = new Thread(r, "appliede-shared-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final java.util.concurrent.atomic.AtomicBoolean saveScheduled = new java.util.concurrent.atomic.AtomicBoolean(false);
    // debounce interval is now configurable via AppliedEConfig

    // One-time startup seeding flags: when we load a persisted EMC TSV we mark that we should
    // attempt to enqueue warm requests for matching known items on the main thread in small batches.
    private boolean startupSeedQueued = false;
    private boolean startupSeeded = false;
    // simple server tick counter maintained from onServerStartTick()
    private int serverTickCounter = 0;
    // track last pattern update tick per node to avoid frequent repeated requestUpdate() calls
    private final java.util.WeakHashMap<IManagedGridNode, Integer> lastPatternUpdateTick = new java.util.WeakHashMap<>();
    // fingerprint of published patterns per node to suppress unchanged updates
    private final java.util.WeakHashMap<IManagedGridNode, Integer> lastPublishedFingerprint = new java.util.WeakHashMap<>();

    public KnowledgeService(IGrid grid) {
        this.grid = grid;
        emcCacheFile = Paths.get("run", "config", "appliede", "emc_cache.tsv");
        loadEmcCacheFromDisk();

        // start background task to aggregate and dedupe warm requests into finalWarmQueue
        // Use shared scheduler to avoid allocating one thread per KnowledgeService instance.
        SHARED_SCHEDULER.scheduleWithFixedDelay(() -> {
            try {
                if (warmQueue.isEmpty()) return;
                var dedup = new java.util.LinkedHashSet<appeng.api.stacks.AEKey>();
                appeng.api.stacks.AEKey k;
                while ((k = warmQueue.poll()) != null) {
                    dedup.add(k);
                    if (dedup.size() >= 1024) break; // limit per aggregation to avoid long background runs
                }

                for (var key : dedup) {
                    finalWarmQueue.offer(key);
                }
            } catch (Throwable t) {
                // best effort; swallow to avoid scheduler termination
            }
        }, 100, 100, TimeUnit.MILLISECONDS);

        MinecraftForge.EVENT_BUS.addListener((PlayerKnowledgeChangeEvent event) -> {
            knownItemCache = null;
            emcCache.clear();
            // clear pattern cache since known items changed
            patternCache.clear();
            // clear persisted map as knowledge changed; will be rebuilt on next known items scan
            persistedEmc.clear();
            // Knowledge changed: force immediate pattern update so AE2 sees new transmutations
            forceUpdatePatterns();
        });
        MinecraftForge.EVENT_BUS.addListener((OnDatapackSyncEvent event) -> {
            if (event.getPlayer() == null) {
                knownItemCache = null;
                emcCache.clear();
                patternCache.clear();
                persistedEmc.clear();
                // Datapack sync affects knowledge; ensure immediate refresh
                forceUpdatePatterns();
            }
        });
        // Note: io tasks also run on the shared scheduler to avoid per-instance threads.
        // (No per-instance executor allocation.)
    }

    @Override
    public void addNode(IGridNode gridNode, @Nullable CompoundTag savedData) {
        if (gridNode.getOwner() instanceof EMCModulePart module) {
            knownItemCache = null;
            emcCache.clear();
            moduleNodes.add(module.getMainNode());
            var uuid = gridNode.getOwningPlayerProfileId();

            if (uuid != null) {
                addProvider(uuid);
            }

            // node was added; ensure crafting providers are updated immediately for correctness
            forceUpdatePatterns();
        }
    }

    @Override
    public void removeNode(IGridNode gridNode) {
        if (gridNode.getOwner() instanceof EMCModulePart module) {
            knownItemCache = null;
            emcCache.clear();
            moduleNodes.remove(module.getMainNode());
            providers.clear();
            tpeHandler.clear();

            for (var mainNode : moduleNodes) {
                var node = mainNode.getNode();

                if (node != null) {
                    var uuid = node.getOwningPlayerProfileId();

                    if (uuid != null) {
                        addProvider(uuid);
                    }
                }
            }

            moduleNodes.forEach(IStorageProvider::requestUpdate);
            // node removed; force immediate update so providers reflect current nodes
            forceUpdatePatterns();
        }
    }

    @Override
    public void onServerStartTick() {
        // increment server tick counter
        serverTickCounter++;
        if (ticksSinceLastSync < TICKS_PER_SYNC) {
            ticksSinceLastSync++;
        }

        if (needsSync && ticksSinceLastSync == TICKS_PER_SYNC) {
            tpeHandler.syncTeamProviders(providers);
            needsSync = false;
            ticksSinceLastSync = 0;
        }

        // finalize a bounded number of warm keys per tick to avoid spikes
        // perform a one-time startup seed from persisted EMC strings into the warm queue
        if (startupSeedQueued && !startupSeeded) {
            try {
                var providersList = getProviders();
                // build a small seed list of AEKeys that appear in persistedEmc (bounded to avoid long work)
                var seeded = 0;
                final int MAX_SEED = 10000;
                for (var provider : providersList) {
                    for (var item : provider.getKnowledge()) {
                        if (seeded >= MAX_SEED) break;
                        try {
                            var stack = item.createStack();
                            var key = AEItemKey.of(stack);
                            if (key == null) continue;
                            var keyStr = key.toString();
                            if (persistedEmc.containsKey(keyStr)) {
                                warmQueue.offer(key);
                                seeded++;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    if (seeded >= MAX_SEED) break;
                }
            } catch (Throwable ignored) {
            } finally {
                startupSeeded = true;
                startupSeedQueued = false;
            }
        }

        var toProcess = AppliedEConfig.CONFIG.getWarmKeysPerTick();
        for (int i = 0; i < toProcess; i++) {
            var key = finalWarmQueue.poll();
            if (key == null) break;
            try {
                // perform actual serialization/cache fill on main thread
                gripe._90.appliede.me.reporting.GridInventoryEMCEntry.warmKey(key);
            } catch (Throwable ignored) {
            }
        }
    }

    private void addProvider(UUID playerUUID) {
        if (!providers.containsKey(playerUUID)) {
            providers.put(playerUUID, retrieveProvider(playerUUID));
        }
    }

    /**
     * Enqueue an AEKey to be warmed. This is safe to call from any thread.
     */
    @SuppressWarnings("unused")
    public void warmKey(appeng.api.stacks.AEKey key) {
        if (key == null) return;
        warmQueue.offer(key);
    }

    static Supplier<IKnowledgeProvider> retrieveProvider(UUID playerUUID) {
        return () -> {
            try {
                return ITransmutationProxy.INSTANCE.getKnowledgeProviderFor(playerUUID);
            } catch (Throwable e) {
                return TransmutationOfflineAccessor.invokeForPlayer(playerUUID);
            }
        };
    }

    List<IKnowledgeProvider> getProviders() {
        var out = new ArrayList<IKnowledgeProvider>(providers.size());
        for (var s : providers.values()) {
            out.add(s.get());
        }
        return out;
    }

    public Supplier<IKnowledgeProvider> getProviderFor(UUID uuid) {
        var s = providers.get(uuid);
        return s != null ? s : tpeHandler.getProviderFor(uuid);
    }

    Supplier<IKnowledgeProvider> getProviderFor(Player player) {
        return getProviderFor(player.getUUID());
    }

    Supplier<IKnowledgeProvider> getProviderFor(IActionHost host) {
        var node = host.getActionableNode();

        if (node != null) {
            var uuid = node.getOwningPlayerProfileId();
            return uuid != null ? getProviderFor(uuid) : null;
        }

        return null;
    }

    public EMCStorage getStorage() {
        return storage;
    }

    /**
     * Return a cached EMC value for the given AEItemKey if available, otherwise null.
     */
    public Long getCachedEmc(AEItemKey key) {
        if (emcCache.containsKey(key)) {
            return emcCache.getLong(key);
        }

        return null;
    }

    /**
     * Return a cached EMC value as a primitive OptionalLong to avoid boxing in hot paths.
     */
    public java.util.OptionalLong getCachedEmcPrimitive(AEItemKey key) {
        if (emcCache.containsKey(key)) {
            return java.util.OptionalLong.of(emcCache.getLong(key));
        }

        return java.util.OptionalLong.empty();
    }

    public MEStorage getStorage(IManagedGridNode node) {
        return !moduleNodes.isEmpty() && node.equals(moduleNodes.get(0)) && node.isActive()
                ? storage
                : NullInventory.of();
    }

    public Set<AEItemKey> getKnownItems() {
        if (knownItemCache == null) {
            knownItemCache = new HashSet<>();

            for (var provider : getProviders()) {
                for (var item : provider.getKnowledge()) {
                    if (!IEMCProxy.INSTANCE.hasValue(item)) {
                        continue;
                    }

                    var stack = item.createStack();
                    var key = AEItemKey.of(stack);

                    if (key != null) {
                        knownItemCache.add(key);
                        String keyStr;
                        synchronized (keyStringCache) {
                            if (keyStringCache.containsKey(key)) {
                                KEY_STRING_CACHE_HITS.increment();
                                // move to MRU
                                var v = keyStringCache.get(key);
                                keyStringCache.remove(key);
                                keyStringCache.put(key, v);
                                keyStr = v;
                            } else {
                                KEY_STRING_CACHE_MISSES.increment();
                                keyStr = key.toString();
                                keyStringCache.put(key, keyStr);
                                if (keyStringCache.size() > AppliedEConfig.CONFIG.getKeyCacheMax()) {
                                    var it = keyStringCache.keySet().iterator();
                                    if (it.hasNext()) {
                                        var eldest = it.next();
                                        keyStringCache.remove(eldest);
                                    }
                                }
                            }
                        }
                        // If we have a persisted value for this key, reuse it to avoid an expensive ProjectE lookup
                        if (persistedEmc.containsKey(keyStr)) {
                            emcCache.put(key, persistedEmc.getLong(keyStr));
                        } else {
                            try {
                                var val = IEMCProxy.INSTANCE.getValue(stack);
                                emcCache.put(key, val);
                                 // persist string form for reloads and schedule async/coalesced save
                                 persistedEmc.put(keyStr, val);
                                 scheduleSaveEmcCacheToDisk();
                            } catch (Throwable ignored) {
                                // if ProjectE lookup fails, skip caching for this key
                            }
                        }
                    }
                }
            }
        }

        return knownItemCache;
    }

    private void loadEmcCacheFromDisk() {
        try {
            var parent = emcCacheFile.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            if (!Files.exists(emcCacheFile)) {
                return;
            }

            try (BufferedReader r = Files.newBufferedReader(emcCacheFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    var idx = line.indexOf('\t');
                    if (idx <= 0) continue;
                    var keyStr = line.substring(0, idx);
                    var valS = line.substring(idx + 1);
                    try {
                        var val = Long.parseLong(valS);
                        persistedEmc.put(keyStr, val);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            // mark that we should attempt a startup seed of warm keys for items that we have persisted values for
            if (!persistedEmc.isEmpty()) {
                startupSeedQueued = true;
            }
        } catch (IOException ignored) {
        }
    }

    private void saveEmcCacheToDisk() throws IOException {
        var parent = emcCacheFile.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        // snapshot persistedEmc to avoid concurrent modification during write
        java.util.Map<String, Long> snapshot = new java.util.HashMap<>();
        synchronized (persistedEmc) {
            for (var e : persistedEmc.object2LongEntrySet()) {
                snapshot.put(e.getKey(), e.getLongValue());
            }
        }

        try (BufferedWriter w = Files.newBufferedWriter(emcCacheFile, StandardCharsets.UTF_8)) {
            for (var e : snapshot.entrySet()) {
                w.write(e.getKey());
                w.write('\t');
                w.write(Long.toString(e.getValue()));
                w.newLine();
            }
        }
    }

    /**
     * Schedule an async/coalesced save of the persisted EMC cache to disk. Multiple calls within the
     * debounce window are coalesced into a single write.
     */
    private void scheduleSaveEmcCacheToDisk() {
        if (!saveScheduled.compareAndSet(false, true)) return;

        SHARED_SCHEDULER.schedule(() -> {
            try {
                try {
                    saveEmcCacheToDisk();
                } catch (IOException ignored) {
                }
            } finally {
                saveScheduled.set(false);
            }
        }, AppliedEConfig.CONFIG.getSaveDebounceMillis(), TimeUnit.MILLISECONDS);
    }

    // Instrumentation getters to aid debugging and tuning
    public int getPersistedEmcSize() {
        return persistedEmc.size();
    }

    public int getEmcCacheSize() {
        return emcCache.size();
    }

    public int getWarmQueueSize() {
        return warmQueue.size();
    }

    public int getFinalWarmQueueSize() {
        return finalWarmQueue.size();
    }

    public int getPatternCacheSize() {
        return patternCache.size();
    }

    public int getKeyStringCacheSize() {
        return keyStringCache.size();
    }

    public long getKeyStringCacheHits() {
        return KEY_STRING_CACHE_HITS.sum();
    }

    public long getKeyStringCacheMisses() {
        return KEY_STRING_CACHE_MISSES.sum();
    }

    public List<IPatternDetails> getPatterns(IManagedGridNode node) {
        if (!moduleNodes.isEmpty() && node.equals(moduleNodes.get(0)) && node.isActive()) {

            // ensure tier patterns are cached and up-to-date
            var highest = storage.getHighestTier();
            if (highest != cachedHighestTier) {
                tierPatterns.clear();
                for (var tier = highest; tier > 1; tier--) {
                    tierPatterns.add(new TransmutationPattern(tier));
                }
                cachedHighestTier = highest;
            }

            var patterns = new ArrayList<IPatternDetails>(tierPatterns);

            // reuse TransmutationPattern objects for known items where possible
            var known = getKnownItems();
            // reserve adequate capacity to avoid resizing
            patterns.ensureCapacity(patterns.size() + known.size() + temporaryPatterns.size());

            for (var item : known) {
                Long cachedValue = emcCache.containsKey(item) ? emcCache.getLong(item) : null;
                var pattern = patternCache.get(item);

                if (pattern == null) {
                    pattern = new TransmutationPattern(item, 1, cachedValue);
                    patternCache.put(item, pattern);
                } else {
                    // if cached EMC changed since pattern creation, replace the cached pattern with a new one
                    // (TransmutationPattern is immutable so we must recreate it when EMC changes)
                    Long prev = patternCache.get(item) != null
                            ? (emcCache.containsKey(item) ? emcCache.getLong(item) : null)
                            : null;
                    // we can't cheaply compare prior cached value stored inside pattern (no accessor), so recreate if
                    // null vs non-null mismatch
                    if ((prev == null && cachedValue != null) || (prev != null && !prev.equals(cachedValue))) {
                        pattern = new TransmutationPattern(item, 1, cachedValue);
                        patternCache.put(item, pattern);
                    }
                }

                patterns.add(pattern);
            }

            patterns.addAll(temporaryPatterns);
            return patterns;
        }

        return Collections.emptyList();
    }

    public void addTemporaryPattern(IPatternDetails pattern) {
        temporaryPatterns.add(pattern);
        // temporary patterns must be visible immediately for crafting
        forceUpdatePatterns();
    }

    public void removeTemporaryPattern(IPatternDetails pattern) {
        temporaryPatterns.remove(pattern);
        forceUpdatePatterns();
    }

    void updatePatterns() {
        // Throttle per-node pattern update requests to avoid thundering-herd
        int minInterval = AppliedEConfig.CONFIG.getPatternMinUpdateInterval();
        if (minInterval <= 0) {
            // disabled: call immediately
            moduleNodes.forEach(ICraftingProvider::requestUpdate);
            return;
        }

        for (var node : moduleNodes) {
            try {
                // compute a lightweight fingerprint of the current patterns state
                int fp = computePatternsFingerprint(node);
                var lastFp = lastPublishedFingerprint.get(node);

                if (lastFp != null && lastFp == fp) {
                    // no visible change for this node: skip update
                    continue;
                }

                var last = lastPatternUpdateTick.getOrDefault(node, Integer.MIN_VALUE);
                if (serverTickCounter - last >= minInterval) {
                    ICraftingProvider.requestUpdate(node);
                    lastPatternUpdateTick.put(node, serverTickCounter);
                    lastPublishedFingerprint.put(node, fp);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Compute a deterministic, lightweight fingerprint for the current pattern state that is
     * cheap enough to compute frequently and sufficient to detect most visible changes.
     */
    private int computePatternsFingerprint(IManagedGridNode node) {
        int h = 1;
        // include tier & temporary patterns sizes
        h = 31 * h + tierPatterns.size();
        h = 31 * h + temporaryPatterns.size();

        // include knowledge set and cached EMC (cheap: uses emcCache and knownItemCache)
        var known = getKnownItems();
        for (var item : known) {
            h = 31 * h + item.hashCode();
            if (emcCache.containsKey(item)) {
                h = 31 * h + Long.hashCode(emcCache.getLong(item));
            } else {
                h = 31 * h;
            }
        }

        return h;
    }

    /** Force an immediate requestUpdate() for all nodes (bypasses throttle). */
    void forceUpdatePatterns() {
        moduleNodes.forEach(ICraftingProvider::requestUpdate);
    }

    IGrid getGrid() {
        return grid;
    }

    BigInteger getEmc() {
        var emc = BigInteger.ZERO;

        for (var entry : providers.entrySet()) {
            if (tpeHandler.notSharingEmc(entry)) {
                emc = emc.add(entry.getValue().get().getEmc());
            }
        }

        return emc;
    }

    public boolean isTrackingPlayer(Player player) {
        var uuid = player.getUUID();
        return providers.containsKey(uuid) || tpeHandler.isPlayerInTrackedTeam(uuid);
    }

    void syncEmc() {
        needsSync = true;
    }
}
