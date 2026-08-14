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
