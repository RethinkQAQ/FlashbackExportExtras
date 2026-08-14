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

import com.rethinkqaq.flashbackexportextras.Flashbackplus;
import com.rethinkqaq.flashbackexportextras.exporting.HdrExportState;
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
