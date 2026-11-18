package com.choculaterie.mixin;

import com.choculaterie.gui.CloudButton;
import com.choculaterie.gui.SaveManagerScreen;
import com.choculaterie.vanilib.gui.theme.UITheme;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin extends Screen {
    @Unique
    private CloudButton savemanager$cloud;

    protected CreateWorldScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"), remap = false)
    private void savemanager$init(CallbackInfo ci) {
        if (savemanager$hasWorlds())
            return;
        savemanager$cloud = addRenderableWidget(new CloudButton(6, 6, 20, 20,
                b -> this.minecraft.gui.setScreen(new SaveManagerScreen(new SelectWorldScreen(new TitleScreen())))));
        savemanager$place();
    }

    @Inject(method = "repositionElements", at = @At("TAIL"), remap = false)
    private void savemanager$reposition(CallbackInfo ci) {
        savemanager$place();
    }

    @Unique
    private void savemanager$place() {
        if (savemanager$cloud == null)
            return;
        Button create = null;
        for (var child : children()) {
            if (child instanceof Button b && b.getMessage().getContents() instanceof TranslatableContents t
                    && "selectWorld.create".equals(t.getKey())) {
                create = b;
                break;
            }
        }
        int x = create == null ? -1 : create.getX() - savemanager$cloud.getWidth() - UITheme.Dimensions.BUTTON_GAP;
        if (create == null || x < UITheme.Dimensions.PADDING_SMALL)
            savemanager$cloud.setPosition(6, 6);
        else
            savemanager$cloud.setPosition(x, create.getY());
    }

    @Unique
    private boolean savemanager$hasWorlds() {
        Path saves = this.minecraft.gameDirectory.toPath().resolve("saves");
        if (!Files.isDirectory(saves))
            return false;
        try (Stream<Path> dirs = Files.list(saves)) {
            return dirs.anyMatch(d -> Files.isRegularFile(d.resolve("level.dat")));
        } catch (IOException e) {
            return true;
        }
    }
}
