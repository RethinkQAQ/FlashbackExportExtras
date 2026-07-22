package com.rethinkqaq.flashbackplus.exporting;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * 16-bit PBO asynchronous frame readback for HDR export.
 *
 * Double-buffered PBO pipeline using raw OpenGL:
 *   - PBO size: 8 bytes/pixel (RGBA16)
 *   - glGetTexImage → PBO → fence sync → glMapBuffer → ByteBuffer
 */
public class HdrFrameCapture implements AutoCloseable {

    private static final int PBO_COUNT = 2;

    private final int[] pboIds = new int[PBO_COUNT];
    private final long[] fences = new long[PBO_COUNT];
    private final ByteBuffer[] results = new ByteBuffer[PBO_COUNT];
    private long pboSize;
    private int width;
    private int height;
    private int writeIdx;
    private int pendingCount;
    private boolean initialized;

    public HdrFrameCapture() {
    }

    /**
     * Lazy-init or resize PBOs to match the actual texture being read.
     * Called on first frame (dimensions may differ from ExportJob settings due to SSAA).
     */
    private void ensureResources(int texId) {
        // Query actual texture dimensions
        GL32.glBindTexture(GL32.GL_TEXTURE_2D, texId);
        int texW = GL32.glGetTexLevelParameteri(GL32.GL_TEXTURE_2D, 0, GL32.GL_TEXTURE_WIDTH);
        int texH = GL32.glGetTexLevelParameteri(GL32.GL_TEXTURE_2D, 0, GL32.GL_TEXTURE_HEIGHT);
        GL32.glBindTexture(GL32.GL_TEXTURE_2D, 0);

        long newSize = 8L * texW * texH;

        if (initialized && this.pboSize == newSize) return;

        // Close old PBOs if size changed
        if (initialized) {
            close();
        }

        this.width = texW;
        this.height = texH;
        this.pboSize = newSize;

        for (int i = 0; i < PBO_COUNT; i++) {
            pboIds[i] = GL32.glGenBuffers();
            GL32.glBindBuffer(GL32.GL_PIXEL_PACK_BUFFER, pboIds[i]);
            GL32.glBufferData(GL32.GL_PIXEL_PACK_BUFFER, pboSize, GL32.GL_STREAM_READ);
        }
        GL32.glBindBuffer(GL32.GL_PIXEL_PACK_BUFFER, 0);
        initialized = true;
    }

    /**
     * Issues an asynchronous readback of the given GL texture.
     * Call {@link #tryCollect()} on subsequent frames to retrieve data.
     *
     * @param textureId Raw OpenGL texture ID
     */
    public void issueReadback(int textureId) {
        RenderSystem.assertOnRenderThread();
        ensureResources(textureId);

        int readIdx = (writeIdx + 1) % PBO_COUNT;

        // Collect previous result if ready
        if (fences[readIdx] != 0) {
            int waitResult = GL32.glClientWaitSync(fences[readIdx], 0, 0);
            if (waitResult == GL32.GL_ALREADY_SIGNALED || waitResult == GL32.GL_CONDITION_SATISFIED) {
                GL32.glDeleteSync(fences[readIdx]);
                fences[readIdx] = 0;

                GL32.glBindBuffer(GL32.GL_PIXEL_PACK_BUFFER, pboIds[readIdx]);
                ByteBuffer mapped = GL32.glMapBuffer(GL32.GL_PIXEL_PACK_BUFFER, GL32.GL_READ_ONLY);
                if (mapped != null) {
                    if (results[readIdx] != null) {
                        MemoryUtil.memFree(results[readIdx]);
                    }
                    results[readIdx] = MemoryUtil.memAlloc((int) pboSize);
                    MemoryUtil.memCopy(MemoryUtil.memAddress(mapped),
                            MemoryUtil.memAddress(results[readIdx]), (int) pboSize);
                    GL32.glUnmapBuffer(GL32.GL_PIXEL_PACK_BUFFER);
                    if (pendingCount < PBO_COUNT) pendingCount++;
                }
                GL32.glBindBuffer(GL32.GL_PIXEL_PACK_BUFFER, 0);
            }
        }

        // Issue async read into current PBO
        GL32.glBindBuffer(GL32.GL_PIXEL_PACK_BUFFER, pboIds[writeIdx]);
        GL32.glBindTexture(GL32.GL_TEXTURE_2D, textureId);
        GL32.glGetTexImage(GL32.GL_TEXTURE_2D, 0, GL32.GL_RGBA, GL32.GL_UNSIGNED_SHORT, 0);
        GL32.glBindTexture(GL32.GL_TEXTURE_2D, 0);
        GL32.glBindBuffer(GL32.GL_PIXEL_PACK_BUFFER, 0);

        fences[writeIdx] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        writeIdx = readIdx;
    }

    /** Attempts to collect the oldest pending frame. Returns null if none ready. */
    public ByteBuffer tryCollect() {
        for (int i = 0; i < PBO_COUNT; i++) {
            int idx = (writeIdx + 1 + i) % PBO_COUNT;
            if (results[idx] != null) {
                ByteBuffer result = results[idx];
                results[idx] = null;
                if (pendingCount > 0) pendingCount--;
                return result;
            }
        }
        return null;
    }

    /** Blocking collect — waits until a frame is available. */
    public ByteBuffer collect() {
        ByteBuffer result;
        while ((result = tryCollect()) == null) {
            // Poll the fences manually
            for (int i = 0; i < PBO_COUNT; i++) {
                int idx = (writeIdx + 1 + i) % PBO_COUNT;
                if (fences[idx] != 0) {
                    int waitResult = GL32.glClientWaitSync(fences[idx], 0, 1_000_000L);
                    if (waitResult == GL32.GL_ALREADY_SIGNALED || waitResult == GL32.GL_CONDITION_SATISFIED) {
                        GL32.glDeleteSync(fences[idx]);
                        fences[idx] = 0;
                        GL32.glBindBuffer(GL32.GL_PIXEL_PACK_BUFFER, pboIds[idx]);
                        ByteBuffer mapped = GL32.glMapBuffer(GL32.GL_PIXEL_PACK_BUFFER, GL32.GL_READ_ONLY);
                        if (mapped != null) {
                            if (results[idx] != null) MemoryUtil.memFree(results[idx]);
                            results[idx] = MemoryUtil.memAlloc((int) pboSize);
                            MemoryUtil.memCopy(MemoryUtil.memAddress(mapped),
                                    MemoryUtil.memAddress(results[idx]), (int) pboSize);
                            GL32.glUnmapBuffer(GL32.GL_PIXEL_PACK_BUFFER);
                            if (pendingCount < PBO_COUNT) pendingCount++;
                        }
                        GL32.glBindBuffer(GL32.GL_PIXEL_PACK_BUFFER, 0);
                    }
                }
            }
        }
        return result;
    }

    @Override
    public void close() {
        if (!initialized) return;
        for (int i = 0; i < PBO_COUNT; i++) {
            if (fences[i] != 0) {
                GL32.glClientWaitSync(fences[i], GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
                GL32.glDeleteSync(fences[i]);
                fences[i] = 0;
            }
            if (results[i] != null) {
                MemoryUtil.memFree(results[i]);
                results[i] = null;
            }
        }
        GL32.glDeleteBuffers(pboIds);
        initialized = false;
    }
}
