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
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package com.rethinkqaq.flashbackexportextras.mixins;

import com.rethinkqaq.flashbackexportextras.exporting.IrisDepthCaptureState;
import me.fallenbreath.conditionalmixin.api.annotation.Condition;
import me.fallenbreath.conditionalmixin.api.annotation.Restriction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional Iris bridge. The mixin is only applied when Iris is loaded. */
@Restriction(require = @Condition(
        value = "iris",
        //? if >=26.1 {
        /*versionPredicates = ">=1.11 <1.12"
        *//*?} elif >=1.21.11 {*/
        /*versionPredicates = ">=1.10 <1.11"
        *//*?} elif >=1.21.8 {*/
        /*versionPredicates = ">=1.9 <1.10"
        *//*?} else {*/
        versionPredicates = ">=1.8 <1.9"
        /*?}*/
))
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public final class MixinIrisRenderingPipeline {
    @Inject(method = "beginLevelRendering", at = @At("HEAD"), remap = false)
    private void flashbackexportextras$markShaderPackPipeline(CallbackInfo ci) {
        IrisDepthCaptureState.markShaderPackPipelineActive();
    }
}
