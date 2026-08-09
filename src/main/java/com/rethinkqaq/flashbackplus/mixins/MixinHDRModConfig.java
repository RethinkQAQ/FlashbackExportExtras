package com.rethinkqaq.flashbackplus.mixins;

import com.rethinkqaq.flashbackplus.Flashbackplus;
import com.rethinkqaq.flashbackplus.exporting.HdrExportState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "xyz.rrtt217.HDRMod.config.HDRModConfig")
public class MixinHDRModConfig {
    @Shadow public boolean enableHDR;
    @Inject(method = "<init>", at = @At("RETURN"))
    private void flashbackplus$readConfig(CallbackInfo ci) {
        HdrExportState.setHdrModLoaded(true);
        HdrExportState.setHdrModEnabled(enableHDR);
        Flashbackplus.LOGGER.info("HDR Mod enabled: {}", enableHDR);
    }
}
