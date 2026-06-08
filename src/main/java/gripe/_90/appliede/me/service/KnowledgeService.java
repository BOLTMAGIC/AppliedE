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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

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

public class KnowledgeService implements IGridService, IGridServiceProvider {
    private static final int TICKS_PER_SYNC = AppliedEConfig.CONFIG.getSyncThrottleInterval();

    private final List<IManagedGridNode> moduleNodes = new ArrayList<>();
    private final Map<UUID, Supplier<IKnowledgeProvider>> providers = new HashMap<>();
    private final EMCStorage storage = new EMCStorage(this);
    private final List<IPatternDetails> temporaryPatterns = new ArrayList<>();
    private final TeamProjectEHandler.Proxy tpeHandler = new TeamProjectEHandler.Proxy();

    private final IGrid grid;
    private Set<AEItemKey> knownItemCache;
    /** Cache of EMC values for known AEItemKey instances to avoid repeated ItemStack creation and ProjectE lookups. */
    private final Object2LongOpenHashMap<AEItemKey> emcCache = new Object2LongOpenHashMap<>();
    // persisted simple string -> long map (key string -> emc) loaded from disk
    private final Map<String, Long> persistedEmc = new HashMap<>();
    private final Path emcCacheFile;
    // cache of created TransmutationPattern objects for known items to avoid allocations on each getPatterns()
    private final Map<AEItemKey, TransmutationPattern> patternCache = new HashMap<>();
    private final List<TransmutationPattern> tierPatterns = new ArrayList<>();
    private int cachedHighestTier = 1;

    private boolean needsSync;
    private int ticksSinceLastSync;

    public KnowledgeService(IGrid grid) {
        this.grid = grid;
        emcCacheFile = Paths.get("run", "config", "appliede", "emc_cache.tsv");
        loadEmcCacheFromDisk();

        MinecraftForge.EVENT_BUS.addListener((PlayerKnowledgeChangeEvent event) -> {
            knownItemCache = null;
            emcCache.clear();
            // clear pattern cache since known items changed
            patternCache.clear();
            // clear persisted map as knowledge changed; will be rebuilt on next known items scan
            persistedEmc.clear();
            updatePatterns();
        });
        MinecraftForge.EVENT_BUS.addListener((OnDatapackSyncEvent event) -> {
            if (event.getPlayer() == null) {
                knownItemCache = null;
                emcCache.clear();
                patternCache.clear();
                persistedEmc.clear();
                updatePatterns();
            }
        });
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

            updatePatterns();
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
            updatePatterns();
        }
    }

    @Override
    public void onServerStartTick() {
        if (ticksSinceLastSync < TICKS_PER_SYNC) {
            ticksSinceLastSync++;
        }

        if (needsSync && ticksSinceLastSync == TICKS_PER_SYNC) {
            tpeHandler.syncTeamProviders(providers);
            needsSync = false;
            ticksSinceLastSync = 0;
        }
    }

    private void addProvider(UUID playerUUID) {
        providers.putIfAbsent(playerUUID, retrieveProvider(playerUUID));
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
        return providers.values().stream().map(Supplier::get).toList();
    }

    public Supplier<IKnowledgeProvider> getProviderFor(UUID uuid) {
        return providers.getOrDefault(uuid, tpeHandler.getProviderFor(uuid));
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
                        var keyStr = key.toString();
                        // If we have a persisted value for this key, reuse it to avoid an expensive ProjectE lookup
                        if (persistedEmc.containsKey(keyStr)) {
                            emcCache.put(key, persistedEmc.get(keyStr));
                        } else {
                            try {
                                var val = IEMCProxy.INSTANCE.getValue(stack);
                                emcCache.put(key, val);
                                // persist string form for reloads
                                persistedEmc.put(keyStr, val);
                                // best-effort save (do not fail startup if IO fails)
                                try {
                                    saveEmcCacheToDisk();
                                } catch (IOException ignored) {
                                }
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
        } catch (IOException ignored) {
        }
    }

    private void saveEmcCacheToDisk() throws IOException {
        var parent = emcCacheFile.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        try (BufferedWriter w = Files.newBufferedWriter(emcCacheFile, StandardCharsets.UTF_8)) {
            for (var e : persistedEmc.entrySet()) {
                w.write(e.getKey());
                w.write('\t');
                w.write(Long.toString(e.getValue()));
                w.newLine();
            }
        }
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
        updatePatterns();
    }

    public void removeTemporaryPattern(IPatternDetails pattern) {
        temporaryPatterns.remove(pattern);
        updatePatterns();
    }

    void updatePatterns() {
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
