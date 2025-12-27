package com.choculaterie.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class CustomTextField extends TextFieldWidget {
    private static final int FIELD_BG_COLOR = 0xFF2A2A2A;
    private static final int FIELD_BORDER_COLOR = 0xFF555555;
    private static final int FIELD_BORDER_FOCUSED_COLOR = 0xFF888888;
    private static final int CLEAR_BUTTON_SIZE = 12;
    private static final int CLEAR_BUTTON_COLOR = 0xFF888888;
    private static final int CLEAR_BUTTON_HOVER_COLOR = 0xFFAAAAAA;

    private final MinecraftClient client;
    private Runnable onEnterPressed;
    private Runnable onChanged;
    private boolean wasEnterDown = false;
    private boolean wasClearButtonMouseDown = false;
    private Text placeholderText;
    private Runnable onClearPressed;

    private static CustomTextField activeField = null;
    private static boolean callbackInstalled = false;
    private static long installedWindowHandle = 0;

    private boolean wasBackspacePressed = false;
    private boolean wasDeletePressed = false;
    private boolean wasLeftPressed = false;
    private boolean wasRightPressed = false;
    private boolean wasHomePressed = false;
    private boolean wasEndPressed = false;
    private boolean wasCtrlCPressed = false;
    private boolean wasCtrlVPressed = false;

    private long backspaceHoldStart = 0;
    private long deleteHoldStart = 0;
    private long lastBackspaceRepeat = 0;
    private long lastDeleteRepeat = 0;
    private long leftHoldStart = 0;
    private long rightHoldStart = 0;
    private long lastLeftRepeat = 0;
    private long lastRightRepeat = 0;

    public CustomTextField(MinecraftClient client, int x, int y, int width, int height, Text text) {
        super(client.textRenderer, x, y, width, height, text);
        this.client = client;
        this.setMaxLength(256);
        this.setDrawsBackground(false);
        this.setFocusUnlocked(true);
    }

    @Override
    public void write(String text) {}

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
        long windowHandle = client.getWindow() != null ? client.getWindow().getHandle() : 0;
        if (windowHandle != 0 && (!callbackInstalled || installedWindowHandle != windowHandle)) {
            GLFW.glfwSetCharCallback(windowHandle, (window, codepoint) -> {
                if (activeField != null && activeField.isFocused()) {
                    activeField.onCharTyped((char) codepoint);
                }
            });
            callbackInstalled = true;
            installedWindowHandle = windowHandle;
        }
    }

    private void onCharTyped(char c) {
        if (c == '\r' || c == '\n' || c < 32) return;
        String currentText = this.getText();
        int cursorPos = this.getCursor();
        if (currentText.length() < 256) {
            String newText = currentText.substring(0, cursorPos) + c + currentText.substring(cursorPos);
            this.setText(newText);
            this.setCursor(cursorPos + 1, false);
            if (onChanged != null) onChanged.run();
        }
    }

    @Override
    public void setPlaceholder(Text placeholder) {
        super.setPlaceholder(placeholder);
        this.placeholderText = placeholder;
    }

    private boolean isOverClearButton(int mouseX, int mouseY) {
        if (this.getText().isEmpty()) return false;
        int clearX = this.getX() + this.getWidth() - CLEAR_BUTTON_SIZE - 4;
        int clearY = this.getY() + (this.getHeight() - CLEAR_BUTTON_SIZE) / 2 + 1;
        return mouseX >= clearX && mouseX < clearX + CLEAR_BUTTON_SIZE &&
                mouseY >= clearY && mouseY < clearY + CLEAR_BUTTON_SIZE;
    }

    @Override
    public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        long windowHandle = client.getWindow() != null ? client.getWindow().getHandle() : 0;

        if (windowHandle != 0) {
            boolean isMouseDown = GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            if (!this.getText().isEmpty() && isMouseDown && !wasClearButtonMouseDown && isOverClearButton(mouseX, mouseY)) {
                this.setText("");
                if (onChanged != null) onChanged.run();
                if (onClearPressed != null) onClearPressed.run();
            }
            wasClearButtonMouseDown = isMouseDown;

            boolean isEnterDown = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_ENTER) == GLFW.GLFW_PRESS ||
                    GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_KP_ENTER) == GLFW.GLFW_PRESS;
            if (this.isFocused() && onEnterPressed != null && isEnterDown && !wasEnterDown) {
                wasEnterDown = true;
                onEnterPressed.run();
            } else if (!isEnterDown) {
                wasEnterDown = false;
            }
        } else {
            wasClearButtonMouseDown = false;
        }

        if (windowHandle != 0 && this.isFocused()) {
            handleSpecialKeys(windowHandle);
        }

        context.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), FIELD_BG_COLOR);
        int borderColor = this.isFocused() ? FIELD_BORDER_FOCUSED_COLOR : FIELD_BORDER_COLOR;
        context.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + 1, borderColor);
        context.fill(this.getX(), this.getY() + this.getHeight() - 1, this.getX() + this.getWidth(), this.getY() + this.getHeight(), borderColor);
        context.fill(this.getX(), this.getY(), this.getX() + 1, this.getY() + this.getHeight(), borderColor);
        context.fill(this.getX() + this.getWidth() - 1, this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), borderColor);

        int textY = this.getY() + (this.getHeight() - 8) / 2;
        int textX = this.getX() + 4;
        int maxTextWidth = this.getWidth() - 8 - (this.getText().isEmpty() ? 0 : CLEAR_BUTTON_SIZE + 4);
        String text = this.getText();

        if (text.isEmpty() && !this.isFocused()) {
            if (placeholderText != null) {
                context.drawTextWithShadow(client.textRenderer, placeholderText, textX, textY, 0xFF888888);
            }
        } else {
            int color = this.isFocused() ? 0xFFFFFFFF : 0xFFE0E0E0;
            int cursorPos = this.getCursor();
            String beforeCursor = text.substring(0, Math.min(cursorPos, text.length()));
            context.enableScissor(textX, this.getY(), textX + maxTextWidth, this.getY() + this.getHeight());
            context.drawTextWithShadow(client.textRenderer, text, textX, textY, color);
            context.disableScissor();
            if (this.isFocused() && this.isActive() && (System.currentTimeMillis() / 500) % 2 == 0) {
                int cursorX = textX + client.textRenderer.getWidth(beforeCursor);
                context.fill(cursorX, textY - 1, cursorX + 1, textY + 9, 0xFFFFFFFF);
            }
        }

        if (!this.getText().isEmpty()) {
            int clearX = this.getX() + this.getWidth() - CLEAR_BUTTON_SIZE - 4;
            int clearY = this.getY() + (this.getHeight() - CLEAR_BUTTON_SIZE) / 2 + 1;
            boolean isHovered = isOverClearButton(mouseX, mouseY);
            int clearColor = isHovered ? CLEAR_BUTTON_HOVER_COLOR : CLEAR_BUTTON_COLOR;
            String xSymbol = "✕";
            int xWidth = client.textRenderer.getWidth(xSymbol);
            int xX = clearX + (CLEAR_BUTTON_SIZE - xWidth) / 2;
            int xY = clearY + (CLEAR_BUTTON_SIZE - 8) / 2;
            context.drawTextWithShadow(client.textRenderer, xSymbol, xX, xY, clearColor);
        }
    }

    private void handleSpecialKeys(long windowHandle) {
        long currentTime = System.currentTimeMillis();
        long initialDelay = 400;
        long repeatDelay = 50;
        String currentText = this.getText();
        int cursorPos = this.getCursor();

        boolean isBackspaceDown = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_BACKSPACE) == GLFW.GLFW_PRESS;
        if (isBackspaceDown) {
            boolean shouldDelete = false;
            if (!wasBackspacePressed) {
                shouldDelete = true;
                backspaceHoldStart = currentTime;
                lastBackspaceRepeat = currentTime;
            } else if (currentTime - backspaceHoldStart > initialDelay && currentTime - lastBackspaceRepeat > repeatDelay) {
                shouldDelete = true;
                lastBackspaceRepeat = currentTime;
            }
            if (shouldDelete && cursorPos > 0) {
                String newText = currentText.substring(0, cursorPos - 1) + currentText.substring(cursorPos);
                this.setText(newText);
                this.setCursor(cursorPos - 1, false);
                currentText = newText;
                cursorPos = cursorPos - 1;
                if (onChanged != null) onChanged.run();
            }
        }
        wasBackspacePressed = isBackspaceDown;

        boolean isDeleteDown = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_DELETE) == GLFW.GLFW_PRESS;
        if (isDeleteDown) {
            boolean shouldDelete = false;
            if (!wasDeletePressed) {
                shouldDelete = true;
                deleteHoldStart = currentTime;
                lastDeleteRepeat = currentTime;
            } else if (currentTime - deleteHoldStart > initialDelay && currentTime - lastDeleteRepeat > repeatDelay) {
                shouldDelete = true;
                lastDeleteRepeat = currentTime;
            }
            if (shouldDelete && cursorPos < currentText.length()) {
                String newText = currentText.substring(0, cursorPos) + currentText.substring(cursorPos + 1);
                this.setText(newText);
                currentText = newText;
                if (onChanged != null) onChanged.run();
            }
        }
        wasDeletePressed = isDeleteDown;

        boolean isLeftDown = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT) == GLFW.GLFW_PRESS;
        if (isLeftDown) {
            boolean shouldMove = false;
            if (!wasLeftPressed) {
                shouldMove = true;
                leftHoldStart = currentTime;
                lastLeftRepeat = currentTime;
            } else if (currentTime - leftHoldStart > initialDelay && currentTime - lastLeftRepeat > repeatDelay) {
                shouldMove = true;
                lastLeftRepeat = currentTime;
            }
            if (shouldMove && cursorPos > 0) this.setCursor(cursorPos - 1, false);
        }
        wasLeftPressed = isLeftDown;

        boolean isRightDown = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT) == GLFW.GLFW_PRESS;
        if (isRightDown) {
            boolean shouldMove = false;
            if (!wasRightPressed) {
                shouldMove = true;
                rightHoldStart = currentTime;
                lastRightRepeat = currentTime;
            } else if (currentTime - rightHoldStart > initialDelay && currentTime - lastRightRepeat > repeatDelay) {
                shouldMove = true;
                lastRightRepeat = currentTime;
            }
            if (shouldMove && cursorPos < currentText.length()) this.setCursor(cursorPos + 1, false);
        }
        wasRightPressed = isRightDown;

        boolean isHomeDown = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_HOME) == GLFW.GLFW_PRESS;
        if (isHomeDown && !wasHomePressed) this.setCursor(0, false);
        wasHomePressed = isHomeDown;

        boolean isEndDown = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_END) == GLFW.GLFW_PRESS;
        if (isEndDown && !wasEndPressed) this.setCursor(currentText.length(), false);
        wasEndPressed = isEndDown;

        boolean isEscapeDown = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS;
        if (isEscapeDown) this.setFocused(false);

        boolean isCtrlDown = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS ||
                GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;

        boolean isCtrlCDown = isCtrlDown && GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_C) == GLFW.GLFW_PRESS;
        if (isCtrlCDown && !wasCtrlCPressed) {
            String textToCopy = this.getText();
            if (!textToCopy.isEmpty()) {
                GLFW.glfwSetClipboardString(windowHandle, textToCopy);
            }
        }
        wasCtrlCPressed = isCtrlCDown;

        boolean isCtrlVDown = isCtrlDown && GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_V) == GLFW.GLFW_PRESS;
        if (isCtrlVDown && !wasCtrlVPressed) {
            String clipboardText = GLFW.glfwGetClipboardString(windowHandle);
            if (clipboardText != null && !clipboardText.isEmpty()) {
                String sanitized = clipboardText.replaceAll("[\\r\\n]+", "");
                if (!sanitized.isEmpty()) {
                    String newText = currentText.substring(0, cursorPos) + sanitized + currentText.substring(cursorPos);
                    if (newText.length() <= 256) {
                        this.setText(newText);
                        this.setCursor(cursorPos + sanitized.length(), false);
                        if (onChanged != null) onChanged.run();
                    }
                }
            }
        }
        wasCtrlVPressed = isCtrlVDown;
    }
}
