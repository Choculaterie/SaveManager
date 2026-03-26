package com.choculaterie.gui;

import com.choculaterie.SaveManagerMod;
import com.choculaterie.mixin.SelectWorldScreenAccessor;
import com.choculaterie.network.NetworkManager;
import com.choculaterie.util.ConfigManager;
import com.choculaterie.util.ScreenUtils;
import com.choculaterie.util.WatchManager;
import com.choculaterie.widget.ConfirmPopup;
import com.choculaterie.widget.CustomButton;
import com.choculaterie.widget.LoadingSpinner;
import com.choculaterie.widget.ScrollBar;
import com.choculaterie.widget.ToastManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.tooltip.HoveredTooltipPositioner;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.text.Text;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.choculaterie.util.FormatUtils.*;

public class SaveManagerScreen extends Screen {
    private static final int ROW_HEIGHT = 24;
    private static final int VISIBLE_ROWS = 8;
    private static final int PANEL_GAP = 20;
    private static final long RELOAD_THRESHOLD_MS = 15 * 60 * 1000L;

    private static long lastLoadTimeMs = 0L;
    private static final List<LocalSave> cachedLocalSaves = new ArrayList<>();
    private static final List<CloudSave> cachedCloudSaves = new ArrayList<>();
    private static String cachedQuotaFormatted = "Loading";

    private final Screen parent;
    private final NetworkManager networkManager = new NetworkManager();
    private final ToastManager toastManager;
    private final LoadingSpinner spinner;
    private final List<LocalSave> localSaves = new ArrayList<>();
    private final List<CloudSave> cloudSaves = new ArrayList<>();
    private ScrollBar localScrollBar, cloudScrollBar;
    private int localScrollOffset = 0, cloudScrollOffset = 0;
    private int localSelectedIndex = -1, cloudSelectedIndex = -1;
    private boolean localLoading = true, cloudLoading = true;
    private int localPanelX, localPanelW, cloudPanelX, cloudPanelW, listY, listH;
    private ConfirmPopup confirmPopup = null;
    private CustomButton uploadBtn, downloadBtn, deleteBtn, refreshBtn;
    private String quotaFormatted = "Loading";
    private long quotaBytes = 5L * 1024L * 1024L * 1024L;
    private boolean quotaLoading = false;
    private static final TransferState ACTIVE = new TransferState();
    private String autoUploadWorld = null;
    private boolean closed = false;
    private String starTooltipText = null;

    private static final class TransferState {
        volatile boolean dlActive, upActive, zipping, unzipping;
        volatile long bytes, total = -1L, lastBytes, lastTickNanos;
        volatile double speedBps;

        boolean isActive() { return dlActive || upActive || zipping || unzipping; }

        void reset(boolean download) {
            if (download) { dlActive = true; unzipping = false; }
            else { upActive = true; zipping = true; }
            bytes = 0L; total = -1L; lastBytes = 0L;
            lastTickNanos = System.nanoTime(); speedBps = 0.0;
        }

        void updateSpeed() {
            long now = System.nanoTime();
            long dtNs = now - lastTickNanos;
            long dBytes = bytes - lastBytes;
            if (dtNs > 50_000_000L) {
                double instBps = dBytes > 0 ? (dBytes * 1_000_000_000.0) / dtNs : 0.0;
                speedBps = speedBps <= 0 ? instBps : (0.2 * instBps + 0.8 * speedBps);
                lastTickNanos = now;
                lastBytes = bytes;
            }
        }
    }

    public SaveManagerScreen(Screen parent) {
        super(Text.literal("Save Manager"));
        this.parent = parent;
        this.toastManager = new ToastManager(null);
        this.spinner = new LoadingSpinner(0, 0);
    }

    public SaveManagerScreen(Screen parent, String autoUploadWorld) {
        this(parent);
        this.autoUploadWorld = autoUploadWorld;
    }

    @Override
    protected void init() {
        toastManager.initClient(client);
        int btnSize = 20, margin = 6;
        addBtn(margin, margin, btnSize, btnSize, "\u2190", b -> closeScreen());
        refreshBtn = addBtn(margin + btnSize + 5, margin, btnSize, btnSize, "\uD83D\uDD04", b -> refresh());
        addBtn(this.width - margin - btnSize * 2 - 5, margin, btnSize, btnSize, "\uD83D\uDCC1", b -> openSavesFolder());
        addBtn(this.width - margin - btnSize, margin, btnSize, btnSize, "\u2699", b -> client.setScreen(new AccountLinkingScreen(this)));

        int totalW = this.width - 60;
        int panelW = (totalW - PANEL_GAP) / 2;
        localPanelX = 30; localPanelW = panelW;
        cloudPanelX = localPanelX + panelW + PANEL_GAP; cloudPanelW = panelW;

        int btnW = 80, actionBtnY = this.height - 28;
        uploadBtn = addBtn(localPanelX + (localPanelW - btnW) / 2, actionBtnY, btnW, 20, "Upload", b -> onUpload());
        downloadBtn = addBtn(cloudPanelX + (cloudPanelW - btnW * 2 - 10) / 2, actionBtnY, btnW, 20, "Download", b -> onDownload());
        deleteBtn = addBtn(cloudPanelX + (cloudPanelW - btnW * 2 - 10) / 2 + btnW + 10, actionBtnY, btnW, 20, "Delete", b -> onDelete());

        listY = 70; listH = VISIBLE_ROWS * ROW_HEIGHT;
        localScrollBar = new ScrollBar(localPanelX + localPanelW + 4, listY, listH);
        cloudScrollBar = new ScrollBar(cloudPanelX + cloudPanelW + 4, listY, listH);

        String apiKey = ConfigManager.loadApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            client.setScreen(new AccountLinkingScreen(this));
            return;
        }
        networkManager.setApiKey(apiKey);
        quotaFormatted = cachedQuotaFormatted;
        quotaBytes = parseQuotaBytes(cachedQuotaFormatted);

        boolean shouldReloadCloud = (System.currentTimeMillis() - lastLoadTimeMs) > RELOAD_THRESHOLD_MS || cachedCloudSaves.isEmpty();
        fetchLocalSaves();
        if (shouldReloadCloud) {
            quotaLoading = true;
            fetchCloudSaves(); fetchQuotaInfo();
        } else {
            quotaLoading = false;
            cloudSaves.clear(); cloudSaves.addAll(cachedCloudSaves);
            cloudLoading = false;
        }
    }

    private CustomButton addBtn(int x, int y, int w, int h, String label, CustomButton.PressAction onPress) {
        CustomButton btn = new CustomButton(x, y, w, h, Text.literal(label), onPress);
        btn.setToastManager(toastManager);
        addDrawableChild(btn);
        return btn;
    }

    public void refresh() {
        localSelectedIndex = -1; cloudSelectedIndex = -1;
        localScrollOffset = 0; cloudScrollOffset = 0;
        fetchLocalSaves(); fetchCloudSaves(); fetchQuotaInfo();
    }

    public Screen getParent() { return parent; }

    // ── Data fetching ──

    private void fetchLocalSaves() {
        localLoading = true;
        CompletableFuture.runAsync(() -> {
            List<LocalSave> tmp = new ArrayList<>();
            try {
                Path savesDir = client.runDirectory.toPath().resolve("saves");
                if (Files.exists(savesDir) && Files.isDirectory(savesDir)) {
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(savesDir)) {
                        for (Path p : ds) {
                            if (Files.isDirectory(p)) tmp.add(LocalSave.fromDir(p));
                        }
                    }
                }
                tmp.sort(Comparator.comparingLong((LocalSave s) -> s.lastModified).reversed());
            } catch (Exception e) {
                SaveManagerMod.LOGGER.warn("LocalSaves: scan error - {}", extractErrorMessage(e));
            }
            runOnClient(() -> {
                localSaves.clear(); localSaves.addAll(tmp);
                cachedLocalSaves.clear(); cachedLocalSaves.addAll(tmp);
                lastLoadTimeMs = System.currentTimeMillis();
                localScrollOffset = 0; localSelectedIndex = -1; localLoading = false;
                triggerAutoUpload();
            });
        });
    }

    private void fetchCloudSaves() {
        cloudLoading = true;
        networkManager.listWorldSaves().whenComplete((json, err) -> {
            if (err != null) {
                String msg = extractErrorMessage(err);
                SaveManagerMod.LOGGER.warn("CloudSaves: list failed - {}", msg);
                runOnClient(() -> {
                    cloudLoading = false; cloudSaves.clear();
                    if (msg.contains("account must be linked")) toastManager.showError(msg, "Profile -> Edit profile -> Link");
                    else toastManager.showError(msg);
                });
                return;
            }
            List<CloudSave> tmp = new ArrayList<>();
            try {
                JsonArray arr = findArray(json, "saves", "items", "data", "list", "worlds");
                if (arr == null && json.has("result") && json.get("result").isJsonObject())
                    arr = findArray(json.getAsJsonObject("result"), "saves", "items", "data", "list", "worlds");
                if (arr == null) {
                    for (var e : json.entrySet()) {
                        if (e.getValue() != null && e.getValue().isJsonArray()) { arr = e.getValue().getAsJsonArray(); break; }
                    }
                }
                if (arr != null) {
                    for (int i = 0; i < arr.size(); i++) {
                        if (arr.get(i).isJsonObject()) tmp.add(CloudSave.from(arr.get(i).getAsJsonObject()));
                    }
                }
            } catch (Throwable e) {
                SaveManagerMod.LOGGER.warn("CloudSaves: parse error - {}", extractErrorMessage(e));
            }
            runOnClient(() -> {
                cloudSaves.clear(); cloudSaves.addAll(tmp);
                cachedCloudSaves.clear(); cachedCloudSaves.addAll(tmp);
                cloudLoading = false; cloudScrollOffset = 0; cloudSelectedIndex = -1;
            });
        });
    }

    private void fetchQuotaInfo() {
        networkManager.getQuotaInfo().whenComplete((json, err) -> {
            if (err != null) {
                SaveManagerMod.LOGGER.warn("Quota: fetch failed - {}", extractErrorMessage(err));
                quotaFormatted = cachedQuotaFormatted;
                runOnClient(() -> quotaLoading = false);
                return;
            }
            try {
                String quota = json.has("quotaFormatted") ? json.get("quotaFormatted").getAsString() : "5 GB";
                quotaFormatted = quota; cachedQuotaFormatted = quota; quotaBytes = parseQuotaBytes(quota);
            } catch (Exception e) {
                SaveManagerMod.LOGGER.warn("Quota: parse error - {}", extractErrorMessage(e));
                quotaFormatted = cachedQuotaFormatted;
            }
            runOnClient(() -> quotaLoading = false);
        });
    }

    // ── Actions ──

    private void onUpload() {
        if (localSelectedIndex < 0 || localSelectedIndex >= localSaves.size()) return;
        if (networkManager.getApiKey() == null || networkManager.getApiKey().isBlank()) {
            client.setScreen(new AccountLinkingScreen(this)); return;
        }
        LocalSave s = localSaves.get(localSelectedIndex);
        localLoading = true;
        networkManager.listWorldSaveNames().whenComplete((names, err) -> runOnClient(() -> {
            if (err != null) { localLoading = false; client.setScreen(new AccountLinkingScreen(this)); return; }
            String sanitized = sanitizeFolderName(s.worldName);
            boolean exists = names != null && names.stream().anyMatch(n ->
                    n != null && (n.equalsIgnoreCase(s.worldName) || (!sanitized.isEmpty() && n.equalsIgnoreCase(sanitized))));
            if (!exists) { beginZipAndUpload(s); return; }
            confirmPopup = new ConfirmPopup(this, "Overwrite Cloud Save?",
                    "A save named \"" + s.worldName + "\" already exists. Overwrite?",
                    () -> { confirmPopup = null; beginZipAndUpload(s); },
                    () -> { confirmPopup = null; localLoading = false; }, "Overwrite");
        }));
    }

    private void beginZipAndUpload(LocalSave s) {
        ACTIVE.reset(false);
        localLoading = true;
        new Thread(() -> {
            Path zip;
            try {
                zip = zipWorld(s.dir, s.worldName.replaceAll("[\\\\/:*?\"<>|]+", "_"));
            } catch (Exception ex) {
                String msg = extractErrorMessage(ex);
                SaveManagerMod.LOGGER.warn("Zip failed - {}", msg);
                runOnClient(() -> { ACTIVE.upActive = false; ACTIVE.zipping = false; localLoading = false; toastManager.showError(msg); });
                return;
            }
            runOnClient(() -> startUpload(zip, s.worldName));
        }, "SaveManager-zip").start();
    }

    private void startUpload(Path zipFile, String worldName) {
        ACTIVE.zipping = false;
        ACTIVE.bytes = 0L; ACTIVE.total = -1L;
        ACTIVE.lastTickNanos = System.nanoTime(); ACTIVE.lastBytes = 0L; ACTIVE.speedBps = 0.0;
        try {
            networkManager.uploadWorldSave(worldName, zipFile, (sent, total) -> {
                ACTIVE.bytes = Math.max(0L, sent);
                if (total > 0) ACTIVE.total = total;
                ACTIVE.updateSpeed();
            }).whenComplete((json, err) -> runOnClient(() -> {
                try { Files.deleteIfExists(zipFile); } catch (Throwable ignored) {}
                ACTIVE.upActive = false; ACTIVE.zipping = false; localLoading = false;
                if (err != null) {
                    String msg = extractErrorMessage(err);
                    SaveManagerMod.LOGGER.warn("Upload failed - {}", msg);
                    toastManager.showError(msg);
                } else {
                    toastManager.showSuccess("Upload complete: " + worldName);
                    LocalSave uploaded = localSaves.stream()
                            .filter(s -> s.worldName.equals(worldName)).findFirst().orElse(null);
                    if (uploaded != null) WatchManager.updateLastKnown(worldName, uploaded.dir);
                    WatchManager.clearPendingNotification(worldName);
                    fetchLocalSaves(); fetchCloudSaves();
                }
            }));
        } catch (Throwable t) {
            try { Files.deleteIfExists(zipFile); } catch (Throwable ignored) {}
            ACTIVE.upActive = false; localLoading = false;
            toastManager.showError("Upload failed");
        }
    }

    private void onDownload() {
        if (cloudSelectedIndex < 0 || cloudSelectedIndex >= cloudSaves.size()) return;
        CloudSave s = cloudSaves.get(cloudSelectedIndex);
        Path savesDir = client.runDirectory.toPath().resolve("saves");
        try { Files.createDirectories(savesDir); } catch (Exception ignored) {}

        String baseName = sanitizeFolderName(s.worldName);
        if (baseName.isEmpty()) baseName = "world";

        if (Files.exists(savesDir.resolve(baseName))) {
            confirmPopup = new ConfirmPopup(this, "Overwrite Local Save?",
                    "A save named \"" + s.worldName + "\" already exists. Overwrite?",
                    () -> { confirmPopup = null; beginDownload(s, savesDir); },
                    () -> { confirmPopup = null; }, "Overwrite");
            return;
        }
        beginDownload(s, savesDir);
    }

    private void beginDownload(CloudSave s, Path savesDir) {
        cloudLoading = true;
        Path tmpDir;
        try { tmpDir = Files.createTempDirectory("savemanager-dl-"); }
        catch (Exception e) { toastManager.showError("Failed to prepare temp dir"); cloudLoading = false; return; }

        ACTIVE.reset(true);
        if (s.fileSizeBytes > 0) ACTIVE.total = s.fileSizeBytes;

        networkManager.downloadWorldSave(s.id, tmpDir, (downloaded, total) -> {
            ACTIVE.bytes = Math.max(0L, downloaded);
            if (total > 0) ACTIVE.total = total;
            ACTIVE.updateSpeed();
        }).whenComplete((zipPath, err) -> {
            ACTIVE.dlActive = false;
            if (err != null) {
                String msg = extractErrorMessage(err);
                SaveManagerMod.LOGGER.warn("Download failed - {}", msg);
                runOnClient(() -> { cloudLoading = false; toastManager.showError(msg); });
                return;
            }
            runOnClient(() -> ACTIVE.unzipping = true);
            String baseName = sanitizeFolderName(s.worldName);
            if (baseName.isEmpty()) baseName = "world";
            Path targetBase = savesDir.resolve(baseName);
            try {
                Files.createDirectories(targetBase);
                deleteDirectoryRecursively(targetBase);
                Files.createDirectories(targetBase);
                unzipSmart(zipPath, targetBase);
                runOnClient(() -> { toastManager.showSuccess("Download complete"); fetchLocalSaves(); });
            } catch (Exception ex) {
                String msg = extractErrorMessage(ex);
                SaveManagerMod.LOGGER.warn("Unzip failed - {}", msg);
                runOnClient(() -> toastManager.showError(msg));
            } finally {
                try { Files.deleteIfExists(zipPath); } catch (Exception ignored) {}
                try { Files.deleteIfExists(tmpDir); } catch (Exception ignored) {}
                runOnClient(() -> { ACTIVE.unzipping = false; cloudLoading = false; });
            }
        });
    }

    private void onDelete() {
        if (localSelectedIndex >= 0 && localSelectedIndex < localSaves.size()) {
            LocalSave s = localSaves.get(localSelectedIndex);
            confirmPopup = new ConfirmPopup(this, "Delete Local Save?",
                    "Are you sure you want to permanently delete \"" + safe(s.worldName) + "\"?",
                    () -> { confirmPopup = null; deleteLocalSave(s); }, () -> confirmPopup = null, "Delete");
            return;
        }
        if (cloudSelectedIndex < 0 || cloudSelectedIndex >= cloudSaves.size()) return;
        CloudSave s = cloudSaves.get(cloudSelectedIndex);
        confirmPopup = new ConfirmPopup(this, "Delete Cloud Save?",
                "Are you sure you want to permanently delete \"" + safe(s.worldName) + "\"?",
                () -> { confirmPopup = null; cloudLoading = true;
                    networkManager.deleteWorldSave(s.id).whenComplete((resp, err) -> runOnClient(() -> {
                        if (err != null) {
                            String msg = extractErrorMessage(err);
                            SaveManagerMod.LOGGER.warn("Delete failed - {}", msg);
                            toastManager.showError(msg); cloudLoading = false; return;
                        }
                        toastManager.showSuccess("Deleted"); cloudLoading = false; cloudSelectedIndex = -1; fetchCloudSaves();
                    }));
                }, () -> confirmPopup = null, "Delete");
    }

    private void deleteLocalSave(LocalSave s) {
        new Thread(() -> {
            try {
                deleteDirectoryRecursively(s.dir);
                runOnClient(() -> { toastManager.showSuccess("Deleted"); localSelectedIndex = -1; fetchLocalSaves(); });
            } catch (Exception e) {
                String msg = extractErrorMessage(e);
                SaveManagerMod.LOGGER.warn("Local delete failed - {}", msg);
                runOnClient(() -> toastManager.showError(msg));
            }
        }, "SaveManager-delete").start();
    }

    private void triggerAutoUpload() {
        if (autoUploadWorld == null) return;
        for (int i = 0; i < localSaves.size(); i++) {
            if (localSaves.get(i).worldName.equals(autoUploadWorld)) {
                localSelectedIndex = i;
                autoUploadWorld = null;
                onUpload();
                return;
            }
        }
    }

    // ── Rendering ──

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        updateButtonStates();
        int cx = this.width / 2;
        ctx.drawCenteredTextWithShadow(textRenderer, title, cx, 10, 0xFFFFFFFF);

        if (quotaLoading) renderTinySpinner(ctx, cx, 28, delta);
        else ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(computeQuotaLine()), cx, 22, 0xFFAAAAAA);

        ctx.drawTextWithShadow(textRenderer, Text.literal("Local Saves"), localPanelX, 50, 0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("Cloud Saves"), cloudPanelX, 50, 0xFFFFFFFF);
        starTooltipText = null;
        renderSavePanel(ctx, mouseX, mouseY, delta, true);
        renderSavePanel(ctx, mouseX, mouseY, delta, false);
        if (starTooltipText != null) {
            ctx.drawTooltipImmediately(textRenderer, List.of(
                    TooltipComponent.of(Text.literal(starTooltipText).asOrderedText())
            ), mouseX, mouseY, HoveredTooltipPositioner.INSTANCE, null);
        }

        if (ACTIVE.isActive()) {
            int statusY = listY + listH + 10;
            if (ACTIVE.zipping || ACTIVE.unzipping || ACTIVE.bytes <= 0L) {
                spinner.setPosition(cx - 16, statusY);
                spinner.render(ctx, mouseX, mouseY, delta);
                String msg = ACTIVE.zipping ? "Zipping..." : ACTIVE.unzipping ? "Unzipping..." : "Preparing...";
                ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(msg), cx, statusY + 40, 0xFFFFFFFF);
            } else {
                renderProgressBar(ctx, cx, statusY);
            }
        } else if (localLoading || cloudLoading) {
            int statusY = listY + listH + 10;
            spinner.setPosition(cx - 16, statusY);
            spinner.render(ctx, mouseX, mouseY, delta);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Loading..."), cx, statusY + 40, 0xFFFFFFFF);
        }

        // Re-render action buttons on top of panels so they're always clickable
        uploadBtn.render(ctx, mouseX, mouseY, delta);
        downloadBtn.render(ctx, mouseX, mouseY, delta);
        deleteBtn.render(ctx, mouseX, mouseY, delta);

        toastManager.render(ctx, delta, mouseX, mouseY);
        if (confirmPopup != null) confirmPopup.render(ctx, mouseX, mouseY, delta);
    }

    private void renderSavePanel(DrawContext ctx, int mouseX, int mouseY, float delta, boolean isLocal) {
        List<?> saves = isLocal ? localSaves : cloudSaves;
        ScrollBar scrollBar = isLocal ? localScrollBar : cloudScrollBar;
        int panelX = isLocal ? localPanelX : cloudPanelX;
        int panelW = isLocal ? localPanelW : cloudPanelW;
        int selectedIndex = isLocal ? localSelectedIndex : cloudSelectedIndex;
        int scrollOffset = isLocal ? localScrollOffset : cloudScrollOffset;

        int maxScroll = Math.max(0, saves.size() - VISIBLE_ROWS);
        scrollBar.setScrollData(saves.size() * ROW_HEIGHT, listH);
        if (maxScroll > 0) scrollBar.setScrollPercentage((double) scrollOffset / maxScroll);

        boolean blockHover = toastManager.isMouseOverToast(mouseX, mouseY) || confirmPopup != null;

        if (scrollBar.updateAndRender(ctx, mouseX, mouseY, delta, client.getWindow().getHandle())) {
            int newOffset = (int) Math.round(scrollBar.getScrollPercentage() * maxScroll);
            if (isLocal) localScrollOffset = newOffset; else cloudScrollOffset = newOffset;
            scrollOffset = newOffset;
        }

        ctx.enableScissor(panelX, listY, panelX + panelW, listY + listH);
        int end = Math.min(scrollOffset + VISIBLE_ROWS, saves.size());
        for (int i = scrollOffset; i < end; i++) {
            int ry = listY + (i - scrollOffset) * ROW_HEIGHT;
            if (!blockHover && i == selectedIndex) ctx.fill(panelX, ry - 1, panelX + panelW, ry + ROW_HEIGHT - 2, 0x66FFFFFF);

            String worldName, info;
            if (isLocal) {
                LocalSave s = (LocalSave) saves.get(i);
                worldName = s.worldName;
                info = formatBytes(s.sizeBytes) + " \u2022 " + shortDateMillis(s.lastModified);
                boolean watching = WatchManager.isWatching(worldName);
                int starColor = watching ? 0xFFFFDD44 : 0xFF383838;
                int starX = panelX + panelW - 12, starY = ry + 7;
                ctx.drawTextWithShadow(textRenderer, Text.literal("\u2605"), starX, starY, starColor);
                if (mouseX >= starX - 1 && mouseX < starX + 8 && mouseY >= starY && mouseY < starY + 9)
                    starTooltipText = watching ? "Remove from favorite" : "Add to favorite";
            } else {
                CloudSave s = (CloudSave) saves.get(i);
                worldName = s.worldName;
                info = formatBytes(s.fileSizeBytes) + " \u2022 " + shortDate(s.updatedAt);
            }
            ctx.drawTextWithShadow(textRenderer, Text.literal(safe(worldName)), panelX + 4, ry + 2, 0xFFDDDDDD);
            ctx.drawTextWithShadow(textRenderer, Text.literal(info), panelX + 4, ry + 12, 0xFF888888);
            ctx.fill(panelX, ry + ROW_HEIGHT - 2, panelX + panelW, ry + ROW_HEIGHT - 1, 0x22FFFFFF);
        }
        ctx.disableScissor();
    }

    private void renderProgressBar(DrawContext ctx, int cx, int statusY) {
        long bytes = ACTIVE.bytes, total = ACTIVE.total;
        double speed = ACTIVE.speedBps;
        int barW = 360, barH = 8, bx = cx - barW / 2, by = statusY + 14;
        ctx.fill(bx, by, bx + barW, by + barH, 0xFF444444);
        if (total > 0) {
            double frac = Math.min(1.0, (double) bytes / total);
            ctx.fill(bx, by, bx + (int) (barW * frac), by + barH, 0xFFCCCCCC);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal((int) Math.min(100, bytes * 100.0 / total) + "%"), cx, by - 10, 0xFFFFFFFF);
            String info = formatBytes(bytes) + " / " + formatBytes(total);
            if (speed > 1) {
                long etaSec = (long) Math.ceil(Math.max(0L, total - bytes) / Math.max(1.0, speed));
                info += " \u2022 " + formatBytes((long) speed) + "/s \u2022 ETA " + formatDuration(etaSec);
            }
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(info), cx, by + barH + 2, 0xFFCCCCCC);
        }
    }

    private void updateButtonStates() {
        boolean opActive = ACTIVE.isActive();
        boolean hasLocal = localSelectedIndex >= 0 && localSelectedIndex < localSaves.size();
        boolean hasCloud = cloudSelectedIndex >= 0 && cloudSelectedIndex < cloudSaves.size();
        if (uploadBtn != null) uploadBtn.active = hasLocal && !opActive;
        if (downloadBtn != null) downloadBtn.active = hasCloud && !opActive;
        if (deleteBtn != null) deleteBtn.active = (hasLocal || hasCloud) && !opActive;
        if (refreshBtn != null) refreshBtn.active = !localLoading && !cloudLoading;
    }

    // ── Input handling ──

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean consumed) {
        double mx = click.x(), my = click.y();
        if (confirmPopup != null) return confirmPopup.mouseClicked(click, consumed);
        if (toastManager.mouseClicked(click, consumed)) return true;
        if (toastManager.isMouseOverToast(mx, my) || consumed) return true;
        if (super.mouseClicked(click, false)) return true;

        if (click.button() == 0 && !localLoading && !cloudLoading && !ACTIVE.dlActive && !ACTIVE.upActive) {
            int idx = clickedRowIndex(mx, my, localPanelX, localPanelW, localScrollOffset, localSaves.size());
            if (idx >= 0) {
                int starX = localPanelX + localPanelW - 14;
                if (mx >= starX && mx < starX + 14) {
                    LocalSave s = localSaves.get(idx);
                    boolean nowWatching = !WatchManager.isWatching(s.worldName);
                    WatchManager.setWatching(s.worldName, s.dir, nowWatching);
                    return true;
                }
                localSelectedIndex = idx; cloudSelectedIndex = -1; return true;
            }
            idx = clickedRowIndex(mx, my, cloudPanelX, cloudPanelW, cloudScrollOffset, cloudSaves.size());
            if (idx >= 0) { cloudSelectedIndex = idx; localSelectedIndex = -1; return true; }
        }
        return false;
    }

    private int clickedRowIndex(double mx, double my, int panelX, int panelW, int scrollOffset, int count) {
        if (mx < panelX || mx >= panelX + panelW || my < listY || my >= listY + listH) return -1;
        int rowIdx = (int) ((my - listY) / ROW_HEIGHT) + scrollOffset;
        if (rowIdx < 0 || rowIdx >= count) return -1;
        int clickY = listY + ((rowIdx - scrollOffset) * ROW_HEIGHT);
        return (my >= clickY && my < clickY + ROW_HEIGHT) ? rowIdx : -1;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double h, double v) {
        if (confirmPopup != null && confirmPopup.mouseScrolled(mouseX, mouseY, h, v)) return true;
        if (mouseX >= localPanelX && mouseX < localPanelX + localPanelW + 20) {
            localScrollOffset = clampScroll(localScrollOffset - (int) v, localSaves.size());
            return true;
        }
        if (mouseX >= cloudPanelX && mouseX < cloudPanelX + cloudPanelW + 20) {
            cloudScrollOffset = clampScroll(cloudScrollOffset - (int) v, cloudSaves.size());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, h, v);
    }

    private int clampScroll(int offset, int count) {
        return Math.max(0, Math.min(Math.max(0, count - VISIBLE_ROWS), offset));
    }

    @Override
    public boolean shouldCloseOnEsc() { return true; }

    @Override
    public void close() {
        if (confirmPopup != null) {
            confirmPopup = null;
            localLoading = false;
            cloudLoading = false;
        } else {
            closeScreen();
        }
    }

    // ── Navigation & utilities ──

    private void closeScreen() {
        if (client == null) return;
        closed = true;
        autoUploadWorld = null;
        ACTIVE.upActive = false;
        ACTIVE.dlActive = false;
        ACTIVE.zipping = false;
        ACTIVE.unzipping = false;
        Screen dest;
        if (parent instanceof SelectWorldScreen) {
            Screen grandParent = ((SelectWorldScreenAccessor) parent).getParentScreen();
            dest = new SelectWorldScreen(grandParent);
        } else {
            dest = new SelectWorldScreen(ScreenUtils.resolveRootParent(parent));
        }
        client.setScreen(dest);
    }

    private void openSavesFolder() {
        try {
            Path savesDir = client.runDirectory.toPath().resolve("saves");
            Files.createDirectories(savesDir);
            net.minecraft.util.Util.getOperatingSystem().open(savesDir.toFile());
        } catch (Exception e) {
            SaveManagerMod.LOGGER.warn("Failed to open saves folder - {}", extractErrorMessage(e));
            toastManager.showError("Failed to open saves folder");
        }
    }

    private void runOnClient(Runnable r) { if (client != null) client.execute(() -> { if (!closed) r.run(); }); }

    private String computeQuotaLine() {
        long used = cloudSaves.stream().mapToLong(s -> Math.max(0L, s.fileSizeBytes)).sum();
        return formatBytes(used) + " of " + quotaFormatted + " (" + formatBytes(Math.max(0L, quotaBytes - used)) + " left)";
    }

    private static long parseQuotaBytes(String formatted) {
        if (formatted == null || formatted.isEmpty()) return 5L * 1024L * 1024L * 1024L;
        try {
            String[] parts = formatted.trim().split("\\s+");
            if (parts.length >= 2) {
                double value = Double.parseDouble(parts[0]);
                long mult = switch (parts[1].toUpperCase()) {
                    case "B" -> 1L; case "KB" -> 1024L; case "MB" -> 1024L * 1024L;
                    case "GB" -> 1024L * 1024L * 1024L; case "TB" -> 1024L * 1024L * 1024L * 1024L;
                    default -> 1024L * 1024L * 1024L;
                };
                return (long) (value * mult);
            }
        } catch (Exception ignored) {}
        return 5L * 1024L * 1024L * 1024L;
    }

    // ── File I/O helpers ──

    private static void unzipSmart(Path zipFile, Path targetBase) throws Exception {
        String root = detectSingleRootDir(zipFile);
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(Files.newInputStream(zipFile))) {
            for (java.util.zip.ZipEntry e; (e = zis.getNextEntry()) != null; ) {
                if (e.isDirectory()) continue;
                String name = e.getName().replace('\\', '/');
                if (root != null && name.startsWith(root + "/")) name = name.substring(root.length() + 1);
                if (name.isBlank()) continue;
                Path out = targetBase.resolve(name).normalize();
                if (!out.startsWith(targetBase)) throw new IllegalArgumentException("Blocked zip entry: " + name);
                Files.createDirectories(out.getParent());
                Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                zis.closeEntry();
            }
        }
    }

    private static String detectSingleRootDir(Path zipFile) throws Exception {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zipFile.toFile())) {
            String root = null;
            var en = zf.entries();
            while (en.hasMoreElements()) {
                String name = en.nextElement().getName().replace('\\', '/');
                if (name.isBlank()) continue;
                String top = name.split("/", 2)[0];
                if (top.isBlank()) return null;
                if (root == null) root = top;
                else if (!root.equals(top)) return null;
            }
            return root;
        }
    }

    private static Path zipWorld(Path worldDir, String worldName) throws Exception {
        Path zip = Files.createTempFile("savemanager-" + worldName + "-", ".zip");
        try (var zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip, StandardOpenOption.WRITE))) {
            Files.walkFileTree(worldDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                    Path rel = worldDir.relativize(file);
                    if ("session.lock".equalsIgnoreCase(rel.getFileName().toString())) return FileVisitResult.CONTINUE;
                    zos.putNextEntry(new java.util.zip.ZipEntry(rel.toString().replace('\\', '/')));
                    Files.copy(file, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            try { Files.deleteIfExists(zip); } catch (Exception ignored) {}
            throw e;
        }
        return zip;
    }

    private static void deleteDirectoryRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws java.io.IOException { Files.delete(f); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path d, java.io.IOException e) throws java.io.IOException { if (e != null) throw e; Files.delete(d); return FileVisitResult.CONTINUE; }
        });
    }

    // ── JSON helpers ──

    private static JsonArray findArray(JsonObject obj, String... names) {
        for (String n : names) { if (!obj.has(n)) continue; var e = obj.get(n); if (e != null && e.isJsonArray()) return e.getAsJsonArray(); }
        return null;
    }

    private static String getString(JsonObject obj, String... names) {
        for (String n : names) { if (!obj.has(n)) continue; var e = obj.get(n); if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) return e.getAsString(); }
        return "";
    }

    private static long getLong(JsonObject obj, String... names) {
        for (String n : names) { if (!obj.has(n)) continue; var e = obj.get(n); if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return e.getAsLong(); }
        return 0L;
    }

    // ── Tiny spinner for quota loading ──

    private static float tinySpinnerAngle = 0f;

    private void renderTinySpinner(DrawContext ctx, int cx, int cy, float delta) {
        tinySpinnerAngle = (tinySpinnerAngle + 6f * delta) % 360f;
        for (int i = 0; i < 8; i++) {
            double rad = Math.toRadians(tinySpinnerAngle + i * 45f);
            int x = (int) (cx + Math.cos(rad) * 4), y = (int) (cy + Math.sin(rad) * 4);
            int color = (int) ((1f - i * 0.125f) * 255) << 24 | 0x00AAAAAA;
            ctx.fill(x - 1, y - 1, x + 1, y + 1, color);
        }
    }

    // ── Data classes ──

    static class LocalSave {
        final Path dir;
        final String worldName;
        final long sizeBytes;
        final long lastModified;

        LocalSave(Path dir, String worldName, long sizeBytes, long lastModified) {
            this.dir = dir; this.worldName = worldName; this.sizeBytes = sizeBytes; this.lastModified = lastModified;
        }

        static LocalSave fromDir(Path dir) {
            String name = dir.getFileName() != null ? dir.getFileName().toString() : dir.toString();
            long lm = 0L; try { lm = Files.getLastModifiedTime(dir).toMillis(); } catch (Exception ignored) {}
            long size = 0L; try { size = computeDirSize(dir); } catch (Exception ignored) {}
            return new LocalSave(dir, name, size, lm);
        }

        static long computeDirSize(Path dir) throws Exception {
            final long[] sum = {0L};
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) { try { sum[0] += Files.size(f); } catch (Exception ignored) {} return FileVisitResult.CONTINUE; }
            });
            return sum[0];
        }
    }

    static class CloudSave {
        String id, worldName, createdAt, updatedAt;
        long fileSizeBytes;

        static CloudSave from(JsonObject o) {
            CloudSave s = new CloudSave();
            s.id = getString(o, "id", "saveId", "guid");
            s.worldName = getString(o, "worldName", "name", "world", "title");
            s.fileSizeBytes = getLong(o, "sizeBytes", "fileSizeBytes", "fileSize", "size", "bytes");
            s.createdAt = getString(o, "createdAt", "created", "created_on");
            s.updatedAt = getString(o, "updatedAt", "updated", "updated_on", "lastModified");
            return s;
        }
    }
}
