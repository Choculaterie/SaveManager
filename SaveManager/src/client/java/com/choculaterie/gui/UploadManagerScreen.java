package com.choculaterie.gui;

import com.choculaterie.SaveManagerMod;
import com.choculaterie.network.NetworkManager;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.text.Text;

import java.io.File;
import java.io.FileReader;
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

public class UploadManagerScreen extends Screen {
    private final Screen parent;
    private final NetworkManager networkManager = new NetworkManager();

    // Top controls
    private ButtonWidget backBtn;
    private ButtonWidget refreshBtn;
    private ButtonWidget prevBtn;
    private ButtonWidget nextBtn;
    private ButtonWidget settingsBtn;

    // Bottom actions
    private ButtonWidget uploadBtn;

    private boolean loading = true;
    private String status = "Loading...";

    private final List<LocalSave> saves = new ArrayList<>();
    private int selectedIndex = -1;
    private int currentPage = 0;
    private static final int PAGE_SIZE = 6;

    private static final int COL_WORLD_W = 300;
    private static final int COL_SIZE_W = 100;
    private static final int COL_UPDATED_W = 180;

    private static final ActiveUp ACTIVE = new ActiveUp();
    private static final class ActiveUp {
        volatile boolean active = false;   // true while zipping/uploading
        volatile boolean zipping = false;  // true during zipping phase
        volatile long uploaded = 0L;       // file bytes sent
        volatile long total = -1L;         // total file bytes
        volatile long startNanos = 0L;
        volatile long lastTickNanos = 0L;
        volatile long lastBytes = 0L;
        volatile double speedBps = 0.0;
    }

    private final List<PressableWidget> rowHitBoxes = new ArrayList<>();

    public UploadManagerScreen(Screen parent) {
        super(Text.literal("Upload a Local World"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int bottomY = this.height - 28;
        int btnSize = 20;
        int margin = 6;

        this.backBtn = ButtonWidget.builder(Text.literal("←"), b -> {
            if (this.client != null) {
                Screen rootParent = sm$resolveWorldRootParent(this.parent);
                this.client.setScreen(new SelectWorldScreen(rootParent));
            }
        }).dimensions(10, 10, 20, 20).build();
        this.addDrawableChild(backBtn);

        this.refreshBtn = ButtonWidget.builder(Text.literal("\uD83D\uDD04"), b -> {
            selectedIndex = -1;
            fetchLocalSaves();
        }).dimensions(35, 10, 20, 20).build();
        this.addDrawableChild(refreshBtn);

        this.settingsBtn = ButtonWidget.builder(Text.literal("⚙"), b -> {
            if (this.client != null) {
                Screen rootParent = sm$resolveWorldRootParent(this.parent);
                this.client.setScreen(new AccountLinkingScreen(rootParent));
            }
        }).dimensions(this.width - margin - btnSize, margin, btnSize, btnSize).build();
        this.addDrawableChild(this.settingsBtn);

        this.prevBtn = ButtonWidget.builder(Text.literal("<"), b -> {
            if (currentPage > 0) {
                currentPage--;
                selectedIndex = -1;
                buildRowHitBoxes();
                rebuildPagerState();
                updateActionButtons();
            }
        }).dimensions(this.width - 55, bottomY, 20, 20).build();
        this.addDrawableChild(prevBtn);

        this.nextBtn = ButtonWidget.builder(Text.literal(">"), b -> {
            if ((currentPage + 1) * PAGE_SIZE < saves.size()) {
                currentPage++;
                selectedIndex = -1;
                buildRowHitBoxes();
                rebuildPagerState();
                updateActionButtons();
            }
        }).dimensions(this.width - 30, bottomY, 20, 20).build();
        this.addDrawableChild(nextBtn);

        this.uploadBtn = ButtonWidget.builder(Text.literal("Upload"), b -> onUploadClicked())
                .dimensions(cx - 50, bottomY, 100, 20).build();
        this.addDrawableChild(uploadBtn);

        String apiKey = loadApiKeyFromDisk();
        if (apiKey == null || apiKey.isBlank()) {
            loading = false;
            status = "No API key configured";
        } else {
            try { networkManager.setApiKey(apiKey); } catch (Throwable ignored) {}
            fetchLocalSaves();
        }

        buildRowHitBoxes();
        rebuildPagerState();
        updateActionButtons();
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        this.clearAndInit();
    }

    private void fetchLocalSaves() {
        loading = true;
        status = "Loading...";
        updateActionButtons();

        CompletableFuture.runAsync(() -> {
            List<LocalSave> tmp = new ArrayList<>();
            try {
                Path savesDir = this.client.runDirectory.toPath().resolve("saves");
                if (Files.exists(savesDir) && Files.isDirectory(savesDir)) {
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(savesDir)) {
                        for (Path p : ds) {
                            if (!Files.isDirectory(p)) continue;
                            LocalSave s = LocalSave.fromDir(p);
                            tmp.add(s);
                        }
                    }
                }
                // Sort by last modified desc
                tmp.sort(Comparator.comparingLong((LocalSave s) -> s.lastModified).reversed());
            } catch (Exception e) {
                SaveManagerMod.LOGGER.error("UploadManager: error scanning saves", e);
            }
            runOnClient(() -> {
                saves.clear();
                saves.addAll(tmp);
                currentPage = 0;
                selectedIndex = -1;
                loading = false;
                status = saves.isEmpty() ? "No local saves found" : "";
                buildRowHitBoxes();
                rebuildPagerState();
                updateActionButtons();
            });
        });
    }

    private void onUploadClicked() {
        LocalSave s = getSelected();
        if (s == null) return;

        if (networkManager.getApiKey() == null || networkManager.getApiKey().isBlank()) {
            // Defer to settings screen
            if (this.client != null) {
                Screen rootParent = sm$resolveWorldRootParent(this.parent);
                this.client.setScreen(new AccountLinkingScreen(rootParent));
            }
            return;
        }

        loading = true;
        status = "Preparing...";
        updateActionButtons();

        // First query remote names to check overwrite
        networkManager.listWorldSaveNames().whenComplete((names, err) -> runOnClient(() -> {
            if (err != null) {
                SaveManagerMod.LOGGER.warn("UploadManager: names list failed; proceeding", err);
                beginZipAndUpload(s);
                return;
            }
            String sanitized = sanitizeFolderName(s.worldName);
            boolean exists = false;
            if (names != null) {
                for (String n : names) {
                    if (n == null) continue;
                    if (n.equalsIgnoreCase(s.worldName) || (!sanitized.isEmpty() && n.equalsIgnoreCase(sanitized))) {
                        exists = true; break;
                    }
                }
            }
            if (!exists) {
                beginZipAndUpload(s);
                return;
            }

            Text title = Text.literal("Overwrite Cloud Save?");
            Text message = Text.literal("A save named \"" + s.worldName + "\" already exists in the cloud. Overwrite?");
            this.client.setScreen(new ConfirmScreen(confirmed -> {
                this.client.setScreen(this);
                if (confirmed) beginZipAndUpload(s);
                else {
                    loading = false;
                    status = "";
                    updateActionButtons();
                }
            }, title, message));
        }));
    }

    private void beginZipAndUpload(LocalSave s) {
        // Init op state
        ACTIVE.active = true;
        ACTIVE.zipping = true;
        ACTIVE.uploaded = 0L;
        ACTIVE.total = -1L;
        ACTIVE.startNanos = System.nanoTime();
        ACTIVE.lastTickNanos = ACTIVE.startNanos;
        ACTIVE.lastBytes = 0L;
        ACTIVE.speedBps = 0.0;
        loading = true;
        status = "Zipping...";
        updateActionButtons();

        final Path worldDir = s.dir;
        final String worldName = s.worldName;

        new Thread(() -> {
            Path zip = null;
            try {
                zip = sm$zipWorld(worldDir, worldName.replaceAll("[\\\\/:*?\"<>|]+", "_"));
            } catch (Exception ex) {
                SaveManagerMod.LOGGER.error("UploadManager: zip failed", ex);
                final String raw = sm$rawFromThrowable(ex);
                final Path toDelete = zip;
                runOnClient(() -> {
                    ACTIVE.active = false;
                    ACTIVE.zipping = false;
                    loading = false;
                    status = "Zip failed";
                    updateActionButtons();
                    sm$showErrorDialog(raw, "Failed to prepare world zip");
                });
                return;
            }

            final Path zipFinal = zip;
            runOnClient(() -> startUpload(zipFinal, worldName));
        }, "SaveManager-zip").start();
    }

    private void startUpload(Path zipFile, String worldName) {
        ACTIVE.zipping = false;
        ACTIVE.active = true;
        ACTIVE.uploaded = 0L;
        ACTIVE.total = -1L;
        ACTIVE.startNanos = System.nanoTime();
        ACTIVE.lastTickNanos = ACTIVE.startNanos;
        ACTIVE.lastBytes = 0L;
        ACTIVE.speedBps = 0.0;
        loading = true;
        status = "Uploading...";
        updateActionButtons();

        try {
            networkManager.uploadWorldSave(worldName, zipFile, (sent, total) -> {
                ACTIVE.uploaded = Math.max(0L, sent);
                if (total > 0) ACTIVE.total = total;

                long now = System.nanoTime();
                long dtNs = now - ACTIVE.lastTickNanos;
                long dBytes = ACTIVE.uploaded - ACTIVE.lastBytes;
                if (dtNs > 50_000_000L) {
                    double instBps = dBytes > 0 ? (dBytes * 1_000_000_000.0) / dtNs : 0.0;
                    double alpha = 0.2;
                    ACTIVE.speedBps = ACTIVE.speedBps <= 0 ? instBps : (alpha * instBps + (1 - alpha) * ACTIVE.speedBps);
                    ACTIVE.lastTickNanos = now;
                    ACTIVE.lastBytes = ACTIVE.uploaded;
                }
            }).whenComplete((json, err) -> runOnClient(() -> {
                try { Files.deleteIfExists(zipFile); } catch (Throwable ignored) {}

                ACTIVE.active = false;
                ACTIVE.zipping = false;
                ACTIVE.uploaded = 0L;
                ACTIVE.total = -1L;
                ACTIVE.startNanos = 0L;
                ACTIVE.lastTickNanos = 0L;
                ACTIVE.lastBytes = 0L;
                ACTIVE.speedBps = 0.0;

                if (err != null) {
                    SaveManagerMod.LOGGER.error("UploadManager: upload failed", err);
                    String raw = sm$rawFromThrowable(err);
                    loading = false;
                    status = "Upload failed";
                    updateActionButtons();
                    sm$showErrorDialog(raw, "Upload failed");
                    return;
                }

                loading = false;
                status = "Uploaded";
                updateActionButtons();
            }));
        } catch (Throwable t) {
            try { Files.deleteIfExists(zipFile); } catch (Throwable ignored) {}
            SaveManagerMod.LOGGER.error("UploadManager: upload error", t);
            String raw = sm$rawFromThrowable(t);
            loading = false;
            status = "Upload failed";
            updateActionButtons();
            sm$showErrorDialog(raw, "Upload failed");
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        int cx = this.width / 2;

        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, 10, 0xFFFFFFFF);

        Geometry g = computeGeometry();
        int col1 = g.listX();
        int col2 = col1 + COL_WORLD_W;
        int col3 = col2 + COL_SIZE_W;

        int headerY = (this.height / 4) + 10;
        drawColText(ctx, "World",  col1, headerY, 0xFFFFFFFF);
        drawColText(ctx, "Size",   col2, headerY, 0xFFFFFFFF);
        drawColText(ctx, "Updated",col3, headerY, 0xFFFFFFFF);

        ctx.enableScissor(g.listX(), g.listY(), g.listX() + g.listW(), g.listY() + g.listH());

        int start = currentPage * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, saves.size());
        for (int i = start; i < end; i++) {
            LocalSave s = saves.get(i);
            int ry = g.rowStartY() + (i - start) * g.rowHeight();

            if (i == selectedIndex) {
                int h = Math.max(1, g.rowHeight() - 4);
                int x1 = g.listX();
                int x2 = g.listX() + g.listW();
                ctx.fill(x1, ry - 4, x2, ry - 1 + h, 0x66FFFFFF);
            }

            drawColText(ctx, safe(s.worldName), col1, ry, 0xFFDDDDDD);
            drawColText(ctx, formatBytes(s.sizeBytes), col2, ry, 0xFFDDDDDD);
            drawColText(ctx, shortDate(s.lastModified), col3, ry, 0xFFDDDDDD);

            ctx.fill(g.listX(), ry + g.rowHeight() - 5, g.listX() + g.listW(), ry + g.rowHeight() - 4, 0x22FFFFFF);
        }

        ctx.disableScissor();

        // Status and progress (match CloudSaveManagerScreen spacing)
        boolean opActive = ACTIVE.active;
        int baseStatusY = this.height - 56;
        int statusY = opActive ? baseStatusY - 16 : baseStatusY;

        String displayStatus = status;
        if (ACTIVE.active) {
            displayStatus = ACTIVE.zipping ? "Zipping..." : (ACTIVE.uploaded <= 0L ? "Preparing..." : "");
        }

        if (loading || (displayStatus != null && !displayStatus.isEmpty())) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(displayStatus), cx, statusY, 0xFFFFFFFF);
        }

        if (ACTIVE.active) {
            if (ACTIVE.zipping || ACTIVE.uploaded <= 0L) {
                int radius = 8;
                int dots = 12;
                int cx0 = this.width / 2;
                int cy0 = statusY + 24;
                long t = System.currentTimeMillis();
                int head = (int)((t / 100) % dots);
                for (int i = 0; i < dots; i++) {
                    double ang = (2 * Math.PI * i) / dots;
                    int dx = (int)Math.round(Math.cos(ang) * radius);
                    int dy = (int)Math.round(Math.sin(ang) * radius);
                    int x = cx0 + dx;
                    int y = cy0 + dy;
                    int dist = (i - head + dots) % dots;
                    int alpha = switch (dist) {
                        case 0 -> 0xFF;
                        case 1 -> 0xCC;
                        case 2 -> 0x99;
                        case 3 -> 0x66;
                        default -> 0x33;
                    };
                    int col = (alpha << 24) | 0xCCCCCC;
                    ctx.fill(x - 1, y - 1, x + 2, y + 2, col);
                }
            } else {
                long uploaded = ACTIVE.uploaded;
                long total = ACTIVE.total;
                double speed = ACTIVE.speedBps;

                boolean knownTotals = total > 0 && uploaded >= 0;
                if (knownTotals) {
                    int barW = 360;
                    int barH = 8;
                    int bx = cx - (barW / 2);
                    int by = statusY + 14;

                    ctx.fill(bx, by, bx + barW, by + barH, 0xFF444444);
                    double frac = Math.min(1.0, (double) uploaded / total);
                    int filled = (int) (barW * frac);
                    ctx.fill(bx, by, bx + filled, by + barH, 0xFFCCCCCC);

                    int pct = (int) Math.min(100, Math.floor((uploaded * 100.0) / total));
                    ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(pct + "%"), cx, by - 10, 0xFFFFFFFF);

                    String info = formatBytes(uploaded) + " / " + formatBytes(total);
                    if (speed > 1) {
                        String sp = formatBytes((long) speed) + "/s";
                        long remaining = Math.max(0L, total - uploaded);
                        long etaSec = Math.max(0L, (long) Math.ceil(remaining / Math.max(1.0, speed)));
                        info += " • " + sp + " • ETA " + formatDurationShort(etaSec);
                    }
                    ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(info), cx, by + barH + 2, 0xFFCCCCCC);
                } else {
                    int radius = 8;
                    int dots = 12;
                    int cx0 = this.width / 2;
                    int cy0 = statusY + 24;
                    long t = System.currentTimeMillis();
                    int head = (int)((t / 100) % dots);
                    for (int i = 0; i < dots; i++) {
                        double ang = (2 * Math.PI * i) / dots;
                        int dx = (int)Math.round(Math.cos(ang) * radius);
                        int dy = (int)Math.round(Math.sin(ang) * radius);
                        int x = cx0 + dx;
                        int y = cy0 + dy;
                        int dist = (i - head + dots) % dots;
                        int alpha = switch (dist) {
                            case 0 -> 0xFF;
                            case 1 -> 0xCC;
                            case 2 -> 0x99;
                            case 3 -> 0x66;
                            default -> 0x33;
                        };
                        int col = (alpha << 24) | 0xCCCCCC;
                        ctx.fill(x - 1, y - 1, x + 2, y + 2, col);
                    }
                }
            }
        }

        rebuildPagerState();
        updateActionButtons();
    }

    private void buildRowHitBoxes() {
        for (var w : rowHitBoxes) {
            try { this.remove(w); } catch (Throwable ignored) {}
        }
        rowHitBoxes.clear();

        Geometry g = computeGeometry();
        int start = currentPage * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, saves.size());

        for (int i = start; i < end; i++) {
            final int globalIndex = i;
            int ry = g.rowStartY + (i - start) * g.rowHeight;

            ButtonWidget hit = ButtonWidget.builder(Text.literal(""), b -> {
                selectedIndex = globalIndex;
                if (!loading && !ACTIVE.active) {
                    status = "";
                }
                updateActionButtons();
            }).dimensions(g.listX, ry - 4, g.listW, g.rowHeight).build();

            try { hit.setAlpha(0.0f); } catch (Throwable ignored) {}
            hit.visible = true;
            hit.active = true;

            this.addDrawableChild(hit);
            rowHitBoxes.add(hit);
        }
    }

    private void rebuildPagerState() {
        boolean allowPager = !loading || ACTIVE.active;
        if (prevBtn != null) prevBtn.active = (currentPage > 0) && allowPager;
        if (nextBtn != null) nextBtn.active = (((currentPage + 1) * PAGE_SIZE) < saves.size()) && allowPager;
    }

    private void updateActionButtons() {
        boolean enabled = getSelected() != null && !loading && !ACTIVE.active;
        if (uploadBtn != null) uploadBtn.active = enabled;
    }

    private LocalSave getSelected() {
        if (selectedIndex < 0 || selectedIndex >= saves.size()) return null;
        return saves.get(selectedIndex);
    }

    private Geometry computeGeometry() {
        int cx = this.width / 2;
        int top = this.height / 4;
        int headerY = top + 10;

        int rowStartY = headerY + 20;
        int rowHeight = 22;

        int start = currentPage * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, saves.size());
        int visible = Math.max(0, end - start);

        int contentW = COL_WORLD_W + COL_SIZE_W + COL_UPDATED_W;
        int listX = Math.max(0, cx - (contentW / 2));
        int listY = rowStartY - 10;
        int listW = contentW;
        int listH = Math.max(visible * rowHeight, rowHeight) + 12;
        return new Geometry(listX, listY, listW, listH, rowStartY, rowHeight);
    }

    private record Geometry(int listX, int listY, int listW, int listH, int rowStartY, int rowHeight) {}

    private void drawColText(DrawContext ctx, String s, int x, int y, int color) {
        ctx.drawTextWithShadow(this.textRenderer, Text.literal(s), x, y, color);
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String shortDate(long epochMillis) {
        if (epochMillis <= 0) return "";
        Instant i = Instant.ofEpochMilli(epochMillis);
        return DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault()).format(i);
    }

    private static String formatDurationShort(long seconds) {
        if (seconds <= 0) return "0s";
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) return String.format("%dh %02dm", h, m);
        if (m > 0) return String.format("%dm %02ds", m, s);
        return String.format("%ds", s);
    }

    private static String formatBytes(long n) {
        if (n < 1024) return n + " B";
        int u = -1;
        double d = n;
        String[] units = {"KB", "MB", "GB", "TB"};
        do { d /= 1024; u++; } while (d >= 1024 && u < units.length - 1);
        return String.format(java.util.Locale.ROOT, "%.1f %s", d, units[u]);
    }

    private static String sanitizeFolderName(String s) {
        if (s == null) return "";
        String clean = s.trim().replaceAll("[\\\\/:*?\"<>|]+", "_");
        return clean.isBlank() ? "" : clean;
    }

    private void runOnClient(Runnable r) {
        if (this.client != null) this.client.execute(r);
    }

    private static Screen sm$resolveWorldRootParent(Screen parent) {
        Screen p = parent;
        int guard = 0;
        while (p instanceof SelectWorldScreen && guard++ < 8) {
            try {
                java.lang.reflect.Field f = SelectWorldScreen.class.getDeclaredField("parent");
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

    private static String sm$rawFromThrowable(Throwable t) {
        if (t == null) return "";
        StringBuilder sb = new StringBuilder();
        int guard = 0;
        while (t != null && guard++ < 16) {
            String m = t.getMessage();
            if (m != null && !m.isBlank()) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(m);
            }
            t = t.getCause();
        }
        return sb.toString();
    }

    private void sm$showErrorDialog(String raw, String fallbackTitle) {
        String friendly = sm$extractFriendly(raw);
        Text title = Text.literal("Error");
        Text message = Text.literal((friendly == null || friendly.isBlank()) ? fallbackTitle : friendly);
        boolean invalidKey = sm$isInvalidKey(raw, friendly);

        this.client.setScreen(new ConfirmScreen(confirmed -> {
            if (!confirmed) {
                try {
                    if (this.client != null && this.client.keyboard != null) {
                        this.client.keyboard.setClipboard(raw == null ? "" : raw);
                    }
                } catch (Throwable ignored) {}
                return;
            }
            if (invalidKey) {
                if (this.client != null) {
                    Screen rootParent = sm$resolveWorldRootParent(this.parent);
                    this.client.setScreen(new AccountLinkingScreen(rootParent));
                }
                return;
            }
            // Stay on this screen (no navigation) on OK
            this.client.setScreen(this);
        }, title, message, Text.literal("OK"), Text.literal("Copy error")));
    }

    private static boolean sm$isInvalidKey(String raw, String friendly) {
        String r = (raw == null ? "" : raw).toLowerCase(java.util.Locale.ROOT);
        if (friendly != null && friendly.equalsIgnoreCase("Invalid Save Manager API key")) return true;
        if (r.contains("401") && r.contains("invalid save manager api key")) return true;
        return false;
    }

    private static String sm$extractFriendly(String raw) {
        if (raw == null) raw = "";
        String friendly = "";
        int start = raw.indexOf('{');
        while (start >= 0 && start < raw.length()) {
            try {
                String sub = raw.substring(start).trim();
                com.google.gson.JsonElement el = new com.google.gson.JsonParser().parse(sub);
                if (el.isJsonObject()) {
                    com.google.gson.JsonObject obj = el.getAsJsonObject();
                    if (obj.has("error") && obj.get("error").isJsonPrimitive()) {
                        friendly = obj.get("error").getAsString();
                        if (friendly != null && !friendly.isBlank()) return friendly;
                    }
                }
            } catch (Throwable ignored) {}
            start = raw.indexOf('{', start + 1);
        }
        if (raw.contains("401") && raw.toLowerCase(java.util.Locale.ROOT).contains("invalid save manager api key")) {
            return "Invalid Save Manager API key";
        }
        if (raw.toLowerCase(java.util.Locale.ROOT).contains("exceed storage quota")) {
            int i = raw.indexOf('{');
            if (i >= 0) {
                try {
                    var obj = new com.google.gson.JsonParser().parse(raw.substring(i)).getAsJsonObject();
                    if (obj.has("error")) {
                        String s = obj.get("error").getAsString();
                        if (s != null && !s.isBlank()) return s;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return friendly;
    }

    private String loadApiKeyFromDisk() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null) return null;
            File configDir = new File(mc.runDirectory, "config");
            File configFile = new File(configDir, "save-manager-settings.json");
            if (!configFile.exists()) return null;

            try (FileReader reader = new FileReader(configFile)) {
                com.google.gson.JsonObject json = new com.google.gson.Gson().fromJson(reader, com.google.gson.JsonObject.class);
                if (json == null) return null;
                if (json.has("encryptedApiToken")) {
                    return decrypt(json.get("encryptedApiToken").getAsString());
                }
                if (json.has("apiToken")) {
                    return json.get("apiToken").getAsString();
                }
            }
        } catch (Exception e) {
            SaveManagerMod.LOGGER.error("UploadManager: error loading API key", e);
        }
        return null;
    }

    private String decrypt(String base64) throws Exception {
        byte[] data = java.util.Base64.getDecoder().decode(base64);
        if (data.length < 12 + 16) throw new IllegalArgumentException("Invalid data");
        byte[] iv = new byte[12];
        byte[] ct = new byte[data.length - 12];
        System.arraycopy(data, 0, iv, 0, 12);
        System.arraycopy(data, 12, ct, 0, ct.length);

        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(
                MessageDigest.getInstance("SHA-256").digest("SaveManagerSecKey.v1".getBytes(StandardCharsets.UTF_8)), "AES");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, new javax.crypto.spec.GCMParameterSpec(128, iv));
        byte[] pt = cipher.doFinal(ct);
        return new String(pt, StandardCharsets.UTF_8);
    }

    // Zip the world directory similar to SinglePlayerScreenMixin
    private static Path sm$zipWorld(Path worldDir, String worldName) throws Exception {
        Path zip = Files.createTempFile("savemanager-" + worldName + "-", ".zip");
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip, StandardOpenOption.WRITE))) {
            final Path base = worldDir;
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        Path rel = base.relativize(file);
                        // Skip transient lock files if present
                        if ("session.lock".equalsIgnoreCase(rel.getFileName().toString())) return FileVisitResult.CONTINUE;
                        java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(rel.toString().replace('\\', '/'));
                        zos.putNextEntry(entry);
                        Files.copy(file, zos);
                        zos.closeEntry();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return zip;
    }

    private static class LocalSave {
        final Path dir;
        final String worldName;
        final long sizeBytes;
        final long lastModified;

        LocalSave(Path dir, String worldName, long sizeBytes, long lastModified) {
            this.dir = dir; this.worldName = worldName; this.sizeBytes = sizeBytes; this.lastModified = lastModified;
        }

        static LocalSave fromDir(Path dir) {
            String name = dir.getFileName() != null ? dir.getFileName().toString() : dir.toString();
            long lm = 0L;
            try {
                lm = Files.getLastModifiedTime(dir).toMillis();
            } catch (Exception ignored) {}
            long size = 0L;
            try {
                size = computeDirSize(dir);
            } catch (Exception ignored) {}
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
}

