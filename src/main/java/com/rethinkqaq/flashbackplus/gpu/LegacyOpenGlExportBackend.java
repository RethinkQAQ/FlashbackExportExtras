package com.rethinkqaq.flashbackplus.gpu;

import com.mojang.blaze3d.pipeline.RenderTarget;

import java.nio.ByteBuffer;

/** Transitional backend for pre-26.1 versions. */
public final class LegacyOpenGlExportBackend implements GpuExportBackend {
    @Override public boolean supportsHdr() { return true; }
    @Override public void captureDepth(RenderTarget target, int width, int height, float depthFar) {}
    @Override public ByteBuffer captureHdr(RenderTarget target, int width, int height, float peakBrightness) { return null; }
    @Override public void endFrame() {}
    @Override public void close() {}
}
