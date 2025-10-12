package com.choculaterie.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.nio.file.StandardCopyOption;
import java.util.function.BiConsumer;

public class NetworkManager {
    private static final String BASE_URL = "https://localhost:7282";
    private static final String API_KEY_HEADER = "X-Save-Key";

    // Dev-only: trust-all client to avoid PKIX with local self-signed certs
    private static final HttpClient INSECURE_CLIENT;
    // For HttpsURLConnection (used by our streaming upload path) - dev-only trust-all
    private static final SSLSocketFactory INSECURE_SSLSF;
    private static final HostnameVerifier INSECURE_HV;
    static {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
            };
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, trustAllCerts, new SecureRandom());
            // Make this SSLContext the JVM default (dev convenience) so other libraries
            // that rely on SSLContext.getDefault() will also use the trust-all context.
            try { SSLContext.setDefault(ssl); } catch (Throwable ignored) {}
            INSECURE_CLIENT = HttpClient.newBuilder()
                    .sslContext(ssl)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            INSECURE_SSLSF = ssl.getSocketFactory();
            INSECURE_HV = (hostname, session) -> true;
            // Apply as defaults for HttpsURLConnection too (dev-time convenience)
            try {
                HttpsURLConnection.setDefaultSSLSocketFactory(INSECURE_SSLSF);
                HttpsURLConnection.setDefaultHostnameVerifier(INSECURE_HV);
            } catch (Throwable ignored) {}
         } catch (Exception e) {
             throw new RuntimeException("Failed to initialize HttpClient with custom SSL context", e);
         }
    }

    private final HttpClient httpClient;
    private final Gson gson;
    private String apiKey;

    public NetworkManager() {
        this.httpClient = INSECURE_CLIENT;
        this.gson = new Gson();
    }

    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getApiKey() { return apiKey; }

    public String getApiTokenGenerationUrl() {
        // MVC view that issues/refreshes the Save Key
        return BASE_URL + "/api/SaveManagerAPI/GenerateSaveToken";
    }

    public CompletableFuture<JsonObject> listWorldSaves() {
        validateApiKey();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/SaveManagerAPI/list"))
                .header(API_KEY_HEADER, apiKey)
                .GET()
                .build();
        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::handleJsonResponse);
    }

    // Keep the two-arg convenience method that delegates to the new overload
    public CompletableFuture<JsonObject> uploadWorldSave(String worldName, Path zipFilePath) throws IOException {
        return uploadWorldSave(worldName, zipFilePath, null);
    }

    // New overload that reports progress via the provided BiConsumer (uploadedBytes, totalBytes)
    public CompletableFuture<JsonObject> uploadWorldSave(String worldName, Path zipFilePath, BiConsumer<Long, Long> progressCallback) throws IOException {
        validateApiKey();

        String safeWorldName = (worldName == null || worldName.isBlank()) ? "world" : worldName;
        String fileName = safeWorldName + ".zip";
        String boundary = "----savemanager-" + UUID.randomUUID();

        return CompletableFuture.supplyAsync(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(BASE_URL + "/api/SaveManagerAPI/upload");
                conn = (HttpURLConnection) url.openConnection();
                // If it's HTTPS, apply the insecure SSL socket factory + hostname verifier for dev
                if (conn instanceof HttpsURLConnection) {
                    try {
                        ((HttpsURLConnection) conn).setSSLSocketFactory(INSECURE_SSLSF);
                        ((HttpsURLConnection) conn).setHostnameVerifier(INSECURE_HV);
                    } catch (Throwable ignored) {}
                }
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty(API_KEY_HEADER, apiKey);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                // Ask server to respond with 100-Continue so we can detect rejections before sending large body
                conn.setRequestProperty("Expect", "100-continue");

                // Determine the multipart pieces and total size so we can send a Content-Length
                // header (preferred for some servers). If computing size fails or the runtime
                // doesn't support fixed-length streaming, fall back to chunked mode.

                // Optional: sensible timeouts (increase read timeout for large uploads)
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(600_000); // 10 minutes

                // Prepare the multipart pieces as bytes (preamble and closing only).
                String partWorldName =
                        "--" + boundary + "\r\n" +
                                "Content-Disposition: form-data; name=\"WorldName\"\r\n\r\n" +
                                safeWorldName + "\r\n";

                String partFileHeader =
                        "--" + boundary + "\r\n" +
                                "Content-Disposition: form-data; name=\"SaveFile\"; filename=\"" + fileName + "\"\r\n" +
                                "Content-Type: application/zip\r\n\r\n";

                String closing = "\r\n--" + boundary + "--\r\n";

                byte[] metaBytes = partWorldName.getBytes(StandardCharsets.UTF_8);
                byte[] fileHdrBytes = partFileHeader.getBytes(StandardCharsets.UTF_8);
                byte[] closingBytes = closing.getBytes(StandardCharsets.UTF_8);

                // Try to set fixed-length streaming mode using the file size + overhead so the
                // server gets a Content-Length header (some servers reject chunked uploads).
                boolean usingFixedLength = false;
                long fileSize = -1L;
                long total = -1L;
                try {
                    fileSize = Files.size(zipFilePath);
                    total = (long) metaBytes.length + (long) fileHdrBytes.length + fileSize + (long) closingBytes.length;
                    try {
                        conn.setFixedLengthStreamingMode(total);
                        usingFixedLength = true;
                    } catch (NoSuchMethodError | IllegalArgumentException e) {
                        // Older runtimes may not support the long overload; try int overload if safe
                        if (total <= Integer.MAX_VALUE) {
                            conn.setFixedLengthStreamingMode((int) total);
                            usingFixedLength = true;
                        } else {
                            // Can't set fixed length; fall back to chunked below
                            usingFixedLength = false;
                        }
                    }
                } catch (Throwable t) {
                    // Could not compute file size or set fixed-length mode; fall back to chunked streaming
                    usingFixedLength = false;
                    fileSize = -1L;
                    total = -1L;
                }

                if (!usingFixedLength) {
                    // Fall back to chunked streaming (no Content-Length)
                    try {
                        conn.setChunkedStreamingMode(0);
                    } catch (Throwable ignored) {}
                }

                // If caller provided a progress callback and we know total, emit initial 0 total
                if (progressCallback != null) {
                    try { progressCallback.accept(0L, total); } catch (Throwable ignored) {}
                }

                try (OutputStream rawOut = conn.getOutputStream();
                     BufferedOutputStream out = new BufferedOutputStream(rawOut);
                     InputStream fileIn = Files.newInputStream(zipFilePath)) {

                    // Write the WorldName part then file header
                    out.write(metaBytes);
                    out.write(fileHdrBytes);
                    out.flush();

                    // Stream the file content in small chunks; handle IO errors gracefully with
                    // an informative exception so calling code can log it.
                    byte[] buffer = new byte[64 * 1024]; // 64 KB buffer for faster throughput
                    int read;
                    long uploaded = 0L;
                    while ((read = fileIn.read(buffer)) != -1) {
                        try {
                            out.write(buffer, 0, read);
                            uploaded += read;
                            if (progressCallback != null) {
                                try { progressCallback.accept(uploaded, (total > 0) ? total : fileSize); } catch (Throwable ignored) {}
                            }
                        } catch (IOException io) {
                            // Try to get more information from the server's error stream or response code
                            String serverMsg = "";
                            try {
                                int code = -1;
                                try { code = conn.getResponseCode(); } catch (Throwable ignored) {}
                                InputStream err = null;
                                try { err = conn.getErrorStream(); } catch (Throwable ignored) {}
                                if (err != null) {
                                    try (InputStream e = err) {
                                        byte[] eb = e.readAllBytes();
                                        serverMsg = new String(eb, StandardCharsets.UTF_8);
                                    } catch (Throwable ignored) {}
                                }
                                String codePart = code == -1 ? "" : ("HTTP/" + code + " ");
                                throw new IOException(codePart + "Error writing request body to server while streaming file. Server message: " + serverMsg, io);
                            } catch (IOException re) {
                                throw re;
                            } catch (Throwable t) {
                                throw new IOException("Error writing request body to server while streaming file and failed to read error stream", io);
                            }
                        }
                    }
                    out.flush();

                    // Write closing boundary
                    out.write(closingBytes);
                    out.flush();
                }

                int status = conn.getResponseCode();
                InputStream respStream = (status >= 400) ? conn.getErrorStream() : conn.getInputStream();
                if (respStream == null) respStream = new ByteArrayInputStream(new byte[0]);

                String body = new String(respStream.readAllBytes(), StandardCharsets.UTF_8);

                if (status >= 400) {
                    throw new RuntimeException("Upload failed: " + status + " - " + body);
                }

                return JsonParser.parseString(body).getAsJsonObject();
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    public CompletableFuture<java.util.List<String>> listWorldSaveNames() {
        validateApiKey();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/SaveManagerAPI/names"))
                .header(API_KEY_HEADER, apiKey)
                .GET()
                .build();

        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() >= 400) {
                        throw new RuntimeException("Request failed: " + response.statusCode() + " - " + response.body());
                    }

                    String body = response.body();
                    java.util.Optional<String> ct = response.headers().firstValue("Content-Type");
                    if (ct.map(s -> s.contains("text/html")).orElse(false) || body.startsWith("<!DOCTYPE")) {
                        throw new RuntimeException("Unexpected HTML response from server. Check the API URL.");
                    }

                    com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(body).getAsJsonArray();
                    java.util.List<String> out = new java.util.ArrayList<>();
                    for (com.google.gson.JsonElement e : arr) {
                        if (e != null && e.isJsonPrimitive()) {
                            out.add(e.getAsString());
                        }
                    }
                    return out;
                });
    }

    public CompletableFuture<Path> downloadWorldSave(String saveId, Path destinationDirectory) {
        return downloadWorldSave(saveId, destinationDirectory, null);
    }

    // Streamed download with progress callback (downloadedBytes, totalBytes or -1 if unknown)
    public CompletableFuture<Path> downloadWorldSave(String saveId, Path destinationDirectory, BiConsumer<Long, Long> progressCallback) {
        validateApiKey();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/SaveManagerAPI/download/" + saveId))
                .header(API_KEY_HEADER, apiKey)
                // Important: avoid transparent gzip -> keeps Content-Length so we can show percent
                .header("Accept-Encoding", "identity")
                .GET()
                .build();

        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream())
                .thenCompose(response -> CompletableFuture.supplyAsync(() -> {
                    int status = response.statusCode();
                    if (status >= 400) {
                        try (InputStream err = response.body()) {
                            String body = new String(err.readAllBytes(), StandardCharsets.UTF_8);
                            throw new RuntimeException("Request failed: " + status + " - " + body);
                        } catch (IOException e) {
                            throw new RuntimeException("Request failed: " + status, e);
                        }
                    }

                    // Try several header names for total length
                    long total = firstLongHeader(response, "Content-Length", "X-Content-Length", "X-File-Size", "X-Total-Length")
                            .orElse(-1L);

                    // Emit initial progress with known total, even before first bytes
                    if (progressCallback != null) {
                        try { progressCallback.accept(0L, total); } catch (Throwable ignored) {}
                    }

                    String fileName = extractFileNameFromResponse(response);
                    try {
                        Files.createDirectories(destinationDirectory);
                        Path temp = Files.createTempFile(destinationDirectory, "download-", ".tmp");

                        long downloaded = 0L;
                        try (InputStream in = response.body();
                             OutputStream out = Files.newOutputStream(temp)) {
                            byte[] buffer = new byte[256 * 1024];
                            int read;
                            while ((read = in.read(buffer)) != -1) {
                                out.write(buffer, 0, read);
                                downloaded += read;
                                if (progressCallback != null) {
                                    try { progressCallback.accept(downloaded, total); } catch (Throwable ignored) {}
                                }
                            }
                            out.flush();
                        }

                        Path finalPath = destinationDirectory.resolve(fileName);
                        try {
                            Files.move(temp, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                        } catch (Exception e) {
                            Files.move(temp, finalPath, StandardCopyOption.REPLACE_EXISTING);
                        }

                        // Final tick with exact size
                        long finalSize = Files.size(finalPath);
                        if (progressCallback != null) {
                            long finalTotal = (total > 0) ? total : finalSize;
                            try { progressCallback.accept(finalSize, finalTotal); } catch (Throwable ignored) {}
                        }
                        return finalPath;
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }));
    }

    // Overload works with any HttpResponse type
    private String extractFileNameFromResponse(HttpResponse<?> response) {
        String def = "world_save.zip";
        return response.headers()
                .firstValue("Content-Disposition")
                .map(header -> {
                    String lower = header.toLowerCase(java.util.Locale.ROOT);
                    int idx = lower.indexOf("filename=");
                    if (idx >= 0) {
                        String raw = header.substring(idx + 9).trim();
                        if (raw.startsWith("\"")) {
                            int end = raw.indexOf('"', 1);
                            if (end > 1) return raw.substring(1, end);
                        }
                        int semi = raw.indexOf(';');
                        String v = (semi >= 0 ? raw.substring(0, semi) : raw).replace("\"", "").trim();
                        if (!v.isEmpty()) return v;
                    }
                    return def;
                }).orElse(def);
    }

    private Optional<Long> firstLongHeader(HttpResponse<?> response, String... names) {
        for (String n : names) {
            Optional<String> v = response.headers().firstValue(n);
            if (v.isPresent()) {
                try { return Optional.of(Long.parseLong(v.get().trim())); }
                catch (NumberFormatException ignored) {}
            }
        }
        return Optional.empty();
    }

    public CompletableFuture<JsonObject> deleteWorldSave(String saveId) {
        validateApiKey();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/SaveManagerAPI/delete/" + saveId))
                .header(API_KEY_HEADER, apiKey)
                .DELETE()
                .build();

        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::handleJsonResponse);
    }

    private void validateApiKey() {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("API key is not set");
        }
    }

    private JsonObject handleJsonResponse(HttpResponse<String> response) {
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Request failed: " + response.statusCode() + " - " + response.body());
        }
        // Guard: if we accidentally hit an HTML page (e.g., wrong route/login page), fail clearly
        Optional<String> ct = response.headers().firstValue("Content-Type");
        String body = response.body();
        if (ct.map(s -> s.contains("text/html")).orElse(false) || body.startsWith("<!DOCTYPE")) {
            throw new RuntimeException("Unexpected HTML response from server. Check the API URL.");
        }
        return JsonParser.parseString(body).getAsJsonObject();
    }

}
