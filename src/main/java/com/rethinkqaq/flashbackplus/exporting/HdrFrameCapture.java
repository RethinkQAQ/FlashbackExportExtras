package com.rethinkqaq.flashbackplus.exporting;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * 16-bit PBO asynchronous frame readback for HDR export.
 *
 * Double-buffered PBO pipeline. Pooled ByteBuffers are reused across
 * frames to avoid per-frame native allocation (~16 MB/frame at 1080p).
 *
 * Flow: glGetTexImage → PBO → fence sync → glMapBuffer → memCopy→pooled buf
 */
public class HdrFrameCapture implements AutoCloseable {

    private static final int PBO_COUNT = 2;

    private final int[] pboIds = new int[PBO_COUNT];
    private final long[] fences = new long[PBO_COUNT];
    /** Pooled readers — allocated once, reused every frame. */
    private final ByteBuffer[] pooled = new ByteBuffer[PBO_COUNT];
    /** Flags whether pooled[i] contains valid data ready for collection. */
    private final boolean[] ready = new boolean[PBO_COUNT];
    private long pboSize;
    private int writeIdx;
    private int pendingCount;
    private boolean initialized;

    public HdrFrameCapture() {
    }

    private void ensureResources(int texId) {
        GL32.glBindTexture(GL32.GL_TEXTURE_2D, texId);
        int texW = GL32.glGetTexLevelParameteri(GL32.GL_TEXTURE_2D, 0, GL32.GL_TEXTURE_WIDTH);
        int texH = GL32.glGetTexLevelParameteri(GL32.GL_TEXTURE_2D, 0, GL32.GL_TEXTURE_HEIGHT);
        GL32.glBindTexture(GL32.GL_TEXTURE_2D, 0);

        long newSize = 8L * texW * texH;
        if (initialized && this.pboSize == newSize) return;

        // Size changed — close and recreate
        if (initialized) close();

        this.pboSize = newSize;

        for (int i = 0; i < PBO_COUNT; i++) {
            pboIds[i] = GL32.glGenBuffers();
            GL32.glBindBuffer(GL32.GL_PIXEL_PACK_BUFFER, pboIds[i]);
            GL32.glBufferData(GL32.GL_PIXEL_PACK_BUFFER, pboSize, GL32.GL_STREAM_READ);
            // Pre-allocate pooled buffer
            pooled[i] = MemoryUtil.memAlloc((int) pboSize);
        }
        GL32.glBindBuffer(GL32.GL_PIXEL_PACK_BUFFER, 0);
        initialized = true;
    }

    /**
     * Issues an asynchronous readback of the given GL texture.
     * Call {@link #tryCollect()} on subsequent frames to retrieve data.
     */
    public void issueReadback(int textureId) {
        RenderSystem.assertOnRenderThread();
        ensureResources(textureId);

        int readIdx = (writeIdx + 1) % PBO_COUNT;

        // Collect previous result if fence signaled
        if (fences[readIdx] != 0) {
            int waitResult = GL32.glClientWaitSync(fences[readIdx], 0, 0);
            if (waitResult == GL32.GL_ALREADY_SIGNALED || waitResult == GL32.GL_CONDITION_SATISFIED) {
                GL32.glDeleteSync(fences[readIdx]);
                fences[readIdx] = 0;

                // Copy PBO → pooled buffer (reused allocation)
                GL32.glBindBuffer(GL32.GL_PIXEL_PACK_BUFFER, pboIds[readIdx]);
                ByteBuffer mapped = GL32.glMapBuffer(GL32.GL_PIXEL_PACK_BUFFER, GL32.GL_READ_ONLY);
                if (mapped != null) {
                    // Rewind pooled buffer and copy in-place
                    ByteBuffer buf = pooled[readIdx];
                    buf.rewind();
                    MemoryUtil.memCopy(MemoryUtil.memAddress(mapped),
                            MemoryUtil.memAddress(buf), (int) pboSize);
                    buf.rewind();
                    GL32.glUnmapBuffer(GL32.GL_PIXEL_PACK_BUFFER);
                    ready[readIdx] = true;
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

    /**
     * Attempts to collect the oldest pending frame.
     * @return a COPY of the frame data (caller must memFree), or null if none ready.
     */
    public ByteBuffer tryCollect() {
        for (int i = 0; i < PBO_COUNT; i++) {
            int idx = (writeIdx + 1 + i) % PBO_COUNT;
            if (ready[idx]) {
                ready[idx] = false;
                if (pendingCount > 0) pendingCount--;
                // Return a copy so the pooled buffer can be reused next cycle
                ByteBuffer result = MemoryUtil.memAlloc((int) pboSize);
                ByteBuffer src = pooled[idx];
                src.rewind();
                MemoryUtil.memCopy(MemoryUtil.memAddress(src), MemoryUtil.memAddress(result), (int) pboSize);
                result.rewind();
                return result;
            }
        }
        return null;
    }

    /** Blocking collect — waits until a frame is available. */
    public ByteBuffer collect() {
        ByteBuffer result;
        while ((result = tryCollect()) == null) {
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
                            ByteBuffer buf = pooled[idx];
                            buf.rewind();
                            MemoryUtil.memCopy(MemoryUtil.memAddress(mapped),
                                    MemoryUtil.memAddress(buf), (int) pboSize);
                            buf.rewind();
                            GL32.glUnmapBuffer(GL32.GL_PIXEL_PACK_BUFFER);
                            ready[idx] = true;
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
            if (pooled[i] != null) {
                MemoryUtil.memFree(pooled[i]);
                pooled[i] = null;
            }
        }
        GL32.glDeleteBuffers(pboIds);
        initialized = false;
    }
}
