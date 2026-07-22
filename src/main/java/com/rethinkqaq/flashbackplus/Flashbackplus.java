package com.rethinkqaq.flashbackplus;

import com.rethinkqaq.flashbackplus.exporting.HdrExportState;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
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

        // Detect HDR Mod presence
        if (FabricLoader.getInstance().isModLoaded("hdr_mod")) {
            HdrExportState.setHdrModLoaded(true);
            LOGGER.info("HDR Mod detected — HDR export will be available");

            // Check if HDR is currently enabled via Cloth Config AutoConfig
            try {
                Class<?> autoConfigClass = Class.forName("me.shedaniel.autoconfig.AutoConfig");
                Class<?> hdrModConfigClass = Class.forName("xyz.rrtt217.HDRMod.config.HDRModConfig");
                Object configHolder = autoConfigClass
                        .getMethod("getConfigHolder", Class.class)
                        .invoke(null, hdrModConfigClass);
                Object config = configHolder.getClass().getMethod("getConfig").invoke(configHolder);
                boolean hdrEnabled = (boolean) hdrModConfigClass.getField("enableHDR").get(config);
                HdrExportState.setHdrModEnabled(hdrEnabled);
                LOGGER.info("HDR Mod enabled: {}", hdrEnabled);
            } catch (Exception e) {
                LOGGER.warn("Could not read HDR Mod config via AutoConfig — trying direct field access", e);
                // Fallback: HDR Mod stores config in HDRMod.configHolder
                try {
                    Class<?> hdrModClass = Class.forName("xyz.rrtt217.HDRMod.HDRMod");
                    Object configHolder = hdrModClass.getField("configHolder").get(null);
                    Object config = configHolder.getClass().getMethod("getConfig").invoke(configHolder);
                    Class<?> hdrModConfigClass = Class.forName("xyz.rrtt217.HDRMod.config.HDRModConfig");
                    boolean hdrEnabled = (boolean) hdrModConfigClass.getField("enableHDR").get(config);
                    HdrExportState.setHdrModEnabled(hdrEnabled);
                    LOGGER.info("HDR Mod enabled (fallback): {}", hdrEnabled);
                } catch (Exception e2) {
                    LOGGER.warn("Could not read HDR Mod config — HDR export unavailable", e2);
                    HdrExportState.setHdrModEnabled(false);
                }
            }
        } else {
            LOGGER.info("HDR Mod not detected — HDR export unavailable");
        }
    }
}
