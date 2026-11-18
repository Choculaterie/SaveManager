package com.choculaterie.mixin;

import com.choculaterie.gui.CloudUpdateScreen;
import com.choculaterie.sync.CloudPull;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldOpenFlows.class)
public class WorldOpenFlowsMixin {

    @Inject(method = "openWorld(Ljava/lang/String;Ljava/lang/Runnable;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void savemanager$checkCloudFirst(String levelId, Runnable onCancel, CallbackInfo ci) {
        if (!CloudPull.shouldCheck(levelId))
            return;
        ci.cancel();
        Minecraft.getInstance().gui.setScreen(new CloudUpdateScreen(levelId, onCancel));
    }
}
