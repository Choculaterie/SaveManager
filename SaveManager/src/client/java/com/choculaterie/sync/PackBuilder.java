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
            byte[] deflated = deflate(e);

            out.write(e.sha256.getBytes(StandardCharsets.US_ASCII));
            out.write(longToLittleEndian(deflated.length));
            out.write(deflated);

            sent += e.size;
            if (progress != null)
                progress.accept(sent, total);
        }
        out.flush();
        return sent;
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
