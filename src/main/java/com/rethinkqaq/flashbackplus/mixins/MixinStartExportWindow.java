package com.rethinkqaq.flashbackplus.mixins;

import com.moulberry.flashback.configuration.FlashbackConfigV1;
import com.moulberry.flashback.state.EditorState;
import com.rethinkqaq.flashbackplus.FlashbackPlusConfig;
import imgui.moulberry90.ImGui;
import net.minecraft.client.resources.language.I18n;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GUI additions:
 * - Format selector: Video / OpenEXR Sequence (Depth)
 * - Camera path export checkbox + relative origin sub-option
 */
@Mixin(value = com.moulberry.flashback.editor.ui.windows.StartExportWindow.class, remap = false)
public class MixinStartExportWindow {

    // === Format selector: injected at start of renderVideoOptions ===

    @Inject(method = "renderVideoOptions", at = @At("HEAD"), remap = false, cancellable = true)
    private static void addFormatSelector(EditorState editorState, FlashbackConfigV1 config,
                                           CallbackInfo ci) {
        // Format radio buttons
        ImGui.separator();
        ImGui.text(I18n.get("flashbackplus.export_format") + ":");
        ImGui.sameLine();

        boolean isExr = FlashbackPlusConfig.INSTANCE.exportAsExr;
        if (ImGui.radioButton(I18n.get("flashbackplus.format_video"), !isExr)) {
            FlashbackPlusConfig.INSTANCE.exportAsExr = false;
            FlashbackPlusConfig.save();
        }
        ImGui.sameLine();
        if (ImGui.radioButton(I18n.get("flashbackplus.format_exr"), isExr)) {
            FlashbackPlusConfig.INSTANCE.exportAsExr = true;
            FlashbackPlusConfig.save();
        }

        if (FlashbackPlusConfig.INSTANCE.exportAsExr) {
            // Force container to PNG_SEQUENCE (triggers folder picker)
            config.internalExport.container =
                    com.moulberry.flashback.combo_options.VideoContainer.PNG_SEQUENCE;

            // Force SSAA off
            config.internalExport.ssaa = false;

            ImGui.spacing();
            ImGui.textWrapped(I18n.get("flashbackplus.exr_info"));

            // Depth linearization option
            boolean lin = FlashbackPlusConfig.INSTANCE.depthLinearizeWorldSpace;
            if (ImGui.checkbox(I18n.get("flashbackplus.linearize_depth"), lin)) {
                FlashbackPlusConfig.INSTANCE.depthLinearizeWorldSpace = !lin;
                FlashbackPlusConfig.save();
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(I18n.get("flashbackplus.linearize_depth_tooltip"));
            }

            // Skip normal renderVideoOptions (container dropdown, codecs, bitrate)
            ci.cancel();
        }
    }

    // === Camera path options: injected before the start/queue buttons ===

    @Inject(method = "render",
            at = @At(value = "INVOKE",
                    target = "Limgui/moulberry90/ImGui;dummy(FF)V"),
            remap = false)
    private static void addCameraPathOptions(CallbackInfo ci) {
        ImGui.separator();

        boolean exportCam = FlashbackPlusConfig.INSTANCE.exportCameraPath;
        if (ImGui.checkbox(I18n.get("flashbackplus.export_camera_path"), exportCam)) {
            FlashbackPlusConfig.INSTANCE.exportCameraPath = !exportCam;
            FlashbackPlusConfig.save();
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(I18n.get("flashbackplus.export_camera_path_tooltip"));
        }

        if (FlashbackPlusConfig.INSTANCE.exportCameraPath) {
            boolean rel = FlashbackPlusConfig.INSTANCE.cameraPathRelativeOrigin;
            if (ImGui.checkbox(I18n.get("flashbackplus.relative_camera_path"), rel)) {
                FlashbackPlusConfig.INSTANCE.cameraPathRelativeOrigin = !rel;
                FlashbackPlusConfig.save();
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(I18n.get("flashbackplus.relative_camera_path_tooltip"));
            }
        }
    }
}
