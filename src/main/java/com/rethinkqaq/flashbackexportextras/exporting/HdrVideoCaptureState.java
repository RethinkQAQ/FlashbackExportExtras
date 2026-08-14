/*
 * Flashback Export Extras
 * Copyright (C) RethinkQAQ
 *
 * This file is part of Flashback Export Extras.
 *
 * Flashback Export Extras is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Flashback Export Extras is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with Flashback Export Extras. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
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
