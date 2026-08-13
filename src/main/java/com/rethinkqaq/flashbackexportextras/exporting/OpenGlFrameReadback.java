package com.rethinkqaq.flashbackexportextras.exporting;

//? if legacy_hdr {

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.function.BiConsumer;

/** Shared frame-numbered PBO/fence readback used by legacy OpenGL HDR paths. */
final class OpenGlFrameReadback implements AutoCloseable {
    private static final int BUFFER_COUNT = 3;
    private static final long WAIT_NANOS = 1_000_000_000L;

    private final String label;
    private final int pixelType;
    private final BiConsumer<Long, ByteBuffer> frameConsumer;
    private final int[] pboIds = new int[BUFFER_COUNT];
    private final long[] fences = new long[BUFFER_COUNT];
    private final long[] frameIds = new long[BUFFER_COUNT];
    private int width = -1;
    private int height = -1;
    private int writeIndex;

    OpenGlFrameReadback(String label, int pixelType, BiConsumer<Long, ByteBuffer> frameConsumer) {
        this.label = label;
        this.pixelType = pixelType;
        this.frameConsumer = frameConsumer;
    }

    void issue(int textureId, int newWidth, int newHeight, long frameId) {
        RenderSystem.assertOnRenderThread();
        ensureResources(newWidth, newHeight);
        collectReady(0L);

        int index = writeIndex;
        if (fences[index] != 0L && !collect(index, WAIT_NANOS)) {
            throw new IllegalStateException("Timed out waiting for " + label + " PBO " + index);
        }

        int oldTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int oldPbo = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pboIds[index]);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, pixelType, 0L);
            frameIds[index] = frameId;
            fences[index] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldTexture);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, oldPbo);
        }
        writeIndex = (writeIndex + 1) % BUFFER_COUNT;
    }

    void collectReady(long timeoutNanos) {
        for (int i = 0; i < BUFFER_COUNT; i++) {
            if (fences[i] != 0L) collect(i, timeoutNanos);
        }
    }

    void flush() {
        RenderSystem.assertOnRenderThread();
        for (int i = 0; i < BUFFER_COUNT; i++) {
            if (fences[i] != 0L && !collect(i, WAIT_NANOS)) {
                throw new IllegalStateException("Timed out flushing " + label + " frame " + frameIds[i]);
            }
        }
    }

    private boolean collect(int index, long timeoutNanos) {
        long fence = fences[index];
        if (fence == 0L) return true;
        int flags = timeoutNanos > 0L ? GL32.GL_SYNC_FLUSH_COMMANDS_BIT : 0;
        int waitResult = GL32.glClientWaitSync(fence, flags, timeoutNanos);
        if (waitResult == GL32.GL_WAIT_FAILED) {
            throw new IllegalStateException("GPU fence wait failed for " + label + " frame " + frameIds[index]);
        }
        if (waitResult != GL32.GL_ALREADY_SIGNALED && waitResult != GL32.GL_CONDITION_SATISFIED) {
            return false;
        }

        int oldPbo = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        ByteBuffer copy = null;
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pboIds[index]);
            long byteSize = (long) width * height * 8L;
            ByteBuffer mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY,
                    byteSize, null);
            if (mapped == null) throw new IllegalStateException("Failed to map " + label + " PBO");
            try {
                copy = MemoryUtil.memAlloc((int) byteSize);
                MemoryUtil.memCopy(MemoryUtil.memAddress(mapped), MemoryUtil.memAddress(copy), byteSize);
                copy.rewind();
                frameConsumer.accept(frameIds[index], copy);
                copy = null;
            } finally {
                GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
            }
        } finally {
            if (copy != null) MemoryUtil.memFree(copy);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, oldPbo);
            GL32.glDeleteSync(fence);
            fences[index] = 0L;
            frameIds[index] = -1L;
        }
        return true;
    }

    private void ensureResources(int newWidth, int newHeight) {
        if (width == newWidth && height == newHeight && pboIds[0] != 0) return;
        close();
        width = newWidth;
        height = newHeight;
        long size = (long) width * height * 8L;
        int oldPbo = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            for (int i = 0; i < BUFFER_COUNT; i++) {
                pboIds[i] = GL15.glGenBuffers();
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pboIds[i]);
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, size, GL15.GL_STREAM_READ);
                frameIds[i] = -1L;
            }
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, oldPbo);
        }
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        for (int i = 0; i < BUFFER_COUNT; i++) {
            if (fences[i] != 0L) {
                int waitResult = GL32.glClientWaitSync(
                        fences[i], GL32.GL_SYNC_FLUSH_COMMANDS_BIT, WAIT_NANOS);
                if (waitResult != GL32.GL_ALREADY_SIGNALED
                        && waitResult != GL32.GL_CONDITION_SATISFIED) {
                    com.rethinkqaq.flashbackexportextras.Flashbackplus.LOGGER.warn(
                            "Closing {} resources before frame {} fence completed", label, frameIds[i]);
                }
                GL32.glDeleteSync(fences[i]);
                fences[i] = 0L;
            }
            if (pboIds[i] != 0) {
                GL15.glDeleteBuffers(pboIds[i]);
                pboIds[i] = 0;
            }
            frameIds[i] = -1L;
        }
        width = height = -1;
        writeIndex = 0;
    }
}

//?}
