package com.rethinkqaq.flashbackplus;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Flashbackplus implements ModInitializer, ClientModInitializer {

    public static final String MOD_ID = "flashbackplus";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Flashback Plus initializing...");
    }

    @Override
    public void onInitializeClient() {
        LOGGER.info("Flashback Plus client initializing...");
        FlashbackPlusConfig.load();
    }
}
