package com.rethinkqaq.flashbackexportextras.gpu;

//? if <26.1 {

import com.mojang.blaze3d.pipeline.RenderTarget;

/** Transitional backend for pre-26.1 versions. */
public final class LegacyOpenGlExportBackend implements GpuExportBackend {
    /*? if hdr {*/
    private com.rethinkqaq.flashbackexportextras.exporting.HdrColorTransformShader hdrShader;
    private com.rethinkqaq.flashbackexportextras.exporting.HdrFrameCapture hdrCapture;
    private com.rethinkqaq.flashbackexportextras.exporting.SceneLinearHdrShader sceneLinearShader;
    private com.rethinkqaq.flashbackexportextras.exporting.SceneLinearHdrPboCapture sceneLinearCapture;
    private boolean hdrReadbackFailed;
    private boolean sceneLinearReadbackFailed;
    /*?}*/

    @Override public boolean supportsHdr() {
        /*? if hdr {*/
        return true;
        /*?} else {*/
        /*return false;
        *//*?}*/
    }
    @Override public boolean supportsSceneLinearHdr() {
        /*? if hdr {*/
        return true;
        /*?} else {*/
        /*return false;
        *//*?}*/
    }
    @Override public void captureDepth(RenderTarget target, int width, int height, float depthFar) {}
    @Override
    public void captureHdr(RenderTarget target, int width, int height,
                           float peakBrightness, long frameId) {
        /*? if hdr {*/
        if (target == null || hdrReadbackFailed) return;
        try {
            if (hdrShader == null) hdrShader = new com.rethinkqaq.flashbackexportextras.exporting.HdrColorTransformShader();
            if (hdrCapture == null) hdrCapture = new com.rethinkqaq.flashbackexportextras.exporting.HdrFrameCapture();
            int hdrTexture = hdrShader.render(colorTextureId(target), peakBrightness);
            hdrCapture.issueReadback(hdrTexture, width, height, frameId);
        } catch (RuntimeException e) {
            hdrReadbackFailed = true;
            com.rethinkqaq.flashbackexportextras.exporting.HdrVideoCaptureState.fail(e);
            com.rethinkqaq.flashbackexportextras.Flashbackplus.LOGGER.error(
                    "OpenGL HDR10 capture failed for frame " + frameId, e);
        }
        /*?}*/
    }

    @Override
    public void captureSceneLinearHdr(RenderTarget target, int width, int height, long frameId) {
        /*? if hdr {*/
        if (target == null || sceneLinearReadbackFailed) return;
        try {
            if (sceneLinearShader == null) sceneLinearShader = new com.rethinkqaq.flashbackexportextras.exporting.SceneLinearHdrShader();
            if (sceneLinearCapture == null) sceneLinearCapture = new com.rethinkqaq.flashbackexportextras.exporting.SceneLinearHdrPboCapture();
            int linearTexture = sceneLinearShader.render(colorTextureId(target));
            sceneLinearCapture.issue(linearTexture, width, height, frameId);
        } catch (RuntimeException e) {
            sceneLinearReadbackFailed = true;
            com.rethinkqaq.flashbackexportextras.exporting.SceneLinearHdrCaptureState.fail(e);
            com.rethinkqaq.flashbackexportextras.Flashbackplus.LOGGER.error(
                    "OpenGL scene-linear HDR capture failed for frame " + frameId, e);
        }
        /*?}*/
    }

    @Override public void endFrame() {
        /*? if hdr {*/
        if (hdrCapture != null) hdrCapture.collectReady(0L);
        if (sceneLinearCapture != null) sceneLinearCapture.collectReady(0L);
        /*?}*/
    }

    @Override public void flush() {
        /*? if hdr {*/
        if (hdrCapture != null) hdrCapture.flush();
        if (sceneLinearCapture != null) sceneLinearCapture.flush();
        /*?}*/
    }

    @Override public void close() {
        /*? if hdr {*/
        if (hdrCapture != null) hdrCapture.close();
        if (sceneLinearCapture != null) sceneLinearCapture.close();
        if (hdrShader != null) hdrShader.close();
        if (sceneLinearShader != null) sceneLinearShader.close();
        hdrCapture = null;
        sceneLinearCapture = null;
        hdrShader = null;
        sceneLinearShader = null;
        hdrReadbackFailed = false;
        sceneLinearReadbackFailed = false;
        /*?}*/
    }

    /*? if hdr {*/
    private static int colorTextureId(RenderTarget target) {
        /*? if >=1.21.5 {*/
        /*return ((com.mojang.blaze3d.opengl.GlTexture) target.getColorTexture()).glId();
        *//*?} else {*/
        return target.getColorTextureId();
        /*?}*/
    }
    /*?}*/
}
//?}
