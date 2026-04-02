package com.choculaterie.util;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;

import java.lang.reflect.Field;

public final class ScreenUtils {
    private ScreenUtils() {}

    public static Screen resolveRootParent(Screen parent) {
        Screen p = parent;
        int guard = 0;
        while (p instanceof SelectWorldScreen && guard++ < 8) {
            try {
                Field f = SelectWorldScreen.class.getDeclaredField("lastScreen");
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
}
