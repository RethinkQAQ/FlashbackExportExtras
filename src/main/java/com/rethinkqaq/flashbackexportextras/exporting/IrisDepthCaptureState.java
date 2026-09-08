/*
 * Flashback Export Extras
 * Copyright (C) RethinkQAQ
 *
 * This file is part of Flashback Export Extras.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package com.rethinkqaq.flashbackexportextras.exporting;

/**
 * Frame-local state for the optional Iris depth source.
 *
 * <p>This class intentionally contains no Iris classes.  The optional Mixin
 * only uses it to announce that a shaderpack pipeline is active. The export
 * path always captures the final Minecraft main render target before its
 * world-depth clear, because Iris' earlier {@code depthtex2} snapshot is not
 * a complete scene depth source.</p>
 */
public final class IrisDepthCaptureState {
    private static volatile boolean shaderPackPipelineActive;

    private IrisDepthCaptureState() {}

    public static void beginRenderFrame() {
        shaderPackPipelineActive = false;
    }

    public static void markShaderPackPipelineActive() {
        if (DepthCaptureState.active) shaderPackPipelineActive = true;
    }

    public static boolean isShaderPackPipelineActive() {
        return shaderPackPipelineActive;
    }

    public static void reset() {
        shaderPackPipelineActive = false;
    }
}
