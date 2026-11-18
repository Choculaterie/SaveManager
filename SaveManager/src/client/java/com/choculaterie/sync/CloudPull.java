package com.choculaterie.sync;

import com.choculaterie.vanilib.util.WatchManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public final class CloudPull {

    private static final Set<String> BYPASS = ConcurrentHashMap.newKeySet();

    private CloudPull() {
    }

    public static boolean shouldCheck(String folder) {
        if (BYPASS.remove(folder))
            return false;
        return AutoSync.isEnabled() && WatchManager.isWatching(folder);
    }

    public static void bypassOnce(String folder) {
        BYPASS.add(folder);
    }

    public static boolean hasLocalChanges(Path worldDir, String folder) {
        SyncState.WorldEntry entry = SyncState.get(folder);
        if (entry.pending)
            return true;
        Map<String, String> stamps = entry.fileStamps;
        if (stamps == null || stamps.isEmpty())
            return false;
        Map<String, String> now = new HashMap<>();
        try (Stream<Path> walk = Files.walk(worldDir)) {
            for (Path f : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(f) || WorldManifest.isIgnored(f))
                    continue;
                BasicFileAttributes a = Files.readAttributes(f, BasicFileAttributes.class);
                now.put(worldDir.relativize(f).toString().replace('\\', '/'),
                        a.size() + ":" + a.lastModifiedTime().toMillis());
            }
        } catch (IOException e) {
            return true;
        }
        return !now.equals(stamps);
    }
}
