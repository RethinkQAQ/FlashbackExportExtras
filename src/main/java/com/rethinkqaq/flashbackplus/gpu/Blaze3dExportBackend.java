package com.rethinkqaq.flashbackplus.gpu;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.rethinkqaq.flashbackplus.exporting.DepthCaptureState;

import java.nio.ByteBuffer;

/** Blaze3D boundary for 26.x; resource implementation is version-specific. */
public final class Blaze3dExportBackend implements GpuExportBackend {
    private static final int BUFFER_COUNT = 2;
    private final GpuBuffer[] depthBuffers = new GpuBuffer[BUFFER_COUNT];
    private final GpuFence[] depthFences = new GpuFence[BUFFER_COUNT];
    private int writeIndex;
    private int width;
    private int height;

    @Override public boolean supportsDepthReadback() { return true; }
    @Override public boolean supportsHdr() { return false; }
    @Override
    public void captureDepth(RenderTarget target, int width, int height, float depthFar) {
        if (target == null || !target.useDepth || target.getDepthTexture() == null) return;
        RenderSystem.assertOnRenderThread();
        ensureDepthBuffers(width, height);
        collectDepth();

        int index = writeIndex;
        if (depthFences[index] != null) return;
        GpuTexture texture = target.getDepthTexture();
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToBuffer(texture, depthBuffers[index], 0L, () -> {}, 0);
        depthFences[index] = encoder.createFence();
        /*? if >=26.2 {*/
        encoder.submit();
        /*?}*/
        writeIndex = 1 - writeIndex;
    }
    @Override public ByteBuffer captureHdr(RenderTarget target, int width, int height, float peakBrightness) { return null; }
    private void ensureDepthBuffers(int newWidth, int newHeight) {
        if (width == newWidth && height == newHeight && depthBuffers[0] != null) return;
        closeDepthBuffers();
        width = newWidth;
        height = newHeight;
        long size = (long) newWidth * newHeight * 4L;
        for (int i = 0; i < BUFFER_COUNT; i++) {
            depthBuffers[i] = RenderSystem.getDevice().createBuffer(
                    () -> "Flashback Plus depth readback",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                    size);
        }
    }

    private void collectDepth() {
        int index = 1 - writeIndex;
        GpuFence fence = depthFences[index];
        if (fence == null || !fence.awaitCompletion(0L)) return;
        /*? if >=26.2 {*/
        try (GpuBufferSlice.MappedView mapped = depthBuffers[index].slice().map(true, false)) {
            ByteBuffer data = mapped.data().duplicate();
            data.rewind();
            var copy = DepthCaptureState.acquireBuffer();
            copy.put(data.asFloatBuffer());
            copy.rewind();
            synchronized (DepthCaptureState.depthQueue) {
                DepthCaptureState.depthQueue.addLast(copy);
            }
        } finally {
            fence.close();
            depthFences[index] = null;
        }
        /*?}*/
    }

    private void closeDepthBuffers() {
        for (int i = 0; i < BUFFER_COUNT; i++) {
            if (depthFences[i] != null) depthFences[i].close();
            if (depthBuffers[i] != null) depthBuffers[i].close();
            depthFences[i] = null;
            depthBuffers[i] = null;
        }
        writeIndex = 0;
    }

    @Override public void endFrame() { collectDepth(); }
    @Override public void close() { closeDepthBuffers(); }
}
