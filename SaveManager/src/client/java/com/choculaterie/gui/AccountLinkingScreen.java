package com.choculaterie.gui;

import com.choculaterie.network.NetworkManager;
import com.choculaterie.util.ConfigManager;
import com.choculaterie.util.ScreenUtils;
import com.choculaterie.widget.CustomButton;
import com.choculaterie.widget.ToastManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.text.Text;

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
    private ScheduledExecutorService pollExecutor = null;

    public AccountLinkingScreen(Screen parent) {
        super(Text.literal("Link Your Account"));
        this.parent = parent;
        this.toastManager = new ToastManager(null);
    }

    @Override
    protected void init() {
        toastManager.initClient(client);

        int btnSize = 20;
        int margin = 6;
        addDrawableChild(new CustomButton(margin, margin, btnSize, btnSize, Text.literal("\u2190"), b -> goBack()));

        String apiKey = ConfigManager.loadApiKey();
        boolean hasKey = apiKey != null && !apiKey.isBlank();

        int btnW = 100;
        addDrawableChild(new CustomButton(
                this.width / 2 - btnW / 2, this.height / 2, btnW, 20,
                Text.literal(hasKey ? "Reset" : "Link Account"),
                b -> handleLinkOrReset(hasKey)));

        if (hasKey) networkManager.setApiKey(apiKey);
    }

    private void handleLinkOrReset(boolean hasKey) {
        if (hasKey) {
            networkManager.setApiKey(null);
            ConfigManager.clearApiKey();
            client.setScreen(new AccountLinkingScreen(parent));
        } else {
            startOAuthFlow();
        }
    }

    private void goBack() {
        if (client == null) return;
        stopPolling();
        String apiKey = ConfigManager.loadApiKey();
        if (parent instanceof SaveManagerScreen sms) {
            if (apiKey != null && !apiKey.isBlank()) {
                client.setScreen(parent);
            } else {
                navigateToWorldSelect(sms.getParent());
            }
        } else {
            navigateToWorldSelect(parent);
        }
    }

    private void navigateToWorldSelect(Screen target) {
        if (target instanceof SelectWorldScreen) {
            client.setScreen(target);
        } else {
            client.setScreen(new SelectWorldScreen(ScreenUtils.resolveRootParent(target)));
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
                    linkingStatus = "Waiting for approval...";
                    try { net.minecraft.util.Util.getOperatingSystem().open(new java.net.URI(authUrl)); }
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
        final var mc = net.minecraft.client.MinecraftClient.getInstance();

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

    private void handlePollResponse(com.google.gson.JsonObject json, net.minecraft.client.MinecraftClient mc) {
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

    private void handleCompleted(com.google.gson.JsonObject json, net.minecraft.client.MinecraftClient mc) {
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
                if (mc.getNetworkHandler() != null) {
                    mc.getNetworkHandler().getConnection().disconnect(Text.literal("Linking complete"));
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
        final var mc = net.minecraft.client.MinecraftClient.getInstance();
        try {
            var serverAddress = net.minecraft.client.network.ServerAddress.parse("mc.choculaterie.com");
            var serverInfo = new net.minecraft.client.network.ServerInfo(
                    "Choculaterie", "mc.choculaterie.com", net.minecraft.client.network.ServerInfo.ServerType.OTHER);

            net.minecraft.client.gui.screen.multiplayer.ConnectScreen.connect(this, mc, serverAddress, serverInfo, false, null);

            scheduleLinkCommand(mc, linkCode, 6);
        } catch (Exception e) {
            isLinking = false;
            linkingStatus = "";
        }
    }

    private void scheduleLinkCommand(net.minecraft.client.MinecraftClient mc, String linkCode, int delaySeconds) {
        CompletableFuture.delayedExecutor(delaySeconds, TimeUnit.SECONDS).execute(() -> mc.execute(() -> {
            if (mc.player != null && mc.player.networkHandler != null) {
                linkingStatus = "Sending link command...";
                mc.player.networkHandler.sendChatCommand("link " + linkCode);
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

        var mc = net.minecraft.client.MinecraftClient.getInstance();
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
        if (client != null) client.execute(r);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int cx = this.width / 2;
        context.drawCenteredTextWithShadow(textRenderer, title, cx, 10, 0xFFFFFFFF);
        if (!linkingStatus.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(linkingStatus), cx, this.height / 2 + 30, 0xFF88FF88);
        }
        toastManager.render(context, delta, mouseX, mouseY);
    }

    @Override
    public boolean shouldCloseOnEsc() { return true; }

    @Override
    public void close() { goBack(); }
}
