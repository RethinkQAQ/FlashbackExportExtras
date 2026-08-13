package com.rethinkqaq.flashbackexportextras.gpu;

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
import com.rethinkqaq.flashbackexportextras.exporting.DepthCaptureState;
import com.rethinkqaq.flashbackexportextras.exporting.HdrExportState;
import com.rethinkqaq.flashbackexportextras.exporting.HdrVideoCaptureState;
import com.rethinkqaq.flashbackexportextras.exporting.SceneLinearHdrCaptureState;

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
    private HdrMod26_1ExportBridge hdrModBridge;
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
    private GpuTexture sceneLinearTexture;
    private GpuTextureView sceneLinearView;
    private RenderPipeline sceneLinearPipeline;
    private final GpuBuffer[] sceneLinearBuffers = new GpuBuffer[BUFFER_COUNT];
    private final GpuFence[] sceneLinearFences = new GpuFence[BUFFER_COUNT];
    private final long[] sceneLinearFrameIds = new long[BUFFER_COUNT];
    private int sceneLinearWriteIndex;
    private int sceneLinearWidth;
    private int sceneLinearHeight;
    private boolean sceneLinearReadbackFailed;
    ^//^?}^/
    @Override public boolean supportsHdr() {
        /^? if >=26.2 {^/
        /^return true;
        ^//^?} else {^/
        return HdrExportState.isHdrModLoaded();
        /^?}^/
    }
    @Override public boolean supportsSceneLinearHdr() {
        /^? if >=26.2 {^/
        /^return true;
        ^//^?} else {^/
        return HdrExportState.isHdrModLoaded();
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
            com.rethinkqaq.flashbackexportextras.Flashbackplus.LOGGER.error(
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
                    com.rethinkqaq.flashbackexportextras.Flashbackplus.LOGGER.warn(
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
            com.rethinkqaq.flashbackexportextras.Flashbackplus.LOGGER.error(
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
                    com.rethinkqaq.flashbackexportextras.Flashbackplus.LOGGER.warn(
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
            com.rethinkqaq.flashbackexportextras.Flashbackplus.LOGGER.error(
                    "Blaze3D depth readback unavailable; continuing without depth", e);
        }
        /^?}^/
    }

    @Override
    public void captureHdr(RenderTarget target, int width, int height,
                           float peakBrightness, long frameId) {
        /^? if >=26.2 {^/
        /^if (target == null || target.getColorTexture() == null || target.getColorTextureView() == null) return;
        try {
            RenderSystem.assertOnRenderThread();
            ensureHdrTarget(width, height);
            ByteBuffer parameters = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
            parameters.putFloat(peakBrightness).putFloat(0.0f).putFloat(0.0f).putFloat(0.0f).flip();

            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.writeToBuffer(hdrUniformBuffer.slice(), parameters);
            try (RenderPass pass = encoder.createRenderPass(
                    () -> "Flashback Export Extras HDR10 colour transform", hdrCopyView, java.util.Optional.empty())) {
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
                    throw new IllegalStateException("HDR GPU readback did not complete in time for frame " + frameId);
                }
                try (GpuBufferSlice.MappedView mapped = hdrReadbackBuffer.slice().map(true, false)) {
                    ByteBuffer source = mapped.data().duplicate();
                    source.rewind();
                    ByteBuffer result = MemoryUtil.memAlloc(width * height * 8);
                    try {
                        result.put(source);
                        result.rewind();
                        HdrVideoCaptureState.submit(frameId, result);
                        result = null;
                    } finally {
                        if (result != null) MemoryUtil.memFree(result);
                    }
                }
            } finally {
                fence.close();
            }
        } catch (RuntimeException e) {
            HdrVideoCaptureState.fail(e);
            com.rethinkqaq.flashbackexportextras.Flashbackplus.LOGGER.error(
                    "Blaze3D HDR capture failed for frame " + frameId, e);
        }
        ^//^?} else {^/
        if (target == null || target.getColorTexture() == null || target.getColorTextureView() == null) return;
        try {
            if (hdrModBridge == null) hdrModBridge = new HdrMod26_1ExportBridge();
            hdrModBridge.captureHdr(target, width, height, peakBrightness, frameId);
        } catch (RuntimeException e) {
            HdrVideoCaptureState.fail(e);
            com.rethinkqaq.flashbackexportextras.Flashbackplus.LOGGER.error(
                    "26.1 HDR capture failed for frame " + frameId, e);
        }
        /^?}^/
    }

    @Override
    public void captureSceneLinearHdr(RenderTarget target, int width, int height, long frameId) {
        /^? if >=26.2 {^/
        /^if (target == null || target.getColorTexture() == null
                || target.getColorTextureView() == null || sceneLinearReadbackFailed) return;
        try {
            RenderSystem.assertOnRenderThread();
            ensureSceneLinearTarget(width, height);
            collectSceneLinearReady(0L);

            int index = sceneLinearWriteIndex;
            if (sceneLinearFences[index] != null
                    && !collectSceneLinear(index, 1_000_000_000L)) {
                throw new IllegalStateException(
                        "Timed out waiting for scene-linear HDR buffer " + index);
            }

            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            try (RenderPass pass = encoder.createRenderPass(
                    () -> "Flashback Export Extras scene-linear HDR transform",
                    sceneLinearView, java.util.Optional.empty())) {
                pass.setPipeline(sceneLinearPipeline);
                pass.bindTexture("InSampler", target.getColorTextureView(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.draw(3, 1, 0, 0);
            }
            encoder.copyTextureToBuffer(sceneLinearTexture, sceneLinearBuffers[index], 0L, () -> {}, 0);
            sceneLinearFrameIds[index] = frameId;
            sceneLinearFences[index] = encoder.createFence();
            encoder.submit();
            sceneLinearWriteIndex = (sceneLinearWriteIndex + 1) % BUFFER_COUNT;
        } catch (RuntimeException e) {
            sceneLinearReadbackFailed = true;
            SceneLinearHdrCaptureState.fail(e);
            com.rethinkqaq.flashbackexportextras.Flashbackplus.LOGGER.error(
                    "Blaze3D scene-linear HDR capture failed for frame " + frameId, e);
        }
        ^//^?} else {^/
        if (target == null || target.getColorTexture() == null || target.getColorTextureView() == null) return;
        try {
            if (hdrModBridge == null) hdrModBridge = new HdrMod26_1ExportBridge();
            hdrModBridge.captureSceneLinear(target, width, height, frameId);
        } catch (RuntimeException e) {
            SceneLinearHdrCaptureState.fail(e);
            com.rethinkqaq.flashbackexportextras.Flashbackplus.LOGGER.error(
                    "26.1 scene-linear HDR capture failed for frame " + frameId, e);
        }
        /^?}^/
    }

    /^? if >=26.2 {^/
    /^private void ensureHdrTarget(int newWidth, int newHeight) {
        if (hdrCopyTexture != null && hdrCopyTexture.getWidth(0) == newWidth
                && hdrCopyTexture.getHeight(0) == newHeight) return;
        closeHdrTarget();
        long size = (long) newWidth * newHeight * 8L;
        hdrCopyTexture = RenderSystem.getDevice().createTexture(
                "Flashback Export Extras HDR10 colour transform",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                GpuFormat.RGBA16_UNORM, newWidth, newHeight, 1, 1);
        hdrCopyView = RenderSystem.getDevice().createTextureView(hdrCopyTexture);
        hdrReadbackBuffer = RenderSystem.getDevice().createBuffer(
                () -> "Flashback Export Extras HDR10 readback",
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, size);
        hdrUniformBuffer = RenderSystem.getDevice().createBuffer(
                () -> "Flashback Export Extras HDR10 parameters",
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

    private void ensureSceneLinearTarget(int newWidth, int newHeight) {
        if (sceneLinearTexture != null && sceneLinearWidth == newWidth
                && sceneLinearHeight == newHeight) return;
        flushSceneLinearHdr();
        if (!closeSceneLinearTarget()) {
            throw new IllegalStateException("Scene-linear HDR resources are still in use");
        }

        sceneLinearWidth = newWidth;
        sceneLinearHeight = newHeight;
        long size = (long) newWidth * newHeight * 8L;
        sceneLinearTexture = RenderSystem.getDevice().createTexture(
                "Flashback Export Extras scene-linear HDR transform",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                GpuFormat.RGBA16_FLOAT, newWidth, newHeight, 1, 1);
        sceneLinearView = RenderSystem.getDevice().createTextureView(sceneLinearTexture);
        for (int i = 0; i < BUFFER_COUNT; i++) {
            sceneLinearBuffers[i] = RenderSystem.getDevice().createBuffer(
                    () -> "Flashback Export Extras scene-linear HDR readback",
                    GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, size);
            sceneLinearFrameIds[i] = -1L;
        }
        sceneLinearPipeline = RenderPipeline.builder()
                .withLocation(ResourceLocation.fromNamespaceAndPath(
                        "flashbackplus", "scene_linear_hdr_blaze"))
                .withVertexShader(ResourceLocation.fromNamespaceAndPath(
                        "flashbackplus", "core/flashbackplus_hdr_color_transform_blaze"))
                .withFragmentShader(ResourceLocation.fromNamespaceAndPath(
                        "flashbackplus", "core/flashbackplus_scene_linear_hdr_blaze"))
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withSampler("InSampler")
                        .build())
                .withColorTargetState(new ColorTargetState(java.util.Optional.empty(),
                        GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .build();
    }

    private void collectSceneLinearReady(long timeoutNanos) {
        for (int i = 0; i < BUFFER_COUNT; i++) {
            if (sceneLinearFences[i] != null) collectSceneLinear(i, timeoutNanos);
        }
    }

    private boolean collectSceneLinear(int index, long timeoutNanos) {
        GpuFence fence = sceneLinearFences[index];
        if (fence == null) return true;
        if (!fence.awaitCompletion(timeoutNanos)) return false;
        try (GpuBufferSlice.MappedView mapped = sceneLinearBuffers[index].slice().map(true, false)) {
            int expected = sceneLinearWidth * sceneLinearHeight * 8;
            ByteBuffer source = mapped.data().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            source.rewind();
            if (source.remaining() < expected) {
                throw new IllegalStateException("Scene-linear HDR readback is too small: "
                        + source.remaining() + " < " + expected);
            }
            source.limit(expected);
            ByteBuffer result = MemoryUtil.memAlloc(expected);
            try {
                result.put(source);
                result.rewind();
                SceneLinearHdrCaptureState.submit(sceneLinearFrameIds[index], result);
                result = null;
            } finally {
                if (result != null) MemoryUtil.memFree(result);
            }
        } finally {
            fence.close();
            sceneLinearFences[index] = null;
            sceneLinearFrameIds[index] = -1L;
        }
        return true;
    }

    private void flushSceneLinearHdr() {
        for (int i = 0; i < BUFFER_COUNT; i++) {
            if (sceneLinearFences[i] != null
                    && !collectSceneLinear(i, 1_000_000_000L)) {
                throw new IllegalStateException("Timed out flushing scene-linear HDR frame "
                        + sceneLinearFrameIds[i]);
            }
        }
    }

    private boolean closeSceneLinearTarget() {
        for (GpuFence fence : sceneLinearFences) {
            if (fence != null && !fence.awaitCompletion(0L)) return false;
        }
        for (int i = 0; i < BUFFER_COUNT; i++) {
            if (sceneLinearFences[i] != null) sceneLinearFences[i].close();
            if (sceneLinearBuffers[i] != null) sceneLinearBuffers[i].close();
            sceneLinearFences[i] = null;
            sceneLinearBuffers[i] = null;
            sceneLinearFrameIds[i] = -1L;
        }
        if (sceneLinearView != null) sceneLinearView.close();
        if (sceneLinearTexture != null) sceneLinearTexture.close();
        sceneLinearView = null;
        sceneLinearTexture = null;
        sceneLinearPipeline = null;
        sceneLinearWidth = sceneLinearHeight = 0;
        sceneLinearWriteIndex = 0;
        return true;
    }
    ^//^?}^/
    private boolean ensureDepthBuffers(int newWidth, int newHeight) {
        if (width == newWidth && height == newHeight && depthBuffers[0] != null) return true;
        if (!closeDepthBuffers()) {
            com.rethinkqaq.flashbackexportextras.Flashbackplus.LOGGER.warn(
                    "Deferring depth-buffer resize until pending GPU copies complete");
            return false;
        }
        width = newWidth;
        height = newHeight;
        long size = (long) newWidth * newHeight * 4L;
        for (int i = 0; i < BUFFER_COUNT; i++) {
            depthBuffers[i] = RenderSystem.getDevice().createBuffer(
                    () -> "Flashback Export Extras depth readback",
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
            DepthCaptureState.submit(new DepthCaptureState.DepthFrame(depthFrameIds[index], copy));
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
                DepthCaptureState.submit(new DepthCaptureState.DepthFrame(depthFrameIds[index], copy));
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

    @Override public void endFrame() {
        collectDepth();
        /^? if <26.2 {^/
        /*if (hdrModBridge != null) hdrModBridge.collectReady();
        ^//^?}^/
    }

    @Override
    public void flush() {
        for (int i = 0; i < BUFFER_COUNT; i++) {
            if (depthFences[i] != null) collectDepth(i, 1_000_000_000L);
        }
        /^? if >=26.2 {^/
        /^flushSceneLinearHdr();
        ^//^?}^/
        /^? if <26.2 {^/
        /*if (hdrModBridge != null) hdrModBridge.flush();
        ^//^?}^/
    }
    @Override public boolean releaseOnRenderThread() {
        if (!RenderSystem.isOnRenderThread()) return false;
        if (!closeDepthBuffers()) return false;
        /^? if >=26.2 {^/
        /^if (!closeSceneLinearTarget()) return false;
        closeHdrTarget();
        ^//^?} else {^/
        if (hdrModBridge != null && !hdrModBridge.release()) return false;
        hdrModBridge = null;
        /^?}^/
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
        com.rethinkqaq.flashbackexportextras.Flashbackplus.LOGGER.info(
                "Blaze3D depth readback #{}: source={}, buffer={}, size={}x{}, "
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
