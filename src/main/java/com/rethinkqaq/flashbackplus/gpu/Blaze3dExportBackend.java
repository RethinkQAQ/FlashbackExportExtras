package com.rethinkqaq.flashbackplus.gpu;

//? if >=26.1 {

/*import com.mojang.blaze3d.pipeline.RenderTarget;
/^? if >=26.2 {^/
/^import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.shaders.UniformType;
^//^?}^/
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
/^? if >=26.2 {^/
/^import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.systems.RenderPass;
import net.minecraft.resources.ResourceLocation;
^//^?}^/
import com.rethinkqaq.flashbackplus.exporting.DepthCaptureState;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryUtil;

/^* Blaze3D boundary for 26.x; resource implementation is version-specific. ^/
public final class Blaze3dExportBackend implements GpuExportBackend {
    private static final int BUFFER_COUNT = 3;
    private final GpuBuffer[] depthBuffers = new GpuBuffer[BUFFER_COUNT];
    private final GpuFence[] depthFences = new GpuFence[BUFFER_COUNT];
    private final long[] depthFrameIds = new long[BUFFER_COUNT];
    private int writeIndex;
    private int width;
    private int height;
    private boolean depthReadbackFailed;
    private int depthDebugFrame;
    private int pendingWorldDepthIndex = -1;
    /^? if >=26.2 {^/
    /^private GpuTexture hdrCopyTexture;
    private GpuTextureView hdrCopyView;
    private RenderPipeline hdrCopyPipeline;
    private GpuBuffer hdrReadbackBuffer;
    private GpuBuffer hdrUniformBuffer;
    ^//^?}^/
    /^? if >=26.2 {^/
    /^private GpuTexture depthCopyTexture;
    private GpuTextureView depthCopyView;
    private RenderPipeline depthCopyPipeline;
    ^//^?}^/
    @Override public boolean supportsDepthReadback() { return true; }
    @Override public boolean supportsHdr() {
        /^? if >=26.2 {^/
        /^return true;
        ^//^?} else {^/
        return false;
        /^?}^/
    }
    @Override public boolean capturesBeforeDepthClear() { return true; }

    @Override
    public void snapshotWorldDepth(RenderTarget target, int width, int height, float depthFar) {
        /^? if >=26.2 {^/
        /^if (target == null || !target.useDepth || target.getDepthTexture() == null || depthReadbackFailed) return;
        try {
            RenderSystem.assertOnRenderThread();
            ensureDepthBuffers(width, height);

            // Keep the snapshot unnumbered until ExportJob starts the matching
            // colour download. Minecraft clears this attachment immediately
            // after the world pass, so it cannot be read at startDownload.
            if (pendingWorldDepthIndex >= 0) {
                discardDepthCopy(pendingWorldDepthIndex);
                pendingWorldDepthIndex = -1;
            }
            int index = writeIndex;
            if (depthFences[index] != null) discardDepthCopy(index);

            ensureDepthCopyTarget(width, height);
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            try (RenderPass pass = encoder.createRenderPass(
                    () -> "Flashback Plus world-depth snapshot",
                    depthCopyView, java.util.Optional.empty())) {
                pass.setPipeline(depthCopyPipeline);
                pass.bindTexture("InDepth", target.getDepthTextureView(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.draw(3, 1, 0, 0);
            }
            encoder.copyTextureToBuffer(depthCopyTexture, depthBuffers[index], 0L, () -> {}, 0);
            depthFrameIds[index] = -1L;
            depthFences[index] = encoder.createFence();
            encoder.submit();
            pendingWorldDepthIndex = index;
            writeIndex = (writeIndex + 1) % BUFFER_COUNT;
        } catch (RuntimeException e) {
            depthReadbackFailed = true;
            closeDepthBuffers();
            com.rethinkqaq.flashbackplus.Flashbackplus.LOGGER.error(
                    "Blaze3D world-depth snapshot failed; continuing without depth", e);
        }
        ^//^?}^/
    }

    @Override
    public void captureDepth(RenderTarget target, int width, int height, float depthFar) {
        /^? if >=26.2 {^/
        /^consumeWorldDepth(DepthCaptureState.captureFrameId());
        ^//^?} else {^/
        if (target == null || !target.useDepth || target.getDepthTexture() == null) return;
        if (depthReadbackFailed) return;
        try {
            RenderSystem.assertOnRenderThread();
            ensureDepthBuffers(width, height);
            collectDepth();
            int index = writeIndex;
            if (depthFences[index] != null) return;
            GpuTexture texture = target.getDepthTexture();
            /^? if >=26.2 {^/
            /^ensureDepthCopyTarget(width, height);
            ^//^?}^/
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            /^? if >=26.2 {^/
            /^try (RenderPass pass = encoder.createRenderPass(
                    () -> "Flashback Plus depth conversion",
                    depthCopyView, java.util.Optional.empty())) {
                pass.setPipeline(depthCopyPipeline);
                pass.bindTexture("InDepth", target.getDepthTextureView(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.draw(3, 1, 0, 0);
            }
            encoder.copyTextureToBuffer(depthCopyTexture, depthBuffers[index], 0L, () -> {}, 0);
            ^//^?} else {^/
            encoder.copyTextureToBuffer(texture, depthBuffers[index], 0L, () -> {}, 0);
            /^?}^/
            depthFrameIds[index] = DepthCaptureState.captureFrameId();
            depthFences[index] = encoder.createFence();
            /^? if >=26.2 {^/
            /^encoder.submit();
            // Flashback starts its color download immediately after renderLevel.
            // Wait for this frame's depth copy so the EXR writer receives its
            // matching depth buffer instead of permanently skipping the frame.
            collectDepth(index, 1_000_000_000L);
            ^//^?}^/
            writeIndex = (writeIndex + 1) % BUFFER_COUNT;
        } catch (RuntimeException e) {
            depthReadbackFailed = true;
            closeDepthBuffers();
            com.rethinkqaq.flashbackplus.Flashbackplus.LOGGER.error(
                    "Blaze3D depth readback unavailable; continuing without depth", e);
        }
        /^?}^/
    }

    /^? if >=26.2 {^/
    /^private void consumeWorldDepth(long frameId) {
        int index = pendingWorldDepthIndex;
        pendingWorldDepthIndex = -1;
        if (index < 0 || depthFences[index] == null) {
            com.rethinkqaq.flashbackplus.Flashbackplus.LOGGER.warn(
                    "No pre-clear world-depth snapshot available for EXR frame {}", frameId);
            return;
        }
        readDepthCopy(index, frameId, 1_000_000_000L);
    }

    private void discardDepthCopy(int index) {
        GpuFence fence = depthFences[index];
        if (fence == null) return;
        fence.awaitCompletion(1_000_000_000L);
        fence.close();
        depthFences[index] = null;
        depthFrameIds[index] = -1L;
    }

    private void readDepthCopy(int index, long frameId, long timeoutNanos) {
        GpuFence fence = depthFences[index];
        if (fence == null || !fence.awaitCompletion(timeoutNanos)) {
            com.rethinkqaq.flashbackplus.Flashbackplus.LOGGER.warn(
                    "World-depth GPU snapshot did not complete for EXR frame {}", frameId);
            return;
        }
        try (GpuBufferSlice.MappedView mapped = depthBuffers[index].slice().map(true, false)) {
            ByteBuffer data = mapped.data().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            data.rewind();
            var copy = DepthCaptureState.acquireBuffer();
            java.nio.FloatBuffer source = data.asFloatBuffer();
            for (int i = 0; i < source.remaining(); i++) copy.put(normalizeDepth(source.get(i)));
            copy.rewind();
            logDepthReadback(copy, index);
            synchronized (DepthCaptureState.depthQueue) {
                DepthCaptureState.depthQueue.addLast(new DepthCaptureState.DepthFrame(frameId, copy));
            }
        } finally {
            fence.close();
            depthFences[index] = null;
            depthFrameIds[index] = -1L;
        }
    }
    ^//^?}^/
    @Override
    public ByteBuffer captureHdr(RenderTarget target, int width, int height, float peakBrightness) {
        /^? if >=26.2 {^/
        /^if (target == null || target.getColorTexture() == null || target.getColorTextureView() == null) return null;
        try {
            RenderSystem.assertOnRenderThread();
            ensureHdrTarget(width, height);
            ByteBuffer parameters = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
            parameters.putFloat(peakBrightness).putFloat(0.0f).putFloat(0.0f).putFloat(0.0f).flip();

            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.writeToBuffer(hdrUniformBuffer.slice(), parameters);
            try (RenderPass pass = encoder.createRenderPass(
                    () -> "Flashback Plus HDR10 colour transform", hdrCopyView, java.util.Optional.empty())) {
                pass.setPipeline(hdrCopyPipeline);
                pass.bindTexture("InSampler", target.getColorTextureView(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.setUniform("HdrParameters", hdrUniformBuffer);
                pass.draw(3, 1, 0, 0);
            }
            encoder.copyTextureToBuffer(hdrCopyTexture, hdrReadbackBuffer, 0L, () -> {}, 0);
            GpuFence fence = encoder.createFence();
            encoder.submit();
            if (!fence.awaitCompletion(1_000_000_000L)) {
                com.rethinkqaq.flashbackplus.Flashbackplus.LOGGER.warn("HDR GPU readback did not complete in time");
                return null;
            }
            try (GpuBufferSlice.MappedView mapped = hdrReadbackBuffer.slice().map(true, false)) {
                ByteBuffer source = mapped.data().duplicate();
                source.rewind();
                ByteBuffer result = MemoryUtil.memAlloc(width * height * 8);
                result.put(source);
                result.rewind();
                return result;
            } finally {
                fence.close();
            }
        } catch (RuntimeException e) {
            com.rethinkqaq.flashbackplus.Flashbackplus.LOGGER.error("Blaze3D HDR capture failed", e);
            return null;
        }
        ^//^?} else {^/
        return null;
        /^?}^/
    }

    /^? if >=26.2 {^/
    /^private void ensureHdrTarget(int newWidth, int newHeight) {
        if (hdrCopyTexture != null && hdrCopyTexture.getWidth(0) == newWidth
                && hdrCopyTexture.getHeight(0) == newHeight) return;
        closeHdrTarget();
        long size = (long) newWidth * newHeight * 8L;
        hdrCopyTexture = RenderSystem.getDevice().createTexture(
                "Flashback Plus HDR10 colour transform",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                GpuFormat.RGBA16_UNORM, newWidth, newHeight, 1, 1);
        hdrCopyView = RenderSystem.getDevice().createTextureView(hdrCopyTexture);
        hdrReadbackBuffer = RenderSystem.getDevice().createBuffer(
                () -> "Flashback Plus HDR10 readback",
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, size);
        hdrUniformBuffer = RenderSystem.getDevice().createBuffer(
                () -> "Flashback Plus HDR10 parameters",
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM, 16L);
        hdrCopyPipeline = RenderPipeline.builder()
                .withLocation("flashbackplus_hdr_color_transform_blaze")
                .withVertexShader(ResourceLocation.fromNamespaceAndPath(
                        "flashbackplus", "core/flashbackplus_hdr_color_transform_blaze"))
                .withFragmentShader(ResourceLocation.fromNamespaceAndPath(
                        "flashbackplus", "core/flashbackplus_hdr_color_transform_blaze"))
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withSampler("InSampler")
                        .withUniform("HdrParameters", UniformType.UNIFORM_BUFFER)
                        .build())
                .withColorTargetState(new ColorTargetState(java.util.Optional.empty(), GpuFormat.RGBA16_UNORM,
                        ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .build();
    }

    private void closeHdrTarget() {
        if (hdrUniformBuffer != null) hdrUniformBuffer.close();
        if (hdrReadbackBuffer != null) hdrReadbackBuffer.close();
        if (hdrCopyView != null) hdrCopyView.close();
        if (hdrCopyTexture != null) hdrCopyTexture.close();
        hdrUniformBuffer = null;
        hdrReadbackBuffer = null;
        hdrCopyView = null;
        hdrCopyTexture = null;
        hdrCopyPipeline = null;
    }
    ^//^?}^/
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
        int index = (writeIndex + BUFFER_COUNT - 1) % BUFFER_COUNT;
        collectDepth(index, 0L);
    }

    private void collectDepth(int index, long timeoutNanos) {
        GpuFence fence = depthFences[index];
        if (fence == null || !fence.awaitCompletion(timeoutNanos)) return;
        /^? if >=26.2 {^/
        /^try (GpuBufferSlice.MappedView mapped = depthBuffers[index].slice().map(true, false)) {
            ByteBuffer data = mapped.data().duplicate();
            // GPU readback buffers use little-endian byte order. A duplicated
            // ByteBuffer defaults to BIG_ENDIAN in Java, corrupting every
            // float sample (1.0f becomes 4.6006E-41).
            data.order(ByteOrder.LITTLE_ENDIAN);
            data.rewind();
            var copy = DepthCaptureState.acquireBuffer();
            java.nio.FloatBuffer source = data.asFloatBuffer();
            for (int i = 0; i < source.remaining(); i++) {
                copy.put(normalizeDepth(source.get(i)));
            }
            copy.rewind();
            logDepthReadback(copy, index);
            synchronized (DepthCaptureState.depthQueue) {
                DepthCaptureState.depthQueue.addLast(
                        new DepthCaptureState.DepthFrame(depthFrameIds[index], copy));
            }
        } finally {
            fence.close();
            depthFences[index] = null;
        }
        ^//^?}^/
    }

    private float normalizeDepth(float depth) {
        // 26.2 reverse-Z is already converted by the depth-copy shader.
        // Keep the readback value unchanged so it is not inverted twice.
        return depth;
    }

    /^? if >=26.2 {^/
    /^private void ensureDepthCopyTarget(int newWidth, int newHeight) {
        if (depthCopyTexture != null && depthCopyTexture.getWidth(0) == newWidth
                && depthCopyTexture.getHeight(0) == newHeight) return;
        if (depthCopyView != null) depthCopyView.close();
        if (depthCopyTexture != null) depthCopyTexture.close();
        depthCopyTexture = RenderSystem.getDevice().createTexture(
                "Flashback Plus depth conversion",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                GpuFormat.R32_FLOAT, newWidth, newHeight, 1, 1);
        depthCopyView = RenderSystem.getDevice().createTextureView(depthCopyTexture);
        if (depthCopyPipeline == null) {
            depthCopyPipeline = RenderPipeline.builder()
                    .withLocation("flashbackplus_depth_copy")
                    .withVertexShader(ResourceLocation.fromNamespaceAndPath(
                            "flashbackplus", "core/flashbackplus_depth_copy"))
                    .withFragmentShader(ResourceLocation.fromNamespaceAndPath(
                            "flashbackplus", "core/flashbackplus_depth_copy"))
                    .withBindGroupLayout(BindGroupLayout.builder().withSampler("InDepth").build())
                    .withColorTargetState(new ColorTargetState(java.util.Optional.empty(), GpuFormat.R32_FLOAT,
                            ColorTargetState.WRITE_RED))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .withCull(false)
                    .build();
        }
    }
    ^//^?}^/

    private void closeDepthBuffers() {
        for (int i = 0; i < BUFFER_COUNT; i++) {
            if (depthFences[i] != null && depthFences[i].awaitCompletion(0L)) depthFences[i].close();
            if (depthFences[i] == null && depthBuffers[i] != null) depthBuffers[i].close();
            depthFences[i] = null;
            depthBuffers[i] = null;
            depthFrameIds[i] = -1L;
        }
        writeIndex = 0;
    }

    @Override public void endFrame() { collectDepth(); }

    @Override
    public void flush() {
        for (int i = 0; i < BUFFER_COUNT; i++) {
            if (depthFences[i] != null) collectDepth(i, 1_000_000_000L);
        }
    }
    @Override public void close() {
        // ExportJob.doExport returns on its worker executor. GPU objects must only
        // be destroyed while the render thread/context is still alive.
        if (!RenderSystem.isOnRenderThread()) return;
        closeDepthBuffers();
        /^? if >=26.2 {^/
        /^if (depthCopyView != null) depthCopyView.close();
        if (depthCopyTexture != null) depthCopyTexture.close();
        depthCopyView = null;
        depthCopyTexture = null;
        closeHdrTarget();
        ^//^?}^/
    }

    private void logDepthReadback(java.nio.FloatBuffer data, int bufferIndex) {
        int frame = depthDebugFrame++;
        if (frame >= 3 && frame % 30 != 0) return;

        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        int finite = 0;
        int count = data.remaining();
        for (int i = 0; i < count; i++) {
            float value = data.get(i);
            if (Float.isFinite(value)) {
                min = Math.min(min, value);
                max = Math.max(max, value);
                finite++;
            }
        }

        int center = Math.max(0, Math.min(count - 1, (height / 2) * width + width / 2));
        int quarter = Math.max(0, Math.min(count - 1, (height / 4) * width + width / 4));
        com.rethinkqaq.flashbackplus.Flashbackplus.LOGGER.info(
                "26.2 depth readback #{}: buffer={}, size={}x{}, finite={}/{}, min={}, max={}, q1={}, center={}, q3={}",
                frame, bufferIndex, width, height, finite, count, min, max,
                data.get(quarter), data.get(center), data.get(Math.max(0, count - 1 - quarter)));
    }
}
*///?}
