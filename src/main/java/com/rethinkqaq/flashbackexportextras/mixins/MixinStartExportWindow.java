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

import com.moulberry.flashback.configuration.FlashbackConfigV1;
import com.moulberry.flashback.combo_options.VideoCodec;
import com.moulberry.flashback.state.EditorState;
import com.rethinkqaq.flashbackexportextras.FlashbackPlusConfig;
import com.rethinkqaq.flashbackexportextras.FlashbackPlusConfig.ExportMode;
import com.rethinkqaq.flashbackexportextras.exporting.HdrExportState;
import com.rethinkqaq.flashbackexportextras.gpu.GpuExportBackendFactory;
import imgui.moulberry90.ImGui;
import net.minecraft.client.resources.language.I18n;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

import com.moulberry.flashback.exporting.ExportSettings;

/**
 * GUI additions:
 * - Format selector: Video / OpenEXR Sequence (Depth)
 * - Camera path export checkbox + relative origin sub-option
 */
@Mixin(value = com.moulberry.flashback.editor.ui.windows.StartExportWindow.class, remap = false)
public class MixinStartExportWindow {

    /** Trace the asynchronous folder/file selection before an ExportJob exists. */
    @Inject(method = "createExportSettings", at = @At("RETURN"), remap = false)
    private static void flashbackplus$traceExportSettings(String jobName, FlashbackConfigV1 config,
                                                           CallbackInfoReturnable<CompletableFuture<ExportSettings>> cir) {
        CompletableFuture<ExportSettings> future = cir.getReturnValue();
        com.rethinkqaq.flashbackexportextras.Flashbackplus.LOGGER.info(
                "Export settings request created: jobName={}, container={}, future={}",
                jobName, config.internalExport.container, future != null);
        if (future == null) {
            com.rethinkqaq.flashbackexportextras.Flashbackplus.LOGGER.warn(
                    "Export settings request returned null future");
            return;
        }
        future.whenComplete((settings, error) -> {
            if (error != null) {
                com.rethinkqaq.flashbackexportextras.Flashbackplus.LOGGER.error(
                        "Export settings future failed", error);
            } else if (settings == null) {
                com.rethinkqaq.flashbackexportextras.Flashbackplus.LOGGER.warn(
                        "Export settings future completed with null; export was cancelled or file dialog failed");
            } else {
                com.rethinkqaq.flashbackexportextras.Flashbackplus.LOGGER.info(
                        "Export settings ready: output={}, container={}, resolution={}x{}, framerate={}",
                        settings.output(), settings.container(), settings.resolutionX(), settings.resolutionY(),
                        settings.framerate());
            }
        });
    }

    // === Format selector: injected at start of renderVideoOptions ===

    @Inject(method = "renderVideoOptions", at = @At("HEAD"), remap = false, cancellable = true)
    private static void addFormatSelector(EditorState editorState, FlashbackConfigV1 config,
                                           CallbackInfo ci) {
        // Format radio buttons
        ImGui.separator();
        ImGui.text(I18n.get("flashbackplus.export_format") + ":");
        ImGui.sameLine();

        boolean isExr = FlashbackPlusConfig.INSTANCE.getExportMode() == ExportMode.EXR;
        if (ImGui.radioButton(I18n.get("flashbackplus.format_video"), !isExr)) {
            FlashbackPlusConfig.INSTANCE.setExportMode(ExportMode.VIDEO);
            FlashbackPlusConfig.save();
        }
        ImGui.sameLine();
        if (ImGui.radioButton(I18n.get("flashbackplus.format_exr"), isExr)) {
            FlashbackPlusConfig.INSTANCE.setExportMode(ExportMode.EXR);
            FlashbackPlusConfig.save();
        }

        if (FlashbackPlusConfig.INSTANCE.getExportMode() == ExportMode.EXR) {
            // Force container to PNG_SEQUENCE (triggers folder picker)
            config.internalExport.container =
                    com.moulberry.flashback.combo_options.VideoContainer.PNG_SEQUENCE;

            // Flashback still builds a complete ExportSettings object for a
            // PNG_SEQUENCE export before our ExportJob writer redirect runs.
            // Since EXR mode skips Flashback's normal codec controls, the
            // codec fields may otherwise remain null and createExportSettings
            // fails before the ExportJob is queued.
            if (config.internalExport.videoCodec == null) {
                config.internalExport.videoCodec = VideoCodec.H264;
            }
            if (config.internalExport.selectedVideoEncoder == null
                    || config.internalExport.selectedVideoEncoder.length == 0) {
                config.internalExport.selectedVideoEncoder = new int[]{0};
            }

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

            /*? if hdr {*/
            boolean sceneLinearHdrAvailable = HdrExportState.isAvailable()
                    && GpuExportBackendFactory.get().supportsSceneLinearHdr();
            if (sceneLinearHdrAvailable) {
                boolean sceneLinearHdr = FlashbackPlusConfig.INSTANCE.exrSceneLinearHdr;
                if (ImGui.checkbox(I18n.get("flashbackplus.exr_scene_linear_hdr"), sceneLinearHdr)) {
                    FlashbackPlusConfig.INSTANCE.exrSceneLinearHdr = !sceneLinearHdr;
                    FlashbackPlusConfig.save();
                }
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip(I18n.get("flashbackplus.exr_scene_linear_hdr_tooltip"));
                }
            } else if (FlashbackPlusConfig.INSTANCE.exrSceneLinearHdr) {
                ImGui.textWrapped(I18n.get("flashbackplus.exr_scene_linear_hdr_unavailable"));
            }
            /*?}*/

            // Skip normal renderVideoOptions (container dropdown, codecs, bitrate)
            ci.cancel();
            return;
        }

        /*? if hdr {*/
        // === HDR Export option (only shown when HDR Mod is available) ===
        if (HdrExportState.isAvailable() && GpuExportBackendFactory.get().supportsHdr()) {
            ImGui.spacing();
            boolean hdr = FlashbackPlusConfig.INSTANCE.getExportMode() == ExportMode.HDR10;
            if (ImGui.checkbox(I18n.get("flashbackplus.hdr_export"), hdr)) {
                FlashbackPlusConfig.INSTANCE.setExportMode(hdr ? ExportMode.VIDEO : ExportMode.HDR10);
                FlashbackPlusConfig.save();
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(I18n.get("flashbackplus.hdr_export_tooltip"));
            }

            if (FlashbackPlusConfig.INSTANCE.getExportMode() == ExportMode.HDR10) {
                // Peak brightness slider
                int[] peak = {FlashbackPlusConfig.INSTANCE.hdrPeakBrightness};
                if (ImGui.sliderInt(I18n.get("flashbackplus.hdr_peak_brightness"), peak, 500, 4000)) {
                    FlashbackPlusConfig.INSTANCE.hdrPeakBrightness = peak[0];
                    HdrExportState.setPeakBrightness((float) peak[0]);
                    FlashbackPlusConfig.save();
                }
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip(I18n.get("flashbackplus.hdr_peak_brightness_tooltip"));
                }

                // Paper white brightness slider
                int[] paperWhite = {FlashbackPlusConfig.INSTANCE.hdrPaperWhiteNits};
                if (ImGui.sliderInt(I18n.get("flashbackplus.hdr_paper_white"), paperWhite, 80, 500)) {
                    FlashbackPlusConfig.INSTANCE.hdrPaperWhiteNits = paperWhite[0];
                    FlashbackPlusConfig.save();
                }
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip(I18n.get("flashbackplus.hdr_paper_white_tooltip"));
                }
            }
        }
        /*?}*/
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
