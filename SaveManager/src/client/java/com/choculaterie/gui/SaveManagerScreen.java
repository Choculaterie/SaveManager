package com.choculaterie.gui;
import com.choculaterie.SaveManagerMod;
import com.choculaterie.network.NetworkManager;
import com.choculaterie.widget.ConfirmPopup;
import com.choculaterie.widget.CustomButton;
import com.choculaterie.widget.LoadingSpinner;
import com.choculaterie.widget.ScrollBar;
import com.choculaterie.widget.ToastManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.text.Text;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class SaveManagerScreen extends Screen {
    private static final int ROW_HEIGHT = 24;
    private static final int VISIBLE_ROWS = 8;
    private static final int PANEL_GAP = 20;
    private static final long QUOTA_LIMIT_BYTES = 5L * 1024L * 1024L * 1024L;
    private static final long RELOAD_THRESHOLD_MS = 15 * 60 * 1000L; // 15 minutes

    private static long lastLoadTimeMs = 0L;
    private static final List<LocalSave> cachedLocalSaves = new ArrayList<>();
    private static final List<CloudSave> cachedCloudSaves = new ArrayList<>();

    private final Screen parent;
    private final NetworkManager networkManager = new NetworkManager();
    private final ToastManager toastManager;
    private final LoadingSpinner spinner;
    private final List<LocalSave> localSaves = new ArrayList<>();
    private final List<CloudSave> cloudSaves = new ArrayList<>();
    private ScrollBar localScrollBar;
    private ScrollBar cloudScrollBar;
    private int localScrollOffset = 0;
    private int cloudScrollOffset = 0;
    private int localSelectedIndex = -1;
    private int cloudSelectedIndex = -1;
    private boolean localLoading = true;
    private boolean cloudLoading = true;
    private int localPanelX, localPanelW, cloudPanelX, cloudPanelW, listY, listH;
    private ConfirmPopup confirmPopup = null;
    private static final ActiveOp ACTIVE = new ActiveOp();
    private static final class ActiveOp {
        volatile boolean dlActive = false;
        volatile boolean upActive = false;
        volatile boolean zipping = false;
        volatile boolean unzipping = false;
        volatile long bytes = 0L;
        volatile long total = -1L;
        volatile long lastBytes = 0L;
        volatile long lastTickNanos = 0L;
        volatile double speedBps = 0.0;
    }
    public SaveManagerScreen(Screen parent) {
        super(Text.literal("Save Manager"));
        this.parent = parent;
        this.toastManager = new ToastManager(null);
        this.spinner = new LoadingSpinner(0, 0);
    }
    @Override
    protected void init() {
        initToastManager();
        int cx = this.width / 2;
        int btnSize = 20;
        int margin = 6;
        addCustomButton(margin, margin, btnSize, btnSize, Text.literal("←"), b -> closeScreen());
        addCustomButton(margin + btnSize + 5, margin, btnSize, btnSize, Text.literal("🔄"), b -> refresh());
        addCustomButton(this.width - margin - btnSize, margin, btnSize, btnSize, Text.literal("⚙"),
                b -> client.setScreen(new AccountLinkingScreen(this)));
        int totalW = this.width - 60;
        int panelW = (totalW - PANEL_GAP) / 2;
        localPanelX = 30;
        localPanelW = panelW;
        cloudPanelX = localPanelX + panelW + PANEL_GAP;
        cloudPanelW = panelW;
        listY = 70;
        listH = VISIBLE_ROWS * ROW_HEIGHT;
        localScrollBar = new ScrollBar(localPanelX + localPanelW + 4, listY, listH);
        cloudScrollBar = new ScrollBar(cloudPanelX + cloudPanelW + 4, listY, listH);
        int bottomY = this.height - 28;
        int btnW = 80;
        addCustomButton(localPanelX + (localPanelW - btnW) / 2, bottomY, btnW, 20, Text.literal("Upload"), b -> onUpload());
        addCustomButton(cloudPanelX + (cloudPanelW - btnW * 2 - 10) / 2, bottomY, btnW, 20, Text.literal("Download"), b -> onDownload());
        addCustomButton(cloudPanelX + (cloudPanelW - btnW * 2 - 10) / 2 + btnW + 10, bottomY, btnW, 20, Text.literal("Delete"), b -> onDelete());
        String apiKey = loadApiKeyFromDisk();
        if (apiKey == null || apiKey.isBlank()) {
            client.setScreen(new AccountLinkingScreen(this));
            return;
        }
        networkManager.setApiKey(apiKey);
        long now = System.currentTimeMillis();
        boolean shouldReload = (now - lastLoadTimeMs) > RELOAD_THRESHOLD_MS || cachedLocalSaves.isEmpty();
        if (shouldReload) {
            fetchLocalSaves();
            fetchCloudSaves();
        } else {
            localSaves.clear();
            localSaves.addAll(cachedLocalSaves);
            cloudSaves.clear();
            cloudSaves.addAll(cachedCloudSaves);
            localLoading = false;
            cloudLoading = false;
        }
    }

    private void addCustomButton(int x, int y, int width, int height, Text message, CustomButton.PressAction onPress) {
        CustomButton button = new CustomButton(x, y, width, height, message, onPress);
        button.setToastManager(toastManager);
        addDrawableChild(button);
    }

    private void initToastManager() {
        try {
            var f = ToastManager.class.getDeclaredField("client");
            f.setAccessible(true);
            f.set(toastManager, client);
        } catch (Exception ignored) {}
    }
    private void refresh() {
        localSelectedIndex = -1;
        cloudSelectedIndex = -1;
        localScrollOffset = 0;
        cloudScrollOffset = 0;
        fetchLocalSaves();
        fetchCloudSaves();
    }
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
                String cleanError = extractErrorMessage(e);
                SaveManagerMod.LOGGER.warn("LocalSaveManager: error scanning saves - {}", cleanError);
            }
            runOnClient(() -> {
                localSaves.clear();
                localSaves.addAll(tmp);
                cachedLocalSaves.clear();
                cachedLocalSaves.addAll(tmp);
                lastLoadTimeMs = System.currentTimeMillis();
                localScrollOffset = 0;
                localSelectedIndex = -1;
                localLoading = false;
            });
        });
    }
    private void fetchCloudSaves() {
        cloudLoading = true;
        networkManager.listWorldSaves().whenComplete((json, err) -> {
            if (err != null) {
                String cleanError = extractErrorMessage(err);
                SaveManagerMod.LOGGER.warn("CloudSaveManager: list failed - {}", cleanError);
                runOnClient(() -> {
                    cloudLoading = false;
                    cloudSaves.clear();
                    toastManager.showError("Cloud list failed");
                });
                return;
            }
            List<CloudSave> tmp = new ArrayList<>();
            try {
                JsonArray arr = findArray(json, "saves", "items", "data", "list", "worlds");
                if (arr == null && json.has("result") && json.get("result").isJsonObject()) {
                    arr = findArray(json.getAsJsonObject("result"), "saves", "items", "data", "list", "worlds");
                }
                if (arr == null) {
                    for (var e : json.entrySet()) {
                        if (e.getValue() != null && e.getValue().isJsonArray()) {
                            arr = e.getValue().getAsJsonArray();
                            break;
                        }
                    }
                }
                if (arr != null) {
                    for (int i = 0; i < arr.size(); i++) {
                        if (arr.get(i).isJsonObject()) tmp.add(CloudSave.from(arr.get(i).getAsJsonObject()));
                    }
                }
            } catch (Throwable parseEx) {
                String cleanError = extractErrorMessage(parseEx);
                SaveManagerMod.LOGGER.warn("CloudSaveManager: parse error - {}", cleanError);
            }
            runOnClient(() -> {
                cloudSaves.clear();
                cloudSaves.addAll(tmp);
                cachedCloudSaves.clear();
                cachedCloudSaves.addAll(tmp);
                cloudLoading = false;
                cloudScrollOffset = 0;
                cloudSelectedIndex = -1;
            });
        });
    }
    private void onUpload() {
        if (localSelectedIndex < 0 || localSelectedIndex >= localSaves.size()) return;
        if (networkManager.getApiKey() == null || networkManager.getApiKey().isBlank()) {
            client.setScreen(new AccountLinkingScreen(this));
            return;
        }
        LocalSave s = localSaves.get(localSelectedIndex);
        localLoading = true;
        networkManager.listWorldSaveNames().whenComplete((names, err) -> runOnClient(() -> {
            if (err != null) {
                localLoading = false;
                client.setScreen(new AccountLinkingScreen(this));
                return;
            }
            String sanitized = sanitizeFolderName(s.worldName);
            boolean exists = false;
            if (names != null) {
                for (String n : names) {
                    if (n != null && (n.equalsIgnoreCase(s.worldName) || (!sanitized.isEmpty() && n.equalsIgnoreCase(sanitized)))) {
                        exists = true;
                        break;
                    }
                }
            }
            if (!exists) {
                beginZipAndUpload(s);
                return;
            }
            confirmPopup = new ConfirmPopup(
                this,
                "Overwrite Cloud Save?",
                "A save named \"" + s.worldName + "\" already exists in the cloud. Overwrite?",
                () -> {
                    confirmPopup = null;
                    beginZipAndUpload(s);
                },
                () -> {
                    confirmPopup = null;
                    localLoading = false;
                },
                "Overwrite"
            );
        }));
    }
    private void beginZipAndUpload(LocalSave s) {
        ACTIVE.upActive = true;
        ACTIVE.zipping = true;
        ACTIVE.bytes = 0L;
        ACTIVE.total = -1L;
        ACTIVE.lastTickNanos = System.nanoTime();
        ACTIVE.lastBytes = 0L;
        ACTIVE.speedBps = 0.0;
        localLoading = true;
        new Thread(() -> {
            Path zip;
            try {
                zip = zipWorld(s.dir, s.worldName.replaceAll("[\\\\/:*?\"<>|]+", "_"));
            } catch (Exception ex) {
                String cleanError = extractErrorMessage(ex);
                SaveManagerMod.LOGGER.warn("ZipManager: zip failed - {}", cleanError);
                runOnClient(() -> {
                    ACTIVE.upActive = false;
                    ACTIVE.zipping = false;
                    localLoading = false;
                    toastManager.showError("Zip failed");
                });
                return;
            }
            runOnClient(() -> startUpload(zip, s.worldName));
        }, "SaveManager-zip").start();
    }
    private void startUpload(Path zipFile, String worldName) {
        ACTIVE.zipping = false;
        ACTIVE.bytes = 0L;
        ACTIVE.total = -1L;
        ACTIVE.lastTickNanos = System.nanoTime();
        ACTIVE.lastBytes = 0L;
        ACTIVE.speedBps = 0.0;
        try {
            networkManager.uploadWorldSave(worldName, zipFile, (sent, total) -> {
                ACTIVE.bytes = Math.max(0L, sent);
                if (total > 0) ACTIVE.total = total;
                updateSpeed();
            }).whenComplete((json, err) -> runOnClient(() -> {
                try { Files.deleteIfExists(zipFile); } catch (Throwable ignored) {}
                ACTIVE.upActive = false;
                ACTIVE.zipping = false;
                localLoading = false;
                if (err != null) {
                    String cleanError = extractErrorMessage(err);
                    SaveManagerMod.LOGGER.warn("UploadManager: upload failed - {}", cleanError);
                    toastManager.showError("Upload failed");
                } else {
                    toastManager.showSuccess("Upload complete: " + worldName);
                    fetchLocalSaves();
                    fetchCloudSaves();
                }
            }));
        } catch (Throwable t) {
            try { Files.deleteIfExists(zipFile); } catch (Throwable ignored) {}
            ACTIVE.upActive = false;
            localLoading = false;
            toastManager.showError("Upload failed");
        }
    }
    private void onDownload() {
        if (cloudSelectedIndex < 0 || cloudSelectedIndex >= cloudSaves.size()) return;
        CloudSave s = cloudSaves.get(cloudSelectedIndex);
        cloudLoading = true;
        Path savesDir = client.runDirectory.toPath().resolve("saves");
        try { Files.createDirectories(savesDir); } catch (Exception ignored) {}
        Path tmpDir;
        try {
            tmpDir = Files.createTempDirectory("savemanager-dl-");
        } catch (Exception e) {
            toastManager.showError("Failed to prepare temp dir");
            cloudLoading = false;
            return;
        }
        ACTIVE.dlActive = true;
        ACTIVE.unzipping = false;
        ACTIVE.bytes = 0L;
        ACTIVE.total = s.fileSizeBytes > 0 ? s.fileSizeBytes : -1L;
        ACTIVE.lastTickNanos = System.nanoTime();
        ACTIVE.lastBytes = 0L;
        ACTIVE.speedBps = 0.0;
        networkManager.downloadWorldSave(s.id, tmpDir, (downloaded, total) -> {
            ACTIVE.bytes = Math.max(0L, downloaded);
            if (total > 0) ACTIVE.total = total;
            updateSpeed();
        }).whenComplete((zipPath, err) -> {
            ACTIVE.dlActive = false;
            if (err != null) {
                String cleanError = extractErrorMessage(err);
                SaveManagerMod.LOGGER.warn("DownloadManager: download failed - {}", cleanError);
                runOnClient(() -> {
                    cloudLoading = false;
                    toastManager.showError("Download failed");
                });
                return;
            }
            runOnClient(() -> ACTIVE.unzipping = true);
            String baseName = sanitizeFolderName(s.worldName);
            if (baseName.isEmpty()) baseName = "world";
            Path targetBase = ensureUniqueDir(savesDir, baseName);
            try {
                Files.createDirectories(targetBase);
                unzipSmart(zipPath, targetBase);
                runOnClient(() -> {
                    toastManager.showSuccess("Download complete");
                    fetchLocalSaves();
                });
            } catch (Exception ex) {
                String cleanError = extractErrorMessage(ex);
                SaveManagerMod.LOGGER.warn("UnzipManager: unzip failed - {}", cleanError);
                runOnClient(() -> toastManager.showError("Unzip failed"));
            } finally {
                try { Files.deleteIfExists(zipPath); } catch (Exception ignored) {}
                try { Files.deleteIfExists(tmpDir); } catch (Exception ignored) {}
                runOnClient(() -> {
                    ACTIVE.unzipping = false;
                    cloudLoading = false;
                });
            }
        });
    }
    private void onDelete() {
        if (cloudSelectedIndex < 0 || cloudSelectedIndex >= cloudSaves.size()) return;
        CloudSave s = cloudSaves.get(cloudSelectedIndex);

        confirmPopup = new ConfirmPopup(
            this,
            "Delete Cloud Save?",
            "Are you sure you want to permanently delete \"" + safe(s.worldName) + "\"?",
            () -> {
                confirmPopup = null;
                cloudLoading = true;
                networkManager.deleteWorldSave(s.id).whenComplete((resp, err) -> runOnClient(() -> {
                    if (err != null) {
                        String cleanError = extractErrorMessage(err);
                        SaveManagerMod.LOGGER.warn("DeleteManager: delete failed - {}", cleanError);
                        toastManager.showError("Delete failed");
                        cloudLoading = false;
                        return;
                    }
                    toastManager.showSuccess("Deleted");
                    cloudLoading = false;
                    cloudSelectedIndex = -1;
                    fetchCloudSaves();
                }));
            },
            () -> {
                confirmPopup = null;
            },
            "Delete"
        );
    }
    private void updateSpeed() {
        long now = System.nanoTime();
        long dtNs = now - ACTIVE.lastTickNanos;
        long dBytes = ACTIVE.bytes - ACTIVE.lastBytes;
        if (dtNs > 50_000_000L) {
            double instBps = dBytes > 0 ? (dBytes * 1_000_000_000.0) / dtNs : 0.0;
            ACTIVE.speedBps = ACTIVE.speedBps <= 0 ? instBps : (0.2 * instBps + 0.8 * ACTIVE.speedBps);
            ACTIVE.lastTickNanos = now;
            ACTIVE.lastBytes = ACTIVE.bytes;
        }
    }
    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = this.width / 2;
        ctx.drawCenteredTextWithShadow(textRenderer, title, cx, 10, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(computeQuotaLine()), cx, 22, 0xFFAAAAAA);
        ctx.drawTextWithShadow(textRenderer, Text.literal("Local Saves"), localPanelX, 50, 0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("Cloud Saves"), cloudPanelX, 50, 0xFFFFFFFF);
        renderLocalPanel(ctx, mouseX, mouseY, delta);
        renderCloudPanel(ctx, mouseX, mouseY, delta);
        boolean opActive = ACTIVE.dlActive || ACTIVE.upActive || ACTIVE.zipping || ACTIVE.unzipping;
        if (opActive) {
            int statusY = this.height - 70;
            if (ACTIVE.zipping || ACTIVE.unzipping || ACTIVE.bytes <= 0L) {
                spinner.setPosition(cx - 16, statusY);
                spinner.render(ctx, mouseX, mouseY, delta);
                String msg = ACTIVE.zipping ? "Zipping..." : ACTIVE.unzipping ? "Unzipping..." : "Preparing...";
                ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(msg), cx, statusY + 40, 0xFFFFFFFF);
            } else {
                renderProgressBar(ctx, cx, statusY);
            }
        } else if (localLoading || cloudLoading) {
            int statusY = this.height - 70;
            spinner.setPosition(cx - 16, statusY);
            spinner.render(ctx, mouseX, mouseY, delta);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Loading..."), cx, statusY + 40, 0xFFFFFFFF);
        }
        toastManager.render(ctx, delta, mouseX, mouseY);

        if (confirmPopup != null) {
            confirmPopup.render(ctx, mouseX, mouseY, delta);
        }
    }
    private void renderLocalPanel(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderSavePanel(ctx, mouseX, mouseY, delta, true);
    }

    private void renderCloudPanel(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderSavePanel(ctx, mouseX, mouseY, delta, false);
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

        long windowHandle = client.getWindow().getHandle();
        if (scrollBar.updateAndRender(ctx, mouseX, mouseY, delta, windowHandle)) {
            int newOffset = (int) Math.round(scrollBar.getScrollPercentage() * maxScroll);
            if (isLocal) localScrollOffset = newOffset;
            else cloudScrollOffset = newOffset;
            scrollOffset = newOffset;
        }

        ctx.enableScissor(panelX, listY, panelX + panelW, listY + listH);
        int end = Math.min(scrollOffset + VISIBLE_ROWS, saves.size());

        for (int i = scrollOffset; i < end; i++) {
            int ry = listY + (i - scrollOffset) * ROW_HEIGHT;

            boolean isHovered = !blockHover && i == selectedIndex;
            if (isHovered) {
                ctx.fill(panelX, ry - 1, panelX + panelW, ry + ROW_HEIGHT - 2, 0x66FFFFFF);
            }

            String worldName;
            String info;
            if (isLocal) {
                LocalSave s = (LocalSave) saves.get(i);
                worldName = s.worldName;
                info = formatBytes(s.sizeBytes) + " • " + shortDateMillis(s.lastModified);
            } else {
                CloudSave s = (CloudSave) saves.get(i);
                worldName = s.worldName;
                info = formatBytes(s.fileSizeBytes) + " • " + shortDate(s.updatedAt);
            }

            ctx.drawTextWithShadow(textRenderer, Text.literal(safe(worldName)), panelX + 4, ry + 2, 0xFFDDDDDD);
            ctx.drawTextWithShadow(textRenderer, Text.literal(info), panelX + 4, ry + 12, 0xFF888888);
            ctx.fill(panelX, ry + ROW_HEIGHT - 2, panelX + panelW, ry + ROW_HEIGHT - 1, 0x22FFFFFF);
        }

        ctx.disableScissor();
    }
    private void renderProgressBar(DrawContext ctx, int cx, int statusY) {
        long bytes = ACTIVE.bytes;
        long total = ACTIVE.total;
        double speed = ACTIVE.speedBps;
        int barW = 360;
        int barH = 8;
        int bx = cx - (barW / 2);
        int by = statusY + 14;
        ctx.fill(bx, by, bx + barW, by + barH, 0xFF444444);
        if (total > 0) {
            double frac = Math.min(1.0, (double) bytes / total);
            ctx.fill(bx, by, bx + (int) (barW * frac), by + barH, 0xFFCCCCCC);
            int pct = (int) Math.min(100, (bytes * 100.0) / total);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(pct + "%"), cx, by - 10, 0xFFFFFFFF);
            String info = formatBytes(bytes) + " / " + formatBytes(total);
            if (speed > 1) {
                long remaining = Math.max(0L, total - bytes);
                long etaSec = (long) Math.ceil(remaining / Math.max(1.0, speed));
                info += " • " + formatBytes((long) speed) + "/s • ETA " + formatDuration(etaSec);
            }
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(info), cx, by + barH + 2, 0xFFCCCCCC);
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean consumed) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();

        if (confirmPopup != null) {
            return confirmPopup.mouseClicked(click, consumed);
        }

        if (toastManager.mouseClicked(click, consumed)) {
            return true;
        }

        if (toastManager.isMouseOverToast(mouseX, mouseY)) {
            return true;
        }

        if (consumed) {
            return true;
        }

        if (super.mouseClicked(click, false)) {
            return true;
        }

        if (button == 0 && !localLoading && !cloudLoading && !ACTIVE.dlActive && !ACTIVE.upActive) {
            if (mouseX >= localPanelX && mouseX < localPanelX + localPanelW && mouseY >= listY && mouseY < listY + listH) {
                int rowIdx = (int) ((mouseY - listY) / ROW_HEIGHT) + localScrollOffset;
                if (rowIdx >= 0 && rowIdx < localSaves.size()) {
                    localSelectedIndex = rowIdx;
                    cloudSelectedIndex = -1;
                    return true;
                }
            }
            if (mouseX >= cloudPanelX && mouseX < cloudPanelX + cloudPanelW && mouseY >= listY && mouseY < listY + listH) {
                int rowIdx = (int) ((mouseY - listY) / ROW_HEIGHT) + cloudScrollOffset;
                if (rowIdx >= 0 && rowIdx < cloudSaves.size()) {
                    cloudSelectedIndex = rowIdx;
                    localSelectedIndex = -1;
                    return true;
                }
            }
        }
        return false;
    }
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (confirmPopup != null) {
            if (confirmPopup.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
                return true;
            }
        }

        if (mouseX >= localPanelX && mouseX < localPanelX + localPanelW + 20) {
            int maxScroll = Math.max(0, localSaves.size() - VISIBLE_ROWS);
            localScrollOffset = Math.max(0, Math.min(maxScroll, localScrollOffset - (int) verticalAmount));
            return true;
        }
        if (mouseX >= cloudPanelX && mouseX < cloudPanelX + cloudPanelW + 20) {
            int maxScroll = Math.max(0, cloudSaves.size() - VISIBLE_ROWS);
            cloudScrollOffset = Math.max(0, Math.min(maxScroll, cloudScrollOffset - (int) verticalAmount));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }
    private String computeQuotaLine() {
        long used = cloudSaves.stream().mapToLong(s -> Math.max(0L, s.fileSizeBytes)).sum();
        long left = Math.max(0L, QUOTA_LIMIT_BYTES - used);
        return formatBytes(used) + " of 5 GB (" + formatBytes(left) + " left)";
    }
    private void closeScreen() {
        if (client != null) client.setScreen(new SelectWorldScreen(resolveRootParent(parent)));
    }
    private void runOnClient(Runnable r) {
        if (client != null) client.execute(r);
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

        msg = msg.replaceFirst("^Request failed: \\d+ - ", "");
        msg = msg.replaceFirst("^Upload failed: ", "");
        msg = msg.replaceFirst("^HTTP \\d+: ", "");

        if (msg.contains("{") && msg.contains("\"error\"")) {
            try {
                int start = msg.indexOf('{');
                int end = msg.lastIndexOf('}') + 1;
                if (start >= 0 && end > start) {
                    String jsonPart = msg.substring(start, end);
                    JsonObject json = new Gson().fromJson(jsonPart, JsonObject.class);
                    if (json != null && json.has("error")) {
                        return json.get("error").getAsString();
                    }
                }
            } catch (Exception ignored) {}
        }

        return msg;
    }

    private static String safe(String s) { return s == null ? "" : s; }
    private static String shortDate(String iso) {
        if (iso == null) return "";
        int t = iso.indexOf('T');
        return t > 0 ? iso.substring(0, t) : iso;
    }
    private static String shortDateMillis(long epochMillis) {
        if (epochMillis <= 0) return "";
        return DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMillis));
    }
    private static String formatBytes(long n) {
        if (n < 1024) return n + " B";
        int u = -1;
        double d = n;
        String[] units = {"KB", "MB", "GB", "TB"};
        do { d /= 1024; u++; } while (d >= 1024 && u < units.length - 1);
        return String.format(java.util.Locale.ROOT, "%.1f %s", d, units[u]);
    }
    private static String formatDuration(long seconds) {
        if (seconds <= 0) return "0s";
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        if (h > 0) return String.format("%dh %02dm", h, m);
        if (m > 0) return String.format("%dm %02ds", m, s);
        return String.format("%ds", s);
    }
    private static String sanitizeFolderName(String s) {
        if (s == null) return "";
        String clean = s.trim().replaceAll("[\\\\/:*?\"<>|]+", "_");
        return clean.isBlank() ? "" : clean;
    }
    private static Path ensureUniqueDir(Path parent, String baseName) {
        Path p = parent.resolve(baseName);
        if (!Files.exists(p)) return p;
        int idx = 1;
        while (true) {
            Path cand = parent.resolve(baseName + "-" + idx);
            if (!Files.exists(cand)) return cand;
            idx++;
        }
    }
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
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip, StandardOpenOption.WRITE))) {
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
        }
        return zip;
    }
    private static JsonArray findArray(JsonObject obj, String... names) {
        for (String n : names) {
            if (!obj.has(n)) continue;
            var e = obj.get(n);
            if (e != null && e.isJsonArray()) return e.getAsJsonArray();
        }
        return null;
    }
    private static String getString(JsonObject obj, String... names) {
        for (String n : names) {
            if (!obj.has(n)) continue;
            var e = obj.get(n);
            if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) return e.getAsString();
        }
        return "";
    }
    private static long getLong(JsonObject obj, String... names) {
        for (String n : names) {
            if (!obj.has(n)) continue;
            var e = obj.get(n);
            if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return e.getAsLong();
        }
        return 0L;
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
            } catch (Throwable ignored) { break; }
        }
        return p;
    }
    private String loadApiKeyFromDisk() {
        try {
            File configFile = new File(new File(MinecraftClient.getInstance().runDirectory, "config"), "save-manager-settings.json");
            if (!configFile.exists()) return null;
            try (FileReader reader = new FileReader(configFile)) {
                JsonObject json = new Gson().fromJson(reader, JsonObject.class);
                if (json == null) return null;
                if (json.has("encryptedApiToken")) return decrypt(json.get("encryptedApiToken").getAsString());
                if (json.has("apiToken")) return json.get("apiToken").getAsString();
            }
        } catch (Exception e) {
            String cleanError = extractErrorMessage(e);
            SaveManagerMod.LOGGER.warn("ConfigManager: error loading API key - {}", cleanError);
        }
        return null;
    }
    private String decrypt(String base64) throws Exception {
        byte[] data = java.util.Base64.getDecoder().decode(base64);
        if (data.length < 28) throw new IllegalArgumentException("Invalid data");
        byte[] iv = new byte[12];
        byte[] ct = new byte[data.length - 12];
        System.arraycopy(data, 0, iv, 0, 12);
        System.arraycopy(data, 12, ct, 0, ct.length);
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(MessageDigest.getInstance("SHA-256").digest("SaveManagerSecKey.v1".getBytes(StandardCharsets.UTF_8)), "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
    }
    private static class LocalSave {
        final Path dir;
        final String worldName;
        final long sizeBytes;
        final long lastModified;
        LocalSave(Path dir, String worldName, long sizeBytes, long lastModified) {
            this.dir = dir;
            this.worldName = worldName;
            this.sizeBytes = sizeBytes;
            this.lastModified = lastModified;
        }
        static LocalSave fromDir(Path dir) {
            String name = dir.getFileName() != null ? dir.getFileName().toString() : dir.toString();
            long lm = 0L;
            try { lm = Files.getLastModifiedTime(dir).toMillis(); } catch (Exception ignored) {}
            long size = 0L;
            try { size = computeDirSize(dir); } catch (Exception ignored) {}
            return new LocalSave(dir, name, size, lm);
        }
        static long computeDirSize(Path dir) throws Exception {
            final long[] sum = {0L};
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try { sum[0] += Files.size(file); } catch (Exception ignored) {}
                    return FileVisitResult.CONTINUE;
                }
            });
            return sum[0];
        }
    }
    private static class CloudSave {
        String id;
        String worldName;
        long fileSizeBytes;
        String createdAt;
        String updatedAt;
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

    public Screen getParent() {
        return parent;
    }
}
