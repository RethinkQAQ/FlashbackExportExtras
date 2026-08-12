package com.rethinkqaq.flashbackplus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FlashbackPlusConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("flashbackplus.json");

    public static FlashbackPlusConfig INSTANCE = new FlashbackPlusConfig();

    /** Mutually exclusive export destination selected by the export UI. */
    public ExportMode exportMode = ExportMode.VIDEO;

    /** True = linearize depth from NDC [0,1] to world-space distance. */
    public boolean depthLinearizeWorldSpace = true;

    /** True = export camera path as GLB alongside any video export. */
    public boolean exportCameraPath = true;

    /** True = offset camera path to start at origin. */
    public boolean cameraPathRelativeOrigin = true;

    /** Peak brightness in nits for PQ encoding (default 1000). */
    public int hdrPeakBrightness = 1000;

    /** Paper white brightness in nits (SDR reference level, default 203). */
    public int hdrPaperWhiteNits = 203;

    public enum ExportMode {
        VIDEO,
        EXR,
        HDR10
    }

    public ExportMode getExportMode() {
        return exportMode == null ? ExportMode.VIDEO : exportMode;
    }

    public void setExportMode(ExportMode mode) {
        exportMode = mode == null ? ExportMode.VIDEO : mode;
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH);
                JsonObject object = JsonParser.parseString(json).getAsJsonObject();
                INSTANCE = GSON.fromJson(object, FlashbackPlusConfig.class);
                if (INSTANCE == null) INSTANCE = new FlashbackPlusConfig();
                // Migrate the two old, independently persisted mode flags.
                // HDR10 keeps precedence to preserve the behaviour of old configs.
                if (!object.has("exportMode")) {
                    if (object.has("hdrExport") && object.get("hdrExport").getAsBoolean()) {
                        INSTANCE.setExportMode(ExportMode.HDR10);
                    } else if (object.has("exportAsExr") && object.get("exportAsExr").getAsBoolean()) {
                        INSTANCE.setExportMode(ExportMode.EXR);
                    }
                }
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
