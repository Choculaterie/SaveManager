package com.choculaterie.gui;

import com.choculaterie.SaveManagerMod;
import com.choculaterie.network.ApiException;
import com.choculaterie.network.NetworkManager;
import com.choculaterie.sync.AutoSync;
import com.choculaterie.sync.CloudPull;
import com.choculaterie.sync.SyncState;
import com.choculaterie.util.ConfigManager;
import com.choculaterie.vanilib.gui.theme.ButtonStyle;
import com.choculaterie.vanilib.gui.theme.UIDraw;
import com.choculaterie.vanilib.gui.theme.UIIcon;
import com.choculaterie.vanilib.gui.theme.UITheme;
import com.choculaterie.vanilib.gui.widget.CustomButton;
import com.choculaterie.vanilib.gui.widget.LoadingSpinner;
import com.choculaterie.vanilib.util.FormatUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

public class CloudUpdateScreen extends Screen {

    private enum Stage { CHECKING, WAITING, DOWNLOADING, INSTALLING, CHOICE, FAILED }

    private static final int LINE_H = 10;
    private static final int BAR_H = 6;

    private final String levelId;
    private final Runnable onCancel;
    private final NetworkManager network = new NetworkManager();
    private final LoadingSpinner spinner = new LoadingSpinner(0, 0);

    private volatile Stage stage = Stage.CHECKING;
    private volatile long bytes;
    private volatile long total;
    private volatile boolean cancelled;
    private boolean started;
    private boolean lockHeld;
    private String headVersionId;
    private String detail = "";
    private boolean neverSyncedHere;

    public CloudUpdateScreen(String levelId, Runnable onCancel) {
        super(Component.literal("Updating " + levelId));
        this.levelId = levelId;
        this.onCancel = onCancel;
    }

    private Path savesDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("saves");
    }

    @Override
    protected void init() {
        int rowH = UITheme.Dimensions.BUTTON_HEIGHT, gap = UITheme.Dimensions.BUTTON_GAP;
        int y = buttonsY();
        int cx = this.width / 2;
        switch (stage) {
            case CHOICE -> {
                int w = Math.min(130, (this.width - UITheme.Dimensions.PADDING * 2 - gap * 2) / 3);
                int x = cx - (w * 3 + gap * 2) / 2;
                addRenderableWidget(new CustomButton(x, y, w, rowH, Component.literal("Use cloud version"),
                        b -> startDownload()).setIcon(UIIcon.DOWNLOAD).setStyle(ButtonStyle.PRIMARY));
                addRenderableWidget(new CustomButton(x + w + gap, y, w, rowH, Component.literal("Play this copy"),
                        b -> play()));
                addRenderableWidget(new CustomButton(x + (w + gap) * 2, y, w, rowH, Component.literal("Back"),
                        b -> back()));
            }
            case FAILED -> {
                int w = 110;
                int x = cx - (w * 2 + gap) / 2;
                addRenderableWidget(new CustomButton(x, y, w, rowH, Component.literal("Play anyway"), b -> play())
                        .setStyle(ButtonStyle.PRIMARY));
                addRenderableWidget(new CustomButton(x + w + gap, y, w, rowH, Component.literal("Back"), b -> back()));
            }
            case INSTALLING -> {
            }
            default -> addRenderableWidget(new CustomButton(cx - 55, y, 110, rowH, Component.literal("Cancel"),
                    b -> cancel()));
        }

        if (!started) {
            started = true;
            check();
        }
    }

    private int buttonsY() {
        return this.height / 2 + 34;
    }

    private void setStage(Stage next) {
        Minecraft.getInstance().execute(() -> {
            stage = next;
            if (Minecraft.getInstance().gui.screen() == this)
                rebuildWidgets();
        });
    }

    private void check() {
        String key = ConfigManager.loadApiKey();
        if (key == null || key.isBlank()) {
            play();
            return;
        }
        network.setApiKey(key);
        network.syncVersions(levelId).orTimeout(10, TimeUnit.SECONDS).whenComplete((arr, err) -> {
            if (cancelled)
                return;
            if (err != null) {
                Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
                if (cause instanceof ApiException api && api.status() == 404) {
                    SaveManagerMod.LOGGER.info("[SM] launch check '{}': not in the cloud, opening the local copy", levelId);
                    Minecraft.getInstance().execute(this::play);
                    return;
                }
                SaveManagerMod.LOGGER.warn("[SM] launch check '{}': cloud unreachable - {}", levelId, err.toString());
                fail("Couldn't reach the cloud. Your local copy may be out of date.");
                return;
            }
            String head = null;
            long headBytes = 0L;
            if (arr != null) {
                for (JsonElement e : arr) {
                    if (e == null || !e.isJsonObject())
                        continue;
                    JsonObject o = e.getAsJsonObject();
                    if (o.has("isHead") && o.get("isHead").getAsBoolean()) {
                        head = o.has("versionId") && !o.get("versionId").isJsonNull() ? o.get("versionId").getAsString() : null;
                        headBytes = o.has("totalBytes") && !o.get("totalBytes").isJsonNull() ? o.get("totalBytes").getAsLong() : 0L;
                    }
                }
            }
            SyncState.WorldEntry local = SyncState.get(levelId);
            if (head == null || (head.equals(local.headVersionId) && !local.conflicted)) {
                SaveManagerMod.LOGGER.info("[SM] launch check '{}': up to date ({})", levelId, head);
                Minecraft.getInstance().execute(this::play);
                return;
            }
            headVersionId = head;
            total = headBytes;
            neverSyncedHere = local.headVersionId == null;
            boolean localChanges = CloudPull.hasLocalChanges(savesDir().resolve(levelId), levelId);
            SaveManagerMod.LOGGER.info("[SM] launch check '{}': cloud head {} differs from local {} (local changes={}, conflicted={})",
                    levelId, head, local.headVersionId, localChanges, local.conflicted);
            if (neverSyncedHere || localChanges || local.conflicted)
                setStage(Stage.CHOICE);
            else
                Minecraft.getInstance().execute(this::startDownload);
        });
    }

    private void startDownload() {
        if (SaveManagerScreen.isWorldLocked(savesDir().resolve(levelId))) {
            fail("This world is open in another instance. Close it first.");
            return;
        }
        if (!AutoSync.tryAcquireForManual()) {
            setStage(Stage.WAITING);
            return;
        }
        lockHeld = true;
        bytes = 0L;
        setStage(Stage.DOWNLOADING);

        Path tmpDir;
        try {
            Path tempBase = savesDir().resolve(".savemanager-temp");
            Files.createDirectories(tempBase);
            tmpDir = Files.createTempDirectory(tempBase, "savemanager-pull-");
        } catch (Exception e) {
            releaseLock();
            fail("Couldn't prepare the download.");
            return;
        }

        network.downloadVersion(headVersionId, tmpDir, (done, size) -> {
            bytes = Math.max(0L, done);
            if (size > 0)
                total = size;
        }, () -> cancelled).whenComplete((zipPath, err) -> {
            try {
                if (err != null) {
                    Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
                    if (cause instanceof CancellationException || cancelled)
                        return;
                    SaveManagerMod.LOGGER.warn("[SM] launch update '{}': download failed - {}", levelId, cause.toString());
                    fail(FormatUtils.extractErrorMessage(cause));
                    return;
                }
                setStage(Stage.INSTALLING);
                SaveManagerScreen.installWorldZip(zipPath, savesDir(), levelId, headVersionId, () -> {
                    if (cancelled)
                        throw new CancellationException("Cancelled");
                });
                SaveManagerMod.LOGGER.info("[SM] launch update '{}': now at cloud version {}", levelId, headVersionId);
                Minecraft.getInstance().execute(this::play);
            } catch (CancellationException ignored) {
            } catch (Exception ex) {
                SaveManagerMod.LOGGER.warn("[SM] launch update '{}': install failed - {}", levelId, ex.toString());
                fail(FormatUtils.extractErrorMessage(ex));
            } finally {
                try { if (zipPath != null) Files.deleteIfExists(zipPath); } catch (Exception ignored) {}
                try { Files.deleteIfExists(tmpDir); } catch (Exception ignored) {}
                releaseLock();
            }
        });
    }

    private void releaseLock() {
        if (lockHeld) {
            lockHeld = false;
            AutoSync.releaseManual();
        }
    }

    private void fail(String why) {
        detail = why == null ? "" : why;
        setStage(Stage.FAILED);
    }

    @Override
    public void tick() {
        if (stage == Stage.WAITING && !cancelled && AutoSync.syncingWorld() == null)
            startDownload();
    }

    private void play() {
        if (cancelled && stage != Stage.CHOICE && stage != Stage.FAILED)
            return;
        CloudPull.bypassOnce(levelId);
        Minecraft.getInstance().createWorldOpenFlows().openWorld(levelId, onCancel);
    }

    private void cancel() {
        cancelled = true;
        back();
    }

    private void back() {
        cancelled = true;
        onCancel.run();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return stage != Stage.INSTALLING;
    }

    @Override
    public void onClose() {
        cancel();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        if (ConfigManager.isSolidBackground())
            context.fill(0, 0, this.width, this.height, UITheme.Colors.BACKGROUND);
        else
            super.extractBackground(context, mouseX, mouseY, delta);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int maxW = Math.max(120, Math.min(320, this.width - UITheme.Dimensions.PADDING * 4));
        int y = this.height / 2 - 34;

        String heading = switch (stage) {
            case CHOICE -> "\"" + levelId + "\" changed on another computer";
            case FAILED -> "Couldn't update \"" + levelId + "\"";
            case CHECKING -> "Checking \"" + levelId + "\"";
            default -> "Updating \"" + levelId + "\"";
        };
        y += centered(context, heading, cx, y, maxW, UITheme.Colors.TEXT_HIGHLIGHT) + 6;

        String line = switch (stage) {
            case CHECKING -> "Looking for a newer version in the cloud...";
            case WAITING -> "Waiting for another upload to finish...";
            case DOWNLOADING -> "Changed on another computer. Downloading the latest version...";
            case INSTALLING -> "Installing the latest version...";
            case CHOICE -> neverSyncedHere
                    ? "This copy has never been synced from this computer. Which one do you want to play?"
                    : "This computer also has changes that aren't in the cloud. Which one do you want to play?";
            case FAILED -> detail;
        };
        y += centered(context, line, cx, y, maxW, UITheme.Colors.TEXT_MUTED) + 8;

        if (stage == Stage.DOWNLOADING) {
            int barW = Math.min(240, maxW);
            int x = cx - barW / 2;
            context.fill(x, y, x + barW, y + BAR_H, UITheme.Colors.SURFACE);
            double frac = total > 0 ? Math.min(1.0, bytes / (double) total) : 0.0;
            context.fill(x, y, x + (int) (barW * frac), y + BAR_H, UITheme.Colors.ACCENT);
            UIDraw.outline(context, x - 1, y - 1, x + barW + 1, y + BAR_H + 1, UITheme.Colors.OUTLINE);
            String size = total > 0
                    ? FormatUtils.formatBytes(bytes) + " / " + FormatUtils.formatBytes(total)
                    : FormatUtils.formatBytes(bytes);
            UIDraw.centeredText(context, Component.literal(size), cx, y + BAR_H + 5, UITheme.Colors.TEXT_MUTED);
        } else if (stage == Stage.CHECKING || stage == Stage.WAITING || stage == Stage.INSTALLING) {
            spinner.setPosition(cx - spinner.getWidth() / 2, y);
            spinner.extractRenderState(context, mouseX, mouseY, delta);
        }
    }

    private int centered(GuiGraphicsExtractor context, String text, int cx, int y, int maxW, int color) {
        List<FormattedCharSequence> lines = font.split(Component.literal(text), maxW);
        for (FormattedCharSequence l : lines) {
            UIDraw.text(context, l, cx - font.width(l) / 2, y, color);
            y += LINE_H;
        }
        return lines.size() * LINE_H;
    }
}
