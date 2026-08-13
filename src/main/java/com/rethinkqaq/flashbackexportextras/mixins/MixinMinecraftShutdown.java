package com.rethinkqaq.flashbackexportextras.mixins;

import net.minecraft.client.Minecraft;
import com.rethinkqaq.flashbackexportextras.Flashbackplus;
import com.rethinkqaq.flashbackexportextras.exporting.DepthCaptureState;
import com.rethinkqaq.flashbackexportextras.exporting.SceneLinearHdrCaptureState;
/*? if hdr {*/
import com.rethinkqaq.flashbackexportextras.exporting.HdrVideoCaptureState;
/*?}*/
import com.rethinkqaq.flashbackexportextras.gpu.GpuExportBackendFactory;
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
            SceneLinearHdrCaptureState.reset();
            /*? if hdr {*/
            HdrVideoCaptureState.reset();
            /*?}*/
            GpuExportBackendFactory.reset();
            // Minecraft.close runs before the render device is torn down. Drain
            // resources now; unfinished fences stay queued rather than being
            // accessed from an arbitrary worker thread.
            GpuExportBackendFactory.releasePendingOnRenderThread();
        } catch (Throwable t) {
            Flashbackplus.LOGGER.warn("Failed to clean Flashback Export Extras GPU resources during shutdown", t);
        }
    }
}
