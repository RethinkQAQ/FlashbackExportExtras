package com.rethinkqaq.flashbackplus.exporting;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Queue;

/**
 * Shared mutable state for depth capture and camera recording.
 * Written by MixinGameRenderer, read by MixinExportJob.
 * Lives outside the mixin class to satisfy Mixin's field visibility rules.
 *
 * Also manages a small pool of FloatBuffers to avoid per-frame
 * native allocation overhead during depth readback.
 */
public class DepthCaptureState {

    /** Whether depth capture is active for the current export. */
    public static volatile boolean active = false;

    /** Render target dimensions for depth readback. */
    public static int width, height;

    /** Far plane distance used during depth capture. */
    public static volatile float depthFar = 1000.0f;

    /** FOV captured from GameRenderer.getProjectionMatrix (degrees). */
    public static volatile float fovDegrees = 70.0f;

    /**
     * Target FOV set by the keyframe interpolation system.
     * Updated by MixinFOVKeyframe whenever a FOV keyframe is evaluated.
     * This is the "desired" FOV at the current server tick, before
     * client-frame interpolation.
     */
    public static volatile float keyframeTargetFov = 70.0f;

    /**
     * Previous frame's interpolated FOV, used for client-frame
     * interpolation between server ticks (via partialClientTick).
     */
    public static volatile float previousFov = 70.0f;

    /** Camera position captured after renderLevel (MC world space). */
    public static volatile double camX, camY, camZ;

    /** Camera rotation captured after renderLevel (MC degrees). */
    public static volatile float camYaw, camPitch;

    /** FIFO queue of captured depth buffers, consumed at encode time. */
    public static final Deque<DepthFrame> depthQueue = new ArrayDeque<>();
    /**
     * The world-depth snapshot taken immediately before Minecraft clears the
     * depth attachment for hand rendering. It is intentionally unnumbered:
     * ExportJob assigns the output frame ID only when it starts downloading
     * the corresponding color image.
     */
    private static FloatBuffer pendingWorldDepth;
    private static long nextExportFrameId;

    /** Frame ID explicitly requested by ExportJob for the frame being rendered. */
    public static volatile long requestedFrameId = -1L;

    /**
     * Set only when Iris' shaderpack pipeline actually rendered this frame.
     * Installing Iris without enabling a shaderpack leaves this false.
     */
    public static volatile boolean irisShaderPackRenderedThisFrame = false;

    public static void beginRenderFrame() {
        irisShaderPackRenderedThisFrame = false;
    }

    public static void markIrisShaderPackRendered() {
        if (active) irisShaderPackRenderedThisFrame = true;
    }

    // === Buffer pool for readback copies ===
    private static final int POOL_CAPACITY = 4;
    private static final Queue<FloatBuffer> bufferPool = new ArrayDeque<>();

    /** Acquire a FloatBuffer from the pool, or allocate a new one. */
    public static FloatBuffer acquireBuffer() {
        synchronized (bufferPool) {
            FloatBuffer buf = bufferPool.poll();
            if (buf != null) {
                buf.clear();
                return buf;
            }
        }
        return ByteBuffer.allocateDirect(width * height * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
    }

    /** Return a consumed FloatBuffer to the pool for reuse. */
    public static void releaseBuffer(FloatBuffer buf) {
        if (buf == null) return;
        synchronized (bufferPool) {
            if (bufferPool.size() < POOL_CAPACITY) {
                bufferPool.add(buf);
            }
        }
    }

    public static synchronized long nextExportFrameId() {
        return nextExportFrameId++;
    }

    /** Returns the export frame ID requested for the current render. */
    public static synchronized long captureFrameId() {
        return requestedFrameId;
    }

    public static synchronized void replacePendingWorldDepth(FloatBuffer data) {
        FloatBuffer previous = pendingWorldDepth;
        pendingWorldDepth = data;
        if (previous != null) releaseBuffer(previous);
    }

    public static synchronized FloatBuffer takePendingWorldDepth() {
        FloatBuffer data = pendingWorldDepth;
        pendingWorldDepth = null;
        return data;
    }

    public static final class DepthFrame {
        public final long frameId;
        public final FloatBuffer data;
        public final float zNear;
        public final float zFar;

        public DepthFrame(long frameId, FloatBuffer data) {
            this(frameId, data, 0.05f, depthFar);
        }

        public DepthFrame(long frameId, FloatBuffer data, float zNear, float zFar) {
            this.frameId = frameId;
            this.data = data;
            this.zNear = zNear;
            this.zFar = zFar;
        }
    }

    /** Clears all state for a new export. */
    public static void reset() {
        active = false;
        width = height = 0;
        fovDegrees = 70.0f;
        keyframeTargetFov = 70.0f;
        previousFov = 70.0f;
        camX = camY = camZ = 0.0;
        camYaw = camPitch = 0.0f;
        depthFar = 1000.0f;
        nextExportFrameId = 0;
        requestedFrameId = -1L;
        irisShaderPackRenderedThisFrame = false;

        FloatBuffer pending = takePendingWorldDepth();
        releaseBuffer(pending);

        synchronized (depthQueue) {
            DepthFrame frame;
            while ((frame = depthQueue.pollFirst()) != null) {
                releaseBuffer(frame.data);
            }
        }
        synchronized (bufferPool) {
            bufferPool.clear();
        }

    }
}
