package com.choculaterie;

import net.fabricmc.api.ClientModInitializer;

public class SaveManagerClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        SaveManagerMod.LOGGER.info("Initializing Save Manager Client");
    }
}
