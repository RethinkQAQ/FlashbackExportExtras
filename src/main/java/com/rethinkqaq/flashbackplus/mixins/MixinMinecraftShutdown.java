package com.rethinkqaq.flashbackplus.mixins;

import net.minecraft.client.Minecraft;
import com.rethinkqaq.flashbackplus.Flashbackplus;
import com.rethinkqaq.flashbackplus.exporting.DepthCaptureState;
import com.rethinkqaq.flashbackplus.gpu.GpuExportBackendFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevent Flashback's non-daemon dialog executor from holding the JVM open. */
@Mixin(value = Minecraft.class, remap = false)
public class MixinMinecraftShutdown {
    @Inject(method = "close", at = @At("HEAD"), remap = false)
    private void flashbackplus$shutdownFlashbackDialogExecutor(CallbackInfo ci) {
        try {
            AsyncFileDialogsAccessor.flashbackplus$getDialogThread().shutdownNow();
        } catch (Throwable t) {
            Flashbackplus.LOGGER.warn("Failed to stop Flashback file-dialog executor during shutdown", t);
        }
        try {
            DepthCaptureState.reset();
            GpuExportBackendFactory.reset();
        } catch (Throwable t) {
            Flashbackplus.LOGGER.warn("Failed to clean Flashback Plus GPU resources during shutdown", t);
        }
    }
}
