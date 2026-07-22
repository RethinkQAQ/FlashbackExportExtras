package com.rethinkqaq.flashbackplus.mixins;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.moulberry.flashback.combo_options.VideoContainer;
import com.moulberry.flashback.exporting.*;
import com.rethinkqaq.flashbackplus.FlashbackPlusConfig;
import com.rethinkqaq.flashbackplus.Flashbackplus;
import com.rethinkqaq.flashbackplus.exporting.*;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Path;

@Mixin(value = ExportJob.class, remap = false)
public class MixinExportJob {

    @Shadow
    private ExportSettings settings;

    @Shadow
    private double currentTickDouble;

    @Unique
    private CameraPathExporter cameraExporter;

    @Unique
    private boolean isExrMode;

    @Unique
    private boolean isHdrMode;

    @Unique
    private HdrColorTransformShader hdrColorShader;

    @Unique
    private HdrFrameCapture hdrFrameCapture;

    @Unique
    private HdrVideoWriter hdrWriterRef;

    // === Redirect createVideoWriter ===

    @Redirect(method = "run",
            at = @At(value = "INVOKE",
                    target = "Lcom/moulberry/flashback/exporting/ExportJob;createVideoWriter(Lcom/moulberry/flashback/exporting/ExportSettings;Ljava/lang/String;)Lcom/moulberry/flashback/exporting/VideoWriter;"),
            remap = false)
    private VideoWriter redirectCreateWriter(ExportSettings settings, String tempFileName) throws IOException {
        isHdrMode = FlashbackPlusConfig.INSTANCE.hdrExport && HdrExportState.isAvailable();
        isExrMode = FlashbackPlusConfig.INSTANCE.exportAsExr && !isHdrMode;

        if (isExrMode) {
            Path outputDir = settings.output();
            int w = settings.resolutionX();
            int h = settings.resolutionY();
            return new ExrVideoWriter(outputDir, w, h);
        }
        if (isHdrMode) {
            Path tempPath = java.nio.file.Path.of(tempFileName);
            int w = settings.resolutionX();
            int h = settings.resolutionY();
            Flashbackplus.LOGGER.info("HDR export temp: {} → final: {}", tempPath, settings.output());
            hdrWriterRef = new HdrVideoWriter(tempPath, w, h, settings.framerate());
            return hdrWriterRef;
        }
        if (settings.container() == VideoContainer.PNG_SEQUENCE) {
            return new PNGSequenceVideoWriter(settings);
        } else {
            return new AsyncFFmpegVideoWriter(settings, tempFileName);
        }
    }

    // === doExport HEAD ===

    @Inject(method = "doExport", at = @At("HEAD"), remap = false)
    private void onDoExportStart(VideoWriter videoWriter, SaveableFramebufferQueue downloader,
                                  CallbackInfo ci) {
        isExrMode = FlashbackPlusConfig.INSTANCE.exportAsExr;
        isHdrMode = FlashbackPlusConfig.INSTANCE.hdrExport && HdrExportState.isAvailable();
        DepthCaptureState.reset();

        ExportJob self = (ExportJob) (Object) this;

        if (isExrMode) {
            DepthCaptureState.width = self.getWidth();
            DepthCaptureState.height = self.getHeight();
            DepthCaptureState.active = true;
        }

        if (isHdrMode) {
            int w = self.getWidth();
            int h = self.getHeight();
            HdrExportState.width = w;
            HdrExportState.height = h;
            HdrExportState.setPeakBrightness((float) FlashbackPlusConfig.INSTANCE.hdrPeakBrightness);
            HdrExportState.activate();
            hdrColorShader = new HdrColorTransformShader();
            hdrFrameCapture = new HdrFrameCapture();
            Flashbackplus.LOGGER.info("HDR export: {}x{} peak={}nits", w, h, HdrExportState.getPeakBrightness());
        }

        if (FlashbackPlusConfig.INSTANCE.exportCameraPath) {
            float aspectRatio = (float) settings.resolutionX() / (float) settings.resolutionY();
            cameraExporter = new CameraPathExporter(aspectRatio, settings.framerate(),
                    FlashbackPlusConfig.INSTANCE.cameraPathRelativeOrigin);
        }
    }

    // === HDR color transform + capture: inject BEFORE startDownload ===
    //
    // Injects before the startDownload call in doExport(VideoWriter, SaveableFramebufferQueue).
    // Mixin provides closing-method params here, so we use Minecraft.getMainRenderTarget()
    // to access the render target instead of trying to capture locals.

    @Inject(method = "doExport",
            at = @At(value = "INVOKE",
                    target = "Lcom/moulberry/flashback/exporting/SaveableFramebufferQueue;startDownload(Lcom/mojang/blaze3d/pipeline/RenderTarget;Lcom/moulberry/flashback/exporting/SaveableFramebuffer;Z)V"),
            remap = false)
    private void beforeStartDownload(VideoWriter videoWriter, SaveableFramebufferQueue downloader,
                                      CallbackInfo ci) {
        if (!isHdrMode || hdrColorShader == null || hdrFrameCapture == null) return;

        // Get the main render target (MC renders into this during export)
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        com.mojang.blaze3d.pipeline.RenderTarget target = mc.getMainRenderTarget();
        if (target == null) return;

        // Step 1: Color transform — scRGB-nl → BT.2020 + PQ
        int srcTexId = target.getColorTextureId();
        float peak = HdrExportState.getPeakBrightness();
        int hdrTexId = hdrColorShader.render(srcTexId, peak);

        // Step 2: Async 16-bit PBO readback
        hdrFrameCapture.issueReadback(hdrTexId);

        // Step 3: Collect ready frames
        java.nio.ByteBuffer hdrData = hdrFrameCapture.tryCollect();
        if (hdrData != null) {
            if (hdrWriterRef != null) {
                hdrWriterRef.addHdrFrame(hdrData);
            } else {
                HdrExportState.enqueueFrame(hdrData);
            }
        }
    }

    // === Camera capture: inject AFTER startDownload ===

    @Inject(method = "doExport",
            at = @At(value = "INVOKE",
                    target = "Lcom/moulberry/flashback/exporting/SaveableFramebufferQueue;startDownload(Lcom/mojang/blaze3d/pipeline/RenderTarget;Lcom/moulberry/flashback/exporting/SaveableFramebuffer;Z)V",
                    shift = At.Shift.AFTER),
            remap = false)
    private void onStartDownloadAfter(VideoWriter videoWriter, SaveableFramebufferQueue downloader,
                                       CallbackInfo ci) {
        if (cameraExporter == null) return;

        double partialClientTick = currentTickDouble - (int) currentTickDouble;
        float targetFov = DepthCaptureState.keyframeTargetFov;
        float previousFov = DepthCaptureState.previousFov;
        float interpolatedFov = (float) (previousFov + (targetFov - previousFov) * partialClientTick);

        Vec3 pos = new Vec3(DepthCaptureState.camX, DepthCaptureState.camY, DepthCaptureState.camZ);
        cameraExporter.recordFrame(pos, DepthCaptureState.camYaw, DepthCaptureState.camPitch, interpolatedFov);
        DepthCaptureState.previousFov = targetFov;
    }

    // === Redirect VideoWriter.encode ===

    @Redirect(method = "submitDownloadedFrames",
            at = @At(value = "INVOKE",
                    target = "Lcom/moulberry/flashback/exporting/VideoWriter;encode(Lcom/mojang/blaze3d/platform/NativeImage;Ljava/nio/FloatBuffer;)V"),
            remap = false)
    private void onVideoEncode(VideoWriter videoWriter, NativeImage image, FloatBuffer audioBuffer) {
        videoWriter.encode(image, audioBuffer);
    }

    // === doExport RETURN: cleanup ===

    @Inject(method = "doExport", at = @At("RETURN"), remap = false)
    private void onDoExportReturn(VideoWriter videoWriter, SaveableFramebufferQueue downloader,
                                   CallbackInfo ci) {
        if (cameraExporter != null && cameraExporter.getFrameCount() > 0) {
            cameraExporter.applyGaussianSmoothing();
            Path videoPath = settings.output();
            String videoName = videoPath.getFileName().toString();
            int dot = videoName.lastIndexOf('.');
            String base = dot > 0 ? videoName.substring(0, dot) : videoName;
            Path glbPath = videoPath.resolveSibling(base + "_camera.glb");
            try {
                cameraExporter.finish(glbPath);
                Flashbackplus.LOGGER.info("Camera path: {} frames → {}", cameraExporter.getFrameCount(), glbPath);
            } catch (IOException e) {
                Flashbackplus.LOGGER.error("Failed to write camera path GLB", e);
            }
        }

        // Drain remaining HDR frames
        if (isHdrMode && hdrFrameCapture != null) {
            ByteBuffer remaining = hdrFrameCapture.collect();
            if (remaining != null && hdrWriterRef != null) {
                hdrWriterRef.addHdrFrame(remaining);
            }
            // Also drain any buffered frames
            while ((remaining = hdrFrameCapture.tryCollect()) != null) {
                if (hdrWriterRef != null) hdrWriterRef.addHdrFrame(remaining);
            }
        }

        // HDR cleanup
        if (hdrColorShader != null) {
            hdrColorShader.close();
            hdrColorShader = null;
        }
        if (hdrFrameCapture != null) {
            hdrFrameCapture.close();
            hdrFrameCapture = null;
        }
        HdrExportState.deactivate();

        DepthCaptureState.reset();
        cameraExporter = null;
        isExrMode = false;
        isHdrMode = false;
        hdrWriterRef = null;
    }
}
