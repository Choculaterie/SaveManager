package com.choculaterie;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SaveManagerMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("savemanager");

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Save Manager Mod");
        // Your initialization code will go here
    }
}
