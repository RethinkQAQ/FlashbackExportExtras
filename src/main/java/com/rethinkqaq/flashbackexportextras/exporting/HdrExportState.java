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
package com.rethinkqaq.flashbackexportextras.exporting;

/**
 * Shared mutable state for HDR export (similar to DepthCaptureState).
 *
 * HDR data flow:
 *   HDR Mod → RGBA16F main render target (scRGB-nl encoded)
 *   → GpuExportBackend (version-specific transform and readback)
 *   → frame-numbered capture state
 *   → HdrVideoWriter (rgba64 encoding)
 *
 * All fields are volatile — written by the render thread, read by the export/config threads.
 */
public class HdrExportState {

    /** Whether HDR Mod is installed (set once at client init). */
    private static volatile boolean hdrModLoaded = false;

    /** Whether HDR is enabled in HDR Mod's config (toggled in-game). */
    private static volatile boolean hdrModEnabled = false;

    /** Whether an HDR export is currently active. */
    private static volatile boolean active = false;

    /** Peak display brightness in nits (default 1000, configurable). */
    private static volatile float peakBrightness = 1000.0f;

    /** Width/height of the current export render target. */
    public static volatile int width;
    public static volatile int height;

    // === Availability checks ===

    /** HDR export can be offered in the UI (HDR Mod installed AND HDR enabled). */
    public static boolean isAvailable() {
        return hdrModLoaded && hdrModEnabled;
    }

    /** HDR export is currently running. */
    public static boolean isActive() {
        return active && isAvailable();
    }

    // === Getters / setters ===

    public static boolean isHdrModLoaded() { return hdrModLoaded; }
    public static void setHdrModLoaded(boolean v) { hdrModLoaded = v; }

    public static boolean isHdrModEnabled() { return hdrModEnabled; }
    public static void setHdrModEnabled(boolean v) { hdrModEnabled = v; }

    public static float getPeakBrightness() { return peakBrightness; }
    public static void setPeakBrightness(float v) { peakBrightness = v; }

    // === Activation ===

    public static void activate() {
        if (!isAvailable()) {
            throw new IllegalStateException("HDR export requires HDR Mod with HDR enabled");
        }
        active = true;
    }

    public static void deactivate() {
        active = false;
    }
}
