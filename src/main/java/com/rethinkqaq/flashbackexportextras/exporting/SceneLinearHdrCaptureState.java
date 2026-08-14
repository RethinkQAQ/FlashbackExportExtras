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
