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

import me.fallenbreath.conditionalmixin.api.mixin.RestrictiveMixinConfigPlugin;

import java.util.List;
import java.util.Set;

/** Combines Conditional Mixin restrictions with Stonecutter's Dummy targets. */
public final class FlashbackExportExtrasMixinConfigPlugin extends RestrictiveMixinConfigPlugin {
    private static final String DUMMY_TARGET = "com.rethinkqaq.flashbackexportextras.utils.Dummy";

    @Override public boolean shouldApplyMixin(String target, String mixin) {
        return isRealTarget(target) && super.shouldApplyMixin(target, mixin);
    }

    private static boolean isRealTarget(String target) { return !DUMMY_TARGET.equals(target); }

    @Override public String getRefMapperConfig() { return null; }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override public List<String> getMixins() { return null; }
}
