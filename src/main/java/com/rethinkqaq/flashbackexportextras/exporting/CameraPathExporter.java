package com.rethinkqaq.flashbackexportextras.exporting;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Exports camera path animation as a GLB (Binary glTF 2.0) file for Blender.
 *
 * Records per-frame: position, rotation (yaw/pitch → quaternion), FOV.
 * Supports optional relative origin (first frame → 0,0,0).
 * Writes animation channels for translation, rotation, and yfov (KHR_animation_pointer).
 */
public class CameraPathExporter {

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

    /**
     * Applies a 7-point Gaussian kernel to the FOV sequence to eliminate
     * residual micro-jitter from the interpolation pipeline.
     * Only affects frames sufficiently far from the ends (3+ frames from edges).
     */
    public void applyGaussianSmoothing() {
        if (fovs.size() < 7) return;

        // 7-point Gaussian kernel (sigma ≈ 1.0)
        final float[] KERNEL = {0.006f, 0.061f, 0.242f, 0.383f, 0.242f, 0.061f, 0.006f};
        final int RADIUS = 3;

        float[] smoothed = new float[fovs.size()];
        for (int i = 0; i < fovs.size(); i++) {
            if (i < RADIUS || i >= fovs.size() - RADIUS) {
                smoothed[i] = fovs.get(i);  // keep edge values as-is
                continue;
            }
            float sum = 0f;
            for (int j = -RADIUS; j <= RADIUS; j++) {
                sum += fovs.get(i + j) * KERNEL[j + RADIUS];
            }
            smoothed[i] = sum;
        }

        for (int i = 0; i < fovs.size(); i++) {
            fovs.set(i, smoothed[i]);
        }
    }

    public void finish(Path outputPath) throws IOException {
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
