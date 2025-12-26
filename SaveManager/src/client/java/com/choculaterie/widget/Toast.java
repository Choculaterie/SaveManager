package com.choculaterie.widget;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

public class Toast {
    public enum Type {
        SUCCESS(0xFF44FF44),
        ERROR(0xFFFF4444),
        INFO(0xFF4488FF),
        WARNING(0xFFFFAA44);

        final int color;

        Type(int color) {
            this.color = color;
        }
    }

    private static final int TOAST_WIDTH = 250;
    private static final int TOAST_HEIGHT = 40;
    private static final int TOAST_HEIGHT_WITH_BUTTON = 60;
    private static final int PADDING = 10;
    private static final long SLIDE_DURATION = 300;
    private static final long DISPLAY_DURATION = 3000;
    private static final long ERROR_DISPLAY_DURATION = 8000;
    private static final long FADE_DURATION = 500;

    private final String message;
    private final Type type;
    private final long createdTime;
    private final int screenWidth;
    private int yPosition;
    private int targetYPosition;
    private long lastYPositionChange;
    private final boolean hasCopyButton;
    private final String copyText;
    private CustomButton copyButton;
    private CustomButton closeButton;
    private boolean dismissed = false;
    private boolean hovered = false;
    private long pausedTime = 0;
    private long hoverStartTime = 0;
    private static double mouseX = 0;
    private static double mouseY = 0;

    public Toast(String message, Type type, int screenWidth, int yPosition) {
        this(message, type, screenWidth, yPosition, false, null);
    }

    public Toast(String message, Type type, int screenWidth, int yPosition, boolean hasCopyButton, String copyText) {
        this.message = message;
        this.type = type;
        this.screenWidth = screenWidth;
        this.yPosition = yPosition;
        this.targetYPosition = yPosition;
        this.createdTime = System.currentTimeMillis();
        this.lastYPositionChange = createdTime;
        this.hasCopyButton = hasCopyButton;
        this.copyText = copyText != null ? copyText : message;
        closeButton = new CustomButton(0, 0, 16, 16, Text.of("×"), btn -> {});
        if (hasCopyButton) {
            copyButton = new CustomButton(0, 0, 50, 18, Text.of("Copy"), btn -> {});
        }
    }

    public void setTargetYPosition(int targetY) {
        if (this.targetYPosition != targetY) {
            this.targetYPosition = targetY;
            this.lastYPositionChange = System.currentTimeMillis();
        }
    }

    public boolean render(DrawContext context, net.minecraft.client.font.TextRenderer textRenderer) {
        long now = System.currentTimeMillis();

        // Calculate total paused time including current hover
        long totalPausedTime = pausedTime;
        if (hovered && hoverStartTime > 0) {
            totalPausedTime += (now - hoverStartTime);
        }

        // Adjust elapsed time by subtracting paused time
        long elapsed = now - createdTime - totalPausedTime;
        long displayDuration = hasCopyButton ? ERROR_DISPLAY_DURATION : DISPLAY_DURATION;

        if (dismissed || elapsed > SLIDE_DURATION + displayDuration + FADE_DURATION) {
            return true;
        }

        if (yPosition != targetYPosition) {
            long yTransitionElapsed = now - lastYPositionChange;
            float yTransitionProgress = Math.min(1.0f, yTransitionElapsed / 400.0f);
            yTransitionProgress = (float) (1 - Math.pow(1 - yTransitionProgress, 3));
            yPosition = (int) (yPosition + (targetYPosition - yPosition) * yTransitionProgress);
            if (Math.abs(yPosition - targetYPosition) < 1) {
                yPosition = targetYPosition;
            }
        }

        float slideProgress = Math.min(1.0f, elapsed / (float) SLIDE_DURATION);
        float fadeProgress = 1.0f;
        if (elapsed > SLIDE_DURATION + displayDuration) {
            long fadeElapsed = elapsed - SLIDE_DURATION - displayDuration;
            fadeProgress = 1.0f - (fadeElapsed / (float) FADE_DURATION);
        }
        slideProgress = 1 - (float) Math.pow(1 - slideProgress, 3);

        int targetX = screenWidth - TOAST_WIDTH - PADDING;
        int startX = screenWidth + TOAST_WIDTH;
        int currentX = (int) (startX + (targetX - startX) * slideProgress);
        int toastHeight = hasCopyButton ? TOAST_HEIGHT_WITH_BUTTON : TOAST_HEIGHT;
        int alpha = (int) (255 * fadeProgress);
        if (alpha <= 0) return true;

        int bgColorWithAlpha = (alpha << 24) | 0x002A2A2A;
        context.fill(currentX, yPosition, currentX + TOAST_WIDTH, yPosition + toastHeight, bgColorWithAlpha);

        int borderColorWithAlpha = (alpha << 24) | (type.color & 0x00FFFFFF);
        context.fill(currentX, yPosition, currentX + 4, yPosition + toastHeight, borderColorWithAlpha);

        int topBorderColorWithAlpha = (alpha << 24) | 0x00444444;
        context.fill(currentX, yPosition, currentX + TOAST_WIDTH, yPosition + 1, topBorderColorWithAlpha);
        context.fill(currentX, yPosition + toastHeight - 1, currentX + TOAST_WIDTH, yPosition + toastHeight, topBorderColorWithAlpha);
        context.fill(currentX + TOAST_WIDTH - 1, yPosition, currentX + TOAST_WIDTH, yPosition + toastHeight, topBorderColorWithAlpha);

        String icon = switch (type) {
            case SUCCESS -> "✓";
            case ERROR -> "✗";
            case WARNING -> "⚠";
            case INFO -> "ℹ";
        };

        int iconColorWithAlpha = (alpha << 24) | (type.color & 0x00FFFFFF);
        context.drawText(textRenderer, icon, currentX + 12, yPosition + 8, iconColorWithAlpha, false);

        int textX = currentX + 28;
        int textY = yPosition + 8;
        int maxTextWidth = TOAST_WIDTH - 40;

        String displayText = message;
        int textWidth = textRenderer.getWidth(displayText);
        if (textWidth > maxTextWidth) {
            while (textWidth > maxTextWidth - 10 && displayText.length() > 3) {
                displayText = displayText.substring(0, displayText.length() - 1);
                textWidth = textRenderer.getWidth(displayText + "...");
            }
            displayText += "...";
        }

        int textColorWithAlpha = (alpha << 24) | 0x00FFFFFF;
        context.drawText(textRenderer, displayText, textX, textY, textColorWithAlpha, false);

        if (closeButton != null) {
            int closeButtonX = currentX + TOAST_WIDTH - 16 - 4;
            int closeButtonY = yPosition + 4;
            closeButton.setX(closeButtonX);
            closeButton.setY(closeButtonY);
            closeButton.setWidth(16);
            closeButton.setHeight(16);
            closeButton.render(context, (int) mouseX, (int) mouseY, 0);
        }

        if (hasCopyButton && copyButton != null) {
            int buttonX = currentX + TOAST_WIDTH - 50 - 8;
            int buttonY = yPosition + toastHeight - 18 - 6;
            copyButton.setX(buttonX);
            copyButton.setY(buttonY);
            copyButton.setWidth(50);
            copyButton.setHeight(18);
            copyButton.render(context, (int) mouseX, (int) mouseY, 0);
        }

        return false;
    }

    public boolean isHovering(double mouseX, double mouseY) {
        long now = System.currentTimeMillis();

        // Calculate total paused time including current hover
        long totalPausedTime = pausedTime;
        if (hovered && hoverStartTime > 0) {
            totalPausedTime += (now - hoverStartTime);
        }

        long elapsed = now - createdTime - totalPausedTime;
        long displayDuration = hasCopyButton ? ERROR_DISPLAY_DURATION : DISPLAY_DURATION;

        if (dismissed || elapsed > SLIDE_DURATION + displayDuration) {
            return false;
        }

        float slideProgress = Math.min(1.0f, elapsed / (float) SLIDE_DURATION);
        slideProgress = 1 - (float) Math.pow(1 - slideProgress, 3);
        int targetX = screenWidth - TOAST_WIDTH - PADDING;
        int startX = screenWidth + TOAST_WIDTH;
        int currentX = (int) (startX + (targetX - startX) * slideProgress);
        int toastHeight = hasCopyButton ? TOAST_HEIGHT_WITH_BUTTON : TOAST_HEIGHT;
        return mouseX >= currentX && mouseX < currentX + TOAST_WIDTH &&
                mouseY >= yPosition && mouseY < yPosition + toastHeight;
    }

    public boolean isCloseButtonClicked(double mouseX, double mouseY) {
        if (closeButton == null) return false;
        return mouseX >= closeButton.getX() && mouseX < closeButton.getX() + closeButton.getWidth() &&
                mouseY >= closeButton.getY() && mouseY < closeButton.getY() + closeButton.getHeight();
    }

    public static void updateMousePosition(double x, double y) {
        mouseX = x;
        mouseY = y;
    }

    public void dismiss() {
        this.dismissed = true;
    }

    public void setHovered(boolean hovered) {
        if (hovered && !this.hovered) {
            this.hoverStartTime = System.currentTimeMillis();
        } else if (!hovered && this.hovered) {
            this.pausedTime += System.currentTimeMillis() - this.hoverStartTime;
        }
        this.hovered = hovered;
    }

    public boolean isHovered() {
        return hovered;
    }

    public int getHeight() {
        return (hasCopyButton ? TOAST_HEIGHT_WITH_BUTTON : TOAST_HEIGHT) + 5;
    }

    public boolean isCopyButtonClicked(double mouseX, double mouseY) {
        if (!hasCopyButton || copyButton == null) return false;
        return mouseX >= copyButton.getX() && mouseX < copyButton.getX() + copyButton.getWidth() &&
                mouseY >= copyButton.getY() && mouseY < copyButton.getY() + copyButton.getHeight();
    }

    public String getCopyText() {
        return copyText;
    }
}

