package com.choculaterie.gui;

import com.choculaterie.SaveManagerMod;
import com.choculaterie.network.NetworkManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.function.BiFunction;
import java.util.function.Function;

public class AccountLinkingScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget tokenField;
    private String statusMessage = "";
    private final NetworkManager networkManager;

    // Masking state (hidden by default)
    private boolean passwordVisible = false;
    private boolean maskingProviderInstalled = false;
    private ButtonWidget visibilityButton;
    private ButtonWidget resetButton;

    // Backing value for the real token (used by fallback masking)
    private String realToken = "";
    private boolean lastEmptyState = true;

    private static final String CONFIG_FILE = "save-manager-settings.json";

    // AES-GCM setup
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

    public AccountLinkingScreen(Screen parent) {
        super(Text.literal("Link Your Account"));
        this.parent = parent;
        this.networkManager = new NetworkManager();
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int fieldY = this.height / 2 - 10;

        this.tokenField = new TextFieldWidget(
                this.textRenderer,
                centerX - 100, fieldY,
                200, 20,
                Text.literal("API Key")
        );
        this.tokenField.setMaxLength(Integer.MAX_VALUE);

        // Update backing value; when effectively visible (incl. empty), we can trust widget text
        this.tokenField.setChangedListener(s -> {
            if (isEffectivelyVisible() || maskingProviderInstalled) {
                realToken = (s != null) ? s : "";
                boolean isEmptyNow = realToken.isEmpty();
                if (isEmptyNow != lastEmptyState) {
                    lastEmptyState = isEmptyNow;
                    applyMasking(); // re-evaluate when empty/non-empty flips
                }
            }
        });

        this.addSelectableChild(this.tokenField);
        this.setInitialFocus(this.tokenField);

        // Link button
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Link Account"),
                b -> openApiKeyWebsite()
        ).dimensions(centerX - 100, fieldY - 25, 200, 20).build());

        // Eye button
        this.visibilityButton = ButtonWidget.builder(
                Text.literal(""),
                b -> {
                    passwordVisible = !passwordVisible;
                    applyMasking();
                }
        ).dimensions(centerX + 105, fieldY, 25, 20).build();
        this.addDrawableChild(this.visibilityButton);

        // Reset button
        this.resetButton = ButtonWidget.builder(
                Text.literal("Reset"),
                b -> resetApiKeyAndOpenLink()
        ).dimensions(centerX + 135, fieldY, 50, 20).build();
        this.addDrawableChild(this.resetButton);

        // Save/Cancel
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Save"),
                b -> saveApiKey(getApiKeyForSave())
        ).dimensions(centerX - 100, fieldY + 30, 95, 20).build());

        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Cancel"),
                b -> this.client.setScreen(this.parent)
        ).dimensions(centerX + 5, fieldY + 30, 95, 20).build());

        // Load saved token; force visible when empty on first paint
        loadSavedApiKey();
        boolean empty = isTokenEmpty();
        lastEmptyState = empty;
        passwordVisible = empty; // always visible if empty on load
        applyMasking();
    }

    private void applyMasking() {
        boolean effectiveVisible = isEffectivelyVisible();

        if (this.visibilityButton != null) {
            this.visibilityButton.setMessage(Text.literal("👁"));
        }

        boolean providerOk = effectiveVisible
                ? installClearTextProviderReflectively()
                : installMaskingProviderReflectively();
        this.maskingProviderInstalled = providerOk;

        if (providerOk) {
            this.tokenField.setText(realToken);
            return;
        }

        if (effectiveVisible) {
            this.tokenField.setText(realToken);
        } else {
            this.tokenField.setText(mask(realToken.length()));
        }
    }

    private boolean isTokenEmpty() {
        return realToken == null || realToken.isEmpty();
    }

    private boolean isEffectivelyVisible() {
        // Always visible if empty; otherwise follow the eye toggle
        return passwordVisible || isTokenEmpty();
    }

    private boolean installMaskingProviderReflectively() {
        try {
            BiFunction<String, Integer, OrderedText> provider = (text, firstIndex) -> {
                String s = text == null ? "" : text;
                String masked = s.isEmpty() ? "" : mask(s.length());
                int start = Math.min(Math.max(firstIndex, 0), masked.length());
                return Text.literal(masked.substring(start)).asOrderedText();
            };
            var m = TextFieldWidget.class.getMethod("setRenderTextProvider", BiFunction.class);
            m.invoke(this.tokenField, provider);
            return true;
        } catch (Throwable ignored) {
        }

        try {
            Function<String, OrderedText> provider = s -> {
                String str = s == null ? "" : s;
                return Text.literal(str.isEmpty() ? "" : mask(str.length())).asOrderedText();
            };
            var m = TextFieldWidget.class.getMethod("setRenderTextProvider", Function.class);
            m.invoke(this.tokenField, provider);
            return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean installClearTextProviderReflectively() {
        try {
            BiFunction<String, Integer, OrderedText> provider = (text, firstIndex) -> {
                String s = text == null ? "" : text;
                int start = Math.min(Math.max(firstIndex, 0), s.length());
                return Text.literal(s.substring(start)).asOrderedText();
            };
            var m = TextFieldWidget.class.getMethod("setRenderTextProvider", BiFunction.class);
            m.invoke(this.tokenField, provider);
            return true;
        } catch (Throwable ignored) {
        }

        try {
            Function<String, OrderedText> provider = s -> Text.literal(s == null ? "" : s).asOrderedText();
            var m = TextFieldWidget.class.getMethod("setRenderTextProvider", Function.class);
            m.invoke(this.tokenField, provider);
            return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void resetApiKeyAndOpenLink() {
        try {
            // Always visible after reset
            passwordVisible = true;

            // Clear in-memory state and UI
            realToken = "";
            lastEmptyState = true;
            applyMasking();

            // Clear runtime API key
            this.networkManager.setApiKey(null);
            this.statusMessage = "API key cleared";

            // Remove token from JSON config
            File configDir = new File(client.runDirectory, "config");
            File configFile = new File(configDir, CONFIG_FILE);
            if (configFile.exists()) {
                boolean keptFile = false;
                JsonObject json = null;
                try (FileReader reader = new FileReader(configFile)) {
                    json = new Gson().fromJson(reader, JsonObject.class);
                } catch (Exception readEx) {
                    SaveManagerMod.LOGGER.warn("Failed reading config; deleting file", readEx);
                }
                if (json != null) {
                    json.remove("encryptedApiToken");
                    json.remove("apiToken");
                    if (!json.entrySet().isEmpty()) {
                        try (FileWriter writer = new FileWriter(configFile)) {
                            new GsonBuilder().setPrettyPrinting().create().toJson(json, writer);
                            keptFile = true;
                        }
                    }
                }
                if (!keptFile) {
                    //noinspection ResultOfMethodCallIgnored
                    configFile.delete();
                }
            }
        } catch (Exception e) {
            SaveManagerMod.LOGGER.error("Error clearing API key", e);
            this.statusMessage = "Error clearing API key";
        }

        openApiKeyWebsite();
    }

    private void loadSavedApiKey() {
        try {
            File configDir = new File(client.runDirectory, "config");
            File configFile = new File(configDir, CONFIG_FILE);

            if (configFile.exists()) {
                try (FileReader reader = new FileReader(configFile)) {
                    JsonObject json = new Gson().fromJson(reader, JsonObject.class);
                    if (json != null) {
                        String token = null;
                        if (json.has("encryptedApiToken")) {
                            token = decrypt(json.get("encryptedApiToken").getAsString());
                            this.statusMessage = "API key loaded";
                        } else if (json.has("apiToken")) {
                            token = json.get("apiToken").getAsString();
                            this.statusMessage = "API key loaded (plain)";
                        } else {
                            this.statusMessage = "No saved API key";
                        }
                        if (token != null) {
                            realToken = token;
                            this.networkManager.setApiKey(token);
                        }
                    }
                }
            }
        } catch (Exception e) {
            SaveManagerMod.LOGGER.error("Error loading API key", e);
            this.statusMessage = "Error loading saved API key";
        }
    }

    private void saveApiKey(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            this.statusMessage = "Please enter your API key";
            return;
        }

        try {
            networkManager.setApiKey(apiKey);
            String encrypted = encrypt(apiKey);

            File configDir = new File(client.runDirectory, "config");
            if (!configDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                configDir.mkdirs();
            }

            File configFile = new File(configDir, CONFIG_FILE);
            JsonObject json = new JsonObject();
            json.addProperty("encryptedApiToken", encrypted);

            try (FileWriter writer = new FileWriter(configFile)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(json, writer);
            }

            this.statusMessage = "API key saved";
            SaveManagerMod.LOGGER.info("API key saved (encrypted)");

            if (this.client != null) {
                this.client.setScreen(new SelectWorldScreen(this.parent));
            }
        } catch (Exception e)            {
            this.statusMessage = "Error: " + e.getMessage();
            SaveManagerMod.LOGGER.error("Error saving API key", e);
        }
    }

    private String getApiKeyForSave() {
        // If provider is installed, the field holds the real text; else use the backing value when hidden
        return (passwordVisible || maskingProviderInstalled) ? this.tokenField.getText() : this.realToken;
    }

    private static String mask(int len) {
        if (len <= 0) return "";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append('*');
        return sb.toString();
    }

    private String encrypt(String input) throws Exception {
        byte[] iv = new byte[IV_LEN];
        RNG.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec keySpec = new SecretKeySpec(AES_KEY, "AES");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
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
        SecretKeySpec keySpec = new SecretKeySpec(AES_KEY, "AES");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] pt = cipher.doFinal(ct);
        return new String(pt, StandardCharsets.UTF_8);
    }

    private void openApiKeyWebsite() {
        try {
            net.minecraft.util.Util.getOperatingSystem().open(networkManager.getApiTokenGenerationUrl());
        } catch (Exception e) {
            this.statusMessage = "Could not open browser";
            SaveManagerMod.LOGGER.error("Failed to open API key website", e);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 20, 0xFFFFFF);
        context.drawCenteredTextWithShadow(
                this.textRenderer,
                Text.literal("Enter your API key to link your account"),
                this.width / 2,
                this.height / 4 - 10,
                0xAAAAAA
        );

        if (!this.statusMessage.isEmpty()) {
            context.drawCenteredTextWithShadow(
                    this.textRenderer,
                    Text.literal(this.statusMessage),
                    this.width / 2,
                    this.height / 4 + 115,
                    0xFFFFFF
            );
        }

        boolean effectiveVisible = isEffectivelyVisible();

        if (maskingProviderInstalled) {
            this.tokenField.setText(realToken);
        } else {
            if (effectiveVisible) {
                this.tokenField.setText(realToken);
            } else {
                this.tokenField.setText(mask(realToken.length()));
            }
        }

        this.tokenField.render(context, mouseX, mouseY, delta);
    }
}