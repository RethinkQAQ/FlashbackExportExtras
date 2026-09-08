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
 * You should have received a copy of the GNU Lesser General Public License along
 * with Flashback Export Extras. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package com.rethinkqaq.flashbackexportextras.mixins;

import com.rethinkqaq.flashbackexportextras.utils.Dummy;
import me.fallenbreath.conditionalmixin.api.annotation.Condition;
import me.fallenbreath.conditionalmixin.api.annotation.Restriction;
//? if >=26.2 {
/*
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.irisshaders.iris.vertices.ImmediateState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*/
//?} else {
import org.spongepowered.asm.mixin.Mixin;
//?}

@Restriction(require = {
        @Condition("iris"),
        @Condition(value = "minecraft", versionPredicates = ">=26.2 <26.3")
})
//? if >=26.2 {
/*@Mixin(targets = "com.mojang.blaze3d.opengl.GlDevice", priority = 2000, remap = false)
*/ //?} else {
@Mixin(Dummy.class)
//?}
public abstract class MixinIrisGlDevicePipelineBypass {
    //? if >=26.2 {
    /*
    @Unique
    private boolean flashbackexportextras$restoreIrisBypass;

    @Inject(method = "getOrCompilePipeline", at = @At("HEAD"))
    private void flashbackexportextras$enablePipelineBypass(
            RenderPipeline pipeline, CallbackInfoReturnable<GlRenderPipeline> cir) {
        if (isFlashbackExportExtrasPipeline(pipeline)) {
            flashbackexportextras$restoreIrisBypass = ImmediateState.bypass;
            ImmediateState.bypass = true;
        }
    }

    @Inject(method = "getOrCompilePipeline", at = @At("RETURN"))
    private void flashbackexportextras$restorePipelineBypass(
            RenderPipeline pipeline, CallbackInfoReturnable<GlRenderPipeline> cir) {
        if (isFlashbackExportExtrasPipeline(pipeline)) {
            ImmediateState.bypass = flashbackexportextras$restoreIrisBypass;
        }
    }

    @Unique
    private static boolean isFlashbackExportExtrasPipeline(RenderPipeline pipeline) {
        return pipeline != null
                && "flashbackexportextras".equals(pipeline.getLocation().getNamespace());
    }
    */
    //?}
}
