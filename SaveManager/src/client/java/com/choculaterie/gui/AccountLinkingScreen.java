package com.choculaterie.gui;

import com.choculaterie.SaveManagerMod;
import com.choculaterie.network.NetworkManager;
import com.choculaterie.util.AccountState;
import com.choculaterie.util.ConfigManager;
import com.choculaterie.vanilib.util.ScreenUtils;
import com.choculaterie.vanilib.gui.theme.ButtonStyle;
import com.choculaterie.vanilib.gui.theme.UIDraw;
import com.choculaterie.vanilib.gui.theme.UIIcon;
import com.choculaterie.vanilib.gui.theme.UITheme;
import com.choculaterie.vanilib.gui.widget.ConfirmPopup;
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

    private CustomButton linkBtn = null, indicatorBtn, backgroundBtn;
    private int accountRowY, preferencesRowY;
    private CustomButton copyUrlBtn = null;
    private ConfirmPopup confirmPopup = null;
    private CustomTextField manualKeyField = null;
    private CustomButton applyKeyBtn = null;

    public AccountLinkingScreen(Screen parent) {
        super(Component.literal("Link Your Account"));
        this.parent = parent;
        this.toastManager = new ToastManager(net.minecraft.client.Minecraft.getInstance());
    }

    @Override
    protected void init() {

        int btnSize = UITheme.Dimensions.BUTTON_HEIGHT, margin = UITheme.Dimensions.PADDING_SMALL + 1;
        addRenderableWidget(
                new CustomButton(margin, margin, btnSize, btnSize, Component.literal(""), b -> goBack()).setIcon(UIIcon.ARROW_LEFT));

        String apiKey = ConfigManager.loadApiKey();
        boolean hasKey = apiKey != null && !apiKey.isBlank();

        int cx = this.width / 2, btnW = 120;
        int contentH = hasKey ? 174 : 132;
        accountRowY = Math.max(36, (this.height - contentH) / 2);
        preferencesRowY = accountRowY + 108;

        int rowH = UITheme.Dimensions.BUTTON_HEIGHT;
        linkBtn = new CustomButton(cx - btnW / 2, accountRowY + 52, btnW, rowH,
                Component.literal(hasKey ? "Unlink Account" : "Link Account"),
                b -> handleLinkOrReset(hasKey));
        linkBtn.setStyle(hasKey ? ButtonStyle.DANGER : ButtonStyle.PRIMARY).setIcon(hasKey ? UIIcon.LOGOUT : UIIcon.GLOBE);
        addRenderableWidget(linkBtn);

        copyUrlBtn = new CustomButton(cx - btnW / 2, accountRowY + 52, btnW, rowH,
                Component.literal("Copy URL"), b -> copyAuthUrl());
        copyUrlBtn.setIcon(UIIcon.CLIPBOARD);
        copyUrlBtn.visible = false;
        addRenderableWidget(copyUrlBtn);

        int fieldW = Math.min(240, this.width - 80), applyW = 58;
        int fieldX = cx - (fieldW + applyW + 4) / 2;
        manualKeyField = new CustomTextField(minecraft, fieldX, accountRowY + 80, fieldW, rowH, Component.empty());
        manualKeyField.setPlaceholder(Component.literal("Paste save key here..."));
        manualKeyField.setMaxLength(128);
        manualKeyField.setOnEnterPressed(this::applyManualKey);
        manualKeyField.visible = false;
        addRenderableWidget(manualKeyField);

        applyKeyBtn = new CustomButton(fieldX + fieldW + 4, accountRowY + 80, applyW, rowH,
                Component.literal("Apply"), b -> applyManualKey());
        applyKeyBtn.setIcon(UIIcon.CHECK).setStyle(ButtonStyle.PRIMARY);
        applyKeyBtn.visible = false;
        addRenderableWidget(applyKeyBtn);

        int prefW = Math.max(font.width("Sync message: Off"), font.width("Background: Transparent")) + 16;
        indicatorBtn = new CustomButton(cx - prefW - 2, preferencesRowY + 22, prefW, rowH,
                Component.literal(indicatorLabel()), b -> toggleSyncIndicator());
        indicatorBtn.visible = hasKey;
        addRenderableWidget(indicatorBtn);

        backgroundBtn = new CustomButton(cx + 2, preferencesRowY + 22, prefW, rowH,
                Component.literal(backgroundLabel()), b -> toggleBackground());
        backgroundBtn.visible = hasKey;
        addRenderableWidget(backgroundBtn);

        if (hasKey) {
            networkManager.setApiKey(apiKey);
            refreshAccountState();
        }
    }

    private void drawDivider(GuiGraphicsExtractor context, int cx, int y) {
        int half = Math.min(150, this.width / 2 - 20);
        UIDraw.hDivider(context, cx - half, cx + half, y, UITheme.Colors.DIVIDER_LIGHT);
    }

    private static String backgroundLabel() {
        return ConfigManager.isSolidBackground() ? "Background: Solid" : "Background: Transparent";
    }

    private void toggleBackground() {
        ConfigManager.setSolidBackground(!ConfigManager.isSolidBackground());
        backgroundBtn.setMessage(Component.literal(backgroundLabel()));
    }

    private static String indicatorLabel() {
        return ConfigManager.isSyncIndicatorEnabled() ? "Sync message: On" : "Sync message: Off";
    }

    private void toggleSyncIndicator() {
        ConfigManager.setSyncIndicatorEnabled(!ConfigManager.isSyncIndicatorEnabled());
        if (indicatorBtn != null)
            indicatorBtn.setMessage(Component.literal(indicatorLabel()));
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
            com.mojang.blaze3d.Blaze3D.openUri(new java.net.URI(url));
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
            confirmPopup = new ConfirmPopup(this, "Unlink Account?",
                    "Unlinking keeps your cloud saves. Link again any time to get them back.",
                    () -> {
                        confirmPopup = null;
                        networkManager.setApiKey(null);
                        ConfigManager.clearApiKey();
                        SaveManagerScreen.resetAccountCaches();
                        minecraft.gui.setScreen(new AccountLinkingScreen(parent));
                    }, () -> confirmPopup = null, "Unlink").setConfirmStyle(ButtonStyle.DANGER);
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
            Screen grandParent = ((com.choculaterie.mixin.SelectWorldScreenAccessor) target).getParentScreen();
            minecraft.gui.setScreen(new SelectWorldScreen(java.util.Objects.requireNonNull(grandParent)));
        } else {
            minecraft.gui.setScreen(new SelectWorldScreen(ScreenUtils.resolveRootParent(target)));
        }
    }

    private void copyAuthUrl() {
        if (pendingAuthUrl != null && minecraft.keyboardHandler != null) {
            minecraft.keyboardHandler.setClipboard(pendingAuthUrl);
            copyUrlBtn.showSuccess("Copied");
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
        applyKeyBtn.active = false;
        networkManager.setApiKey(key);
        networkManager.getQuotaInfo().whenComplete((json, err) -> runOnClient(() -> {
            if (err != null || json == null) {
                applyKeyBtn.active = true;
                networkManager.setApiKey(ConfigManager.loadApiKey());
                applyKeyBtn.showError("Invalid");
                return;
            }
            applyKeyBtn.showSuccess("Applied");
            CompletableFuture.delayedExecutor(700, TimeUnit.MILLISECONDS)
                    .execute(() -> runOnClient(() -> {
                        completeLinking(key);
                        AccountState.update(json);
                    }));
        }));
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
                        com.mojang.blaze3d.Blaze3D.openUri(new java.net.URI(authUrl));
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
        SaveManagerScreen.resetAccountCaches();

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
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        if (ConfigManager.isSolidBackground()) {
            context.fill(0, 0, this.width, this.height, UITheme.Colors.BACKGROUND);
        } else {
            super.extractBackground(context, mouseX, mouseY, delta);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, confirmPopup != null ? -1 : mouseX, confirmPopup != null ? -1 : mouseY, delta);
        int cx = this.width / 2;

        UIDraw.centeredText(context, title, cx, UIDraw.flatContentY(UITheme.Dimensions.PADDING_SMALL + 1, UITheme.Dimensions.BUTTON_HEIGHT), UITheme.Colors.TEXT_HIGHLIGHT);

        String apiKey = ConfigManager.loadApiKey();
        boolean hasKey = apiKey != null && !apiKey.isBlank();

        if (hasKey && !isLinking) {
            UIDraw.centeredText(context, Component.literal("Account"), cx, accountRowY, UITheme.Colors.ACCENT);
            drawDivider(context, cx, accountRowY + 10);

            String who = AccountState.username().isBlank() ? "Linked" : AccountState.username();
            UIDraw.centeredText(context, Component.literal(who), cx, accountRowY + 22, UITheme.Colors.SUCCESS_TEXT);

            if (AccountState.isKnown()) {
                String plan = AccountState.isPremium() ? "Premium" : "Free";
                int planColor = AccountState.isPremium() ? UITheme.Palette.GOLD : UITheme.Colors.TEXT_MUTED;
                UIDraw.centeredText(context, Component.literal(plan), cx, accountRowY + 36, planColor);
            }

            UIDraw.centeredText(context, Component.literal("Preferences"), cx, preferencesRowY, UITheme.Colors.ACCENT);
            drawDivider(context, cx, preferencesRowY + 10);

            if (AccountState.hasAutoSync()) {
                premiumLinkX = -1;
                UIDraw.centeredText(context,
                        Component.literal("Favourited worlds sync automatically."),
                        cx, preferencesRowY + 56, UITheme.Colors.TEXT_MUTED);
            } else {
                String word = "Premium";
                String rest = " adds auto sync and version history.";
                int wordW = font.width(word);
                int startX = cx - (wordW + font.width(rest)) / 2;
                UIDraw.text(context, word, startX, preferencesRowY + 56, PREMIUM_ACCENT);
                UIDraw.text(context, rest, startX + wordW, preferencesRowY + 56, UITheme.Colors.TEXT_MUTED);
                premiumLinkX = startX;
                premiumLinkY = preferencesRowY + 56;
                premiumLinkW = wordW;
            }

        } else if (isLinking) {
            if (!linkingStatus.isEmpty()) {
                UIDraw.centeredText(context, Component.literal(linkingStatus),
                        cx, accountRowY + 34, UITheme.Colors.SUCCESS_TEXT);
            }
            if (pendingAuthUrl != null) {
                UIDraw.centeredText(context,
                        Component.literal("Browser didn't open? Copy the URL and paste it manually."),
                        cx, accountRowY + 108, UITheme.Colors.TEXT_MUTED);
                UIDraw.centeredText(context,
                        Component.literal("Or paste your save key manually if the mod didn't receive it:"),
                        cx, accountRowY + 122, UITheme.Colors.TEXT_MUTED);
            }
        } else {
            UIDraw.centeredText(context, Component.literal("Getting started"), cx, accountRowY, UITheme.Colors.ACCENT);
            drawDivider(context, cx, accountRowY + 10);

            UIDraw.centeredText(context,
                    Component.literal("Link this client to your Choculaterie account to sync your worlds."),
                    cx, accountRowY + 22, UITheme.Colors.TEXT_PRIMARY);

            int stepY = accountRowY + 88;
            int lineH = 14;
            UIDraw.centeredText(context,
                    Component.literal("1. A browser window will open. Sign in and click Approve."),
                    cx, stepY, UITheme.Colors.TEXT_PRIMARY);
            stepY += lineH;
            UIDraw.centeredText(context,
                    Component.literal("2. The game briefly joins a server to verify your Minecraft account."),
                    cx, stepY, UITheme.Colors.TEXT_PRIMARY);
            stepY += lineH;
            UIDraw.centeredText(context,
                    Component.literal("3. Once verified, you're ready to sync your saves."),
                    cx, stepY, UITheme.Colors.TEXT_PRIMARY);
        }

        if (confirmPopup != null)
            confirmPopup.extractRenderState(context, mouseX, mouseY, delta);
        toastManager.render(context, delta, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (toastManager.mouseClicked(click.x(), click.y())) return true;
        if (confirmPopup != null)
            return confirmPopup.mouseClicked(click.x(), click.y(), click.button());
        if (premiumLinkX >= 0
                && click.x() >= premiumLinkX && click.x() <= premiumLinkX + premiumLinkW
                && click.y() >= premiumLinkY - 2 && click.y() <= premiumLinkY + 11) {
            UIDraw.playClick();
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
        if (confirmPopup != null) {
            confirmPopup = null;
            return;
        }
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
