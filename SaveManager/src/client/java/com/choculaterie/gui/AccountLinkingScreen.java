package com.choculaterie.gui;

import com.choculaterie.SaveManagerMod;
import com.choculaterie.network.NetworkManager;
import com.choculaterie.util.AccountState;
import com.choculaterie.util.ConfigManager;
import com.choculaterie.vanilib.util.ScreenUtils;
import com.choculaterie.vanilib.gui.widget.CustomButton;
import com.choculaterie.vanilib.gui.widget.CustomTextField;
import com.choculaterie.vanilib.gui.widget.ToastManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;

import net.minecraft.client.input.MouseButtonEvent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AccountLinkingScreen extends Screen {
    private final Screen parent;
    private final NetworkManager networkManager = new NetworkManager();
    private final ToastManager toastManager;

    private String currentFlowId = null;
    private String currentPollToken = null;
    private String pendingLinkCode = null;
    private String pendingSaveKey = null;
    private boolean isLinking = false;
    private String linkingStatus = "";
    private String pendingAuthUrl = null;
    private ScheduledExecutorService pollExecutor = null;
    private static final int PREMIUM_ACCENT = 0xFFE879F9;

    private int premiumLinkX = -1;
    private int premiumLinkY = -1;
    private int premiumLinkW = 0;

    private CustomButton linkBtn = null;
    private CustomButton copyUrlBtn = null;
    private CustomTextField manualKeyField = null;
    private CustomButton applyKeyBtn = null;

    public AccountLinkingScreen(Screen parent) {
        super(Component.literal("Link Your Account"));
        this.parent = parent;
        this.toastManager = new ToastManager(net.minecraft.client.Minecraft.getInstance());
    }

    @Override
    protected void init() {

        int btnSize = 20, margin = 6;
        addRenderableWidget(
                new CustomButton(margin, margin, btnSize, btnSize, Component.literal("\u2190"), b -> goBack()));

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

        int fieldW = Math.min(240, this.width - 80), applyW = 50;
        int fieldX = cx - (fieldW + applyW + 4) / 2;
        manualKeyField = new CustomTextField(minecraft, fieldX, btnY + 60, fieldW, 16, Component.empty());
        manualKeyField.setPlaceholder(Component.literal("Paste save key here..."));
        manualKeyField.setMaxLength(128);
        manualKeyField.setOnEnterPressed(this::applyManualKey);
        manualKeyField.visible = false;
        addRenderableWidget(manualKeyField);

        applyKeyBtn = new CustomButton(fieldX + fieldW + 4, btnY + 60, applyW, 16,
                Component.literal("Apply"), b -> applyManualKey());
        applyKeyBtn.visible = false;
        addRenderableWidget(applyKeyBtn);

        if (hasKey) {
            networkManager.setApiKey(apiKey);
            refreshAccountState();
        }
    }

    private void openPremiumPage() {
        try {
            String user = AccountState.username();
            String url = user.isBlank()
                    ? "https://choculaterie.com/premium"
                    : "https://choculaterie.com/users/"
                            + java.net.URLEncoder.encode(user, java.nio.charset.StandardCharsets.UTF_8)
                                    .replace("+", "%20")
                            + "?tab=5&section=premium";
            net.minecraft.util.Util.getPlatform().openUri(new java.net.URI(url));
        } catch (Exception e) {
            SaveManagerMod.LOGGER.warn("[SM] premium link: failed to open - {}", e.toString());
        }
    }

    private void refreshAccountState() {
        networkManager.getQuotaInfo().whenComplete((json, err) -> {
            if (err == null)
                AccountState.update(json);
        });
    }

    private void handleLinkOrReset(boolean hasKey) {
        if (hasKey) {
            networkManager.setApiKey(null);
            ConfigManager.clearApiKey();
            minecraft.gui.setScreen(new AccountLinkingScreen(parent));
        } else {
            startOAuthFlow();
        }
    }

    private void goBack() {
        if (minecraft == null)
            return;
        stopPolling();
        String apiKey = ConfigManager.loadApiKey();
        if (parent instanceof SaveManagerScreen sms) {
            if (apiKey != null && !apiKey.isBlank()) {
                minecraft.gui.setScreen(parent);
            } else {
                navigateToWorldSelect(sms.getParent());
            }
        } else {
            navigateToWorldSelect(parent);
        }
    }

    private void navigateToWorldSelect(Screen target) {
        if (target instanceof SelectWorldScreen) {
            minecraft.gui.setScreen(target);
        } else {
            minecraft.gui.setScreen(new SelectWorldScreen(ScreenUtils.resolveRootParent(target)));
        }
    }

    private void copyAuthUrl() {
        if (pendingAuthUrl != null && minecraft.keyboardHandler != null) {
            minecraft.keyboardHandler.setClipboard(pendingAuthUrl);
            toastManager.showSuccess("URL copied! Paste it in your browser.");
        }
    }

    private void applyManualKey() {
        if (manualKeyField == null)
            return;
        String key = manualKeyField.getValue().trim();
        if (key.isEmpty()) {
            toastManager.showError("Paste your save key first.");
            return;
        }
        stopPolling();
        completeLinking(key);
    }

    private void startOAuthFlow() {
        if (isLinking)
            return;
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
                currentPollToken = json.has("pollToken") ? json.get("pollToken").getAsString() : null;
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
                    pendingAuthUrl = authUrl;
                    if (linkBtn != null)
                        linkBtn.visible = false;
                    if (copyUrlBtn != null)
                        copyUrlBtn.visible = true;
                    if (manualKeyField != null)
                        manualKeyField.visible = true;
                    if (applyKeyBtn != null)
                        applyKeyBtn.visible = true;
                    linkingStatus = "Waiting for approval...";
                    try {
                        net.minecraft.util.Util.getPlatform().openUri(new java.net.URI(authUrl));
                    } catch (Exception ignored) {
                    }
                });
                startPolling(currentFlowId, currentPollToken, expiresIn);
            } catch (Exception e) {
                runOnClient(() -> {
                    isLinking = false;
                    linkingStatus = "";
                });
            }
        });
    }

    private void startPolling(String flowId, String pollToken, int timeoutSeconds) {
        stopPolling();
        pollExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SaveManager-OAuth-Poll");
            t.setDaemon(true);
            return t;
        });

        final int[] attempts = { 0 };
        final int maxAttempts = timeoutSeconds / 2;
        final var mc = net.minecraft.client.Minecraft.getInstance();

        pollExecutor.scheduleAtFixedRate(() -> {
            if (++attempts[0] >= maxAttempts) {
                mc.execute(() -> {
                    stopPolling();
                    isLinking = false;
                    linkingStatus = "";
                });
                return;
            }
            networkManager.getOAuthFlowStatus(flowId, pollToken).whenComplete((json, err) -> {
                if (err != null)
                    return;
                try {
                    handlePollResponse(json, mc);
                } catch (Exception ignored) {
                }
            });
        }, 0, 2, TimeUnit.SECONDS);
    }

    private void handlePollResponse(com.google.gson.JsonObject json, net.minecraft.client.Minecraft mc) {
        String status = json.has("status") ? json.get("status").getAsString() : "pending";

        switch (status) {
            case "expired" -> mc.execute(() -> {
                stopPolling();
                isLinking = false;
                linkingStatus = "";
                resetFlowUI();
            });
            case "cancelled" -> {
                mc.execute(() -> {
                    stopPolling();
                    isLinking = false;
                    linkingStatus = "\u00a7cCancelled";
                    resetFlowUI();
                });
                CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS)
                        .execute(() -> mc.execute(() -> linkingStatus = ""));
            }
            case "pending" -> mc.execute(() -> linkingStatus = "Waiting for approval...");
            case "completed" -> handleCompleted(json, mc);
        }
    }

    private void handleCompleted(com.google.gson.JsonObject json, net.minecraft.client.Minecraft mc) {
        String saveKey = json.has("saveKey") ? json.get("saveKey").getAsString() : null;
        if (saveKey == null)
            return;

        boolean isMinecraftLinked = json.has("isMinecraftLinked") && json.get("isMinecraftLinked").getAsBoolean();
        boolean linkingComplete = json.has("minecraftLinkingComplete")
                && json.get("minecraftLinkingComplete").getAsBoolean();
        String linkCode = json.has("linkCode") && !json.get("linkCode").isJsonNull()
                ? json.get("linkCode").getAsString()
                : null;

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
            var serverAddress = net.minecraft.client.multiplayer.resolver.ServerAddress
                    .parseString("mc.choculaterie.com");
            var serverInfo = new net.minecraft.client.multiplayer.ServerData(
                    "Choculaterie", "mc.choculaterie.com", net.minecraft.client.multiplayer.ServerData.Type.OTHER);

            net.minecraft.client.gui.screens.ConnectScreen.startConnecting(this, mc, serverAddress, serverInfo, false,
                    null);

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
        currentPollToken = null;

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
            mc.gui.setScreen(screen);
            screen.refresh();
        });
    }

    private void runOnClient(Runnable r) {
        if (minecraft != null)
            minecraft.execute(r);
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
            String who = AccountState.username().isBlank()
                    ? "Account linked \u2713"
                    : "Linked as " + AccountState.username();
            context.centeredText(font, Component.literal("\u00a7a" + who), cx, btnY - 44, 0xFFFFFFFF);

            if (AccountState.isKnown()) {
                String plan = AccountState.isPremium() ? "Premium" : "Free";
                int planColor = AccountState.isPremium() ? 0xFFFFD257 : 0xFF999999;
                context.centeredText(font, Component.literal(plan), cx, btnY - 32, planColor);

                String storage = AccountState.usedFormatted().isEmpty()
                        ? AccountState.quotaFormatted()
                        : AccountState.usedFormatted() + " of " + AccountState.quotaFormatted() + " used";
                if (!storage.isBlank())
                    context.centeredText(font, Component.literal(storage), cx, btnY - 20, 0xFFCCCCCC);
            }

            if (AccountState.hasAutoSync()) {
                premiumLinkX = -1;
                context.centeredText(font,
                        Component.literal("Auto sync is on for worlds you favourite with the star."),
                        cx, btnY + 30, 0xFF888888);
            } else {
                String word = "Premium";
                String rest = " adds auto sync and version history.";
                int wordW = font.width(word);
                int startX = cx - (wordW + font.width(rest)) / 2;
                context.text(font, word, startX, btnY + 30, PREMIUM_ACCENT, false);
                context.text(font, rest, startX + wordW, btnY + 30, 0xFF888888, false);
                premiumLinkX = startX;
                premiumLinkY = btnY + 30;
                premiumLinkW = wordW;
            }
            context.centeredText(font,
                    Component.literal("Reset to unlink and connect a different account."),
                    cx, btnY + 42, 0xFF888888);
        } else if (!isLinking) {
            int stepY = btnY + 32;
            int lineH = 12;
            context.centeredText(font, Component.literal("How it works:"), cx, stepY, 0xFF999999);
            stepY += lineH + 4;
            context.centeredText(font, Component.literal("1. A browser window will open. Sign in and click Approve."),
                    cx, stepY, 0xFFCCCCCC);
            stepY += lineH;
            context.centeredText(font,
                    Component.literal("2. The game will briefly join a server to verify your Minecraft account."), cx,
                    stepY, 0xFFCCCCCC);
            stepY += lineH;
            context.centeredText(font, Component.literal("3. Once verified, you're ready to sync your saves!"), cx,
                    stepY, 0xFFCCCCCC);
        } else {
            if (!linkingStatus.isEmpty()) {
                context.centeredText(font,
                        Component.literal(linkingStatus), cx, btnY - 20, 0xFF88FF88);
            }
            if (pendingAuthUrl != null) {
                context.centeredText(font,
                        Component.literal("Browser didn't open? Copy the URL and paste it manually."),
                        cx, btnY + 30, 0xFF888888);
                context.centeredText(font,
                        Component.literal("Or paste your save key manually if the mod didn't receive it:"),
                        cx, btnY + 44, 0xFF888888);
            }
        }

        toastManager.render(context, delta, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (toastManager.mouseClicked(click.x(), click.y())) return true;
        if (premiumLinkX >= 0
                && click.x() >= premiumLinkX && click.x() <= premiumLinkX + premiumLinkW
                && click.y() >= premiumLinkY - 2 && click.y() <= premiumLinkY + 11) {
            openPremiumPage();
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        goBack();
    }

    private void resetFlowUI() {
        pendingAuthUrl = null;
        if (linkBtn != null) linkBtn.visible = true;
        if (copyUrlBtn != null) copyUrlBtn.visible = false;
        if (manualKeyField != null) manualKeyField.visible = false;
        if (applyKeyBtn != null) applyKeyBtn.visible = false;
    }
}
