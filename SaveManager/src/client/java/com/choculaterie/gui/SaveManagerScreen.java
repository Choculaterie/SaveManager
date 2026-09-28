package com.choculaterie.gui;

import com.choculaterie.SaveManagerMod;
import com.choculaterie.mixin.SelectWorldScreenAccessor;
import com.choculaterie.network.ApiException;
import com.choculaterie.network.NetworkManager;
import com.choculaterie.sync.AutoSync;
import com.choculaterie.sync.SyncState;
import com.choculaterie.sync.WorldManifest;
import com.choculaterie.util.AccountState;
import com.choculaterie.util.ConfigManager;
import com.choculaterie.vanilib.util.ScreenUtils;
import com.choculaterie.vanilib.util.WatchManager;
import com.choculaterie.vanilib.gui.widget.ConfirmPopup;
import com.choculaterie.vanilib.gui.widget.CustomButton;
import com.choculaterie.vanilib.gui.widget.ScrollBar;
import com.choculaterie.vanilib.gui.widget.ToastManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;

import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.concurrent.CompletableFuture;

import static com.choculaterie.vanilib.util.FormatUtils.*;

public class SaveManagerScreen extends Screen {
    private static final int ROW_HEIGHT = 24;
    private static final int PANEL_GAP = 20;
    private static final int HEADER_Y = 50;
    private static final int LIST_GAP = 20;
    private static final long RELOAD_THRESHOLD_MS = 15 * 60 * 1000L;

    private static long lastLoadTimeMs = 0L;
    private static final List<LocalSave> cachedLocalSaves = new ArrayList<>();
    private static final List<CloudSave> cachedCloudSaves = new ArrayList<>();
    private static String cachedQuotaFormatted = "Loading";

    private final Screen parent;
    private final NetworkManager networkManager = new NetworkManager();
    private final ToastManager toastManager;
    private final List<LocalSave> localSaves = new ArrayList<>();
    private final List<CloudSave> cloudSaves = new ArrayList<>();
    private VersionPickerPopup<CloudVersion> versionPopup;
    private ScrollBar localScrollBar, cloudScrollBar;
    private int localScrollOffset = 0, cloudScrollOffset = 0;
    private int localSelectedIndex = -1, cloudSelectedIndex = -1;
    private boolean localLoading = true, cloudLoading = true;
    private int localPanelX, localPanelW, cloudPanelX, cloudPanelW, listY, listH;
    private int visibleRows = 1;
    private ConfirmPopup confirmPopup = null;
    private CustomButton uploadBtn, downloadBtn, deleteBtn, refreshBtn;
    private String quotaFormatted = "Loading";
    private long quotaBytes = 5L * 1024L * 1024L * 1024L;
    private boolean quotaLoading = false;
    private static final TransferState ACTIVE = new TransferState();
    private static volatile SaveManagerScreen activeScreen = null;
    private String autoUploadWorld = null;
    private boolean closed = false;
    private String starTooltipText = null;

    private static final class TransferState {
        volatile boolean dlActive, upActive, zipping, unzipping, cancelled;
        volatile String worldName;
        volatile long bytes, total = -1L, lastBytes, lastTickNanos;
        volatile double speedBps;

        boolean isActive() {
            return dlActive || upActive || zipping || unzipping;
        }

        void reset(boolean download, String worldName) {
            this.worldName = worldName;
            if (download) {
                dlActive = true;
                unzipping = false;
            } else {
                upActive = true;
                zipping = true;
            }
            cancelled = false;
            bytes = 0L;
            total = -1L;
            lastBytes = 0L;
            lastTickNanos = System.nanoTime();
            speedBps = 0.0;
        }

        void abortIfCancelled() {
            if (cancelled)
                throw new java.util.concurrent.CancellationException("Cancelled");
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
        super(Component.literal("Save Manager"));
        this.parent = parent;
        this.toastManager = new ToastManager(net.minecraft.client.Minecraft.getInstance());
    }

    public SaveManagerScreen(Screen parent, String autoUploadWorld) {
        this(parent);
        this.autoUploadWorld = autoUploadWorld;
    }

    @Override
    protected void init() {
        clearStaleTransferState();
        int btnSize = 20, margin = 6;
        addBtn(margin, margin, btnSize, btnSize, "\u2190", b -> closeScreen());
        refreshBtn = addBtn(margin + btnSize + 5, margin, btnSize, btnSize, "\uD83D\uDD04", b -> refresh());
        addBtn(this.width - margin - btnSize * 2 - 5, margin, btnSize, btnSize, "\uD83D\uDCC1", b -> openSavesFolder());
        addBtn(this.width - margin - btnSize, margin, btnSize, btnSize, "\u2699",
                b -> minecraft.gui.setScreen(new AccountLinkingScreen(this)));

        int totalW = this.width - 60;
        int panelW = (totalW - PANEL_GAP) / 2;
        localPanelX = 30;
        localPanelW = panelW;
        cloudPanelX = localPanelX + panelW + PANEL_GAP;
        cloudPanelW = panelW;

        int btnW = 80, actionBtnY = this.height - 28;
        uploadBtn = addBtn(localPanelX + (localPanelW - btnW) / 2, actionBtnY, btnW, 20, "Upload",
                b -> { if (ACTIVE.isActive()) cancelTransfer(); else onUpload(); });

        downloadBtn = addBtn(cloudPanelX + (cloudPanelW - btnW * 2 - 10) / 2, actionBtnY, btnW, 20, "Download",
                b -> { if (ACTIVE.isActive()) cancelTransfer(); else onDownload(); });
        deleteBtn = addBtn(cloudPanelX + (cloudPanelW - btnW * 2 - 10) / 2 + btnW + 10, actionBtnY, btnW, 20, "Delete",
                b -> onDelete());

        listY = HEADER_Y + LIST_GAP;
        listH = Math.max(ROW_HEIGHT, actionBtnY - LIST_GAP - listY);
        visibleRows = Math.max(1, listH / ROW_HEIGHT);
        localScrollBar = new ScrollBar(localPanelX + localPanelW + 4, listY, listH);
        cloudScrollBar = new ScrollBar(cloudPanelX + cloudPanelW + 4, listY, listH);

        String apiKey = ConfigManager.loadApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            minecraft.gui.setScreen(new AccountLinkingScreen(this));
            return;
        }
        networkManager.setApiKey(apiKey);
        activeScreen = this;

        if (ACTIVE.isActive()) {
            localLoading = false;
            cloudLoading = false;
            quotaLoading = false;
            localSaves.clear();
            cloudSaves.clear();
            localSaves.addAll(cachedLocalSaves);
            cloudSaves.addAll(cachedCloudSaves);
            quotaFormatted = cachedQuotaFormatted;
            quotaBytes = parseQuotaBytes(cachedQuotaFormatted);
            return;
        }

        quotaFormatted = cachedQuotaFormatted;
        quotaBytes = parseQuotaBytes(cachedQuotaFormatted);

        boolean shouldReloadCloud = (System.currentTimeMillis() - lastLoadTimeMs) > RELOAD_THRESHOLD_MS
                || cachedCloudSaves.isEmpty();
        fetchLocalSaves();
        quotaLoading = true;
        fetchQuotaInfo();
        if (shouldReloadCloud) {
            fetchCloudSaves();
        } else {
            cloudSaves.clear();
            cloudSaves.addAll(cachedCloudSaves);
            cloudLoading = false;
        }
    }

    private static void runOnActive(java.util.function.Consumer<SaveManagerScreen> action) {
        SaveManagerScreen s = activeScreen;
        if (s == null) return;
        net.minecraft.client.Minecraft.getInstance().execute(() -> {
            SaveManagerScreen cur = activeScreen;
            if (cur != null && !cur.closed) action.accept(cur);
        });
    }

    private CustomButton addBtn(int x, int y, int w, int h,
            @org.checkerframework.checker.nullness.qual.NonNull String label, Button.OnPress onPress) {
        CustomButton btn = new CustomButton(x, y, w, h, Component.literal(label), onPress);
        addRenderableWidget(btn);
        return btn;
    }

    public void refresh() {
        localSelectedIndex = -1;
        cloudSelectedIndex = -1;
        localScrollOffset = 0;
        cloudScrollOffset = 0;
        fetchLocalSaves();
        fetchCloudSaves();
        fetchQuotaInfo();
    }

    public Screen getParent() {
        return parent;
    }

    // ── Data fetching ──

    private void fetchLocalSaves() {
        localLoading = true;
        CompletableFuture.runAsync(() -> {
            List<LocalSave> tmp = new ArrayList<>();
            try {
                Path savesDir = minecraft.gameDirectory.toPath().resolve("saves");
                if (Files.exists(savesDir) && Files.isDirectory(savesDir)) {
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(savesDir)) {
                        for (Path p : ds) {
                            Path name = p.getFileName();
                            if (Files.isDirectory(p) && (name == null || !name.toString().startsWith("."))
                                    && Files.isRegularFile(p.resolve("level.dat")))
                                tmp.add(LocalSave.fromDir(p));
                        }
                    }
                }
                tmp.sort(Comparator.comparingLong((LocalSave s) -> s.lastModified).reversed());
            } catch (Exception e) {
                SaveManagerMod.LOGGER.warn("LocalSaves: scan error - {}", extractErrorMessage(e));
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
                    cloudLoading = false;
                    cloudSaves.clear();
                    if (msg.contains("account must be linked"))
                        toastManager.showError(msg + " (Profile -> Edit profile -> Link)");
                    else
                        toastManager.showError(msg);
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
                        if (e.getValue() != null && e.getValue().isJsonArray()) {
                            arr = e.getValue().getAsJsonArray();
                            break;
                        }
                    }
                }
                if (arr != null) {
                    for (int i = 0; i < arr.size(); i++) {
                        if (arr.get(i).isJsonObject())
                            tmp.add(CloudSave.from(arr.get(i).getAsJsonObject()));
                    }
                }
            } catch (Throwable e) {
                SaveManagerMod.LOGGER.warn("CloudSaves: parse error - {}", extractErrorMessage(e));
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

    private void fetchQuotaInfo() {
        networkManager.getQuotaInfo().whenComplete((json, err) -> {
            if (err != null) {
                SaveManagerMod.LOGGER.warn("Quota: fetch failed - {}", extractErrorMessage(err));
                quotaFormatted = cachedQuotaFormatted;
                runOnClient(() -> quotaLoading = false);
                return;
            }
            try {
                AccountState.update(json);
                String quota = json.has("quotaFormatted") ? json.get("quotaFormatted").getAsString() : "5 GB";
                quotaFormatted = quota;
                cachedQuotaFormatted = quota;
                quotaBytes = parseQuotaBytes(quota);
            } catch (Exception e) {
                SaveManagerMod.LOGGER.warn("Quota: parse error - {}", extractErrorMessage(e));
                quotaFormatted = cachedQuotaFormatted;
            }
            runOnClient(() -> quotaLoading = false);
        });
    }

    // ── Actions ──

    private void onUpload() {
        if (localSelectedIndex < 0 || localSelectedIndex >= localSaves.size())
            return;
        if (networkManager.getApiKey() == null || networkManager.getApiKey().isBlank()) {
            minecraft.gui.setScreen(new AccountLinkingScreen(this));
            return;
        }
        LocalSave s = localSaves.get(localSelectedIndex);
        localLoading = true;
        networkManager.listWorldSaveNames().whenComplete((names, err) -> runOnClient(() -> {
            if (err != null) {
                localLoading = false;
                minecraft.gui.setScreen(new AccountLinkingScreen(this));
                return;
            }
            String sanitized = sanitizeFolderName(s.worldName);
            boolean exists = names != null && names.stream().anyMatch(n -> n != null
                    && (n.equalsIgnoreCase(s.worldName) || (!sanitized.isEmpty() && n.equalsIgnoreCase(sanitized))));
            if (!exists) {
                beginZipAndUpload(s);
                return;
            }
            confirmPopup = new ConfirmPopup(this, "Overwrite Cloud Save?",
                    "A save named \"" + s.worldName + "\" already exists. Overwrite?",
                    () -> {
                        confirmPopup = null;
                        beginZipAndUpload(s);
                    },
                    () -> {
                        confirmPopup = null;
                        localLoading = false;
                    }, "Overwrite");
        }));
    }

    private void beginZipAndUpload(LocalSave s) {
        beginDeltaSync(s);
    }

    private void beginLegacyZipUpload(LocalSave s) {
        ACTIVE.reset(false, s.worldName);
        if (s.sizeBytes > 0)
            ACTIVE.total = s.sizeBytes;
        localLoading = true;
        new Thread(() -> {
            Path zip;
            try {
                Path tempRoot = minecraft.gameDirectory.toPath().resolve("savemanager-temp");
                zip = zipWorld(s.dir, s.worldName.replaceAll("[\\\\/:*?\"<>|]+", "_"), tempRoot, n -> {
                    ACTIVE.bytes += n;
                    ACTIVE.updateSpeed();
                });
            } catch (Exception ex) {
                String msg = extractErrorMessage(ex);
                SaveManagerMod.LOGGER.warn("Zip failed - {}", msg);
                ACTIVE.upActive = false;
                ACTIVE.zipping = false;
                runOnActive(sc -> {
                    sc.localLoading = false;
                    sc.toastManager.showError(msg);
                });
                return;
            }
            final Path finalZip = zip;
            final String finalName = s.worldName;
            net.minecraft.client.Minecraft.getInstance().execute(() -> startUpload(finalZip, finalName));
        }, "SaveManager-zip").start();
    }

    private void beginDeltaSync(LocalSave s) {
        beginDeltaSync(s, s.worldName, headVersionFor(s.worldName));
    }

    private void beginDeltaSync(LocalSave s, String targetWorldName, String parentVersionId) {
        ACTIVE.reset(false, s.worldName);
        ACTIVE.zipping = true;
        localLoading = true;

        final String worldName = targetWorldName;
        final Path worldDir = s.dir;

        new Thread(() -> {
            AutoSync.acquireForManual();
            AutoSync.markTransferStarted(s.worldName);
            try {
                SaveManagerMod.LOGGER.info("[SM] manual: hashing '{}' (parent={})",
                        worldName, parentVersionId == null ? "none" : parentVersionId);
                if (!Files.isRegularFile(worldDir.resolve("level.dat")))
                    throw new IOException("\"" + s.worldName + "\" has no level.dat, so it is not a loadable world. "
                            + "Nothing was uploaded.");

                List<WorldManifest.Entry> entries = WorldManifest.build(worldDir, (done, total) -> {
                    ACTIVE.bytes = done;
                    ACTIVE.total = total;
                    ACTIVE.updateSpeed();
                });
                if (entries.isEmpty())
                    throw new IOException("World contains no files.");

                SaveManagerMod.LOGGER.info("[SM] manual: manifest {} file(s), calling begin", entries.size());
                JsonObject begun = networkManager.syncBegin(worldName, parentVersionId, entries).join();
                String sessionId = begun.get("sessionId").getAsString();

                Set<String> missingHashes = new HashSet<>();
                for (var el : begun.getAsJsonArray("missing"))
                    missingHashes.add(el.getAsString().toLowerCase(Locale.ROOT));

                List<WorldManifest.Entry> toSend = new ArrayList<>();
                Set<String> queued = new HashSet<>();
                for (WorldManifest.Entry e : entries) {
                    String h = e.sha256.toLowerCase(Locale.ROOT);
                    if (missingHashes.contains(h) && queued.add(h))
                        toSend.add(e);
                }

                long pending = 0L;
                for (WorldManifest.Entry e : toSend)
                    pending += e.size;
                SaveManagerMod.LOGGER.info("[SM] manual: server needs {} of {} blob(s), {} bytes",
                        toSend.size(), entries.size(), pending);

                ACTIVE.zipping = false;
                ACTIVE.bytes = 0L;
                ACTIVE.total = -1L;
                ACTIVE.lastTickNanos = System.nanoTime();
                ACTIVE.lastBytes = 0L;
                ACTIVE.speedBps = 0.0;

                if (!toSend.isEmpty()) {
                    networkManager.syncUpload(sessionId, toSend, (sent, total) -> {
                        ACTIVE.bytes = Math.max(0L, sent);
                        if (total > 0)
                            ACTIVE.total = total;
                        ACTIVE.updateSpeed();
                    }, () -> ACTIVE.cancelled);
                }

                ACTIVE.abortIfCancelled();

                JsonObject committed = networkManager.syncCommit(sessionId).join();
                if (committed.has("versionId")) {
                    SyncState.recordSuccess(worldName, committed.get("versionId").getAsString());
                    SaveManagerMod.LOGGER.info("[SM] manual: COMMITTED version {} ({} file(s), pruned {})",
                            committed.get("versionId").getAsString(),
                            committed.has("fileCount") ? committed.get("fileCount").getAsInt() : -1,
                            committed.has("prunedVersions") ? committed.get("prunedVersions").getAsInt() : 0);
                }

                String localFolder = s.worldName;
                AutoSync.markClean(localFolder);
                WatchManager.clearPendingNotification(localFolder);
                WatchManager.updateLastKnown(localFolder, worldDir);

                ACTIVE.upActive = false;
                ACTIVE.zipping = false;
                final int sentCount = toSend.size();
                final int totalCount = entries.size();
                runOnActive(sc -> {
                    sc.localLoading = false;
                    sc.toastManager.showSuccess(sentCount == 0
                            ? "Already up to date"
                            : "Synced " + sentCount + " of " + totalCount + " files");
                    sc.fetchLocalSaves();
                    sc.fetchCloudSaves();
                });
            } catch (Throwable ex) {
                Throwable cause = ex instanceof java.util.concurrent.CompletionException && ex.getCause() != null
                        ? ex.getCause()
                        : ex;
                ACTIVE.upActive = false;
                ACTIVE.zipping = false;

                if (cause instanceof java.util.concurrent.CancellationException) {
                    SaveManagerMod.LOGGER.info("[SM] manual: cancelled before commit, nothing was published");
                    runOnActive(sc -> {
                        sc.localLoading = false;
                        sc.toastManager.showInfo("Upload cancelled");
                    });
                    return;
                }

                if (cause instanceof ApiException api && api.isConflict()) {
                    String remote = api.field("head");
                    SyncState.markConflicted(worldName, remote);
                    SaveManagerMod.LOGGER.warn("[SM] manual: CONFLICT on '{}', remote head {}", worldName, remote);
                    runOnActive(sc -> {
                        sc.localLoading = false;
                        sc.promptConflict(s, worldName);
                    });
                    return;
                }

                String msg = extractErrorMessage(cause);
                boolean versioned = isVersionedWorld(s.worldName);
                if (versioned) {
                    SaveManagerMod.LOGGER.warn("[SM] manual: delta FAILED on a synced world - {}", msg);
                    runOnActive(sc -> {
                        sc.localLoading = false;
                        sc.toastManager.showError(msg);
                    });
                    return;
                }

                SaveManagerMod.LOGGER.warn("[SM] manual: delta FAILED, falling back to full zip upload - {}", msg);
                runOnActive(sc -> sc.beginLegacyZipUpload(s));
            } finally {
                AutoSync.markTransferFinished();
                AutoSync.releaseManual();
            }
        }, "SaveManager-sync").start();
    }

    private void promptConflict(LocalSave s, String worldName) {
        confirmPopup = new ConfirmPopup(this, "World Changed Elsewhere",
                "\"" + worldName + "\" was updated from another device since this copy last synced. "
                        + "Uploading would discard that. Upload this copy as a separate world instead? "
                        + "Use Download to take the cloud version instead.",
                () -> {
                    confirmPopup = null;
                    String stamp = java.time.LocalDate.now().toString();
                    String copyName = worldName + " (conflict " + stamp + ")";
                    toastManager.showInfo("Uploading as \"" + copyName + "\"");
                    beginDeltaSync(s, copyName, null);
                },
                () -> confirmPopup = null,
                "Upload as copy");
    }

    private boolean isVersionedWorld(String worldName) {
        if (SyncState.get(worldName).headVersionId != null)
            return true;
        for (CloudSave c : cloudSaves) {
            if (c.worldName != null && c.worldName.equals(worldName))
                return c.versioned;
        }
        return false;
    }

    private String headVersionFor(String worldName) {
        String known = SyncState.get(worldName).headVersionId;
        return known == null || known.isEmpty() ? null : known;
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
                if (total > 0)
                    ACTIVE.total = total;
                ACTIVE.updateSpeed();
            }).whenComplete((json, err) -> {
                try { Files.deleteIfExists(zipFile); } catch (Throwable ignored) {}
                ACTIVE.upActive = false;
                ACTIVE.zipping = false;
                if (err != null) {
                    String msg = extractErrorMessage(err);
                    SaveManagerMod.LOGGER.warn("Upload failed - {}", msg);
                    runOnActive(sc -> {
                        sc.localLoading = false;
                        sc.toastManager.showError(msg);
                    });
                } else {
                    runOnActive(sc -> {
                        sc.localLoading = false;
                        sc.toastManager.showSuccess("Upload complete: " + worldName);
                        LocalSave uploaded = sc.localSaves.stream()
                                .filter(s -> s.worldName.equals(worldName)).findFirst().orElse(null);
                        if (uploaded != null)
                            WatchManager.updateLastKnown(worldName, uploaded.dir);
                        WatchManager.clearPendingNotification(worldName);
                        sc.fetchLocalSaves();
                        sc.fetchCloudSaves();
                    });
                }
            });
        } catch (Throwable t) {
            try { Files.deleteIfExists(zipFile); } catch (Throwable ignored) {}
            ACTIVE.upActive = false;
            runOnActive(sc -> {
                sc.localLoading = false;
                sc.toastManager.showError("Upload failed");
            });
        }
    }

    private void onDownload() {
        if (cloudSelectedIndex < 0 || cloudSelectedIndex >= cloudSaves.size())
            return;
        CloudSave s = cloudSaves.get(cloudSelectedIndex);
        Path savesDir = minecraft.gameDirectory.toPath().resolve("saves");
        try {
            Files.createDirectories(savesDir);
        } catch (Exception ignored) {
        }

        if (!s.versioned || !AccountState.isPremium()) {
            confirmDownload(s, savesDir, null);
            return;
        }

        cloudLoading = true;
        networkManager.syncVersions(s.worldName).whenComplete((arr, err) -> {
            List<CloudVersion> versions = new ArrayList<>();
            if (err == null && arr != null) {
                for (com.google.gson.JsonElement e : arr) {
                    if (e != null && e.isJsonObject())
                        versions.add(CloudVersion.from(e.getAsJsonObject()));
                }
            }
            runOnActive(sc -> {
                sc.cloudLoading = false;
                if (versions.size() > 1)
                    sc.openVersionPopup(s, versions, savesDir);
                else
                    sc.confirmDownload(s, savesDir, null);
            });
        });
    }

    private void openVersionPopup(CloudSave s, List<CloudVersion> versions, Path savesDir) {
        versionPopup = new VersionPickerPopup<>("Which version of \"" + s.worldName + "\"?",
                versions, CloudVersion::label,
                v -> {
                    versionPopup = null;
                    if (v != null)
                        confirmDownload(s, savesDir, v.isHead ? null : v.versionId);
                },
                () -> versionPopup = null);
    }

    private void confirmDownload(CloudSave s, Path savesDir, String versionId) {
        String baseName = sanitizeFolderName(s.worldName);
        if (baseName.isEmpty())
            baseName = "world";
        Path target = savesDir.resolve(baseName);

        if (isWorldLocked(target)) {
            toastManager.showError("That world is open in another instance. Close it first.");
            return;
        }

        if (Files.exists(target)) {
            confirmPopup = new ConfirmPopup(this, "Overwrite Local Save?",
                    "A save named \"" + s.worldName + "\" already exists. Overwrite?",
                    () -> {
                        confirmPopup = null;
                        beginDownload(s, savesDir, versionId);
                    },
                    () -> confirmPopup = null, "Overwrite");
            return;
        }
        beginDownload(s, savesDir, versionId);
    }

    private static boolean isWorldLocked(Path worldDir) {
        if (!Files.isDirectory(worldDir))
            return false;
        try {
            return net.minecraft.util.DirectoryLock.isLocked(worldDir);
        } catch (Exception e) {
            return false;
        }
    }

    private void beginDownload(CloudSave s, Path savesDir) {
        beginDownload(s, savesDir, null);
    }

    private void beginDownload(CloudSave s, Path savesDir, String versionId) {
        cloudLoading = true;
        Path tmpDir;
        try {
            Path tempBase = savesDir.resolve(".savemanager-temp");
            Files.createDirectories(tempBase);
            tmpDir = Files.createTempDirectory(tempBase, "savemanager-dl-");
        } catch (Exception e) {
            toastManager.showError("Failed to prepare temp dir");
            cloudLoading = false;
            return;
        }

        ACTIVE.reset(true, s.worldName);
        if (s.fileSizeBytes > 0)
            ACTIVE.total = s.fileSizeBytes;

        var download = versionId == null
                ? networkManager.downloadWorldSave(s.id, tmpDir, (downloaded, total) -> {
                    ACTIVE.bytes = Math.max(0L, downloaded);
                    if (total > 0)
                        ACTIVE.total = total;
                    ACTIVE.updateSpeed();
                }, () -> ACTIVE.cancelled)
                : networkManager.downloadVersion(versionId, tmpDir, (downloaded, total) -> {
                    ACTIVE.bytes = Math.max(0L, downloaded);
                    if (total > 0)
                        ACTIVE.total = total;
                    ACTIVE.updateSpeed();
                }, () -> ACTIVE.cancelled);

        download.whenComplete((zipPath, err) -> {
            ACTIVE.dlActive = false;
            if (err != null) {
                Throwable dlCause = err instanceof java.util.concurrent.CompletionException && err.getCause() != null
                        ? err.getCause()
                        : err;
                if (dlCause instanceof java.util.concurrent.CancellationException) {
                    SaveManagerMod.LOGGER.info("[SM] download cancelled, the local world was not touched");
                    try { Files.deleteIfExists(tmpDir); } catch (Exception ignored) {}
                    runOnActive(sc -> {
                        sc.cloudLoading = false;
                        sc.toastManager.showInfo("Download cancelled");
                    });
                    return;
                }
                String msg = extractErrorMessage(err);
                SaveManagerMod.LOGGER.warn("Download failed - {}", msg);
                runOnActive(sc -> {
                    sc.cloudLoading = false;
                    sc.toastManager.showError(msg);
                });
                return;
            }
            net.minecraft.client.Minecraft.getInstance().execute(() -> ACTIVE.unzipping = true);
            String baseName = sanitizeFolderName(s.worldName);
            if (baseName.isEmpty())
                baseName = "world";
            Path targetBase = savesDir.resolve(baseName);
            Path stagedDir = null;
            Path displaced = null;
            try {
                Path work = savesDir.resolve(".savemanager-temp");
                Files.createDirectories(work);
                stagedDir = work.resolve(baseName + "-new-" + UUID.randomUUID());
                Files.createDirectories(stagedDir);
                unzipSmart(zipPath, stagedDir);

                ACTIVE.abortIfCancelled();

                Path worldRoot = locateWorldRoot(stagedDir);
                if (worldRoot == null) {
                    SaveManagerMod.LOGGER.warn("[SM] download: no level.dat under {} - extracted {}",
                            stagedDir, describeTree(stagedDir));
                    throw new IOException("That copy has no level.dat. Your save was left untouched.");
                }
                if (!worldRoot.equals(stagedDir)) {
                    SaveManagerMod.LOGGER.info("[SM] download: archive was nested, using {}",
                            stagedDir.relativize(worldRoot));
                    Path lifted = stagedDir.getParent().resolve(stagedDir.getFileName() + "-root");
                    Files.move(worldRoot, lifted, StandardCopyOption.ATOMIC_MOVE);
                    deleteDirectoryRecursively(stagedDir);
                    stagedDir = lifted;
                }

                if (Files.exists(targetBase)) {
                    displaced = work.resolve(baseName + "-old-" + UUID.randomUUID());
                    Files.move(targetBase, displaced, StandardCopyOption.ATOMIC_MOVE);
                }
                try {
                    Files.move(stagedDir, targetBase, StandardCopyOption.ATOMIC_MOVE);
                    stagedDir = null;
                } catch (IOException swapFailed) {
                    if (displaced != null && !Files.exists(targetBase)) {
                        Files.move(displaced, targetBase, StandardCopyOption.ATOMIC_MOVE);
                        displaced = null;
                    }
                    throw swapFailed;
                }

                String folder = targetBase.getFileName().toString();
                if (SyncState.isConflicted(folder) || SyncState.get(folder).headVersionId != null) {
                    AutoSync.discardStaging(folder);
                    SyncState.clearConflict(folder,
                            s.headVersionId == null || s.headVersionId.isEmpty() ? null : s.headVersionId);
                }
                WatchManager.updateLastKnown(folder, targetBase);

                runOnActive(sc -> {
                    sc.toastManager.showSuccess(versionId == null
                            ? "Download complete"
                            : "Restored \"" + s.worldName + "\" from history");
                    sc.fetchLocalSaves();
                });
            } catch (java.util.concurrent.CancellationException cancel) {
                SaveManagerMod.LOGGER.info("[SM] download cancelled before the swap, the local world is unchanged");
                runOnActive(sc -> sc.toastManager.showInfo("Download cancelled"));
            } catch (Exception ex) {
                String msg = extractErrorMessage(ex);
                SaveManagerMod.LOGGER.warn("Unzip failed - {}", msg);
                runOnActive(sc -> sc.toastManager.showError(msg));
            } finally {
                if (stagedDir != null) {
                    try { deleteDirectoryRecursively(stagedDir); } catch (Exception ignored) {}
                }
                if (displaced != null) {
                    try { deleteDirectoryRecursively(displaced); } catch (Exception ignored) {}
                }
                try { Files.deleteIfExists(zipPath); } catch (Exception ignored) {}
                try { Files.deleteIfExists(tmpDir); } catch (Exception ignored) {}
                try { if (Files.exists(tmpDir)) deleteDirectoryRecursively(tmpDir); } catch (Exception ignored) {}
                ACTIVE.unzipping = false;
                runOnActive(sc -> sc.cloudLoading = false);
            }
        });
    }

    private void onDelete() {
        if (localSelectedIndex >= 0 && localSelectedIndex < localSaves.size()) {
            LocalSave s = localSaves.get(localSelectedIndex);
            confirmPopup = new ConfirmPopup(this, "Delete Local Save?",
                    "Are you sure you want to permanently delete \"" + safe(s.worldName) + "\"?",
                    () -> {
                        confirmPopup = null;
                        deleteLocalSave(s);
                    }, () -> confirmPopup = null, "Delete");
            return;
        }
        if (cloudSelectedIndex < 0 || cloudSelectedIndex >= cloudSaves.size())
            return;
        CloudSave s = cloudSaves.get(cloudSelectedIndex);
        confirmPopup = new ConfirmPopup(this, "Delete Cloud Save?",
                "Are you sure you want to permanently delete \"" + safe(s.worldName) + "\"?",
                () -> {
                    confirmPopup = null;
                    cloudLoading = true;
                    networkManager.deleteWorldSave(s.id).whenComplete((resp, err) -> runOnClient(() -> {
                        if (err != null) {
                            String msg = extractErrorMessage(err);
                            SaveManagerMod.LOGGER.warn("Delete failed - {}", msg);
                            toastManager.showError(msg);
                            cloudLoading = false;
                            return;
                        }
                        toastManager.showSuccess("Deleted");
                        cloudLoading = false;
                        cloudSelectedIndex = -1;
                        fetchCloudSaves();
                    }));
                }, () -> confirmPopup = null, "Delete");
    }

    private void deleteLocalSave(LocalSave s) {
        if (isWorldLocked(s.dir)) {
            toastManager.showError("That world is open in another instance. Close it first.");
            return;
        }
        new Thread(() -> {
            try {
                deleteDirectoryRecursively(s.dir);
                runOnClient(() -> {
                    toastManager.showSuccess("Deleted");
                    localSelectedIndex = -1;
                    fetchLocalSaves();
                });
            } catch (Exception e) {
                String msg = extractErrorMessage(e);
                SaveManagerMod.LOGGER.warn("Local delete failed - {}", msg);
                runOnClient(() -> toastManager.showError(msg));
            }
        }, "SaveManager-delete").start();
    }

    private void triggerAutoUpload() {
        if (autoUploadWorld == null)
            return;
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
    public void extractRenderState(@org.checkerframework.checker.nullness.qual.NonNull GuiGraphicsExtractor ctx,
            int mouseX,
            int mouseY, float delta) {
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        updateButtonStates();
        int cx = this.width / 2;
        ctx.centeredText(font, title, cx, 10, 0xFFFFFFFF);

        if (quotaLoading)
            renderTinySpinner(ctx, cx, 28, delta);
        else
            ctx.centeredText(font, Component.literal(java.util.Objects.requireNonNull(computeQuotaLine())), cx, 22,
                    0xFFAAAAAA);

        ctx.text(font, Component.literal("Local Saves"), localPanelX, HEADER_Y, 0xFFFFFFFF);
        ctx.text(font, Component.literal("Cloud Saves"), cloudPanelX, HEADER_Y, 0xFFFFFFFF);
        starTooltipText = null;
        renderSavePanel(ctx, mouseX, mouseY, delta, true);
        renderSavePanel(ctx, mouseX, mouseY, delta, false);
        if (starTooltipText != null) {
            ctx.setComponentTooltipForNextFrame(font,
                    List.of(Component.literal(starTooltipText)), mouseX, mouseY);
        }

        // Re-render action buttons on top of panels so they're always clickable
        uploadBtn.extractRenderState(ctx, mouseX, mouseY, delta);
        downloadBtn.extractRenderState(ctx, mouseX, mouseY, delta);
        deleteBtn.extractRenderState(ctx, mouseX, mouseY, delta);

        toastManager.render(ctx, delta, mouseX, mouseY);
        if (versionPopup != null)
            versionPopup.extractRenderState(ctx, mouseX, mouseY, delta);
        if (confirmPopup != null)
            confirmPopup.extractRenderState(ctx, mouseX, mouseY, delta);
    }

    private void renderSavePanel(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta, boolean isLocal) {
        List<?> saves = isLocal ? localSaves : cloudSaves;
        ScrollBar scrollBar = isLocal ? localScrollBar : cloudScrollBar;
        int panelX = isLocal ? localPanelX : cloudPanelX;
        int panelW = isLocal ? localPanelW : cloudPanelW;
        int selectedIndex = isLocal ? localSelectedIndex : cloudSelectedIndex;
        int scrollOffset = isLocal ? localScrollOffset : cloudScrollOffset;

        int maxScroll = Math.max(0, saves.size() - visibleRows);
        scrollBar.setScrollData(saves.size() * ROW_HEIGHT, listH);
        if (maxScroll > 0)
            scrollBar.setScrollPercentage((double) scrollOffset / maxScroll);

        boolean blockHover = toastManager.isMouseOverToast(mouseX, mouseY) || confirmPopup != null
                || versionPopup != null;

        if (scrollBar.updateAndRender(ctx, mouseX, mouseY, delta, minecraft.getWindow().handle())) {
            int newOffset = (int) Math.round(scrollBar.getScrollPercentage() * maxScroll);
            if (isLocal)
                localScrollOffset = newOffset;
            else
                cloudScrollOffset = newOffset;
            scrollOffset = newOffset;
        }

        ctx.enableScissor(panelX, listY, panelX + panelW, listY + listH);
        int end = Math.min(scrollOffset + visibleRows + 1, saves.size());
        for (int i = scrollOffset; i < end; i++) {
            int ry = listY + (i - scrollOffset) * ROW_HEIGHT;

            String worldName, info;
            if (isLocal) {
                LocalSave s = (LocalSave) saves.get(i);
                if (s == null)
                    continue;
                worldName = s.worldName;
                info = formatBytes(s.sizeBytes) + " \u2022 " + shortDateMillis(s.lastModified);
            } else {
                CloudSave s = (CloudSave) saves.get(i);
                if (s == null)
                    continue;
                worldName = s.worldName;
                info = formatBytes(s.fileSizeBytes) + " \u2022 " + shortDate(s.updatedAt);
            }

            if (isTransferring(worldName, isLocal)) {
                renderRowProgress(ctx, panelX, panelW, ry);
                info = transferInfo();
            } else if (i == selectedIndex) {
                ctx.fill(panelX, ry - 1, panelX + panelW, ry + ROW_HEIGHT - 2, 0x33FFFFFF);
            }

            ctx.text(font, Component.literal(java.util.Objects.requireNonNull(safe(worldName))), panelX + 4, ry + 2,
                    0xFFDDDDDD);
            ctx.text(font, Component.literal(java.util.Objects.requireNonNull(info)), panelX + 4, ry + 12,
                    0xFF888888);
            if (isLocal) {
                boolean watching = WatchManager.isWatching(worldName);
                int starColor = watching ? 0xFFFFDD44 : 0xFF383838;
                int starX = panelX + panelW - 12, starY = ry + 7;
                ctx.text(font, Component.literal("\u2605"), starX, starY, starColor);
                if (!blockHover && mouseX >= starX - 1 && mouseX < starX + 8 && mouseY >= starY
                        && mouseY < starY + 9)
                    starTooltipText = watching ? "Remove from favorite" : "Add to favorite";
            }
            ctx.fill(panelX, ry + ROW_HEIGHT - 2, panelX + panelW, ry + ROW_HEIGHT - 1, 0x22FFFFFF);
        }
        ctx.disableScissor();
    }

    public static boolean isUploading() {
        return ACTIVE.upActive;
    }

    public static boolean isDownloading() {
        return ACTIVE.dlActive;
    }

    public static double transferProgress() {
        long total = ACTIVE.total, bytes = ACTIVE.bytes;
        return total > 0 ? Math.min(1.0, (double) bytes / total) : 0.0;
    }

    private boolean isTransferring(String worldName, boolean isLocal) {
        return ACTIVE.isActive() && worldName.equals(ACTIVE.worldName)
                && (isLocal ? ACTIVE.upActive : ACTIVE.dlActive);
    }

    private void renderRowProgress(GuiGraphicsExtractor ctx, int panelX, int panelW, int ry) {
        int top = ry - 1, bot = ry + ROW_HEIGHT - 2;
        ctx.fill(panelX, top, panelX + panelW, bot, 0x1AFFFFFF);
        long total = ACTIVE.total;
        if (total > 0) {
            double frac = Math.min(1.0, (double) ACTIVE.bytes / total);
            ctx.fill(panelX, top, panelX + (int) (panelW * frac), bot, 0x40FFFFFF);
        }
    }

    private String transferInfo() {
        long bytes = ACTIVE.bytes, total = ACTIVE.total;
        if (bytes <= 0 || total <= 0)
            return ACTIVE.zipping ? "Zipping..." : ACTIVE.unzipping ? "Unzipping..." : "Preparing...";
        String prefix = ACTIVE.zipping ? "Zipping \u2022 " : ACTIVE.unzipping ? "Unzipping \u2022 " : "";
        String info = prefix + formatBytes(bytes) + " / " + formatBytes(total);
        double speed = ACTIVE.speedBps;
        if (speed > 1) {
            long etaSec = (long) Math.ceil(Math.max(0L, total - bytes) / Math.max(1.0, speed));
            info += " \u2022 " + formatBytes((long) speed) + "/s \u2022 ETA " + formatDuration(etaSec);
        }
        return info;
    }

    private static long lastStaleCheckMs;

    private static void clearStaleTransferState() {
        if (!ACTIVE.isActive())
            return;
        long now = System.currentTimeMillis();
        if (now - lastStaleCheckMs < 1000L)
            return;
        lastStaleCheckMs = now;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith("SaveManager-"))
                return;
        }
        SaveManagerMod.LOGGER.warn("[SM] clearing stale transfer state, no transfer thread is alive");
        ACTIVE.dlActive = false;
        ACTIVE.upActive = false;
        ACTIVE.zipping = false;
        ACTIVE.unzipping = false;
        ACTIVE.cancelled = false;
        AutoSync.releaseManualIfHeld();
    }

    private void cancelTransfer() {
        if (!ACTIVE.isActive() || ACTIVE.cancelled)
            return;
        ACTIVE.cancelled = true;
        toastManager.showInfo("Cancelling\u2026");
    }

    private void updateButtonStates() {
        clearStaleTransferState();
        boolean opActive = ACTIVE.isActive();
        boolean uploading = ACTIVE.upActive || ACTIVE.zipping;
        boolean downloading = ACTIVE.dlActive || ACTIVE.unzipping;
        boolean hasLocal = localSelectedIndex >= 0 && localSelectedIndex < localSaves.size();
        boolean hasCloud = cloudSelectedIndex >= 0 && cloudSelectedIndex < cloudSaves.size();
        if (uploadBtn != null) {
            uploadBtn.active = uploading ? !ACTIVE.cancelled : (hasLocal && !opActive);
            uploadBtn.setMessage(Component.literal(uploading ? "Cancel" : "Upload"));
        }
        if (downloadBtn != null) {
            downloadBtn.active = downloading ? !ACTIVE.cancelled : (hasCloud && !opActive);
            downloadBtn.setMessage(Component.literal(downloading ? "Cancel" : "Download"));
        }
        if (deleteBtn != null)
            deleteBtn.active = (hasLocal || hasCloud) && !opActive;
        if (refreshBtn != null)
            refreshBtn.active = !localLoading && !cloudLoading;
    }

    // ── Input handling ──

    @Override
    public boolean mouseClicked(
            net.minecraft.client.input.@org.checkerframework.checker.nullness.qual.NonNull MouseButtonEvent click,
            boolean doubleClick) {
        double mx = click.x(), my = click.y();
        if (confirmPopup != null)
            return confirmPopup.mouseClicked(click.x(), click.y(), click.button());
        if (versionPopup != null)
            return versionPopup.mouseClicked(click.x(), click.y(), click.button());
        if (toastManager.mouseClicked(click.x(), click.y()))
            return true;
        if (toastManager.isMouseOverToast(mx, my))
            return true;
        if (super.mouseClicked(click, false))
            return true;

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
                localSelectedIndex = idx;
                cloudSelectedIndex = -1;
                return true;
            }
            idx = clickedRowIndex(mx, my, cloudPanelX, cloudPanelW, cloudScrollOffset, cloudSaves.size());
            if (idx >= 0) {
                cloudSelectedIndex = idx;
                localSelectedIndex = -1;
                return true;
            }
        }
        return false;
    }

    private int clickedRowIndex(double mx, double my, int panelX, int panelW, int scrollOffset, int count) {
        if (mx < panelX || mx >= panelX + panelW || my < listY || my >= listY + listH)
            return -1;
        int rowIdx = (int) ((my - listY) / ROW_HEIGHT) + scrollOffset;
        if (rowIdx < 0 || rowIdx >= count)
            return -1;
        int clickY = listY + ((rowIdx - scrollOffset) * ROW_HEIGHT);
        return (my >= clickY && my < clickY + ROW_HEIGHT) ? rowIdx : -1;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double h, double v) {
        if (confirmPopup != null && confirmPopup.mouseScrolled(mouseX, mouseY, h, v))
            return true;
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
        return Math.max(0, Math.min(Math.max(0, count - visibleRows), offset));
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        if (confirmPopup != null) {
            confirmPopup = null;
            localLoading = false;
            cloudLoading = false;
        } else if (versionPopup != null) {
            versionPopup = null;
            cloudLoading = false;
        } else {
            closeScreen();
        }
    }

    // ── Navigation & utilities ──

    private void closeScreen() {
        closed = true;
        activeScreen = null;
        autoUploadWorld = null;
        Screen dest;
        if (parent instanceof SelectWorldScreen) {
            Screen grandParent = ((SelectWorldScreenAccessor) parent).getParentScreen();
            dest = new SelectWorldScreen(java.util.Objects.requireNonNull(grandParent));
        } else {
            dest = new SelectWorldScreen(java.util.Objects.requireNonNull(ScreenUtils.resolveRootParent(parent)));
        }
        minecraft.gui.setScreen(dest);
    }

    private void openSavesFolder() {
        try {
            Path savesDir = minecraft.gameDirectory.toPath().resolve("saves");
            Files.createDirectories(savesDir);
            net.minecraft.util.Util.getPlatform().openUri(java.util.Objects.requireNonNull(savesDir.toUri()));
        } catch (Exception e) {
            SaveManagerMod.LOGGER.warn("Failed to open saves folder - {}", extractErrorMessage(e));
            toastManager.showError("Failed to open saves folder");
        }
    }

    private void runOnClient(Runnable r) {
        if (minecraft != null)
            minecraft.execute(() -> {
                if (!closed)
                    r.run();
            });
    }

    private @org.checkerframework.checker.nullness.qual.NonNull String computeQuotaLine() {
        long used = cloudSaves.stream().mapToLong(s -> Math.max(0L, s.fileSizeBytes)).sum();
        return formatBytes(used) + " of " + quotaFormatted + " (" + formatBytes(Math.max(0L, quotaBytes - used))
                + " left)";
    }

    private static long parseQuotaBytes(String formatted) {
        if (formatted == null || formatted.isEmpty())
            return 5L * 1024L * 1024L * 1024L;
        try {
            String[] parts = formatted.trim().split("\\s+");
            if (parts.length >= 2) {
                double value = Double.parseDouble(parts[0]);
                long mult = switch (parts[1].toUpperCase()) {
                    case "B" -> 1L;
                    case "KB" -> 1024L;
                    case "MB" -> 1024L * 1024L;
                    case "GB" -> 1024L * 1024L * 1024L;
                    case "TB" -> 1024L * 1024L * 1024L * 1024L;
                    default -> 1024L * 1024L * 1024L;
                };
                return (long) (value * mult);
            }
        } catch (Exception ignored) {
        }
        return 5L * 1024L * 1024L * 1024L;
    }

    // ── File I/O helpers ──

    private static Path locateWorldRoot(Path extracted) throws IOException {
        if (Files.isRegularFile(extracted.resolve("level.dat")))
            return extracted;
        try (Stream<Path> kids = Files.list(extracted)) {
            for (Path kid : kids.toList()) {
                if (Files.isDirectory(kid) && Files.isRegularFile(kid.resolve("level.dat")))
                    return kid;
            }
        }
        return null;
    }

    private static String describeTree(Path dir) {
        try (Stream<Path> kids = Files.list(dir)) {
            List<String> names = kids.map(p -> p.getFileName().toString()
                    + (Files.isDirectory(p) ? "/" : "")).sorted().limit(12).toList();
            return names.isEmpty() ? "nothing" : String.join(", ", names);
        } catch (IOException e) {
            return "unreadable (" + e + ")";
        }
    }

    private static void unzipSmart(Path zipFile, Path targetBase) throws Exception {
        String root = detectSingleRootDir(zipFile);
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zipFile.toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (e == null || e.isDirectory())
                    continue;
                String name = e.getName().replace('\\', '/');
                if (root != null && name.startsWith(root + "/"))
                    name = name.substring(root.length() + 1);
                if (name.isBlank())
                    continue;
                Path out = targetBase.resolve(name).normalize();
                if (!out.startsWith(targetBase))
                    throw new IllegalArgumentException("Blocked zip entry: " + name);
                Files.createDirectories(out.getParent());
                try (InputStream in = zf.getInputStream(e)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static String detectSingleRootDir(Path zipFile) throws Exception {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zipFile.toFile())) {
            String root = null;
            var en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry entry = en.nextElement();
                if (entry == null)
                    continue;
                String name = entry.getName().replace('\\', '/');
                if (name.isBlank())
                    continue;
                String top = name.split("/", 2)[0];
                if (top.isBlank())
                    return null;
                if (root == null)
                    root = top;
                else if (!root.equals(top))
                    return null;
            }
            return root;
        }
    }

    private static Path zipWorld(Path worldDir, String worldName, Path tempRoot,
            java.util.function.LongConsumer onBytes) throws Exception {
        Path zip;
        try {
            Path parent = worldDir.getParent();
            Path dir = null;
            if (tempRoot != null) {
                try {
                    Files.createDirectories(tempRoot);
                    if (Files.isDirectory(tempRoot) && Files.isWritable(tempRoot))
                        dir = tempRoot;
                } catch (Exception ignored) {
                }
            }
            if (dir == null && parent != null && Files.isDirectory(parent) && Files.isWritable(parent))
                dir = parent;
            if (dir == null)
                throw new java.io.IOException("No writable temp directory for archive staging");
            zip = Files.createTempFile(dir, "savemanager-" + worldName + "-", ".zip");
        } catch (Exception e) {
            throw new java.io.IOException("Failed to create archive staging file", e);
        }
        try (var zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip, StandardOpenOption.WRITE))) {
            Files.walkFileTree(worldDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                    Path rel = worldDir.relativize(file);
                    if ("session.lock".equalsIgnoreCase(rel.getFileName().toString()))
                        return FileVisitResult.CONTINUE;
                    zos.putNextEntry(new java.util.zip.ZipEntry(rel.toString().replace('\\', '/')));
                    Files.copy(file, zos);
                    zos.closeEntry();
                    onBytes.accept(attrs.size());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            try {
                Files.deleteIfExists(zip);
            } catch (Exception ignored) {
            }
            throw e;
        }
        SaveManagerMod.LOGGER.info("Zip: created temp archive at {} (world={})", zip, worldName);
        return zip;
    }

    private static void deleteDirectoryRecursively(Path dir) throws Exception {
        if (!Files.exists(dir))
            return;
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws java.io.IOException {
                Files.delete(f);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, java.io.IOException e) throws java.io.IOException {
                if (e != null)
                    throw e;
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ── JSON helpers ──

    private static JsonArray findArray(JsonObject obj, String... names) {
        for (String n : names) {
            if (!obj.has(n))
                continue;
            var e = obj.get(n);
            if (e != null && e.isJsonArray())
                return e.getAsJsonArray();
        }
        return null;
    }

    private static String getString(JsonObject obj, String... names) {
        for (String n : names) {
            if (!obj.has(n))
                continue;
            var e = obj.get(n);
            if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString())
                return e.getAsString();
        }
        return "";
    }

    private static long getLong(JsonObject obj, String... names) {
        for (String n : names) {
            if (!obj.has(n))
                continue;
            var e = obj.get(n);
            if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber())
                return e.getAsLong();
        }
        return 0L;
    }

    // ── Tiny spinner for quota loading ──

    private static float tinySpinnerAngle = 0f;

    private void renderTinySpinner(GuiGraphicsExtractor ctx, int cx, int cy, float delta) {
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
            this.dir = dir;
            this.worldName = worldName;
            this.sizeBytes = sizeBytes;
            this.lastModified = lastModified;
        }

        static LocalSave fromDir(Path dir) {
            String name = dir.getFileName() != null ? dir.getFileName().toString() : dir.toString();
            long lm = 0L;
            try {
                lm = Files.getLastModifiedTime(dir).toMillis();
            } catch (Exception ignored) {
            }
            long size = 0L;
            try {
                size = computeDirSize(dir);
            } catch (Exception ignored) {
            }
            return new LocalSave(dir, name, size, lm);
        }

        static long computeDirSize(Path dir) throws Exception {
            final long[] sum = { 0L };
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    try {
                        sum[0] += Files.size(f);
                    } catch (Exception ignored) {
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return sum[0];
        }
    }

    static class CloudVersion {
        String versionId, createdAt;
        int fileCount;
        long totalBytes;
        boolean isHead;

        static CloudVersion from(JsonObject o) {
            CloudVersion v = new CloudVersion();
            v.versionId = getString(o, "versionId", "id");
            v.createdAt = getString(o, "createdAt", "created");
            v.fileCount = (int) getLong(o, "fileCount");
            v.totalBytes = getLong(o, "totalBytes", "sizeBytes", "size");
            v.isHead = o.has("isHead") && o.get("isHead").getAsBoolean();
            return v;
        }

        String label() {
            String when = shortDate(createdAt);
            return (isHead ? "Current \u2022 " : "") + when
                    + " \u2022 " + fileCount + " files \u2022 " + formatBytes(totalBytes);
        }
    }

    static class CloudSave {
        String id, worldName, createdAt, updatedAt;
        long fileSizeBytes;
        String headVersionId;
        boolean versioned;

        static CloudSave from(JsonObject o) {
            CloudSave s = new CloudSave();
            s.id = getString(o, "id", "saveId", "guid");
            s.worldName = getString(o, "worldName", "name", "world", "title");
            s.fileSizeBytes = getLong(o, "sizeBytes", "fileSizeBytes", "fileSize", "size", "bytes");
            s.createdAt = getString(o, "createdAt", "created", "created_on");
            s.updatedAt = getString(o, "updatedAt", "updated", "updated_on", "lastModified");
            s.headVersionId = getString(o, "headVersionId");
            s.versioned = "Versioned".equalsIgnoreCase(getString(o, "storageMode"));
            return s;
        }
    }
}
