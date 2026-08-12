package com.rethinkqaq.flashbackplus.mixins;

import com.rethinkqaq.flashbackplus.utils.Dummy;
/*? if >=26.2 {*/
/*import com.rethinkqaq.flashbackplus.exporting.DepthCaptureState;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*//*?}*/
import org.spongepowered.asm.mixin.Mixin;

/*? if >=26.2 {*/
/*@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
*//*?} else {*/
@Mixin(Dummy.class)
/*?}*/
public final class MixinIrisRenderingPipeline {
    /*? if >=26.2 {*/
    /*@Inject(method = "beginLevelRendering", at = @At("HEAD"), remap = false)
    private void flashbackplus$markShaderPackFrame(CallbackInfo ci) {
        DepthCaptureState.markIrisShaderPackRendered();
    }
    *//*?}*/
}
