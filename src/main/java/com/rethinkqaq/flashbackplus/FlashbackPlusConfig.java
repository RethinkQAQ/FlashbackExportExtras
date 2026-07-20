package com.rethinkqaq.flashbackplus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FlashbackPlusConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("flashbackplus.json");

    public static FlashbackPlusConfig INSTANCE = new FlashbackPlusConfig();

    /** True = export as OpenEXR sequence. */
    public boolean exportAsExr = false;

    /** True = linearize depth from NDC [0,1] to world-space distance. */
    public boolean depthLinearizeWorldSpace = true;

    /** True = export camera path as GLB alongside any video export. */
    public boolean exportCameraPath = true;

    /** True = offset camera path to start at origin. */
    public boolean cameraPathRelativeOrigin = true;

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH);
                INSTANCE = GSON.fromJson(json, FlashbackPlusConfig.class);
                if (INSTANCE == null) INSTANCE = new FlashbackPlusConfig();
            } catch (IOException e) {
                Flashbackplus.LOGGER.error("Failed to load config", e);
                INSTANCE = new FlashbackPlusConfig();
            }
        }
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(INSTANCE));
        } catch (IOException e) {
            Flashbackplus.LOGGER.error("Failed to save config", e);
        }
    }
}
