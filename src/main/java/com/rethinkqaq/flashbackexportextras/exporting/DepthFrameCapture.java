/*
 * Flashback Export Extras
 * Copyright (C) RethinkQAQ
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package com.rethinkqaq.flashbackexportextras.exporting;

//? if <26.1 {

import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/** Asynchronous legacy OpenGL depth readback with explicit export frame IDs. */
public final class DepthFrameCapture implements AutoCloseable {
    private final OpenGlFrameReadback readback = new OpenGlFrameReadback(
            "depth", GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, Float.BYTES,
            this::submit);

    public void issueReadback(int textureId, int width, int height, long frameId,
                              float zNear, float zFar, DepthCaptureState.Encoding encoding,
                              String source) {
        final float near = zNear;
        final float far = zFar;
        final DepthCaptureState.Encoding depthEncoding = encoding;
        final String depthSource = source;
        // Metadata is attached in submit(), where the readback bytes become a
        // frame. A map is required because up to three frames can be in flight.
        metadataByFrame.put(frameId, new Metadata(near, far, depthEncoding, depthSource));
        try {
            readback.issue(textureId, width, height, frameId);
        } catch (RuntimeException e) {
            metadataByFrame.remove(frameId);
            throw e;
        }
    }

    private final Map<Long, Metadata> metadataByFrame = new HashMap<>();

    private void submit(long frameId, ByteBuffer bytes) {
        Metadata metadata = metadataByFrame.remove(frameId);
        if (metadata == null) {
            throw new IllegalStateException("Missing metadata for depth frame " + frameId);
        }
        ByteBuffer source = bytes.duplicate().order(ByteOrder.nativeOrder());
        source.rewind();
        java.nio.FloatBuffer copy = DepthCaptureState.acquireBuffer();
        java.nio.FloatBuffer floats = source.asFloatBuffer();
        try {
            copy.put(floats);
            copy.rewind();
            DepthCaptureState.submit(new DepthCaptureState.DepthFrame(
                    frameId, copy, metadata.zNear, metadata.zFar,
                    metadata.encoding, metadata.source));
            copy = null;
        } finally {
            if (copy != null) DepthCaptureState.releaseBuffer(copy);
        }
    }

    public void collectReady(long timeoutNanos) {
        readback.collectReady(timeoutNanos);
    }

    public void flush() {
        readback.flush();
    }

    public boolean release() {
        return readback.release();
    }

    @Override
    public void close() {
        readback.close();
        metadataByFrame.clear();
    }

    private record Metadata(float zNear, float zFar,
                            DepthCaptureState.Encoding encoding, String source) {}
}

//?}
