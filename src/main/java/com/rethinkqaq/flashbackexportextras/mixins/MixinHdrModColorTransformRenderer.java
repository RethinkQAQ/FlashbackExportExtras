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

import com.rethinkqaq.flashbackexportextras.utils.Dummy;
import org.spongepowered.asm.mixin.Mixin;
//? if mc_26_1_2 {
import com.rethinkqaq.flashbackexportextras.gpu.HdrModColorTransformAccess;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
//?}

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
