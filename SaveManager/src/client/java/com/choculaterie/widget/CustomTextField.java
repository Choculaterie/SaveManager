package com.choculaterie.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class CustomTextField extends EditBox {
    private static final int FIELD_BG_COLOR = 0xFF2A2A2A;
    private static final int FIELD_BORDER_COLOR = 0xFF555555;
    private static final int FIELD_BORDER_FOCUSED_COLOR = 0xFF888888;
    private static final int CLEAR_BUTTON_SIZE = 12;
    private static final long INITIAL_DELAY = 400;
    private static final long REPEAT_DELAY = 50;

    private final Minecraft client;
    private Runnable onEnterPressed;
    private Runnable onChanged;
    private Runnable onClearPressed;
    private boolean wasEnterDown = false;
    private boolean wasClearButtonMouseDown = false;
    private Component placeholderText;

    private static CustomTextField activeField = null;
    private static boolean callbackInstalled = false;
    private static long installedWindowHandle = 0;

    private final KeyRepeatTracker backspace = new KeyRepeatTracker();
    private final KeyRepeatTracker delete = new KeyRepeatTracker();
    private final KeyRepeatTracker left = new KeyRepeatTracker();
    private final KeyRepeatTracker right = new KeyRepeatTracker();
    private boolean wasHomePressed = false;
    private boolean wasEndPressed = false;
    private boolean wasCtrlCPressed = false;
    private boolean wasCtrlVPressed = false;

    public CustomTextField(Minecraft client, int x, int y, int width, int height, Component text) {
        super(client.font, x, y, width, height, text);
        this.client = client;
        this.setMaxLength(256);
        this.setBordered(false);
        this.setCanLoseFocus(true);
    }

    @Override
    public void insertText(String text) {
    }

    public void setOnEnterPressed(Runnable callback) {
        this.onEnterPressed = callback;
    }

    public void setOnChanged(Runnable callback) {
        this.onChanged = callback;
    }

    public void setOnClearPressed(Runnable callback) {
        this.onClearPressed = callback;
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (focused) {
            activeField = this;
            installCharCallback();
        } else if (activeField == this) {
            activeField = null;
        }
    }

    private void installCharCallback() {
        long wh = getWindowHandle();
        if (wh != 0 && (!callbackInstalled || installedWindowHandle != wh)) {
            GLFW.glfwSetCharCallback(wh, (window, codepoint) -> {
                if (activeField != null && activeField.isFocused()) {
                    activeField.onCharTyped((char) codepoint);
                }
            });
            callbackInstalled = true;
            installedWindowHandle = wh;
        }
    }

    private void onCharTyped(char c) {
        if (c == '\r' || c == '\n' || c < 32)
            return;
        String text = this.getValue();
        int cursor = this.getCursorPosition();
        if (text.length() < 256) {
            this.setValue(text.substring(0, cursor) + c + text.substring(cursor));
            this.setCursorPosition(cursor + 1);
            fireChanged();
        }
    }

    @Override
    public void setHint(Component placeholder) {
        super.setHint(placeholder);
        this.placeholderText = placeholder;
    }

    private boolean isOverClearButton(int mouseX, int mouseY) {
        if (this.getValue().isEmpty())
            return false;
        int clearX = this.getX() + this.getWidth() - CLEAR_BUTTON_SIZE - 4;
        int clearY = this.getY() + (this.getHeight() - CLEAR_BUTTON_SIZE) / 2 + 1;
        return mouseX >= clearX && mouseX < clearX + CLEAR_BUTTON_SIZE &&
                mouseY >= clearY && mouseY < clearY + CLEAR_BUTTON_SIZE;
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        long wh = getWindowHandle();

        if (wh != 0) {
            handleClearButton(wh, mouseX, mouseY);
            handleEnterKey(wh);
        } else {
            wasClearButtonMouseDown = false;
        }

        if (wh != 0 && this.isFocused())
            handleSpecialKeys(wh);

        renderBackground(context);
        renderText(context, mouseX, mouseY);
    }

    private void handleClearButton(long wh, int mouseX, int mouseY) {
        boolean isMouseDown = GLFW.glfwGetMouseButton(wh, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (!this.getValue().isEmpty() && isMouseDown && !wasClearButtonMouseDown
                && isOverClearButton(mouseX, mouseY)) {
            this.setValue("");
            fireChanged();
            if (onClearPressed != null)
                onClearPressed.run();
        }
        wasClearButtonMouseDown = isMouseDown;
    }

    private void handleEnterKey(long wh) {
        boolean isEnterDown = GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_ENTER) == GLFW.GLFW_PRESS ||
                GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_KP_ENTER) == GLFW.GLFW_PRESS;
        if (this.isFocused() && onEnterPressed != null && isEnterDown && !wasEnterDown) {
            onEnterPressed.run();
        }
        wasEnterDown = isEnterDown;
    }

    private void renderBackground(GuiGraphicsExtractor context) {
        int x = this.getX(), y = this.getY(), w = this.getWidth(), h = this.getHeight();
        context.fill(x, y, x + w, y + h, FIELD_BG_COLOR);
        int bc = this.isFocused() ? FIELD_BORDER_FOCUSED_COLOR : FIELD_BORDER_COLOR;
        context.fill(x, y, x + w, y + 1, bc);
        context.fill(x, y + h - 1, x + w, y + h, bc);
        context.fill(x, y, x + 1, y + h, bc);
        context.fill(x + w - 1, y, x + w, y + h, bc);
    }

    private void renderText(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int textY = this.getY() + (this.getHeight() - 8) / 2;
        int textX = this.getX() + 4;
        int maxTextWidth = this.getWidth() - 8 - (this.getValue().isEmpty() ? 0 : CLEAR_BUTTON_SIZE + 4);
        String text = this.getValue();

        if (text.isEmpty() && !this.isFocused()) {
            if (placeholderText != null) {
                context.text(client.font, placeholderText, textX, textY, 0xFF888888);
            }
        } else {
            int color = this.isFocused() ? 0xFFFFFFFF : 0xFFE0E0E0;
            int cursor = this.getCursorPosition();
            context.enableScissor(textX, this.getY(), textX + maxTextWidth, this.getY() + this.getHeight());
            context.text(client.font, text, textX, textY, color);
            context.disableScissor();
            if (this.isFocused() && this.isActive() && (System.currentTimeMillis() / 500) % 2 == 0) {
                int cursorX = textX + client.font.width(text.substring(0, Math.min(cursor, text.length())));
                context.fill(cursorX, textY - 1, cursorX + 1, textY + 9, 0xFFFFFFFF);
            }
        }

        if (!text.isEmpty()) {
            int clearX = this.getX() + this.getWidth() - CLEAR_BUTTON_SIZE - 4;
            int clearY = this.getY() + (this.getHeight() - CLEAR_BUTTON_SIZE) / 2 + 1;
            boolean isHovered = isOverClearButton(mouseX, mouseY);
            String xSymbol = "\u2715";
            int xWidth = client.font.width(xSymbol);
            context.text(client.font, xSymbol,
                    clearX + (CLEAR_BUTTON_SIZE - xWidth) / 2,
                    clearY + (CLEAR_BUTTON_SIZE - 8) / 2,
                    isHovered ? 0xFFAAAAAA : 0xFF888888);
        }
    }

    private void handleSpecialKeys(long wh) {
        long now = System.currentTimeMillis();
        String text = this.getValue();
        int cursor = this.getCursorPosition();

        if (backspace.shouldFire(GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_BACKSPACE) == GLFW.GLFW_PRESS, now, INITIAL_DELAY,
                REPEAT_DELAY)) {
            if (cursor > 0) {
                this.setValue(text.substring(0, cursor - 1) + text.substring(cursor));
                this.setCursorPosition(cursor - 1);
                text = this.getValue();
                cursor = this.getCursorPosition();
                fireChanged();
            }
        }

        if (delete.shouldFire(GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_DELETE) == GLFW.GLFW_PRESS, now, INITIAL_DELAY,
                REPEAT_DELAY)) {
            if (cursor < text.length()) {
                this.setValue(text.substring(0, cursor) + text.substring(cursor + 1));
                text = this.getValue();
                fireChanged();
            }
        }

        if (left.shouldFire(GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_LEFT) == GLFW.GLFW_PRESS, now, INITIAL_DELAY,
                REPEAT_DELAY)) {
            if (cursor > 0)
                this.setCursorPosition(cursor - 1);
        }

        if (right.shouldFire(GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_RIGHT) == GLFW.GLFW_PRESS, now, INITIAL_DELAY,
                REPEAT_DELAY)) {
            if (cursor < text.length())
                this.setCursorPosition(cursor + 1);
        }

        boolean isHomeDown = GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_HOME) == GLFW.GLFW_PRESS;
        if (isHomeDown && !wasHomePressed)
            this.setCursorPosition(0);
        wasHomePressed = isHomeDown;

        boolean isEndDown = GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_END) == GLFW.GLFW_PRESS;
        if (isEndDown && !wasEndPressed)
            this.setCursorPosition(text.length());
        wasEndPressed = isEndDown;

        if (GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS)
            this.setFocused(false);

        boolean isCtrlDown = GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS ||
                GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;

        boolean isCtrlCDown = isCtrlDown && GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_C) == GLFW.GLFW_PRESS;
        if (isCtrlCDown && !wasCtrlCPressed && !text.isEmpty()) {
            GLFW.glfwSetClipboardString(wh, text);
        }
        wasCtrlCPressed = isCtrlCDown;

        boolean isCtrlVDown = isCtrlDown && GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_V) == GLFW.GLFW_PRESS;
        if (isCtrlVDown && !wasCtrlVPressed) {
            String clip = GLFW.glfwGetClipboardString(wh);
            if (clip != null && !clip.isEmpty()) {
                String sanitized = clip.replaceAll("[\\r\\n]+", "");
                if (!sanitized.isEmpty()) {
                    String newText = text.substring(0, cursor) + sanitized + text.substring(cursor);
                    if (newText.length() <= 256) {
                        this.setValue(newText);
                        this.setCursorPosition(cursor + sanitized.length());
                        fireChanged();
                    }
                }
            }
        }
        wasCtrlVPressed = isCtrlVDown;
    }

    private void fireChanged() {
        if (onChanged != null)
            onChanged.run();
    }

    private long getWindowHandle() {
        return client.getWindow() != null ? client.getWindow().handle() : 0;
    }

    private static class KeyRepeatTracker {
        private boolean wasDown;
        private long holdStart;
        private long lastRepeat;

        boolean shouldFire(boolean isDown, long now, long initialDelay, long repeatDelay) {
            if (isDown) {
                if (!wasDown) {
                    wasDown = true;
                    holdStart = now;
                    lastRepeat = now;
                    return true;
                } else if (now - holdStart > initialDelay && now - lastRepeat > repeatDelay) {
                    lastRepeat = now;
                    return true;
                }
            } else {
                wasDown = false;
            }
            return false;
        }
    }
}
