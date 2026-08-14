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
package com.rethinkqaq.flashbackexportextras.mixins;

import com.rethinkqaq.flashbackexportextras.utils.Dummy;
/*? if >=26.1 {*/
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

/*? if >=26.1 {*/
/*@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder", remap = false)
*//*?} else {*/
@Mixin(Dummy.class)
/*?}*/
public class MixinGlCommandEncoderDepthCopy {
    /*? if >=26.1 {*/
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
            ((DirectStateAccessInvoker) access).flashbackplus$bindFrameBufferTextures(
                    framebuffer, 0, colorTexture, mipLevel, bindTarget);
        } else {
            ((DirectStateAccessInvoker) access).flashbackplus$bindFrameBufferTextures(
                    framebuffer, colorTexture, depthTexture, mipLevel, bindTarget);
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
