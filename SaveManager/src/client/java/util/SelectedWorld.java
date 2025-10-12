package com.choculaterie.util;

import java.nio.file.Path;
import java.util.Objects;

public final class SelectedWorld {
    private final Path dir;
    private final String displayName;

    public SelectedWorld(Path dir, String displayName) {
        this.dir = Objects.requireNonNull(dir, "dir");
        this.displayName = displayName == null ? "" : displayName;
    }

    public Path getDir() {
        return dir;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return "SelectedWorld{dir=" + dir + ", displayName='" + displayName + "'}";
    }
}
