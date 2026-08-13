package com.rethinkqaq.flashbackexportextras.exporting;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
/** Owns scene-linear RGBA16F frames until the matching EXR color frame arrives. */
public final class SceneLinearHdrCaptureState {
    private static final FrameIndexedQueue<ColorFrame> QUEUE = new FrameIndexedQueue<>(
            "scene-linear HDR", frame -> frame.frameId, frame -> release(frame.data));

    private SceneLinearHdrCaptureState() {}

    public static void submit(long frameId, ByteBuffer data) {
        if (data == null) return;
        QUEUE.submit(new ColorFrame(frameId, data));
    }

    public static ColorFrame poll(long expectedFrameId) {
        return QUEUE.poll(expectedFrameId);
    }

    public static int size() {
        return QUEUE.size();
    }

    public static void fail(Throwable throwable) {
        QUEUE.fail(throwable);
    }

    public static void release(ByteBuffer data) {
        if (data != null) MemoryUtil.memFree(data);
    }

    public static void reset() {
        QUEUE.reset();
    }

    public static final class ColorFrame {
        public final long frameId;
        public final ByteBuffer data;

        public ColorFrame(long frameId, ByteBuffer data) {
            this.frameId = frameId;
            this.data = data;
        }
    }
}
