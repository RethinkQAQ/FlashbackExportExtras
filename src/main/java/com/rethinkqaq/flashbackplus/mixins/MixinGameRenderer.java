package com.rethinkqaq.flashbackplus.mixins;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.rethinkqaq.flashbackplus.Flashbackplus;
import com.rethinkqaq.flashbackplus.exporting.DepthCaptureState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * Captures FOV, depth buffer, and camera data for Flashback Plus.
 *
 * Depth capture uses a double-buffered PBO (Pixel Buffer Object) pipeline
 * with GL fence sync objects to avoid stalling the render thread:
 *
 *   Frame 0: async glGetTexImage → PBO[0], set fence[0]
 *   Frame 1: async glGetTexImage → PBO[1], set fence[1]
 *   Frame 2: check fence[0] → map PBO[0] → copy to queue; async write PBO[0]
 *   ...
 *
 * This decouples GPU depth readback from the render thread's frame loop.
 *
 * Camera: captured after renderLevel() returns (in GameRenderer.render()).
 */
@Mixin(value = GameRenderer.class, remap = false)
public class MixinGameRenderer {

    // === FOV Capture ===

    @Redirect(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;getProjectionMatrix(D)Lorg/joml/Matrix4f;"),
            remap = false)
    private org.joml.Matrix4f captureFov(GameRenderer gameRenderer, double fov) {
        DepthCaptureState.fovDegrees = (float) fov;
        return gameRenderer.getProjectionMatrix(fov);
    }

    // === Depth Capture: intercept hand-render depth clear inside renderLevel ===

    @Redirect(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"),
            remap = false)
    private void redirectClearInRenderLevel(int mask, boolean getError) {
        if ((mask & 256) != 0 && DepthCaptureState.active) {
            captureDepthPbo();
        }
        com.mojang.blaze3d.systems.RenderSystem.clear(mask, getError);
    }

    // === Camera capture: after renderLevel() returns ===

    @Inject(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
                    shift = At.Shift.AFTER),
            remap = false)
    private void afterRenderLevel(CallbackInfo ci) {
        try {
            Minecraft mc = Minecraft.getInstance();
            var camera = mc.gameRenderer.getMainCamera();
            if (camera != null && camera.isInitialized()) {
                var pos = camera.getPosition();
                DepthCaptureState.camX = pos.x;
                DepthCaptureState.camY = pos.y;
                DepthCaptureState.camZ = pos.z;
                DepthCaptureState.camYaw = camera.getYRot();
                DepthCaptureState.camPitch = camera.getXRot();
            }
        } catch (Exception ignored) {}
    }

    // ============================================================
    //  PBO double-buffered async depth readback
    // ============================================================

    @Unique
    private static final int PBO_COUNT = 2;

    @Unique
    private final int[] flashbackplus_pbo = new int[PBO_COUNT];

    @Unique
    private final long[] flashbackplus_fence = new long[PBO_COUNT];

    @Unique
    private int flashbackplus_writeIdx = 0;

    @Unique
    private boolean flashbackplus_pboReady = false;

    /** Allocate PBOs on first use. Called from render thread. */
    @Unique
    private void flashbackplus_initPbos() {
        int size = DepthCaptureState.width * DepthCaptureState.height * 4; // FLOAT
        for (int i = 0; i < PBO_COUNT; i++) {
            flashbackplus_pbo[i] = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, flashbackplus_pbo[i]);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, size, GL15.GL_STREAM_READ);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        flashbackplus_pboReady = true;

        // Register cleanup hook
        DepthCaptureState.pboCleanup = this::flashbackplus_deletePbos;

        Flashbackplus.LOGGER.debug("PBO pipeline initialized: {} MB × {}",
                size / (1024 * 1024), PBO_COUNT);
    }

    /** Delete PBOs and fences. Called on export end or game shutdown. */
    @Unique
    private void flashbackplus_deletePbos() {
        for (int i = 0; i < PBO_COUNT; i++) {
            if (flashbackplus_fence[i] != 0) {
                GL32.glDeleteSync(flashbackplus_fence[i]);
                flashbackplus_fence[i] = 0;
            }
            if (flashbackplus_pbo[i] != 0) {
                GL15.glDeleteBuffers(flashbackplus_pbo[i]);
                flashbackplus_pbo[i] = 0;
            }
        }
        flashbackplus_pboReady = false;
        flashbackplus_writeIdx = 0;
    }

    /**
     * Main depth capture entry point.
     * 1. Try to read from the PBO written 2 frames ago (non-blocking).
     * 2. Start async write into the current PBO.
     * 3. Flip write index for next frame.
     */
    @Unique
    private void captureDepthPbo() {
        try {
            Minecraft mc = Minecraft.getInstance();
            RenderTarget rt = mc.getMainRenderTarget();
            if (rt == null || !rt.useDepth) return;

            // Lazy init on first frame
            if (!flashbackplus_pboReady) {
                flashbackplus_initPbos();
            }

            // Capture far plane every frame (it might change)
            DepthCaptureState.depthFar = ((GameRenderer) (Object) this).getDepthFar();

            int readIdx = 1 - flashbackplus_writeIdx;

            // --- Step 1: Read from PBO written 2 frames ago (if ready) ---
            if (flashbackplus_fence[readIdx] != 0) {
                int result = GL32.glClientWaitSync(flashbackplus_fence[readIdx],
                        GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000); // 1ms
                if (result == GL32.GL_ALREADY_SIGNALED || result == GL32.GL_CONDITION_SATISFIED) {
                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, flashbackplus_pbo[readIdx]);
                    ByteBuffer mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY);
                    if (mapped != null) {
                        // Copy into pooled buffer (mapped buffer invalid after unmap)
                        FloatBuffer copy = DepthCaptureState.acquireBuffer();
                        copy.put(mapped.asFloatBuffer());
                        copy.rewind();
                        synchronized (DepthCaptureState.depthQueue) {
                            DepthCaptureState.depthQueue.addLast(copy);
                        }
                        GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                    }
                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
                    GL32.glDeleteSync(flashbackplus_fence[readIdx]);
                    flashbackplus_fence[readIdx] = 0;
                }
                // If not ready, skip this frame's read — data will be captured
                // once the GPU has finished the transfer (typically 1-2 frames later)
            }

            // --- Step 2: Start async write to current PBO ---
            int writeIdx = flashbackplus_writeIdx;
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, flashbackplus_pbo[writeIdx]);
            int depthTexId = rt.getDepthTextureId();
            int[] oldTex = new int[1];
            GL30.glGetIntegerv(GL30.GL_TEXTURE_BINDING_2D, oldTex);
            GL30.glBindTexture(GL30.GL_TEXTURE_2D, depthTexId);
            // With PBO bound, last arg = offset into PBO (0 = start)
            GL30.glGetTexImage(GL30.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT, GL30.GL_FLOAT, 0);
            GL30.glBindTexture(GL30.GL_TEXTURE_2D, oldTex[0]);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);

            // Create fence for GPU to signal when the DMA transfer completes
            flashbackplus_fence[writeIdx] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);

            // --- Step 3: Flip for next frame ---
            flashbackplus_writeIdx = 1 - flashbackplus_writeIdx;
        } catch (Exception e) {
            Flashbackplus.LOGGER.error("Failed to capture depth buffer via PBO", e);
        }
    }
}
