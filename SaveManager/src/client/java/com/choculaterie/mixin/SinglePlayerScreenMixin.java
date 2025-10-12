package com.choculaterie.mixin;

import com.choculaterie.SaveManagerMod;
import com.choculaterie.gui.AccountLinkingScreen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SelectWorldScreen.class)
public abstract class SinglePlayerScreenMixin extends Screen {
    protected SinglePlayerScreenMixin(Text title) {
        super(title);
    }

    // Anchor: base game's search bar
    @Shadow private TextFieldWidget searchBox;

    @Unique private ButtonWidget saveManagerLinkBtn;
    @Unique private static final int SAVE_BTN_SIZE = 20;
    @Unique private static final int SAVE_BTN_GAP = 4;

    // Add and place the button after the screen builds its widgets
    @Inject(method = "init", at = @At("RETURN"))
    private void savemanager$addLinkAccountButton(CallbackInfo ci) {
        this.saveManagerLinkBtn = ButtonWidget.builder(
                Text.literal("\uD83D\uDCBE"), // floppy disk icon
                button -> {
                    SaveManagerMod.LOGGER.info("Opening account link screen");
                    this.client.setScreen(new AccountLinkingScreen((Screen) (Object) this));
                }
        ).dimensions(0, 0, SAVE_BTN_SIZE, SAVE_BTN_SIZE).build();

        this.addDrawableChild(this.saveManagerLinkBtn);

        // Re-anchor every frame so it always follows the search bar (no mixin into render/resize needed)
        this.addDrawable((Drawable) (context, mouseX, mouseY, delta) -> savemanager$repositionLinkButton());

        savemanager$repositionLinkButton(); // initial placement
    }

    @Unique
    private void savemanager$repositionLinkButton() {
        if (this.saveManagerLinkBtn == null) return;

        if (this.searchBox != null) {
            int x = this.searchBox.getX() - SAVE_BTN_SIZE - SAVE_BTN_GAP;
            int y = this.searchBox.getY() + (this.searchBox.getHeight() - this.saveManagerLinkBtn.getHeight()) / 2;
            this.saveManagerLinkBtn.setX(x);
            this.saveManagerLinkBtn.setY(y);
        } else {
            // Fallback near where the search bar typically renders
            int x = this.width / 2 - 210;
            int y = 32;
            this.saveManagerLinkBtn.setX(x);
            this.saveManagerLinkBtn.setY(y);
        }

        this.saveManagerLinkBtn.visible = true;
        this.saveManagerLinkBtn.active = true;
    }
}
