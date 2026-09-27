package com.choculaterie.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SyncState {

    public static final class WorldEntry {
        public String headVersionId;
        public boolean stagingPopulated;
        public long lastSyncedAtMs;
        public long failureCount;
        public long nextAttemptAtMs;
        public boolean conflicted;
        public String remoteHeadVersionId;
        public Map<String, String> fileStamps = new HashMap<>();
        public Map<String, String> hashCache = new HashMap<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CLIENT_ID_KEY = "clientId";
    private static final String WORLDS_KEY = "worlds";
    private static final Map<String, WorldEntry> WORLDS = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;
    private static volatile String clientId;

    private SyncState() {
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("save-manager-sync.json");
    }

    public static synchronized void load() {
        if (loaded)
            return;
        loaded = true;
        Path p = file();
        if (!Files.exists(p))
            return;
        try (Reader r = Files.newBufferedReader(p)) {
            JsonObject root = GSON.fromJson(r, JsonObject.class);
            if (root == null)
                return;
            if (root.has(CLIENT_ID_KEY) && !root.get(CLIENT_ID_KEY).isJsonNull())
                clientId = root.get(CLIENT_ID_KEY).getAsString();
            if (root.has(WORLDS_KEY) && root.get(WORLDS_KEY).isJsonObject()) {
                var type = new TypeToken<Map<String, WorldEntry>>() {
                }.getType();
                Map<String, WorldEntry> read = GSON.fromJson(root.getAsJsonObject(WORLDS_KEY), type);
                if (read != null)
                    WORLDS.putAll(read);
            } else {
                var type = new TypeToken<Map<String, WorldEntry>>() {
                }.getType();
                Map<String, WorldEntry> legacy = GSON.fromJson(root, type);
                if (legacy != null)
                    WORLDS.putAll(legacy);
            }
        } catch (Exception ignored) {
        }
    }

    public static synchronized void save() {
        Path p = file();
        try {
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            JsonObject root = new JsonObject();
            if (clientId != null)
                root.addProperty(CLIENT_ID_KEY, clientId);
            root.add(WORLDS_KEY, GSON.toJsonTree(WORLDS));
            try (Writer w = Files.newBufferedWriter(tmp)) {
                GSON.toJson(root, w);
            }
            try {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
        }
    }

    public static synchronized String clientId() {
        load();
        if (clientId == null || clientId.isBlank()) {
            clientId = java.util.UUID.randomUUID().toString();
            save();
        }
        return clientId;
    }

    public static WorldEntry get(String worldFolder) {
        load();
        return WORLDS.computeIfAbsent(worldFolder, k -> new WorldEntry());
    }

    public static void forget(String worldFolder) {
        WORLDS.remove(worldFolder);
        save();
    }

    public static void markConflicted(String worldFolder, String remoteHead) {
        WorldEntry e = get(worldFolder);
        e.conflicted = true;
        e.remoteHeadVersionId = remoteHead;
        e.nextAttemptAtMs = Long.MAX_VALUE;
        save();
    }

    public static void clearConflict(String worldFolder, String acceptedHead) {
        WorldEntry e = get(worldFolder);
        e.conflicted = false;
        e.remoteHeadVersionId = null;
        e.headVersionId = acceptedHead;
        e.failureCount = 0;
        e.nextAttemptAtMs = 0;
        save();
    }

    public static boolean isConflicted(String worldFolder) {
        return get(worldFolder).conflicted;
    }

    public static void recordSuccess(String worldFolder, String headVersionId) {
        WorldEntry e = get(worldFolder);
        e.headVersionId = headVersionId;
        e.conflicted = false;
        e.remoteHeadVersionId = null;
        e.lastSyncedAtMs = System.currentTimeMillis();
        e.failureCount = 0;
        e.nextAttemptAtMs = 0;
        save();
    }

    public static void recordFailure(String worldFolder, boolean terminal) {
        WorldEntry e = get(worldFolder);
        e.failureCount++;
        long[] backoffMinutes = { 1, 2, 5, 15, 60 };
        int idx = (int) Math.min(e.failureCount - 1, backoffMinutes.length - 1);
        e.nextAttemptAtMs = terminal
                ? Long.MAX_VALUE
                : System.currentTimeMillis() + backoffMinutes[idx] * 60_000L;
        save();
    }

    public static boolean mayAttempt(String worldFolder) {
        WorldEntry e = get(worldFolder);
        return System.currentTimeMillis() >= e.nextAttemptAtMs;
    }

    public static void clearBackoff(String worldFolder) {
        WorldEntry e = get(worldFolder);
        e.failureCount = 0;
        e.nextAttemptAtMs = 0;
    }

    public static JsonObject debug() {
        JsonObject o = new JsonObject();
        WORLDS.forEach((k, v) -> o.addProperty(k, v.headVersionId + " files=" + v.fileStamps.size()));
        return o;
    }
}
