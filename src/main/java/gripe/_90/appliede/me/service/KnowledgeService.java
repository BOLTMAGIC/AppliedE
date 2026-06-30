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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

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
    private final Object2ObjectOpenHashMap<UUID, Supplier<IKnowledgeProvider>> providers =
            new Object2ObjectOpenHashMap<>();
    private final EMCStorage storage = new EMCStorage(this);
    // temporary patterns are associated with a timestamp so we can garbage-collect
    // stale patterns that for some reason were not removed by AE2 lifecycle hooks.
    private final java.util.concurrent.ConcurrentHashMap<IPatternDetails, Long> temporaryPatterns =
            new java.util.concurrent.ConcurrentHashMap<>();
    // TTL for temporary patterns in milliseconds; patterns older than this are pruned.
    private static final long TEMPORARY_PATTERN_TTL_MS = 60_000L;
    private final TeamProjectEHandler.Proxy tpeHandler = new TeamProjectEHandler.Proxy();

    private final IGrid grid;
    private Set<AEItemKey> knownItemCache;
    /** Cache of EMC values for known AEItemKey instances to avoid repeated ItemStack creation and ProjectE lookups. */
    // Use BigInteger here to match ProjectE / provider EMC semantics and avoid overflow when EMC exceeds
    // Long.MAX_VALUE.
    // SHARED (static) across ALL KnowledgeService instances: EMC values are global (a single item -> EMC-value table
    // loaded from one file, not grid- or player-specific). AE2 rebuilds grids constantly and creates one service per
    // grid, so a per-instance copy of this ~20k-entry table multiplied across 1000+ leaked instances caused the OOM.
    // ConcurrentHashMap because reads/writes happen on BOTH the server thread and the appliede-shared-scheduler thread
    // (Object2ObjectOpenHashMap is not thread-safe and must not be shared unguarded).
    private static final java.util.concurrent.ConcurrentHashMap<AEItemKey, java.math.BigInteger> emcCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    // persisted simple string -> BigInteger map (key string -> emc) loaded from disk; also SHARED + concurrent.
    private static final java.util.concurrent.ConcurrentHashMap<String, java.math.BigInteger> persistedEmc =
            new java.util.concurrent.ConcurrentHashMap<>();
    // Single shared on-disk location for the global EMC table.
    private static final Path emcCacheFile = Paths.get("config", "AppliedeE", "emc_cache.tsv");
    // Guards the one-time load of the shared EMC cache from disk (loaded exactly once for all instances).
    private static final java.util.concurrent.atomic.AtomicBoolean emcCacheLoaded =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    // LRU cache for AEItemKey -> String to avoid repeated toString() allocations in hot loops
    // Converted to fastutil linked map with manual synchronization to avoid boxed iteration overhead
    private final it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap<AEItemKey, String> keyStringCache =
            new it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap<>();
    private final LongAdder KEY_STRING_CACHE_HITS = new LongAdder();
    private final LongAdder KEY_STRING_CACHE_MISSES = new LongAdder();
    // Warm queue for serialized AEKey caching. Background thread dedupes and main thread finalizes.
    private final ConcurrentLinkedQueue<appeng.api.stacks.AEKey> warmQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<appeng.api.stacks.AEKey> finalWarmQueue = new ConcurrentLinkedQueue<>();
    // cache of created TransmutationPattern objects for known items to avoid allocations on each getPatterns()
    private final Object2ObjectOpenHashMap<AEItemKey, TransmutationPattern> patternCache =
            new Object2ObjectOpenHashMap<>();
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
    // Registry of active KnowledgeService instances held weakly so the static aggregator
    // doesn't prevent GC of services when grids are unloaded. We use a ReferenceQueue to
    // detect cleared references and an AtomicInteger to track live instance count. This
    // allows quicker cleanup and reliable instrumentation instead of scanning all refs.
    private static final java.lang.ref.ReferenceQueue<KnowledgeService> REF_QUEUE =
            new java.lang.ref.ReferenceQueue<>();

    private static final java.util.List<java.lang.ref.WeakReference<KnowledgeService>> INSTANCES =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private static final java.util.concurrent.atomic.AtomicInteger LIVE_INSTANCES =
            new java.util.concurrent.atomic.AtomicInteger(0);

    // Warn if instances exceed this threshold; helps detect runaway grid creation early.
    private static final int WARN_INSTANCE_THRESHOLD = 500;
    // recent creation stack snippets for diagnostic dumps when we detect runaway creation
    private static final java.util.concurrent.ConcurrentLinkedDeque<String> CREATION_TRACES =
            new java.util.concurrent.ConcurrentLinkedDeque<>();
    private static final int CREATION_TRACE_MAX = 128;

    private static void cleanupClearedReferences() {
        java.lang.ref.Reference<? extends KnowledgeService> cleared;
        int removed = 0;
        while ((cleared = REF_QUEUE.poll()) != null) {
            try {
                //noinspection SuspiciousMethodCalls
                INSTANCES.remove(cleared);
                int live = LIVE_INSTANCES.decrementAndGet();
                removed++;
            } catch (Throwable ignored) {
            }
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean AGGREGATOR_STARTED =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    // Static: register the Forge event listeners exactly ONCE for the whole class, not once per instance. A
    // per-instance listener captures `this`, so the global EVENT_BUS would strongly hold every KnowledgeService
    // and prevent GC of services whose grids were unloaded (the root instance leak). The static handlers fan out
    // over the weak-ref INSTANCES registry instead, so leaked services stay GC-able and event cost is O(live).
    private static final java.util.concurrent.atomic.AtomicBoolean EVENT_LISTENERS_REGISTERED =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    // Static: a single shared writer/debounce flag so the global EMC table is saved once, not once per instance.
    private static final java.util.concurrent.atomic.AtomicBoolean saveScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    // debounce interval is now configurable via AppliedEConfig

    // One-time startup seeding flags: when we load a persisted EMC TSV we mark that we should
    // attempt to enqueue warm requests for matching known items on the main thread in small batches.
    private boolean startupSeedQueued = false;
    private boolean startupSeeded = false;
    // When cleanup removes patterns off-thread we signal the main server tick to force
    // a pattern update on the main thread (ICraftingProvider.requestUpdate must run there).
    private volatile boolean needsPatternRefresh = false;
    // simple server tick counter maintained from onServerStartTick()
    private int serverTickCounter = 0;
    // track last pattern update tick per node to avoid frequent repeated requestUpdate() calls
    private final java.util.WeakHashMap<IManagedGridNode, Integer> lastPatternUpdateTick =
            new java.util.WeakHashMap<>();
    // fingerprint of published patterns per node to suppress unchanged updates
    private final java.util.WeakHashMap<IManagedGridNode, Integer> lastPublishedFingerprint =
            new java.util.WeakHashMap<>();

    public KnowledgeService(IGrid grid) {
        this.grid = grid;
        // Persisted EMC cache lives under config/AppliedeE/ (see the static emcCacheFile). Load the shared
        // global EMC table from disk exactly once across all instances (the load is internally guarded).
        loadEmcCacheFromDisk();
        // Preserve the per-instance startup warm seed: if the shared persisted table has values, this grid
        // should attempt to warm matching known items (previously every instance loaded the file and seeded).
        if (!persistedEmc.isEmpty()) {
            startupSeedQueued = true;
        }
        // Register this instance for the shared aggregator and start the aggregator once.
        // cleanup any cleared references first
        cleanupClearedReferences();

        // track this instance with a weak reference registered to the ref queue
        var instanceRef = new java.lang.ref.WeakReference<>(this, REF_QUEUE);
        INSTANCES.add(instanceRef);

        int liveCount = LIVE_INSTANCES.incrementAndGet();

        // capture a short creation stack snippet for diagnostics (include thread name + top N frames)
        try {
            var st = new Exception().getStackTrace();
            var sb = new StringBuilder();
            // include thread name to help identify where services are created
            sb.append("thread=").append(Thread.currentThread().getName()).append('\n');
            int taken = 0;
            for (var e : st) {
                var cls = e.getClassName();
                // skip frames originating from this class to show the external caller
                if (cls.startsWith("gripe._90.appliede.me.service.KnowledgeService")) continue;
                sb.append(e).append('\n');
                if (++taken >= 8) break; // capture top 8 external frames
            }
            var s = sb.toString();
            CREATION_TRACES.addFirst(s);
            while (CREATION_TRACES.size() > CREATION_TRACE_MAX) CREATION_TRACES.removeLast();
            // also emit a debug-level creation snippet so it's visible in most dev logs
            // logging removed: creation trace suppressed
        } catch (Throwable ignored) {
        }

        // logging removed for creation and thresholds

        if (AGGREGATOR_STARTED.compareAndSet(false, true)) {
            // single shared aggregator that iterates weak refs to instances and moves
            // warmQueue entries into the corresponding finalWarmQueue. This avoids
            // scheduling one repeating task per KnowledgeService instance.
            SHARED_SCHEDULER.scheduleWithFixedDelay(
                    () -> {
                        try {
                            cleanupClearedReferences();
                            for (var ref : INSTANCES) {
                                var ks = ref.get();
                                if (ks == null) {
                                    INSTANCES.remove(ref);
                                    int liveNow = LIVE_INSTANCES.decrementAndGet();
                                    continue;
                                }

                                if (ks.warmQueue.isEmpty()) continue;
                                var dedup = new java.util.LinkedHashSet<appeng.api.stacks.AEKey>();
                                appeng.api.stacks.AEKey k;
                                int added = 0;
                                while ((k = ks.warmQueue.poll()) != null) {
                                    dedup.add(k);
                                    added++;
                                    if (dedup.size() >= 1024 || added >= 2048) break; // safety bounds
                                }

                                for (var key : dedup) {
                                    ks.finalWarmQueue.offer(key);
                                }
                            }
                        } catch (Throwable t) {
                            // swallow to avoid scheduler termination
                        }
                    },
                    100,
                    100,
                    TimeUnit.MILLISECONDS);

            // background cleanup: remove temporary patterns older than TTL to ensure
            // completed crafts don't leave stale patterns behind. Cleanup runs off-thread
            // and only marks a flag to request a main-thread update.
            SHARED_SCHEDULER.scheduleWithFixedDelay(
                    () -> {
                        try {
                            final long now = System.currentTimeMillis();
                            boolean removedAny = false;

                            for (var ref : INSTANCES) {
                                var ks = ref.get();
                                if (ks == null) continue;

                                for (var e : ks.temporaryPatterns.entrySet()) {
                                    if (now - e.getValue() > TEMPORARY_PATTERN_TTL_MS) {
                                        if (ks.temporaryPatterns.remove(e.getKey(), e.getValue())) {
                                            removedAny = true;
                                        }
                                    }
                                }
                            }

                            if (removedAny) {
                                // request main-thread refresh next server tick
                                for (var ref : INSTANCES) {
                                    var ks = ref.get();
                                    if (ks != null) ks.needsPatternRefresh = true;
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    },
                    5,
                    5,
                    TimeUnit.SECONDS);
        }

        // Register the Forge event listeners exactly ONCE (statically) on the first instance. Using static
        // handlers that fan out over the INSTANCES weak-ref registry avoids capturing `this` on the global
        // EVENT_BUS (the root KnowledgeService leak) and turns per-event cost from O(N listeners) into
        // O(live instances) on these rare events.
        if (EVENT_LISTENERS_REGISTERED.compareAndSet(false, true)) {
            MinecraftForge.EVENT_BUS.addListener((PlayerKnowledgeChangeEvent event) -> onPlayerKnowledgeChange());
            MinecraftForge.EVENT_BUS.addListener((OnDatapackSyncEvent event) -> {
                if (event.getPlayer() == null) {
                    onDatapackReload();
                }
            });
        }
        // Note: io tasks also run on the shared scheduler to avoid per-instance threads.
        // (No per-instance executor allocation.)
    }

    /**
     * A player's transmutation knowledge changed: the SET of known items may differ, but the EMC VALUES do not.
     * Invalidate each live service's per-instance known-item/pattern caches and refresh patterns. The shared
     * global EMC value cache is intentionally NOT cleared here (it only changes on a datapack/config reload).
     * Fires on the server thread, the same thread that reads these per-instance caches.
     */
    private static void onPlayerKnowledgeChange() {
        cleanupClearedReferences();
        for (var ref : INSTANCES) {
            var ks = ref.get();
            if (ks == null) continue;
            ks.knownItemCache = null;
            // clear pattern cache since known items changed
            ks.patternCache.clear();
            // Knowledge changed: force immediate pattern update so AE2 sees new transmutations
            ks.forceUpdatePatterns();
        }
    }

    /**
     * Datapack/recipe reload: EMC values themselves may have changed, so clear the shared global EMC cache ONCE,
     * then have every live service rebuild its per-instance known-item/pattern caches. Server-thread only.
     */
    private static void onDatapackReload() {
        // EMC values can change on a datapack/config reload — invalidate the shared global cache a single time.
        emcCache.clear();
        persistedEmc.clear();
        cleanupClearedReferences();
        for (var ref : INSTANCES) {
            var ks = ref.get();
            if (ks == null) continue;
            ks.knownItemCache = null;
            ks.patternCache.clear();
            // Datapack sync affects knowledge; ensure immediate refresh
            ks.forceUpdatePatterns();
        }
    }

    @Override
    public void addNode(IGridNode gridNode, @Nullable CompoundTag savedData) {
        if (gridNode.getOwner() instanceof EMCModulePart module) {
            // Adding a module changes the SET of known items (knownItemCache), not EMC values, so the
            // shared EMC cache is left intact — grids churn nodes constantly and clearing it would thrash.
            knownItemCache = null;
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
            // Removing a module changes the SET of known items, not EMC values — leave the shared EMC cache intact.
            knownItemCache = null;
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

        // If background cleanup or other off-thread actions requested a pattern refresh,
        // perform the safe main-thread request here.
        if (needsPatternRefresh) {
            needsPatternRefresh = false;
            forceUpdatePatterns();
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
    public java.math.BigInteger getCachedEmc(AEItemKey key) {
        if (emcCache.containsKey(key)) {
            return emcCache.get(key);
        }

        return null;
    }

    /** Return a cached EMC value as Optional<BigInteger> to avoid lossy conversion/overflow. */
    public java.util.Optional<java.math.BigInteger> getCachedEmcOptional(AEItemKey key) {
        if (emcCache.containsKey(key)) {
            return java.util.Optional.of(emcCache.get(key));
        }

        return java.util.Optional.empty();
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
                            emcCache.put(key, persistedEmc.get(keyStr));
                        } else {
                            try {
                                var val = IEMCProxy.INSTANCE.getValue(stack);
                                var big = java.math.BigInteger.valueOf(val);
                                emcCache.put(key, big);
                                // persist string form for reloads and schedule async/coalesced save
                                persistedEmc.put(keyStr, big);
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

    private static void loadEmcCacheFromDisk() {
        // Load the shared global EMC table from disk exactly once, regardless of how many grids/instances exist.
        if (!emcCacheLoaded.compareAndSet(false, true)) {
            return;
        }
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
                        // persisted values are stored as decimal strings for arbitrary precision
                        var val = new java.math.BigInteger(valS);
                        persistedEmc.put(keyStr, val);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void saveEmcCacheToDisk() throws IOException {
        var parent = emcCacheFile.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        // Concurrent snapshot of the shared persistedEmc to avoid mutation during the write. ConcurrentHashMap's
        // iterator is weakly consistent, so copying it into a plain HashMap never throws ConcurrentModification.
        java.util.Map<String, java.math.BigInteger> snapshot = new java.util.HashMap<>(persistedEmc);

        try (BufferedWriter w = Files.newBufferedWriter(emcCacheFile, StandardCharsets.UTF_8)) {
            for (var e : snapshot.entrySet()) {
                w.write(e.getKey());
                w.write('\t');
                w.write(e.getValue().toString());
                w.newLine();
            }
        }
    }

    /**
     * Schedule an async/coalesced save of the persisted EMC cache to disk. Multiple calls within the
     * debounce window are coalesced into a single write.
     */
    private static void scheduleSaveEmcCacheToDisk() {
        if (!saveScheduled.compareAndSet(false, true)) return;

        SHARED_SCHEDULER.schedule(
                () -> {
                    try {
                        try {
                            saveEmcCacheToDisk();
                        } catch (IOException ignored) {
                        }
                    } finally {
                        saveScheduled.set(false);
                    }
                },
                AppliedEConfig.CONFIG.getSaveDebounceMillis(),
                TimeUnit.MILLISECONDS);
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
                java.math.BigInteger cachedValue = emcCache.getOrDefault(item, null);
                // recreate pattern based on current cached EMC (avoids needing an accessor on TransmutationPattern)
                var pattern = new TransmutationPattern(item, 1, cachedValue);
                patternCache.put(item, pattern);
                patterns.add(pattern);
            }

            patterns.addAll(temporaryPatterns.keySet());
            return patterns;
        }

        return Collections.emptyList();
    }

    public void addTemporaryPattern(IPatternDetails pattern) {
        // Only add and trigger a refresh when this pattern did not already exist.
        // TransmutationPattern implements equals/hashCode based on definition, so
        // different instances representing the same logical pattern will be
        // deduplicated by the map. Using putIfAbsent avoids repeatedly updating
        // the timestamp and repeatedly requesting updates for the same pattern
        // when crafting simulations create many equivalent pattern instances.
        var now = System.currentTimeMillis();
        var prev = temporaryPatterns.putIfAbsent(pattern, now);
        if (prev == null) {
            // newly added pattern: schedule a main-thread refresh
            needsPatternRefresh = true;
        }
    }

    public void removeTemporaryPattern(IPatternDetails pattern) {
        var removed = temporaryPatterns.remove(pattern) != null;
        if (removed) {
            needsPatternRefresh = true;
        }
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
                h = 31 * h + emcCache.get(item).hashCode();
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
