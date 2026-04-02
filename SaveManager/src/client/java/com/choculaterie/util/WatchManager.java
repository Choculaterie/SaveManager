package com.choculaterie.util;

import com.google.gson.*;
import net.minecraft.client.Minecraft;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public final class WatchManager {
    private static final String WATCH_FILE = "save-manager-watch.json";
    private static final Map<String, Long> watchedWorlds = new LinkedHashMap<>();
    private static boolean loaded = false;
    private static volatile List<String> pendingNotifications = List.of();

    private WatchManager() {}

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            File f = getFile();
            if (!f.exists()) return;
            try (FileReader r = new FileReader(f)) {
                JsonObject obj = new Gson().fromJson(r, JsonObject.class);
                if (obj == null || !obj.has("worlds")) return;
                for (var entry : obj.getAsJsonObject("worlds").entrySet()) {
                    if (entry.getValue().isJsonPrimitive())
                        watchedWorlds.put(entry.getKey(), entry.getValue().getAsLong());
                }
            }
        } catch (Exception ignored) {}
    }

    private static synchronized void persist() {
        try {
            File f = getFile();
            f.getParentFile().mkdirs();
            JsonObject worlds = new JsonObject();
            watchedWorlds.forEach(worlds::addProperty);
            JsonObject root = new JsonObject();
            root.add("worlds", worlds);
            try (FileWriter w = new FileWriter(f)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(root, w);
            }
        } catch (Exception ignored) {}
    }

    public static synchronized boolean isWatching(String worldName) {
        ensureLoaded();
        return watchedWorlds.containsKey(worldName);
    }

    public static synchronized void setWatching(String worldName, Path worldDir, boolean watching) {
        ensureLoaded();
        if (watching) {
            watchedWorlds.put(worldName, computeMaxModified(worldDir));
        } else {
            watchedWorlds.remove(worldName);
        }
        persist();
    }

    public static synchronized void updateLastKnown(String worldName, Path worldDir) {
        ensureLoaded();
        if (watchedWorlds.containsKey(worldName)) {
            watchedWorlds.put(worldName, computeMaxModified(worldDir));
            persist();
        }
    }

    public static synchronized List<String> getChangedWorlds(Path savesDir) {
        ensureLoaded();
        List<String> changed = new ArrayList<>();
        for (var entry : watchedWorlds.entrySet()) {
            Path worldDir = savesDir.resolve(entry.getKey());
            if (!Files.exists(worldDir)) continue;
            if (computeMaxModified(worldDir) > entry.getValue())
                changed.add(entry.getKey());
        }
        return changed;
    }

    public static void setPendingNotifications(List<String> worlds) {
        pendingNotifications = List.copyOf(worlds);
    }

    public static List<String> getPendingNotifications() {
        return pendingNotifications;
    }

    public static void clearPendingNotification(String worldName) {
        pendingNotifications = pendingNotifications.stream()
                .filter(n -> !n.equals(worldName))
                .collect(java.util.stream.Collectors.toList());
    }

    public static synchronized void dismissChanges(List<String> worldNames, Path savesDir) {
        ensureLoaded();
        for (String name : worldNames) {
            if (!watchedWorlds.containsKey(name)) continue;
            Path worldDir = savesDir.resolve(name);
            if (Files.exists(worldDir))
                watchedWorlds.put(name, computeMaxModified(worldDir));
        }
        persist();
    }

    private static long computeMaxModified(Path dir) {
        final long[] max = {0L};
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    max[0] = Math.max(max[0], attrs.lastModifiedTime().toMillis());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {}
        return max[0];
    }

    private static File getFile() {
        return new File(new File(Minecraft.getInstance().gameDirectory, "config"), WATCH_FILE);
    }
}
