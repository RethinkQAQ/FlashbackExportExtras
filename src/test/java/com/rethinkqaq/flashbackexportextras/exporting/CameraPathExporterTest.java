/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package com.rethinkqaq.flashbackexportextras.exporting;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraPathExporterTest {

    @TempDir
    Path directory;

    @Test
    void jsonPreservesLargeWorldPositionPrecision() throws Exception {
        CameraPathExporter exporter = new CameraPathExporter(16.0f / 9.0f, 60.0, false);
        exporter.recordFrame(new Vec3(30_000_000.125, 80.25, -29_999_999.875), 15.0f, -5.0f, 42.5f);

        Path output = directory.resolve("camera.json");
        exporter.finish(output, CameraPathExporter.Format.JSON);

        JsonObject frame = JsonParser.parseString(Files.readString(output)).getAsJsonObject()
                .getAsJsonArray("frames").get(0).getAsJsonObject();
        assertEquals(-30_000_000.125,
                frame.getAsJsonArray("position").get(0).getAsDouble(), 0.000_001);
        assertEquals(42.5, frame.get("verticalFovDegrees").getAsDouble(), 0.000_001);
    }

    @Test
    void fusionImporterUsesVerticalAovAndCompositionTimeline() throws Exception {
        CameraPathExporter exporter = sampleExporter();
        Path output = directory.resolve("camera.lua");
        exporter.finish(output, CameraPathExporter.Format.FUSION_LUA);

        String script = Files.readString(output);
        assertTrue(script.contains("bmd.scriptapp(\"Fusion\")"));
        assertTrue(script.contains("camera.AovType = 0"));
        assertTrue(script.contains("camera.AoV[t]"));
        assertTrue(script.contains("frameScale = compFps / exportFps"));
        assertFalse(script.contains("AngleofView"));
    }

    @Test
    void publishesCompletedFileWithoutLeavingTemporarySibling() throws Exception {
        CameraPathExporter exporter = sampleExporter();
        Path output = directory.resolve("camera.usda");
        exporter.finish(output, CameraPathExporter.Format.USDA);

        assertTrue(Files.isRegularFile(output));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void usdaDeclaresMinecraftBlocksAsMetres() throws Exception {
        CameraPathExporter exporter = sampleExporter();
        Path output = directory.resolve("camera.usda");
        exporter.finish(output, CameraPathExporter.Format.USDA);

        String usda = Files.readString(output);
        assertTrue(usda.contains("metersPerUnit = 1.0"));
    }

    private static CameraPathExporter sampleExporter() {
        CameraPathExporter exporter = new CameraPathExporter(16.0f / 9.0f, 60.0, true);
        exporter.recordFrame(new Vec3(10.0, 70.0, 20.0), 0.0f, 0.0f, 70.0f);
        exporter.recordFrame(new Vec3(11.0, 70.5, 21.0), 10.0f, -2.0f, 70.0f);
        return exporter;
    }
}
