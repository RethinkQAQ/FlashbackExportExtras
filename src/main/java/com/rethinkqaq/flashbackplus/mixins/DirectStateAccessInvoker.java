package com.rethinkqaq.flashbackplus.mixins;

import com.rethinkqaq.flashbackplus.utils.Dummy;
/*? if >=26.1 {*/
/*import com.mojang.blaze3d.opengl.DirectStateAccess;
import org.spongepowered.asm.mixin.gen.Invoker;
*//*?}*/
import org.spongepowered.asm.mixin.Mixin;

/*? if >=26.1 {*/
/*@Mixin(DirectStateAccess.class)
*//*?} else {*/
@Mixin(Dummy.class)
/*?}*/
public interface DirectStateAccessInvoker {
    /*? if >=26.1 {*/
    /*@Invoker("bindFrameBufferTextures")
    void flashbackplus$bindFrameBufferTextures(int framebuffer, int colorTexture,
                                                int depthTexture, int mipLevel, int bindTarget);
    *//*?}*/
}
