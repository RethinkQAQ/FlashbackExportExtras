/*
 * Flashback Export Extras
 * Copyright (C) RethinkQAQ
 *
 * This file is part of Flashback Export Extras.
 *
 * Flashback Export Extras is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Flashback Export Extras is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with Flashback Export Extras. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package com.rethinkqaq.flashbackexportextras;

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

    /** True = preserve scene-linear Rec.709 HDR color in OpenEXR output. */
    public boolean exrSceneLinearHdr = false;

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
