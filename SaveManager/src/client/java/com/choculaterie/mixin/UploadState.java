package com.choculaterie.mixin;

// Simple holder for upload UI state shared with the SelectWorldScreen mixin
public final class UploadState {
    public volatile boolean active = false;   // true while zipping/uploading
    public volatile boolean zipping = false;  // true during zipping phase
    public volatile long uploaded = 0L;       // bytes sent
    public volatile long total = -1L;         // total bytes (or -1)
    public volatile long startNanos = 0L;
    public volatile long lastTickNanos = 0L;
    public volatile long lastBytes = 0L;
    public volatile double speedBps = 0.0;

    public UploadState() {}
}

