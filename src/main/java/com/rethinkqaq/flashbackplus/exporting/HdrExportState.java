package com.rethinkqaq.flashbackplus.exporting;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Shared mutable state for HDR export (similar to DepthCaptureState).
 *
 * HDR data flow:
 *   HDR Mod → RGBA16F main render target (scRGB-nl encoded)
 *   → HdrColorTransformShader (scRGB-nl → BT.2020 + PQ via GLSL)
 *   → HdrFrameCapture (16-bit PBO readback)
 *   → HdrVideoWriter / AsyncFFmpegVideoWriter (rgba64 encoding)
 *
 * All fields are volatile — written by the render thread, read by the export/config threads.
 */
public class HdrExportState {

    /** Whether HDR Mod is installed (set once at client init). */
    private static volatile boolean hdrModLoaded = false;

    /** Whether HDR is enabled in HDR Mod's config (toggled in-game). */
    private static volatile boolean hdrModEnabled = false;

    /** Whether an HDR export is currently active. */
    private static volatile boolean active = false;

    /** Peak display brightness in nits (default 1000, configurable). */
    private static volatile float peakBrightness = 1000.0f;

    /** Width/height of the current export render target. */
    public static volatile int width;
    public static volatile int height;

    // === Availability checks ===

    /** HDR export can be offered in the UI (HDR Mod installed AND HDR enabled). */
    public static boolean isAvailable() {
        return hdrModLoaded && hdrModEnabled;
    }

    /** HDR export is currently running. */
    public static boolean isActive() {
        return active && isAvailable();
    }

    // === Getters / setters ===

    public static boolean isHdrModLoaded() { return hdrModLoaded; }
    public static void setHdrModLoaded(boolean v) { hdrModLoaded = v; }

    public static boolean isHdrModEnabled() { return hdrModEnabled; }
    public static void setHdrModEnabled(boolean v) { hdrModEnabled = v; }

    public static float getPeakBrightness() { return peakBrightness; }
    public static void setPeakBrightness(float v) { peakBrightness = v; }

    // === Activation ===

    /** Frame queue: HDR 16-bit data, produced by HdrFrameCapture, consumed by the encoder. */
    private static final Deque<ByteBuffer> frameQueue = new ArrayDeque<>();

    public static void enqueueFrame(ByteBuffer hdrData) {
        synchronized (frameQueue) {
            frameQueue.add(hdrData);
        }
    }

    public static ByteBuffer dequeueFrame() {
        synchronized (frameQueue) {
            return frameQueue.poll();
        }
    }

    public static int queuedFrameCount() {
        synchronized (frameQueue) {
            return frameQueue.size();
        }
    }

    public static void activate() {
        if (!isAvailable()) {
            throw new IllegalStateException("HDR export requires HDR Mod with HDR enabled");
        }
        active = true;
    }

    public static void deactivate() {
        active = false;
        // Free any unconsumed frames
        synchronized (frameQueue) {
            frameQueue.forEach(org.lwjgl.system.MemoryUtil::memFree);
            frameQueue.clear();
        }
    }
}
