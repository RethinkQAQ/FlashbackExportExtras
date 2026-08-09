package com.rethinkqaq.flashbackplus.exporting;

import com.mojang.blaze3d.platform.NativeImage;
import com.moulberry.flashback.exporting.VideoWriter;
import com.rethinkqaq.flashbackplus.FlashbackPlusConfig;
import com.rethinkqaq.flashbackplus.Flashbackplus;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * VideoWriter implementation that writes multi-layer OpenEXR frames
 * (color + depth) instead of a video file.
 */
public class ExrVideoWriter implements VideoWriter {

    private final MultiLayerExrWriter exrWriter;
    private int frameCount;

    public ExrVideoWriter(Path outputDir, int width, int height) throws IOException {
        this.exrWriter = new MultiLayerExrWriter(outputDir, width, height,
                FlashbackPlusConfig.INSTANCE.depthLinearizeWorldSpace);
    }

    @Override
    public void encode(NativeImage colorImage, FloatBuffer audioBuffer) {
        FloatBuffer depth = null;
        synchronized (DepthCaptureState.depthQueue) {
            if (!DepthCaptureState.depthQueue.isEmpty()) {
                depth = DepthCaptureState.depthQueue.removeFirst();
            }
        }

        if (depth != null) {
            try {
                exrWriter.writeFrame(colorImage, depth, frameCount++);
            } catch (IOException e) {
                Flashbackplus.LOGGER.error("Failed to write EXR frame", e);
            } finally {
                // Return buffer to pool for reuse (avoids per-frame native allocation)
                DepthCaptureState.releaseBuffer(depth);
            }
        }
    }

    @Override
    /*? if >=1.21.5 {*/
    /*public void finish(Consumer<String> statusConsumer) {
    *//*?} else {*/
    public void finish() {
    /*?}*/
        exrWriter.close();
    }

    @Override
    public void close() {
        exrWriter.close();
    }
}
