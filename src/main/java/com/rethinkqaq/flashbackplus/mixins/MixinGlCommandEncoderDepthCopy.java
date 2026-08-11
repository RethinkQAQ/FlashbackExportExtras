package com.rethinkqaq.flashbackplus.mixins;

/*? if >=26.2 {*/
/*import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.DirectStateAccess;
import com.mojang.blaze3d.textures.GpuTexture;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*//*?}*/
import org.spongepowered.asm.mixin.Mixin;

/** Repairs only the OpenGL attachment selection for abstract depth readback. */
@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder", remap = false)
public class MixinGlCommandEncoderDepthCopy {
    /*? if >=26.2 {*/
    /*@Unique private GpuTexture flashbackplus$copyTexture;

    @Inject(method = "copyTextureToBuffer(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V",
            at = @At("HEAD"), remap = false)
    private void flashbackplus$rememberCopyTexture(GpuTexture texture, GpuBuffer buffer, long offset,
                                                    Runnable callback, int x, int y, int width, int height,
                                                    int mipLevel, CallbackInfo ci) {
        flashbackplus$copyTexture = texture;
    }

    @Redirect(method = "copyTextureToBuffer(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/opengl/DirectStateAccess;bindFrameBufferTextures(IIIII)V"),
            remap = false)
    private void flashbackplus$bindDepthAttachment(DirectStateAccess access, int framebuffer, int colorTexture,
                                                    int depthTexture, int mipLevel, int bindTarget) {
        GpuTexture texture = flashbackplus$copyTexture;
        if (texture != null && texture.getFormat().hasDepthAspect() && depthTexture == 0) {
            access.bindFrameBufferTextures(framebuffer, 0, colorTexture, mipLevel, bindTarget);
        } else {
            access.bindFrameBufferTextures(framebuffer, colorTexture, depthTexture, mipLevel, bindTarget);
        }
    }

    @Inject(method = "copyTextureToBuffer(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V",
            at = @At("RETURN"), remap = false)
    private void flashbackplus$forgetCopyTexture(GpuTexture texture, GpuBuffer buffer, long offset,
                                                   Runnable callback, int x, int y, int width, int height,
                                                   int mipLevel, CallbackInfo ci) {
        flashbackplus$copyTexture = null;
    }
    *//*?}*/
}
