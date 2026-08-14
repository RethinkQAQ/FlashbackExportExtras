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

import com.moulberry.flashback.Utils;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.impl.FOVKeyframe;
import com.rethinkqaq.flashbackexportextras.exporting.DepthCaptureState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Captures FOV from Flashback's keyframe interpolation system, replacing the
 * old approach of reading from getProjectionMatrix().
 *
 * FOVKeyframe interpolates in FOCAL LENGTH space (non-linear), then converts
 * back to FOV. By capturing the post-interpolation value here, we get the
 * exact FOV that will be passed to getProjectionMatrix for rendering — but
 * captured at the correct granularity (per keyframe evaluation).
 */
@Mixin(value = FOVKeyframe.class, remap = false)
public class MixinFOVKeyframe {

    @Shadow
    private float fov;

    /** Store raw keyframe FOV for non-interpolated changes. */
    @Inject(method = "createChange", at = @At("HEAD"), remap = false)
    private void onCaptureFov(CallbackInfoReturnable<KeyframeChange> cir) {
        DepthCaptureState.keyframeTargetFov = this.fov;
    }

    /** Catmull-Rom spline interpolation (4 keyframe window) in focal length space. */
    @Inject(method = "createSmoothInterpolatedChange", at = @At("RETURN"), remap = false)
    private void onCaptureSmoothInterpolated(Keyframe p1, Keyframe p2, Keyframe p3,
                                              float t0, float t1, float t2, float t3,
                                              float amount, CallbackInfoReturnable<KeyframeChange> cir) {
        float time1 = t1 - t0;
        float time2 = t2 - t0;
        float time3 = t3 - t0;

        float f0 = Utils.fovToFocalLength(this.fov);
        float f1 = Utils.fovToFocalLength(((FOVKeyframe) (Object) p1).fov);
        float f2 = Utils.fovToFocalLength(((FOVKeyframe) (Object) p2).fov);
        float f3 = Utils.fovToFocalLength(((FOVKeyframe) (Object) p3).fov);

        float focalLength = com.moulberry.flashback.spline.CatmullRom.value(
                f0, f1, f2, f3, time1, time2, time3, amount);
        DepthCaptureState.keyframeTargetFov = Utils.focalLengthToFov(focalLength);
    }

    /** Hermite spline interpolation in focal length space. */
    @Inject(method = "createHermiteInterpolatedChange", at = @At("RETURN"), remap = false)
    private void onCaptureHermiteInterpolated(Map<Float, Keyframe> keyframes, float amount,
                                               CallbackInfoReturnable<KeyframeChange> cir) {
        float focalLength = (float) com.moulberry.flashback.spline.Hermite.value(
                com.google.common.collect.Maps.transformValues(keyframes,
                        k -> (double) Utils.fovToFocalLength(((FOVKeyframe) (Object) k).fov)),
                amount
        );
        DepthCaptureState.keyframeTargetFov = Utils.focalLengthToFov(focalLength);
    }
}
