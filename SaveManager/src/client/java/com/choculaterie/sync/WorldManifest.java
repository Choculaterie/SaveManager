package com.choculaterie.sync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.CRC32;

public final class WorldManifest {

    private static final List<String> IGNORED_NAMES = List.of("session.lock");

    public static final class Entry {
        public final String path;
        public final String sha256;
        public final long size;
        public final long crc32;
        public final Path source;

        Entry(String path, String sha256, long size, long crc32, Path source) {
            this.path = path;
            this.sha256 = sha256;
            this.size = size;
            this.crc32 = crc32;
            this.source = source;
        }
    }

    private WorldManifest() {
    }

    public static List<Entry> build(Path worldDir, ProgressSink progress) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(worldDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !IGNORED_NAMES.contains(p.getFileName().toString()))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(files::add);
        }

        long totalBytes = 0L;
        for (Path f : files) {
            try {
                totalBytes += Files.size(f);
            } catch (IOException ignored) {
            }
        }
        if (progress != null)
            progress.accept(0L, totalBytes);

        List<Entry> entries = new ArrayList<>(files.size());
        long hashed = 0L;
        for (Path f : files) {
            if (!Files.exists(f))
                continue;

            MessageDigest sha;
            try {
                sha = MessageDigest.getInstance("SHA-256");
            } catch (Exception e) {
                throw new IOException("SHA-256 unavailable", e);
            }
            CRC32 crc = new CRC32();
            long size = 0L;

            byte[] buffer = new byte[128 * 1024];
            try (InputStream in = Files.newInputStream(f)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    sha.update(buffer, 0, read);
                    crc.update(buffer, 0, read);
                    size += read;
                    hashed += read;
                    if (progress != null)
                        progress.accept(hashed, totalBytes);
                }
            }

            String rel = worldDir.relativize(f).toString().replace('\\', '/');
            entries.add(new Entry(rel, toHex(sha.digest()), size, crc.getValue(), f));
        }
        return entries;
    }

    public static List<Entry> buildCached(Path root, Map<String, String> cache) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !IGNORED_NAMES.contains(p.getFileName().toString()))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(files::add);
        }

        List<Entry> entries = new ArrayList<>(files.size());
        Map<String, String> fresh = new HashMap<>();

        for (Path f : files) {
            String rel = root.relativize(f).toString().replace('\\', '/');
            BasicFileAttributes a;
            try {
                a = Files.readAttributes(f, BasicFileAttributes.class);
            } catch (IOException e) {
                continue;
            }
            String stamp = a.size() + ":" + a.lastModifiedTime().toMillis();
            String cached = cache.get(rel);

            if (cached != null && cached.startsWith(stamp + ":")) {
                String[] parts = cached.split(":");
                if (parts.length == 4) {
                    entries.add(new Entry(rel, parts[2], a.size(), Long.parseLong(parts[3]), f));
                    fresh.put(rel, cached);
                    continue;
                }
            }

            Entry computed = digest(root, f);
            if (computed == null)
                continue;
            entries.add(computed);
            fresh.put(rel, stamp + ":" + computed.sha256 + ":" + computed.crc32);
        }

        cache.clear();
        cache.putAll(fresh);
        return entries;
    }

    private static Entry digest(Path root, Path f) throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        CRC32 crc = new CRC32();
        long size = 0L;
        byte[] buffer = new byte[128 * 1024];
        try (InputStream in = Files.newInputStream(f)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                sha.update(buffer, 0, read);
                crc.update(buffer, 0, read);
                size += read;
            }
        } catch (IOException e) {
            return null;
        }
        String rel = root.relativize(f).toString().replace('\\', '/');
        return new Entry(rel, toHex(sha.digest()), size, crc.getValue(), f);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes)
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    public interface ProgressSink {
        void accept(long done, long total);
    }
}
