package com.choculaterie.gui;

import com.choculaterie.SaveManagerMod;
import com.choculaterie.network.NetworkManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.text.Text;

import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CloudSaveManagerScreen extends Screen {
    private final Screen parent;
    private final NetworkManager networkManager = new NetworkManager();

    private boolean firstRenderLogged = false;
    private boolean loading = true;
    private String status = "Loading...";
    private String quotaLine = "";

    private final List<SaveItem> saves = new ArrayList<>();
    private int currentPage = 0;
    private static final int PAGE_SIZE = 6;

    // Top controls
    private ButtonWidget backBtn;
    private ButtonWidget refreshBtn;
    private ButtonWidget prevBtn;
    private ButtonWidget nextBtn;

    // Bottom action buttons
    private ButtonWidget downloadBtn;
    private ButtonWidget deleteBtn;

    // Selection (global index in `saves`, -1 means none)
    private int selectedIndex = -1;

    private volatile long dlDownloaded = 0L;
    private volatile long dlTotal = -1L;
    private volatile boolean dlActive = false;
    private volatile long dlStartNanos = 0L;
    private volatile long dlLastTickNanos = 0L;
    private volatile long dlLastBytes = 0L;
    private volatile double dlSpeedBps = 0.0;
    private volatile boolean unzipActive = false;

    private static final ActiveOp ACTIVE = new ActiveOp();
    private static final class ActiveOp {
        volatile boolean dlActive = false;
        volatile boolean unzipActive = false;
        volatile long downloaded = 0L;
        volatile long total = -1L;
        volatile long lastBytes = 0L;
        volatile long lastTickNanos = 0L;
        volatile long startNanos = 0L;
        volatile double speedBps = 0.0;
    }
    private static boolean sm$isOpActive() { return ACTIVE.dlActive || ACTIVE.unzipActive; }

    private static final long QUOTA_LIMIT_BYTES = 5L * 1024L * 1024L * 1024L; // 5 GB

    // Invisible click areas for rows
    private final List<PressableWidget> rowHitBoxes = new ArrayList<>();

    public CloudSaveManagerScreen(Screen parent) {
        super(Text.literal("Choculaterie Cloud Saves"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int topY = this.height / 4;
        int bottomY = this.height - 28;

        // Top buttons
        this.backBtn = ButtonWidget.builder(Text.literal("←"), b -> {
            if (this.client != null) {
                // Re-open a fresh SelectWorldScreen to force a filesystem rescan
                this.client.setScreen(new SelectWorldScreen(this.parent));
            }
        }).dimensions(10, 10, 20, 20).build();
        this.addDrawableChild(backBtn);

        this.refreshBtn = ButtonWidget.builder(Text.literal("\uD83D\uDD04"), b -> {
            selectedIndex = -1;
            fetchList();
        }).dimensions(35, 10, 20, 20).build();
        this.addDrawableChild(refreshBtn);

        this.prevBtn = ButtonWidget.builder(Text.literal("<"), b -> {
            if (currentPage > 0) {
                currentPage--;
                selectedIndex = -1;
                this.clearAndInit();
            }
        }).dimensions(this.width - 55, bottomY, 20, 20).build();
        this.addDrawableChild(prevBtn);

        this.nextBtn = ButtonWidget.builder(Text.literal(">"), b -> {
            if ((currentPage + 1) * PAGE_SIZE < saves.size()) {
                currentPage++;
                selectedIndex = -1;
                this.clearAndInit();
            }
        }).dimensions(this.width - 30, bottomY, 20, 20).build();
        this.addDrawableChild(nextBtn);

        // Bottom action buttons

        this.downloadBtn = ButtonWidget.builder(Text.literal("Download"), b -> {
            SaveItem s = getSelected();
            if (s != null) onDownload(s);
        }).dimensions(cx - 110, bottomY, 100, 20).build();
        this.addDrawableChild(downloadBtn);

        this.deleteBtn = ButtonWidget.builder(Text.literal("Delete"), b -> {
            SaveItem s = getSelected();
            if (s != null) confirmDelete(s);
        }).dimensions(cx + 10, bottomY, 100, 20).build();
        this.addDrawableChild(deleteBtn);

        // API key
        String apiKey = loadApiKeyFromDisk();
        if (apiKey == null || apiKey.isBlank()) {
            loading = false;
            status = "No API key configured";
        } else {
            networkManager.setApiKey(apiKey);
            if (saves.isEmpty()) fetchList();
        }

        // Build row hit boxes for current page
        buildRowHitBoxes();

        // Update controls state
        rebuildPagerState();
        updateActionButtons();
    }

    @Override
    public void resize(MinecraftClient client, int width, int height) {
        super.resize(client, width, height);
        // Re-init places and re-creates row hit boxes
        this.clearAndInit();
    }

    private void fetchList() {
        loading = true;
        status = "Loading...";
        updateActionButtons();
        SaveManagerMod.LOGGER.info("CloudSaveManager: fetchList() start");

        networkManager.listWorldSaves().whenComplete((json, err) -> {
            // Log API response for debugging (pretty-print and truncate large payloads)
            if (json != null) {
                try {
                    String pretty = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(json);
                    if (pretty.length() > 2000) {
                        SaveManagerMod.LOGGER.info("CloudSaveManager: list response (truncated, totalLen={}) : {}",
                                pretty.length(), pretty.substring(0, 2000) + "...(truncated)");
                    } else {
                        SaveManagerMod.LOGGER.info("CloudSaveManager: list response: {}", pretty);
                    }
                } catch (Throwable logEx) {
                    try {
                        SaveManagerMod.LOGGER.info("CloudSaveManager: list response (raw): {}", json.toString());
                    } catch (Throwable ignored) {}
                }
            } else {
                SaveManagerMod.LOGGER.info("CloudSaveManager: list response is null");
            }

            if (err != null) {
                SaveManagerMod.LOGGER.error("CloudSaveManager: list failed", err);
                runOnClient(() -> {
                    loading = false;
                    status = "List failed";
                    saves.clear();
                    quotaLine = "";
                    selectedIndex = -1;
                    buildRowHitBoxes();
                    rebuildPagerState();
                    updateActionButtons();
                });
                return;
            }

            List<SaveItem> tmp = new ArrayList<>();
            String newQuota = "";
            try {
                // Prefer top-level, then common containers like "result" or "meta"
                com.google.gson.JsonArray arr = sm$findArray(json, "saves", "items", "data", "list", "worlds");
                if (newQuota.isEmpty()) newQuota = sm$parseQuota(json);

                if (arr == null && json.has("result") && json.get("result").isJsonObject()) {
                    JsonObject result = json.getAsJsonObject("result");
                    if (newQuota.isEmpty()) newQuota = sm$parseQuota(result);
                    arr = sm$findArray(result, "saves", "items", "data", "list", "worlds");
                }

                if (arr == null && json.has("meta") && json.get("meta").isJsonObject()) {
                    JsonObject meta = json.getAsJsonObject("meta");
                    if (newQuota.isEmpty()) newQuota = sm$parseQuota(meta);
                    if (arr == null) arr = sm$findArray(meta, "saves", "items", "data", "list", "worlds");
                }

                // Last resort: first array field found
                if (arr == null) {
                    for (var e : json.entrySet()) {
                        if (e.getValue() != null && e.getValue().isJsonArray()) {
                            arr = e.getValue().getAsJsonArray();
                            break;
                        }
                        if (e.getValue() != null && e.getValue().isJsonObject() && arr == null) {
                            for (var e2 : e.getValue().getAsJsonObject().entrySet()) {
                                if (e2.getValue() != null && e2.getValue().isJsonArray()) {
                                    arr = e2.getValue().getAsJsonArray();
                                    break;
                                }
                            }
                        }
                        if (arr != null) break;
                    }
                }

                if (arr != null) {
                    for (int i = 0; i < arr.size(); i++) {
                        if (!arr.get(i).isJsonObject()) continue;
                        tmp.add(SaveItem.from(arr.get(i).getAsJsonObject()));
                    }
                }
            } catch (Throwable parseEx) {
                SaveManagerMod.LOGGER.error("CloudSaveManager: parse error", parseEx);
                newQuota = "";
            }

            final String quotaOut = newQuota;
            runOnClient(() -> {
                saves.clear();
                saves.addAll(tmp);
                loading = false;
                status = tmp.isEmpty() ? "No saves found" : "";
                quotaLine = quotaOut == null ? "" : quotaOut;
                selectedIndex = -1;
                buildRowHitBoxes();
                rebuildPagerState();
                updateActionButtons();
            });
        });
    }

    // Replace the whole method
    private static JsonArray sm$findArray(JsonObject obj, String... names) {
        for (String n : names) {
            if (!obj.has(n)) continue;
            com.google.gson.JsonElement e = obj.get(n);
            if (e == null) continue;
            if (e.isJsonArray()) return e.getAsJsonArray();
            if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                // common nested holder
                if (o.has("items") && o.get("items").isJsonArray()) return o.getAsJsonArray("items");
                if (o.has("saves") && o.get("saves").isJsonArray()) return o.getAsJsonArray("saves");
            }
        }
        return null;
    }

    // Replace the whole method
    private static String sm$getString(JsonObject obj, String... names) {
        for (String n : names) {
            if (!obj.has(n)) continue;
            com.google.gson.JsonElement e = obj.get(n);
            if (e == null) continue;
            if (e.isJsonNull()) continue;
            if (e.isJsonPrimitive()) {
                var p = e.getAsJsonPrimitive();
                if (p.isString()) return p.getAsString();
                if (p.isNumber()) return String.valueOf(p.getAsLong());
                if (p.isBoolean()) return String.valueOf(p.getAsBoolean());
            } else if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                // common text carriers
                if (o.has("text")) {
                    String s = sm$getString(o, "text");
                    if (!s.isEmpty()) return s;
                }
                if (o.has("value")) {
                    String s = sm$getString(o, "value");
                    if (!s.isEmpty()) return s;
                }
                if (o.has("iso")) {
                    String s = sm$getString(o, "iso");
                    if (!s.isEmpty()) return s;
                }
            } else if (e.isJsonArray() && e.getAsJsonArray().size() > 0) {
                var first = e.getAsJsonArray().get(0);
                if (first.isJsonPrimitive() && first.getAsJsonPrimitive().isString()) {
                    return first.getAsString();
                }
            }
        }
        return "";
    }

    // Replace the whole method
    private static long sm$getLong(JsonObject obj, String... names) {
        for (String n : names) {
            if (!obj.has(n)) continue;
            com.google.gson.JsonElement e = obj.get(n);
            if (e == null || e.isJsonNull()) continue;

            // Direct primitive
            if (e.isJsonPrimitive()) {
                var p = e.getAsJsonPrimitive();
                if (p.isNumber()) return p.getAsLong();
                if (p.isString()) {
                    String s = p.getAsString();
                    // strip common separators/spaces; keep leading minus sign
                    String digits = s.replaceAll("[_,\\s]", "");
                    try {
                        // Try exact long
                        return Long.parseLong(digits);
                    } catch (NumberFormatException ex) {
                        // Extract contiguous digits if mixed text, e.g., "5368709120 bytes"
                        String only = digits.replaceAll("[^0-9-]", "");
                        if (!only.isEmpty()) {
                            try { return Long.parseLong(only); } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }

            // Nested common shapes
            if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                // typical carriers for byte counts
                long v = sm$getLong(o, "bytes", "value", "size", "sizeBytes", "length", "amount");
                if (v != 0L) return v;
            }

            // Fallback: array first element
            if (e.isJsonArray() && e.getAsJsonArray().size() > 0) {
                var first = e.getAsJsonArray().get(0);
                if (first.isJsonPrimitive() && first.getAsJsonPrimitive().isNumber()) {
                    return first.getAsLong();
                }
            }
        }
        return 0L;
    }

    // Replace the whole method
    private String sm$parseQuota(JsonObject obj) {
        // 1) Direct text
        String direct = sm$getString(obj, "quotaLine", "quotaText", "storageText");
        if (!direct.isEmpty()) return direct;

        // 2) quota object with bytes
        if (obj.has("quota") && obj.get("quota").isJsonObject()) {
            JsonObject q = obj.getAsJsonObject("quota");
            long used = sm$getLong(q, "usedBytes", "used", "usageBytes", "bytesUsed", "current");
            long total = sm$getLong(q, "totalBytes", "limitBytes", "total", "capacityBytes", "maxBytes", "limit");
            if (used == 0 && q.has("used") && q.get("used").isJsonObject()) {
                used = sm$getLong(q.getAsJsonObject("used"), "bytes", "value");
            }
            if (total == 0 && q.has("total") && q.get("total").isJsonObject()) {
                total = sm$getLong(q.getAsJsonObject("total"), "bytes", "value", "limitBytes");
            }
            if (used > 0 || total > 0) {
                return "Used " + formatBytes(used) + " of " + (total > 0 ? formatBytes(total) : "?");
            }
        }

        // 3) usage object
        if (obj.has("usage") && obj.get("usage").isJsonObject()) {
            JsonObject q = obj.getAsJsonObject("usage");
            long used = sm$getLong(q, "usedBytes", "used", "bytesUsed", "current");
            long total = sm$getLong(q, "totalBytes", "limitBytes", "total", "capacityBytes", "maxBytes", "limit");
            if (used > 0 || total > 0) {
                return "Used " + formatBytes(used) + " of " + (total > 0 ? formatBytes(total) : "?");
            }
        }

        return "";
    }

    // Replace the whole inner class
    private static class SaveItem {
        String id;
        String worldName;
        long fileSizeBytes;
        String createdAt;
        String updatedAt;

        static SaveItem from(JsonObject o) {
            SaveItem s = new SaveItem();
            s.id = sm$getString(o, "id", "saveId", "guid");
            s.worldName = sm$getString(o, "worldName", "name", "world", "title");
            long size = sm$getLong(o, "sizeBytes", "fileSizeBytes", "fileSize", "size", "bytes", "length");
            s.fileSizeBytes = size;
            s.createdAt = sm$getString(o, "createdAt", "created", "created_on", "createdOn");
            s.updatedAt = sm$getString(o, "updatedAt", "updated", "updated_on", "lastModified", "modifiedOn");
            return s;
        }
    }

    private void rebuildPagerState() {
        if (prevBtn != null) prevBtn.active = currentPage > 0 && !loading;
        if (nextBtn != null) nextBtn.active = ((currentPage + 1) * PAGE_SIZE) < saves.size() && !loading;
    }

    private SaveItem getSelected() {
        if (selectedIndex < 0 || selectedIndex >= saves.size()) return null;
        return saves.get(selectedIndex);
    }

    private void updateActionButtons() {
        boolean enabled = getSelected() != null && !loading && !sm$isOpActive();
        if (downloadBtn != null) downloadBtn.active = enabled;
        if (deleteBtn != null) deleteBtn.active = enabled;
    }

    // Build invisible pressable boxes over each visible row (avoids overriding mouseClicked API)
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
                if (!loading && !sm$isOpActive()) {
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

    private void confirmDelete(SaveItem s) {
        Text title = Text.literal("Delete Cloud Save");
        Text message = Text.literal("Are you sure you want to permanently delete \"" + safe(s.worldName) + "\"?");
        this.client.setScreen(new ConfirmScreen(confirmed -> {
            if (!confirmed) {
                this.client.setScreen(this);
                return;
            }
            loading = true;
            status = "Deleting...";
            updateActionButtons();
            networkManager.deleteWorldSave(s.id).whenComplete((resp, err) -> runOnClient(() -> {
                if (err != null) {
                    SaveManagerMod.LOGGER.error("Delete failed", err);
                    status = "Delete failed";
                    loading = false;
                    this.client.setScreen(this);
                    return;
                }
                status = "Deleted";
                loading = false;
                selectedIndex = -1;
                fetchList();
                this.client.setScreen(this);
            }));
        }, title, message));
    }

    private void onDownload(SaveItem s) {
        loading = true;
        status = "Preparing...";
        updateActionButtons();

        Path savesDir = this.client.runDirectory.toPath().resolve("saves");
        try { Files.createDirectories(savesDir); } catch (Exception ignored) {}

        Path tmpDir;
        try {
            tmpDir = Files.createTempDirectory("savemanager-dl-");
        } catch (Exception e) {
            SaveManagerMod.LOGGER.error("CloudSaveManager: temp dir error", e);
            status = "Failed to prepare temp dir";
            loading = false;
            updateActionButtons();
            return;
        }

        // Init global op state
        ACTIVE.dlActive = true;
        ACTIVE.unzipActive = false;
        ACTIVE.downloaded = 0L;
        ACTIVE.total = -1L;
        ACTIVE.startNanos = System.nanoTime();
        ACTIVE.lastTickNanos = ACTIVE.startNanos;
        ACTIVE.lastBytes = 0L;
        ACTIVE.speedBps = 0.0;

        final long fallbackTotal = (s != null && s.fileSizeBytes > 0) ? s.fileSizeBytes : -1L;

        networkManager.downloadWorldSave(s.id, tmpDir, (downloaded, total) -> {
            long effTotal = (total > 0) ? total : fallbackTotal;

            // Update global progress
            ACTIVE.downloaded = Math.max(0L, downloaded);
            if (effTotal > 0) ACTIVE.total = effTotal;

            long now = System.nanoTime();
            long dtNs = now - ACTIVE.lastTickNanos;
            long dBytes = ACTIVE.downloaded - ACTIVE.lastBytes;
            if (dtNs > 50_000_000L) {
                double instBps = dBytes > 0 ? (dBytes * 1_000_000_000.0) / dtNs : 0.0;
                double alpha = 0.2;
                ACTIVE.speedBps = ACTIVE.speedBps <= 0 ? instBps : (alpha * instBps + (1 - alpha) * ACTIVE.speedBps);
                ACTIVE.lastTickNanos = now;
                ACTIVE.lastBytes = ACTIVE.downloaded;
            }

            // UI update only (status text is computed dynamically in render)
            runOnClient(this::updateActionButtons);
        }).whenComplete((zipPath, err) -> {
            ACTIVE.dlActive = false;

            if (err != null) {
                SaveManagerMod.LOGGER.error("CloudSaveManager: download failed", err);
                runOnClient(() -> {
                    status = "Download failed";
                    loading = false;
                    updateActionButtons();
                });
                return;
            }

            // Unzipping phase (persisted)
            runOnClient(() -> {
                ACTIVE.unzipActive = true;
                status = "Unzipping...";
            });

            String baseName = sanitizeFolderName(s.worldName);
            if (baseName.isEmpty()) baseName = stripExtSafe(zipPath.getFileName().toString(), "world");
            Path targetBase = ensureUniqueDir(savesDir, baseName);

            String tmpEndMsg = ""; // empty means success
            try {
                Files.createDirectories(targetBase);
                unzipSmart(zipPath, targetBase);
            } catch (Exception ex) {
                SaveManagerMod.LOGGER.error("CloudSaveManager: unzip failed", ex);
                tmpEndMsg = "Unzip failed";
            } finally {
                try { Files.deleteIfExists(zipPath); } catch (Exception ignored) {}
                try { Files.deleteIfExists(tmpDir); } catch (Exception ignored) {}
            }

            final String endMsg = tmpEndMsg;
            runOnClient(() -> {
                ACTIVE.unzipActive = false;
                status = endMsg;  // "" on success
                loading = false;  // unlock actions
                updateActionButtons();
            });
        });
    }

    // Detects if zip has a single root directory and strips it during extraction
    private static void unzipSmart(Path zipFile, Path targetBase) throws Exception {
        String root = detectSingleRootDir(zipFile);
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(Files.newInputStream(zipFile))) {
            for (java.util.zip.ZipEntry e; (e = zis.getNextEntry()) != null; ) {
                if (e.isDirectory()) continue;
                String name = e.getName().replace('\\', '/');
                if (root != null && name.startsWith(root + "/")) {
                    name = name.substring(root.length() + 1);
                }
                if (name.isBlank()) continue;

                Path out = safeResolve(targetBase, name);
                Files.createDirectories(out.getParent());
                Files.copy(zis, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                zis.closeEntry();
            }
        }
    }

    // Returns the single top-level folder name if all entries share it; else null
    private static String detectSingleRootDir(Path zipFile) throws Exception {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zipFile.toFile())) {
            String root = null;
            var en = zf.entries();
            while (en.hasMoreElements()) {
                var e = en.nextElement();
                String name = e.getName().replace('\\', '/');
                // Skip empty
                if (name.isBlank()) continue;
                String top = name.split("/", 2)[0];
                if (top.isBlank()) return null;
                if (root == null) root = top;
                else if (!root.equals(top)) return null;
            }
            // Only treat as a directory root if at least one entry is inside it
            return root;
        }
    }

    // Prevents Zip Slip
    private static Path safeResolve(Path base, String entryName) {
        Path out = base.resolve(entryName).normalize();
        if (!out.startsWith(base)) throw new IllegalArgumentException("Blocked suspicious zip entry: " + entryName);
        return out;
    }

    private static String sanitizeFolderName(String s) {
        if (s == null) return "";
        String clean = s.trim().replaceAll("[\\\\/:*?\"<>|]+", "_");
        return clean.isBlank() ? "" : clean;
    }

    private static String stripExtSafe(String name, String fallback) {
        int i = name.lastIndexOf('.');
        String base = (i > 0) ? name.substring(0, i) : name;
        base = sanitizeFolderName(base);
        return base.isBlank() ? fallback : base;
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

    // Small helper to marshal back to client thread
    private void runOnClient(Runnable r) {
        if (this.client != null) this.client.execute(r);
    }


    // Render
    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        try { ctx.disableScissor(); } catch (Throwable ignored) {}

        int cx = this.width / 2;

        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, 10, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(sm$quotaLineComputed()), cx, 22, 0xFFAAAAAA);

        Geometry g = computeGeometry();
        ctx.enableScissor(g.listX, g.listY, g.listX + g.listW, g.listY + g.listH);

        int start = currentPage * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, saves.size());
        for (int i = start; i < end; i++) {
            SaveItem s = saves.get(i);
            int ry = g.rowStartY + (i - start) * g.rowHeight;

            if (i == selectedIndex) {
                int h = Math.max(1, g.rowHeight - 4);
                int x1 = g.listX;
                int x2 = g.listX + g.listW;
                ctx.fill(x1, ry - 4, x2, ry - 1 + h, 0x66FFFFFF);
            }

            int left = cx - 220;
            drawColText(ctx, safe(s.worldName), left, ry, 0xFFDDDDDD);
            drawColText(ctx, formatBytes(s.fileSizeBytes), left + 220, ry, 0xFFDDDDDD);
            drawColText(ctx, shortDate(s.createdAt), left + 300, ry, 0xFFDDDDDD);
            drawColText(ctx, shortDate(s.updatedAt), left + 420, ry, 0xFFDDDDDD);

            ctx.fill(g.listX, ry + g.rowHeight - 5, g.listX + g.listW - 10, ry + g.rowHeight - 4, 0x22FFFFFF);
        }

        ctx.disableScissor();

        // Read global op state
        boolean opActive = sm$isOpActive();
        boolean unzipActiveV = ACTIVE.unzipActive;
        long dlDownloadedV = ACTIVE.downloaded;
        long dlTotalV = ACTIVE.total;
        double speedV = ACTIVE.speedBps;

        int baseStatusY = this.height - 56;
        int statusY = opActive ? baseStatusY - 16 : baseStatusY;

        // Dynamic status based on active phase; fall back to stored status if idle
        String displayStatus = status;
        if (opActive) {
            displayStatus = unzipActiveV ? "Unzipping..." : (dlDownloadedV <= 0L ? "Preparing..." : "");
        }

        if (loading || (displayStatus != null && !displayStatus.isEmpty())) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(displayStatus), cx, statusY, 0xFFFFFFFF);
        }

        if (opActive) {
            if (unzipActiveV || dlDownloadedV <= 0L) {
                // Spinner (light gray)
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
                    int col = (alpha << 24) | 0xCCCCCC; // light gray dots
                    ctx.fill(x - 1, y - 1, x + 2, y + 2, col);
                }
            } else {
                // Light gray download bar
                int barW = 360;
                int barH = 8;
                int bx = cx - (barW / 2);
                int by = statusY + 14;

                ctx.fill(bx, by, bx + barW, by + barH, 0xFF444444);

                double frac = (dlTotalV > 0) ? Math.min(1.0, (double) dlDownloadedV / dlTotalV) : 0.0;
                int filled = (int) (barW * frac);
                ctx.fill(bx, by, bx + filled, by + barH, 0xFFCCCCCC);

                if (dlTotalV > 0) {
                    int pct = (int) Math.min(100, Math.floor((dlDownloadedV * 100.0) / dlTotalV));
                    ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(pct + "%"), cx, by - 10, 0xFFFFFFFF);

                    String info = formatBytes(dlDownloadedV) + " / " + formatBytes(dlTotalV);
                    if (speedV > 1) {
                        String sp = formatBytes((long) speedV) + "/s";
                        long remaining = Math.max(0L, dlTotalV - dlDownloadedV);
                        long etaSec = Math.max(0L, (long) Math.ceil(remaining / Math.max(1.0, speedV)));
                        info += " • " + sp + " • ETA " + formatDurationShort(etaSec);
                    }
                    ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(info), cx, by + barH + 2, 0xFFCCCCCC);
                }
            }
        }

        rebuildPagerState();
        updateActionButtons();

        if (!firstRenderLogged) {
            firstRenderLogged = true;
            SaveManagerMod.LOGGER.info("CloudSaveManager: first render; saves={}, page={}", saves.size(), currentPage);
        }
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

    private String sm$quotaLineComputed() {
        long used = sm$sumUsedBytes();
        long left = Math.max(0L, QUOTA_LIMIT_BYTES - used);
        // Use floor-rounded formatting to avoid showing "5.0 GB left" when it's < 5 GB
        String usedStr = sm$formatBytesFloor(used);
        String leftStr = sm$formatBytesFloor(left);
        return String.format("%s of 5 GB (%s left)", usedStr, leftStr);
    }

    private static String sm$formatBytesFloor(long bytes) {
        if (bytes < 0) bytes = 0;
        final long KB = 1024L;
        final long MB = KB * 1024L;
        final long GB = MB * 1024L;
        final long TB = GB * 1024L;

        double value;
        String unit;
        if (bytes >= TB) { value = (double) bytes / TB; unit = "TB"; }
        else if (bytes >= GB) { value = (double) bytes / GB; unit = "GB"; }
        else if (bytes >= MB) { value = (double) bytes / MB; unit = "MB"; }
        else if (bytes >= KB) { value = (double) bytes / KB; unit = "KB"; }
        else { return bytes + " B"; }

        // Floor to 1 decimal to avoid rounding up (e.g., 4.96 -> 4.9)
        double floored = Math.floor(value * 10.0) / 10.0;
        // Avoid "-0.0" or "0.0" oddities
        if (floored >= 100 || Math.abs(floored - Math.rint(floored)) < 1e-9) {
            return String.format("%.0f %s", floored, unit);
        }
        return String.format("%.1f %s", floored, unit);
    }

    private long sm$sumUsedBytes() {
        long sum = 0L;
        for (SaveItem s : saves) {
            if (s != null) {
                sum += Math.max(0L, s.fileSizeBytes);
            }
        }
        return sum;
    }

    private Geometry computeGeometry() {
        int cx = this.width / 2;
        int left = cx - 220;
        int top = this.height / 4;
        int headerY = top + 10;

        int rowStartY = headerY + 20;
        int rowHeight = 22;

        int start = currentPage * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, saves.size());
        int visible = Math.max(0, end - start);

        int listX = left - 6;
        int listY = rowStartY - 10;
        int listW = 720;
        int listH = Math.max(visible * rowHeight, rowHeight) + 12;
        return new Geometry(listX, listY, listW, listH, rowStartY, rowHeight);
    }

    private record Geometry(int listX, int listY, int listW, int listH, int rowStartY, int rowHeight) {}


    private void drawColText(DrawContext ctx, String s, int x, int y, int color) {
        ctx.drawTextWithShadow(this.textRenderer, Text.literal(s), x, y, color);
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String formatBytes(long n) {
        if (n < 1024) return n + " B";
        int u = -1;
        double d = n;
        String[] units = {"KB", "MB", "GB", "TB"};
        do { d /= 1024; u++; } while (d >= 1024 && u < units.length - 1);
        return String.format(java.util.Locale.ROOT, "%.1f %s", d, units[u]);
    }

    private static String shortDate(String iso) {
        if (iso == null) return "";
        int t = iso.indexOf('T');
        return t > 0 ? iso.substring(0, t) : iso;
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
            SaveManagerMod.LOGGER.error("Error loading API key", e);
        }
        return null;
    }

    private String decrypt(String base64) throws Exception {
        // Match the AES-GCM scheme used elsewhere
        byte[] data = java.util.Base64.getDecoder().decode(base64);
        if (data.length < 12 + 16) throw new IllegalArgumentException("Invalid data");
        byte[] iv = new byte[12];
        byte[] ct = new byte[data.length - 12];
        System.arraycopy(data, 0, iv, 0, 12);
        System.arraycopy(data, 12, ct, 0, ct.length);

        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest("SaveManagerSecKey.v1".getBytes(java.nio.charset.StandardCharsets.UTF_8)), "AES");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, new javax.crypto.spec.GCMParameterSpec(128, iv));
        byte[] pt = cipher.doFinal(ct);
        return new String(pt, java.nio.charset.StandardCharsets.UTF_8);
    }

}

