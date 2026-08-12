package com.rethinkqaq.flashbackplus.mixins;

import com.mojang.blaze3d.pipeline.RenderTarget;
/*? if >=1.21.5 {*/
/*import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
*//*?}*/
/*? if >=26.1 {*/
/*// 26.x must not compile the legacy OpenGL depth path.
*//*?} else {*/
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import java.nio.FloatBuffer;
/*?}*/
import com.rethinkqaq.flashbackplus.Flashbackplus;
import com.rethinkqaq.flashbackplus.exporting.DepthCaptureState;
import com.rethinkqaq.flashbackplus.gpu.GpuExportBackendFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
/*? if >=26.2 {*/
/*import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
*//*?}*/
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures camera metadata and delegates version-specific depth work to the GPU backend. */
@Mixin(value = GameRenderer.class, remap = false)
public class MixinGameRenderer implements com.rethinkqaq.flashbackplus.exporting.GameRendererDepthAccess {
    /*? if >=26.2 {*/
    /*@Shadow @Final private GameRenderState gameRenderState;
    *//*?}*/
    @Unique
    private boolean flashbackplus_cameraCaptureFailedLogged;

    /*? if >=26.2 {*/
    /*@Redirect(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"),
            remap = false)
    private void flashbackplus$redirectClearDepthTexture(CommandEncoder encoder, GpuTexture texture, double depth) {
        if (!DepthCaptureState.active) encoder.clearDepthTexture(texture, depth);
    }

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"),
            remap = false)
    private void flashbackplus$preserveDepthDuringGui(CommandEncoder encoder, GpuTexture texture, double depth) {
        if (!DepthCaptureState.active) encoder.clearDepthTexture(texture, depth);
    }
    *//*?} elif >=26.1 {*/
    /*@Redirect(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"),
            remap = false)
    private void flashbackplus$redirectClearDepthTexture(CommandEncoder encoder, GpuTexture texture, double depth) {
        if (DepthCaptureState.active) {
            RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
            if (target != null) {
                GpuExportBackendFactory.get().snapshotDepth(
                        target, DepthCaptureState.width, DepthCaptureState.height, DepthCaptureState.depthFar);
            }
        }
        encoder.clearDepthTexture(texture, depth);
    }
    *//*?} elif >=1.21.5 {*/
    /*@Redirect(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"),
            remap = false)
    private void flashbackplus$redirectClearDepthTexture(CommandEncoder encoder, GpuTexture texture, double depth) {
        flashbackplus_snapshotWorldDepthBeforeClear();
        encoder.clearDepthTexture(texture, depth);
    }
    *//*?} elif >=1.21.4 {*/
    /*@Redirect(method = "renderLevel",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(I)V"),
            remap = false)
    private void flashbackplus$redirectClearInRenderLevel(int mask) {
        if ((mask & 256) != 0) flashbackplus_snapshotWorldDepthBeforeClear();
        com.mojang.blaze3d.systems.RenderSystem.clear(mask);
    }
    *//*?} else {*/
    @Redirect(method = "renderLevel",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"),
            remap = false)
    private void flashbackplus$redirectClearInRenderLevel(int mask, boolean getError) {
        if ((mask & 256) != 0) flashbackplus_snapshotWorldDepthBeforeClear();
        com.mojang.blaze3d.systems.RenderSystem.clear(mask, getError);
    }
    /*?}*/

    @Inject(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
                    shift = At.Shift.AFTER),
            remap = false)
    private void flashbackplus$captureCamera(CallbackInfo ci) {
        try {
            Minecraft mc = Minecraft.getInstance();
            /*? if >=26.2 {*/
            /*var camera = mc.gameRenderer.mainCamera();
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
            flashbackplus_cameraCaptureFailedLogged = false;
        } catch (Exception e) {
            if (!flashbackplus_cameraCaptureFailedLogged) {
                flashbackplus_cameraCaptureFailedLogged = true;
                Flashbackplus.LOGGER.error("Failed to capture camera data for camera-path export", e);
            }
        }
    }

    @Override
    public void flashbackplus_captureDepthForFrame(RenderTarget target, long frameId) {
        if (!DepthCaptureState.active || target == null) return;
        /*? if >=26.2 {*/
        /*CameraRenderState cameraState = gameRenderState.levelRenderState.cameraRenderState;
        if (cameraState != null && Float.isFinite(cameraState.depthFar) && cameraState.depthFar > 0.05f) {
            DepthCaptureState.depthFar = cameraState.depthFar;
        }
        *//*?}*/
        DepthCaptureState.requestedFrameId = frameId;
        try {
            /*? if <26.1 {*/
            FloatBuffer snapshot = DepthCaptureState.takePendingWorldDepth();
            if (snapshot == null) {
                Flashbackplus.LOGGER.warn("No world-depth snapshot available for EXR frame {}", frameId);
                return;
            }
            synchronized (DepthCaptureState.depthQueue) {
                DepthCaptureState.depthQueue.addLast(new DepthCaptureState.DepthFrame(frameId, snapshot));
            }
            /*?} else {*/
            GpuExportBackendFactory.get().captureDepth(
                    target, DepthCaptureState.width, DepthCaptureState.height, DepthCaptureState.depthFar);
            /*?}*/
        } finally {
            DepthCaptureState.requestedFrameId = -1L;
        }
    }

    @Unique
    private void flashbackplus_snapshotWorldDepthBeforeClear() {
        /*? if >=26.2 {*/
        /*// 26.2 follows ReplayMod's strategy: suppress the clear and copy the
        // main RenderTarget depth beside the matching colour download.
        *//*?} elif >=26.1 {*/
        /*// 26.1.x copies the matching RenderTarget depth through the Blaze3D
        // staging-buffer path when ExportJob starts the colour download.
        *//*?} else {*/
        if (!DepthCaptureState.active) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.getMainRenderTarget();
        if (target == null || !target.useDepth) return;
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
    }

    /** Legacy PBO capture was removed; all remaining GPU queues flush through the backend. */
    @Override
    public void flashbackplus_flushDepthPbo() {
    }

    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void flashbackplus$releasePendingGpuResources(CallbackInfo ci) {
        DepthCaptureState.beginRenderFrame();
        GpuExportBackendFactory.releasePendingOnRenderThread();
    }
}
