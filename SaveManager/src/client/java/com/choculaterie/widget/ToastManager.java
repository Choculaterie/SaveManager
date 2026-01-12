package com.choculaterie.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ToastManager {
    private final List<Toast> toasts = new ArrayList<>();
    private final MinecraftClient client;
    private static final int TOP_PADDING = 10;

    public ToastManager(MinecraftClient client) {
        this.client = client;
    }

    public void showToast(String message, Toast.Type type) {
        showToast(message, type, false, null, null);
    }

    public void showToast(String message, Toast.Type type, boolean hasCopyButton, String copyText) {
        showToast(message, type, hasCopyButton, copyText, null);
    }

    public void showToast(String message, Toast.Type type, boolean hasCopyButton, String copyText, String hintText) {
        if (client.getWindow() == null) return;
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();
        int yPosition = TOP_PADDING;
        for (Toast toast : toasts) {
            yPosition += toast.getHeight();
        }
        if (yPosition + 70 > screenHeight) {
            if (!toasts.isEmpty()) {
                toasts.remove(0);
                yPosition = TOP_PADDING;
                for (Toast toast : toasts) {
                    yPosition += toast.getHeight();
                }
            }
        }
        toasts.add(new Toast(message, type, screenWidth, yPosition, hasCopyButton, copyText, hintText));
    }

    public void showSuccess(String message) {
        showToast(message, Toast.Type.SUCCESS);
    }

    public void showError(String message) {
        showToast(message, Toast.Type.ERROR);
    }

    public void showError(String message, String hintText) {
        showToast(message, Toast.Type.ERROR, false, null, hintText);
    }

    public void showError(String message, String fullErrorText, String hintText) {
        showToast(message, Toast.Type.ERROR, true, fullErrorText, hintText);
    }

    public void showInfo(String message) {
        showToast(message, Toast.Type.INFO);
    }

    public void showWarning(String message) {
        showToast(message, Toast.Type.WARNING);
    }

    public void render(DrawContext context, float delta, int mouseX, int mouseY) {
        if (toasts.isEmpty()) return;
        Toast.updateMousePosition(mouseX, mouseY);
        Iterator<Toast> iterator = toasts.iterator();
        boolean toastRemoved = false;
        while (iterator.hasNext()) {
            Toast toast = iterator.next();
            toast.setHovered(toast.isHovering(mouseX, mouseY));
            boolean shouldRemove = toast.render(context, client.textRenderer);
            if (shouldRemove) {
                iterator.remove();
                toastRemoved = true;
            }
        }
        if (toastRemoved && !toasts.isEmpty()) {
            int newY = TOP_PADDING;
            for (Toast toast : toasts) {
                toast.setTargetYPosition(newY);
                newY += toast.getHeight();
            }
        }
    }

    public void clear() {
        toasts.clear();
    }

    public boolean hasToasts() {
        return !toasts.isEmpty();
    }

    public boolean isMouseOverToast(double mouseX, double mouseY) {
        for (Toast toast : toasts) {
            if (toast.isHovering(mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean consumed) {
        if (consumed) {
            return false;
        }
        double mouseX = click.x();
        double mouseY = click.y();

        for (Toast toast : toasts) {
            if (toast.isCloseButtonClicked(mouseX, mouseY)) {
                toast.dismiss();
                return true;
            }
            if (toast.isCopyButtonClicked(mouseX, mouseY)) {
                String textToCopy = toast.getCopyText();
                if (client.keyboard != null) {
                    client.keyboard.setClipboard(textToCopy);
                    showSuccess("Error copied to clipboard!");
                    return true;
                }
            }
        }
        return false;
    }
}
