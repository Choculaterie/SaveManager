package com.choculaterie.widget;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import org.lwjgl.glfw.GLFW;

public class ScrollBar implements Drawable {
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int SCROLLBAR_COLOR = 0xFF555555;
    private static final int SCROLLBAR_HANDLE_COLOR = 0xFF888888;
    private static final int SCROLLBAR_HANDLE_HOVER_COLOR = 0xFFAAAAAA;

    private final int x;
    private final int y;
    private final int height;
    private double scrollPercentage = 0.0;
    private double contentHeight = 0.0;
    private double visibleHeight = 0.0;
    private boolean isDragging = false;
    private double dragStartY = 0;
    private double dragStartScroll = 0;
    private boolean isHovered = false;
    private boolean wasMouseDown = false;

    public ScrollBar(int x, int y, int height) {
        this.x = x;
        this.y = y;
        this.height = height;
    }

    public void setScrollData(double contentHeight, double visibleHeight) {
        this.contentHeight = contentHeight;
        this.visibleHeight = visibleHeight;
    }

    public void setScrollPercentage(double percentage) {
        this.scrollPercentage = Math.max(0.0, Math.min(1.0, percentage));
    }

    public double getScrollPercentage() {
        return scrollPercentage;
    }

    public boolean isVisible() {
        return contentHeight > visibleHeight;
    }

    public boolean isDragging() {
        return isDragging;
    }

    public boolean updateAndRender(DrawContext context, int mouseX, int mouseY, float delta, long windowHandle) {
        if (!isVisible()) return false;

        boolean isMouseDown = GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        double handleHeight = Math.max(20, (visibleHeight / contentHeight) * height);
        double maxHandleY = height - handleHeight;
        double handleY = y + (scrollPercentage * maxHandleY);

        isHovered = mouseX >= x && mouseX < x + SCROLLBAR_WIDTH &&
                mouseY >= handleY && mouseY < handleY + handleHeight;
        boolean isOverTrack = mouseX >= x && mouseX < x + SCROLLBAR_WIDTH &&
                mouseY >= y && mouseY < y + height;

        boolean scrollChanged = false;

        if (isMouseDown && !wasMouseDown) {
            if (isHovered) {
                isDragging = true;
                dragStartY = mouseY;
                dragStartScroll = scrollPercentage;
            } else if (isOverTrack) {
                double clickPositionInTrack = mouseY - y - (handleHeight / 2);
                double newPercentage = Math.max(0.0, Math.min(1.0, clickPositionInTrack / maxHandleY));
                if (newPercentage != scrollPercentage) {
                    scrollPercentage = newPercentage;
                    scrollChanged = true;
                }
                isDragging = true;
                dragStartY = mouseY;
                dragStartScroll = scrollPercentage;
            }
        }

        if (isDragging && isMouseDown) {
            double deltaYMouse = mouseY - dragStartY;
            double deltaScroll = deltaYMouse / maxHandleY;
            double newPercentage = Math.max(0.0, Math.min(1.0, dragStartScroll + deltaScroll));
            if (newPercentage != scrollPercentage) {
                scrollPercentage = newPercentage;
                scrollChanged = true;
            }
        }

        if (!isMouseDown && isDragging) {
            isDragging = false;
        }

        wasMouseDown = isMouseDown;

        context.fill(x, y, x + SCROLLBAR_WIDTH, y + height, SCROLLBAR_COLOR);
        handleY = y + (scrollPercentage * maxHandleY);
        int handleColor = (isHovered || isDragging) ? SCROLLBAR_HANDLE_HOVER_COLOR : SCROLLBAR_HANDLE_COLOR;
        context.fill(x, (int) handleY, x + SCROLLBAR_WIDTH, (int) (handleY + handleHeight), handleColor);

        return scrollChanged;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (!isVisible()) return;
        context.fill(x, y, x + SCROLLBAR_WIDTH, y + height, SCROLLBAR_COLOR);
        double handleHeight = Math.max(20, (visibleHeight / contentHeight) * height);
        double maxHandleY = height - handleHeight;
        double handleY = y + (scrollPercentage * maxHandleY);
        isHovered = mouseX >= x && mouseX < x + SCROLLBAR_WIDTH &&
                mouseY >= handleY && mouseY < handleY + handleHeight;
        int handleColor = (isHovered || isDragging) ? SCROLLBAR_HANDLE_HOVER_COLOR : SCROLLBAR_HANDLE_COLOR;
        context.fill(x, (int) handleY, x + SCROLLBAR_WIDTH, (int) (handleY + handleHeight), handleColor);
    }

    public int getWidth() {
        return SCROLLBAR_WIDTH;
    }
}
