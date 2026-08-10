package com.rethinkqaq.flashbackplus.mixins;

import com.mojang.blaze3d.pipeline.RenderTarget;
/*? if >=1.21.5 {*/
/*import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
*//*?}*/
import com.rethinkqaq.flashbackplus.Flashbackplus;
import com.rethinkqaq.flashbackplus.exporting.DepthCaptureState;
import com.rethinkqaq.flashbackplus.gpu.GpuExportBackendFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL11;
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
public class MixinGameRenderer implements com.rethinkqaq.flashbackplus.exporting.GameRendererDepthAccess {

    // === Depth Capture: intercept hand-render depth clear inside renderLevel ===

    /*? if >= 1.21.5 {*/
    /*@Redirect(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"),
            remap = false)
    private void redirectClearDepthTexture(CommandEncoder encoder, GpuTexture texture, double depth) {
        flashbackplus_snapshotWorldDepthBeforeClear();
        encoder.clearDepthTexture(texture, depth);
    }
    *//*?} elif >=1.21.4 {*/
    /*@Redirect(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(I)V"),
            remap = false)
    private void redirectClearInRenderLevel(int mask) {
        if ((mask & 256) != 0) flashbackplus_snapshotWorldDepthBeforeClear();
        com.mojang.blaze3d.systems.RenderSystem.clear(mask);
    }
    *//*?} else {*/
    @Redirect(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"),
            remap = false)
    private void redirectClearInRenderLevel(int mask, boolean getError) {
        if ((mask & 256) != 0) flashbackplus_snapshotWorldDepthBeforeClear();
        com.mojang.blaze3d.systems.RenderSystem.clear(mask, getError);
    }
    /*?}*/

    // === Camera capture: after renderLevel() returns ===

    @Inject(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
                    shift = At.Shift.AFTER),
            remap = false)
    private void afterRenderLevel(CallbackInfo ci) {
        try {
            Minecraft mc = Minecraft.getInstance();
            /*? if >=26.2 {*/
            /*/^? if >=26.2 {^/
            /^var camera = mc.gameRenderer.mainCamera();
            ^//^?} else {^/
            var camera = mc.gameRenderer.getMainCamera();
            /^?}^/
            *//*?} else {*/
            var camera = mc.gameRenderer.getMainCamera();
            /*?}*/
            if (camera != null && camera.isInitialized()) {
                /*? if >=1.21.11 {*/
                /*var pos = camera.position();
                *//*?} else {*/
                var pos = camera.getPosition();
                /*?}*/
                DepthCaptureState.camX = pos.x;
                DepthCaptureState.camY = pos.y;
                DepthCaptureState.camZ = pos.z;
                /*? if >=1.21.11 {*/
                /*DepthCaptureState.camYaw = camera.yRot();
                DepthCaptureState.camPitch = camera.xRot();
                *//*?} else {*/
                DepthCaptureState.camYaw = camera.getYRot();
                DepthCaptureState.camPitch = camera.getXRot();
                /*?}*/
            }
        } catch (Exception ignored) {}
    }

    // ============================================================
    //  PBO double-buffered async depth readback
    // ============================================================

    @Unique
    private static final int PBO_COUNT = 3;

    @Unique
    private final int[] flashbackplus_pbo = new int[PBO_COUNT];

    @Unique
    private final long[] flashbackplus_fence = new long[PBO_COUNT];

    @Unique
    private final long[] flashbackplus_pboFrame = new long[PBO_COUNT];

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
            flashbackplus_pboFrame[i] = -1L;
        }
        flashbackplus_pboReady = false;
        flashbackplus_writeIdx = 0;
    }

    @Override
    public void flashbackplus_captureDepthForFrame(RenderTarget target, long frameId) {
        if (!DepthCaptureState.active || target == null) return;
        DepthCaptureState.requestedFrameId = frameId;
        try {
            /*? if <26.2 {*/
            FloatBuffer snapshot = DepthCaptureState.takePendingWorldDepth();
            if (snapshot == null) {
                Flashbackplus.LOGGER.warn("No world-depth snapshot available for EXR frame {}", frameId);
                return;
            }
            synchronized (DepthCaptureState.depthQueue) {
                DepthCaptureState.depthQueue.addLast(new DepthCaptureState.DepthFrame(frameId, snapshot));
            }
            /*?} else {*/
            captureDepthPbo(frameId, target);
            /*?}*/
        } finally {
            DepthCaptureState.requestedFrameId = -1L;
        }
    }

    /**
     * Saves world depth before Minecraft clears it for hand rendering. The
     * snapshot is assigned to an output frame later, at startDownload.
     */
    @Unique
    private void flashbackplus_snapshotWorldDepthBeforeClear() {
        /*? if >=26.2 {*/
        /*if (!DepthCaptureState.active) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.getMainRenderTarget();
        if (target == null || !target.useDepth || target.getDepthTexture() == null) return;
        GpuExportBackendFactory.get().snapshotWorldDepth(
                target, DepthCaptureState.width, DepthCaptureState.height, DepthCaptureState.depthFar);
        *//*?} else {*/
        /*? if <26.2 {*/
        if (!DepthCaptureState.active) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.getMainRenderTarget();
        if (target == null) return;
        if (!target.useDepth) return;

        /*? if >=1.21.5 {*/
        /*int depthTextureId = ((GlTexture) target.getDepthTexture()).glId();
        *//*?} else {*/
        int depthTextureId = target.getDepthTextureId();
        /*?}*/
        if (depthTextureId <= 0) return;

        FloatBuffer copy = DepthCaptureState.acquireBuffer();
        int oldTexture = GL11.glGetInteger(GL30.GL_TEXTURE_BINDING_2D);
        int oldPbo = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        boolean captured = false;
        try {
            // A non-zero pack PBO changes the last argument from a destination
            // buffer to a byte offset, so force direct client-memory readback.
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL30.glBindTexture(GL30.GL_TEXTURE_2D, depthTextureId);
            GL30.glGetTexImage(GL30.GL_TEXTURE_2D, 0,
                    GL30.GL_DEPTH_COMPONENT, GL30.GL_FLOAT, copy);
            copy.rewind();
            captured = true;
            DepthCaptureState.replacePendingWorldDepth(copy);
        } finally {
            GL30.glBindTexture(GL30.GL_TEXTURE_2D, oldTexture);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, oldPbo);
            if (!captured) DepthCaptureState.releaseBuffer(copy);
        }
        /*?}*/
        /*?}*/
    }

    @Unique
    private void captureDepthPbo(long frameId, RenderTarget target) {
        try {
            if (GpuExportBackendFactory.get().capturesBeforeDepthClear()) {
                captureDepthBeforeClear(target);
                return;
            }
            if (flashbackplus_deferDepth) return;
            /*? if <26.2 {*/
            if (!target.useDepth) return;

            // Lazy init on first frame
            if (!flashbackplus_pboReady) {
                flashbackplus_initPbos();
            }

            // Capture far plane every frame (it might change)
            // Version-specific render-state access is isolated in the GPU backend.
            // Keep the legacy Mixin source compatible with all pre-26.2 mappings.
            DepthCaptureState.depthFar = 1000.0f;

            int readIdx = (flashbackplus_writeIdx + PBO_COUNT - 1) % PBO_COUNT;

            // --- Step 1: Read from PBO written 2 frames ago (if ready) ---
            if (flashbackplus_fence[readIdx] != 0) {
                int result = GL32.glClientWaitSync(flashbackplus_fence[readIdx],
                        GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000); // 1ms
                if (result == GL32.GL_ALREADY_SIGNALED || result == GL32.GL_CONDITION_SATISFIED) {
                    int oldPbo = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, flashbackplus_pbo[readIdx]);
                    ByteBuffer mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY);
                    if (mapped != null) {
                        // Copy into pooled buffer (mapped buffer invalid after unmap)
                        FloatBuffer copy = DepthCaptureState.acquireBuffer();
                        copy.put(mapped.asFloatBuffer());
                        copy.rewind();
                        synchronized (DepthCaptureState.depthQueue) {
                            DepthCaptureState.depthQueue.addLast(
                                    new DepthCaptureState.DepthFrame(flashbackplus_pboFrame[readIdx], copy));
                        }
                        GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                    }
                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, oldPbo);
                    GL32.glDeleteSync(flashbackplus_fence[readIdx]);
                    flashbackplus_fence[readIdx] = 0;
                }
                // If not ready, skip this frame's read — data will be captured
                // once the GPU has finished the transfer (typically 1-2 frames later)
            }

            // --- Step 2: Start async write to current PBO ---
            int writeIdx = flashbackplus_writeIdx;
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, flashbackplus_pbo[writeIdx]);
            /*? if >=1.21.5 {*/
            /*int depthTexId = ((GlTexture) target.getDepthTexture()).glId();
            *//*?} else {*/
            int depthTexId = target.getDepthTextureId();
            /*?}*/
            if (depthTexId <= 0 || flashbackplus_pbo[writeIdx] == 0) return;
            int[] oldTex = new int[1];
            int oldPbo = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            GL30.glGetIntegerv(GL30.GL_TEXTURE_BINDING_2D, oldTex);
            GL30.glBindTexture(GL30.GL_TEXTURE_2D, depthTexId);
            // With PBO bound, last arg = offset into PBO (0 = start)
            GL30.glGetTexImage(GL30.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT, GL30.GL_FLOAT, 0);
            GL30.glBindTexture(GL30.GL_TEXTURE_2D, oldTex[0]);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, oldPbo);

            // Create fence for GPU to signal when the DMA transfer completes
            flashbackplus_pboFrame[writeIdx] = frameId;
            flashbackplus_fence[writeIdx] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);

            // --- Step 3: Flip for next frame ---
            flashbackplus_writeIdx = (flashbackplus_writeIdx + 1) % PBO_COUNT;
            /*?}*/
        } catch (Exception e) {
            Flashbackplus.LOGGER.error("Failed to capture depth buffer via PBO", e);
        }
    }

    /** Drain all pending legacy PBO transfers before EXR finalization. */
    @Unique
    public void flashbackplus_flushDepthPbo() {
        for (int i = 0; i < PBO_COUNT; i++) {
            long fence = flashbackplus_fence[i];
            if (fence == 0) continue;
            int result = GL32.glClientWaitSync(fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT,
                    1_000_000_000L);
            if (result != GL32.GL_ALREADY_SIGNALED && result != GL32.GL_CONDITION_SATISFIED) {
                Flashbackplus.LOGGER.warn("Depth PBO {} did not finish before EXR finalization", i);
                continue;
            }
            int oldPbo = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, flashbackplus_pbo[i]);
            ByteBuffer mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY);
            if (mapped != null) {
                FloatBuffer copy = DepthCaptureState.acquireBuffer();
                copy.put(mapped.asFloatBuffer());
                copy.rewind();
                synchronized (DepthCaptureState.depthQueue) {
                    DepthCaptureState.depthQueue.addLast(
                            new DepthCaptureState.DepthFrame(flashbackplus_pboFrame[i], copy));
                }
                GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, oldPbo);
            GL32.glDeleteSync(fence);
            flashbackplus_fence[i] = 0;
        }
    }

    @Unique
    private void captureDepthBeforeClear(RenderTarget target) {
        GpuExportBackendFactory.get().captureDepth(
                target, DepthCaptureState.width, DepthCaptureState.height, DepthCaptureState.depthFar);
    }

    @Unique
    private boolean flashbackplus_deferDepth = false;
}
