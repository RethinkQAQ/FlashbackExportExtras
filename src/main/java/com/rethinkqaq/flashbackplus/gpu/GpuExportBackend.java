package com.rethinkqaq.flashbackplus.gpu;

import com.mojang.blaze3d.pipeline.RenderTarget;

import java.nio.ByteBuffer;

/** Backend boundary for GPU work performed during Flashback export. */
public interface GpuExportBackend extends AutoCloseable {
    boolean supportsDepthReadback();
    boolean supportsHdr();

    default boolean capturesBeforeDepthClear() { return false; }

    /**
     * Snapshot the world depth attachment before Minecraft clears it for the
     * hand and GUI passes. Backends that do not need a GPU-side snapshot keep
     * the default no-op implementation.
     */
    default void snapshotWorldDepth(RenderTarget target, int width, int height, float depthFar) {}

    void captureDepth(RenderTarget target, int width, int height, float depthFar);

    ByteBuffer captureHdr(RenderTarget target, int width, int height, float peakBrightness);

    void endFrame();

    /** Drain pending GPU readback before the EXR writer is finalized. */
    default void flush() { endFrame(); }

    /**
     * Releases GPU objects from the render thread. Returning {@code false}
     * keeps this backend queued until outstanding GPU work completes.
     */
    default boolean releaseOnRenderThread() {
        close();
        return true;
    }

    @Override
    void close();
}
