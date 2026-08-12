package com.rethinkqaq.flashbackplus.gpu;

import com.mojang.blaze3d.pipeline.RenderTarget;

import java.nio.ByteBuffer;

/** Backend boundary for GPU work performed during Flashback export. */
public interface GpuExportBackend extends AutoCloseable {
    boolean supportsHdr();

    /** Captures depth before the game clears the main depth attachment. */
    default void snapshotDepth(RenderTarget target, int width, int height, float depthFar) {}

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
