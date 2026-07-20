package com.rethinkqaq.flashbackplus.exporting;

import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Shared mutable state for depth capture and camera recording.
 * Written by MixinGameRenderer, read by MixinExportJob.
 * Lives outside the mixin class to satisfy Mixin's field visibility rules.
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

    /** Camera position captured after renderLevel (MC world space). */
    public static volatile double camX, camY, camZ;

    /** Camera rotation captured after renderLevel (MC degrees). */
    public static volatile float camYaw, camPitch;

    /** FIFO queue of captured depth buffers, consumed at encode time. */
    public static final Deque<FloatBuffer> depthQueue = new ArrayDeque<>();

    /** Clears all state for a new export. */
    public static void reset() {
        active = false;
        width = height = 0;
        fovDegrees = 70.0f;
        camX = camY = camZ = 0.0;
        camYaw = camPitch = 0.0f;
        synchronized (depthQueue) {
            depthQueue.clear();
        }
    }
}
