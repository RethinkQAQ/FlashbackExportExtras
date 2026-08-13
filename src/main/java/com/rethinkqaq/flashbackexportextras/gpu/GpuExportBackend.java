package com.rethinkqaq.flashbackexportextras.gpu;

import com.mojang.blaze3d.pipeline.RenderTarget;

/** Backend boundary for GPU work performed during Flashback export. */
public interface GpuExportBackend extends AutoCloseable {
    boolean supportsHdr();

    default boolean supportsSceneLinearHdr() { return false; }

    /** Captures depth before the game clears the main depth attachment. */
    default void snapshotDepth(RenderTarget target, int width, int height, float depthFar) {}

    void captureDepth(RenderTarget target, int width, int height, float depthFar);

    /** Queues an RGBA16 BT.2020/PQ capture for the matching export frame. */
    default void captureHdr(RenderTarget target, int width, int height,
                            float peakBrightness, long frameId) {}

    /** Queues an RGBA16F scene-linear Rec.709 capture for the matching export frame. */
    default void captureSceneLinearHdr(RenderTarget target, int width, int height, long frameId) {}

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
