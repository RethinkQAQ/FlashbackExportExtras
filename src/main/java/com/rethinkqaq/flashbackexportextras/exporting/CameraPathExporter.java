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
package com.rethinkqaq.flashbackexportextras.exporting;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Exports camera path animation as a GLB (Binary glTF 2.0) file for Blender.
 *
 * Records per-frame: position, rotation (yaw/pitch → quaternion), FOV.
 * Supports optional relative origin (first frame → 0,0,0).
 * Writes animation channels for translation, rotation, and yfov (KHR_animation_pointer).
 */
public class CameraPathExporter {

    /**
     * Camera path formats exported from the same sampled Minecraft camera path.
     * GLB remains the default so existing configuration files retain their output.
     */
    public enum Format {
        GLB("camera", "glb"),
        USDA("camera", "usda"),
        JSON("camera", "json"),
        AFTER_EFFECTS_JSX("camera", "jsx"),
        FUSION_LUA("camera_fusion", "lua");

        private final String fileStem;
        private final String extension;

        Format(String fileStem, String extension) {
            this.fileStem = fileStem;
            this.extension = extension;
        }

        public String fileStem() {
            return fileStem;
        }

        public String fileName() {
            return fileStem + "." + extension;
        }

        public String extension() {
            return extension;
        }
    }

    private final float aspectRatio;
    private final double framerate;
    private final boolean relativeOrigin;

    private final List<Double> times = new ArrayList<>();
    private final List<Vec3> positions = new ArrayList<>();
    private final List<Float> yaws = new ArrayList<>();
    private final List<Float> pitches = new ArrayList<>();
    private final List<Float> fovs = new ArrayList<>();

    private int frameCount = 0;
    private double timeAccum = 0.0;

    public CameraPathExporter(float aspectRatio, double framerate, boolean relativeOrigin) {
        this.aspectRatio = aspectRatio;
        this.framerate = framerate;
        this.relativeOrigin = relativeOrigin;
    }

    /** Records one frame. Time is auto-incremented by 1/framerate. */
    public void recordFrame(Vec3 position, float yawDegrees, float pitchDegrees, float fovDegrees) {
        times.add(timeAccum);
        positions.add(position);
        yaws.add(yawDegrees);
        pitches.add(pitchDegrees);
        fovs.add(fovDegrees);
        frameCount++;
        timeAccum += 1.0 / framerate;
    }

    public int getFrameCount() {
        return frameCount;
    }

    private void writeGlb(Path outputPath) throws IOException {
        if (frameCount == 0) return;

        Files.createDirectories(outputPath.getParent());

        // Compute origin offset if relative
        Vec3 origin = relativeOrigin ? positions.get(0) : Vec3.ZERO;

        // BufferView indices
        final int BVI_TIME = 0, BVI_TRANS = 1, BVI_ROT = 2, BVI_FOV = 3;

        ByteBuffer buf = ByteBuffer.allocate(frameCount * 36 + 256).order(ByteOrder.LITTLE_ENDIAN);

        // --- Time ---
        int offTime = align4(buf.position());
        float[] timeArr = new float[frameCount];
        float minT = Float.MAX_VALUE, maxT = -Float.MAX_VALUE;
        for (int i = 0; i < frameCount; i++) {
            timeArr[i] = (float) (double) times.get(i);
            if (timeArr[i] < minT) minT = timeArr[i];
            if (timeArr[i] > maxT) maxT = timeArr[i];
        }
        for (float t : timeArr) buf.putFloat(t);

        // --- Translation (MC→glTF, optionally relative) ---
        int offTrans = align4(buf.position());
        float[] trans = new float[frameCount * 3];
        float[] tMin = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] tMax = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < frameCount; i++) {
            Vec3 p = positions.get(i).subtract(origin);
            float gx = -(float) p.x;  // MC → glTF
            float gy = (float) p.y;
            float gz = -(float) p.z;
            trans[i * 3] = gx;
            trans[i * 3 + 1] = gy;
            trans[i * 3 + 2] = gz;
            uMinMax3(tMin, tMax, gx, gy, gz);
        }
        for (float v : trans) buf.putFloat(v);

        // --- Rotation (yaw/pitch → quaternion) ---
        int offRot = align4(buf.position());
        float[] rots = new float[frameCount * 4];
        float[] rMin = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] rMax = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < frameCount; i++) {
            float[] q = yawPitchToQuaternion(yaws.get(i), pitches.get(i));
            for (int j = 0; j < 4; j++) {
                rots[i * 4 + j] = q[j];
                if (q[j] < rMin[j]) rMin[j] = q[j];
                if (q[j] > rMax[j]) rMax[j] = q[j];
            }
        }
        for (float v : rots) buf.putFloat(v);

        // --- FOV (radians) ---
        int offFov = align4(buf.position());
        float[] fovArr = new float[frameCount];
        float minF = Float.MAX_VALUE, maxF = -Float.MAX_VALUE;
        for (int i = 0; i < frameCount; i++) {
            fovArr[i] = (float) Math.toRadians(fovs.get(i));
            if (fovArr[i] < minF) minF = fovArr[i];
            if (fovArr[i] > maxF) maxF = fovArr[i];
        }
        for (float v : fovArr) buf.putFloat(v);

        int totalLen = buf.position();
        buf.flip();

        // --- JSON ---
        JsonObject root = new JsonObject();
        JsonObject asset = new JsonObject();
        asset.addProperty("version", "2.0");
        asset.addProperty("generator", "Flashback Export Extras");
        root.add("asset", asset);

        JsonArray eu = new JsonArray();
        eu.add("KHR_animation_pointer");
        root.add("extensionsUsed", eu);

        root.addProperty("scene", 0);

        JsonArray scenes = new JsonArray();
        JsonObject sc = new JsonObject();
        JsonArray sn = new JsonArray(); sn.add(0);
        sc.add("nodes", sn);
        scenes.add(sc);
        root.add("scenes", scenes);

        JsonArray nodes = new JsonArray();
        JsonObject node = new JsonObject();
        node.addProperty("camera", 0);
        node.add("translation", v3(trans[0], trans[1], trans[2]));
        node.add("rotation", v4(rots[0], rots[1], rots[2], rots[3]));
        nodes.add(node);
        root.add("nodes", nodes);

        JsonArray cameras = new JsonArray();
        JsonObject cam = new JsonObject();
        cam.addProperty("type", "perspective");
        JsonObject psp = new JsonObject();
        psp.addProperty("aspectRatio", aspectRatio);
        psp.addProperty("yfov", (float) Math.toRadians(fovs.isEmpty() ? 70f : fovs.get(0)));
        psp.addProperty("znear", 0.05f);
        psp.addProperty("zfar", 1000.0f);
        cam.add("perspective", psp);
        cameras.add(cam);
        root.add("cameras", cameras);

        JsonArray acc = new JsonArray();
        acc.add(accessor(BVI_TIME, offTime, frameCount, "SCALAR", 5126, minT, maxT));
        acc.add(accessorV3(BVI_TRANS, offTrans, frameCount, tMin, tMax));
        acc.add(accessorV4(BVI_ROT, offRot, frameCount, rMin, rMax));
        acc.add(accessor(BVI_FOV, offFov, frameCount, "SCALAR", 5126, minF, maxF));
        root.add("accessors", acc);

        JsonArray bv = new JsonArray();
        bv.add(bufferView(0, offTime, frameCount * 4));
        bv.add(bufferView(0, offTrans, frameCount * 12));
        bv.add(bufferView(0, offRot, frameCount * 16));
        bv.add(bufferView(0, offFov, frameCount * 4));
        root.add("bufferViews", bv);

        JsonArray bufs = new JsonArray();
        JsonObject bo = new JsonObject();
        bo.addProperty("byteLength", totalLen);
        bufs.add(bo);
        root.add("buffers", bufs);

        JsonArray anims = new JsonArray();
        JsonObject anim = new JsonObject();
        anim.addProperty("name", "CameraPath");

        JsonArray samplers = new JsonArray();
        samplers.add(sampler(0, 1, "LINEAR"));
        samplers.add(sampler(0, 2, "LINEAR"));
        samplers.add(sampler(0, 3, "LINEAR"));
        anim.add("samplers", samplers);

        JsonArray chans = new JsonArray();
        chans.add(channelNode(0, 0, "translation"));
        chans.add(channelNode(1, 0, "rotation"));
        chans.add(channelFov(2, 0));
        anim.add("channels", chans);

        anims.add(anim);
        root.add("animations", anims);

        byte[] jsonBytes = toPadded(root.toString().getBytes(StandardCharsets.UTF_8));
        int totalSize = 12 + 8 + jsonBytes.length + 8 + totalLen;

        try (OutputStream fos = Files.newOutputStream(outputPath);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fos))) {
            writeLE(out, 0x46546C67); // "glTF"
            writeLE(out, 2);
            writeLE(out, totalSize);
            writeLE(out, jsonBytes.length);
            writeLE(out, 0x4E4F534A); // "JSON"
            out.write(jsonBytes);
            writeLE(out, totalLen);
            writeLE(out, 0x004E4942); // "BIN\0"
            out.write(buf.array(), 0, totalLen);
        }
    }

    /**
     * Writes the sampled path through a sibling temporary file, then publishes
     * it atomically where the file system supports atomic moves.
     */
    public void finish(Path outputPath, Format format) throws IOException {
        if (frameCount == 0) return;
        Format resolvedFormat = format == null ? Format.GLB : format;
        Path absoluteOutput = outputPath.toAbsolutePath().normalize();
        createOutputDirectory(absoluteOutput);
        Path temporary = Files.createTempFile(absoluteOutput.getParent(),
                "." + absoluteOutput.getFileName() + ".", ".tmp");
        boolean published = false;
        try {
            switch (resolvedFormat) {
                case GLB -> writeGlb(temporary);
                case USDA -> writeUsda(temporary);
                case JSON -> writeJson(temporary);
                case AFTER_EFFECTS_JSX -> writeAfterEffectsJsx(temporary);
                case FUSION_LUA -> writeFusionLua(temporary);
            }
            try {
                Files.move(temporary, absoluteOutput, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absoluteOutput, StandardCopyOption.REPLACE_EXISTING);
            }
            published = true;
        } finally {
            if (!published) Files.deleteIfExists(temporary);
        }
    }

    /**
     * Writes a USDA camera using the same right-handed, Y-up transform used by
     * the GLB export. Time samples are frame numbers and the stage frame rate
     * carries the recorded export FPS.
     */
    private void writeUsda(Path outputPath) throws IOException {
        if (frameCount == 0) return;
        createOutputDirectory(outputPath);

        double verticalAperture = 36.0 / aspectRatio;
        StringBuilder out = new StringBuilder(1024 + frameCount * 180);
        out.append("#usda 1.0\n(\n")
                .append("    defaultPrim = \"FlashbackCamera\"\n")
                .append("    framesPerSecond = ").append(number(framerate)).append("\n")
                .append("    timeCodesPerSecond = ").append(number(framerate)).append("\n")
                .append("    startTimeCode = 0\n")
                .append("    endTimeCode = ").append(frameCount - 1).append("\n")
                // Minecraft defines one block as one metre. Explicitly set
                // the USD stage unit so Blender does not apply USD's default
                // centimetre-scale interpretation to camera translations.
                .append("    metersPerUnit = 1.0\n")
                .append("    upAxis = \"Y\"\n")
                .append(")\n\n")
                .append("def Camera \"FlashbackCamera\"\n{\n")
                .append("    float horizontalAperture = 36\n")
                .append("    float verticalAperture = ").append(number(verticalAperture)).append("\n")
                .append("    float2 clippingRange = (0.05, 1000)\n")
                .append("    double3 xformOp:translate.timeSamples = {\n");
        for (int i = 0; i < frameCount; i++) {
            Vec3 position = transformedPosition(i);
            out.append("        ").append(i).append(": (")
                    .append(number(position.x)).append(", ")
                    .append(number(position.y)).append(", ")
                    .append(number(position.z)).append(")")
                    .append(i + 1 == frameCount ? "\n" : ",\n");
        }
        out.append("    }\n    quatf xformOp:orient.timeSamples = {\n");
        for (int i = 0; i < frameCount; i++) {
            float[] rotation = yawPitchToQuaternion(yaws.get(i), pitches.get(i));
            out.append("        ").append(i).append(": (")
                    .append(number(rotation[3])).append(", (")
                    .append(number(rotation[0])).append(", ")
                    .append(number(rotation[1])).append(", ")
                    .append(number(rotation[2])).append("))")
                    .append(i + 1 == frameCount ? "\n" : ",\n");
        }
        out.append("    }\n    float focalLength.timeSamples = {\n");
        for (int i = 0; i < frameCount; i++) {
            double focalLength = verticalAperture / (2.0 * Math.tan(Math.toRadians(fovs.get(i)) / 2.0));
            out.append("        ").append(i).append(": ").append(number(focalLength))
                    .append(i + 1 == frameCount ? "\n" : ",\n");
        }
        out.append("    }\n    uniform token[] xformOpOrder = [\"xformOp:translate\", \"xformOp:orient\"]\n}\n");
        Files.writeString(outputPath, out, StandardCharsets.UTF_8);
    }

    /** Writes an interchange-friendly, plain-text description of every sampled frame. */
    private void writeJson(Path outputPath) throws IOException {
        if (frameCount == 0) return;
        createOutputDirectory(outputPath);

        JsonObject root = new JsonObject();
        root.addProperty("format", "Flashback Export Extras Camera Path");
        root.addProperty("version", 1);
        root.addProperty("framesPerSecond", framerate);
        root.addProperty("aspectRatio", aspectRatio);
        root.addProperty("relativeOrigin", relativeOrigin);
        root.addProperty("positionUnit", "Minecraft block");
        root.addProperty("coordinateSystem", "right-handed Y-up; X/Z converted from Minecraft as GLB/USD");
        root.addProperty("verticalFov", true);

        JsonArray frames = new JsonArray();
        for (int i = 0; i < frameCount; i++) {
            Vec3 position = transformedPosition(i);
            float[] rotation = yawPitchToQuaternion(yaws.get(i), pitches.get(i));
            JsonObject frame = new JsonObject();
            frame.addProperty("frame", i);
            frame.addProperty("time", times.get(i));
            frame.add("position", vd3(position.x, position.y, position.z));
            frame.add("rotationQuaternion", v4(rotation[0], rotation[1], rotation[2], rotation[3]));
            frame.addProperty("verticalFovDegrees", fovs.get(i));
            frames.add(frame);
        }
        root.add("frames", frames);
        Files.writeString(outputPath, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root),
                StandardCharsets.UTF_8);
    }

    /**
     * Writes an ExtendScript importer. It creates and keyframes an After Effects
     * camera in the active composition; users can adjust the documented scale to
     * match the scale of their imported scene.
     */
    private void writeAfterEffectsJsx(Path outputPath) throws IOException {
        if (frameCount == 0) return;
        createOutputDirectory(outputPath);

        StringBuilder out = new StringBuilder(2048 + frameCount * 210);
        out.append("// Flashback Export Extras camera path importer\n")
                .append("// Coordinate system: right-handed Y-up. Adjust WORLD_SCALE to match your scene.\n")
                .append("(function () {\n")
                .append("    app.beginUndoGroup(\"Import Flashback Camera\");\n")
                .append("    try {\n")
                .append("        var comp = app.project.activeItem;\n")
                .append("        if (!(comp instanceof CompItem)) throw new Error(\"Select a composition before running this script.\");\n")
                .append("        var WORLD_SCALE = 100.0;\n")
                .append("        var camera = comp.layers.addCamera(\"Flashback Camera\", [comp.width / 2, comp.height / 2]);\n")
                .append("        camera.autoOrient = AutoOrientType.NO_AUTO_ORIENT;\n")
                .append("        var transform = camera.property(\"Transform\");\n")
                .append("        var position = transform.property(\"Position\");\n")
                .append("        var orientation = transform.property(\"Orientation\");\n")
                .append("        var zoom = camera.property(\"Camera Options\").property(\"Zoom\");\n");
        for (int i = 0; i < frameCount; i++) {
            Vec3 position = transformedPosition(i);
            double horizontalFov = horizontalFovRadians(fovs.get(i));
            double zoom = 1.0 / (2.0 * Math.tan(horizontalFov / 2.0));
            out.append("        position.setValueAtTime(").append(number(times.get(i))).append(", [comp.width / 2 + ")
                    .append(number(position.x)).append(" * WORLD_SCALE, comp.height / 2 - ")
                    .append(number(position.y)).append(" * WORLD_SCALE, ")
                    .append(number(position.z)).append(" * WORLD_SCALE]);\n")
                    .append("        orientation.setValueAtTime(").append(number(times.get(i))).append(", [")
                    .append(number(-pitches.get(i))).append(", ").append(number(-yaws.get(i))).append(", 0]);\n")
                    .append("        zoom.setValueAtTime(").append(number(times.get(i))).append(", comp.width * ")
                    .append(number(zoom)).append(");\n");
        }
        out.append("    } finally {\n")
                .append("        app.endUndoGroup();\n")
                .append("    }\n")
                .append("}());\n");
        Files.writeString(outputPath, out, StandardCharsets.UTF_8);
    }

    /** Writes a Fusion Lua script that creates and keyframes a Camera3D tool. */
    private void writeFusionLua(Path outputPath) throws IOException {
        if (frameCount == 0) return;
        createOutputDirectory(outputPath);

        StringBuilder out = new StringBuilder(2048 + frameCount * 220);
        out.append("-- Flashback Export Extras camera path importer\n")
                .append("-- Coordinate system: right-handed Y-up, matching the GLB and USDA exports.\n")
                .append("local fusion = bmd.scriptapp(\"Fusion\")\n")
                .append("local comp = fusion and fusion.CurrentComp\n")
                .append("if comp == nil then error(\"Open a Fusion composition before running this script.\") end\n")
                .append("local exportFps = ").append(number(framerate)).append("\n")
                .append("local attrs = comp:GetAttrs()\n")
                .append("local startFrame = attrs.COMPN_GlobalStart or 0\n")
                .append("local compFps = comp:GetPrefs(\"Comp.FrameFormat.Rate\") or exportFps\n")
                .append("local frameScale = compFps / exportFps\n")
                .append("local camera = comp:AddTool(\"Camera3D\", -32768, -32768)\n")
                .append("camera:SetAttrs({ TOOLS_Name = \"Flashback Camera\" })\n")
                .append("camera.AovType = 0 -- vertical angle of view\n");
        for (int i = 0; i < frameCount; i++) {
            Vec3 position = transformedPosition(i);
            out.append("local t = startFrame + ").append(i).append(" * frameScale\n")
                    .append("camera.Transform3DOp.Translate.X[t] = ").append(number(position.x)).append("\n")
                    .append("camera.Transform3DOp.Translate.Y[t] = ").append(number(position.y)).append("\n")
                    .append("camera.Transform3DOp.Translate.Z[t] = ").append(number(position.z)).append("\n")
                    .append("camera.Transform3DOp.Rotate.X[t] = ").append(number(-pitches.get(i))).append("\n")
                    .append("camera.Transform3DOp.Rotate.Y[t] = ").append(number(-yaws.get(i))).append("\n")
                    .append("camera.Transform3DOp.Rotate.Z[t] = 0\n")
                    .append("camera.AoV[t] = ").append(number(fovs.get(i))).append("\n");
        }
        Files.writeString(outputPath, out, StandardCharsets.UTF_8);
    }

    private void createOutputDirectory(Path outputPath) throws IOException {
        Path parent = outputPath.getParent();
        if (parent != null) Files.createDirectories(parent);
    }

    private Vec3 transformedPosition(int frame) {
        Vec3 origin = relativeOrigin ? positions.get(0) : Vec3.ZERO;
        Vec3 position = positions.get(frame).subtract(origin);
        return new Vec3(-position.x, position.y, -position.z);
    }

    private double horizontalFovRadians(float verticalFovDegrees) {
        return 2.0 * Math.atan(Math.tan(Math.toRadians(verticalFovDegrees) / 2.0) * aspectRatio);
    }

    private static String number(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Camera path contains a non-finite value");
        return String.format(Locale.ROOT, "%.9f", value);
    }

    // === Quaternion conversion ===
    static float[] yawPitchToQuaternion(float yawDeg, float pitchDeg) {
        double a = Math.toRadians(-pitchDeg);
        double b = Math.toRadians(-yawDeg);
        float sx = (float) Math.sin(a / 2.0), cx = (float) Math.cos(a / 2.0);
        float sy = (float) Math.sin(b / 2.0), cy = (float) Math.cos(b / 2.0);
        return new float[]{cy * sx, sy * cx, -sy * sx, cy * cx};
    }

    // === JSON helpers ===
    static byte[] toPadded(byte[] raw) {
        int pad = (4 - (raw.length % 4)) % 4;
        if (pad == 0) return raw;
        byte[] p = new byte[raw.length + pad];
        System.arraycopy(raw, 0, p, 0, raw.length);
        for (int i = 0; i < pad; i++) p[raw.length + i] = 0x20;
        return p;
    }
    static JsonArray v3(float x, float y, float z) { JsonArray a = new JsonArray(); a.add(x); a.add(y); a.add(z); return a; }
    static JsonArray vd3(double x, double y, double z) { JsonArray a = new JsonArray(); a.add(x); a.add(y); a.add(z); return a; }
    static JsonArray v4(float x, float y, float z, float w) { JsonArray a = new JsonArray(); a.add(x); a.add(y); a.add(z); a.add(w); return a; }
    static JsonObject bufferView(int b, int off, int len) { JsonObject o = new JsonObject(); o.addProperty("buffer", b); o.addProperty("byteOffset", off); o.addProperty("byteLength", len); return o; }
    static JsonObject accessor(int bv, int off, int cnt, String type, int ct, float min, float max) {
        JsonObject o = new JsonObject(); o.addProperty("bufferView", bv); o.addProperty("byteOffset", 0);
        o.addProperty("componentType", ct); o.addProperty("count", cnt); o.addProperty("type", type);
        JsonArray mn = new JsonArray(); mn.add(min); o.add("min", mn);
        JsonArray mx = new JsonArray(); mx.add(max); o.add("max", mx);
        return o;
    }
    static JsonObject accessorV3(int bv, int off, int cnt, float[] min, float[] max) {
        JsonObject o = new JsonObject(); o.addProperty("bufferView", bv); o.addProperty("byteOffset", 0);
        o.addProperty("componentType", 5126); o.addProperty("count", cnt); o.addProperty("type", "VEC3");
        o.add("min", v3(min[0], min[1], min[2])); o.add("max", v3(max[0], max[1], max[2]));
        return o;
    }
    static JsonObject accessorV4(int bv, int off, int cnt, float[] min, float[] max) {
        JsonObject o = new JsonObject(); o.addProperty("bufferView", bv); o.addProperty("byteOffset", 0);
        o.addProperty("componentType", 5126); o.addProperty("count", cnt); o.addProperty("type", "VEC4");
        o.add("min", v4(min[0], min[1], min[2], min[3])); o.add("max", v4(max[0], max[1], max[2], max[3]));
        return o;
    }
    static JsonObject sampler(int inp, int out, String i) { JsonObject o = new JsonObject(); o.addProperty("input", inp); o.addProperty("output", out); o.addProperty("interpolation", i); return o; }
    static JsonObject channelNode(int smp, int node, String path) { JsonObject o = new JsonObject(); o.addProperty("sampler", smp); JsonObject t = new JsonObject(); t.addProperty("node", node); t.addProperty("path", path); o.add("target", t); return o; }
    static JsonObject channelFov(int smp, int camIdx) { JsonObject o = new JsonObject(); o.addProperty("sampler", smp); JsonObject t = new JsonObject(); t.addProperty("path", "pointer"); JsonObject ext = new JsonObject(); JsonObject ptr = new JsonObject(); ptr.addProperty("pointer", "/cameras/" + camIdx + "/perspective/yfov"); ext.add("KHR_animation_pointer", ptr); t.add("extensions", ext); o.add("target", t); return o; }

    // === Binary ===
    static int align4(int v) { return (v + 3) & ~3; }
    static void writeLE(DataOutputStream out, int v) throws IOException { out.writeByte(v & 0xFF); out.writeByte((v >> 8) & 0xFF); out.writeByte((v >> 16) & 0xFF); out.writeByte((v >> 24) & 0xFF); }
    static void uMinMax3(float[] min, float[] max, float x, float y, float z) { if (x < min[0]) min[0] = x; if (x > max[0]) max[0] = x; if (y < min[1]) min[1] = y; if (y > max[1]) max[1] = y; if (z < min[2]) min[2] = z; if (z > max[2]) max[2] = z; }
}
