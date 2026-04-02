package com.choculaterie.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

public final class ConfigManager {
    private static final String CONFIG_FILE = "save-manager-settings.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ConfigManager() {}

    private static File getConfigFile() {
        return new File(new File(Minecraft.getInstance().gameDirectory, "config"), CONFIG_FILE);
    }

    public static String loadApiKey() {
        try {
            File configFile = getConfigFile();
            if (!configFile.exists()) return null;
            try (FileReader reader = new FileReader(configFile)) {
                JsonObject json = new Gson().fromJson(reader, JsonObject.class);
                if (json == null) return null;
                if (json.has("encryptedApiToken")) return CryptoUtils.decrypt(json.get("encryptedApiToken").getAsString());
                if (json.has("apiToken")) return json.get("apiToken").getAsString();
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    public static void saveApiKey(String key) {
        try {
            File configFile = getConfigFile();
            configFile.getParentFile().mkdirs();
            JsonObject json = new JsonObject();
            json.addProperty("encryptedApiToken", CryptoUtils.encrypt(key));
            try (FileWriter writer = new FileWriter(configFile)) {
                GSON.toJson(json, writer);
            }
        } catch (Exception ignored) {}
    }

    public static void clearApiKey() {
        try {
            File configFile = getConfigFile();
            if (!configFile.exists()) return;
            JsonObject json;
            try (FileReader reader = new FileReader(configFile)) {
                json = new Gson().fromJson(reader, JsonObject.class);
            }
            if (json == null) return;
            json.remove("encryptedApiToken");
            json.remove("apiToken");
            if (json.entrySet().isEmpty()) {
                configFile.delete();
            } else {
                try (FileWriter writer = new FileWriter(configFile)) {
                    GSON.toJson(json, writer);
                }
            }
        } catch (Exception ignored) {}
    }
}
