package com.rethinkqaq.flashbackplus.mixins;

import com.mojang.blaze3d.pipeline.RenderTarget;
/*? if >=1.21.5 {*/
/*import com.mojang.blaze3d.opengl.GlTexture;
*//*?}*/
import com.mojang.blaze3d.platform.NativeImage;
import com.moulberry.flashback.combo_options.VideoContainer;
import com.moulberry.flashback.exporting.*;
import com.rethinkqaq.flashbackplus.FlashbackPlusConfig;
import com.rethinkqaq.flashbackplus.Flashbackplus;
import com.rethinkqaq.flashbackplus.exporting.*;
import com.rethinkqaq.flashbackplus.gpu.GpuExportBackendFactory;
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
    private int flashbackplus_originalDummyFrames;

    @Unique
    private boolean flashbackplus_dummyFramesOverridden;

    /*? if hdr {*/
    @Unique
    private HdrColorTransformShader hdrColorShader;

    @Unique
    private HdrFrameCapture hdrFrameCapture;

    @Unique
    private HdrVideoWriter hdrWriterRef;
    /*?}*/

    // === Redirect createVideoWriter ===

    @Redirect(method = "run",
            at = @At(value = "INVOKE",
                    target = "Lcom/moulberry/flashback/exporting/ExportJob;createVideoWriter(Lcom/moulberry/flashback/exporting/ExportSettings;Ljava/lang/String;)Lcom/moulberry/flashback/exporting/VideoWriter;"),
            remap = false)
    private VideoWriter redirectCreateWriter(ExportSettings settings, String tempFileName) throws IOException {
        Flashbackplus.LOGGER.info(
                "ExportJob creating writer: output={}, container={}, resolution={}x{}, temp={}",
                settings.output(), settings.container(), settings.resolutionX(), settings.resolutionY(), tempFileName);
        /*? if hdr {*/
        isHdrMode = FlashbackPlusConfig.INSTANCE.hdrExport && HdrExportState.isAvailable();
        /*?} else {*/
        /*isHdrMode = false;
        *//*?}*/
        isExrMode = FlashbackPlusConfig.INSTANCE.exportAsExr && !isHdrMode;

        if (isExrMode) {
            Path outputDir = settings.output();
            int w = settings.resolutionX();
            int h = settings.resolutionY();
            return new ExrVideoWriter(outputDir, w, h);
        }
        /*? if hdr {*/
        if (isHdrMode) {
            Path tempPath = java.nio.file.Path.of(tempFileName);
            int w = settings.resolutionX();
            int h = settings.resolutionY();
            Flashbackplus.LOGGER.info("HDR export temp: {} → final: {}", tempPath, settings.output());
            int bitrate = settings.bitrate() > 0
                    ? settings.bitrate()
                    : Math.min(288_000_000,
                            5_000 + (int) Math.ceil(w * (double) h * settings.framerate()));
            hdrWriterRef = new HdrVideoWriter(tempPath, w, h, settings.framerate(), bitrate);
            return hdrWriterRef;
        }
        /*?}*/
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
        Flashbackplus.LOGGER.info("ExportJob doExport started: writer={}",
                videoWriter == null ? "null" : videoWriter.getClass().getName());
        /*? if hdr {*/
        isHdrMode = FlashbackPlusConfig.INSTANCE.hdrExport && HdrExportState.isAvailable();
        /*?} else {*/
        /*isHdrMode = false;
        *//*?}*/
        // Keep the mode decision identical to redirectCreateWriter. HDR takes
        // precedence, so it must not accidentally activate depth capture.
        isExrMode = FlashbackPlusConfig.INSTANCE.exportAsExr && !isHdrMode;
        if (isExrMode) {
            com.moulberry.flashback.configuration.FlashbackConfigV1 config =
                    com.moulberry.flashback.Flashback.getConfig();
            flashbackplus_originalDummyFrames = config.exporting.exportRenderDummyFrames;
            config.exporting.exportRenderDummyFrames = 0;
            flashbackplus_dummyFramesOverridden = true;
            Flashbackplus.LOGGER.info("EXR export: disabled {} Flashback warm-up frame(s)",
                    flashbackplus_originalDummyFrames);
        }
        DepthCaptureState.reset();

        ExportJob self = (ExportJob) (Object) this;

        if (isExrMode) {
            DepthCaptureState.width = self.getWidth();
            DepthCaptureState.height = self.getHeight();
            DepthCaptureState.active = true;
        }

        /*? if hdr {*/
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
        /*?}*/

        if (FlashbackPlusConfig.INSTANCE.exportCameraPath) {
            float aspectRatio = (float) settings.resolutionX() / (float) settings.resolutionY();
            cameraExporter = new CameraPathExporter(aspectRatio, settings.framerate(),
                    FlashbackPlusConfig.INSTANCE.cameraPathRelativeOrigin);
        }
    }

    // === Bind the latest pre-clear world-depth snapshot to this color download ===

    @Redirect(method = "doExport",
            at = @At(value = "INVOKE",
                    target = "Lcom/moulberry/flashback/exporting/SaveableFramebufferQueue;startDownload(Lnet/minecraft/class_276;Lcom/moulberry/flashback/exporting/SaveableFramebuffer;Z)V"),
            remap = false)
    private void captureDepthBeforeStartDownload(SaveableFramebufferQueue downloader,
                                                  RenderTarget target,
                                                  SaveableFramebuffer framebuffer,
                                                  boolean flag) {
        if (isExrMode) {
            long frameId = DepthCaptureState.nextExportFrameId();
            GameRendererDepthAccess renderer =
                    (GameRendererDepthAccess) (Object) net.minecraft.client.Minecraft.getInstance().gameRenderer;
            renderer.flashbackplus_captureDepthForFrame(target, frameId);
        }
        downloader.startDownload(target, framebuffer, flag);
    }

    @Inject(method = "doExport",
            at = @At(value = "INVOKE",
                    target = "Lcom/moulberry/flashback/exporting/SaveableFramebufferQueue;startDownload(Lnet/minecraft/class_276;Lcom/moulberry/flashback/exporting/SaveableFramebuffer;Z)V"),
            remap = false)
    private void beforeStartDownload(VideoWriter videoWriter, SaveableFramebufferQueue downloader,
                                      CallbackInfo ci) {
        /*? if hdr {*/
        if (!isHdrMode) return;

        // Get the main render target (MC renders into this during export)
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        /*? if >=26.2 {*/
        /*com.mojang.blaze3d.pipeline.RenderTarget target = mc.gameRenderer.mainRenderTarget();
        *//*?} else {*/
        com.mojang.blaze3d.pipeline.RenderTarget target = mc.getMainRenderTarget();
        /*?}*/
        if (target == null) return;

        float peak = HdrExportState.getPeakBrightness();
        /*? if >=26.2 {*/
        /*java.nio.ByteBuffer hdrData = GpuExportBackendFactory.get().captureHdr(
                target, target.width, target.height, peak);
        if (hdrData != null && hdrWriterRef != null) hdrWriterRef.addHdrFrame(hdrData);
        *//*?}*/
        /*? if <26.2 {*/

        // Step 1: Color transform — scRGB-nl → BT.2020 + PQ
        /*? if >=1.21.5 {*/
        /*int srcTexId = ((GlTexture) target.getColorTexture()).glId();
        *//*?} else {*/
        int srcTexId = target.getColorTextureId();
        /*?}*/
        int hdrTexId = hdrColorShader.render(srcTexId, peak);

        // Step 2: Async 16-bit PBO readback
        hdrFrameCapture.issueReadback(hdrTexId);

        // Step 3: Collect ready frames
        java.nio.ByteBuffer hdrData = hdrFrameCapture.tryCollect();
        if (hdrData != null && hdrWriterRef != null) {
            hdrWriterRef.addHdrFrame(hdrData);
        }
        /*?}*/
        /*?}*/
    }

    /*? if >=1.21.5 {*/
    /*@Inject(method = "doExport",
            at = @At(value = "INVOKE",
                    target = "Lcom/moulberry/flashback/exporting/VideoWriter;finish(Ljava/util/function/Consumer;)V"),
            remap = false)
    private void flashbackplus$flushGpuBeforeFinish(VideoWriter videoWriter,
                                                     SaveableFramebufferQueue downloader,
                                                     CallbackInfo ci) {
        flashbackplusFlushGpuReadback();
    }
    *//*?} else {*/
    /*@Inject(method = "doExport",
            at = @At(value = "INVOKE",
                    target = "Lcom/moulberry/flashback/exporting/VideoWriter;finish()V"),
            remap = false)
    private void flashbackplus$flushGpuBeforeFinish(VideoWriter videoWriter,
                                                     SaveableFramebufferQueue downloader,
                                                     CallbackInfo ci) {
        flashbackplusFlushGpuReadback();
    }
    *//*?}*/

    @Unique
    private void flashbackplusFlushGpuReadback() {
        Flashbackplus.LOGGER.info("EXR finish: GPU flush started");
        GpuExportBackendFactory.get().flush();
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        com.rethinkqaq.flashbackplus.exporting.GameRendererDepthAccess renderer =
                (com.rethinkqaq.flashbackplus.exporting.GameRendererDepthAccess) (Object) mc.gameRenderer;
        renderer.flashbackplus_flushDepthPbo();
        Flashbackplus.LOGGER.info("EXR finish: GPU flush completed");
    }

    // === Camera capture: inject AFTER startDownload ===

    @Inject(method = "doExport",
            at = @At(value = "INVOKE",
                    target = "Lcom/moulberry/flashback/exporting/SaveableFramebufferQueue;startDownload(Lnet/minecraft/class_276;Lcom/moulberry/flashback/exporting/SaveableFramebuffer;Z)V",
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
        /*? if hdr {*/
        if (isHdrMode) {
            // Normal pipeline's NativeImage is unused in HDR mode.
            // Explicitly free to prevent native memory accumulation (GC finalizer is too slow).
            image.close();
        } else {
            videoWriter.encode(image, audioBuffer);
        }
        /*?} else {*/
        /*videoWriter.encode(image, audioBuffer);
        *//*?}*/
    }

    // === doExport RETURN: cleanup ===

    @Inject(method = "doExport", at = @At("RETURN"), remap = false)
    private void onDoExportReturn(VideoWriter videoWriter, SaveableFramebufferQueue downloader,
                                   CallbackInfo ci) {
        Flashbackplus.LOGGER.info("EXR finish: doExport returned, starting cleanup");
        if (cameraExporter != null && cameraExporter.getFrameCount() > 0) {
            cameraExporter.applyGaussianSmoothing();
            Path videoPath = settings.output();
            String videoName = videoPath.getFileName().toString();
            int dot = videoName.lastIndexOf('.');
            String base = dot > 0 ? videoName.substring(0, dot) : videoName;
            Path glbPath = isExrMode
                    ? videoPath.resolve("camera.glb")
                    : videoPath.resolveSibling(base + "_camera.glb");
            try {
                cameraExporter.finish(glbPath);
                Flashbackplus.LOGGER.info("Camera path: {} frames → {}", cameraExporter.getFrameCount(), glbPath);
            } catch (IOException e) {
                Flashbackplus.LOGGER.error("Failed to write camera path GLB", e);
            }
        }

        // Drain remaining HDR frames
        /*? if hdr {*/
        /*? if <26.2 {*/
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
        /*?}*/
        /*?}*/

        /*? if hdr {*/
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
        /*?}*/

        DepthCaptureState.reset();
        GpuExportBackendFactory.reset();
        if (flashbackplus_dummyFramesOverridden) {
            com.moulberry.flashback.Flashback.getConfig().exporting.exportRenderDummyFrames =
                    flashbackplus_originalDummyFrames;
            flashbackplus_dummyFramesOverridden = false;
        }
        cameraExporter = null;
        isExrMode = false;
        isHdrMode = false;
        /*? if hdr {*/
        hdrWriterRef = null;
        /*?}*/
        Flashbackplus.LOGGER.info("EXR finish: cleanup completed");
    }
}
