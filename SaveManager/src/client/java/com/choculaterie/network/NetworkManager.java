package com.choculaterie.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class NetworkManager {
    private static final String BASE_URL = "https://localhost:7282";
    private static final String API_KEY_HEADER = "X-Save-Key";

    private final HttpClient httpClient;
    private final Gson gson;
    private String apiKey;

    public NetworkManager() {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();
        this.gson = new Gson();
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiKey() {
        return apiKey;
    }

    // Browser route for token generation (auth flow)
    public String getApiTokenGenerationUrl() {
        return BASE_URL + "/api/SaveManagerAPI/GenerateSaveToken";
    }

    // Lists all world saves for the authenticated user
    public CompletableFuture<JsonObject> listWorldSaves() {
        validateApiKey();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/SaveManagerAPI/list"))
                .header(API_KEY_HEADER, apiKey)
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::handleJsonResponse);
    }

    // Uploads a world save as a ZIP file
    public CompletableFuture<JsonObject> uploadWorldSave(String worldName, Path zipFilePath) throws IOException {
        validateApiKey();

        String boundary = "----" + UUID.randomUUID();
        byte[] worldNamePart = ("--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"WorldName\"\r\n\r\n" +
                worldName + "\r\n").getBytes();

        byte[] fileHeaderPart = ("--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"SaveFile\"; filename=\"" +
                zipFilePath.getFileName() + "\"\r\n" +
                "Content-Type: application/zip\r\n\r\n").getBytes();

        byte[] fileContent = Files.readAllBytes(zipFilePath);
        byte[] endBoundary = ("\r\n--" + boundary + "--\r\n").getBytes();

        byte[] requestBody = new byte[worldNamePart.length + fileHeaderPart.length +
                fileContent.length + endBoundary.length];

        System.arraycopy(worldNamePart, 0, requestBody, 0, worldNamePart.length);
        System.arraycopy(fileHeaderPart, 0, requestBody, worldNamePart.length, fileHeaderPart.length);
        System.arraycopy(fileContent, 0, requestBody, worldNamePart.length + fileHeaderPart.length, fileContent.length);
        System.arraycopy(endBoundary, 0, requestBody, worldNamePart.length + fileHeaderPart.length + fileContent.length, endBoundary.length);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/SaveManagerAPI/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header(API_KEY_HEADER, apiKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::handleJsonResponse);
    }

    // Downloads a world save by ID
    public CompletableFuture<Path> downloadWorldSave(String saveId, Path destinationDirectory) {
        validateApiKey();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/SaveManagerAPI/download/" + saveId))
                .header(API_KEY_HEADER, apiKey)
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    if (response.statusCode() >= 400) {
                        throw new RuntimeException("Download failed with status code: " + response.statusCode());
                    }

                    String fileName = extractFileNameFromResponse(response);
                    Path filePath = destinationDirectory.resolve(fileName);

                    try {
                        Files.createDirectories(destinationDirectory);
                        Files.write(filePath, response.body());
                        return filePath;
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to save downloaded file", e);
                    }
                });
    }

    // Deletes a world save by ID
    public CompletableFuture<JsonObject> deleteWorldSave(String saveId) {
        validateApiKey();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/SaveManagerAPI/delete/" + saveId))
                .header(API_KEY_HEADER, apiKey)
                .DELETE()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::handleJsonResponse);
    }

    // Helpers
    private void validateApiKey() {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("API key not set. Please set an API key before making requests.");
        }
    }

    private JsonObject handleJsonResponse(HttpResponse<String> response) {
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Request failed with status code: " + response.statusCode() +
                    ", Response: " + response.body());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
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
                                .replace("\"", "").trim();
                        return fileName.isEmpty() ? defaultName : fileName;
                    }
                    return defaultName;
                })
                .orElse(defaultName);
    }
}
