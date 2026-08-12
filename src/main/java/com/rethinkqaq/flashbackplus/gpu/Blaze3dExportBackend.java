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
    private final boolean[] depthReversed = new boolean[BUFFER_COUNT];
    private final String[] depthSources = new String[BUFFER_COUNT];
    /^? if <26.2 {^/
    /*private int pendingWorldDepthIndex = -1;
    ^//^?}^/
    private int writeIndex;
    private int width;
    private int height;
    private boolean depthReadbackFailed;
    private int depthDebugFrame;
    /^? if >=26.2 {^/
    /^private GpuTexture hdrCopyTexture;
    private GpuTextureView hdrCopyView;
    private RenderPipeline hdrCopyPipeline;
    private GpuBuffer hdrReadbackBuffer;
    private GpuBuffer hdrUniformBuffer;
    ^//^?}^/
    @Override public boolean supportsHdr() {
        /^? if >=26.2 {^/
        /^return true;
        ^//^?} else {^/
        return false;
        /^?}^/
    }
    @Override
    public void snapshotDepth(RenderTarget target, int width, int height, float depthFar) {
        /^? if <26.2 {^/
        /*if (target == null || !target.useDepth || target.getDepthTexture() == null
                || depthReadbackFailed || pendingWorldDepthIndex >= 0) return;
        try {
            RenderSystem.assertOnRenderThread();
            if (!ensureDepthBuffers(width, height)) return;
            int index = writeIndex;
            if (depthFences[index] != null) {
                collectDepth(index, 1_000_000_000L);
                if (depthFences[index] != null) return;
            }
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.copyTextureToBuffer(target.getDepthTexture(), depthBuffers[index], 0L, () -> {}, 0);
            depthFrameIds[index] = -1L;
            depthReversed[index] = false;
            depthSources[index] = "Minecraft 26.1 pre-clear depth";
            depthFences[index] = encoder.createFence();
            pendingWorldDepthIndex = index;
            writeIndex = (writeIndex + 1) % BUFFER_COUNT;
        } catch (RuntimeException e) {
            depthReadbackFailed = true;
            closeDepthBuffers();
            com.rethinkqaq.flashbackplus.Flashbackplus.LOGGER.error(
                    "26.1 pre-clear depth snapshot failed", e);
        }
        ^//^?}^/
    }

    @Override
    public void captureDepth(RenderTarget target, int width, int height, float depthFar) {
        /^? if >=26.2 {^/
        /^if (target == null || !target.useDepth || target.getDepthTexture() == null || depthReadbackFailed) return;
        try {
            RenderSystem.assertOnRenderThread();
            if (!ensureDepthBuffers(width, height)) return;
            collectDepth();
            int index = writeIndex;
            if (depthFences[index] != null) {
                collectDepth(index, 1_000_000_000L);
                if (depthFences[index] != null) {
                    com.rethinkqaq.flashbackplus.Flashbackplus.LOGGER.warn(
                            "Depth GPU readback buffer {} is still busy; skipping frame {}",
                            index, DepthCaptureState.captureFrameId());
                    return;
                }
            }
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.copyTextureToBuffer(target.getDepthTexture(), depthBuffers[index], 0L, () -> {}, 0);
            depthFrameIds[index] = DepthCaptureState.captureFrameId();
            // Minecraft 26.2 normally renders with reversed-Z. Iris keeps the
            // shaderpack-facing main depth in the standard OpenGL convention.
            // The optional Iris Mixin marks only frames where a shaderpack
            // pipeline actually ran, so merely installing Iris changes nothing.
            depthReversed[index] = !DepthCaptureState.irisShaderPackRenderedThisFrame;
            depthSources[index] = depthReversed[index]
                    ? "Minecraft main depth (reversed-Z)"
                    : "Iris shaderpack main depth (standard-Z)";
            depthFences[index] = encoder.createFence();
            encoder.submit();
            collectDepth(index, 1_000_000_000L);
            writeIndex = (writeIndex + 1) % BUFFER_COUNT;
        } catch (RuntimeException e) {
            depthReadbackFailed = true;
            closeDepthBuffers();
            com.rethinkqaq.flashbackplus.Flashbackplus.LOGGER.error(
                    "Blaze3D direct depth readback failed; continuing without depth", e);
        }
        ^//^?} else {^/
        if (target == null || !target.useDepth || target.getDepthTexture() == null) return;
        if (depthReadbackFailed) return;
        try {
            RenderSystem.assertOnRenderThread();
            if (!ensureDepthBuffers(width, height)) return;
            /^? if <26.2 {^/
            /*if (pendingWorldDepthIndex >= 0) {
                int index = pendingWorldDepthIndex;
                pendingWorldDepthIndex = -1;
                depthFrameIds[index] = DepthCaptureState.captureFrameId();
                collectDepth(index, 1_000_000_000L);
                if (depthFences[index] != null) {
                    com.rethinkqaq.flashbackplus.Flashbackplus.LOGGER.warn(
                            "26.1 pre-clear depth readback did not complete for frame {}",
                            depthFrameIds[index]);
                }
                return;
            }
            ^//^?}^/
            collectDepth();
            int index = writeIndex;
            if (depthFences[index] != null) return;
            GpuTexture texture = target.getDepthTexture();
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.copyTextureToBuffer(texture, depthBuffers[index], 0L, () -> {}, 0);
            depthFrameIds[index] = DepthCaptureState.captureFrameId();
            depthReversed[index] = false;
            depthSources[index] = "Minecraft 26.1 main depth (standard-Z)";
            depthFences[index] = encoder.createFence();
            collectDepth(index, 1_000_000_000L);
            writeIndex = (writeIndex + 1) % BUFFER_COUNT;
        } catch (RuntimeException e) {
            depthReadbackFailed = true;
            closeDepthBuffers();
            com.rethinkqaq.flashbackplus.Flashbackplus.LOGGER.error(
                    "Blaze3D depth readback unavailable; continuing without depth", e);
        }
        /^?}^/
    }

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
            try {
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
                }
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
                // String locations default to minecraft:, which Iris treats as
                // an overridable vanilla program. Keep our utility pipeline
                // in this mod's namespace.
                .withLocation(ResourceLocation.fromNamespaceAndPath(
                        "flashbackplus", "hdr_color_transform_blaze"))
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
    private boolean ensureDepthBuffers(int newWidth, int newHeight) {
        if (width == newWidth && height == newHeight && depthBuffers[0] != null) return true;
        if (!closeDepthBuffers()) {
            com.rethinkqaq.flashbackplus.Flashbackplus.LOGGER.warn(
                    "Deferring depth-buffer resize until pending GPU copies complete");
            return false;
        }
        width = newWidth;
        height = newHeight;
        long size = (long) newWidth * newHeight * 4L;
        for (int i = 0; i < BUFFER_COUNT; i++) {
            depthBuffers[i] = RenderSystem.getDevice().createBuffer(
                    () -> "Flashback Plus depth readback",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                    size);
        }
        return true;
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
                float depth = source.get(i);
                copy.put(depthReversed[index] ? normalizeDepth(depth) : depth);
            }
            copy.rewind();
            logDepthReadback(source, copy, index);
            synchronized (DepthCaptureState.depthQueue) {
                DepthCaptureState.depthQueue.addLast(
                        new DepthCaptureState.DepthFrame(depthFrameIds[index], copy));
            }
        } finally {
            fence.close();
            depthFences[index] = null;
        }
        ^//^?} elif >=26.1 {^/
        /^try (GpuBuffer.MappedView mapped = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(depthBuffers[index], true, false)) {
                ByteBuffer data = mapped.data().duplicate().order(ByteOrder.LITTLE_ENDIAN);
                data.rewind();
                var copy = DepthCaptureState.acquireBuffer();
                java.nio.FloatBuffer source = data.asFloatBuffer();
                for (int i = 0; i < source.remaining(); i++) {
                    float depth = source.get(i);
                    copy.put(depthReversed[index] ? normalizeDepth(depth) : depth);
                }
                copy.rewind();
                logDepthReadback(source, copy, index);
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
        return 1.0f - depth;
    }

    private boolean closeDepthBuffers() {
        boolean pending = false;
        for (int i = 0; i < BUFFER_COUNT; i++) {
            if (depthFences[i] != null && !depthFences[i].awaitCompletion(0L)) {
                pending = true;
                continue;
            }
            if (depthFences[i] != null) depthFences[i].close();
            if (depthBuffers[i] != null) depthBuffers[i].close();
            depthFences[i] = null;
            depthBuffers[i] = null;
            depthFrameIds[i] = -1L;
            depthReversed[i] = false;
            depthSources[i] = null;
        }
        if (!pending) writeIndex = 0;
        /^? if <26.2 {^/
        /*if (!pending) pendingWorldDepthIndex = -1;
        ^//^?}^/
        return !pending;
    }

    @Override public void endFrame() { collectDepth(); }

    @Override
    public void flush() {
        for (int i = 0; i < BUFFER_COUNT; i++) {
            if (depthFences[i] != null) collectDepth(i, 1_000_000_000L);
        }
    }
    @Override public boolean releaseOnRenderThread() {
        if (!RenderSystem.isOnRenderThread()) return false;
        if (!closeDepthBuffers()) return false;
        /^? if >=26.2 {^/
        /^closeHdrTarget();
        ^//^?}^/
        return true;
    }

    @Override public void close() {
        releaseOnRenderThread();
    }

    private void logDepthReadback(java.nio.FloatBuffer raw, java.nio.FloatBuffer converted, int bufferIndex) {
        int frame = depthDebugFrame++;
        if (frame >= 3 && frame % 30 != 0) return;

        float rawMin = Float.POSITIVE_INFINITY;
        float rawMax = Float.NEGATIVE_INFINITY;
        float convertedMin = Float.POSITIVE_INFINITY;
        float convertedMax = Float.NEGATIVE_INFINITY;
        int rawFinite = 0;
        int convertedFinite = 0;
        int count = converted.remaining();
        for (int i = 0; i < count; i++) {
            float rawValue = raw.get(i);
            if (Float.isFinite(rawValue)) {
                rawMin = Math.min(rawMin, rawValue);
                rawMax = Math.max(rawMax, rawValue);
                rawFinite++;
            }
            float convertedValue = converted.get(i);
            if (Float.isFinite(convertedValue)) {
                convertedMin = Math.min(convertedMin, convertedValue);
                convertedMax = Math.max(convertedMax, convertedValue);
                convertedFinite++;
            }
        }

        int center = Math.max(0, Math.min(count - 1, (height / 2) * width + width / 2));
        int quarter = Math.max(0, Math.min(count - 1, (height / 4) * width + width / 4));
        int thirdQuarter = Math.max(0, count - 1 - quarter);
        com.rethinkqaq.flashbackplus.Flashbackplus.LOGGER.info(
                "26.2 depth readback #{}: source={}, buffer={}, size={}x{}, "
                        + "raw[finite={}/{}, min={}, max={}, q1={}, center={}, q3={}], "
                        + "standard[finite={}/{}, min={}, max={}, q1={}, center={}, q3={}]",
                frame, depthSources[bufferIndex], bufferIndex, width, height,
                rawFinite, count, rawMin, rawMax,
                raw.get(quarter), raw.get(center), raw.get(thirdQuarter),
                convertedFinite, count, convertedMin, convertedMax,
                converted.get(quarter), converted.get(center), converted.get(thirdQuarter));
    }
}
*///?}
