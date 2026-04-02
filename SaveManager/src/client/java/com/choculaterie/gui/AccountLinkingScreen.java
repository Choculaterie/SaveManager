package com.choculaterie.gui;

import com.choculaterie.network.NetworkManager;
import com.choculaterie.util.ConfigManager;
import com.choculaterie.util.ScreenUtils;
import com.choculaterie.widget.CustomButton;
import com.choculaterie.widget.ToastManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AccountLinkingScreen extends Screen {
    private final Screen parent;
    private final NetworkManager networkManager = new NetworkManager();
    private final ToastManager toastManager;

    private String currentFlowId = null;
    private String pendingLinkCode = null;
    private String pendingSaveKey = null;
    private boolean isLinking = false;
    private String linkingStatus = "";
    private String pendingAuthUrl = null;
    private ScheduledExecutorService pollExecutor = null;
    private CustomButton linkBtn = null;
    private CustomButton copyUrlBtn = null;

    public AccountLinkingScreen(Screen parent) {
        super(Component.literal("Link Your Account"));
        this.parent = parent;
        this.toastManager = new ToastManager(null);
    }

    @Override
    protected void init() {
        toastManager.initClient(minecraft);

        int btnSize = 20, margin = 6;
        addRenderableWidget(new CustomButton(margin, margin, btnSize, btnSize, Component.literal("\u2190"), b -> goBack()));

        String apiKey = ConfigManager.loadApiKey();
        boolean hasKey = apiKey != null && !apiKey.isBlank();

        int cx = this.width / 2, btnW = 100;
        int btnY = this.height / 2 - 10;
        linkBtn = new CustomButton(cx - btnW / 2, btnY, btnW, 20,
                Component.literal(hasKey ? "Reset" : "Link Account"),
                b -> handleLinkOrReset(hasKey));
        addRenderableWidget(linkBtn);

        copyUrlBtn = new CustomButton(cx - btnW / 2, btnY, btnW, 20,
                Component.literal("Copy URL"), b -> copyAuthUrl());
        copyUrlBtn.visible = false;
        addRenderableWidget(copyUrlBtn);

        if (hasKey) networkManager.setApiKey(apiKey);
    }

    private void handleLinkOrReset(boolean hasKey) {
        if (hasKey) {
            networkManager.setApiKey(null);
            ConfigManager.clearApiKey();
            minecraft.setScreen(new AccountLinkingScreen(parent));
        } else {
            startOAuthFlow();
        }
    }

    private void goBack() {
        if (minecraft == null) return;
        stopPolling();
        String apiKey = ConfigManager.loadApiKey();
        if (parent instanceof SaveManagerScreen sms) {
            if (apiKey != null && !apiKey.isBlank()) {
                minecraft.setScreen(parent);
            } else {
                navigateToWorldSelect(sms.getParent());
            }
        } else {
            navigateToWorldSelect(parent);
        }
    }

    private void navigateToWorldSelect(Screen target) {
        if (target instanceof SelectWorldScreen) {
            minecraft.setScreen(target);
        } else {
            minecraft.setScreen(new SelectWorldScreen(ScreenUtils.resolveRootParent(target)));
        }
    }

    private void copyAuthUrl() {
        if (pendingAuthUrl != null && minecraft.keyboardHandler != null) {
            minecraft.keyboardHandler.setClipboard(pendingAuthUrl);
            toastManager.showSuccess("URL copied! Paste it in your browser.");
        }
    }

    private void startOAuthFlow() {
        if (isLinking) return;
        isLinking = true;
        linkingStatus = "Initiating...";

        networkManager.initiateOAuthFlow("SaveManager Mod").whenComplete((json, err) -> {
            if (err != null) {
                runOnClient(() -> { isLinking = false; linkingStatus = ""; });
                return;
            }
            try {
                currentFlowId = json.has("flowId") ? json.get("flowId").getAsString() : null;
                int expiresIn = json.has("expiresInSeconds") ? json.get("expiresInSeconds").getAsInt() : 300;
                if (currentFlowId == null) {
                    runOnClient(() -> { isLinking = false; linkingStatus = ""; });
                    return;
                }
                String authUrl = networkManager.getOAuthAuthorizeUrl(currentFlowId);
                runOnClient(() -> {
                    pendingAuthUrl = authUrl;
                    if (linkBtn != null) linkBtn.visible = false;
                    if (copyUrlBtn != null) copyUrlBtn.visible = true;
                    linkingStatus = "Waiting for approval...";
                    try { net.minecraft.util.Util.getPlatform().openUri(new java.net.URI(authUrl)); }
                    catch (Exception ignored) {}
                });
                startPolling(currentFlowId, expiresIn);
            } catch (Exception e) {
                runOnClient(() -> { isLinking = false; linkingStatus = ""; });
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
        final var mc = net.minecraft.client.Minecraft.getInstance();

        pollExecutor.scheduleAtFixedRate(() -> {
            if (++attempts[0] >= maxAttempts) {
                mc.execute(() -> { stopPolling(); isLinking = false; linkingStatus = ""; });
                return;
            }
            networkManager.getOAuthFlowStatus(flowId).whenComplete((json, err) -> {
                if (err != null) return;
                try {
                    handlePollResponse(json, mc);
                } catch (Exception ignored) {}
            });
        }, 0, 2, TimeUnit.SECONDS);
    }

    private void handlePollResponse(com.google.gson.JsonObject json, net.minecraft.client.Minecraft mc) {
        String status = json.has("status") ? json.get("status").getAsString() : "pending";

        switch (status) {
            case "expired" -> mc.execute(() -> { stopPolling(); isLinking = false; linkingStatus = ""; });
            case "cancelled" -> {
                mc.execute(() -> { stopPolling(); isLinking = false; linkingStatus = "\u00a7cCancelled"; });
                CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS)
                        .execute(() -> mc.execute(() -> linkingStatus = ""));
            }
            case "pending" -> mc.execute(() -> linkingStatus = "Waiting for approval...");
            case "completed" -> handleCompleted(json, mc);
        }
    }

    private void handleCompleted(com.google.gson.JsonObject json, net.minecraft.client.Minecraft mc) {
        String saveKey = json.has("saveKey") ? json.get("saveKey").getAsString() : null;
        if (saveKey == null) return;

        boolean isMinecraftLinked = json.has("isMinecraftLinked") && json.get("isMinecraftLinked").getAsBoolean();
        boolean linkingComplete = json.has("minecraftLinkingComplete") && json.get("minecraftLinkingComplete").getAsBoolean();
        String linkCode = json.has("linkCode") && !json.get("linkCode").isJsonNull() ? json.get("linkCode").getAsString() : null;

        pendingSaveKey = saveKey;

        if (isMinecraftLinked) {
            stopPolling();
            mc.execute(() -> completeLinking(saveKey));
            return;
        }
        if (linkingComplete) {
            stopPolling();
            mc.execute(() -> {
                if (mc.getConnection() != null) {
                    mc.getConnection().getConnection().disconnect(Component.literal("Linking complete"));
                }
                CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS)
                        .execute(() -> mc.execute(() -> completeLinking(saveKey)));
            });
            return;
        }
        if (linkCode != null && !linkCode.equals(pendingLinkCode)) {
            pendingLinkCode = linkCode;
            mc.execute(() -> {
                linkingStatus = "Linking MC account...";
                autoJoinServerAndLink(linkCode);
            });
        }
    }

    private void stopPolling() {
        if (pollExecutor != null && !pollExecutor.isShutdown()) {
            pollExecutor.shutdownNow();
            pollExecutor = null;
        }
    }

    private void autoJoinServerAndLink(String linkCode) {
        linkingStatus = "Joining server...";
        final var mc = net.minecraft.client.Minecraft.getInstance();
        try {
            var serverAddress = net.minecraft.client.multiplayer.resolver.ServerAddress.parseString("mc.choculaterie.com");
            var serverInfo = new net.minecraft.client.multiplayer.ServerData(
                    "Choculaterie", "mc.choculaterie.com", net.minecraft.client.multiplayer.ServerData.Type.OTHER);

            net.minecraft.client.gui.screens.ConnectScreen.startConnecting(this, mc, serverAddress, serverInfo, false, null);

            scheduleLinkCommand(mc, linkCode, 6);
        } catch (Exception e) {
            isLinking = false;
            linkingStatus = "";
        }
    }

    private void scheduleLinkCommand(net.minecraft.client.Minecraft mc, String linkCode, int delaySeconds) {
        CompletableFuture.delayedExecutor(delaySeconds, TimeUnit.SECONDS).execute(() -> mc.execute(() -> {
            if (mc.player != null && mc.player.connection != null) {
                linkingStatus = "Sending link command...";
                mc.player.connection.sendCommand("link " + linkCode);
            } else if (delaySeconds == 6) {
                scheduleLinkCommand(mc, linkCode, 3);
            }
        }));
    }

    private void completeLinking(String saveKey) {
        stopPolling();
        isLinking = false;
        linkingStatus = "";
        pendingLinkCode = null;
        pendingSaveKey = null;
        currentFlowId = null;

        networkManager.setApiKey(saveKey);
        ConfigManager.saveApiKey(saveKey);

        var mc = net.minecraft.client.Minecraft.getInstance();
        mc.execute(() -> {
            SaveManagerScreen screen;
            if (parent instanceof SaveManagerScreen sms) {
                screen = sms;
            } else {
                screen = new SaveManagerScreen(parent instanceof SelectWorldScreen ? parent : parent);
            }
            mc.setScreen(screen);
            screen.refresh();
        });
    }

    private void runOnClient(Runnable r) {
        if (minecraft != null) minecraft.execute(r);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int btnY = this.height / 2 - 10;

        context.centeredText(font, title, cx, 10, 0xFFFFFFFF);

        String apiKey = ConfigManager.loadApiKey();
        boolean hasKey = apiKey != null && !apiKey.isBlank();

        if (hasKey && !isLinking) {
            context.centeredText(font,
                    Component.literal("\u00a7aAccount linked \u2713"), cx, btnY - 20, 0xFFFFFFFF);
            context.centeredText(font,
                    Component.literal("Reset to unlink and connect a different account."),
                    cx, btnY + 30, 0xFF888888);
        } else if (!isLinking) {
            int stepY = btnY + 32;
            int lineH = 12;
            context.centeredText(font, Component.literal("How it works:"), cx, stepY, 0xFF999999);
            stepY += lineH + 4;
            context.centeredText(font, Component.literal("1. A browser window will open. Sign in and click Approve."), cx, stepY, 0xFFCCCCCC);
            stepY += lineH;
            context.centeredText(font, Component.literal("2. The game will briefly join a server to verify your Minecraft account."), cx, stepY, 0xFFCCCCCC);
            stepY += lineH;
            context.centeredText(font, Component.literal("3. Once verified, you're ready to sync your saves!"), cx, stepY, 0xFFCCCCCC);
        } else {
            if (!linkingStatus.isEmpty()) {
                context.centeredText(font,
                        Component.literal(linkingStatus), cx, btnY - 20, 0xFF88FF88);
            }
            if (pendingAuthUrl != null) {
                context.centeredText(font,
                        Component.literal("Browser didn't open? Copy the URL and paste it manually."),
                        cx, btnY + 30, 0xFF888888);
            }
        }

        toastManager.render(context, delta, mouseX, mouseY);
    }

    @Override
    public boolean shouldCloseOnEsc() { return true; }

    @Override
    public void onClose() { goBack(); }
}
