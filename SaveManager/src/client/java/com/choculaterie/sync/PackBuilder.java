package com.choculaterie.sync;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

public final class PackBuilder {

    private PackBuilder() {
    }

    public static long writePack(OutputStream out, List<WorldManifest.Entry> entries, ProgressSink progress)
            throws IOException {
        long sent = 0L;
        long total = 0L;
        for (WorldManifest.Entry e : entries)
            total += e.size;

        for (WorldManifest.Entry e : entries) {
            out.write(e.sha256.getBytes(StandardCharsets.US_ASCII));

            if (e.size > STORED_THRESHOLD) {
                out.write(longToLittleEndian(storedLength(e.size)));
                final long base = sent;
                final long all = total;
                writeStored(out, e, done -> {
                    if (progress != null)
                        progress.accept(base + done, all);
                });
            } else {
                byte[] deflated = deflate(e);
                out.write(longToLittleEndian(deflated.length));
                out.write(deflated);
            }

            sent += e.size;
            if (progress != null)
                progress.accept(sent, total);
        }
        out.flush();
        return sent;
    }

    private static final long STORED_THRESHOLD = 64L * 1024 * 1024;
    private static final int STORED_BLOCK = 65_535;

    static long storedLength(long size) {
        long blocks = Math.max(1L, (size + STORED_BLOCK - 1) / STORED_BLOCK);
        return size + blocks * 5L;
    }

    private static void writeStored(OutputStream out, WorldManifest.Entry entry, java.util.function.LongConsumer progress)
            throws IOException {
        byte[] block = new byte[STORED_BLOCK];
        long left = entry.size;
        long done = 0L;
        try (InputStream in = Files.newInputStream(entry.source)) {
            do {
                int len = (int) Math.min(STORED_BLOCK, left);
                int got = in.readNBytes(block, 0, len);
                if (got != len)
                    throw new IOException("\"" + entry.source.getFileName() + "\" changed while uploading");
                left -= len;
                out.write(left == 0 ? 1 : 0);
                out.write(len & 0xFF);
                out.write((len >>> 8) & 0xFF);
                out.write(~len & 0xFF);
                out.write((~len >>> 8) & 0xFF);
                out.write(block, 0, len);
                done += len;
                if ((done & (16L * 1024 * 1024 - 1)) < len || left == 0)
                    progress.accept(done);
            } while (left > 0);
        }
    }

    private static byte[] deflate(WorldManifest.Entry entry) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try (DeflaterOutputStream dos = new DeflaterOutputStream(buffer, deflater, 64 * 1024);
                InputStream in = Files.newInputStream(entry.source)) {
            byte[] chunk = new byte[128 * 1024];
            int read;
            while ((read = in.read(chunk)) != -1)
                dos.write(chunk, 0, read);
        } finally {
            deflater.end();
        }
        return buffer.toByteArray();
    }

    private static byte[] longToLittleEndian(long value) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++)
            b[i] = (byte) (value >>> (8 * i));
        return b;
    }

    public interface ProgressSink {
        void accept(long done, long total);
    }
}
