package com.rethinkqaq.flashbackplus.mixins;

import com.rethinkqaq.flashbackplus.exporting.DepthCaptureState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optional Iris marker. It never accesses Iris textures and is enabled only
 * on 26.2 when Iris is installed. IrisRenderingPipeline is used only while a
 * shaderpack is active; the vanilla Iris pipeline does not invoke this hook.
 */
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public final class MixinIrisRenderingPipeline {
    @Inject(method = "beginLevelRendering", at = @At("HEAD"), remap = false)
    private void flashbackplus$markShaderPackFrame(CallbackInfo ci) {
        DepthCaptureState.markIrisShaderPackRendered();
    }
}
