package com.choculaterie.gui;

import com.choculaterie.SaveManagerMod;
import com.choculaterie.network.NetworkManager;
import com.choculaterie.widget.CustomButton;
import com.choculaterie.widget.CustomTextField;
import com.choculaterie.widget.ToastManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.BiFunction;

public class AccountLinkingScreen extends Screen {
    private static final String CONFIG_FILE = "save-manager-settings.json";
    private static final String KEY_MATERIAL = "SaveManagerSecKey.v1";
    private static final byte[] AES_KEY;
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LEN = 12;
    private static final SecureRandom RNG = new SecureRandom();

    static {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            AES_KEY = md.digest(KEY_MATERIAL.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Failed to init crypto key", e);
        }
    }

    private final Screen parent;
    private final NetworkManager networkManager;
    private final ToastManager toastManager;

    private CustomTextField tokenField;
    private CustomButton visibilityButton;
    private CustomButton resetButton;
    private CustomButton saveButton;
    private CustomButton cancelButton;

    private String realToken = "";
    private boolean passwordVisible = false;

    public AccountLinkingScreen(Screen parent) {
        super(Text.literal("Link Your Account"));
        this.parent = parent;
        this.networkManager = new NetworkManager();
        this.toastManager = new ToastManager(null);
    }

    @Override
    protected void init() {
        if (toastManager != null) {
            try {
                var f = ToastManager.class.getDeclaredField("client");
                f.setAccessible(true);
                f.set(toastManager, client);
            } catch (Exception ignored) {}
        }

        int cx = this.width / 2;
        int fieldW = 200;
        int eyeW = 25;
        int resetW = 50;
        int gap = 6;
        int groupW = fieldW + gap + eyeW + gap + resetW;
        int groupLeft = cx - (groupW / 2);
        int fieldY = this.height / 2 - 10;
        int actionY = fieldY + 30;

        tokenField = new CustomTextField(client, groupLeft, fieldY, fieldW, 20, Text.literal("API Key"));
        tokenField.setPlaceholder(Text.literal("Enter your API key..."));
        tokenField.setOnChanged(() -> {
            if (passwordVisible) {
                realToken = tokenField.getText();
                updateResetButton();
            }
        });
        this.addSelectableChild(tokenField);
        this.setInitialFocus(tokenField);

        visibilityButton = new CustomButton(groupLeft + fieldW + gap, fieldY, eyeW, 20,
                Text.literal("👁"), b -> toggleVisibility());
        this.addDrawableChild(visibilityButton);

        resetButton = new CustomButton(groupLeft + fieldW + gap + eyeW + gap, fieldY, resetW, 20,
                Text.literal(realToken.isEmpty() ? "Link" : "Reset"), b -> handleResetOrLink());
        this.addDrawableChild(resetButton);

        int actionW = 95;
        int actionGap = 10;
        int actionsGroupW = actionW + actionGap + actionW;
        int actionsLeft = cx - (actionsGroupW / 2);

        saveButton = new CustomButton(actionsLeft, actionY, actionW, 20,
                Text.literal("Save"), b -> saveApiKey());
        this.addDrawableChild(saveButton);

        cancelButton = new CustomButton(actionsLeft + actionW + actionGap, actionY, actionW, 20,
                Text.literal("Cancel"), b -> closeScreen());
        this.addDrawableChild(cancelButton);

        loadSavedApiKey();
        passwordVisible = realToken.isEmpty();
        applyMasking();
        updateResetButton();
    }

    private void toggleVisibility() {
        passwordVisible = !passwordVisible;
        applyMasking();
    }

    private void applyMasking() {
        boolean visible = passwordVisible || realToken.isEmpty();
        try {
            BiFunction<String, Integer, OrderedText> provider = visible
                    ? (text, idx) -> Text.literal(text.substring(Math.min(idx, text.length()))).asOrderedText()
                    : (text, idx) -> Text.literal(mask(text.length()).substring(Math.min(idx, text.length()))).asOrderedText();
            var m = net.minecraft.client.gui.widget.TextFieldWidget.class.getMethod("setRenderTextProvider", BiFunction.class);
            m.invoke(tokenField, provider);
            tokenField.setText(realToken);
        } catch (Exception ignored) {
            tokenField.setText(visible ? realToken : mask(realToken.length()));
        }
    }

    private void updateResetButton() {
        if (resetButton != null) {
            resetButton.setMessage(Text.literal(realToken.isEmpty() ? "Link" : "Reset"));
        }
    }

    private void handleResetOrLink() {
        if (!realToken.isEmpty()) {
            realToken = "";
            networkManager.setApiKey(null);
            clearConfigToken();
            passwordVisible = true;
            applyMasking();
            updateResetButton();
            toastManager.showInfo("API key cleared");
        }
        openApiKeyWebsite();
    }

    private void loadSavedApiKey() {
        try {
            File configFile = new File(new File(client.runDirectory, "config"), CONFIG_FILE);
            if (configFile.exists()) {
                try (FileReader reader = new FileReader(configFile)) {
                    JsonObject json = new Gson().fromJson(reader, JsonObject.class);
                    if (json != null) {
                        if (json.has("encryptedApiToken")) {
                            realToken = decrypt(json.get("encryptedApiToken").getAsString());
                            networkManager.setApiKey(realToken);
                        } else if (json.has("apiToken")) {
                            realToken = json.get("apiToken").getAsString();
                            networkManager.setApiKey(realToken);
                        }
                    }
                }
            }
        } catch (Exception e) {
            SaveManagerMod.LOGGER.error("Error loading API key", e);
            toastManager.showError("Error loading saved API key");
        }
    }

    private void saveApiKey() {
        String apiKey = passwordVisible ? tokenField.getText() : realToken;
        try {
            if (apiKey == null || apiKey.isEmpty()) {
                networkManager.setApiKey(null);
                clearConfigToken();
                toastManager.showInfo("API key cleared");
            } else {
                networkManager.setApiKey(apiKey);
                File configDir = new File(client.runDirectory, "config");
                if (!configDir.exists()) configDir.mkdirs();
                File configFile = new File(configDir, CONFIG_FILE);
                JsonObject json = new JsonObject();
                json.addProperty("encryptedApiToken", encrypt(apiKey));
                try (FileWriter writer = new FileWriter(configFile)) {
                    new GsonBuilder().setPrettyPrinting().create().toJson(json, writer);
                }
                toastManager.showSuccess("API key saved");
            }
            closeScreen();
        } catch (Exception e) {
            SaveManagerMod.LOGGER.error("Error saving API key", e);
            toastManager.showError("Error saving API key");
        }
    }

    private void clearConfigToken() {
        try {
            File configFile = new File(new File(client.runDirectory, "config"), CONFIG_FILE);
            if (configFile.exists()) {
                JsonObject json;
                try (FileReader reader = new FileReader(configFile)) {
                    json = new Gson().fromJson(reader, JsonObject.class);
                }
                if (json != null) {
                    json.remove("encryptedApiToken");
                    json.remove("apiToken");
                    if (json.entrySet().isEmpty()) {
                        configFile.delete();
                    } else {
                        try (FileWriter writer = new FileWriter(configFile)) {
                            new GsonBuilder().setPrettyPrinting().create().toJson(json, writer);
                        }
                    }
                }
            }
        } catch (Exception e) {
            SaveManagerMod.LOGGER.error("Error clearing config token", e);
        }
    }

    private void openApiKeyWebsite() {
        try {
            net.minecraft.util.Util.getOperatingSystem().open(networkManager.getApiTokenGenerationUrl());
        } catch (Exception e) {
            SaveManagerMod.LOGGER.error("Failed to open API key website", e);
            toastManager.showError("Could not open browser");
        }
    }

    private void closeScreen() {
        if (client != null) {
            if (parent instanceof SaveManagerScreen) {
                client.setScreen(parent);
            } else {
                client.setScreen(new SelectWorldScreen(resolveRootParent(parent)));
            }
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        int cx = this.width / 2;
        int fieldY = this.height / 2 - 10;

        context.drawCenteredTextWithShadow(textRenderer, title, cx, 10, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Enter your API key to link your account"),
                cx, fieldY - 14, 0xFFAAAAAA);

        tokenField.render(context, mouseX, mouseY, delta);
        toastManager.render(context, delta, mouseX, mouseY);
    }

    private static String mask(int len) {
        return "*".repeat(Math.max(0, len));
    }

    private String encrypt(String input) throws Exception {
        byte[] iv = new byte[IV_LEN];
        RNG.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ct = cipher.doFinal(input.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return Base64.getEncoder().encodeToString(out);
    }

    private String decrypt(String base64) throws Exception {
        byte[] data = Base64.getDecoder().decode(base64);
        if (data.length < IV_LEN + 16) throw new IllegalArgumentException("Invalid data");
        byte[] iv = new byte[IV_LEN];
        byte[] ct = new byte[data.length - IV_LEN];
        System.arraycopy(data, 0, iv, 0, IV_LEN);
        System.arraycopy(data, IV_LEN, ct, 0, ct.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
    }

    private static Screen resolveRootParent(Screen parent) {
        Screen p = parent;
        int guard = 0;
        while (p instanceof SelectWorldScreen && guard++ < 8) {
            try {
                Field f = SelectWorldScreen.class.getDeclaredField("parent");
                f.setAccessible(true);
                Screen next = (Screen) f.get(p);
                if (next == null || next == p) break;
                p = next;
            } catch (Throwable ignored) {
                break;
            }
        }
        return p;
    }
}

