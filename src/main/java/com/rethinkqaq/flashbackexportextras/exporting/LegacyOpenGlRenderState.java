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
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/** Saves and restores state touched by the legacy full-screen HDR passes. */
final class LegacyOpenGlRenderState implements AutoCloseable {
    private final int drawFramebuffer;
    private final int readFramebuffer;
    private final int program;
    private final int vertexArray;
    private final int arrayBuffer;
    private final int activeTexture;
    private final int texture0;
    private final int[] viewport = new int[4];
    private final boolean blend;
    private final boolean cullFace;
    private final boolean depthTest;
    private final boolean scissorTest;
    private final boolean depthMask;
    private final boolean colorMaskRed;
    private final boolean colorMaskGreen;
    private final boolean colorMaskBlue;
    private final boolean colorMaskAlpha;
    private boolean closed;

    private LegacyOpenGlRenderState() {
        drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        texture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(activeTexture);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);

        blend = GL11.glIsEnabled(GL11.GL_BLEND);
        cullFace = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        scissorTest = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);

        ByteBuffer colorMask = MemoryUtil.memAlloc(4);
        try {
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorMask);
            colorMaskRed = colorMask.get(0) != 0;
            colorMaskGreen = colorMask.get(1) != 0;
            colorMaskBlue = colorMask.get(2) != 0;
            colorMaskAlpha = colorMask.get(3) != 0;
        } finally {
            MemoryUtil.memFree(colorMask);
        }
    }

    static LegacyOpenGlRenderState capture() {
        return new LegacyOpenGlRenderState();
    }

    void bindTextureForInspection(int texture) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    }

    void beginFullscreenPass(int framebuffer, int width, int height,
                             int shaderProgram, int sourceTexture, int passVertexArray) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL11.glViewport(0, 0, width, height);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDepthMask(false);
        GL11.glColorMask(true, true, true, true);
        GL20.glUseProgram(shaderProgram);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, sourceTexture);
        GL30.glBindVertexArray(passVertexArray);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;

        GL30.glBindVertexArray(vertexArray);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
        GL20.glUseProgram(program);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture0);
        GL13.glActiveTexture(activeTexture);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        GL11.glDepthMask(depthMask);
        GL11.glColorMask(colorMaskRed, colorMaskGreen, colorMaskBlue, colorMaskAlpha);
        restoreCapability(GL11.GL_BLEND, blend);
        restoreCapability(GL11.GL_CULL_FACE, cullFace);
        restoreCapability(GL11.GL_DEPTH_TEST, depthTest);
        restoreCapability(GL11.GL_SCISSOR_TEST, scissorTest);
    }

    private static void restoreCapability(int capability, boolean enabled) {
        if (enabled) {
            GL11.glEnable(capability);
        } else {
            GL11.glDisable(capability);
        }
    }
}

//?}
