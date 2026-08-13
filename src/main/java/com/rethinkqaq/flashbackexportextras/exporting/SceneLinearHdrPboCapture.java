package com.rethinkqaq.flashbackexportextras.exporting;

//? if legacy_hdr {

import org.lwjgl.opengl.GL30;

/** Frame-numbered RGBA16F readback for legacy OpenGL scene-linear export. */
public final class SceneLinearHdrPboCapture implements AutoCloseable {
    private final OpenGlFrameReadback readback = new OpenGlFrameReadback(
            "scene-linear HDR", GL30.GL_HALF_FLOAT, SceneLinearHdrCaptureState::submit);

    public void issue(int textureId, int width, int height, long frameId) {
        readback.issue(textureId, width, height, frameId);
    }

    public void collectReady(long timeoutNanos) {
        readback.collectReady(timeoutNanos);
    }

    public void flush() {
        readback.flush();
    }

    @Override
    public void close() {
        readback.close();
    }
}

//?}
