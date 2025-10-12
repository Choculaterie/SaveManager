package com.choculaterie.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class NetworkManager {
    private static final String BASE_URL = "https://localhost:7282";
    private static final String API_KEY_HEADER = "X-Save-Key";

    // Dev-only: trust-all client to avoid PKIX with local self-signed certs
    private static final HttpClient INSECURE_CLIENT;
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
            INSECURE_CLIENT = HttpClient.newBuilder()
                    .sslContext(ssl)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
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

    public CompletableFuture<JsonObject> uploadWorldSave(String worldName, Path zipFilePath) throws IOException {
        validateApiKey();

        String safeWorldName = (worldName == null || worldName.isBlank()) ? "world" : worldName;
        String fileName = safeWorldName + ".zip";
        byte[] fileBytes = Files.readAllBytes(zipFilePath);

        String boundary = "----savemanager-" + UUID.randomUUID();
        String partWorldName =
                "--" + boundary + "\r\n" +
                        "Content-Disposition: form-data; name=\"WorldName\"\r\n\r\n" +
                        safeWorldName + "\r\n";

        String partFileHeader =
                "--" + boundary + "\r\n" +
                        "Content-Disposition: form-data; name=\"SaveFile\"; filename=\"" + fileName + "\"\r\n" +
                        "Content-Type: application/zip\r\n\r\n";

        byte[] meta = partWorldName.getBytes();
        byte[] fileHdr = partFileHeader.getBytes();
        byte[] closing = ("\r\n--" + boundary + "--\r\n").getBytes();

        byte[] body = new byte[meta.length + fileHdr.length + fileBytes.length + closing.length];
        int p = 0;
        System.arraycopy(meta, 0, body, p, meta.length); p += meta.length;
        System.arraycopy(fileHdr, 0, body, p, fileHdr.length); p += fileHdr.length;
        System.arraycopy(fileBytes, 0, body, p, fileBytes.length); p += fileBytes.length;
        System.arraycopy(closing, 0, body, p, closing.length);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/SaveManagerAPI/upload"))
                .header(API_KEY_HEADER, apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::handleJsonResponse);
    }

    public CompletableFuture<Path> downloadWorldSave(String saveId, Path destinationDirectory) {
        validateApiKey();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/SaveManagerAPI/download/" + saveId))
                .header(API_KEY_HEADER, apiKey)
                .GET()
                .build();

        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(resp -> {
                    if (resp.statusCode() >= 400) {
                        String body = new String(resp.body());
                        throw new RuntimeException("Request failed: " + resp.statusCode() + " - " + body);
                    }
                    String fileName = extractFileNameFromResponse(resp);
                    try {
                        Files.createDirectories(destinationDirectory);
                        Path out = destinationDirectory.resolve(fileName);
                        Files.write(out, resp.body());
                        return out;
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
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

    private String extractFileNameFromResponse(HttpResponse<byte[]> response) {
        String defaultName = "world_save.zip";
        return response.headers()
                .firstValue("Content-Disposition")
                .map(header -> {
                    if (header.contains("filename=")) {
                        int startIndex = header.indexOf("filename=") + 9;
                        int endIndex = header.indexOf(";", startIndex);
                        if (endIndex == -1) endIndex = header.length();
                        String fileName = header.substring(startIndex, endIndex)
                                .replace("\"", "")
                                .trim();
                        return fileName.isEmpty() ? defaultName : fileName;
                    }
                    return defaultName;
                })
                .orElse(defaultName);
    }
}
