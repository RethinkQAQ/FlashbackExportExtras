package com.rethinkqaq.flashbackexportextras.mixins;

import com.rethinkqaq.flashbackexportextras.utils.Dummy;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(
        remap = false,
        //? if mc_26_1_2 {
        /*
        targets = "xyz.rrtt217.HDRMod.core.ColorTransformRenderer"
        *///?} else {
        value = Dummy.class
        //?}
)
public abstract class MixinHdrModColorTransformRenderer
        //? if mc_26_1_2 {
        /*implements HdrModColorTransformAccess
        *///?}
{
    //? if mc_26_1_2 {
    /*
    @Shadow private int dstTextureFormat;
    @Shadow private int dstReadPixelFormat;
    @Shadow public abstract void recreateTexture();

    @Unique
    @Override
    public void flashbackplus$configureOutput(int textureFormat, int readPixelFormat) {
        if (dstTextureFormat == textureFormat && dstReadPixelFormat == readPixelFormat) return;
        dstTextureFormat = textureFormat;
        dstReadPixelFormat = readPixelFormat;
        recreateTexture();
    }
    *///?}
}
