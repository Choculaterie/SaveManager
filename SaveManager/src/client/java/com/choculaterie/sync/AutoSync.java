package com.choculaterie.sync;

import com.choculaterie.SaveManagerMod;
import com.choculaterie.network.NetworkManager;
import com.choculaterie.util.AccountState;
import com.choculaterie.util.ConfigManager;
import com.choculaterie.vanilib.util.WatchManager;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public final class AutoSync {

    private static final long DEBOUNCE_SECONDS = 5;
    private static final long RETRY_MINUTES = 2;
    private static final Set<String> IGNORED = Set.of("session.lock");

    private static final Semaphore TRANSFER = new Semaphore(1, true);
    private static final Map<String, Boolean> DIRTY = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> QUEUED = new ConcurrentHashMap<>();
    private static ScheduledExecutorService scheduler;
    private static volatile NetworkManager network;

    private AutoSync() {
    }

    public static void start(NetworkManager networkManager) {
        network = networkManager;
        SyncState.load();
        if (scheduler != null)
            return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SaveManager-autosync");
            t.setDaemon(true);
            return t;
        });
        SaveManagerMod.LOGGER.info(
                "[SM] auto sync started: uploads {}s after each world save, retry sweep every {} min",
                DEBOUNCE_SECONDS, RETRY_MINUTES);
        scheduler.scheduleWithFixedDelay(AutoSync::tick, RETRY_MINUTES, RETRY_MINUTES, TimeUnit.MINUTES);

        if (ConfigManager.loadApiKey() != null) {
            networkManager.getQuotaInfo().whenComplete((json, err) -> {
                if (err == null) {
                    AccountState.update(json);
                    SaveManagerMod.LOGGER.info("[SM] account: premium={} autoSync={} versionsKept={} quota={}",
                            AccountState.isPremium(), AccountState.hasAutoSync(),
                            AccountState.versionsKept(), AccountState.quotaFormatted());
                } else {
                    SaveManagerMod.LOGGER.warn("[SM] account: quota-info failed - {}", err.toString());
                }
            });
        }
    }

    public static void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    public static boolean acquireForManual() {
        TRANSFER.acquireUninterruptibly();
        return true;
    }

    public static void releaseManual() {
        TRANSFER.release();
    }

    public static boolean isEnabled() {
        return AccountState.hasAutoSync() && ConfigManager.loadApiKey() != null;
    }

    public static void onWorldSaved(Path rawWorldDir) {
        Path worldDir = normalize(rawWorldDir);
        String folder = worldDir.getFileName().toString();
        if (!isEnabled()) {
            logSkip(folder, "autosave", AccountState.hasAutoSync() ? "no account key" : "account is not premium");
            return;
        }
        if (!WatchManager.isWatching(folder)) {
            logSkip(folder, "autosave", "world is not favourited");
            return;
        }

        SyncState.WorldEntry entry = SyncState.get(folder);
        if (!entry.stagingPopulated) {
            logSkip(folder, "autosave", "staging not populated yet, waiting for world exit");
            return;
        }

        try {
            long started = System.nanoTime();
            int copied = refreshStaging(worldDir, folder, entry);
            long ms = (System.nanoTime() - started) / 1_000_000;
            if (copied > 0) {
                DIRTY.put(folder, Boolean.TRUE);
                SyncState.save();
                SaveManagerMod.LOGGER.info("[SM] autosave: staged {} changed file(s) for '{}' in {}ms, syncing in {}s",
                        copied, folder, ms, DEBOUNCE_SECONDS);
                scheduleSync(folder);
            } else {
                SaveManagerMod.LOGGER.info("[SM] autosave: '{}' unchanged since last staging ({}ms)", folder, ms);
            }
        } catch (Exception e) {
            SaveManagerMod.LOGGER.warn("AutoSync: staging '{}' failed - {}", folder, e.toString());
        }
    }

    public static void onWorldClosed(Path rawWorldDir) {
        Path worldDir = normalize(rawWorldDir);
        String folder = worldDir.getFileName().toString();
        if (!isEnabled()) {
            logSkip(folder, "world-exit", AccountState.hasAutoSync() ? "no account key" : "account is not premium");
            return;
        }
        if (!WatchManager.isWatching(folder)) {
            logSkip(folder, "world-exit", "world is not favourited");
            return;
        }
        SaveManagerMod.LOGGER.info("[SM] world-exit: '{}' closing, populating staging then syncing", folder);

        Thread t = new Thread(() -> {
            try {
                SyncState.WorldEntry entry = SyncState.get(folder);
                long t0 = System.nanoTime();
                int copied = refreshStaging(worldDir, folder, entry);
                SaveManagerMod.LOGGER.info("[SM] world-exit: staged {} file(s) for '{}' in {}ms",
                        copied, folder, (System.nanoTime() - t0) / 1_000_000);
                entry.stagingPopulated = true;
                SyncState.save();
                DIRTY.put(folder, Boolean.TRUE);
                SyncState.clearBackoff(folder);
                QUEUED.remove(folder);
                syncOne(folder);
            } catch (Exception e) {
                SaveManagerMod.LOGGER.warn("AutoSync: exit sync for '{}' failed - {}", folder, e.toString());
            }
        }, "SaveManager-autosync-exit");
        t.setDaemon(true);
        t.start();
    }

    private static void scheduleSync(String folder) {
        ScheduledExecutorService s = scheduler;
        if (s == null)
            return;
        if (QUEUED.putIfAbsent(folder, Boolean.TRUE) != null)
            return;
        s.schedule(() -> {
            QUEUED.remove(folder);
            syncOne(folder);
        }, DEBOUNCE_SECONDS, TimeUnit.SECONDS);
    }

    private static Path normalize(Path worldDir) {
        return worldDir.toAbsolutePath().normalize();
    }

    private static void logSkip(String folder, String phase, String why) {
        SaveManagerMod.LOGGER.info("[SM] {}: skipping '{}' - {}", phase, folder, why);
    }

    private static void tick() {
        try {
            if (!isEnabled())
                return;
            pruneUnwatchedStaging();
            Map<String, Boolean> snapshot = new HashMap<>(DIRTY);
            if (snapshot.isEmpty())
                return;
            SaveManagerMod.LOGGER.info("[SM] retry sweep: {} world(s) still pending {}",
                    snapshot.size(), snapshot.keySet());
            for (Map.Entry<String, Boolean> e : snapshot.entrySet()) {
                if (Boolean.TRUE.equals(e.getValue()))
                    syncOne(e.getKey());
            }
        } catch (Throwable t) {
            SaveManagerMod.LOGGER.warn("AutoSync: tick failed - {}", t.toString());
        }
    }

    private static void pruneUnwatchedStaging() {
        Path root = stagingRoot();
        if (!Files.isDirectory(root))
            return;
        try (Stream<Path> dirs = Files.list(root)) {
            List<Path> stale = dirs.filter(Files::isDirectory)
                    .filter(d -> !WatchManager.isWatching(d.getFileName().toString()))
                    .toList();
            for (Path d : stale) {
                String folder = d.getFileName().toString();
                SaveManagerMod.LOGGER.info("AutoSync: '{}' is no longer favourited, discarding its staging copy",
                        folder);
                discardStaging(folder);
                DIRTY.remove(folder);
            }
        } catch (IOException ignored) {
        }
    }

    private static Path stagingRoot() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(".savemanager").resolve("staging");
    }

    private static int refreshStaging(Path worldDir, String folder, SyncState.WorldEntry entry) throws IOException {
        Path staging = stagingRoot().resolve(folder);
        Files.createDirectories(staging);

        Map<String, String> stamps = entry.fileStamps;
        Map<String, String> seen = new HashMap<>();
        int copied = 0;

        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(worldDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !IGNORED.contains(p.getFileName().toString()))
                    .forEach(files::add);
        }

        for (Path f : files) {
            String rel = worldDir.relativize(f).toString().replace('\\', '/');
            String stamp;
            try {
                BasicFileAttributes a = Files.readAttributes(f, BasicFileAttributes.class);
                stamp = a.size() + ":" + a.lastModifiedTime().toMillis();
            } catch (IOException e) {
                continue;
            }
            seen.put(rel, stamp);

            if (stamp.equals(stamps.get(rel)) && Files.exists(staging.resolve(rel)))
                continue;

            Path dest = staging.resolve(rel);
            Files.createDirectories(dest.getParent());
            try {
                Files.copy(f, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                copied++;
            } catch (IOException e) {
                seen.remove(rel);
            }
        }

        Set<String> removed = new HashSet<>(stamps.keySet());
        removed.removeAll(seen.keySet());
        for (String rel : removed) {
            try {
                Files.deleteIfExists(staging.resolve(rel));
                copied++;
            } catch (IOException ignored) {
            }
        }

        entry.fileStamps = seen;
        return copied;
    }

    private static void syncOne(String folder) {
        if (!Boolean.TRUE.equals(DIRTY.get(folder))) {
            SaveManagerMod.LOGGER.info("[SM] sync '{}': nothing pending, skipping", folder);
            return;
        }
        if (!SyncState.mayAttempt(folder))
            return;
        NetworkManager net = network;
        if (net == null)
            return;

        Path staging = stagingRoot().resolve(folder);
        if (!Files.isDirectory(staging))
            return;

        if (!TRANSFER.tryAcquire()) {
            SaveManagerMod.LOGGER.info("[SM] sync '{}': skipped, a manual transfer holds the lock", folder);
            return;
        }
        try {
            SyncState.WorldEntry entry = SyncState.get(folder);
            List<WorldManifest.Entry> entries = WorldManifest.buildCached(staging, entry.hashCache);
            if (entries.isEmpty()) {
                DIRTY.remove(folder);
                return;
            }

            SaveManagerMod.LOGGER.info("[SM] sync '{}': manifest {} file(s), parent={}",
                    folder, entries.size(), entry.headVersionId == null ? "none" : entry.headVersionId);
            JsonObject begun = net.syncBegin(folder, entry.headVersionId, entries).join();
            String sessionId = begun.get("sessionId").getAsString();

            Set<String> missing = new HashSet<>();
            for (var el : begun.getAsJsonArray("missing"))
                missing.add(el.getAsString().toLowerCase(Locale.ROOT));

            List<WorldManifest.Entry> toSend = new ArrayList<>();
            Set<String> queued = new HashSet<>();
            for (WorldManifest.Entry e : entries) {
                String h = e.sha256.toLowerCase(Locale.ROOT);
                if (missing.contains(h) && queued.add(h))
                    toSend.add(e);
            }

            long bytes = 0L;
            for (WorldManifest.Entry e : toSend)
                bytes += e.size;
            SaveManagerMod.LOGGER.info("[SM] sync '{}': server needs {} of {} blob(s), {} bytes",
                    folder, toSend.size(), entries.size(), bytes);

            if (!toSend.isEmpty())
                net.syncUpload(sessionId, toSend, null);

            JsonObject done = net.syncCommit(sessionId).join();
            String versionId = done.get("versionId").getAsString();

            SyncState.recordSuccess(folder, versionId);
            DIRTY.remove(folder);
            WatchManager.clearPendingNotification(folder);
            WatchManager.updateLastKnown(folder, Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("saves").resolve(folder));
            SaveManagerMod.LOGGER.info("[SM] sync '{}': COMMITTED version {}", folder, versionId);
        } catch (Throwable ex) {
            String msg = ex.toString();
            boolean terminal = msg.contains("401") || msg.contains("403") || msg.contains("413");
            if (msg.contains("409") || msg.contains("head_moved")) {
                SyncState.get(folder).headVersionId = null;
                SyncState.save();
                terminal = false;
            }
            SyncState.recordFailure(folder, terminal);
            SaveManagerMod.LOGGER.warn("[SM] sync '{}': FAILED{} - {}", folder, terminal ? " (terminal, will not retry)" : ", will retry with backoff", msg);
        } finally {
            TRANSFER.release();
        }
    }

    public static void discardStaging(String folder) {
        try {
            Path staging = stagingRoot().resolve(folder);
            if (!Files.isDirectory(staging))
                return;
            try (Stream<Path> walk = Files.walk(staging)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        } catch (IOException ignored) {
        }
        SyncState.forget(folder);
    }
}
