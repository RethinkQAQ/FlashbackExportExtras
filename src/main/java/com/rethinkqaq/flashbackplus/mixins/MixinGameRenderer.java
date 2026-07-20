package com.rethinkqaq.flashbackplus.mixins;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.rethinkqaq.flashbackplus.Flashbackplus;
import com.rethinkqaq.flashbackplus.exporting.DepthCaptureState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Captures FOV, depth buffer, and camera data for Flashback Plus.
 *
 * Depth: intercepted in renderLevel() before RenderSystem.clear(GL_DEPTH_BUFFER_BIT)
 *        which clears depth for hand rendering — at this point the 3D scene is rendered.
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
    //
    // renderLevel() flow:
    //   levelRenderer.renderLevel(...)  → 3D scene → depth valid
    //   RenderSystem.clear(DEPTH)       → ★ capture here before clear
    //   renderItemInHand(...)           → hand rendering
    //
    // This clear is the ONLY RenderSystem.clear call directly inside renderLevel()
    // (clears inside levelRenderer are in separate methods, not intercepted).

    @Redirect(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"),
            remap = false)
    private void redirectClearInRenderLevel(int mask, boolean getError) {
        if ((mask & 256) != 0 && DepthCaptureState.active) {
            captureDepthFromFramebuffer();
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

    private void captureDepthFromFramebuffer() {
        try {
            Minecraft mc = Minecraft.getInstance();
            RenderTarget rt = mc.getMainRenderTarget();
            if (rt == null || !rt.useDepth) return;

            // Capture far plane for optional linearization
            DepthCaptureState.depthFar = ((GameRenderer) (Object) this).getDepthFar();

            FloatBuffer buf = ByteBuffer.allocateDirect(DepthCaptureState.width * DepthCaptureState.height * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();

            int depthTexId = rt.getDepthTextureId();
            int[] oldTex = new int[1];
            GL30.glGetIntegerv(GL30.GL_TEXTURE_BINDING_2D, oldTex);
            GL30.glBindTexture(GL30.GL_TEXTURE_2D, depthTexId);
            GL30.glGetTexImage(GL30.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT, GL30.GL_FLOAT, buf);
            GL30.glBindTexture(GL30.GL_TEXTURE_2D, oldTex[0]);

            buf.rewind();
            synchronized (DepthCaptureState.depthQueue) {
                DepthCaptureState.depthQueue.addLast(buf);
            }
        } catch (Exception e) {
            Flashbackplus.LOGGER.error("Failed to capture depth buffer", e);
        }
    }
}
