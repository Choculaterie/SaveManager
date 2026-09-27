package com.choculaterie.gui;

import com.choculaterie.vanilib.gui.theme.UITheme;
import com.choculaterie.vanilib.gui.widget.CustomButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public class VersionPickerPopup<T> implements Renderable {

    private static final int POPUP_WIDTH = 300;
    private static final int ROW_GAP = 4;

    private final String title;
    private final List<T> items;
    private final Consumer<T> onSelected;
    private final Runnable onCancel;
    private final List<CustomButton> buttons = new ArrayList<>();
    private final CustomButton cancelButton;

    private final int x;
    private final int y;
    private final int popupHeight;

    public VersionPickerPopup(String title, List<T> items, Function<T, String> labelFn,
            Consumer<T> onSelected, Runnable onCancel) {
        this.title = title;
        this.items = items;
        this.onSelected = onSelected;
        this.onCancel = onCancel;

        Minecraft client = Minecraft.getInstance();
        int screenWidth = client.getWindow().getGuiScaledWidth();
        int screenHeight = client.getWindow().getGuiScaledHeight();

        int pad = UITheme.Dimensions.PADDING;
        int rowH = UITheme.Dimensions.BUTTON_HEIGHT;
        int rows = items.size();

        this.popupHeight = pad + UITheme.Typography.LINE_HEIGHT + pad
                + rows * rowH + Math.max(0, rows - 1) * ROW_GAP
                + pad + rowH + pad;
        this.x = (screenWidth - POPUP_WIDTH) / 2;
        this.y = (screenHeight - popupHeight) / 2;

        int btnX = x + pad;
        int btnW = POPUP_WIDTH - pad * 2;
        int rowY = y + pad + UITheme.Typography.LINE_HEIGHT + pad;
        for (T item : items) {
            T captured = item;
            buttons.add(new CustomButton(btnX, rowY, btnW, rowH,
                    Component.literal(labelFn.apply(item)), b -> onSelected.accept(captured)));
            rowY += rowH + ROW_GAP;
        }

        cancelButton = new CustomButton(btnX, y + popupHeight - pad - rowH, btnW, rowH,
                Component.literal("Cancel"), b -> onCancel.run());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        Minecraft client = Minecraft.getInstance();
        context.fill(0, 0, client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight(),
                UITheme.Colors.OVERLAY_BG);

        context.fill(x, y, x + POPUP_WIDTH, y + popupHeight, UITheme.Colors.BUTTON_BG_DISABLED);
        int bw = UITheme.Dimensions.BORDER_WIDTH;
        context.fill(x, y, x + POPUP_WIDTH, y + bw, UITheme.Colors.BUTTON_BORDER);
        context.fill(x, y + popupHeight - bw, x + POPUP_WIDTH, y + popupHeight, UITheme.Colors.BUTTON_BORDER);
        context.fill(x, y, x + bw, y + popupHeight, UITheme.Colors.BUTTON_BORDER);
        context.fill(x + POPUP_WIDTH - bw, y, x + POPUP_WIDTH, y + popupHeight, UITheme.Colors.BUTTON_BORDER);

        context.centeredText(client.font, Component.literal(title),
                x + POPUP_WIDTH / 2, y + UITheme.Dimensions.PADDING, UITheme.Colors.TEXT_PRIMARY);

        for (CustomButton b : buttons)
            b.extractRenderState(context, mouseX, mouseY, delta);
        cancelButton.extractRenderState(context, mouseX, mouseY, delta);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseX < x || mouseX > x + POPUP_WIDTH || mouseY < y || mouseY > y + popupHeight) {
            onCancel.run();
            return true;
        }
        for (int i = 0; i < buttons.size(); i++) {
            if (isOver(buttons.get(i), mouseX, mouseY)) {
                onSelected.accept(items.get(i));
                return true;
            }
        }
        if (isOver(cancelButton, mouseX, mouseY))
            onCancel.run();
        return true;
    }

    private static boolean isOver(CustomButton b, double mouseX, double mouseY) {
        return mouseX >= b.getX() && mouseX < b.getX() + b.getWidth()
                && mouseY >= b.getY() && mouseY < b.getY() + b.getHeight();
    }

    public int size() {
        return items.size();
    }
}
