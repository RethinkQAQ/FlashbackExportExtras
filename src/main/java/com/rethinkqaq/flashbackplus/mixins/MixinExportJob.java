package com.rethinkqaq.flashbackplus.mixins;

import com.mojang.blaze3d.platform.NativeImage;
import com.moulberry.flashback.combo_options.VideoContainer;
import com.moulberry.flashback.exporting.*;
import com.rethinkqaq.flashbackplus.FlashbackPlusConfig;
import com.rethinkqaq.flashbackplus.Flashbackplus;
import com.rethinkqaq.flashbackplus.exporting.CameraPathExporter;
import com.rethinkqaq.flashbackplus.exporting.DepthCaptureState;
import com.rethinkqaq.flashbackplus.exporting.ExrVideoWriter;
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

    @Unique
    private CameraPathExporter cameraExporter;

    @Unique
    private boolean isExrMode;

    // === Redirect createVideoWriter: return ExrVideoWriter for EXR mode ===

    @Redirect(method = "run",
            at = @At(value = "INVOKE",
                    target = "Lcom/moulberry/flashback/exporting/ExportJob;createVideoWriter(Lcom/moulberry/flashback/exporting/ExportSettings;Ljava/lang/String;)Lcom/moulberry/flashback/exporting/VideoWriter;"),
            remap = false)
    private VideoWriter redirectCreateWriter(ExportSettings settings, String tempFileName) throws IOException {
        if (FlashbackPlusConfig.INSTANCE.exportAsExr) {
            Path outputDir = settings.output();
            int w = settings.resolutionX();
            int h = settings.resolutionY();
            return new ExrVideoWriter(outputDir, w, h);
        }
        if (settings.container() == VideoContainer.PNG_SEQUENCE) {
            return new PNGSequenceVideoWriter(settings);
        } else {
            return new AsyncFFmpegVideoWriter(settings, tempFileName);
        }
    }

    // === doExport HEAD: setup depth capture + camera exporter ===

    @Inject(method = "doExport", at = @At("HEAD"), remap = false)
    private void onDoExportStart(VideoWriter videoWriter, SaveableFramebufferQueue downloader,
                                  CallbackInfo ci) {
        isExrMode = FlashbackPlusConfig.INSTANCE.exportAsExr;
        DepthCaptureState.reset();

        ExportJob self = (ExportJob) (Object) this;

        if (isExrMode) {
            DepthCaptureState.width = self.getWidth();
            DepthCaptureState.height = self.getHeight();
            DepthCaptureState.active = true;
            Flashbackplus.LOGGER.info("EXR depth capture: {}x{}",
                    DepthCaptureState.width, DepthCaptureState.height);
        }

        if (FlashbackPlusConfig.INSTANCE.exportCameraPath) {
            float aspectRatio = (float) settings.resolutionX() / (float) settings.resolutionY();
            cameraExporter = new CameraPathExporter(aspectRatio, settings.framerate(),
                    FlashbackPlusConfig.INSTANCE.cameraPathRelativeOrigin);
        }
    }

    // === Frame-level camera capture: inject after startDownload in doExport loop ===
    //
    // At this point the frame has been rendered, currentTickDouble is updated,
    // and keyframe FOV (via MixinFOVKeyframe → keyframeTargetFov) has been evaluated.
    // We interpolate FOV between server ticks using partialClientTick to eliminate
    // the staircase effect that occurs when exporting at >20fps.

    @Inject(method = "doExport",
            at = @At(value = "INVOKE",
                    target = "Lcom/moulberry/flashback/exporting/SaveableFramebufferQueue;startDownload(Lcom/mojang/blaze3d/pipeline/RenderTarget;Lcom/moulberry/flashback/exporting/SaveableFramebuffer;Z)V",
                    shift = At.Shift.AFTER),
            remap = false)
    private void onStartDownloadAfter(VideoWriter videoWriter, SaveableFramebufferQueue downloader,
                                       CallbackInfo ci) {
        if (cameraExporter == null) return;

        // Compute sub-tick position for smooth FOV interpolation
        double partialClientTick = currentTickDouble - (int) currentTickDouble;

        float targetFov = DepthCaptureState.keyframeTargetFov;
        float previousFov = DepthCaptureState.previousFov;

        // Linear interpolation between server ticks
        float interpolatedFov = (float) (previousFov + (targetFov - previousFov) * partialClientTick);

        Vec3 pos = new Vec3(DepthCaptureState.camX, DepthCaptureState.camY, DepthCaptureState.camZ);
        cameraExporter.recordFrame(pos, DepthCaptureState.camYaw, DepthCaptureState.camPitch, interpolatedFov);

        // Update previousFov for next frame (track the keyframe target, not interpolated)
        DepthCaptureState.previousFov = targetFov;
    }

    // === Redirect VideoWriter.encode: skip recording (done above), just handle EXR ===

    @Redirect(method = "submitDownloadedFrames",
            at = @At(value = "INVOKE",
                    target = "Lcom/moulberry/flashback/exporting/VideoWriter;encode(Lcom/mojang/blaze3d/platform/NativeImage;Ljava/nio/FloatBuffer;)V"),
            remap = false)
    private void onVideoEncode(VideoWriter videoWriter, NativeImage image, FloatBuffer audioBuffer) {
        if (isExrMode) {
            videoWriter.encode(image, audioBuffer);
        } else {
            videoWriter.encode(image, audioBuffer);
        }
    }

    // === doExport RETURN: apply FOV smoothing + finalize camera path + cleanup ===

    @Inject(method = "doExport", at = @At("RETURN"), remap = false)
    private void onDoExportReturn(VideoWriter videoWriter, SaveableFramebufferQueue downloader,
                                   CallbackInfo ci) {
        if (cameraExporter != null && cameraExporter.getFrameCount() > 0) {
            // Apply Gaussian smoothing to eliminate residual micro-jitter
            cameraExporter.applyGaussianSmoothing();

            Path videoPath = settings.output();
            String videoName = videoPath.getFileName().toString();
            int dot = videoName.lastIndexOf('.');
            String base = dot > 0 ? videoName.substring(0, dot) : videoName;
            Path glbPath = videoPath.resolveSibling(base + "_camera.glb");

            try {
                cameraExporter.finish(glbPath);
                Flashbackplus.LOGGER.info("Camera path: {} frames → {}",
                        cameraExporter.getFrameCount(), glbPath);
            } catch (IOException e) {
                Flashbackplus.LOGGER.error("Failed to write camera path GLB", e);
            }
        }

        DepthCaptureState.reset();
        cameraExporter = null;
        isExrMode = false;
    }
}
