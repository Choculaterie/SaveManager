package com.choculaterie.mixin;

import com.choculaterie.SaveManagerMod;
import com.choculaterie.gui.AccountLinkingScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SelectWorldScreen.class)
public abstract class SinglePlayerScreenMixin extends Screen {
    // Required constructor when extending Screen
    protected SinglePlayerScreenMixin(Text title) {
        super(title);
    }

    // Shadow the search bar so we can anchor relative to it
    @Shadow private TextFieldWidget searchBox;

    @Inject(at = @At("RETURN"), method = "init")
    private void addLinkAccountButton(CallbackInfo ci) {
        final int size = 20;
        final int gap = 4;

        int x;
        int y;

        if (this.searchBox != null) {
            // Place to the immediate left of the search bar, aligned vertically
            x = this.searchBox.getX() - size - gap;
            y = this.searchBox.getY();
        } else {
            // Fallback position near where the search bar usually sits
            x = this.width / 2 - 210;
            y = 32;
        }

        ButtonWidget linkIconButton = ButtonWidget.builder(
                Text.literal("\uD83D\uDCBE"), // floppy disk icon
                button -> {
                    SaveManagerMod.LOGGER.info("Opening account link screen");
                    this.client.setScreen(new AccountLinkingScreen((Screen)(Object)this));
                }
        ).dimensions(x, y, size, size).build();

        this.addDrawableChild(linkIconButton);
    }
}