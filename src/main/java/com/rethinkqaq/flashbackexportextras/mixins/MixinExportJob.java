package com.rethinkqaq.flashbackexportextras.mixins;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.moulberry.flashback.combo_options.VideoContainer;
import com.moulberry.flashback.exporting.*;
import com.rethinkqaq.flashbackexportextras.FlashbackPlusConfig;
import com.rethinkqaq.flashbackexportextras.FlashbackPlusConfig.ExportMode;
import com.rethinkqaq.flashbackexportextras.Flashbackplus;
import com.rethinkqaq.flashbackexportextras.exporting.*;
import com.rethinkqaq.flashbackexportextras.gpu.GpuExportBackendFactory;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Path;

@Mixin(value = ExportJob.class, remap = false)
public class MixinExportJob {

    @Shadow
    private ExportSettings settings;

    @Shadow
    private double currentTickDouble;

    @Shadow
    private void doExport(VideoWriter videoWriter, SaveableFramebufferQueue downloader) {
        throw new AssertionError("Mixin shadow");
    }

    @Unique
    private CameraPathExporter cameraExporter;

    @Unique
    private boolean isExrMode;

    @Unique
    private boolean isExrSceneLinearHdr;

    @Unique
    private boolean isHdrMode;

    @Unique
    private int flashbackplus_originalDummyFrames;

    @Unique
    private boolean flashbackplus_dummyFramesOverridden;

    @Unique
    private boolean flashbackplus_sessionActive;

    /*? if hdr {*/
    @Unique
    private HdrVideoWriter hdrWriterRef;

    @Unique
    private long flashbackplus_hdrCaptureFrameCount;
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
        flashbackplus$configureExportModes();
        if (isExrMode && FlashbackPlusConfig.INSTANCE.exrSceneLinearHdr && !isExrSceneLinearHdr) {
            Flashbackplus.LOGGER.warn(
                    "Scene-linear HDR EXR is unavailable in this runtime; exporting standard SDR color");
        }
        if (isExrMode) {
            Path outputDir = settings.output();
            int w = settings.resolutionX();
            int h = settings.resolutionY();
            return new ExrVideoWriter(outputDir, w, h, isExrSceneLinearHdr);
        }
        /*? if hdr {*/
        if (isHdrMode) {
            Path tempPath = java.nio.file.Path.of(tempFileName);
            int w = settings.resolutionX();
            int h = settings.resolutionY();
            Flashbackplus.LOGGER.info("HDR export temporary path: {}, final path: {}", tempPath, settings.output());
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

    @Unique
    private void flashbackplus$configureExportModes() {
        /*? if hdr {*/
        isHdrMode = FlashbackPlusConfig.INSTANCE.getExportMode() == ExportMode.HDR10
                && HdrExportState.isAvailable() && GpuExportBackendFactory.get().supportsHdr();
        /*?} else {*/
        /*isHdrMode = false;
        *//*?}*/
        isExrMode = FlashbackPlusConfig.INSTANCE.getExportMode() == ExportMode.EXR;
        isExrSceneLinearHdr = isExrMode && FlashbackPlusConfig.INSTANCE.exrSceneLinearHdr
                && HdrExportState.isAvailable()
                && GpuExportBackendFactory.get().supportsSceneLinearHdr();
    }

    // === doExport HEAD ===

    @Inject(method = "doExport", at = @At("HEAD"), remap = false)
    private void onDoExportStart(VideoWriter videoWriter, SaveableFramebufferQueue downloader,
                                  CallbackInfo ci) {
        Flashbackplus.LOGGER.info("ExportJob doExport started: writer={}",
                videoWriter == null ? "null" : videoWriter.getClass().getName());
        flashbackplus_sessionActive = true;
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
        SceneLinearHdrCaptureState.reset();
        /*? if hdr {*/
        HdrVideoCaptureState.reset();
        flashbackplus_hdrCaptureFrameCount = 0L;
        /*?}*/

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
            Flashbackplus.LOGGER.info("HDR export: {}x{} peak={}nits", w, h, HdrExportState.getPeakBrightness());
        }
        /*?}*/

        if (FlashbackPlusConfig.INSTANCE.exportCameraPath) {
            float aspectRatio = (float) settings.resolutionX() / (float) settings.resolutionY();
            cameraExporter = new CameraPathExporter(aspectRatio, settings.framerate(),
                    FlashbackPlusConfig.INSTANCE.cameraPathRelativeOrigin);
        }
    }

    // === Capture depth from the same RenderTarget as this color download ===

    @Redirect(method = "doExport",
            at = @At(value = "INVOKE",
                    target = "Lcom/moulberry/flashback/exporting/SaveableFramebufferQueue;startDownload(Lcom/mojang/blaze3d/pipeline/RenderTarget;Lcom/moulberry/flashback/exporting/SaveableFramebuffer;Z)V"),
            remap = false)
    private void flashbackplus$captureAndStartDownload(SaveableFramebufferQueue downloader,
                                                        RenderTarget target,
                                                        SaveableFramebuffer framebuffer,
                                                        boolean flag) {
        if (isExrMode) {
            long frameId = DepthCaptureState.nextExportFrameId();
            GameRendererDepthAccess renderer =
                    (GameRendererDepthAccess) (Object) net.minecraft.client.Minecraft.getInstance().gameRenderer;
            renderer.flashbackplus_captureDepthForFrame(target, frameId);
            if (isExrSceneLinearHdr) {
                GpuExportBackendFactory.get().captureSceneLinearHdr(
                        target, target.width, target.height, frameId);
            }
        }
        flashbackplus$captureHdrBeforeDownload(target);
        downloader.startDownload(target, framebuffer, flag);
        flashbackplus$recordCameraFrame();
    }

    /** HDR capture shares the depth redirect so the two injectors cannot consume the same invocation. */
    @Unique
    private void flashbackplus$captureHdrBeforeDownload(RenderTarget target) {
        /*? if hdr {*/
        if (!isHdrMode) return;
        if (target == null) return;

        float peak = HdrExportState.getPeakBrightness();
        long frameId = flashbackplus_hdrCaptureFrameCount++;
        GpuExportBackendFactory.get().captureHdr(
                target, target.width, target.height, peak, frameId);
        flashbackplus$drainHdrFrames();

        // Step 1: Color transform — scRGB-nl → BT.2020 + PQ

        /*?}*/
    }

    @Unique
    private void flashbackplus$drainHdrFrames() {
        /*? if hdr {*/
        while (hdrWriterRef != null) {
            HdrVideoCaptureState.Frame frame =
                    HdrVideoCaptureState.poll(hdrWriterRef.getFrameCount());
            if (frame == null) break;
            hdrWriterRef.addHdrFrame(frame.frameId, frame.data);
        }
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
        if (!isExrMode && !isHdrMode) return;
        Flashbackplus.LOGGER.info("Export GPU flush started: exr={}, hdr={}", isExrMode, isHdrMode);
        GpuExportBackendFactory.get().flush();
        /*? if hdr {*/
        if (isHdrMode) {
            flashbackplus$drainHdrFrames();
            long written = hdrWriterRef == null ? 0L : hdrWriterRef.getFrameCount();
            HdrVideoCaptureState.verifyComplete(flashbackplus_hdrCaptureFrameCount, written);
        }
        /*?}*/
        if (isExrMode) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            com.rethinkqaq.flashbackexportextras.exporting.GameRendererDepthAccess renderer =
                    (com.rethinkqaq.flashbackexportextras.exporting.GameRendererDepthAccess) (Object) mc.gameRenderer;
            renderer.flashbackplus_flushDepthPbo();
        }
        Flashbackplus.LOGGER.info("Export GPU flush completed");
    }

    // === Camera capture: called immediately AFTER startDownload ===

    @Unique
    private void flashbackplus$recordCameraFrame() {
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

    // === Export session finalization ===

    @Redirect(method = "run",
            at = @At(value = "INVOKE",
                    target = "Lcom/moulberry/flashback/exporting/ExportJob;doExport(Lcom/moulberry/flashback/exporting/VideoWriter;Lcom/moulberry/flashback/exporting/SaveableFramebufferQueue;)V"),
            remap = false)
    private void flashbackplus$runExportSession(ExportJob instance, VideoWriter videoWriter,
                                                SaveableFramebufferQueue downloader) {
        boolean completed = false;
        try {
            doExport(videoWriter, downloader);
            completed = true;
        } finally {
            flashbackplus$finishExportSession(completed);
        }
    }

    @Unique
    private void flashbackplus$finishExportSession(boolean completed) {
        if (!flashbackplus_sessionActive) return;
        Flashbackplus.LOGGER.info("Export session cleanup started: completed={}", completed);
        if (completed && cameraExporter != null && cameraExporter.getFrameCount() > 0) {
            try {
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
            } catch (Throwable e) {
                Flashbackplus.LOGGER.error("Failed to finalize camera path GLB", e);
            }
        }

        flashbackplus$cleanupStep("HDR export state", () -> {
            /*? if hdr {*/
            HdrExportState.deactivate();
            /*?}*/
        });
        flashbackplus$cleanupStep("depth frame state", DepthCaptureState::reset);
        flashbackplus$cleanupStep("scene-linear HDR frame state", SceneLinearHdrCaptureState::reset);
        flashbackplus$cleanupStep("HDR10 frame state", () -> {
            /*? if hdr {*/
            HdrVideoCaptureState.reset();
            flashbackplus_hdrCaptureFrameCount = 0L;
            /*?}*/
        });
        flashbackplus$cleanupStep("GPU backend", GpuExportBackendFactory::reset);
        if (flashbackplus_dummyFramesOverridden) {
            flashbackplus$cleanupStep("Flashback warm-up frame setting", () ->
                    com.moulberry.flashback.Flashback.getConfig().exporting.exportRenderDummyFrames =
                            flashbackplus_originalDummyFrames);
            flashbackplus_dummyFramesOverridden = false;
        }
        cameraExporter = null;
        isExrMode = false;
        isExrSceneLinearHdr = false;
        isHdrMode = false;
        /*? if hdr {*/
        hdrWriterRef = null;
        /*?}*/
        flashbackplus_sessionActive = false;
        Flashbackplus.LOGGER.info("Export session cleanup completed");
    }

    @Unique
    private void flashbackplus$cleanupStep(String name, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (Throwable e) {
            Flashbackplus.LOGGER.error("Export cleanup step failed: {}", name, e);
        }
    }
}
