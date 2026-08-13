package com.rethinkqaq.flashbackexportextras.exporting;

//? if hdr {

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
/** Owns frame-numbered HDR10 readbacks until the video writer can consume them. */
public final class HdrVideoCaptureState {
    private static final FrameIndexedQueue<Frame> QUEUE = new FrameIndexedQueue<>(
            "HDR10", frame -> frame.frameId, frame -> MemoryUtil.memFree(frame.data));

    private HdrVideoCaptureState() {}

    public static void submit(long frameId, ByteBuffer data) {
        if (data == null) return;
        QUEUE.submit(new Frame(frameId, data));
    }

    public static Frame poll(long expectedFrameId) {
        return QUEUE.poll(expectedFrameId);
    }

    public static void verifyComplete(long expectedFrameCount, long writtenFrameCount) {
        QUEUE.verifyComplete(expectedFrameCount, writtenFrameCount);
    }

    public static void fail(Throwable throwable) {
        QUEUE.fail(throwable);
    }

    public static void throwIfFailed() {
        QUEUE.throwIfFailed();
    }

    public static void reset() {
        QUEUE.reset();
    }

    public static final class Frame {
        public final long frameId;
        public final ByteBuffer data;

        private Frame(long frameId, ByteBuffer data) {
            this.frameId = frameId;
            this.data = data;
        }
    }
}
//?}
