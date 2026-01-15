package com.choculaterie.gui;

import com.choculaterie.network.NetworkManager;
import com.choculaterie.widget.CustomButton;
import com.choculaterie.widget.ToastManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

    private CustomButton linkResetButton;

    private String currentFlowId = null;
    private String pendingLinkCode = null;
    private String pendingSaveKey = null;
    private boolean isLinking = false;
    private String linkingStatus = "";
    private ScheduledExecutorService pollExecutor = null;

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

        int btnSize = 20;
        int margin = 6;
        CustomButton backButton = new CustomButton(margin, margin, btnSize, btnSize, Text.literal("←"), b -> goBack());
        this.addDrawableChild(backButton);

        int cx = this.width / 2;
        int btnW = 100;
        int btnY = this.height / 2;

        String apiKey = loadApiKeyFromDisk();
        boolean hasKey = apiKey != null && !apiKey.isBlank();

        linkResetButton = new CustomButton(cx - (btnW / 2), btnY, btnW, 20,
                Text.literal(hasKey ? "Reset" : "Link Account"), b -> handleLinkOrReset(hasKey));
        this.addDrawableChild(linkResetButton);

        if (hasKey) {
            networkManager.setApiKey(apiKey);
        }
    }

    private void goBack() {
        if (client != null) {
            client.setScreen(new SelectWorldScreen(resolveRootParent(parent)));
        }
    }

    private void handleLinkOrReset(boolean hasKey) {
        if (hasKey) {
            networkManager.setApiKey(null);
            clearConfigToken();
            client.setScreen(new AccountLinkingScreen(parent));
        } else {
            startOAuthFlow();
        }
    }

    private void startOAuthFlow() {
        if (isLinking) {
            return;
        }

        isLinking = true;
        linkingStatus = "Initiating...";

        networkManager.initiateOAuthFlow("SaveManager Mod").whenComplete((json, err) -> {
            if (err != null) {
                runOnClient(() -> {
                    isLinking = false;
                    linkingStatus = "";
                });
                return;
            }

            try {
                currentFlowId = json.has("flowId") ? json.get("flowId").getAsString() : null;
                int expiresIn = json.has("expiresInSeconds") ? json.get("expiresInSeconds").getAsInt() : 300;

                if (currentFlowId == null) {
                    runOnClient(() -> {
                        isLinking = false;
                        linkingStatus = "";
                    });
                    return;
                }

                String authUrl = networkManager.getOAuthAuthorizeUrl(currentFlowId);

                runOnClient(() -> {
                    linkingStatus = "Waiting for approval...";
                    try {
                        net.minecraft.util.Util.getOperatingSystem().open(new java.net.URI(authUrl));
                    } catch (Exception e) {
                    }
                });

                startPolling(currentFlowId, expiresIn);

            } catch (Exception e) {
                runOnClient(() -> {
                    isLinking = false;
                    linkingStatus = "";
                });
            }
        });
    }

    private void startPolling(String flowId, int timeoutSeconds) {
        stopPolling();

        pollExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SaveManager-OAuth-Poll");
            t.setDaemon(true);
            return t;
        });

        final int[] attempts = {0};
        final int maxAttempts = timeoutSeconds / 2;
        final net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();

        pollExecutor.scheduleAtFixedRate(() -> {
            int currentAttempt = ++attempts[0];
            if (currentAttempt >= maxAttempts) {
                mc.execute(() -> {
                    stopPolling();
                    isLinking = false;
                    linkingStatus = "";
                });
                return;
            }

            networkManager.getOAuthFlowStatus(flowId).whenComplete((json, err) -> {
                if (err != null) {
                    return;
                }

                try {
                    String status = json.has("status") ? json.get("status").getAsString() : "pending";

                    if ("expired".equals(status)) {
                        mc.execute(() -> {
                            stopPolling();
                            isLinking = false;
                            linkingStatus = "";
                        });
                        return;
                    }

                    if ("pending".equals(status)) {
                        mc.execute(() -> linkingStatus = "Waiting for approval...");
                        return;
                    }

                    if ("completed".equals(status)) {
                        String saveKey = json.has("saveKey") ? json.get("saveKey").getAsString() : null;
                        boolean isMinecraftLinked = json.has("isMinecraftLinked") && json.get("isMinecraftLinked").getAsBoolean();
                        boolean minecraftLinkingComplete = json.has("minecraftLinkingComplete") && json.get("minecraftLinkingComplete").getAsBoolean();
                        String linkCode = json.has("linkCode") && !json.get("linkCode").isJsonNull() ? json.get("linkCode").getAsString() : null;

                        if (saveKey == null) {
                            return;
                        }

                        pendingSaveKey = saveKey;

                        if (isMinecraftLinked) {
                            stopPolling();
                            mc.execute(() -> completeLinking(saveKey));
                            return;
                        }

                        if (minecraftLinkingComplete) {
                            stopPolling();
                            mc.execute(() -> {
                                if (mc.getNetworkHandler() != null) {
                                    mc.getNetworkHandler().getConnection().disconnect(Text.literal("Linking complete"));
                                }
                                CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS).execute(() -> {
                                    mc.execute(() -> completeLinking(saveKey));
                                });
                            });
                            return;
                        }

                        if (linkCode != null && !linkCode.equals(pendingLinkCode)) {
                            pendingLinkCode = linkCode;
                            mc.execute(() -> {
                                linkingStatus = "Linking MC account...";
                                autoJoinServerAndLink(linkCode, saveKey);
                            });
                        }
                    }
                } catch (Exception e) {
                }
            });
        }, 0, 2, TimeUnit.SECONDS);
    }

    private void stopPolling() {
        if (pollExecutor != null && !pollExecutor.isShutdown()) {
            pollExecutor.shutdownNow();
            pollExecutor = null;
        }
    }

    private void autoJoinServerAndLink(String linkCode, String saveKey) {
        linkingStatus = "Joining server...";

        final net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();

        try {
            net.minecraft.client.network.ServerAddress serverAddress =
                net.minecraft.client.network.ServerAddress.parse("mc.choculaterie.com");
            net.minecraft.client.network.ServerInfo serverInfo =
                new net.minecraft.client.network.ServerInfo("Choculaterie", "mc.choculaterie.com", net.minecraft.client.network.ServerInfo.ServerType.OTHER);

            final String[] linkCodeRef = {linkCode};

            net.minecraft.client.gui.screen.multiplayer.ConnectScreen.connect(
                this,
                mc,
                serverAddress,
                serverInfo,
                false,
                null
            );

            CompletableFuture.delayedExecutor(6, TimeUnit.SECONDS).execute(() -> {
                mc.execute(() -> {
                    if (mc.player != null && mc.player.networkHandler != null) {
                        linkingStatus = "Sending link command...";
                        mc.player.networkHandler.sendChatCommand("link " + linkCodeRef[0]);
                    } else {
                        CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS).execute(() -> {
                            mc.execute(() -> {
                                if (mc.player != null && mc.player.networkHandler != null) {
                                    linkingStatus = "Sending link command...";
                                    mc.player.networkHandler.sendChatCommand("link " + linkCodeRef[0]);
                                }
                            });
                        });
                    }
                });
            });

        } catch (Exception e) {
            isLinking = false;
            linkingStatus = "";
        }
    }

    private void completeLinking(String saveKey) {
        stopPolling();
        isLinking = false;
        linkingStatus = "";
        pendingLinkCode = null;
        pendingSaveKey = null;
        currentFlowId = null;

        networkManager.setApiKey(saveKey);

        try {
            net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
            File configDir = new File(mc.runDirectory, "config");
            if (!configDir.exists()) configDir.mkdirs();
            File configFile = new File(configDir, CONFIG_FILE);
            JsonObject json = new JsonObject();
            json.addProperty("encryptedApiToken", encrypt(saveKey));
            try (FileWriter writer = new FileWriter(configFile)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(json, writer);
            }
        } catch (Exception e) {
        }

        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        mc.execute(() -> {
            SaveManagerScreen screen = new SaveManagerScreen(null);
            mc.setScreen(screen);
            screen.refresh();
        });
    }

    private void runOnClient(Runnable r) {
        if (client != null) {
            client.execute(r);
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
        }
    }

    private void closeScreen() {
        stopPolling();
        if (client != null) {
            String apiKey = loadApiKeyFromDisk();
            if (apiKey != null && !apiKey.isBlank()) {
                if (parent instanceof SaveManagerScreen) {
                    client.setScreen(parent);
                } else {
                    client.setScreen(new SaveManagerScreen(resolveRootParent(parent)));
                }
            } else {
                client.setScreen(new SelectWorldScreen(resolveRootParent(parent instanceof SaveManagerScreen ? ((SaveManagerScreen) parent).getParent() : parent)));
            }
        }
    }

    @Override
    public void removed() {
        super.removed();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void close() {
        goBack();
    }

    private String loadApiKeyFromDisk() {
        try {
            File configFile = new File(new File(client.runDirectory, "config"), CONFIG_FILE);
            if (!configFile.exists()) return null;
            try (FileReader reader = new FileReader(configFile)) {
                JsonObject json = new Gson().fromJson(reader, JsonObject.class);
                if (json == null) return null;
                if (json.has("encryptedApiToken")) return decrypt(json.get("encryptedApiToken").getAsString());
                if (json.has("apiToken")) return json.get("apiToken").getAsString();
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        int cx = this.width / 2;

        context.drawCenteredTextWithShadow(textRenderer, title, cx, 10, 0xFFFFFFFF);

        if (isLinking && !linkingStatus.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(linkingStatus),
                    cx, this.height / 2 + 30, 0xFF88FF88);
        }

        toastManager.render(context, delta, mouseX, mouseY);
    }


    private static String extractErrorMessage(Throwable err) {
        if (err == null) return "Unknown error";

        Throwable cause = err;
        while (cause.getCause() != null &&
               (cause instanceof java.util.concurrent.CompletionException ||
                cause instanceof java.util.concurrent.ExecutionException)) {
            cause = cause.getCause();
        }

        String msg = cause.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = cause.getClass().getSimpleName();
        }

        return msg;
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

