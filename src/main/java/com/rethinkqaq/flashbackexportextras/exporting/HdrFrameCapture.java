package com.rethinkqaq.flashbackexportextras.exporting;

//? if legacy_hdr {

import org.lwjgl.opengl.GL11;

/** Frame-numbered RGBA16 readback for legacy OpenGL HDR10 export. */
public final class HdrFrameCapture implements AutoCloseable {
    private final OpenGlFrameReadback readback = new OpenGlFrameReadback(
            "HDR10", GL11.GL_UNSIGNED_SHORT, HdrVideoCaptureState::submit);

    public void issueReadback(int textureId, int width, int height, long frameId) {
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
