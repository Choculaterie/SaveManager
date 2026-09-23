package com.choculaterie.mixin;

import com.choculaterie.sync.AutoSync;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {

    @Inject(method = "saveEverything", at = @At("TAIL"), require = 0)
    private void saveManager$afterSave(boolean suppressLogs, boolean flush, boolean force,
            CallbackInfoReturnable<Boolean> cir) {
        try {
            MinecraftServer server = (MinecraftServer) (Object) this;
            AutoSync.onWorldSaved(server.getWorldPath(LevelResource.ROOT));
        } catch (Throwable ignored) {
        }
    }
}
