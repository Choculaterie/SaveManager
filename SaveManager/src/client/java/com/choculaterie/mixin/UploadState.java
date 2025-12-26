package com.choculaterie.mixin;

// Simple holder for upload UI state shared with the SelectWorldScreen mixin
public final class UploadState {
    public volatile boolean active = false;
    public volatile boolean zipping = false;
    public volatile long uploaded = 0L;
    public volatile long total = -1L;
    public volatile long startNanos = 0L;
    public volatile long lastTickNanos = 0L;
    public volatile long lastBytes = 0L;
    public volatile double speedBps = 0.0;

    public UploadState() {}
}

