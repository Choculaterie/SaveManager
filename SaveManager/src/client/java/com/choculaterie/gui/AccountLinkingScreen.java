package com.choculaterie.gui;

import com.choculaterie.SaveManagerMod;
import com.mojang.blaze3d.platform.InputConstants;
import com.choculaterie.network.NetworkManager;
import com.choculaterie.util.AccountState;
import com.choculaterie.util.ConfigManager;
import com.choculaterie.vanilib.util.ScreenUtils;
import com.choculaterie.vanilib.gui.theme.ButtonStyle;
import com.choculaterie.vanilib.gui.theme.UIDraw;
import com.choculaterie.vanilib.gui.theme.UIIcon;
import com.choculaterie.vanilib.gui.widget.LoadingSpinner;
import com.choculaterie.vanilib.gui.theme.UITheme;
import com.choculaterie.vanilib.gui.widget.ConfirmPopup;
import com.choculaterie.vanilib.gui.widget.CustomButton;
import com.choculaterie.vanilib.gui.widget.CustomTextField;
import com.choculaterie.vanilib.gui.widget.ToastManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import net.minecraft.client.input.MouseButtonEvent;

import java.util.List;
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
    private static final int AVATAR_GAP = 6;

    private int premiumLinkX = -1;
    private int premiumLinkY = -1;
    private int premiumLinkW = 0;

    private CustomButton linkBtn = null, indicatorBtn, backgroundBtn;
    private int accountRowY, preferencesRowY;
    private int avatarSpace = 0;
    private int avatarSize = 64;
    private int contentW = 320;
    private int linkRowY, stepsY, hintY, prefButtonsY, noteY;
    private final LoadingSpinner avatarSpinner = new LoadingSpinner(0, 0);
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
        int rowH = UITheme.Dimensions.BUTTON_HEIGHT, gap = UITheme.Dimensions.BUTTON_GAP;
        contentW = Math.max(120, Math.min(320, this.width - UITheme.Dimensions.PADDING * 4));
        int top = margin + btnSize + UITheme.Dimensions.PADDING;

        int prefW = Math.max(font.width("Sync message: Off"), font.width("Background: Transparent")) + 16;
        boolean prefStacked = prefW * 2 + gap > this.width - UITheme.Dimensions.PADDING * 2;

        int contentH;
        if (hasKey) {
            int prefRows = prefStacked ? rowH * 2 + gap : rowH;
            int fixedH = 52 + rowH + 22 + prefRows + 14 + lineCount(noteText()) * LINE_H;
            int avail = this.height - top - UITheme.Dimensions.PADDING;
            avatarSize = 0;
            for (int size : new int[] { 64, 48, 32 }) {
                if (fixedH + size + AVATAR_GAP + 12 <= avail) {
                    avatarSize = size;
                    break;
                }
            }
            avatarSpace = avatarSize > 0 ? avatarSize + AVATAR_GAP : 0;
            int sectionGap = Math.max(12, Math.min(36, avail - fixedH - avatarSpace));
            int prefTitle = 52 + avatarSpace + rowH + sectionGap;
            int note = prefTitle + 22 + prefRows + 14;
            contentH = note + lineCount(noteText()) * LINE_H;
            accountRowY = Math.max(top, (this.height - contentH) / 2);
            linkRowY = accountRowY + 52 + avatarSpace;
            preferencesRowY = accountRowY + prefTitle;
            prefButtonsY = preferencesRowY + 22;
            noteY = accountRowY + note;
        } else {
            avatarSize = 0;
            avatarSpace = 0;
            int link = 22 + lineCount(INTRO) * LINE_H + 20;
            int steps = link + rowH + 16;
            int stepsH = 0;
            for (String step : STEPS)
                stepsH += lineCount(step) * LINE_H + 4;
            int hints = link + 56;
            int hintsH = (lineCount(HINT_URL) + lineCount(HINT_KEY)) * LINE_H + 4;
            contentH = Math.max(steps + stepsH, hints + hintsH);
            accountRowY = Math.max(top, (this.height - contentH) / 2);
            linkRowY = accountRowY + link;
            stepsY = accountRowY + steps;
            hintY = accountRowY + hints;
        }

        linkBtn = new CustomButton(cx - btnW / 2, linkRowY, btnW, rowH,
                Component.literal(hasKey ? "Unlink Account" : "Link Account"),
                b -> handleLinkOrReset(hasKey));
        linkBtn.setStyle(hasKey ? ButtonStyle.DANGER : ButtonStyle.PRIMARY).setIcon(hasKey ? UIIcon.LOGOUT : UIIcon.GLOBE);
        addRenderableWidget(linkBtn);

        copyUrlBtn = new CustomButton(cx - btnW / 2, linkRowY, btnW, rowH,
                Component.literal("Copy URL"), b -> copyAuthUrl());
        copyUrlBtn.setIcon(UIIcon.CLIPBOARD);
        copyUrlBtn.visible = false;
        addRenderableWidget(copyUrlBtn);

        int applyW = 58;
        int fieldW = Math.max(80, Math.min(240, this.width - UITheme.Dimensions.PADDING * 4 - applyW - gap));
        int fieldX = cx - (fieldW + applyW + gap) / 2;
        manualKeyField = new CustomTextField(minecraft, fieldX, linkRowY + 28, fieldW, rowH, Component.empty());
        manualKeyField.setPlaceholder(Component.literal("Paste save key here..."));
        manualKeyField.setMaxLength(128);
        manualKeyField.setOnEnterPressed(this::applyManualKey);
        manualKeyField.visible = false;
        addRenderableWidget(manualKeyField);

        applyKeyBtn = new CustomButton(fieldX + fieldW + gap, linkRowY + 28, applyW, rowH,
                Component.literal("Apply"), b -> applyManualKey());
        applyKeyBtn.setIcon(UIIcon.CHECK).setStyle(ButtonStyle.PRIMARY);
        applyKeyBtn.visible = false;
        addRenderableWidget(applyKeyBtn);

        int indicatorX = prefStacked ? cx - prefW / 2 : cx - prefW - gap / 2;
        int backgroundX = prefStacked ? cx - prefW / 2 : cx + gap - gap / 2;
        int backgroundY = prefStacked ? prefButtonsY + rowH + gap : prefButtonsY;
        indicatorBtn = new CustomButton(indicatorX, prefButtonsY, prefW, rowH,
                Component.literal(indicatorLabel()), b -> toggleSyncIndicator());
        indicatorBtn.visible = hasKey;
        addRenderableWidget(indicatorBtn);

        backgroundBtn = new CustomButton(backgroundX, backgroundY, prefW, rowH,
                Component.literal(backgroundLabel()), b -> toggleBackground());
        backgroundBtn.visible = hasKey;
        addRenderableWidget(backgroundBtn);

        if (hasKey) {
            networkManager.setApiKey(apiKey);
            refreshAccountState();
        }
    }

    private static final int LINE_H = 10;
    private static final String INTRO = "Link this client to your Choculaterie account to sync your worlds.";
    private static final String[] STEPS = {
            "1. A browser window will open. Sign in and click Approve.",
            "2. The game briefly joins a server to verify your Minecraft account.",
            "3. Once verified, you're ready to sync your saves." };
    private static final String HINT_URL = "Browser didn't open? Copy the URL and paste it manually.";
    private static final String HINT_KEY = "Or paste your save key manually if the mod didn't receive it:";
    private static final String PREMIUM_WORD = "Premium";

    private static String noteText() {
        return AccountState.hasAutoSync()
                ? "Favourited worlds sync automatically."
                : PREMIUM_WORD + " adds auto sync and version history.";
    }

    private List<FormattedCharSequence> wrap(String text) {
        return font.split(Component.literal(text), contentW);
    }

    private int lineCount(String text) {
        return Math.max(1, wrap(text).size());
    }

    private int wrappedText(GuiGraphicsExtractor context, String text, int cx, int y, int color) {
        List<FormattedCharSequence> lines = wrap(text);
        for (FormattedCharSequence line : lines) {
            UIDraw.text(context, line, cx - font.width(line) / 2, y, color);
            y += LINE_H;
        }
        return lines.size() * LINE_H;
    }

    private void drawDivider(GuiGraphicsExtractor context, int cx, int y) {
        int half = Math.min(150, contentW / 2);
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

    private int avatarX() {
        return this.width / 2 - avatarSize / 2;
    }

    private int avatarY() {
        return accountRowY + 18;
    }

    private boolean isOverAvatar(double x, double y) {
        if (avatarSize == 0 || isLinking || confirmPopup != null || AccountState.username().isBlank()) return false;
        return x >= avatarX() && x < avatarX() + avatarSize && y >= avatarY() && y < avatarY() + avatarSize;
    }

    private void drawAvatar(GuiGraphicsExtractor context, String who, int mouseX, int mouseY) {
        int x = avatarX(), y = avatarY();
        int pixelSize = (int) Math.round(avatarSize * this.minecraft.getWindow().getGuiScale());
        Identifier picture = ProfilePicture.get(AccountState.profilePictureUrl(), pixelSize);
        if (picture != null) {
            context.blit(picture, x, y, x + avatarSize, y + avatarSize, 0.0f, 1.0f, 0.0f, 1.0f);
        } else if (!AccountState.isKnown() || ProfilePicture.isLoading(AccountState.profilePictureUrl())) {
            context.fill(x, y, x + avatarSize, y + avatarSize, UITheme.Colors.SURFACE);
            avatarSpinner.setPosition(x + avatarSize / 2 - avatarSpinner.getWidth() / 2, y + avatarSize / 2 - avatarSpinner.getHeight() / 2);
            avatarSpinner.extractRenderState(context, mouseX, mouseY, 0);
        } else {
            context.fill(x, y, x + avatarSize, y + avatarSize, UITheme.Colors.SURFACE);
            String initial = who.isBlank() ? "?" : who.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
            UIDraw.centeredText(context, Component.literal(initial), x + avatarSize / 2, y + avatarSize / 2 - 4, UITheme.Colors.TEXT_MUTED);
        }
        UIDraw.outline(context, x - 1, y - 1, x + avatarSize + 1, y + avatarSize + 1, UITheme.Colors.OUTLINE);
        if (isOverAvatar(mouseX, mouseY)) {
            context.fill(x, y, x + avatarSize, y + avatarSize, 0x88000000);
            UIIcon.PENCIL.drawCentered(context, x + avatarSize / 2, y + avatarSize / 2, UITheme.Colors.TEXT_HIGHLIGHT);
        }
    }

    private void openProfilePicturePage() {
        try {
            String user = AccountState.username();
            String url = "https://choculaterie.com/users/"
                    + java.net.URLEncoder.encode(user, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")
                    + "?section=avatar";
            com.mojang.blaze3d.Blaze3D.openUri(new java.net.URI(url));
        } catch (Exception e) {
            SaveManagerMod.LOGGER.warn("[SM] profile picture link: failed to open - {}", e.toString());
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
            if (avatarSize > 0)
                drawAvatar(context, who, mouseX, mouseY);
            UIDraw.centeredText(context, Component.literal(who), cx, accountRowY + 22 + avatarSpace, UITheme.Colors.SUCCESS_TEXT);

            if (AccountState.isKnown()) {
                String plan = AccountState.isPremium() ? "Premium" : "Free";
                int planColor = AccountState.isPremium() ? UITheme.Palette.GOLD : UITheme.Colors.TEXT_MUTED;
                UIDraw.centeredText(context, Component.literal(plan), cx, accountRowY + 36 + avatarSpace, planColor);
            }

            UIDraw.centeredText(context, Component.literal("Preferences"), cx, preferencesRowY, UITheme.Colors.ACCENT);
            drawDivider(context, cx, preferencesRowY + 10);

            if (AccountState.hasAutoSync()) {
                premiumLinkX = -1;
                wrappedText(context, noteText(), cx, noteY, UITheme.Colors.TEXT_MUTED);
            } else {
                List<FormattedCharSequence> lines = wrap(noteText());
                int y = noteY;
                for (int i = 0; i < lines.size(); i++) {
                    FormattedCharSequence line = lines.get(i);
                    int startX = cx - font.width(line) / 2;
                    UIDraw.text(context, line, startX, y, UITheme.Colors.TEXT_MUTED);
                    if (i == 0) {
                        UIDraw.text(context, PREMIUM_WORD, startX, y, PREMIUM_ACCENT);
                        premiumLinkX = startX;
                        premiumLinkY = y;
                        premiumLinkW = font.width(PREMIUM_WORD);
                    }
                    y += LINE_H;
                }
            }

        } else if (isLinking) {
            if (!linkingStatus.isEmpty()) {
                wrappedText(context, linkingStatus, cx, linkRowY - 18, UITheme.Colors.SUCCESS_TEXT);
            }
            if (pendingAuthUrl != null) {
                int y = hintY + wrappedText(context, HINT_URL, cx, hintY, UITheme.Colors.TEXT_MUTED) + 4;
                wrappedText(context, HINT_KEY, cx, y, UITheme.Colors.TEXT_MUTED);
            }
        } else {
            UIDraw.centeredText(context, Component.literal("Getting started"), cx, accountRowY, UITheme.Colors.ACCENT);
            drawDivider(context, cx, accountRowY + 10);

            wrappedText(context, INTRO, cx, accountRowY + 22, UITheme.Colors.TEXT_PRIMARY);

            int stepY = stepsY;
            for (String step : STEPS)
                stepY += wrappedText(context, step, cx, stepY, UITheme.Colors.TEXT_PRIMARY) + 4;
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
        if (click.button() == InputConstants.MOUSE_BUTTON_LEFT && isOverAvatar(click.x(), click.y())) {
            UIDraw.playClick();
            openProfilePicturePage();
            return true;
        }
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
